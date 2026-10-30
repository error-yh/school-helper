/* SPDX-License-Identifier: GPL-3.0-only
 * Invented schedules and measured-line geometry; no private image fixture.
 */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.FormatParseContext
import org.junit.Assert.*
import org.junit.Test

class RuledMatrixScopeTest {
    private fun word(text:String,x:Float,y:Float,w:Float=20f)=
        OcrSpan(text,OcrLevel.WORD,95f,SourcePosition(1,TextBox(x,y,x+w,y+10)))
    private fun line(vertical:Boolean,a:Float,b:Float,c:Float)=if(vertical)
        TableLineEvidence(true,TextBox(a-1,b,a+1,c),2) else
        TableLineEvidence(false,TextBox(b,a-1,c,a+1),2)
    private fun rectangle(left:Float=190f,right:Float=320f,top:Float=80f,bottom:Float=130f)=
        listOf(line(true,left,top,bottom),line(true,right,top,bottom),line(false,top,left,right),line(false,bottom,left,right))
    private fun parse(lines:List<TableLineEvidence>,courseY:Float=100f,firstDayX:Float=200f,periodX:Float=200f,title:String="示例课",extraWords:List<OcrSpan> = emptyList(),glyphHeight:Float=10f):OcrLayoutParseResult {
        val words=listOf(word("教学周",10f,20f,60f),word("星期一",firstDayX,20f,80f),word("星期二",600f,20f,80f),
            word("1",periodX,45f,10f),word("3",periodX+50,45f,10f),word("5",periodX+100,45f,10f),
            word("2",600f,45f,10f),word("4",650f,45f,10f),
            word("第1周",10f,100f,40f),word("第2周",10f,180f,40f),word(title,periodX+15,courseY,50f))+extraWords
        val positioned=PositionedPage(1,1000f,300f,emptyList())
        val sizedWords=words.map {it.copy(position=it.position!!.copy(bounds=it.position!!.bounds.let {b->b.copy(bottom=b.top+glyphHeight)}))}
        val variant=OcrVariantEvidence(OcrVariantId.ORIGINAL,1000,300,PageAffineTransform.IDENTITY,positioned,sizedWords,emptySet(),null,OcrWordQuality.EMPTY,true)
        return OcrTimetableParser().parse(OcrLayoutDocument(listOf(OcrLayoutPage(1,1000f,300f,listOf(variant),OcrVariantId.ORIGINAL,lines))),
            OcrLayoutContext(FormatParseContext("synthetic-ruled-matrix",20)))
    }
    private fun meeting(result:OcrLayoutParseResult)=checkNotNull(result.formatResult.draft).courses.single {it.name=="示例课"}.meetings.single()

