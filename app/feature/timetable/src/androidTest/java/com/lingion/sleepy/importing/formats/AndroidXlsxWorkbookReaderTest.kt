/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CancellationException
import javax.xml.parsers.SAXParserFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against the device SAX/ZIP implementation; entirely invented in-memory input. */
@RunWith(AndroidJUnit4::class)
class AndroidXlsxWorkbookReaderTest {
    private val context=FormatParseContext("android-synthetic",30)
    private fun read(parts:Map<String,ByteArray>,ctx:FormatParseContext=context)=XlsxWorkbookReader().read(SyntheticXlsx.zip(parts).inputStream(),ctx)
    @Test fun actualAndroidProviderInstallsRequiredFeatures() {
        val factory=SAXParserFactory.newInstance(); factory.isNamespaceAware=true
        val reader=factory.newSAXParser().xmlReader
        assertEquals("org.apache.harmony.xml.ExpatReader",reader.javaClass.name)
        for(feature in listOf("external-general-entities","external-parameter-entities")) {
            reader.setFeature("http://xml.org/sax/features/$feature",false)
            assertFalse(reader.getFeature("http://xml.org/sax/features/$feature"))
        }
        assertNotNull(read(SyntheticXlsx.parts()).workbook)
    }
    @Test fun utf16XmlLiteralChineseWorkbookIsSupported() {
        val parts=SyntheticXlsx.parts()
        parts["custom/tables/one.xml"]=String(parts.getValue("custom/tables/one.xml")).replace("UTF-8","UTF-16").toByteArray(Charsets.UTF_16)
        val result=read(parts)
        assertTrue(result.issues.isEmpty())
        assertEquals("示例课",result.workbook!!.sheets.single().table!!.rows.single().cells.single().rawText)
    }
    @Test fun doctypeWithoutEntitiesFailsClosed() {
        rejectDoctype("<!DOCTYPE worksheet>")
    }
    @Test fun internalEntityDtdFailsClosedBeforeExpansion() {
        rejectDoctype("<!DOCTYPE worksheet [<!ENTITY x 'synthetic'>]>")
    }
    @Test fun externalSubsetAndGeneralAndParameterEntitiesAreNeverResolved() {
        for(dtd in listOf("<!DOCTYPE worksheet SYSTEM 'https://invalid/never'>", "<!DOCTYPE worksheet [<!ENTITY x SYSTEM 'file:///never'>]>", "<!DOCTYPE worksheet [<!ENTITY % x SYSTEM 'https://invalid/never'> %x;]>")) rejectDoctype(dtd)
    }
    private fun rejectDoctype(dtd:String) {
        for(charset in listOf(Charsets.UTF_8,Charsets.UTF_16)) {
            val encoding=if(charset==Charsets.UTF_8) "UTF-8" else "UTF-16"
            val parts=SyntheticXlsx.parts()
            parts["custom/tables/one.xml"]="""<?xml version="1.0" encoding="$encoding"?>$dtd<worksheet xmlns="${SyntheticXlsx.MAIN}"><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>&amp;synthetic</t></is></c></row></sheetData></worksheet>""".toByteArray(charset)
            val result=read(parts); assertNull(result.workbook); assertEquals("INVALID_STRUCTURE",result.issues.single().code)
        }
    }
    @Test fun namespaceXincludeXmlBaseAndBudgetsAreEnforcedOnDevice() {
        for(xml in listOf("""<worksheet xmlns="${SyntheticXlsx.MAIN}" xml:base="file:///never"><sheetData/></worksheet>""", """<worksheet xmlns="${SyntheticXlsx.MAIN}" xmlns:xi="http://www.w3.org/2001/XInclude"><xi:include href="https://invalid/never"/></worksheet>""")) assertNull(read(SyntheticXlsx.parts(xml)).workbook)
        val result=read(SyntheticXlsx.parts(),context.copy(limits=ImportLimits(maxXmlDepth=2)))
        assertNull(result.workbook); assertEquals("LIMIT_EXCEEDED",result.issues.single().code)
    }
    @Test fun formulaCachesStayUnverifiedAndCancellationDoesNotCloseCallerStream() {
        val parts=SyntheticXlsx.parts(SyntheticXlsx.worksheet("""<row r="1"><c r="A1"><f>1+1</f><v>2</v></c></row>"""))
        val result=read(parts); val cell=result.workbook!!.sheets.single().table!!.rows.single().cells.single()
        assertNull(cell.typedValue); assertEquals("2",cell.rawText); assertTrue(result.issues.any { it.code=="FORMULA_UNSUPPORTED" })
        var closed=false
        val stream=object:ByteArrayInputStream(SyntheticXlsx.zip(parts)) { override fun close() { closed=true; super.close() } }
        try { XlsxWorkbookReader().read(stream,context.copy(cancellation=ImportCancellation { throw CancellationException() })); fail("cancel") } catch(_:CancellationException) { }
        assertFalse(closed)
    }
    @Test fun passiveMcMetadataUsesActualScopedUrisOnAndroidIncludingUtf16() {
        for(utf16 in listOf(false,true)) {
            val parts=SyntheticXlsx.passiveMetadataParts()
            if(utf16) for((name,bytes) in parts.toMap()) parts[name]=String(bytes).replace("UTF-8","UTF-16").toByteArray(Charsets.UTF_16)
            val result=read(parts); assertTrue(result.issues.isEmpty())
            assertEquals("示例课",result.workbook!!.sheets.single().table!!.rows.single().cells.single().rawText)
        }
        val original=SyntheticXlsx.passiveMetadataParts()
        for(xml in listOf(
            String(original.getValue("custom/tables/one.xml")).replace("passive:dyDescent","passive:unknown"),
            String(original.getValue("custom/tables/one.xml")).replace("<row r=\"1\"", "<row xmlns:passive=\"urn:synthetic:rebound\" r=\"1\""),
            String(original.getValue("custom/tables/one.xml")).replace("<sheetData>","<compat:AlternateContent/><sheetData>"),
        )) {
            val parts=LinkedHashMap(original); parts["custom/tables/one.xml"]=xml.toByteArray()
            val result=read(parts); assertNull(result.workbook); assertEquals("INVALID_STRUCTURE",result.issues.single().code)
        }
    }
    @Test fun knownTypedCellsWithoutAnyPayloadRemainExplicitEmptyCellsOnAndroid() {
        val body=listOf("s","b","d","inlineStr","str","n").mapIndexed { index,type -> """<c r="${('A'.code+index).toChar()}1" t="$type"/>""" }.joinToString("")
        val result=read(SyntheticXlsx.parts(SyntheticXlsx.worksheet("<row r=\"1\">$body</row>")))
        assertEquals("EMPTY_TABLE",result.issues.single().code)
        assertTrue(result.workbook!!.sheets.single().table!!.rows.single().cells.all { it.typedValue==CellValue.Empty && it.issues.isEmpty() })
    }
    @Test fun exactBooleanLexicalValuesAreTypedWithoutCaseCoercionOnAndroid() {
        val values=listOf("0","1","false","true","yes","True")
        val body=values.mapIndexed { index,value -> """<c r="${('A'.code+index).toChar()}1" t="b"><v>$value</v></c>""" }.joinToString("")
        val cells=read(SyntheticXlsx.parts(SyntheticXlsx.worksheet("<row r=\"1\">$body</row>"))).workbook!!.sheets.single().table!!.rows.single().cells
        assertEquals(listOf(false,true,false,true),cells.take(4).map { (it.typedValue as CellValue.BooleanValue).value })
        assertTrue(cells.drop(4).all { it.typedValue==null && it.issues.single().code=="INVALID_CELL_VALUE" })
    }
    @Test fun actualSanitizedExcelProducerFixtureKeepsLiteralValuesAndReviewedTimesOnAndroid() {
        val bytes=InstrumentationRegistry.getInstrumentation().context.assets.open("excel-produced-synthetic.xlsx").use { it.readBytes() }
        assertEquals("9c0ff5816a868da4e00b0a59f0c1412b6eb1482bf7ef8612668159eca07cbb71",MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val result=XlsxWorkbookReader().read(bytes.inputStream(),context)
        assertNotNull(result.issues.toString(),result.workbook)
        val rows=result.workbook!!.sheets.single().table!!.rows
        fun cell(row:Int,column:Int)=rows.single { it.number==row }.cells.single { it.column==column }
        assertEquals("1,3,5,8",cell(2,3).rawText)
        assertEquals("0.33333333333333331",cell(2,6).rawText)
        assertEquals(LocalTime.of(8,0),(cell(2,6).typedValue as CellValue.Number).temporal!!.time)
        assertEquals(LocalTime.of(13,0),(cell(3,6).typedValue as CellValue.Number).temporal!!.time)
        assertEquals(LocalTime.of(14,30),(cell(3,7).typedValue as CellValue.Number).temporal!!.time)
        assertEquals(LocalDate.of(2027,1,1),(cell(3,5).typedValue as CellValue.Number).temporal!!.date)
        assertEquals(setOf("XLSX_PASSIVE_METADATA_IGNORED","XLSX_TIME_SERIAL_NORMALIZED"),result.issues.map { it.code }.toSet())
    }
    @Test fun canonicalBinary64PolicyRejectsDistinctNeighborsSecondsAndMidnightUnderflowOnAndroid() {
        val minute=480.0/1440.0
        for(raw in listOf(java.lang.Double.toString(Math.nextUp(minute)),java.lang.Double.toString(Math.nextDown(minute)),java.lang.Double.toString(minute+1.0/86400.0),"1e-9999")) {
            try { ExcelCellFormat.temporal(BigDecimal(raw),NumberFormatKind.TIME,false); fail("invalid synthetic minute") } catch(failure:ImportRejected) { assertEquals("INVALID_EXCEL_DATE",failure.code) }
        }
        assertEquals(LocalTime.of(23,59),ExcelCellFormat.temporal(BigDecimal(java.lang.Double.toString(1439.0/1440.0)),NumberFormatKind.TIME,false).time)
        assertEquals(LocalDate.of(1904,1,1),ExcelCellFormat.temporal(BigDecimal("0.33333333333333331"),NumberFormatKind.DATE_TIME,true).date)
    }
}
