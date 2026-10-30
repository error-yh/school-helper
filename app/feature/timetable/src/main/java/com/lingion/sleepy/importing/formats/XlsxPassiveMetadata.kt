/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import org.xml.sax.Attributes

internal const val XLSX_X15 = "http://schemas.microsoft.com/office/spreadsheetml/2010/11/main"
internal const val XLSX_X15AC = "http://schemas.microsoft.com/office/spreadsheetml/2010/11/ac"
internal const val XLSX_X14 = "http://schemas.microsoft.com/office/spreadsheetml/2009/9/main"
internal const val XLSX_XR = "http://schemas.microsoft.com/office/spreadsheetml/2014/revision"
internal const val XLSX_XR2 = "http://schemas.microsoft.com/office/spreadsheetml/2015/revision2"
private const val XR6 = "http://schemas.microsoft.com/office/spreadsheetml/2016/revision6"
private const val XR10 = "http://schemas.microsoft.com/office/spreadsheetml/2016/revision10"
private const val CALC = "http://schemas.microsoft.com/office/spreadsheetml/2018/calcfeatures"

/** Exact passive producer shapes, not an MCE processor. No descendants reach literal consumers. */
internal class XlsxPassiveMetadata {
    private enum class Kind { ALTERNATE, CHOICE, ABS_PATH, REVISION, EXT_LIST, EXT, WORKBOOK_PR, CALC_FEATURES, FEATURE, SLICER, TIMELINE }
    private class Node(val kind:Kind,val guid:String?=null) { var children=0; val keys=mutableSetOf<String>() }
    private val frames=mutableListOf<Node?>()
    val skipping get()=frames.lastOrNull()!=null
    fun start(uri:String,local:String,path:List<String>,attrs:Attributes,resolve:(String)->String?):Boolean {
        val parent=frames.lastOrNull()
        val root=path.firstOrNull()
        fun isName(expectedUri:String,expectedLocal:String)=uri==expectedUri && local==expectedLocal
        val node=if(parent!=null) {
            parent.children++
            when(parent.kind) {
                Kind.ALTERNATE -> if(isName(XLSX_MC,"Choice")) Node(Kind.CHOICE) else reject()
                Kind.CHOICE -> if(isName(XLSX_X15AC,"absPath")) Node(Kind.ABS_PATH) else reject()
                Kind.EXT_LIST -> {
                    if(!isName(XLSX_MAIN,"ext")) reject()
                    val guid=attrs.required("uri")
                    if(!parent.keys.add(guid)) reject()
                    val valid=if(root=="workbook") setOf("{140A7094-0E35-4892-8432-C4D2E57EDEB5}","{B58B0392-4F1F-4190-BB64-5DF3571DCE5F}")
                        else setOf("{EB79DEF2-80B8-43e5-95BD-54CBDDF9020C}","{9260A510-F301-46a8-8635-F512D64BE5F5}")
                    if(guid !in valid) reject()
                    Node(Kind.EXT,guid)
                }
                Kind.EXT -> when(parent.guid) {
                    "{140A7094-0E35-4892-8432-C4D2E57EDEB5}" -> if(isName(XLSX_X15,"workbookPr")) Node(Kind.WORKBOOK_PR) else reject()
                    "{B58B0392-4F1F-4190-BB64-5DF3571DCE5F}" -> if(isName(CALC,"calcFeatures")) Node(Kind.CALC_FEATURES) else reject()
                    "{EB79DEF2-80B8-43e5-95BD-54CBDDF9020C}" -> if(isName(XLSX_X14,"slicerStyles")) Node(Kind.SLICER) else reject()
                    "{9260A510-F301-46a8-8635-F512D64BE5F5}" -> if(isName(XLSX_X15,"timelineStyles")) Node(Kind.TIMELINE) else reject()
                    else -> reject()
                }
                Kind.CALC_FEATURES -> if(isName(CALC,"feature")) Node(Kind.FEATURE) else reject()
                else -> reject()
            }
        } else when {
            path==listOf("workbook") && isName(XLSX_MC,"AlternateContent") -> Node(Kind.ALTERNATE)
            path==listOf("workbook") && isName(XLSX_XR,"revisionPtr") -> Node(Kind.REVISION)
            path.size==1 && root in setOf("workbook","styleSheet") && isName(XLSX_MAIN,"extLst") -> Node(Kind.EXT_LIST)
            else -> null
        }
        if(node!=null) {
            val plain=when(node.kind) {
                Kind.CHOICE -> setOf("Requires")
                Kind.ABS_PATH -> setOf("url")
                Kind.REVISION -> setOf("revIDLastSave","documentId")
                Kind.EXT -> setOf("uri")
                Kind.WORKBOOK_PR -> setOf("chartTrackingRefBase")
                Kind.FEATURE -> setOf("name")
                Kind.SLICER -> setOf("defaultSlicerStyle")
                Kind.TIMELINE -> setOf("defaultTimelineStyle")
                else -> emptySet()
            }
            val extra=when(node.kind) {
                Kind.ALTERNATE -> setOf(XLSX_MC to "Ignorable")
                Kind.REVISION -> setOf(XR6 to "coauthVersionLast",XR6 to "coauthVersionMax",XR10 to "uidLastSave")
                else -> emptySet()
            }
            for(i in 0 until attrs.length) if(!((attrs.getURI(i).isEmpty() && attrs.getLocalName(i) in plain) || (attrs.getURI(i) to attrs.getLocalName(i)) in extra)) reject()
            if(node.kind==Kind.CHOICE) {
                val prefixes=attrs.required("Requires").split(Regex("[ \\t\\r\\n]+")).filter { it.isNotEmpty() }
                if(prefixes.size!=1 || resolve(prefixes.single())!=XLSX_X15) reject()
            }
        }
        frames+=node
        return node!=null
    }
    fun end():Boolean {
        val node=frames.removeAt(frames.lastIndex) ?: return false
        if(node.kind in setOf(Kind.ALTERNATE,Kind.CHOICE,Kind.EXT) && node.children!=1) reject()
        return true
    }
}
