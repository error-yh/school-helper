/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class XlsxWorkbookReaderTest {
    private val context = FormatParseContext("synthetic-xlsx", 30)
    private fun read(parts: Map<String, ByteArray> = SyntheticXlsx.parts(), ctx: FormatParseContext = context) =
        XlsxWorkbookReader().read(SyntheticXlsx.zip(parts).inputStream(), ctx)
    private fun rejected(parts: Map<String, ByteArray>, code: String = "INVALID_STRUCTURE") {
        val result = read(parts); assertNull(result.workbook); assertEquals(code, result.issues.single().code)
    }
    @Test fun `content types and relationships select root relative custom workbook without filename guessing`() {
        val result = read(); assertTrue(result.issues.isEmpty())
        val book = result.workbook!!; assertFalse(book.date1904)
        val sheet = book.sheets.single(); assertEquals("示例表", sheet.name)
        assertEquals("sheet", sheet.relationshipId); assertEquals(SheetVisibility.VISIBLE, sheet.visibility)
        val cell = sheet.table!!.rows.single().cells.single()
        assertEquals("示例课", cell.rawText); assertEquals(CellValue.Text("示例课"), cell.typedValue)
        assertEquals("sheet:1!A1", cell.sourceLocator)
    }
    @Test fun `sparse literal rich shared inline and empty values retain positions and ignore phonetics`() {
        val parts = SyntheticXlsx.parts(SyntheticXlsx.worksheet("""<row r="3"><c r="B3" t="s"><v>0</v></c><c r="D3" t="inlineStr"><is><r><t xml:space="preserve"> 甲 </t></r><rPh sb="0" eb="1"><t>jia</t></rPh><r><t>乙</t></r></is></c><c r="E3"/><c r="F3" t="inlineStr"><is><t/></is></c><c r="G3" t="b"><v>1</v></c><c r="H3" t="str"><v>=literal</v></c></row>"""))
        parts["custom/_rels/book.xml.rels"] = String(parts.getValue("custom/_rels/book.xml.rels")).replace("</Relationships>", """<Relationship Id="ss" Type="${SyntheticXlsx.REL}/sharedStrings" Target="../strings.xml"/></Relationships>""").toByteArray()
        parts["strings.xml"] = SyntheticXlsx.xml("""<sst xmlns="${SyntheticXlsx.MAIN}"><si><r><t>示例</t></r><rPh sb="0" eb="1"><t>hidden</t></rPh><r><t>课程</t></r></si></sst>""").toByteArray()
        parts["[Content_Types].xml"] = String(parts.getValue("[Content_Types].xml")).replace("</Types>", """<Override PartName="/strings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/></Types>""").toByteArray()
        val row = read(parts).workbook!!.sheets.single().table!!.rows.single()
        assertEquals(3, row.number); assertEquals(listOf(2,4,5,6,7,8), row.cells.map { it.column })
        assertEquals(listOf("示例课程", " 甲 乙", "", "", "1", "=literal"), row.cells.map { it.rawText })
        assertEquals(CellValue.Empty, row.cells[2].typedValue); assertEquals(CellValue.Text(""), row.cells[3].typedValue)
        assertEquals(CellValue.BooleanValue(true), row.cells[4].typedValue)
    }
    @Test fun `formulas preserve private expression and raw cache but never expose verified typed values`() {
        val result = read(SyntheticXlsx.parts(SyntheticXlsx.worksheet("""<row r="1"><c r="C1"><f>1+1</f><v>2</v></c><c r="D1" t="e"><v>#REF!</v></c><c r="E1" t="unknown"><v>raw</v></c></row>""")))
        val cells = result.workbook!!.sheets.single().table!!.rows.single().cells
        assertEquals("2", cells[0].rawText); assertEquals("1+1", cells[0].formula); assertNull(cells[0].typedValue)
        assertEquals("FORMULA_UNSUPPORTED", cells[0].issues.single().code)
        assertTrue(result.issues.any { it.code == "FORMULA_UNSUPPORTED" }); assertNull(cells[1].typedValue); assertNull(cells[2].typedValue)
    }
    @Test fun `workbook lists hidden very hidden unsupported and empty sheet descriptors`() {
        val parts = SyntheticXlsx.parts(SyntheticXlsx.worksheet(""))
        parts["custom/book.xml"] = SyntheticXlsx.xml("""<workbook xmlns="${SyntheticXlsx.MAIN}" xmlns:r="${SyntheticXlsx.REL}"><sheets><sheet name="隐藏" sheetId="1" state="hidden" r:id="sheet"/><sheet name="深藏" sheetId="2" state="veryHidden" r:id="chart"/></sheets></workbook>""").toByteArray()
        parts["custom/_rels/book.xml.rels"] = String(parts.getValue("custom/_rels/book.xml.rels")).replace("</Relationships>", """<Relationship Id="chart" Type="${SyntheticXlsx.REL}/chartsheet" Target="tables/chart.xml"/></Relationships>""").toByteArray()
        parts["[Content_Types].xml"] = String(parts.getValue("[Content_Types].xml")).replace("</Types>", """<Override PartName="/custom/tables/chart.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.chartsheet+xml"/></Types>""").toByteArray()
        parts["custom/tables/chart.xml"] = """<chartsheet xmlns="${SyntheticXlsx.MAIN}"/>""".toByteArray()
        val result = read(parts); val sheets = result.workbook!!.sheets
        assertEquals(listOf(SheetVisibility.HIDDEN, SheetVisibility.VERY_HIDDEN), sheets.map { it.visibility })
        assertEquals(SheetKind.UNSUPPORTED, sheets[1].kind); assertNull(sheets[1].table)
        assertTrue(sheets[0].issues.any { it.code == "EMPTY_TABLE" })
    }
    @Test fun `true merges retain anchors without expanding or filling data rows`() {
        val sheet = SyntheticXlsx.worksheet("""<row r="1"><c r="A1" t="inlineStr"><is><t>header</t></is></c></row><row r="2"><c r="B2"/></row>""").replace("</worksheet>", """<mergeCells count="1"><mergeCell ref="A1:B2"/></mergeCells></worksheet>""")
        val result = read(SyntheticXlsx.parts(sheet)); val data = result.workbook!!.sheets.single()
        assertEquals(MergeRegion(1,1,2,2), data.merges.single()); assertEquals(2, data.table!!.rows.sumOf { it.cells.size })
        assertEquals("A1", data.table!!.rows[1].cells.single().mergeAnchor)
        assertEquals("", data.table!!.rows[1].cells.single().rawText)
        rejected(SyntheticXlsx.parts(sheet.replace("</mergeCells>", """<mergeCell ref="B2:C3"/></mergeCells>""")))
    }
    @Test fun `explicit styles retain numeric raw and bounded temporal capabilities with epochs`() {
        val parts = SyntheticXlsx.parts(SyntheticXlsx.worksheet("""<row r="1"><c r="A1" s="1"><v>40729</v></c><c r="B1" s="2"><v>0.5</v></c><c r="C1" s="3"><v>40729.5</v></c><c r="D1"><v>40729</v></c><c r="E1" s="1"><v>60</v></c><c r="F1" s="4"><v>2</v></c><c r="G1" t="d"><v>2026-10-24T10:30:00</v></c></row>"""))
        parts["custom/_rels/book.xml.rels"] = String(parts.getValue("custom/_rels/book.xml.rels")).replace("</Relationships>", """<Relationship Id="styles" Type="${SyntheticXlsx.REL}/styles" Target="styles.xml"/></Relationships>""").toByteArray()
        parts["custom/styles.xml"] = SyntheticXlsx.xml("""<styleSheet xmlns="${SyntheticXlsx.MAIN}"><numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy-mm-dd h:mm"/><numFmt numFmtId="165" formatCode="[h]:mm"/></numFmts><cellXfs count="5"><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="20"/><xf numFmtId="164"/><xf numFmtId="165"/></cellXfs></styleSheet>""").toByteArray()
        parts["[Content_Types].xml"] = String(parts.getValue("[Content_Types].xml")).replace("</Types>", """<Override PartName="/custom/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""").toByteArray()
        val cells = read(parts).workbook!!.sheets.single().table!!.rows.single().cells
        val date = cells[0].typedValue as CellValue.Number
        assertEquals(BigDecimal("40729"), date.number); assertEquals(LocalDate.of(2011,7,5), date.temporal!!.date)
        assertEquals(LocalTime.NOON, (cells[1].typedValue as CellValue.Number).temporal!!.time)
        assertEquals(LocalTime.NOON, (cells[2].typedValue as CellValue.Number).temporal!!.time)
        assertNull((cells[3].typedValue as CellValue.Number).temporal)
        assertTrue(cells[4].issues.any { it.code == "INVALID_EXCEL_DATE" }); assertTrue(cells[5].issues.any { it.code == "UNSUPPORTED_NUMBER_FORMAT" })
        assertEquals(LocalTime.of(10,30), (cells[6].typedValue as CellValue.Temporal).value.time)
    }
    @Test fun `date helper rejects leap day seconds overflow ambiguity and unsupported elapsed formats`() {
        val date = ExcelCellFormat.classify(14, null)
        for ((serial, expected) in listOf("1" to "1900-01-01", "59" to "1900-02-28", "61" to "1900-03-01")) {
            assertEquals(LocalDate.parse(expected), ExcelCellFormat.temporal(BigDecimal(serial), date, false).date)
        }
        assertEquals(LocalDate.of(1904,1,1), ExcelCellFormat.temporal(BigDecimal.ZERO,date,true).date)
        assertEquals(LocalDate.of(2011,7,5), ExcelCellFormat.temporal(BigDecimal("39267"),date,true).date)
        for (serial in listOf("60", "-1", "999999999999999999999", "1.00001")) {
            try { ExcelCellFormat.temporal(BigDecimal(serial),date,false); fail("invalid serial") } catch (_: ImportRejected) { }
        }
        assertEquals(NumberFormatKind.DATE, ExcelCellFormat.classify(164,"yyyy\"年\"m\"月\"d\"日\""))
        assertEquals(NumberFormatKind.TIME, ExcelCellFormat.classify(164,"hh:mm:ss"))
        for (format in listOf("m", "[h]:mm", "h:mm AM/PM", "[Red]yyyy-mm-dd", "yyyy-mm-dd;0")) assertEquals(NumberFormatKind.UNSUPPORTED, ExcelCellFormat.classify(164,format))
    }
    @Test fun `relationship target normalization rejects external encodings schemes and escape but allows internal parent`() {
        val original = SyntheticXlsx.parts()
        for (target in listOf("../../outside.xml", "//evil/a", "https://invalid/x", "C:/x", "tables\\one.xml", "%2e%2e/x", "tables/one.xml#x", "tables/one.xml?x")) {
            val parts = LinkedHashMap(original)
            parts["custom/_rels/book.xml.rels"] = String(original.getValue("custom/_rels/book.xml.rels")).replace("tables/one.xml", target).toByteArray()
            rejected(parts, "EXTERNAL_REFERENCE_REJECTED")
        }
        val external = LinkedHashMap(original)
        external["custom/_rels/book.xml.rels"] = String(original.getValue("custom/_rels/book.xml.rels")).replace("Target=", "TargetMode=\"External\" Target=").toByteArray()
        rejected(external,"EXTERNAL_REFERENCE_REJECTED")
    }
    @Test fun `zip signatures forbidden parts truncation and structural xml errors fail without payload`() {
        val originals = SyntheticXlsx.parts()
        for (name in listOf("../escape", "/absolute", "C:/drive", "a\\b", "a//b", "a/%2e%2e/b", "custom/vbaProject.bin", "custom/activeX/a.xml", "custom/embeddings/a.bin", "custom/externalLinks/a.xml")) {
            val parts = LinkedHashMap(originals); parts[name] = byteArrayOf(1); assertNull(read(parts).workbook)
        }
        assertNull(XlsxWorkbookReader().read(byteArrayOf(0xd0.toByte(),0xcf.toByte(),0x11,0xe0.toByte()).inputStream(),context).workbook)
        val zip = SyntheticXlsx.zip(originals); assertNull(XlsxWorkbookReader().read(zip.copyOf(zip.size-7).inputStream(),context).workbook)
        rejected(SyntheticXlsx.parts("<worksheet>"))
        rejected(SyntheticXlsx.parts(SyntheticXlsx.worksheet("""<row r="1"><c r="A1"/><c r="A1"/></row>""")))
        rejected(SyntheticXlsx.parts(SyntheticXlsx.worksheet("""<row r="1"><c r="A2"/></row>""")))
        val emptyBook = LinkedHashMap(originals); emptyBook["custom/book.xml"] = """<workbook xmlns="${SyntheticXlsx.MAIN}"><sheets/></workbook>""".toByteArray(); rejected(emptyBook)
    }
    @Test fun `DTD internal external entities and xinclude are rejected even in UTF16`() {
        for (doctype in listOf("<!DOCTYPE worksheet>", "<!DOCTYPE worksheet [<!ENTITY x 'literal'>]>", "<!DOCTYPE worksheet SYSTEM 'https://invalid/never'>", "<!DOCTYPE worksheet [<!ENTITY x SYSTEM 'file:///never'>]>")) {
            val xml = """<?xml version="1.0" encoding="UTF-16"?>$doctype<worksheet xmlns="${SyntheticXlsx.MAIN}"><sheetData/></worksheet>"""
            val parts = SyntheticXlsx.parts(); parts["custom/tables/one.xml"] = xml.toByteArray(Charsets.UTF_16); rejected(parts)
        }
        rejected(SyntheticXlsx.parts("""<worksheet xmlns="${SyntheticXlsx.MAIN}" xml:base="file:///never"><sheetData/></worksheet>"""))
        rejected(SyntheticXlsx.parts("""<worksheet xmlns="${SyntheticXlsx.MAIN}" xmlns:xi="http://www.w3.org/2001/XInclude"><xi:include href="file:///never"/></worksheet>"""))
    }
    @Test fun `limits are actual counted and dimensions never allocate dense grid`() {
        val parts = SyntheticXlsx.parts(SyntheticXlsx.worksheet("""<row r="1"><c r="A1" t="inlineStr"><is><t>12345</t></is></c></row>""").replace("<sheetData>","""<dimension ref="A1:XFD100000"/><sheetData>"""))
        assertEquals(1,read(parts).workbook!!.sheets.single().table!!.rows.single().cells.size)
        val limits = listOf(ImportLimits(maxInputBytes=10),ImportLimits(maxZipEntries=2),ImportLimits(maxInflatedEntryBytes=40),ImportLimits(maxInflatedBytes=100),ImportLimits(maxXmlDepth=2),ImportLimits(maxXmlAttributes=1),ImportLimits(maxXmlEvents=5),ImportLimits(maxFieldUnits=4),ImportLimits(maxTextUnits=10))
        limits.forEach { val result=read(parts,context.copy(limits=it)); assertNull(result.workbook); assertEquals("LIMIT_EXCEEDED",result.issues.single().code) }
    }
    @Test fun `caller owns stream cancellation propagates and IO diagnostics are constant`() {
        var closed=false
        val stream=object: ByteArrayInputStream(SyntheticXlsx.zip(SyntheticXlsx.parts())) { override fun close() { closed=true; super.close() } }
        assertNotNull(XlsxWorkbookReader().read(stream,context).workbook); assertFalse(closed)
        try { read(ctx=context.copy(cancellation=ImportCancellation { throw CancellationException("synthetic") })); fail("cancel") } catch (_: CancellationException) { }
        val broken=object: InputStream() { override fun read(): Int=throw IOException("private synthetic path"); override fun read(b:ByteArray,off:Int,len:Int):Int=read() }
        val result=XlsxWorkbookReader().read(broken,context); assertNull(result.workbook); assertEquals("IMPORT_UNREADABLE",result.issues.single().code); assertFalse(result.issues.toString().contains("private synthetic"))
    }
}
