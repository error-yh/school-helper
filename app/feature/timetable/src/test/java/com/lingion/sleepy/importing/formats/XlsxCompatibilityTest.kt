/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import org.junit.Assert.*
import org.junit.Test

class XlsxCompatibilityTest {
    private val context=FormatParseContext("compatibility-synthetic",30)
    private fun read(parts:Map<String,ByteArray>)=XlsxWorkbookReader().read(SyntheticXlsx.zip(parts).inputStream(),context)
    private fun sheet(body:String,bindings:String="""xmlns:mc="${SyntheticXlsx.MC}" xmlns:ac="${SyntheticXlsx.X14AC}" mc:Ignorable="ac"""",extra:String="")=
        """<worksheet xmlns="${SyntheticXlsx.MAIN}" $bindings>$extra<sheetData>$body</sheetData></worksheet>"""
    @Test fun `bound but unused Ignorable declarations are passive metadata`() {
        val result=read(SyntheticXlsx.parts(sheet("", """xmlns:compat="${SyntheticXlsx.MC}" xmlns:unused="urn:synthetic:unused" compat:Ignorable="unused"""")))
        assertNotNull(result.issues.toString(),result.workbook); assertEquals("EMPTY_TABLE",result.issues.single().code)
    }
    @Test fun `dyDescent accepts actual URI with arbitrary prefix and inherits declaration scope`() {
        val xml=sheet("""<row r="1" ac:dyDescent="0.25"><c r="A1" t="inlineStr"><is><t>literal</t></is></c></row>""",extra="""<sheetFormatPr ac:dyDescent="0.25"/>""")
        val result=read(SyntheticXlsx.parts(xml.replace("ac:","passive:").replace("xmlns:ac=","xmlns:passive=").replace("Ignorable=\"ac\"","Ignorable=\"passive\"")))
        assertTrue(result.issues.toString(),result.issues.isEmpty()); assertEquals("literal",result.workbook!!.sheets.single().table!!.rows.single().cells.single().rawText)
    }
    @Test fun `knownFonts is accepted only as exact passive fonts metadata in styles`() {
        val styles="""<styleSheet xmlns="${SyntheticXlsx.MAIN}" xmlns:mc="${SyntheticXlsx.MC}" xmlns:p="${SyntheticXlsx.X14AC}" mc:Ignorable="p"><fonts p:knownFonts="1"/><cellXfs><xf numFmtId="0"/></cellXfs></styleSheet>"""
        assertTrue(read(SyntheticXlsx.withStyles(SyntheticXlsx.worksheet(""),styles)).workbook!=null)
    }
    @Test fun `Ignorable never authorizes unknown extension content directives forged or rebound namespaces`() {
        val variants=listOf(
            sheet("""<row r="1" ac:dyDescent="0.25"/>""").replace(SyntheticXlsx.X14AC,"urn:synthetic:forged"),
            sheet("""<row r="1" ac:unknown="x"/>"""),
            sheet("""<row r="1"><ac:unknown/></row>"""),
            sheet("""<row r="1"><mc:AlternateContent/></row>"""),
            sheet("").replace("mc:Ignorable=", "mc:MustUnderstand=\"ac\" mc:Ignorable="),
            sheet("").replace("mc:Ignorable=", "mc:ProcessContent=\"ac:row\" mc:Ignorable="),
            sheet("").replace("mc:Ignorable=", "mc:PreserveAttributes=\"ac:dyDescent\" mc:Ignorable="),
            sheet("").replace("mc:Ignorable=", "mc:PreserveElements=\"ac:row\" mc:Ignorable="),
            sheet("").replace("Ignorable=\"ac\"","Ignorable=\"unbound\""),
            sheet("""<row r="1" xmlns:ac="urn:synthetic:rebound"/>"""),
            sheet("""<row r="1" ac:dyDescent="0.25"/>""").replace(" mc:Ignorable=\"ac\"",""),
            sheet("",extra="""<sheetFormatPr ac:knownFonts="1"/>"""),
            sheet("""<row r="1" ac:dyDescent="0.25"/>""").replace(SyntheticXlsx.MC,"urn:synthetic:forged-mc"),
        )
        variants.forEach { xml -> val result=read(SyntheticXlsx.parts(xml)); assertNull(result.workbook); assertEquals("INVALID_STRUCTURE",result.issues.single().code) }
        val parts=SyntheticXlsx.parts()
        parts["[Content_Types].xml"]=String(parts.getValue("[Content_Types].xml")).replace("<Types ","""<Types xmlns:mc="${SyntheticXlsx.MC}" xmlns:p="${SyntheticXlsx.X14AC}" mc:Ignorable="p" """).toByteArray()
        assertNull(read(parts).workbook)
    }
    @Test fun `known typed cells without any value formula or inline element are explicit empties`() {
        val types=listOf("s","b","d","inlineStr","str","n")
        val row=types.mapIndexed { index,type -> """<c r="${('A'.code+index).toChar()}1" t="$type"/>""" }.joinToString("")
        val result=read(SyntheticXlsx.parts(SyntheticXlsx.worksheet("<row r=\"1\">$row</row>")))
        val cells=result.workbook!!.sheets.single().table!!.rows.single().cells
        assertEquals(6,cells.size); assertTrue(cells.all { it.typedValue==CellValue.Empty && it.rawText.isEmpty() && it.issues.isEmpty() })
        assertEquals("EMPTY_TABLE",result.issues.single().code)
    }
    @Test fun `explicit empty strings invalid references styles unknown types and uncached formulas keep their distinctions`() {
        val xml=SyntheticXlsx.worksheet("""<row r="1"><c r="A1" t="inlineStr"><is><t/></is></c><c r="B1" t="s"><v/></c><c r="C1" t="b" s="99"/><c r="D1" t="unknown"/><c r="E1"><f>1+1</f></c></row>""")
        val cells=read(SyntheticXlsx.parts(xml)).workbook!!.sheets.single().table!!.rows.single().cells
        assertEquals(CellValue.Text(""),cells[0].typedValue)
        for(i in 1..3) { assertNull(cells[i].typedValue); assertEquals("INVALID_CELL_VALUE",cells[i].issues.single().code) }
        assertNull(cells[4].typedValue); assertEquals("FORMULA_UNSUPPORTED",cells[4].issues.single().code)
    }
    @Test fun `boolean lexical values are exact schema values without coercion`() {
        val values=listOf("0","1","false","true","yes","True")
        val row=values.mapIndexed { index,value -> """<c r="${('A'.code+index).toChar()}1" t="b"><v>$value</v></c>""" }.joinToString("")
        val cells=read(SyntheticXlsx.parts(SyntheticXlsx.worksheet("<row r=\"1\">$row</row>"))).workbook!!.sheets.single().table!!.rows.single().cells
        assertEquals(listOf(false,true,false,true),cells.take(4).map { (it.typedValue as CellValue.BooleanValue).value })
        assertTrue(cells.take(4).all { it.issues.isEmpty() }); assertTrue(cells.drop(4).all { it.typedValue==null && it.issues.single().code=="INVALID_CELL_VALUE" })
    }
}
