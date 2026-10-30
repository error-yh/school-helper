/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Invented in-memory OPC fixtures only; no real timetable or filesystem input. */
object SyntheticXlsx {
    const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val PKG = "http://schemas.openxmlformats.org/package/2006/relationships"
    const val CT = "http://schemas.openxmlformats.org/package/2006/content-types"
    const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    const val X14AC = "http://schemas.microsoft.com/office/spreadsheetml/2009/9/ac"
    fun xml(body: String) = """<?xml version="1.0" encoding="UTF-8"?>$body"""
    fun worksheet(body: String) = xml("""<worksheet xmlns="$MAIN"><sheetData>$body</sheetData></worksheet>""")
    fun parts(sheet: String = worksheet("""<row r="1"><c r="A1" t="inlineStr"><is><t>示例课</t></is></c></row>""")): LinkedHashMap<String, ByteArray> {
        val values = linkedMapOf(
            "[Content_Types].xml" to xml("""<Types xmlns="$CT"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Override PartName="/custom/book.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/custom/tables/one.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""),
            "_rels/.rels" to xml("""<Relationships xmlns="$PKG"><Relationship Id="book" Type="$REL/officeDocument" Target="/custom/book.xml"/></Relationships>"""),
            "custom/book.xml" to xml("""<workbook xmlns="$MAIN" xmlns:r="$REL"><workbookPr date1904="0"/><sheets><sheet name="示例表" sheetId="1" r:id="sheet"/></sheets></workbook>"""),
            "custom/_rels/book.xml.rels" to xml("""<Relationships xmlns="$PKG"><Relationship Id="sheet" Type="$REL/worksheet" Target="tables/one.xml"/></Relationships>"""),
            "custom/tables/one.xml" to sheet,
        )
        return LinkedHashMap(values.mapValues { it.value.toByteArray() })
    }
    fun zip(parts: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip -> parts.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
    }.toByteArray()
    fun workbook(sheet: String) = zip(parts(sheet))
    fun withStyles(sheet: String, styles: String): LinkedHashMap<String, ByteArray> = parts(sheet).also { parts ->
        parts["custom/styles.xml"] = styles.toByteArray()
        parts["custom/_rels/book.xml.rels"] = String(parts.getValue("custom/_rels/book.xml.rels")).replace("</Relationships>", """<Relationship Id="styles" Type="$REL/styles" Target="styles.xml"/></Relationships>""").toByteArray()
        parts["[Content_Types].xml"] = String(parts.getValue("[Content_Types].xml")).replace("</Types>", """<Override PartName="/custom/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""").toByteArray()
    }
    fun passiveMetadataParts(): LinkedHashMap<String, ByteArray> {
        val sheet = """<worksheet xmlns="$MAIN" xmlns:compat="$MC" xmlns:passive="$X14AC" compat:Ignorable="passive"><sheetFormatPr passive:dyDescent="0.25"/><sheetData><row r="1" passive:dyDescent="0.25"><c r="A1" t="inlineStr"><is><t>示例课</t></is></c></row></sheetData></worksheet>"""
        val styles = """<styleSheet xmlns="$MAIN" xmlns:mc="$MC" xmlns:ac="$X14AC" mc:Ignorable="ac"><fonts ac:knownFonts="1"/><cellXfs><xf numFmtId="0"/></cellXfs></styleSheet>"""
        return withStyles(sheet, styles).also { parts ->
            parts["custom/book.xml"] = String(parts.getValue("custom/book.xml")).replace("<workbook ","""<workbook xmlns:mc="$MC" xmlns:unused="urn:synthetic:unused" mc:Ignorable="unused" """).toByteArray()
        }
    }
}
