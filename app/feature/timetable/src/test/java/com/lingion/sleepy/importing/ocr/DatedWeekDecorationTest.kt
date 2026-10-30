package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.formats.FormatParseContext
import com.lingion.sleepy.importing.PositionedPage
import com.lingion.sleepy.importing.SourcePosition
import com.lingion.sleepy.importing.TextBox
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DatedWeekDecorationTest {
    private fun fields(monday:LocalDate?=LocalDate.of(2026,9,7))=
        LayoutFields(OcrLayoutContext(FormatParseContext("decorated-week",20),confirmedTermMonday=monday))
    private val date=LocalDate.of(2026,10,15)
    @Test fun actualCalendarIconReadoutsKeepLiteralWeeksOnlyWithIndependentDateAgreement() {
        for(prefix in listOf("曲","兰")) {
            val d=fields();assertEquals(setOf(6),d.datedWeeks(prefix+"第6周",date,4))
            assertTrue(d.issues.any {it.code=="WEEK_ICON_PREFIX_REVIEW_REQUIRED"})
            assertFalse(d.issues.any {it.code=="INVALID_WEEKS"})
        }
    }
    @Test fun wrongLiteralWeekIsNeverReplacedByTheCalculatedWeek() {
        val d=fields();assertTrue(d.datedWeeks("曲第7周",date,4).isEmpty())
        assertTrue(d.issues.any {it.code=="INVALID_WEEKS"})
    }
    @Test fun missingTermOrWrongDayCannotResolveAnUnknownDecoration() {
        assertTrue(fields(null).datedWeeks("曲第6周",date,4).isEmpty())
        assertTrue(fields().datedWeeks("曲第6周",date,3).isEmpty())
        assertTrue(fields().datedWeeks("曲第6周",null,4).isEmpty())
    }
    @Test fun semanticPrefixesAndArbitraryGarbageStayInvalid() {
        listOf("非第6周","单第6周","双第6周","X第6周","曲第6周多余","曲6").forEach {raw->
            assertTrue(fields().datedWeeks(raw,date,4).isEmpty())
        }
    }
    @Test fun normalWeeksStillUseTheAuthoritativeParityAndSparseGrammar() {
        val d=fields();assertEquals(setOf(1,3,5,7,9),d.datedWeeks("第1-10周(单)",null,null))
        assertEquals(setOf(1,3,5,8,10),d.datedWeeks("1,3,5,8,10",null,null))
        assertTrue(d.issues.isEmpty())
    }
    @Test fun actualDateAdapterRetainsOriginalDecorationAndExplicitWeekEvidence() {
        fun word(t:String,x:Float,y:Float)=OcrSpan(t,OcrLevel.WORD,95f,SourcePosition(1,TextBox(x,y,x+100,y+12)))
        val spans=listOf("日期","星期","周次","节次","课程名称","实验室").mapIndexed {i,t->word(t,i*140f,20f)}+
            listOf("20261015","星期4","曲第6周","1-4","示例课程","示例教室").mapIndexed {i,t->word(t,i*140f,70f)}
        val p=PositionedPage(1,1000f,500f,emptyList())
        val variant=OcrVariantEvidence(OcrVariantId.ORIGINAL,1000,500,PageAffineTransform.IDENTITY,p,spans,emptySet(),null,OcrWordQuality.EMPTY,true)
        val page=OcrLayoutPage(1,1000f,500f,listOf(variant),OcrVariantId.ORIGINAL)
        val result=OcrTimetableParser().parse(OcrLayoutDocument(listOf(page)),OcrLayoutContext(FormatParseContext("decorated-adapter",20),confirmedTermMonday=LocalDate.of(2026,9,7)))
        val m=result.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(setOf(6),m.weeks);assertEquals(setOf(date),m.dates)
        assertTrue(m.sources.any {it.rawText=="曲第6周"})
        assertFalse(m.warnings.any {it.code=="INVALID_WEEKS"})
    }
}
