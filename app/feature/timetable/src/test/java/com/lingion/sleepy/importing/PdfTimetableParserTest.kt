/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing

import org.junit.Assert.*
import org.junit.Test

class PdfTimetableParserTest {
    private fun page(index: Int = 1, vararg body: Pair<Float, String>): PositionedPage {
        val spans = (1..7).map { day -> PositionedText("星期" + "一二三四五六日"[day-1], SourcePosition(index, TextBox(day*100f,20f,day*100f+40f,30f))) } +
            body.map { (x,text) -> PositionedText(text, SourcePosition(index, TextBox(x,60f,x+70f,160f))) }
        return PositionedPage(index, 850f, 1000f, spans)
    }
    @Test fun `coded adapter is selected even if every title is wrapped across lines`() {
        for(title in listOf("食品分析\n.001","食品\n分析 .001")) {
            val result=PdfTimetableParser().parse(PositionedDocument(listOf(page(1,100f to "$title\n(1周) (15:10-17:25) A 甲(9000000000,教授)"))))
            assertEquals(1,result.courses.size)
            assertEquals("食品分析",result.courses.single().name)
            assertEquals("001",result.courses.single().sourceCourseCode)
            assertEquals(1,result.courses.single().meetings.size)
        }
    }
    @Test fun `unrecognized cells without week or course keywords remain explicitly unparsed`() {
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(page(1,100f to "概率论\n教师:甲",200f to "算法\n(1-2节)1周/场地:A/教师:乙"))))
        assertEquals(1,result.courses.size)
        assertTrue(result.unparsed.any { it.rawText.contains("概率论") })
        assertTrue(result.unparsed.any { it.rawText.contains("教师:甲") })
    }
    @Test fun `seven day grid preserves sparse weeks and separate meetings`() {
        val doc = PositionedDocument(listOf(page(1,100f to "算法▲\n(1-2节)1-3周,7-8周/场地:A101/教师:甲/教学班:算法-001/学分:3",700f to "算法▲\n(3-4节)5-7周(单)/场地:B201/教师:乙/教学班:算法-001/学分:3")))
        val result = PdfTimetableParser().parse(doc)
        assertEquals(1, result.courses.size)
        assertEquals(2, result.courses.single().meetings.size)
        assertEquals(setOf(1,2,3,7,8), result.courses.single().meetings.first().weeks)
        assertEquals(7, result.courses.single().meetings.last().dayOfWeek)
        assertEquals(setOf(3,4), result.courses.single().meetings.last().sections)
        assertTrue(result.missingFields.contains(ImportField.SEMESTER_START_DATE))
    }
    @Test fun `multiple pages and wrapped names are content selected`() {
        val result = PdfTimetableParser().parse(PositionedDocument(listOf(page(1,100f to "运输预测与决策技\n术\n(1-2节)第5周/场地:A/教师:甲"),page(2,200f to "运输预测与决策技\n术\n(3-4节)2-16周(双)/场地:B/教师:甲"))))
        assertEquals(1,result.courses.size)
        assertEquals("运输预测与决策技术",result.courses.single().name)
        assertEquals(setOf(1,2),result.courses.single().sources.map { it.position.page }.toSet())
    }
    @Test fun `practice without a time remains unscheduled`() {
        val p = page(1,100f to "高数\n(1-2节)1-16周/场地:A/教师:甲")
        val practice = PositionedText("实践课程：综合实践○甲(共16周)/1-16周;  社会实践●乙(共1周)/16周;", SourcePosition(1,TextBox(40f,900f,800f,920f)))
        val result = PdfTimetableParser().parse(PositionedDocument(listOf(p.copy(text=p.text+practice))))
        assertEquals(3,result.courses.size)
        assertEquals(2,result.courses.flatMap { it.meetings }.count { it.kind == MeetingKind.UNSCHEDULED })
        assertTrue(result.courses.flatMap { it.meetings }.filter { it.kind == MeetingKind.UNSCHEDULED }.all { ImportField.TIME in it.missingFields })
    }
    @Test fun `coded time grid preserves explicit time instead of inventing periods`() {
        val result = PdfTimetableParser().parse(PositionedDocument(listOf(page(1,600f to "食品分析 .001\n(1~3,5~8周) (15:10-17:25) 实验楼529 甲(9000000000,教授)\n(9~13,16~17周) (8-9节 15:10-17:25) 仙林校区 实验楼529 乙(9000000001,教授)"))))
        val c = result.courses.single()
        assertEquals("食品分析", c.name)
        assertEquals(2,c.meetings.size)
        assertEquals("15:10",c.meetings.first().customTime!!.start.toString())
        assertEquals(setOf(8,9),c.meetings.last().sections)
        assertTrue(result.periodProposals.isEmpty())
    }
    @Test fun `unknown layout and malformed week are retained as review issues`() {
        val unknown = PdfTimetableParser().parse(PositionedDocument(listOf(PositionedPage(1,800f,1000f,listOf(PositionedText("unrecognized",SourcePosition(1,TextBox(0f,0f,50f,20f))))))))
        assertTrue(unknown.issues.any { it.code == "UNSUPPORTED_LAYOUT" })
        val malformed = PdfTimetableParser().parse(PositionedDocument(listOf(page(1,100f to "算法\n(1-2节)1-999周/场地:A/教师:甲"))))
        assertTrue(malformed.courses.single().meetings.single().warnings.isNotEmpty())
        assertTrue(malformed.courses.single().meetings.single().weeks.isEmpty())
    }
    @Test fun `continuation pages reuse measured columns and retain all source pages`() {
        val first=page(1,100f to "算法▲\n(1-2节)1-3周/场地:A/教师:")
        val continuation=PositionedPage(2,850f,1000f,listOf(PositionedText("甲/教学班:算法-001/学分:3\n算法▲\n(3-4节)7-8周/场地:B/教师:甲/教学班:算法-001/学分:3",SourcePosition(2,TextBox(100f,10f,170f,160f)))))
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(first,continuation)))
        assertEquals(1,result.courses.size)
        assertEquals(2,result.courses.single().meetings.size)
        assertEquals(setOf(1,2),result.courses.single().meetings.first().sources.map { it.position.page }.toSet())
        assertTrue(result.issues.isEmpty())
        assertTrue(result.courses.single().meetings.all { ImportField.TIME in it.missingFields })
    }
    @Test fun `coded titles do not absorb preceding class count and radical glyphs normalize`() {
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(page(1,100f to "⻝品分析 .001\n(1~3周) (15:10-17:25) A 甲(9000000000,教授)\n班级人数:49/67\n⻝品分析 .001\n(5~8周) (15:10-17:25) A 甲(9000000000,教授)"))))
        assertEquals(1,result.courses.size)
        assertEquals("食品分析",result.courses.single().name)
        assertEquals(2,result.courses.single().meetings.size)
    }
    @Test fun `parser rejects oversized inputs and invalid coordinates before layout work`() {
        val p=page(1,100f to "算法\n(1-2节)1-16周/教师:甲/场地:A")
        assertEquals("LIMIT_EXCEEDED",PdfTimetableParser(maxPages=0).parse(PositionedDocument(listOf(p))).issues.single().code)
        assertEquals("LIMIT_EXCEEDED",PdfTimetableParser(maxGlyphs=5).parse(PositionedDocument(listOf(p))).issues.single().code)
        val invalid=p.copy(text=p.text+PositionedText("bad",SourcePosition(1,TextBox(Float.NaN,0f,1f,1f))))
        assertEquals("INVALID_COORDINATES",PdfTimetableParser().parse(PositionedDocument(listOf(invalid))).issues.single().code)
    }
    @Test fun `footnote practice with weeks but no clock stays unscheduled`() {
        val p=page(1,100f to "食品分析 .001\n(1周) (15:10-17:25) A 甲(9000000000,教授)")
        val foot=PositionedText("备注: 1.金工实习 41G1163.003 (14~15)",SourcePosition(1,TextBox(40f,900f,800f,920f)))
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(p.copy(text=p.text+foot))))
        assertEquals(2,result.courses.size)
        val practice=result.courses.single { it.name=="金工实习" }.meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,practice.kind)
        assertEquals(setOf(14,15),practice.weeks)
        assertTrue(ImportField.TIME in practice.missingFields)
    }
    @Test fun `inline course remarks are not footer boundaries`() {
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(page(1,100f to "算法\n(1-2节)1-3周/场地:A/教师:甲/选课备注:/学分:3\n算法\n(3-4节)7-8周/场地:A/教师:甲/选课备注:/学分:3"))))
        assertEquals(2,result.courses.single().meetings.size)
    }
    @Test fun `unknown record inside a recognized column stays explicitly unparsed`() {
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(page(1,100f to "算法\n(1-2节)1-3周/场地:A/教师:甲/学分:3\n未知课程/时间待定"))))
        assertTrue(result.unparsed.any { it.rawText.contains("时间待定") })
        assertTrue(result.requiresConfirmation)
    }
    @Test fun `all multiple footnote practices are retained`() {
        val p=page(1,100f to "食品分析 .001\n(1周) (15:10-17:25) A 甲(9000000000,教授)")
        val foot=PositionedText("备注:1.实践甲 41G1163.003 (14~15) 2.实践乙 41G1164.004 (16)",SourcePosition(1,TextBox(40f,900f,800f,920f)))
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(p.copy(text=p.text+foot))))
        assertEquals(2,result.courses.flatMap { it.meetings }.count { it.kind==MeetingKind.UNSCHEDULED })
    }
    @Test fun `coded continuation retains source positions on both pages`() {
        val p=page(1,100f to "食品分析 .001\n(1周) (15:10-17:25) A")
        val continuation=PositionedPage(2,850f,1000f,listOf(PositionedText("甲(9000000000,教授)",SourcePosition(2,TextBox(100f,10f,170f,30f)))))
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(p,continuation)))
        assertEquals(setOf(1,2),result.courses.single().meetings.single().sources.map { it.position.page }.toSet())
    }
    @Test fun `mixed cell formats preserve both independent records`() {
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(page(1,100f to "食品分析 .001\n(1周) (15:10-17:25) A 甲(9000000000,教授)\n算法\n(1-2节)3周/场地:B/教师:乙"))))
        assertEquals(2,result.courses.size)
        assertEquals(2,result.courses.flatMap { it.meetings }.size)
    }
    @Test fun `original raw source text survives normalization`() {
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(page(1,100f to "算法▲\n（1-2节）1-3周/场地:A/教师:甲"))))
        assertEquals(setOf(1,2,3),result.courses.single().meetings.single().weeks)
        assertTrue(result.courses.single().sources.any { it.rawText.contains("（1-2节）") })
    }
    @Test fun `same title multiple teachers stays one reviewable archive when codes are absent`() {
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(page(1,100f to "算法\n(1-2节)1周/场地:A/教师:甲",200f to "算法\n(3-4节)2周/场地:B/教师:乙"))))
        assertEquals(1,result.courses.size)
        assertEquals(2,result.courses.single().meetings.size)
        assertTrue(result.courses.single().warnings.any { it.code=="COURSE_IDENTITY_UNVERIFIED" })
    }
    @Test fun `source individual section clocks are proposals without splitting a combined course time`() {
        val p=page(1,100f to "算法\n(1-2节)1周/场地:A/教师:甲")
        val clocks=listOf(PositionedText("1 08:00-08:45",SourcePosition(1,TextBox(20f,60f,65f,70f))),PositionedText("2 08:50-09:35",SourcePosition(1,TextBox(20f,80f,65f,90f))))
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(p.copy(text=p.text+clocks))))
        assertEquals(2,result.periodProposals.size)
        assertEquals("08:00",result.periodProposals.first().time.start.toString())
        assertFalse(ImportField.TIME in result.courses.single().meetings.single().missingFields)
    }
    @Test fun `dangling text after a completed credit field is unparsed`() {
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(page(1,100f to "算法\n(1-2节)1周/场地:A/教师:甲/学分:3\n额外课程信息无法识别"))))
        assertTrue(result.unparsed.any { it.rawText.contains("无法识别") })
    }
    @Test fun `wrapped elective remarks beginning a line are still course metadata`() {
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(page(1,100f to "算法\n(1-2节)1-3周/场地:A/教师:甲/选课\n备注:/课程学时:3/学分:3\n算法\n(3-4节)7-8周/场地:A/教师:甲/学分:3"))))
        assertEquals(2,result.courses.single().meetings.size)
    }
    @Test fun `a later schedule with wrapped staff and population fields cannot be truncated`() {
        val result=PdfTimetableParser().parse(PositionedDocument(listOf(page(1,100f to "食品分析 .001\n(1周) (15:10-17:25) A 甲(9000000000,教授)人数:49/45\n(2周) (15:10-17:25) B 乙(9000000001,\n教授)班级人数:49/\n45"))))
        assertEquals(2,result.courses.single().meetings.size)
    }
    @Test fun `date specific contract carries exact date without recurring conversion`() {
        val m = RichMeetingDraft(kind=MeetingKind.DATE_SPECIFIC, dates=setOf(java.time.LocalDate.of(2026,10,24)))
        assertTrue(m.weeks.isEmpty())
        assertNull(m.dayOfWeek)
        assertEquals(1,m.dates.size)
    }
}