    @Test fun fourConnectedSidesBindExactlyThePrintedSparsePeriodAnchors() {
        val m=meeting(parse(rectangle()))
        assertEquals(MeetingKind.RECURRING,m.kind)
        assertEquals(1,m.dayOfWeek);assertEquals(setOf(1),m.weeks)
        assertEquals(setOf(1,3,5),m.sections)
    }
    @Test fun missingSideCannotAuthorizeMergedPeriodScope() {
        assertEquals(MeetingKind.UNSCHEDULED,meeting(parse(rectangle().drop(1))).kind)
    }
    @Test fun closedBoxAcrossWeekdaysCannotBecomeAMondayLesson() {
        assertEquals(MeetingKind.UNSCHEDULED,meeting(parse(rectangle(right=710f))).kind)
    }
    @Test fun extraDateRowDoesNotBorrowTheFollowingWeekAnchor() {
        assertEquals(MeetingKind.UNSCHEDULED,meeting(parse(rectangle(top=60f,bottom=90f),courseY=70f)).kind)
    }
    @Test fun leftWeekdayBoundaryUsesMeasuredLineNotCenteredHeaderText() {
        val m=meeting(parse(rectangle(left=100f,right=230f)+line(true,100f,15f,230f),firstDayX=250f,periodX=110f))
        assertEquals(MeetingKind.RECURRING,m.kind)
        assertEquals(setOf(1,3,5),m.sections)
    }
    @Test fun fullInternalDividerPreventsJoiningTwoCells() {
        assertEquals(MeetingKind.UNSCHEDULED,meeting(parse(rectangle()+line(true,250f,80f,130f))).kind)
    }
    @Test fun namedHolidayWithPrintedDateRangeIsAnnotationNotLesson() {
        val r=parse(rectangle(),title="元旦(01.01-01.03)")
        assertTrue(r.formatResult.draft?.courses.orEmpty().none {it.name.contains("元旦")})
        assertTrue(r.coverage.any {it.span.rawText=="元旦(01.01-01.03)" && it.status==SpanCoverageStatus.ANNOTATION})
        assertTrue(r.formatResult.pendingExceptions.isEmpty())
    }
    @Test fun courseWithHolidayWordIsStillACourse() {
        val r=parse(rectangle(),title="元旦文化专题课")
        assertEquals(MeetingKind.RECURRING,r.formatResult.draft!!.courses.single {it.name=="元旦文化专题课"}.meetings.single().kind)
    }
    @Test fun rightBoundaryMustNotIncludeTheNextPeriodWithinGlyphTolerance() {
        assertEquals(setOf(1,3),meeting(parse(rectangle(right=300f))).sections)
    }
    @Test fun leftBoundaryMustNotIncludeThePreviousPeriodWithinGlyphTolerance() {
        assertEquals(setOf(3,5),meeting(parse(rectangle(left=210f))).sections)
    }
    @Test fun oneObservedLiteralSlashTitleRemainsWhole() {
        val r=parse(rectangle(),title="示例甲/示例乙")
        val c=r.formatResult.draft!!.courses.single()
        assertEquals("示例甲/示例乙",c.name)
        assertEquals(MeetingKind.RECURRING,c.meetings.single().kind)
        assertTrue(c.sources.any {it.rawText=="示例甲/示例乙"})
    }
    @Test fun triangleKeepsItsOriginalTextButDoesNotEraseMeasuredScope() {
        val c=parse(rectangle(),title="△示例课").formatResult.draft!!.courses.single()
        assertEquals("△示例课",c.name)
        assertEquals(MeetingKind.RECURRING,c.meetings.single().kind)
        assertEquals(setOf(1,3,5),c.meetings.single().sections)
        assertTrue(c.meetings.single().warnings.any {it.code=="UNEXPLAINED_MARKER_REVIEW_REQUIRED"})
    }
    @Test fun triangleWithoutFourSidesStillNeedsScopeCorrection() {
        assertEquals(MeetingKind.UNSCHEDULED,parse(emptyList(),title="△示例课").formatResult.draft!!.courses.single().meetings.single().kind)
    }
    @Test fun weekendMiniTableIsOneUntimedLessonWithOriginalDateNotAMondayRecurrence() {
        val heading="09.20(周日)的课程安排"
        val special=rectangle(left=190f,right=420f,top=130f,bottom=155f)+rectangle(left=190f,right=420f,top=155f,bottom=175f)
        val r=parse(rectangle()+special,extraWords=listOf(word(heading,200f,135f,210f),word("△临时实验",240f,160f,80f)))
        assertTrue(r.formatResult.draft!!.courses.none {it.name==heading})
        val c=r.formatResult.draft!!.courses.single {it.name=="△临时实验"}
        val m=c.meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind)
        assertTrue(m.weeks.isEmpty());assertTrue(m.sections.isEmpty());assertTrue(m.dates.isEmpty());assertNull(m.dayOfWeek)
        assertTrue(m.sources.any {it.rawText==heading})
        assertTrue(m.warnings.any {it.code=="SPECIAL_DATE_TIME_UNSPECIFIED"})
    }
    @Test fun shortCompleteSidesOfOneRowDoNotNeedTwoGlyphsOfLength() {
        val m=meeting(parse(rectangle(top=95f,bottom=125f),glyphHeight=18f))
        assertEquals(MeetingKind.RECURRING,m.kind)
        assertEquals(setOf(1,3,5),m.sections)
    }
    @Test fun glyphBoxBleedingOverAnEdgeDoesNotEraseTheProvenWeek() {
        val m=meeting(parse(rectangle(top=80f,bottom=146f),courseY=138f))
        assertEquals(MeetingKind.RECURRING,m.kind)
        assertEquals(setOf(1),m.weeks)
    }
}
