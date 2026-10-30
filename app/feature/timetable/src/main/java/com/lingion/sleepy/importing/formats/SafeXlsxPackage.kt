/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.ext.DefaultHandler2

internal const val XLSX_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
internal const val XLSX_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
internal const val OPC_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
internal const val OPC_CT = "http://schemas.openxmlformats.org/package/2006/content-types"
internal const val XML_NS = "http://www.w3.org/XML/1998/namespace"
internal const val XLSX_MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
internal const val XLSX_X14AC = "http://schemas.microsoft.com/office/spreadsheetml/2009/9/ac"
internal const val XLSX_CT = "application/vnd.openxmlformats-officedocument.spreadsheetml."
internal data class PackageRelationship(val id: String, val type: String, val target: String)

/** Bounded memory-only ZIP/OPC. No extraction, filesystem targets, URLs or formula engine. */
internal class SafeXlsxPackage private constructor(
    private val parts: Map<String, ByteArray>, private val context: FormatParseContext,
) {
    private val limits = context.limits
    private var events = 0
    private var xmlUnits = 0
    private val parsedRelationships = mutableMapOf<String, List<PackageRelationship>>()
    private val contentTypes = mutableMapOf<String, String>()
    private val defaultTypes = mutableMapOf<String, String>()
    var passiveMetadataIgnored = false
        private set

    fun contentType(part: String): String? = contentTypes[part] ?: defaultTypes[part.substringAfterLast('.', "")]
    fun contains(part: String) = parts.containsKey(part)
    fun relationships(owner: String): List<PackageRelationship> = parsedRelationships[owner].orEmpty()

    fun workbookPart(): String {
        val roots = relationships("").filter { it.type == "$XLSX_REL/officeDocument" }
        if (roots.size != 1) reject()
        val workbook = roots.single().target
        if (contentType(workbook) != "${XLSX_CT}sheet.main+xml") reject("UNSUPPORTED_FORMAT")
        return workbook
    }

    /** Namespace whitelist + global budgets apply even to ignored nodes/attributes. */
    fun xml(part: String, namespace: String, start: (List<String>, Attributes) -> Unit = { _, _ -> },
            text: (List<String>, String) -> Unit = { _, _ -> }, end: (List<String>) -> Unit = {}) {
        val bytes = parts[part] ?: reject()
        val path = mutableListOf<String>()
        val bindings = mutableMapOf("xml" to mutableListOf(XML_NS))
        val ignorableScopes = mutableListOf<Map<String,String>>()
        val passiveMetadata = XlsxPassiveMetadata()
        fun event() { context.cancellation.check(); if (++events > limits.maxXmlEvents) reject("LIMIT_EXCEEDED") }
        fun units(count: Int) { if (count > limits.maxTextUnits - xmlUnits) reject("LIMIT_EXCEEDED"); xmlUnits += count }
        fun name(value: String) { if(value.length>limits.maxFieldUnits) reject("LIMIT_EXCEEDED"); units(value.length) }
        val handler = object : DefaultHandler2() {
            override fun startElement(uri: String, localName: String, qName: String, attrs: Attributes) {
                event()
                // Check names before any consumer inspects the path or retains attributes.
                name(localName); name(qName)
                if (localName.isEmpty()) reject()
                if (path.size >= limits.maxXmlDepth || attrs.length > limits.maxXmlAttributes) reject("LIMIT_EXCEEDED")
                for (i in 0 until attrs.length) {
                    name(attrs.getLocalName(i)); name(attrs.getQName(i))
                    if (attrs.getValue(i).length > limits.maxFieldUnits) reject("LIMIT_EXCEEDED")
                    units(attrs.getValue(i).length)
                }
                val inherited = ignorableScopes.lastOrNull().orEmpty()
                // An inherited declaration cannot be made to name another namespace by shadowing.
                for ((prefix, expected) in inherited) if (bindings[prefix]?.lastOrNull() != expected) reject()
                val declaration = attrs.getValue(XLSX_MC,"Ignorable")
                val effective = if (declaration == null) inherited else {
                    if (namespace != XLSX_MAIN) reject()
                    inherited.toMutableMap().also { scope ->
                        for (prefix in declaration.split(Regex("[ \\t\\r\\n]+" )).filter { it.isNotEmpty() }) {
                            val actual = bindings[prefix]?.lastOrNull()?.takeIf { it.isNotEmpty() } ?: reject()
                            scope[prefix] = actual
                        }
                    }
                }
                val skipped = namespace == XLSX_MAIN && passiveMetadata.start(uri,localName,path,attrs) { bindings[it]?.lastOrNull() }
                if (!skipped && uri != namespace) reject()
                if(skipped) passiveMetadataIgnored=true
                for (i in 0 until attrs.length) {
                    val attributeUri = attrs.getURI(i); val attributeName = attrs.getLocalName(i)
                    val passive = namespace == XLSX_MAIN && attributeUri == XLSX_X14AC && XLSX_X14AC in effective.values &&
                        ((attributeName == "dyDescent" && localName in setOf("row","sheetFormatPr")) || (attributeName == "knownFonts" && localName == "fonts"))
                    val metadata = namespace == XLSX_MAIN && attributeUri == XLSX_MC && attributeName == "Ignorable"
                    val producerUid = namespace == XLSX_MAIN && attributeName == "uid" && attributeUri in effective.values &&
                        ((attributeUri == XLSX_XR && path.isEmpty() && localName == "worksheet") ||
                         (attributeUri == XLSX_XR2 && path == listOf("workbook","bookViews") && localName == "workbookView"))
                    if(producerUid) passiveMetadataIgnored=true
                    if (!skipped && !passive && !metadata && !producerUid && (attributeUri !in setOf("", XML_NS, XLSX_REL) || (attributeUri == XML_NS && attributeName != "space"))) reject()
                }
                ignorableScopes += effective
                path += localName; if(!skipped) start(path, attrs)
            }
            override fun endElement(uri: String, localName: String, qName: String) { event(); if(namespace != XLSX_MAIN || !passiveMetadata.end()) end(path); path.removeAt(path.lastIndex); ignorableScopes.removeAt(ignorableScopes.lastIndex) }
            override fun characters(ch: CharArray, offset: Int, length: Int) {
                event(); units(length)
                if(passiveMetadata.skipping) { for(i in offset until offset+length) if(ch[i] !in charArrayOf(' ','\t','\r','\n')) reject() }
                else text(path, String(ch, offset, length))
            }
            override fun ignorableWhitespace(ch: CharArray, start: Int, length: Int) { characters(ch,start,length) }
            override fun processingInstruction(target: String, data: String) { event(); reject() }
            override fun startPrefixMapping(prefix: String, uri: String) { event(); name(prefix); name(uri); bindings.getOrPut(prefix) { mutableListOf() }.add(uri) }
            override fun endPrefixMapping(prefix: String) { event(); val stack=bindings[prefix] ?: reject(); stack.removeAt(stack.lastIndex); if(stack.isEmpty()) bindings.remove(prefix) }
            override fun skippedEntity(name: String) { reject() }
            override fun startDTD(name: String, publicId: String?, systemId: String?) { reject() }
            override fun resolveEntity(publicId: String?, systemId: String?): InputSource { reject("EXTERNAL_REFERENCE_REJECTED") }
            override fun resolveEntity(name: String?, publicId: String?, baseURI: String?, systemId: String?): InputSource { reject("EXTERNAL_REFERENCE_REJECTED") }
            override fun comment(ch: CharArray, start: Int, length: Int) { event(); units(length) }
            override fun startCDATA() { event() }
            override fun endCDATA() { event() }
            override fun warning(e: SAXParseException) { reject() }
            override fun error(e: SAXParseException) { reject() }
            override fun fatalError(e: SAXParseException) { reject() }
        }
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            factory.isValidating = false
            val reader = factory.newSAXParser().xmlReader
            for ((feature, value) in listOf("namespaces" to true, "external-general-entities" to false, "external-parameter-entities" to false, "validation" to false)) {
                val name = "http://xml.org/sax/features/$feature"
                reader.setFeature(name,value)
                if (reader.getFeature(name) != value) reject()
            }
            // Mandatory on JVM and Android Expat. Unsupported installation fails closed.
            // No provider-specific Apache feature is assumed, or caught and ignored.
            val lexical = "http://xml.org/sax/properties/lexical-handler"
            reader.setProperty(lexical,handler)
            if (reader.getProperty(lexical) !== handler) reject()
            reader.entityResolver = handler; reader.contentHandler = handler; reader.errorHandler = handler
            reader.parse(InputSource(bytes.inputStream()))
        } catch (_: ParserConfigurationException) { reject() }
        catch (_: SAXException) { reject() }
        catch (_: IOException) { reject("IMPORT_UNREADABLE") }
    }

    private fun initialize() {
        xml("[Content_Types].xml", OPC_CT, start = { path, attrs ->
            when (path) {
                listOf("Types") -> Unit
                listOf("Types","Default") -> {
                    val extension = attrs.required("Extension")
                    if (!extension.matches(Regex("[A-Za-z0-9]+")) || defaultTypes.put(extension,attrs.required("ContentType")) != null) reject()
                }
                listOf("Types","Override") -> {
                    val part = resolveTarget("",attrs.required("PartName"))
                    if (contentTypes.put(part,attrs.required("ContentType")) != null || !parts.containsKey(part)) reject()
                }
                else -> reject()
            }
        })
        if (contentTypes.values.plus(defaultTypes.values).any { forbiddenType(it) }) reject("UNSUPPORTED_FORMAT")
        for (name in parts.keys.filter { it.endsWith(".rels") }) {
            val owner = if (name == "_rels/.rels") "" else if(name.startsWith("_rels/") && name.count { it=='/' }==1) {
                name.removePrefix("_rels/").removeSuffix(".rels")
            } else {
                val slash = name.lastIndexOf("/_rels/")
                if (slash < 0 || !name.endsWith(".rels")) reject()
                name.substring(0,slash + 1) + name.substring(slash + 7).removeSuffix(".rels")
            }
            if (owner.isNotEmpty() && !parts.containsKey(owner)) reject()
            val relationships = mutableListOf<PackageRelationship>()
            val ids = mutableSetOf<String>()
            xml(name,OPC_REL,start = { path, attrs ->
                when (path) {
                    listOf("Relationships") -> Unit
                    listOf("Relationships","Relationship") -> {
                        val id = attrs.required("Id"); if (!ids.add(id)) reject()
                        if (attrs.getValue("TargetMode")?.let { it != "Internal" } == true) reject("EXTERNAL_REFERENCE_REJECTED")
                        val type = attrs.required("Type")
                        if (type.substringAfterLast('/').lowercase(Locale.ROOT) in setOf("externallink", "externallinkpath", "vbaproject", "oleobject", "control", "activexcontrol", "package", "macrosheet", "xlmacrosheet", "xlintlmacrosheet")) reject("EXTERNAL_REFERENCE_REJECTED")
                        val target = resolveTarget(owner,attrs.required("Target"))
                        if (!parts.containsKey(target)) reject()
                        relationships += PackageRelationship(id,type,target)
                    }
                    else -> reject()
                }
            })
            if (parsedRelationships.put(owner,relationships.toList()) != null) reject()
        }
    }

    companion object {
        fun read(input: InputStream, context: FormatParseContext): SafeXlsxPackage {
            val buffer = ByteArray(8192); val output = ByteArrayOutputStream()
            try {
                while (true) {
                    context.cancellation.check()
                    val count = input.read(buffer,0,minOf(buffer.size,context.limits.maxInputBytes-output.size()+1))
                    if (count < 0) break
                    if (count == 0) {
                        val one = input.read(); if (one < 0) break
                        if (output.size() == context.limits.maxInputBytes) reject("LIMIT_EXCEEDED")
                        output.write(one)
                    } else {
                        if (count > context.limits.maxInputBytes-output.size()) reject("LIMIT_EXCEEDED")
                        output.write(buffer,0,count)
                    }
                }
            } catch (_: IOException) { reject("IMPORT_UNREADABLE") }
            return SafeXlsxPackage(unzip(output.toByteArray(),context),context).also { it.initialize() }
        }

        /** OPC internal roots and safe parent references are package keys, never filesystem paths. */
        internal fun resolveTarget(owner: String, target: String): String {
            if (target.isEmpty() || target.startsWith("//") || target.any { it == '\\' || it == '\u0000' || it == '%' || it == '?' || it == '#' || it == ':' || it.isISOControl() }) reject("EXTERNAL_REFERENCE_REJECTED")
            val stack = if (target.startsWith('/')) mutableListOf() else owner.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
            val segments = target.removePrefix("/").split('/')
            if (segments.any { it.isEmpty() }) reject("EXTERNAL_REFERENCE_REJECTED")
            for (segment in segments) when (segment) {
                "." -> Unit
                ".." -> { if (stack.isEmpty()) reject("EXTERNAL_REFERENCE_REJECTED"); stack.removeAt(stack.lastIndex) }
                else -> stack += segment
            }
            if (stack.isEmpty()) reject("EXTERNAL_REFERENCE_REJECTED")
            return stack.joinToString("/")
        }
        private fun forbiddenType(type: String): Boolean {
            val lower = type.lowercase(Locale.ROOT)
            return listOf("macro", "vbaproject", "activex", "oleobject", "externallink").any { it in lower }
        }
        private fun zipName(name: String): String {
            val key = name.removeSuffix("/")
            if (key.isEmpty() || key.startsWith('/') || key.any { it == '\\' || it == '\u0000' || it == '%' || it == ':' || it == '?' || it == '#' || it.isISOControl() } || key.split('/').any { it.isEmpty() || it == "." || it == ".." }) reject()
            val lower = key.lowercase(Locale.ROOT)
            if (lower.split('/').any { it in setOf("activex", "embeddings", "externallinks") } || "vbaproject" in lower || lower.endsWith(".xlsm")) reject("UNSUPPORTED_FORMAT")
            return key
        }
        /** Central-directory and local-header agreement is checked before any inflation.
         * ZIP64, multi-disk, encryption, Unix links and unsupported methods fail closed.
         */
        private fun unzip(bytes: ByteArray, context: FormatParseContext): Map<String, ByteArray> {
            val limits = context.limits
            fun u16(offset: Int): Int { if (offset < 0 || offset > bytes.size-2) reject(); return (bytes[offset].toInt() and 255) or ((bytes[offset+1].toInt() and 255) shl 8) }
            fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset+2).toLong() shl 16)
            fun index(value: Long): Int { if (value < 0 || value > bytes.size) reject(); return value.toInt() }
            fun name(offset: Int, size: Int): String {
                if (offset < 0 || size <= 0 || offset.toLong()+size > bytes.size) reject()
                if (size > limits.maxFieldUnits) reject("LIMIT_EXCEEDED")
                return try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,offset,size)).toString() }
                catch (_: CharacterCodingException) { reject() }
            }
            fun extra(offset: Int, size: Int) {
                var at = offset; val end = offset.toLong()+size
                if (end > bytes.size) reject()
                while (at < end) { if (at+4 > end) reject(); val id=u16(at); val count=u16(at+2); if (id==1) reject("UNSUPPORTED_FORMAT"); at += 4+count; if (at > end) reject() }
            }
            if (bytes.size < 22 || u32(0) != 0x04034b50L) reject("UNSUPPORTED_FORMAT")
            var eocd = bytes.size-22
            while (eocd >= maxOf(0,bytes.size-65557) && (u32(eocd) != 0x06054b50L || eocd+22+u16(eocd+20) != bytes.size)) eocd--
            if (eocd < maxOf(0,bytes.size-65557)) reject()
            if (u16(eocd+4)!=0 || u16(eocd+6)!=0 || u16(eocd+8)!=u16(eocd+10)) reject("UNSUPPORTED_FORMAT")
            val count=u16(eocd+10); val cdSize=u32(eocd+12); val cdOffset=u32(eocd+16)
            if (count==65535 || cdSize==0xffffffffL || cdOffset==0xffffffffL || (eocd>=20 && u32(eocd-20)==0x07064b50L)) reject("UNSUPPORTED_FORMAT")
            if (count==0) reject(); if (count>limits.maxZipEntries) reject("LIMIT_EXCEEDED")
            if (cdOffset+cdSize!=eocd.toLong()) reject()
            var at=index(cdOffset); var expectedLocal=0
            val directory=mutableListOf<Int>()
            repeat(count) {
                if(u32(at)!=0x02014b50L) reject()
                directory+=at
                at=index(at.toLong()+46+u16(at+28)+u16(at+30)+u16(at+32))
            }
            if(at!=eocd) reject()
            val result=linkedMapOf<String,ByteArray>(); val names=mutableSetOf<String>()
            var total=0L; var compressedTotal=0L
            // Central records need not be ordered like their local entries (ZIP APPNOTE).
            for (record in directory.sortedBy { u32(it+42) }) {
                val at=record
                context.cancellation.check()
                if (u32(at)!=0x02014b50L) reject()
                val flags=u16(at+8); val method=u16(at+10); val crc=u32(at+16); val compressed=u32(at+20); val declared=u32(at+24)
                val nameSize=u16(at+28); val extraSize=u16(at+30); val commentSize=u16(at+32); val local=index(u32(at+42))
                if (compressed==0xffffffffL || declared==0xffffffffL || u16(at+34)!=0 || u16(at+6)>20) reject("UNSUPPORTED_FORMAT")
                if (flags and 0xf7f1 != 0 || method !in setOf(0,8)) reject("UNSUPPORTED_FORMAT")
                val mode=((u32(at+38) ushr 16).toInt() and 0xf000)
                if (mode !in setOf(0,0x8000,0x4000)) reject("UNSUPPORTED_FORMAT")
                val raw=name(at+46,nameSize); val key=zipName(raw); if (!names.add(key)) reject()
                extra(at+46+nameSize,extraSize)
                index(at.toLong()+46+nameSize+extraSize+commentSize)
                if (local!=expectedLocal || u32(local)!=0x04034b50L || u16(local+6)!=flags || u16(local+8)!=method) reject()
                val localNameSize=u16(local+26); val localExtraSize=u16(local+28)
                if (name(local+30,localNameSize)!=raw) reject()
                extra(local+30+localNameSize,localExtraSize)
                val data=index(local.toLong()+30+localNameSize+localExtraSize); val dataEnd=index(data.toLong()+compressed)
                if (dataEnd>cdOffset) reject()
                if (flags and 8 == 0 && (u32(local+14)!=crc || u32(local+18)!=compressed || u32(local+22)!=declared)) reject()
                expectedLocal=dataEnd
                if (flags and 8 != 0) {
                    val descriptor=if (u32(dataEnd)==0x08074b50L) dataEnd+4 else dataEnd
                    if (u32(descriptor)!=crc || u32(descriptor+4)!=compressed || u32(descriptor+8)!=declared) reject()
                    expectedLocal=index(descriptor.toLong()+12)
                }
                val output=ByteArrayOutputStream(); val check=CRC32()
                fun accept(buffer:ByteArray,n:Int,consumed:Long) {
                    context.cancellation.check()
                    if (n>limits.maxInflatedEntryBytes-output.size() || total+n>limits.maxInflatedBytes) reject("LIMIT_EXCEEDED")
                    val size=output.size().toLong()+n
                    if (size>limits.expansionRatioThreshold && size>maxOf(1L,consumed)*limits.maxExpansionRatio) reject("LIMIT_EXCEEDED")
                    if (total+n>limits.expansionRatioThreshold && total+n>maxOf(1L,compressedTotal+consumed)*limits.maxExpansionRatio) reject("LIMIT_EXCEEDED")
                    output.write(buffer,0,n); check.update(buffer,0,n); total+=n
                }
                if (method==0) {
                    var pos=data
                    while(pos<dataEnd) { val n=minOf(8192,dataEnd-pos); accept(bytes.copyOfRange(pos,pos+n),n,(pos-data+n).toLong()); pos+=n }
                } else {
                    val inflater=Inflater(true)
                    try {
                        inflater.setInput(bytes,data,index(compressed)); val buffer=ByteArray(8192)
                        while (!inflater.finished()) {
                            context.cancellation.check()
                            val n=inflater.inflate(buffer)
                            if (n==0 && !inflater.finished()) reject()
                            accept(buffer,n,inflater.bytesRead)
                        }
                        if (inflater.bytesRead!=compressed) reject()
                    } catch (_: DataFormatException) { reject() } finally { inflater.end() }
                }
                if (output.size().toLong()!=declared || check.value!=crc) reject()
                compressedTotal+=compressed
                if (raw.endsWith('/')) { if (output.size()!=0) reject() } else result[key]=output.toByteArray()
            }
            if (at!=eocd || expectedLocal.toLong()!=cdOffset) reject()
            return result
        }
    }
}

internal fun Attributes.required(name: String): String = getValue("",name)?.takeIf { it.isNotEmpty() } ?: reject()
