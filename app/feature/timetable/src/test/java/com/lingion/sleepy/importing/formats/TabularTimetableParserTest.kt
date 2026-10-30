/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import com.lingion.sleepy.importing.*
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TabularTimetableParserTest {
    private val context = FormatParseContext("invented", 30)
    private fun csv(text: String) = Utf8CsvReader().read(text.byteInputStream(), context).table!!
    private fun mapped(table: ImportTable) = ConfirmedColumnMapping(setOf(1), HeaderMappingInspector().inspect(table).candidates.associate { it.field to it.column })
    private fun parse(text: String): FormatParseResult { val t=csv(text); return TabularTimetableParser().parse(t,mapped(t),context) }

    @Test fun `public CSV has exact sparse dates and local exception attachment`() {
        val file=generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.map { File(it,"docs/stage-3/import-examples/meetings.csv") }.first { it.isFile }
        val table=csv(file.readText())
        val result=TabularTimetableParser().parse(table,mapped(table),context)
        assertEquals(2,result.draft!!.courses.size)
        val first=result.draft!!.courses[0].meetings.single()
        assertEquals(setOf(1,3,5,8),first.weeks)
        assertEquals(LocalDate.of(2026,9,21),first.exceptions.single().originalDate)
        assertEquals(RichExceptionKind.CANCEL,first.exceptions.single().kind)
        assertEquals(setOf(LocalDate.of(2026,12,30),LocalDate.of(2027,1,6)),result.draft!!.courses[1].meetings.single().dates)
        assertTrue(result.pendingExceptions.isEmpty())
        assertEquals("demo-a", result.courseKeys[0])
        assertTrue(result.sources.all { it.sourceLocator.startsWith("csv:") })
        assertTrue(first.sources.isEmpty())
    }

    @Test fun `inspection proposes aliases with source evidence without confirming and manual unusual headers work`() {
        val table=csv("课程名称,星期,周次,节次,时间,教室,奇怪列\n示例课,周三,1-5(单),1、3,10:00-11:30,示例室,保留")
        val p=HeaderMappingInspector().inspect(table)
        assertEquals(setOf(1),p.headerRows)
        assertEquals(ImportColumnField.TIME_RANGE,p.candidates.single { it.column==5 }.field)
        assertTrue(p.candidates.all { it.sources.single().row==1 && it.sources.single().rawText.isNotEmpty() })
        val manual=ConfirmedColumnMapping(setOf(1),mapOf(ImportColumnField.NAME to 1,ImportColumnField.DAY_OF_WEEK to 2,ImportColumnField.WEEKS to 3,ImportColumnField.SECTIONS to 4,ImportColumnField.TIME_RANGE to 5,ImportColumnField.ROOM to 6))
        val r=TabularTimetableParser().parse(table,manual,context)
        assertEquals(setOf(1,3,5),r.draft!!.courses.single().meetings.single().weeks)
        assertEquals(setOf(1,3),r.draft!!.courses.single().meetings.single().sections)
        assertTrue(r.issues.any { it.code=="UNMAPPED_COLUMN" })
    }

    @Test fun `ambiguous headers duplicate field and invalid mappings require correction`() {
        val table=csv("name,课程名称,weeks\n甲,乙,1")
        assertTrue(HeaderMappingInspector().inspect(table).issues.any { it.code=="AMBIGUOUS_HEADER" })
        listOf(ConfirmedColumnMapping(setOf(1),mapOf(ImportColumnField.NAME to 99)),ConfirmedColumnMapping(setOf(1),mapOf(ImportColumnField.NAME to 1,ImportColumnField.WEEKS to 1)),ConfirmedColumnMapping(setOf(9),mapOf(ImportColumnField.NAME to 1))).forEach {
            assertNull(TabularTimetableParser().parse(table,it,context).draft)
        }
    }

    @Test fun `same titles without identity never merge and explicit keys may share a course only`() {
        val r=parse("name,dayOfWeek,weeks,sections\n同名课,1,1-3,1\n同名课,2,5-8,3")
        assertEquals(2,r.draft!!.courses.size)
        assertTrue(r.issues.any { it.code=="UNKEYED_COURSE_IDENTITY" })
        val keyed=parse("courseKey,meetingKey,name,dayOfWeek,weeks,sections\nc,a,同名课,1,1-3,1\nc,b,同名课,2,5-8,3")
        assertEquals(2,keyed.draft!!.courses.single().meetings.size)
        assertNull(parse("courseKey,meetingKey,name,dayOfWeek,weeks,sections\nc,a,甲,1,1,1\nc,a,甲,2,2,2").draft)
        assertNull(parse("courseKey,meetingKey,name\nc,a,甲\nc,b,乙").draft)
    }

    @Test fun `missing declarations do not fabricate schedules and explicit contradictions survive review`() {
        val a=parse("name,kind,weeks,dates,dayOfWeek,startTime,endTime\n甲,DATE_SPECIFIC,1;3,2026-12-30,1,10:00:01,11:30")
        assertNotNull(a.draft)
        val m=a.draft!!.courses.single().meetings.single()
        assertEquals(setOf(LocalDate.of(2026,12,30)),m.dates)
        assertNull(m.customTime)
        assertTrue(a.issues.any { it.code=="INVALID_TIME" })
        assertTrue(a.issues.any { it.code=="DATE_WEEKDAY_MISMATCH" })
        val b=parse("name,weeks\n甲,1")
        assertEquals(MeetingKind.UNSCHEDULED,b.draft!!.courses.single().meetings.single().kind)
        assertNull(b.draft!!.courses.single().meetings.single().dayOfWeek)
    }

    @Test fun `typed temporals require confirmed mapping formula and unused cell issues remain`() {
        val date=LocalDate.of(2026,12,30)
        val table=ImportTable("invented",listOf(ImportRow(1,listOf(ImportCell(1,"甲","A1"),ImportCell(2,"46386.5","B1",CellValue.Number(BigDecimal("46386.5"),ExcelTemporalValue(date,LocalTime.NOON)),CellStyle(1,22,null,NumberFormatKind.DATE_TIME)),ImportCell(3,"2","C1",formula="1+1",issues=listOf(ParseIssue("FORMULA_UNSUPPORTED","review")))))))
        val r=TabularTimetableParser().parse(table,ConfirmedColumnMapping(emptySet(),mapOf(ImportColumnField.NAME to 1,ImportColumnField.DATES to 2)),context)
        assertEquals(setOf(date),r.draft!!.courses.single().meetings.single().dates)
        assertTrue(r.issues.any { it.code=="UNUSED_TIME_COMPONENT" })
        assertTrue(r.issues.any { it.code=="FORMULA_UNSUPPORTED" })
        val bare=table.copy(rows=listOf(ImportRow(1,listOf(table.rows[0].cells[0],ImportCell(2,"46386","B1",CellValue.Number(BigDecimal(46386)))))))
        assertTrue(TabularTimetableParser().parse(bare,ConfirmedColumnMapping(emptySet(),mapOf(ImportColumnField.NAME to 1,ImportColumnField.DATES to 2)),context).draft!!.courses.single().meetings.single().dates.isEmpty())
    }

    @Test fun `exception metadata preserves empty clear and dangling or invalid bases remain pending`() {
        val r=parse("courseKey,meetingKey,name,dayOfWeek,weeks,sections,room,exceptionKind,originalDate,targetDate\nc,m,甲,1,1,1,旧室,,,\nc,m,,,,,,CANCEL,2026-10-05,\nz,m,,,,,,EXTRA,,2026-10-06")
        assertEquals("",r.draft!!.courses.single().meetings.single().exceptions.single().room)
        assertNull(r.draft!!.courses.single().meetings.single().exceptions.single().teacher)
        assertEquals(1,r.pendingExceptions.size)
        assertEquals("z",r.pendingExceptions.single().courseKey)
        val invalid=parse("courseKey,meetingKey,name,exceptionKind,originalDate\nc,m,甲,,\nc,m,,CANCEL,2026-10-05")
        assertEquals(1,invalid.pendingExceptions.size)
    }

    @Test fun `merged two level headers retain anchors and never propagate body cells`() {
        val table=ImportTable("invented",listOf(ImportRow(1,listOf(ImportCell(1,"课程名称","A1"),ImportCell(2,"安排","B1"))),ImportRow(2,listOf(ImportCell(1,"","A2",mergeAnchor="A1"),ImportCell(2,"周次","B2"),ImportCell(3,"节次","C2"))),ImportRow(3,listOf(ImportCell(1,"甲","A3"),ImportCell(2,"1","B3"),ImportCell(3,"1","C3"))),ImportRow(4,listOf(ImportCell(1,"","A4",mergeAnchor="A3"),ImportCell(2,"2","B4"),ImportCell(3,"3","C4")))))
        val p=HeaderMappingInspector().inspect(table)
        assertEquals(setOf(1,2),p.headerRows)
        assertEquals("A1",p.candidates.single { it.field==ImportColumnField.NAME }.sources.single().sourceLocator)
        val r=TabularTimetableParser().parse(table,ConfirmedColumnMapping(setOf(1,2),mapOf(ImportColumnField.NAME to 1,ImportColumnField.WEEKS to 2,ImportColumnField.SECTIONS to 3)),context)
        assertEquals(2,r.draft!!.courses.size)
        assertEquals("",r.draft!!.courses[1].name)
        assertTrue(r.draft!!.courses[1].missingFields.contains(ImportField.COURSE_NAME))
    }

    @Test fun `limits reject atomically empty rows do not create courses and cancellation propagates`() {
        val t=csv("name,weeks\n甲,1\n乙,2\n,")
        assertEquals(2,TabularTimetableParser().parse(t,mapped(t),context).draft!!.courses.size)
        assertNull(TabularTimetableParser().parse(t,mapped(t),context.copy(limits=ImportLimits(maxCourses=1))).draft)
        assertNull(TabularTimetableParser().parse(t,mapped(t),context.copy(limits=ImportLimits(maxFieldUnits=2))).draft)
        assertTrue(HeaderMappingInspector().inspect(t,context.copy(limits=ImportLimits(maxCells=1))).issues.any { it.code=="LIMIT_EXCEEDED" })
        try { TabularTimetableParser().parse(t,mapped(t),context.copy(cancellation=ImportCancellation { throw CancellationException() })); fail() } catch (_:CancellationException) { }
    }

    @Test fun `missing time stays unscheduled despite explicit declared kind and review retains dates`() {
        val r=parse("name,kind,dayOfWeek,weeks,dates\n甲,RECURRING,1,1,\n乙,DATE_SPECIFIC,,,2026-12-30")
        assertTrue(r.draft!!.courses.all { it.meetings.single().kind==MeetingKind.UNSCHEDULED })
        assertEquals(setOf(LocalDate.of(2026,12,30)),r.draft!!.courses[1].meetings.single().dates)
        assertTrue(r.issues.any { it.code=="MEETING_KIND_MISMATCH" })
    }

    @Test fun `manual datetime composite consumes the time and unverified typed null is never promoted`() {
        val date=LocalDate.of(2026,12,30)
        val cell=ImportCell(2,"46386.5","B1",CellValue.Number(BigDecimal("46386.5"),ExcelTemporalValue(date,LocalTime.NOON)),CellStyle(1,22,null,NumberFormatKind.DATE_TIME))
        val t=ImportTable("invented",listOf(ImportRow(1,listOf(ImportCell(1,"甲","A1"),cell,ImportCell(3,"13:00","C1")))))
        val mapping=ConfirmedColumnMapping(emptySet(),mapOf(ImportColumnField.NAME to 1,ImportColumnField.DATES to 2,ImportColumnField.START_TIME to 2,ImportColumnField.END_TIME to 3))
        val r=TabularTimetableParser().parse(t,mapping,context)
        assertEquals(LocalTime.NOON,r.draft!!.courses.single().meetings.single().customTime!!.start)
        assertFalse(r.issues.any { it.code=="UNUSED_TIME_COMPONENT" })
        val unverified=t.copy(rows=listOf(ImportRow(1,listOf(t.rows[0].cells[0],cell.copy(rawText="2026-12-30",typedValue=null,issues=listOf(ParseIssue("INVALID_CELL_VALUE","review")))))))
        val rejected=TabularTimetableParser().parse(unverified,ConfirmedColumnMapping(emptySet(),mapOf(ImportColumnField.NAME to 1,ImportColumnField.DATES to 2)),context)
        assertTrue(rejected.draft!!.courses.single().meetings.single().dates.isEmpty())
        assertTrue(rejected.issues.any { it.code=="INVALID_CELL_VALUE" })
    }

    @Test fun `manual unusual header preserves literal whitespace preamble and source codes group only explicit identity`() {
        val t=csv("invented title,,,\n奇怪标题,代码,班号,周声明\n  示例 课  ,C,K,1\n  示例 课  ,C,K,3")
        val m=ConfirmedColumnMapping(setOf(2),mapOf(ImportColumnField.NAME to 1,ImportColumnField.SOURCE_COURSE_CODE to 2,ImportColumnField.SOURCE_CLASS_CODE to 3,ImportColumnField.WEEKS to 4))
        val r=TabularTimetableParser().parse(t,m,context)
        assertEquals("  示例 课  ",r.draft!!.courses.single().name)
        assertEquals(2,r.draft!!.courses.single().meetings.size)
        assertTrue(r.unparsedSources.any { it.rawText=="invented title" })
        assertTrue(r.courseKeys.isEmpty())
    }

    @Test fun `actual XLSX temporal date time formulas sparse metadata and empty clears share conversion`() {
        val sheet=SyntheticXlsx.worksheet("""<row r="1"><c r="A1" t="inlineStr"><is><t>甲</t></is></c><c r="B1" t="str"><v>c</v></c><c r="C1" t="str"><v>m</v></c><c r="D1" s="1"><v>40729</v></c><c r="E1" s="2"><v>0.5</v></c><c r="F1" s="2"><v>0.5625</v></c><c r="G1" t="str"><v>原室</v></c><c r="I1"><f>1+1</f><v>2</v></c></row><row r="2"><c r="B2" t="str"><v>c</v></c><c r="C2" t="str"><v>m</v></c><c r="G2"/><c r="H2" t="str"><v>CANCEL</v></c><c r="J2" t="d"><v>2011-07-05</v></c></row><row r="3"><c r="A3" s="1"/></row>""")
        val styles="""<styleSheet xmlns="${SyntheticXlsx.MAIN}"><cellXfs><xf numFmtId="0"/><xf numFmtId="14"/><xf numFmtId="20"/></cellXfs></styleSheet>"""
        val book=XlsxWorkbookReader().read(SyntheticXlsx.zip(SyntheticXlsx.withStyles(sheet,styles)).inputStream(),context).workbook!!
        // Caller explicitly selects this descriptor; parser has no workbook overload or first-sheet shortcut.
        val selected=book.sheets.single().table!!
        val map=ConfirmedColumnMapping(emptySet(),mapOf(ImportColumnField.NAME to 1,ImportColumnField.COURSE_KEY to 2,ImportColumnField.MEETING_KEY to 3,ImportColumnField.DATES to 4,ImportColumnField.START_TIME to 5,ImportColumnField.END_TIME to 6,ImportColumnField.ROOM to 7,ImportColumnField.EXCEPTION_KIND to 8,ImportColumnField.ORIGINAL_DATE to 10))
        val r=TabularTimetableParser().parse(selected,map,context)
        val meeting=r.draft!!.courses.single().meetings.single()
        assertEquals(setOf(LocalDate.of(2011,7,5)),meeting.dates)
        assertEquals(LocalTime.NOON,meeting.customTime!!.start)
        assertEquals("",meeting.exceptions.single().room)
        assertNull(meeting.exceptions.single().teacher)
        assertTrue(r.issues.any { it.code=="FORMULA_UNSUPPORTED" })
        assertTrue(r.sources.any { it.rawText=="2" && it.sourceLocator=="sheet:1!I1" })
        val formulaSource=r.sources.single { it.sourceLocator=="sheet:1!I1" }
        assertEquals("1+1",formulaSource.formula)
        assertEquals(1,formulaSource.row)
        assertEquals(9,formulaSource.column)
    }

    @Test fun `exception conflicting name constraint remains pending with candidate and attached raw paths exist`() {
        val r=parse("courseKey,meetingKey,name,dayOfWeek,weeks,sections,exceptionKind,originalDate\nc,m,甲,1,1,1,,\nc,m,乙,,,,CANCEL,2026-10-05")
        assertEquals(1,r.pendingExceptions.size)
        assertEquals(listOf(DraftPath(0,0)),r.pendingExceptions.single().candidatePaths)
        assertEquals("乙",r.pendingExceptions.single().courseName)
        val attached=parse("courseKey,meetingKey,name,dayOfWeek,weeks,sections,exceptionKind,originalDate\nc,m,甲,1,1,1,,\nc,m,,,,,CANCEL,2026-10-05")
        assertTrue(attached.sources.any { it.path.courseIndex==0 && it.path.meetingIndex==0 && it.path.exceptionIndex==0 && it.rawText=="2026-10-05" })
    }

    @Test fun `inspector checks later normal body row against row limit`() {
        val t=csv("name\n甲\n乙")
        val r=HeaderMappingInspector().inspect(t,context.copy(limits=ImportLimits(maxRows=2)))
        assertEquals("LIMIT_EXCEEDED",r.issues.single().code)
        assertTrue(r.candidates.isEmpty())
    }

    @Test fun `inspector checks oversized later body field`() {
        val t=csv("name\n甲\n"+"乙".repeat(9))
        val r=HeaderMappingInspector().inspect(t,context.copy(limits=ImportLimits(maxFieldUnits=8)))
        assertEquals("LIMIT_EXCEEDED",r.issues.single().code)
    }

    @Test fun `inspector checks formula length in later body cell`() {
        val t=csv("name\n甲\n乙").let { it.copy(rows=it.rows.take(2)+it.rows[2].copy(cells=listOf(it.rows[2].cells.single().copy(formula="1+".repeat(5)+"1")))) }
        val r=HeaderMappingInspector().inspect(t,context.copy(limits=ImportLimits(maxFieldUnits=8)))
        assertEquals("LIMIT_EXCEEDED",r.issues.single().code)
    }

    @Test fun `inspector checks aggregate later body text including formula`() {
        val literal=csv("name\n甲\n乙")
        assertEquals("LIMIT_EXCEEDED",HeaderMappingInspector().inspect(literal,context.copy(limits=ImportLimits(maxTextUnits=5))).issues.single().code)
        val formula=literal.copy(rows=literal.rows.take(2)+literal.rows[2].copy(cells=listOf(literal.rows[2].cells.single().copy(formula="1+1"))))
        assertEquals("LIMIT_EXCEEDED",HeaderMappingInspector().inspect(formula,context.copy(limits=ImportLimits(maxTextUnits=8))).issues.single().code)
    }

    @Test fun `inspector checks later body cell count`() {
        val t=csv("name\n甲\n乙")
        val extra=t.copy(rows=t.rows.take(2)+t.rows[2].copy(cells=t.rows[2].cells+ImportCell(2,"丙","row3:column2")))
        assertEquals("LIMIT_EXCEEDED",HeaderMappingInspector().inspect(extra,context.copy(limits=ImportLimits(maxCells=3))).issues.single().code)
    }

    @Test fun `inspector observes cancellation after early body row`() {
        val t=csv("name\n甲\n乙")
        var checks=0
        try {
            HeaderMappingInspector().inspect(t,context.copy(cancellation=ImportCancellation { if(++checks>=5) throw CancellationException() }))
            fail("Cancellation in a later table row must propagate")
        } catch(_:CancellationException) { }
    }
}
