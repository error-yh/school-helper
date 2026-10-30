/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class XlsxSecurityTest {
    private val context=FormatParseContext("security-synthetic",30)
    private fun read(bytes:ByteArray,ctx:FormatParseContext=context)=XlsxWorkbookReader().read(bytes.inputStream(),ctx)
    private fun u16(bytes:ByteArray,at:Int)=(bytes[at].toInt() and 255) or ((bytes[at+1].toInt() and 255) shl 8)
    private fun u32(bytes:ByteArray,at:Int)=u16(bytes,at) or (u16(bytes,at+2) shl 16)
    private fun put(bytes:ByteArray,at:Int,value:Int,count:Int) { repeat(count) { bytes[at+it]=(value ushr (it*8)).toByte() } }
    private fun central(bytes:ByteArray):List<Int> {
        val end=bytes.size-22; var at=u32(bytes,end+16); val offsets=mutableListOf<Int>()
        repeat(u16(bytes,end+10)) { offsets+=at; at+=46+u16(bytes,at+28)+u16(bytes,at+30)+u16(bytes,at+32) }
        return offsets
    }
    @Test fun `central directory order does not override local part order or reject valid packages`() {
        val bytes=SyntheticXlsx.zip(SyntheticXlsx.parts()); val records=central(bytes)
        val output=ByteArrayOutputStream(); output.write(bytes,0,records.first())
        for(index in records.indices.reversed()) { val at=records[index]; val end=records.getOrNull(index+1) ?: bytes.size-22; output.write(bytes,at,end-at) }
        output.write(bytes,bytes.size-22,22)
        assertNotNull(read(output.toByteArray()).workbook)
    }
    @Test fun `binary duplicate names symlinks encryption methods ZIP64 and CRC disagreements are rejected`() {
        val parts=SyntheticXlsx.parts(); parts["a.xml"]="a".toByteArray(); parts["b.xml"]="b".toByteArray()
        val original=SyntheticXlsx.zip(parts); val records=central(original)
        val variants=mutableListOf<ByteArray>()
        variants+=original.clone().also { bytes -> val entry=records.last(); val local=u32(bytes,entry+42); bytes[entry+46]='a'.code.toByte(); bytes[local+30]='a'.code.toByte() }
        variants+=original.clone().also { put(it,records.last()+38,0xa1ff0000.toInt(),4) }
        variants+=original.clone().also { val entry=records.last(); put(it,entry+8,u16(it,entry+8) or 1,2) }
        variants+=original.clone().also { val entry=records.last(); put(it,entry+10,12,2); put(it,u32(it,entry+42)+8,12,2) }
        variants+=original.clone().also { put(it,records.last()+6,45,2) }
        variants+=original.clone().also { put(it,records.last()+20,-1,4) }
        variants+=original.clone().also { put(it,records.last()+16,123,4) }
        variants+=original.clone().also { put(it,it.size-22+4,1,2) }
        variants.forEach { val result=read(it); assertNull(result.workbook); assertTrue(result.issues.isNotEmpty()) }
    }
    @Test fun `actual inflation and ratio limits reject misleading declared sizes`() {
        val parts=SyntheticXlsx.parts(); parts["padding.bin"]=ByteArray(2000) { 'a'.code.toByte() }
        val bytes=SyntheticXlsx.zip(parts); val record=central(bytes).last(); val local=u32(bytes,record+42)
        val data=local+30+u16(bytes,local+26)+u16(bytes,local+28); val descriptor=data+u32(bytes,record+20)
        put(bytes,record+24,1,4); put(bytes,descriptor+12,1,4)
        val size=read(bytes,context.copy(limits=ImportLimits(maxInflatedEntryBytes=1200)))
        assertNull(size.workbook); assertEquals("LIMIT_EXCEEDED",size.issues.single().code)
        val ratio=read(SyntheticXlsx.zip(parts),context.copy(limits=ImportLimits(maxExpansionRatio=2,expansionRatioThreshold=100)))
        assertNull(ratio.workbook); assertEquals("LIMIT_EXCEEDED",ratio.issues.single().code)
    }
    @Test fun `bad references are semantic issues retaining bounded raw fields`() {
        val parts=SyntheticXlsx.parts(SyntheticXlsx.worksheet("""<row r="1"><c r="A1" t="s"><v>99</v></c><c r="B1" s="99"><v>1</v></c><c r="C1" t="b"><v>yes</v></c><c r="D1"><v>NaN</v></c><c r="E1" t="d"><v>2026-01-01T10:00:01</v></c></row>"""))
        val result=read(SyntheticXlsx.zip(parts)); val cells=result.workbook!!.sheets.single().table!!.rows.single().cells
        assertEquals(listOf("99","1","yes","NaN","2026-01-01T10:00:01"),cells.map { it.rawText })
        assertTrue(cells.all { it.typedValue==null && it.issues.isNotEmpty() }); assertEquals(5,result.issues.size)
    }
    @Test fun `covered literal data remains reviewable rather than acting as an independent value`() {
        val sheet=SyntheticXlsx.worksheet("""<row r="1"><c r="A1" t="inlineStr"><is><t>anchor</t></is></c><c r="B1" t="inlineStr"><is><t>covered</t></is></c></row>""").replace("</worksheet>","""<mergeCells><mergeCell ref="A1:B1"/></mergeCells></worksheet>""")
        val result=read(SyntheticXlsx.workbook(sheet)); val covered=result.workbook!!.sheets.single().table!!.rows.single().cells[1]
        assertEquals("covered",covered.rawText); assertEquals("A1",covered.mergeAnchor); assertNull(covered.typedValue)
        assertTrue(result.issues.any { it.code=="AMBIGUOUS_CELL_SPAN" })
    }
    @Test fun `cancellation during input inflation and SAX never returns partial payload or closes caller`() {
        val bytes=SyntheticXlsx.zip(SyntheticXlsx.parts()); var closed=false
        val stream=object:ByteArrayInputStream(bytes) { override fun close() { closed=true; super.close() } }
        var checks=0
        try { XlsxWorkbookReader().read(stream,context.copy(cancellation=ImportCancellation { if(++checks==25) throw CancellationException() })); fail("cancel") } catch(_:CancellationException) { }
        assertTrue(checks>=25); assertFalse(closed)
    }
    @Test fun `merge comparison work has a separate bounded budget without expanding rectangles`() {
        val rows=(1..6).joinToString("") { """<row r="$it"><c r="A$it"/></row>""" }
        val merges=(1..6).joinToString("") { """<mergeCell ref="A$it:B$it"/>""" }
        val sheet=SyntheticXlsx.worksheet(rows).replace("</worksheet>","<mergeCells>$merges</mergeCells></worksheet>")
        val result=read(SyntheticXlsx.workbook(sheet),context.copy(limits=ImportLimits(maxMergeComparisons=20)))
        assertNull(result.workbook); assertEquals("LIMIT_EXCEEDED",result.issues.single().code)
    }
    @Test fun `root level workbook relationship parts resolve package local sheets`() {
        val parts=linkedMapOf<String,ByteArray>()
        for((name,bytes) in SyntheticXlsx.parts()) {
            val key=when(name) { "custom/book.xml" -> "book.xml"; "custom/_rels/book.xml.rels" -> "_rels/book.xml.rels"; else -> name }
            var value=String(bytes).replace("/custom/book.xml","/book.xml")
            if(key=="_rels/book.xml.rels") value=value.replace("tables/one.xml","custom/tables/one.xml")
            parts[key]=value.toByteArray()
        }
        assertEquals("示例课",read(SyntheticXlsx.zip(parts)).workbook!!.sheets.single().table!!.rows.single().cells.single().rawText)
    }
    @Test fun `unknown internal sheet relationships remain unsupported visible descriptors`() {
        val parts=SyntheticXlsx.parts()
        parts["custom/_rels/book.xml.rels"]=String(parts.getValue("custom/_rels/book.xml.rels")).replace("${SyntheticXlsx.REL}/worksheet","urn:synthetic:unknown-sheet").toByteArray()
        val result=read(SyntheticXlsx.zip(parts)); val sheet=result.workbook!!.sheets.single()
        assertEquals(SheetKind.UNSUPPORTED,sheet.kind); assertEquals("sheet",sheet.relationshipId); assertNull(sheet.table)
        assertEquals("UNSUPPORTED_FORMAT",result.issues.single().code)
    }
    @Test fun `unreferenced macro sheet content types are rejected as forbidden package parts`() {
        val parts=SyntheticXlsx.parts(); parts["custom/macro.xml"]="synthetic".toByteArray()
        parts["[Content_Types].xml"]=String(parts.getValue("[Content_Types].xml")).replace("</Types>","""<Override PartName="/custom/macro.xml" ContentType="application/vnd.ms-excel.macrosheet+xml"/></Types>""").toByteArray()
        val result=read(SyntheticXlsx.zip(parts)); assertNull(result.workbook); assertEquals("UNSUPPORTED_FORMAT",result.issues.single().code)
    }
    @Test fun `element names are bounded before ignored same namespace callbacks build paths`() {
        val name="z".repeat(129)
        val sheet=SyntheticXlsx.worksheet("").replace("</worksheet>","<$name/></worksheet>")
        val result=read(SyntheticXlsx.workbook(sheet),context.copy(limits=ImportLimits(maxFieldUnits=128)))
        assertNull(result.workbook); assertEquals("LIMIT_EXCEEDED",result.issues.single().code)
    }
    @Test fun `attribute names are bounded even when the attribute is unknown and empty`() {
        val name="z".repeat(129)
        val sheet=SyntheticXlsx.worksheet("").replace("<sheetData>","<sheetData $name=\"\">")
        val result=read(SyntheticXlsx.workbook(sheet),context.copy(limits=ImportLimits(maxFieldUnits=128)))
        assertNull(result.workbook); assertEquals("LIMIT_EXCEEDED",result.issues.single().code)
    }
    @Test fun `many bounded XML names share the global text work budget`() {
        val name="z".repeat(90)
        val sheet=SyntheticXlsx.worksheet("").replace("</worksheet>","<$name/>".repeat(200)+"</worksheet>")
        val result=read(SyntheticXlsx.workbook(sheet),context.copy(limits=ImportLimits(maxFieldUnits=128,maxTextUnits=16_000)))
        assertNull(result.workbook); assertEquals("LIMIT_EXCEEDED",result.issues.single().code)
    }
}
