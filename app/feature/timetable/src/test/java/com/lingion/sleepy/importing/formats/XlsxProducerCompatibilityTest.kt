/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test

class XlsxProducerCompatibilityTest {
    private val context=FormatParseContext("excel-producer-synthetic",30)
    @Test fun `actual Excel literal workbook preserves source cells and typed date times`() {
        val result=javaClass.classLoader!!.getResourceAsStream("excel-produced-synthetic.xlsx")!!.use { XlsxWorkbookReader().read(it,context) }
        assertNotNull(result.issues.toString(),result.workbook)
        val sheet=result.workbook!!.sheets.single()
        assertEquals("Synthetic courses",sheet.name)
        val rows=sheet.table!!.rows
        assertEquals(3,rows.size)
        fun cell(row:Int,column:Int)=rows.single { it.number==row }.cells.single { it.column==column }
        assertEquals("Synthetic mathematics",cell(2,1).rawText)
        assertEquals("1,3,5,8",cell(2,3).rawText)
        assertEquals("1,3",cell(2,4).rawText)
        assertEquals("0.33333333333333331",cell(2,6).rawText)
        assertEquals(LocalTime.of(8,0),(cell(2,6).typedValue as CellValue.Number).temporal!!.time)
        assertEquals(LocalTime.of(9,0),(cell(2,7).typedValue as CellValue.Number).temporal!!.time)
        assertEquals(LocalTime.of(13,0),(cell(3,6).typedValue as CellValue.Number).temporal!!.time)
        assertEquals(LocalTime.of(14,30),(cell(3,7).typedValue as CellValue.Number).temporal!!.time)
        assertEquals(LocalDate.of(2027,1,1),(cell(3,5).typedValue as CellValue.Number).temporal!!.date)
        assertEquals(setOf("XLSX_PASSIVE_METADATA_IGNORED","XLSX_TIME_SERIAL_NORMALIZED"),result.issues.map { it.code }.toSet())
    }
    private fun cell(raw:String,format:String="hh:mm",date1904:Boolean=false):ImportCell {
        val sheet=SyntheticXlsx.worksheet("""<row r="1"><c r="A1" s="0"><v>$raw</v></c></row>""")
        val styles="""<styleSheet xmlns="${SyntheticXlsx.MAIN}"><numFmts><numFmt numFmtId="164" formatCode="$format"/></numFmts><cellXfs><xf numFmtId="164"/></cellXfs></styleSheet>"""
        val parts=SyntheticXlsx.withStyles(sheet,styles)
        if(date1904) parts["custom/book.xml"]=String(parts.getValue("custom/book.xml")).replace("date1904=\"0\"","date1904=\"1\"").toByteArray()
        return XlsxWorkbookReader().read(SyntheticXlsx.zip(parts).inputStream(),context).workbook!!.sheets.single().table!!.rows.single().cells.single()
    }
    @Test fun `Excel IEEE minute serials normalize only canonical doubles and keep original decimals`() {
        for((raw,time) in listOf("0.33333333333333331" to LocalTime.of(8,0),"0.54166666666666663" to LocalTime.of(13,0),"0.60416666666666663" to LocalTime.of(14,30),java.lang.Double.toString(1439.0/1440.0) to LocalTime.of(23,59))) {
            val c=cell(raw); val value=c.typedValue as CellValue.Number
            assertEquals(raw,c.rawText); assertEquals(BigDecimal(raw),value.number)
            assertEquals(raw,time,value.temporal?.time)
            assertEquals("XLSX_TIME_SERIAL_NORMALIZED",c.issues.single().code)
        }
        assertTrue(cell("0.375").issues.isEmpty())
    }
    @Test fun `date time canonical doubles retain day and both Excel epochs`() {
        for((day,epoch,date) in listOf(Triple(40729,false,LocalDate.of(2011,7,5)),Triple(39267,true,LocalDate.of(2011,7,5)))) {
            val raw=java.lang.Double.toString(day.toDouble()+480.0/1440.0)
            val c=cell(raw,"yyyy-mm-dd hh:mm",epoch)
            assertEquals(ExcelTemporalValue(date,LocalTime.of(8,0)),(c.typedValue as CellValue.Number).temporal)
        }
        assertEquals(LocalDate.of(1904,1,1),(cell("0.33333333333333331","yyyy-mm-dd hh:mm",true).typedValue as CellValue.Number).temporal?.date)
    }
    @Test fun `seconds adjacent IEEE values carry and fake leap day are never rounded into minutes`() {
        val minute=480.0/1440.0
        val values=listOf(java.lang.Double.toString(Math.nextUp(minute)),java.lang.Double.toString(Math.nextDown(minute)),java.lang.Double.toString(minute+1.0/86400.0),java.lang.Double.toString(Math.nextDown(1.0)),"1","-0.00001","1e-9999")
        for(raw in values) { val c=cell(raw); assertNull(raw,(c.typedValue as? CellValue.Number)?.temporal); assertTrue(raw,c.issues.any { it.code=="INVALID_EXCEL_DATE" }) }
        val leap=cell("60.333333333333336","yyyy-mm-dd hh:mm")
        assertNull((leap.typedValue as CellValue.Number).temporal); assertEquals("INVALID_EXCEL_DATE",leap.issues.single().code)
        val nearMidnight=cell("40729.000000000000001","yyyy-mm-dd hh:mm")
        assertNull((nearMidnight.typedValue as CellValue.Number).temporal)
        assertEquals("INVALID_EXCEL_DATE",nearMidnight.issues.single().code)
    }
    @Test fun `literal escaped Excel date format is explicitly supported`() {
        assertEquals(NumberFormatKind.DATE,ExcelCellFormat.classify(177,"yyyy\\-mm\\-dd"))
    }
    private fun producerParts():Map<String,ByteArray> = linkedMapOf<String,ByteArray>().also { parts ->
        ZipInputStream(javaClass.classLoader!!.getResourceAsStream("excel-produced-synthetic.xlsx")!!).use { zip ->
            while(true) { val entry=zip.nextEntry ?: break; parts[entry.name]=zip.readBytes() }
        }
    }
    @Test fun `passive producer shapes reject unknown attributes children directives locations and text`() {
        val original=producerParts()
        val workbook=String(original.getValue("xl/workbook.xml"))
        val variants=listOf(
            workbook.replace("<mc:Choice Requires=","<mc:Choice unknown=\"1\" Requires="),
            workbook.replace("</mc:Choice>","<sheet xmlns=\"${SyntheticXlsx.MAIN}\"/></mc:Choice>"),
            workbook.replace("<mc:AlternateContent ","<mc:AlternateContent mc:ProcessContent=\"x15:absPath\" "),
            workbook.replace("<mc:Choice Requires=\"x15\">","<mc:Choice Requires=\"xr\">"),
            workbook.replace("</mc:AlternateContent>","<mc:Fallback/></mc:AlternateContent>"),
            workbook.replace("</mc:Choice>","synthetic-unhandled</mc:Choice>"),
            workbook.replace("{140A7094-0E35-4892-8432-C4D2E57EDEB5}","{00000000-0000-0000-0000-000000000000}"),
            workbook.replace("chartTrackingRefBase=\"1\"","chartTrackingRefBase=\"1\" unknown=\"1\""),
            workbook.replace("<xcalcf:feature name=","<xcalcf:feature extra=\"1\" name="),
            workbook.replace("<xr:revisionPtr ","<xr:revisionPtr xml:base=\"file:///never\" "),
            workbook.replace("</sheets>","<xr:revisionPtr revIDLastSave=\"0\"/></sheets>"),
        )
        variants.forEach { xml ->
            val parts=LinkedHashMap(original); parts["xl/workbook.xml"]=xml.toByteArray()
            val result=XlsxWorkbookReader().read(SyntheticXlsx.zip(parts).inputStream(),context)
            assertNull(result.workbook); assertEquals("INVALID_STRUCTURE",result.issues.single().code)
        }
    }
}
