/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

/** All data are invented. These tests exercise canonical geometry, never native OCR. */
class OcrLayoutParserTest {
    private val context = OcrLayoutContext(FormatParseContext("synthetic", 40))
    private fun word(t:String,x:Float,y:Float,w:Float=72f,h:Float=10f, confidence:Float?=93f, page:Int=1) =
        OcrSpan(t,OcrLevel.WORD,confidence,SourcePosition(page,TextBox(x,y,x+w,y+h)))
    private fun page(spans:List<OcrSpan>, n:Int=1, lines:List<TableLineEvidence> = emptyList()):OcrLayoutPage {
        val p=PositionedPage(n,1500f,2000f,emptyList())
        return OcrLayoutPage(n,p.width,p.height,listOf(OcrVariantEvidence(OcrVariantId.ORIGINAL,1500,2000,PageAffineTransform.IDENTITY,p,spans,emptySet(),null,OcrWordQuality.EMPTY,true)),OcrVariantId.ORIGINAL,lines)
    }
    private fun parse(spans:List<OcrSpan>, ctx:OcrLayoutContext=context)=OcrTimetableParser().parse(OcrLayoutDocument(listOf(page(spans))),ctx)
    private fun dateRows(count:Int=12):List<OcrSpan> {
        val out=mutableListOf<OcrSpan>()
        listOf("日期","星期","周次","节次","时间","课程名称","课程代码","实验项目","实验室","班组").forEachIndexed { i,s -> out+=word(s,i*140f,20f) }
        repeat(count) { i ->
            val date=LocalDate.of(2026,12,28).plusDays(i.toLong())
            val fields=listOf(date.toString().replace("-",""),"${date.dayOfWeek.value}","1,3,5","1,3","08:00-09:30",if(i%2==0) "示例甲" else "示例乙",if(i%2==0) "DEMO-A" else "DEMO-B","项目$i","室$i","组${i%3}")
            fields.forEachIndexed { j,s -> out+=word(s,j*140f,60f+i*28f,110f) }
        }; return out
    }
    private fun grid(codes:Boolean=true):List<OcrSpan> {
        val out=mutableListOf<OcrSpan>()
        // Two independently measured blocks, variable column spacing and 14 section axes.
        repeat(2) { block ->
            val yy=20f+block*700f
            (1..7).forEach { d -> out+=word("星期$d",100f+d*150f,yy) }
            repeat(7) { r ->
                out+=word("第${block*7+r+1}节",20f,yy+50+r*80)
                if(block*7+r<9) {
                    val id=block*7+r
                    val text="示例$id"+(if(codes) "/课程代码:D$id" else "")+"/周次:1,3,5/节次:1,3/教师:师$id/教室:室$id"
                    out+=word(text,240f+(id%7)*150f,yy+50+r*80,120f)
                    out+=word(text.replace("1,3,5","2,4").replace("室$id","异室$id"),240f+(id%7)*150f,yy+67+r*80,120f)
                }
            }
        }
        out+=word("实践课程:示例实践/周次:8,10",10f,1500f,400f)
        out+=word("实践课程:示例实践/周次:12",10f,1530f,400f)
        return out
    }
    @Test fun exportLegendAndPrintDateRemainAnnotationsRatherThanUnscheduledCourses() {
        val original=grid()
        val baseline=parse(original)
        val spans=original+word("理论:实践:实验:上机:讨论辅导:设计作业:课外",300f,1610f,700f)+
            word("打印时间:2026-08-18",1050f,1650f,300f)
        val result=parse(spans)
        assertEquals(baseline.formatResult.draft!!.courses.sumOf {it.meetings.size},result.formatResult.draft!!.courses.sumOf {it.meetings.size})
        assertEquals(baseline.regions.count {it.kind==OcrRegionKind.ANNOTATION}+2,result.regions.count {it.kind==OcrRegionKind.ANNOTATION})
        assertEquals(spans.map {it.rawText},result.document.pages.single().variants.single().spans.map {it.rawText})
        assertEquals(spans.size,result.coverage.size)
    }
    @Test fun similarlyNamedRealLessonsAndMalformedPrintMetadataAreNeverDiscarded() {
        val spans=grid()+word("打印时间/周次:1/节次:2",300f,1600f,120f)+
            word("理论与实践/周次:2/节次:3",600f,1630f,120f)+word("打印时间:2026-99-99",1050f,1660f,300f)
        val result=parse(spans)
        assertTrue(result.formatResult.draft!!.courses.any {it.name=="打印时间"})
        assertTrue(result.formatResult.draft!!.courses.any {it.name=="理论与实践"})
        assertFalse(result.regions.any {it.kind==OcrRegionKind.ANNOTATION})
    }
    @Test fun wrappedTeacherTailCannotBecomeTheNextTitleAcrossARealVerticalGap() {
        val spans=mutableListOf(word("星期一",300f,20f,150f))
        repeat(4) {i->spans+=word("第${i+1}节",20f,70f+i*80f)}
        spans+=word("示例前课",300f,55f,120f)
        spans+=word("(1节)1-4周/教师:林",300f,70f,130f)
        spans+=word("甲",300f,90f,20f)
        spans+=word("示例后续",300f,150f,100f)
        spans+=word("课程",300f,162f,50f)
        spans+=word("(3-4节)1-4周/教师:乙",300f,180f,130f)
        val courses=parse(spans).formatResult.draft!!.courses
        assertEquals(listOf("示例前课","示例后续课程"),courses.map {it.name})
        assertEquals("林甲",courses.first().meetings.single().teacher)
        assertTrue(courses.first().sources.any {it.rawText=="甲"})
        assertFalse(courses.last().sources.any {it.rawText=="甲"})
    }
    private fun matrix():List<OcrSpan> = buildList {
        add(word("教学周",10f,20f));add(word("日期范围",100f,20f))
        add(word("星期一",350f,20f,160f));add(word("星期三",700f,20f,160f))
        add(word("1,3节",350f,45f)); add(word("5-6节",700f,45f))
        repeat(19) { i ->
            add(word("第${i+1}周",10f,90f+i*60f)); add(word("09/01-09/07",100f,90f+i*60f,120f))
            if(i!=4) add(word("矩阵示例/课程代码:M/教师:甲/教室:R$i",350f,90f+i*60f,170f))
        }
        add(word("2026-12-30",10f,1250f,120f));add(word("特殊甲/节次:2/教师:乙",350f,1250f,180f))
        add(word("2027-01-06",10f,1310f,120f));add(word("特殊乙/节次:4",700f,1310f,180f))
        repeat(3) { add(word("节假日注记${it+1}",10f,1400f+it*30f,200f)) }
    }
    private fun splitWeekdayHeaders(spans:List<OcrSpan>):List<OcrSpan> = spans.flatMap {span->
        if(!isDayAnchor(span.rawText)) listOf(span) else {
            val box=span.position!!.bounds
            val prefix=span.rawText.dropLast(1)
            listOf(word(prefix,box.left,box.top,32f),word(span.rawText.takeLast(1),box.left+33f,box.top,10f))
        }
    }
    @Test fun fragmentedWeekAndWeekdayInDateRowsAreNotMatrixHeaders() {
        val spans=dateRows().flatMap {span->
            val b=span.position!!.bounds
            val column=(b.left/140f).toInt()
            val x=when(column) {0->280f;2->0f;else->b.left}
            if(b.top==20f) listOf(word(span.rawText,x,b.top,b.right-b.left))
            else when(column) {
                2->listOf(word("第",0f,b.top,10f),word("${((b.top-60f)/28f).toInt()+1}",11f,b.top,12f),word("周",24f,b.top,10f))
                1->listOf(word("星期",140f,b.top,25f),word(span.rawText,166f,b.top,10f))
                else->listOf(word(span.rawText,x,b.top,b.right-b.left))
            }
        }+word("14",700f,73f,12f)
        val result=parse(spans)
        assertTrue(result.layoutCandidates.any {it.adapterId=="date-list"})
        assertTrue("A printed row week must not supply the matrix header",result.layoutCandidates.none {it.adapterId=="week-matrix"})
        assertEquals(12,result.counts.meetings)
    }
    @Test fun weekMatrixAcceptsARealWeekHeadingBelowTheWeekdayHeader() {
        val spans=matrix().map {span->
            if(span.rawText=="教学周") word("周",10f,45f,12f) else span
        }+word("次",10f,60f,12f)
        val result=parse(spans)
        assertEquals(setOf("week-matrix"),result.formatResult.draft!!.adapterIds)
        assertTrue(result.formatResult.draft!!.courses.any {it.sourceCourseCode=="M"})
        val heading=spans.first {it.rawText=="周"}
        val headingIds=result.coverage.filter {it.span==heading}.map {it.id}.toSet()
        assertTrue(result.regions.any {it.kind==OcrRegionKind.STRUCTURAL_ANCHOR && it.spanIds.any {id->id in headingIds}})
    }
    @Test fun dateDigitsOutsideTheWeekColumnCannotBecomeDuplicateWeekRows() {
        val spans=matrix().map {span->
            when {
                span.rawText=="教学周"->word("周次",10f,20f,24f)
                span.rawText.matches(Regex("第[0-9]+周"))->word(span.rawText.removePrefix("第").removeSuffix("周"),10f,span.position!!.bounds.top,12f)
                else->span
            }
        }+word("9",48f,90f,12f)
        val result=parse(spans)
        assertNotNull("Date digits must not invalidate genuine week coordinates",result.formatResult.draft)
        val course=result.formatResult.draft!!.courses.first {it.sourceCourseCode=="M"}
        assertEquals(setOf(1),course.meetings.first().weeks)
    }
    @Test fun adjacentPrintedOneAndZeroRetainTheActualTenthSectionAndBothSources() {
        val spans=matrix().flatMap {span->if(span.rawText=="1,3节") listOf(word("1",350f,45f,10f),word("0",360f,45f,8f)) else listOf(span)}
        val result=parse(spans)
        val meeting=result.formatResult.draft!!.courses.first {it.sourceCourseCode=="M"}.meetings.first()
        assertEquals(setOf(10),meeting.sections)
        assertTrue(meeting.sources.any {it.rawText=="1"});assertTrue(meeting.sources.any {it.rawText=="0"})
    }
    @Test fun splitDigitsInsideTheWeekColumnRemainThePrintedEleventhWeek() {
        val spans=matrix().flatMap {span->when(span.rawText) {
            "教学周"->listOf(word("周次",10f,20f,24f))
            "第11周"->listOf(word("1",10f,span.position!!.bounds.top,9f),word("1",20f,span.position!!.bounds.top,9f))
            else->listOf(span)
        }}
        val result=parse(spans)
        assertNotNull(result.formatResult.draft)
        assertTrue(result.formatResult.draft!!.courses.first {it.sourceCourseCode=="M"}.meetings.any {it.weeks==setOf(11)})
    }
    @Test fun labelledGridReusesPdfCourseParsingAcrossSectionRows() {
        val spans=edgeGrid(word("复用示例",250f,65f,80f))+listOf(
            word("(1-2节)1-8周/校区:示例",250f,90f,190f),
            word("/教师:甲",250f,160f,80f),word("/场地:R1",250f,195f,80f))
        val result=parse(spans)
        val meeting=result.formatResult.draft!!.courses.single {it.name=="复用示例"}.meetings.single()
        assertEquals("甲",meeting.teacher);assertEquals("R1",meeting.room)
        assertEquals(setOf(1,2),meeting.sections);assertEquals((1..8).toSet(),meeting.weeks)
        assertTrue(meeting.sources.any {it.rawText.contains("教师:甲")})
    }
    @Test fun stackedIndividualClocksBecomeSourcePeriodsWithoutGuessingMissingEnds() {
        val spans=edgeGrid(word("作息示例",250f,65f,80f))+listOf(
            word("(1-2节)1周/教师:甲/场地:R1",250f,90f,220f),
            word("08:00",10f,90f,40f),word("08:45",10f,110f,40f),
            word("08:55",10f,200f,40f),word("09:40",10f,220f,40f))
        val full=parse(spans).formatResult.draft!!
        assertEquals(setOf(1,2),full.periodProposals.map {it.section}.toSet())
        assertEquals(java.time.LocalTime.of(8,55),full.periodProposals.single {it.section==2}.time.start)
        assertTrue(full.periodProposals.single {it.section==1}.sources.any {it.rawText=="08:00"})
        assertFalse(full.courses.single().meetings.single().missingFields.contains(ImportField.TIME))
        val partial=parse(spans.filterNot {it.rawText=="09:40"}).formatResult.draft!!
        assertEquals(listOf(1),partial.periodProposals.map {it.section})
        assertTrue(partial.courses.single().meetings.single().missingFields.contains(ImportField.TIME))
    }
    @Test fun sevenDayGridRecognizesAdjacentExactWeekdayHeaderParts() {
        val spans=splitWeekdayHeaders(grid())
        val parsed=parse(spans)
        assertEquals(setOf("seven-day-grid"),parsed.formatResult.draft!!.adapterIds)
        assertEquals(20,parsed.formatResult.draft!!.courses.sumOf {it.meetings.size})
        assertWeekdayPartsHaveStructuralEvidence(parsed)
        assertEquals(spans.map {it.rawText},parsed.document.pages.single().variants.single().spans.map {it.rawText})
    }
    @Test fun weekMatrixRecognizesAdjacentExactWeekdayHeaderParts() {
        val spans=splitWeekdayHeaders(matrix())
        val parsed=parse(spans)
        assertEquals(setOf("week-matrix"),parsed.formatResult.draft!!.adapterIds)
        assertTrue(parsed.formatResult.draft!!.courses.isNotEmpty())
        assertWeekdayPartsHaveStructuralEvidence(parsed)
        assertEquals(spans.map {it.rawText},parsed.document.pages.single().variants.single().spans.map {it.rawText})
    }
    @Test fun fragmentedWeekdaysDoNotRepairDuplicatedDigitsOrJoinDistantParts() {
        val duplicates=splitWeekdayHeaders(grid().map {if(isDayAnchor(it.rawText)) it.copy(rawText="星期三") else it})
        assertTrue(parse(duplicates).formatResult.draft!!.courses.isEmpty())
        val distant=splitWeekdayHeaders(grid()).map {span->
            if(span.rawText.matches(Regex("[1-7]")) && span.position!!.bounds.top in setOf(20f,720f)) {
                val box=span.position.bounds
                span.copy(position=SourcePosition(1,box.copy(left=box.left+50f,right=box.right+50f)))
            } else span
        }
        assertTrue(parse(distant).formatResult.draft!!.courses.isEmpty())
    }
    private fun assertWeekdayPartsHaveStructuralEvidence(parsed:OcrLayoutParseResult) {
        // Axis support can also be PARSED when used by an actual course record.
        // Assert the retained structural region itself, not the status precedence.
        val structural=parsed.regions.filter {it.kind==OcrRegionKind.STRUCTURAL_ANCHOR}.flatMap {it.spanIds}.toSet()
        val prefixes=parsed.coverage.filter {it.span.rawText=="星期"}
        assertTrue(prefixes.isNotEmpty())
        assertTrue(structural.containsAll(prefixes.map {it.id}))
    }
    @Test fun matrixWithUnreadableWeekTitleDoesNotTreatBareWeekNumbersAsSections() {
        val spans=matrix().filterNot {it.rawText=="教学周"}.map {span->
            val week=Regex("第([0-9]+)周").matchEntire(span.rawText)
            if(week==null) span else span.copy(rawText=week.groupValues[1])
        }
        val parsed=parse(spans)
        assertFalse("Bare row numbers need independent section-axis evidence",
            "seven-day-grid" in parsed.formatResult.draft!!.adapterIds)
        assertEquals(spans.map {it.rawText},parsed.document.pages.single().variants.single().spans.map {it.rawText})
    }
    private fun gridWithBareSectionNumbers()=grid().map {span->
        val section=Regex("第([0-9]+)节").matchEntire(span.rawText)
        if(section==null) span else span.copy(rawText=section.groupValues[1])
    }
    @Test fun numberedGridRequiresAndRetainsItsExplicitSectionHeading() {
        val spans=gridWithBareSectionNumbers()+listOf(word("节次",20f,20f),word("节次",20f,720f))
        val parsed=parse(spans)
        assertEquals(20,parsed.counts.meetings)
        assertTrue(parsed.regions.filter {it.kind==OcrRegionKind.STRUCTURAL_ANCHOR}.flatMap {it.spanIds}.toSet()
            .containsAll(parsed.coverage.filter {it.span.rawText=="节次"}.map {it.id}))
    }
    @Test fun numberedGridCanUseActualLeftAxisClockEvidence() {
        val spans=gridWithBareSectionNumbers()+listOf(word("08:00",10f,100f,50f),word("08:45",10f,120f,50f),
            word("17:00",10f,800f,50f),word("17:45",10f,820f,50f))
        val parsed=parse(spans)
        assertEquals(20,parsed.counts.meetings)
        assertTrue(parsed.regions.filter {it.kind==OcrRegionKind.STRUCTURAL_ANCHOR}.flatMap {it.spanIds}.toSet()
            .containsAll(parsed.coverage.filter {it.span.rawText.matches(Regex("[0-9]{2}:[0-9]{2}"))}.map {it.id}))
    }
    @Test fun dateListPreservesTwelveIndependentDatesAndAllFields() {
        val r=parse(dateRows()); val courses=r.formatResult.draft!!.courses
        assertEquals(setOf("date-list"),r.formatResult.draft!!.adapterIds)
        assertEquals(2,courses.size);assertEquals(12,courses.sumOf { it.meetings.size })
        val meetings=courses.flatMap { it.meetings }
        assertTrue(meetings.all { it.kind==MeetingKind.DATE_SPECIFIC && it.dates.size==1 && it.sections==setOf(1,3) && it.weeks==setOf(1,3,5) })
        assertEquals((0..11).map { "室$it" }.toSet(),meetings.map { it.room }.toSet())
        assertEquals(12,meetings.map { it.experimentProject }.toSet().size)
        assertEquals(0,r.counts.confirmedArchives)
        assertEquals(dateRows().size,r.coverage.size)
        assertTrue(r.coverage.all { it.status==SpanCoverageStatus.PARSED || it.status==SpanCoverageStatus.STRUCTURAL_ANCHOR })
        assertEquals(dateRows().map { it.rawText },r.document.pages.single().variants.single().spans.map { it.rawText })
        assertTrue(r.formatResult.sources.all { it.row==null && it.column==null })
        assertTrue(r.regions.filter { it.kind==OcrRegionKind.RECORD }.all { it.spanIds.isNotEmpty() })
    }
    @Test fun dateListRecognizesExactHeadersSplitIntoThreeOrFourNativeWords() {
        val pieces=mapOf("课程名称" to listOf("课程","名","称"),"课程代码" to listOf("课","程","代","码"),
            "实验项目" to listOf("实","验","项","目"),"实验室" to listOf("实验","室"))
        val spans=dateRows(3).flatMap {span->
            val split=pieces[span.rawText]
            if(span.position!!.bounds.top!=20f || split==null) listOf(span) else {
                var x=span.position.bounds.left
                split.map {part->word(part,x,20f,part.length*16f).also {x+=part.length*16f+1f}}
            }
        }
        val parsed=parse(spans)
        val courses=parsed.formatResult.draft!!.courses
        assertEquals(3,courses.sumOf {it.meetings.size})
        assertEquals(setOf("DEMO-A","DEMO-B"),courses.map {it.sourceCourseCode}.toSet())
        assertEquals(setOf("室0","室1","室2"),courses.flatMap {it.meetings}.map {it.room}.toSet())
        assertEquals(spans.size,parsed.coverage.size)
        assertEquals(spans.map {it.rawText},parsed.document.pages.single().variants.single().spans.map {it.rawText})
    }
    private fun dateRowsWithUnrecognizedWeekdayHeader():List<OcrSpan> = buildList {
        listOf(Triple("未知列",50f,36f),Triple("时间",287f,28f),Triple("节次/时间",379f,70f),
            Triple("班级名称",500f,64f),Triple("课程名称",620f,64f),Triple("项目名称",740f,64f),Triple("实验室",860f,48f))
            .forEach {(text,x,width)->add(word(text,x,20f,width))}
        listOf("@20261015","@20261021").forEachIndexed {row,date->
            val y=60f+row*80f
            add(word("第6周",50f,y,52f));add(word(if(row==0) "星期4" else "星期3",165f,y,44f))
            add(word(date,290f,y,80f));add(word("1-4(08:00-11:50)",380f,y,110f))
            add(word("示例组",500f,y,90f));add(word("示例课程",620f,y,90f));add(word("项目甲",740f,y,90f));add(word("实验室甲",860f,y,100f))
        }
    }
    @Test fun dateListUsesAlignedLiteralDatesWhenMissingHeaderMixesNeighborTextIntoBucket() {
        val spans=dateRowsWithUnrecognizedWeekdayHeader()
        val parsed=parse(spans)
        val meetings=parsed.formatResult.draft!!.courses.flatMap {it.meetings}
        assertEquals(2,meetings.size)
        assertEquals(setOf(LocalDate.of(2026,10,15),LocalDate.of(2026,10,21)),meetings.flatMap {it.dates}.toSet())
        assertTrue(parsed.coverage.any {it.span.rawText=="星期4" && it.status==SpanCoverageStatus.UNPARSED})
        assertTrue(meetings.all {m->m.warnings.any {it.code=="DATE_ICON_PREFIX_REVIEW_REQUIRED"}})
        assertEquals(spans.map {it.rawText},parsed.document.pages.single().variants.single().spans.map {it.rawText})
    }
    @Test fun dateListDoesNotExtractDatesFromEmailsOrOtherColumns() {
        val email=dateRowsWithUnrecognizedWeekdayHeader().map {if(it.rawText.startsWith('@')) it.copy(rawText="user"+it.rawText) else it}
        assertTrue(parse(email).formatResult.draft!!.courses.isEmpty())
        val moved=dateRowsWithUnrecognizedWeekdayHeader().map {if(it.rawText.startsWith('@')) it.copy(position=SourcePosition(1,TextBox(40f,it.position!!.bounds.top,120f,it.position.bounds.bottom))) else it}
        assertTrue(parse(moved).formatResult.draft!!.courses.isEmpty())
    }
    @Test fun gridExplicitCodesGroupOnlyIdentityAndKeepEighteenMeetingsPlusPractice() {
        val r=parse(grid()); val cs=r.formatResult.draft!!.courses
        assertEquals(11,cs.size); assertEquals(20,cs.sumOf { it.meetings.size })
        assertEquals(9,cs.count { it.sourceCourseCode!=null })
        assertEquals(2,cs.flatMap { it.meetings }.count { it.kind==MeetingKind.UNSCHEDULED && it.dayOfWeek==null && it.sections.isEmpty() })
        assertTrue(cs.filter { it.sourceCourseCode!=null }.all { it.meetings.size==2 })
        assertEquals(setOf(1,3),cs.first { it.sourceCourseCode=="D0" }.meetings.first().sections)
    }
    @Test fun sameTitleWithoutCodeProducesProposalsNeverConfirmedArchives() {
        val r=parse(grid(false)); assertEquals(20,r.candidateCourses.size)
        assertEquals(20,r.formatResult.draft!!.courses.size)
        assertEquals(10,r.identityProposals.size) // nine body titles plus independent practices
        assertTrue(r.identityProposals.all { it.requiresConfirmation && it.memberIds.size==2 && it.evidence==ArchiveIdentityEvidence.SAME_TITLE })
        assertEquals(20,r.counts.meetings);assertEquals(0,r.counts.confirmedArchives)
        assertTrue(r.formatResult.issues.any { it.code=="ARCHIVE_IDENTITY_REVIEW_REQUIRED" })
        assertTrue(parse(grid(false)).identityProposals.all { it.requiresConfirmation })
    }
    @Test fun matrixRetainsEmptyWeeksSpecialDatesAndThreeIndependentAnnotations() {
        val r=parse(matrix()); val cs=r.formatResult.draft!!.courses
        assertTrue(r.formatResult.draft!!.adapterIds.contains("week-matrix"))
        val weekly=cs.flatMap { it.meetings }.filter { it.kind==MeetingKind.RECURRING }
        assertEquals(18,weekly.size);assertEquals((1..19).toSet()-5,weekly.flatMap { it.weeks }.toSet())
        assertTrue(weekly.all { it.sections==setOf(1,3) && it.dayOfWeek==1 })
        assertEquals(setOf(LocalDate.of(2026,12,30),LocalDate.of(2027,1,6)),cs.flatMap { it.meetings }.flatMap { it.dates }.toSet())
        assertEquals(3,r.regions.count { it.kind==OcrRegionKind.ANNOTATION })
        assertEquals(0,r.formatResult.pendingExceptions.size)
    }
    @Test fun geometryIsOrderScaleAndTranslationIndependent() {
        val source=dateRows(4)
        val transformed=source.reversed().map { s -> val b=s.position!!.bounds;s.copy(position=SourcePosition(1,TextBox(b.left*.7f+17,b.top*.7f+31,b.right*.7f+17,b.bottom*.7f+31))) }
        fun payload(r:OcrLayoutParseResult)=r.formatResult.draft!!.courses.map { it.copy(sources=emptyList(),meetings=it.meetings.map { m -> m.copy(sources=emptyList(),warnings=emptyList()) }) }
        assertEquals(payload(parse(source)),payload(parse(transformed)))
    }
    @Test fun allVariantSymbolUnknownLowConfidenceAndUnpositionedEvidenceSurvives() {
        val words=dateRows(1)+word("未知尾注",20f,500f,confidence=2f)+OcrSpan("无位置",OcrLevel.WORD,null,null)+word("字",1f,1f).copy(level=OcrLevel.SYMBOL)
        val p=page(words);val alt=p.variants.single().copy(id=OcrVariantId.HALF_SCALE,spans=listOf(word("另版",20f,50f)),rasterWidth=749,rasterHeight=999)
        val r=OcrTimetableParser().parse(OcrLayoutDocument(listOf(p.copy(variants=p.variants+alt))),context)
        assertEquals(words.size+1,r.coverage.size);assertEquals(1,r.coverage.count { it.status==SpanCoverageStatus.ALTERNATE_ONLY })
        assertTrue(r.formatResult.unparsedSources.any { it.rawText=="无位置" });assertTrue(r.coverage.any { it.status==SpanCoverageStatus.UNPARSED && it.span.rawText=="未知尾注" })
        assertEquals(1,r.formatResult.draft!!.courses.sumOf { it.meetings.size })
        assertTrue(r.formatResult.issues.any { it.code=="OCR_REVIEW_REQUIRED" })
    }
    @Test fun failedRecommendedVariantCannotProduceCourses() {
        val p=page(dateRows(1));val failed=p.variants.single().copy(failure=OcrFailure.NATIVE_FAILED,usable=false)
        val r=OcrTimetableParser().parse(OcrLayoutDocument(listOf(p.copy(variants=listOf(failed)))),context)
        assertNull(r.formatResult.draft);assertTrue(r.coverage.all { it.status==SpanCoverageStatus.ALTERNATE_ONLY })
    }
    @Test fun missingHeadersAndUnknownBodyNeverPretendComplete() {
        val r=parse(dateRows(2).drop(10));assertTrue(r.formatResult.draft!!.courses.isEmpty())
        assertTrue(r.coverage.all { it.status==SpanCoverageStatus.UNPARSED })
        assertTrue(r.formatResult.issues.any { it.code=="UNSUPPORTED_LAYOUT" })
    }
    @Test fun resourceAndCancellationAreAtomic() {
        val r=parse(dateRows(2),context.copy(limits=OcrLayoutLimits(maxRawSpans=5)))
        assertNull(r.formatResult.draft);assertTrue(r.candidateCourses.isEmpty());assertEquals("LIMIT_EXCEEDED",r.formatResult.issues.single().code)
        val comparisons=parse(dateRows(2),context.copy(limits=OcrLayoutLimits(maxComparisons=1)))
        assertNull(comparisons.formatResult.draft)
        try { parse(dateRows(),context.copy(format=context.format.copy(cancellation=ImportCancellation { throw CancellationException("synthetic") })));fail() } catch(_:CancellationException) { }
    }
    @Test fun invalidDateAndDeclaredWeekdayConflictRetainLiteralSource() {
        val s=dateRows(1).map { if(it.rawText=="20261228") it.copy(rawText="20260230") else it }
        val r=parse(s);assertTrue(r.formatResult.issues.any { it.code=="INVALID_DATE" })
        assertTrue(r.formatResult.draft!!.courses.flatMap { it.meetings }.all { it.kind==MeetingKind.UNSCHEDULED && it.dates.isEmpty() })
        val conflict=parse(dateRows(1).map { if(it.position?.bounds?.left==140f && it.rawText=="1") it.copy(rawText="7") else it })
        assertTrue(conflict.formatResult.issues.any { it.code=="DATE_WEEKDAY_MISMATCH" })
    }
    @Test fun naturalGridMultilineGrammarFooterAndConflictingAxisAreReviewable() {
        val spans=mutableListOf(word("星期一",300f,20f),word("星期二",700f,20f),word("第1节",10f,70f),word("第2节",10f,180f))
        spans+=word("示例数学",300f,70f)
        spans+=word("(1-2节)1-3,5-7(单),10周/校区:示例校区",300f,90f,220f)
        spans+=word("场地:示例楼101/教师:示例教师",300f,110f,220f)
        spans+=word("实践课程：示例实践(共1周)/17周；示例实践(共1周)/18周",10f,300f,900f)
        val r=parse(spans); val meetings=r.formatResult.draft!!.courses.flatMap { it.meetings }
        assertEquals(3,meetings.size)
        val m=meetings.single { it.kind==MeetingKind.RECURRING }
        assertEquals(setOf(1,2,3,5,7,10),m.weeks);assertEquals(setOf(1,2),m.sections)
        assertEquals("示例楼101",m.room);assertEquals("示例教师",m.teacher)
        assertEquals(setOf(setOf(17),setOf(18)),meetings.filter { it.kind==MeetingKind.UNSCHEDULED }.map { it.weeks }.toSet())
        assertTrue(r.formatResult.issues.any { it.code=="SECTION_AXIS_MISMATCH" })
    }
    @Test fun naturalDateTimeHeaderCourseCodeAndLabCodeStaySeparate() {
        val headers=listOf("周次","星期","时间","节次/时间","班级名称","课程名称","项目名称","实验室")
        val spans=headers.mapIndexed { i,s -> word(s,i*170f,20f,130f) }.toMutableList()
        listOf("第6周","星期4","20261015","1-4(08:00-11:50)","示例班1,示例班2","示例综合实践(171500001)","项目甲","示例实验室(1101031)").forEachIndexed { i,s -> spans+=word(s,i*170f,70f,140f) }
        val r=parse(spans);val c=r.formatResult.draft!!.courses.single();val m=c.meetings.single()
        assertEquals("171500001",c.sourceCourseCode);assertEquals("示例综合实践",c.name)
        assertEquals("示例实验室(1101031)",m.room);assertEquals("示例班1,示例班2",m.group)
        assertEquals(MeetingKind.DATE_SPECIFIC,m.kind);assertEquals(setOf(LocalDate.of(2026,10,15)),m.dates)
        assertEquals(setOf(1,2,3,4),m.sections);assertNotNull(m.customTime)
    }
    @Test fun matrixTrianglesAndMultiplePeriodsPreserveAlternativesWithoutInventingMeetings() {
        val spans=mutableListOf(word("教学周",10f,20f),word("日期范围",80f,20f),word("星期一",300f,20f,250f),word("星期二",700f,20f,250f))
        (1..5).forEach { spans+=word("$it",280f+it*50,45f,20f);spans+=word("$it",680f+it*50,45f,20f) }
        spans+=word("第2周",10f,100f);spans+=word("第4周",10f,180f)
        spans+=word("示例制剂/示例制药",320f,100f,210f)
        spans+=word("△示例课程(第一组)",730f,180f,160f)
        spans+=word("节假日:示例节日",10f,270f,300f)
        val r=parse(spans);val meetings=r.formatResult.draft!!.courses.flatMap { it.meetings }
        assertEquals(2,meetings.size);assertTrue(meetings.all { it.kind==MeetingKind.UNSCHEDULED })
        assertTrue(r.regions.any { it.alternatives.isNotEmpty() })
        assertEquals(1,r.regions.count { it.kind==OcrRegionKind.ANNOTATION })
        assertTrue(r.formatResult.issues.any { it.code=="AMBIGUOUS_CELL_SPAN" })
    }    @Test fun sourceFieldsIncludeMeasuredDateWeekDayAndSectionGeometry() {
        val g=parse(grid());val first=g.formatResult.draft!!.courses.first { it.sourceCourseCode=="D0" }.meetings.first()
        assertTrue(first.sources.any { it.rawText=="星期1" })
        assertTrue(first.sources.any { it.rawText=="第1节" })
        val m=parse(matrix()).formatResult.draft!!.courses.first { it.sourceCourseCode=="M" }.meetings.first()
        assertTrue(m.sources.any { it.rawText=="第1周" });assertTrue(m.sources.any { it.rawText=="1,3节" })
        assertTrue(m.sources.any { it.rawText=="星期一" })
        val special=parse(matrix()).formatResult.draft!!.courses.flatMap { it.meetings }.first { it.kind==MeetingKind.DATE_SPECIFIC }
        assertTrue(special.sources.any { it.rawText=="2026-12-30" })
    }
    @Test fun oddHalfCoordinatesAreAlreadyCanonicalAndOnlySelectedVariantProducesCourses() {
        val s=dateRows(2)
        val p=page(s);val alternate=p.variants.single().copy(id=OcrVariantId.HALF_SCALE,rasterWidth=749,rasterHeight=999,
            rasterToPage=PageAffineTransform(1500.0/749,0.0,0.0,2000.0/999,0.0,0.0),
            spans=s.map { if(it.rawText=="示例甲") it.copy(rawText="另一标题") else it })
        val r=OcrTimetableParser().parse(OcrLayoutDocument(listOf(p.copy(variants=p.variants+alternate,recommendedVariantId=OcrVariantId.HALF_SCALE))),context)
        assertEquals(setOf("另一标题","示例乙"),r.formatResult.draft!!.courses.map { it.name }.toSet())
        assertEquals(2,r.counts.meetings)
        assertEquals(s.size,r.coverage.count { it.status==SpanCoverageStatus.ALTERNATE_ONLY })
        assertEquals(s.first().position,r.coverage.first { it.id.variantId==OcrVariantId.HALF_SCALE }.span.position)
    }
    @Test fun ambiguousAdaptersHaveNoMergedCoursesAndRetainEverySpan() {
        val s=dateRows(1)+listOf(word("星期一",500f,800f),word("星期二",900f,800f),word("第1节",20f,850f),word("示例/周次:1/节次:1",500f,850f,200f))
        val r=parse(s);assertTrue(r.formatResult.draft!!.courses.isEmpty());assertEquals(2,r.layoutCandidates.size)
        assertTrue(r.formatResult.issues.any { it.code=="AMBIGUOUS_LAYOUT" });assertEquals(s.size,r.coverage.size)
        assertTrue(r.coverage.all { it.status==SpanCoverageStatus.UNPARSED })
    }
    @Test fun blankCellRequiresFourLineBoundariesAndStillRequiresReview() {
        val s=listOf(word("星期一",250f,20f),word("星期二",650f,20f),word("第1节",10f,70f),word("第2节",10f,170f),word("示例/周次:1/节次:1",650f,70f,160f))
        val lines=listOf(TableLineEvidence(true,TextBox(85f,30f,87f,125f),2),TableLineEvidence(true,TextBox(485f,30f,487f,125f),2),
            TableLineEvidence(false,TextBox(86f,29f,486f,31f),2),TableLineEvidence(false,TextBox(86f,124f,486f,126f),2))
        val r=OcrTimetableParser().parse(OcrLayoutDocument(listOf(page(s,lines=lines))),context)
        assertTrue(r.regions.any { it.kind==OcrRegionKind.EMPTY_BODY && it.spanIds.isEmpty() })
        assertTrue(r.formatResult.issues.any { it.code=="EMPTY_BODY_REGION_REVIEW_REQUIRED" })
        assertFalse(OcrTimetableParser().parse(OcrLayoutDocument(listOf(page(s,lines=lines.drop(1)))),context).regions.any { it.kind==OcrRegionKind.EMPTY_BODY })
    }
    @Test fun sameTitleDifferentClassCodesRemainSeparateAndProposalsContainAllSources() {
        val s=dateRows(2).map { if(it.rawText=="示例乙") it.copy(rawText="示例甲") else it }
        val r=parse(s);assertEquals(2,r.formatResult.draft!!.courses.size);assertEquals(0,r.identityProposals.size)
        val noCodes=grid(false);val q=parse(noCodes)
        assertTrue(q.identityProposals.all { proposal -> proposal.memberIds.all { id -> q.candidateCourses.any { it.id==id } } && proposal.spanIds.all { id -> q.coverage.any { it.id==id } } })
        assertTrue(q.identityProposals.first().copy().requiresConfirmation)
    }
    @Test fun documentWideSpanTextPageRegionAndCourseLimitsNeverReturnPartialDraft() {
        val p=page(dateRows(1))
        val two=OcrLayoutDocument(listOf(p,p.copy(pageNumber=2,variants=p.variants.map { v->v.copy(page=v.page!!.copy(number=2),spans=v.spans.map { it.copy(position=it.position!!.copy(page=2)) }) })))
        assertNull(OcrTimetableParser().parse(two,context.copy(limits=OcrLayoutLimits(maxRawSpans=30))).formatResult.draft)
        assertNull(OcrTimetableParser().parse(two,context.copy(limits=OcrLayoutLimits(maxPages=1))).formatResult.draft)
        assertNull(parse(dateRows(3),context.copy(limits=OcrLayoutLimits(maxTextUnits=100))).formatResult.draft)
        assertNull(parse(dateRows(3),context.copy(limits=OcrLayoutLimits(maxRegions=1))).formatResult.draft)
        assertNull(parse(dateRows(3),context.copy(limits=OcrLayoutLimits(maxCandidates=1))).formatResult.draft)
        var probes=0
        try { parse(dateRows(3),context.copy(format=context.format.copy(cancellation=ImportCancellation { if(++probes>100) throw CancellationException("synthetic") })));fail() } catch(_:CancellationException) { assertTrue(probes>100) }
    }
    @Test fun missingHeaderContinuationDoesNotInheritFromPageWidthAlone() {
        val s=listOf(word("星期一",300f,20f),word("星期二",800f,20f),word("第1节",10f,70f),word("示例/周次:1/节次:1",300f,70f,200f))
        val p1=page(s)
        val s2=listOf(word("第2节",10f,70f,page=2),word("示例/周次:1/节次:2",300f,70f,200f,page=2))
        val r=OcrTimetableParser().parse(OcrLayoutDocument(listOf(p1,page(s2,2))),context)
        assertEquals(1,r.counts.meetings);assertTrue(r.coverage.filter { it.id.pageNumber==2 }.all { it.status==SpanCoverageStatus.UNPARSED })
    }
    @Test fun explicitSparseMergedScopesNeverFillTheGaps() {
        val s=listOf(word("教学周",10f,20f),word("日期范围",100f,20f),word("星期一",400f,20f,200f),word("1节",400f,45f),word("3节",600f,45f),
            word("第1周",10f,100f),word("第3周",10f,180f),word("示例/课程代码:X/周次:1,3/节次:1,3",400f,90f,240f,110f))
        val r=parse(s);val m=r.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(MeetingKind.RECURRING,m.kind);assertEquals(setOf(1,3),m.weeks);assertEquals(setOf(1,3),m.sections)
    }
    @Test fun matrixAndGridShuffleScalingVariableColumnsKeepExactPayload() {
        for(s in listOf(matrix(),grid())) {
            val moved=s.reversed().map { val b=it.position!!.bounds;it.copy(position=SourcePosition(1,TextBox(b.left*.6f+50,b.top*.6f+25,b.right*.6f+50,b.bottom*.6f+25))) }
            fun payload(r:OcrLayoutParseResult)=r.formatResult.draft!!.courses.map { c -> c.copy(sources=emptyList(),meetings=c.meetings.map { it.copy(sources=emptyList(),warnings=emptyList()) }) }
            assertEquals(payload(parse(s)),payload(parse(moved)))
        }
    }    @Test fun inlineUnknownFieldRemainsUnparsedWhileKnownMeetingSurvives() {
        val s=grid().map { if(it.rawText.startsWith("示例0/")) it.copy(rawText=it.rawText+"/未知说明:待核对") else it }
        val r=parse(s);assertEquals(20,r.counts.meetings)
        assertTrue(r.coverage.filter { it.span.rawText.contains("未知说明") }.all { it.status==SpanCoverageStatus.UNPARSED && it.paths.isNotEmpty() })
        assertTrue(r.formatResult.unparsedSources.any { it.rawText.contains("未知说明") })
        assertTrue(r.formatResult.issues.any { it.code=="UNPARSED_RECORD_CONTENT" })
    }
    @Test fun unknownExtraDateColumnAndSplitHeaderAreRetainedSeparately() {
        val s=dateRows(1).map { val b=it.position!!.bounds;it.copy(position=it.position.copy(bounds=TextBox(b.left*.8f,b.top,b.right*.8f,b.bottom))) }.toMutableList()
        val header=s.indexOfFirst { it.rawText=="课程名称" };val original=s.removeAt(header);val b=original.position!!.bounds
        s+=original.copy(rawText="课程",position=original.position.copy(bounds=b.copy(right=b.left+30f)))
        s+=original.copy(rawText="名称",position=original.position.copy(bounds=b.copy(left=b.left+32f,right=b.left+62f)))
        s+=word("额外说明",1300f,20f);s+=word("待核对附加内容",1300f,60f)
        val r=parse(s);assertEquals("示例甲",r.formatResult.draft!!.courses.single().name)
        assertEquals("室0",r.formatResult.draft!!.courses.single().meetings.single().room)
        assertTrue(r.coverage.filter { it.span.rawText in setOf("课程","名称") }.all { it.status==SpanCoverageStatus.STRUCTURAL_ANCHOR })
        assertTrue(r.formatResult.unparsedSources.any { it.rawText=="待核对附加内容" })
    }
    @Test fun singleTitleContainerRetainsMultipleSchedulesAndIndividualClockOnly() {
        val s=listOf(word("星期一",350f,20f),word("星期三",850f,20f),word("1 08:00-08:45",10f,70f,140f),word("3-5 10:00-12:00",10f,300f,160f),
            word("示例分段课程",350f,70f),word("(1,3节)1,3周/教师:师甲/场地:甲室",350f,90f,280f),word("(2,5节)2,4周/教师:师乙/场地:乙室",350f,120f,280f))
        val r=parse(s);val c=r.formatResult.draft!!.courses.single()
        assertEquals(2,c.meetings.size);assertEquals(listOf(setOf(1,3),setOf(2,5)),c.meetings.map { it.sections })
        assertEquals(listOf("师甲","师乙"),c.meetings.map { it.teacher })
        assertEquals(1,r.formatResult.draft!!.periodProposals.size);assertEquals(1,r.formatResult.draft!!.periodProposals.single().section)
    }
    @Test fun continuationRequiresMeasuredBoundaryAlignmentSequentialSectionsAndGridStructure() {
        val first=listOf(word("星期一",300f,20f),word("星期二",800f,20f),word("第1节",10f,70f),word("示例/周次:1/节次:1",300f,70f,200f))
        val second=listOf(word("第2节",10f,70f,page=2),word("继续示例/周次:2/节次:2",300f,70f,200f,page=2))
        // Day boundaries derived from measured first-page anchors: 86,586,1086.
        val lines=listOf(86f,586f,1086f).map { x -> TableLineEvidence(true,TextBox(x-1,40f,x+1,140f),2) }+
            listOf(40f,140f).map { y -> TableLineEvidence(false,TextBox(86f,y-1,1086f,y+1),2) }
        val r=OcrTimetableParser().parse(OcrLayoutDocument(listOf(page(first),page(second,2,lines))),context)
        assertEquals(2,r.counts.meetings)
        val brokenLines=lines.map { it.copy(bounds=it.bounds.copy(left=it.bounds.left+100,right=it.bounds.right+100)) }
        val broken=OcrTimetableParser().parse(OcrLayoutDocument(listOf(page(first),page(second,2,brokenLines))),context)
        assertEquals(1,broken.counts.meetings)
        val nonSequential=second.map { if(it.rawText=="第2节") it.copy(rawText="第4节") else it }
        assertEquals(1,OcrTimetableParser().parse(OcrLayoutDocument(listOf(page(first),page(nonSequential,2,lines))),context).counts.meetings)
    }    @Test fun eachScheduleInOneContainerHasItsOwnSourcePaths() {
        val s=listOf(word("星期一",350f,20f),word("星期三",850f,20f),word("第1节",10f,70f),word("第5节",10f,300f),
            word("示例分段",350f,70f),word("(1节)1,3周/教师:甲/场地:甲室",350f,90f,280f),word("(5节)2,4周/教师:乙/场地:乙室",350f,120f,280f))
        val r=parse(s);val c=r.formatResult.draft!!.courses.single()
        assertEquals(2,c.meetings.size)
        assertFalse(c.meetings.first().sources.any { it.rawText.contains("教师:乙") })
        assertTrue(r.coverage.single { it.span.rawText.contains("教师:乙") }.paths.all { it.meetingIndex==1 })
        assertTrue(r.coverage.single { it.span.rawText=="示例分段" }.paths.map { it.meetingIndex }.toSet().containsAll(setOf(0,1)))
    }
    @Test fun holidayWordsInsideCourseNamesAreNotAnnotationsOrGlobalCancellation() {
        val s=matrix().map { if(it.rawText.startsWith("矩阵示例/")) it.copy(rawText=it.rawText.replace("矩阵示例","节日文化研究")) else it }
        val r=parse(s);assertEquals(18,r.formatResult.draft!!.courses.first { it.sourceCourseCode=="M" }.meetings.size)
        assertEquals(3,r.regions.count { it.kind==OcrRegionKind.ANNOTATION })
        assertTrue(r.formatResult.pendingExceptions.isEmpty())
    }
    @Test fun sameTitleDifferentGroupsRemainIndependentWithoutKeys() {
        val s=listOf(word("星期一",300f,20f),word("星期二",800f,20f),word("第1节",10f,70f),word("第2节",10f,200f),
            word("同名示例/周次:1,3/节次:1/班组:甲组",300f,70f,250f),word("同名示例/周次:2,4/节次:2/班组:乙组",300f,200f,250f))
        val r=parse(s);assertEquals(2,r.formatResult.draft!!.courses.size)
        assertEquals(setOf("甲组","乙组"),r.formatResult.draft!!.courses.flatMap { it.meetings }.map { it.group }.toSet())
        assertEquals(1,r.identityProposals.size);assertEquals(0,r.counts.confirmedArchives)
    }
    @Test fun invalidCoordinatesAndWholeDocumentAlternateBudgetRejectAtomically() {
        val s=dateRows(1);val p=page(s);val bad=s.mapIndexed { i,t->if(i==0) t.copy(position=t.position!!.copy(bounds=TextBox(-1f,20f,10f,30f))) else t }
        assertNull(parse(bad).formatResult.draft)
        val alt=p.variants.single().copy(id=OcrVariantId.HALF_SCALE)
        val r=OcrTimetableParser().parse(OcrLayoutDocument(listOf(p.copy(variants=p.variants+alt))),context.copy(limits=OcrLayoutLimits(maxRawSpans=s.size)))
        assertNull(r.formatResult.draft);assertTrue(r.candidateCourses.isEmpty())
        val layer=p.copy(textLayerEvidence=PositionedPage(1,1500f,2000f,listOf(PositionedText("extra",SourcePosition(1,TextBox(1f,1f,10f,10f))))))
        assertNull(OcrTimetableParser().parse(OcrLayoutDocument(listOf(layer)),context.copy(limits=OcrLayoutLimits(maxRawSpans=s.size))).formatResult.draft)
    }
    @Test fun explicitMergedCourseCodeCanCoverIndependentDateRowsWithoutFillDown() {
        val s=dateRows(2).filterNot { it.rawText in setOf("DEMO-A","DEMO-B") }.map { if(it.rawText=="示例乙") it.copy(rawText="示例甲") else it }.toMutableList()
        s+=word("MERGED-CODE",840f,58f,110f,42f)
        val r=parse(s);assertEquals(1,r.formatResult.draft!!.courses.size);assertEquals(2,r.counts.meetings)
        assertTrue(r.formatResult.draft!!.courses.single().meetings.all { m->m.sources.any { it.rawText=="MERGED-CODE" } })
    }    @Test fun composedCellTextLimitIsCheckedBeforeJoiningWrappedSpans() {
        val s=listOf(word("星期一",300f,20f),word("星期二",800f,20f),word("第1节",10f,70f),word("第2节",10f,300f),
            word("甲".repeat(9000),300f,70f,200f),word("乙".repeat(9000),300f,90f,200f),word("(1节)1周",300f,110f,200f))
        val r=parse(s);assertNull(r.formatResult.draft);assertEquals("LIMIT_EXCEEDED",r.formatResult.issues.single().code)
    }
    @Test fun parentheticalTitleWordsNeverInventAnIdentityCode() {
        val s=dateRows(1).map { if(it.rawText=="示例甲") it.copy(rawText="示例(Advanced)") else it }.filterNot { it.rawText=="DEMO-A" }
        val r=parse(s);val c=r.formatResult.draft!!.courses.single()
        assertNull(c.sourceCourseCode);assertEquals("示例(Advanced)",c.name)
    }    @Test fun yearRomanGroupAndDifferentLongSuffixesDoNotConfirmArchiveIdentity() {
        for(title in listOf("示例课程(2026)","体育(V)篮球","△示例课程(第一组)")) {
            val s=dateRows(1).map { if(it.rawText=="示例甲") it.copy(rawText=title) else it }.filterNot { it.rawText=="DEMO-A" }
            val c=parse(s).formatResult.draft!!.courses.single();assertNull(c.sourceCourseCode);assertEquals(title,c.name)
        }
        val s=dateRows(2).filterNot { it.rawText in setOf("DEMO-A","DEMO-B") }.map { when(it.rawText) { "示例甲" -> it.copy(rawText="示例(123456789)");"示例乙" -> it.copy(rawText="示例(987654321)");else -> it } }
        val r=parse(s);assertEquals(2,r.formatResult.draft!!.courses.size);assertTrue(r.identityProposals.isEmpty())
        assertTrue(r.formatResult.issues.any { it.code=="COURSE_CODE_SUFFIX_REVIEW_REQUIRED" })
        val same=parse(s.map { if(it.rawText=="示例(987654321)") it.copy(rawText="示例(123456789)") else it })
        assertEquals(2,same.formatResult.draft!!.courses.size);assertEquals(1,same.identityProposals.size)
    }
    @Test fun measuredVerticalLinesCorrectUnequalWidthWeekdayBoundary() {
        val s=listOf(word("星期一",300f,20f),word("星期二",800f,20f),word("第1节",10f,70f),word("第2节",10f,170f),word("示例/周次:1/节次:1",510f,70f,80f))
        val lines=listOf(100f,500f,1100f).map { TableLineEvidence(true,TextBox(it-1,30f,it+1,250f),2) }
        val r=OcrTimetableParser().parse(OcrLayoutDocument(listOf(page(s,lines=lines))),context)
        assertEquals(2,r.formatResult.draft!!.courses.single().meetings.single().dayOfWeek)
    }
    @Test fun detectedBlankDateAndMatrixCellsRemainExplicitReviewRegions() {
        val dates=dateRows(2).filterNot { it.rawText=="室0" }
        val dateLines=listOf(TableLineEvidence(true,TextBox(1085f,30f,1087f,79f),2),TableLineEvidence(true,TextBox(1225f,30f,1227f,79f),2),TableLineEvidence(false,TextBox(1086f,29f,1226f,31f),2),TableLineEvidence(false,TextBox(1086f,78f,1226f,80f),2))
        val dr=OcrTimetableParser().parse(OcrLayoutDocument(listOf(page(dates,lines=dateLines))),context)
        assertTrue(dr.regions.any { it.kind==OcrRegionKind.EMPTY_BODY })
        val matrixLines=listOf(TableLineEvidence(true,TextBox(329f,305f,331f,365f),2),TableLineEvidence(true,TextBox(604f,305f,606f,365f),2),TableLineEvidence(false,TextBox(330f,304f,605f,306f),2),TableLineEvidence(false,TextBox(330f,364f,605f,366f),2))
        val mr=OcrTimetableParser().parse(OcrLayoutDocument(listOf(page(matrix(),lines=matrixLines))),context)
        assertTrue(mr.regions.any { it.kind==OcrRegionKind.EMPTY_BODY })
        assertEquals(20,mr.counts.meetings)
    }
    @Test fun noCodeBodyCountsSeparateRecordsProposalsMeetingsAndConfirmations() {
        val r=parse(grid(false).filterNot { it.rawText.startsWith("实践课程") })
        assertEquals(18,r.counts.courseRecordCandidates);assertEquals(9,r.counts.sameTitleProposalGroups)
        assertEquals(18,r.counts.meetings);assertEquals(0,r.counts.confirmedArchives)
    }    @Test fun selectedVariantDisagreementAndNativeIssuesRemainReviewable() {
        val p=page(dateRows(1));val original=p.variants.single()
        val alternate=original.copy(id=OcrVariantId.HALF_SCALE,spans=original.spans.map { if(it.rawText=="示例甲") it.copy(rawText="另一个标题") else it })
        val r=OcrTimetableParser().parse(OcrLayoutDocument(listOf(p.copy(variants=listOf(original,alternate),nativeIssues=setOf(OcrIssue.OCR_UNPOSITIONED_TEXT)))),context)
        assertTrue(r.formatResult.issues.any { it.code=="OCR_VARIANT_DISAGREEMENT" })
        assertTrue(r.formatResult.issues.any { it.code=="OCR_ALTERNATE_REVIEW_REQUIRED" })
        assertTrue(r.formatResult.issues.any { it.code=="OCR_UNPOSITIONED_TEXT" })
        assertEquals(1,r.counts.meetings)
    }
    @Test fun matrixSpecialDatesKeepDateAuthorityAndWarnAgainstWeekdayColumns() {
        val r=parse(matrix())
        val m=r.formatResult.draft!!.courses.flatMap { it.meetings }.first { LocalDate.of(2026,12,30) in it.dates }
        assertEquals(MeetingKind.DATE_SPECIFIC,m.kind);assertEquals(setOf(LocalDate.of(2026,12,30)),m.dates)
        assertTrue(m.warnings.any { it.code=="DATE_WEEKDAY_MISMATCH" })
    }
    @Test fun matrixCrossDayMergedBoxNeverUsesItsCenterAsASchedule() {
        val raw="跨日课程/教师:甲"
        val r=parse(matrix()+word(raw,350f,90f,650f))
        val m=r.formatResult.draft!!.courses.single { it.name=="跨日课程" }.meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertNull(m.dayOfWeek)
        assertTrue(m.sections.isEmpty())
        assertTrue(m.sources.any { it.rawText==raw })
        assertTrue(m.warnings.any { it.code=="AMBIGUOUS_CELL_SPAN" })
        assertTrue(r.regions.single { region -> region.spanIds.any { id -> r.coverage.any { it.id==id && it.span.rawText==raw } } }.alternatives.isNotEmpty())
    }
    @Test fun matrixCrossDayBoundaryIsAmbiguousEvenWithoutTouchingDayAnchors() {
        val raw="跨界课程/教师:甲/周次:1/节次:1,3"
        val r=parse(matrix().filterNot { it.rawText.startsWith("矩阵示例/") }+word(raw,550f,90f,100f)) // Crosses the measured day edge at x=605.
        val m=r.formatResult.draft!!.courses.single { it.name=="跨界课程" }.meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertNull(m.dayOfWeek)
        assertEquals(setOf(1,3),m.sections);assertEquals(setOf(1),m.weeks)
    }
    @Test fun matrixMergedOrdinaryAndSpecialDateRowsCannotBeResolvedByWeeksAlone() {
        val raw="跨周日期课程/教师:甲/周次:19"
        val r=parse(matrix().filterNot { it.rawText.startsWith("矩阵示例/") || it.rawText.startsWith("特殊") }+word(raw,350f,1170f,170f,100f))
        val m=r.formatResult.draft!!.courses.single { it.name=="跨周日期课程" }.meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertTrue(m.dates.isEmpty())
        assertEquals(setOf(19),m.weeks)
        assertTrue(m.warnings.any { it.code=="AMBIGUOUS_CELL_SPAN" })
    }
    @Test fun matrixMergedSpecialDateRowsRetainReviewWithoutChoosingOneDate() {
        val raw="跨日期课程/教师:甲"
        val r=parse(matrix().filterNot { it.rawText.startsWith("特殊") }+word(raw,350f,1250f,170f,70f))
        val m=r.formatResult.draft!!.courses.single { it.name=="跨日期课程" }.meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertTrue(m.dates.isEmpty())
        assertTrue(m.sources.any { it.rawText==raw })
        assertTrue(r.regions.any { it.alternatives.isNotEmpty() })
    }
    private fun twoPeriodMatrix(raw:String,x:Float,width:Float)=listOf(
        word("教学周",10f,20f),word("星期一",420f,20f,160f),
        word("1节",400f,45f),word("3节",600f,45f),
        word("第1周",10f,100f),word("第2周",10f,180f),word(raw,x,100f,width))
    @Test fun matrixPeriodEdgeCrossingWithoutAnchorCentersIsUnscheduled() {
        val raw="节次跨界示例/教师:甲"
        val r=parse(twoPeriodMatrix(raw,510f,60f)) // Crosses x=536, covers neither x=436 nor x=636.
        val m=r.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertTrue(m.sections.isEmpty())
        assertTrue(m.warnings.any { it.code=="AMBIGUOUS_CELL_SPAN" })
        assertTrue(r.regions.any { it.alternatives.isNotEmpty() })
    }
    @Test fun matrixExplicitSparseSectionsResolveMeasuredPeriodScopeWithoutFillingHoles() {
        val r=parse(twoPeriodMatrix("明确跨节示例/教师:甲/节次:1,3",410f,270f))
        val m=r.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(MeetingKind.RECURRING,m.kind);assertEquals(setOf(1,3),m.sections)
    }
    @Test fun explicitMatrixCancellationIsPendingWithRealSourcesAndNeverAttachedByTitle() {
        val raw="停课:2026-12-30 示例数学 10:00-11:30"
        val body=matrix().map { if(it.rawText.startsWith("矩阵示例/")) it.copy(rawText=it.rawText.replace("矩阵示例","示例数学")) else it }
        val r=parse(body+word(raw,10f,1510f,600f))
        assertEquals(1,r.formatResult.pendingExceptions.size)
        val pending=r.formatResult.pendingExceptions.single()
        assertEquals(RichExceptionKind.CANCEL,pending.exception.kind)
        assertEquals("示例数学",pending.courseName);assertEquals(LocalDate.of(2026,12,30),pending.exception.originalDate)
        assertEquals("10:00",pending.exception.customTime!!.start.toString());assertEquals("11:30",pending.exception.customTime!!.end.toString())
        assertNull(pending.courseKey);assertNull(pending.meetingKey);assertTrue(pending.candidatePaths.isEmpty())
        assertEquals("EXPLICIT_ATTACHMENT_REQUIRED",pending.reason)
        assertTrue(pending.exception.warnings.any { it.code=="PENDING_EXCEPTION_ATTACHMENT" })
        assertTrue(pending.sources.all { it.rawText==raw && it.sourceLocator.startsWith("ocr:") && it.row==null && it.column==null })
        assertTrue(r.formatResult.sources.containsAll(pending.sources))
        assertEquals(listOf(SourceExcerpt(SourcePosition(1,TextBox(10f,1510f,610f,1520f)),raw)),pending.exception.sources)
        assertEquals(20,r.counts.meetings)
        assertTrue(r.formatResult.draft!!.courses.flatMap { it.meetings }.all { it.exceptions.isEmpty() })
        assertTrue(r.regions.any { it.kind==OcrRegionKind.ANNOTATION && it.spanIds.contains(r.coverage.single { c -> c.span.rawText==raw }.id) })
    }
    @Test fun genericHolidayAndIncompleteCancellationRemainEvidenceWithoutCoursesOrProposals() {
        for(raw in listOf("节假日:2026-12-30 示例数学 10:00-11:30","停课:2026-12-30 10:00-11:30","停课:示例数学 10:00-11:30",
            "停课:2026-12-30 示例数学","停课:2026-02-30 示例数学 10:00-11:30","停课:2026-12-30 示例数学 25:00-26:00",
            "停课:2026-12-30和2026-12-31 示例数学 10:00-11:30","停课:2026-12-30 示例数学 10:00-11:30 待定范围")) {
            val r=parse(matrix()+word(raw,10f,1510f,800f))
            assertTrue(raw,r.formatResult.pendingExceptions.isEmpty());assertEquals(raw,20,r.counts.meetings)
            assertTrue(raw,r.document.pages.single().variants.single().spans.any { it.rawText==raw })
            val coverage=r.coverage.single { it.span.rawText==raw }
            assertTrue(raw,coverage.status in setOf(SpanCoverageStatus.ANNOTATION,SpanCoverageStatus.UNPARSED))
            if(raw.startsWith("停课")) assertTrue(raw,r.formatResult.unparsedSources.any { it.rawText==raw })
        }
    }
    @Test fun relativeCancellationNeedsExplicitBasisAndKeepsRelativeProposalUnattached() {
        val raw="停课:明日 示例数学 10:00-11:30"
        val without=parse(matrix()+word(raw,10f,1510f,600f))
        assertTrue(without.formatResult.pendingExceptions.isEmpty());assertTrue(without.formatResult.relativeDates.isEmpty())
        assertTrue(without.formatResult.unparsedSources.any { it.rawText==raw })
        val r=parse(matrix()+word(raw,10f,1510f,600f),context.copy(referenceDate=LocalDate.of(2026,12,29)))
        assertTrue(r.formatResult.pendingExceptions.isEmpty())
        val relative=r.formatResult.relativeDates.single()
        assertEquals(LocalDate.of(2026,12,30),relative.candidate);assertTrue(relative.requiresReview)
        assertEquals(raw,relative.source.rawText);assertTrue(relative.source.sourceLocator.startsWith("ocr:"))
        assertTrue(r.formatResult.issues.any { it.code=="RELATIVE_DATE_REVIEW" })
    }
    @Test fun matrixCancellationLimitRejectsWholeDraftAtomically() {
        val notices=listOf(word("停课:2026-12-30 示例数学 10:00-11:30",10f,1510f,600f),word("停课:2026-12-31 示例物理 10:00-11:30",10f,1540f,600f))
        val r=parse(matrix()+notices,context.copy(format=context.format.copy(limits=ImportLimits(maxExceptions=1))))
        assertNull(r.formatResult.draft);assertTrue(r.formatResult.pendingExceptions.isEmpty())
        assertEquals("LIMIT_EXCEEDED",r.formatResult.issues.single().code)
    }
    @Test fun termMondayCannotBecomeTheReferenceDayForTomorrowInMatrixNotes() {
        val raw="停课:明日 示例数学 10:00-11:30"
        val r=parse(matrix()+word(raw,10f,1510f,600f),context.copy(confirmedTermMonday=LocalDate.of(2026,9,7)))
        assertTrue(r.formatResult.relativeDates.isEmpty());assertTrue(r.formatResult.pendingExceptions.isEmpty())
        assertTrue(r.formatResult.unparsedSources.any { it.rawText==raw })
        assertTrue(r.formatResult.issues.any { it.code=="NOTICE_DATE_BASIS_REQUIRED" })
    }
    @Test fun nativeWordSplitCancellationIsGroupedBeforeMatrixDateRowsAndKeepsEverySource() {
        val words=listOf(word("停课:",10f,1510f,40f),word("2026-12-30",60f,1510f,100f),word("示例数学",350f,1510f,80f),word("10:00-11:30",440f,1510f,100f))
        val symbol=word("示",350f,1510f,8f).copy(level=OcrLevel.SYMBOL)
        val r=parse(matrix()+words+symbol)
        assertEquals(20,r.counts.meetings);assertEquals(1,r.formatResult.pendingExceptions.size)
        val p=r.formatResult.pendingExceptions.single()
        assertEquals("示例数学",p.courseName);assertEquals(LocalDate.of(2026,12,30),p.exception.originalDate)
        assertEquals(words.map { it.rawText }.toSet(),p.sources.map { it.rawText }.toSet())
        assertEquals(words.map { SourceExcerpt(it.position!!,it.rawText) }.toSet(),p.exception.sources.toSet())
        assertTrue(p.sources.all { it.sourceLocator.startsWith("ocr:") && it.row==null && it.column==null })
        assertTrue(r.formatResult.sources.containsAll(p.sources));assertTrue(p.candidatePaths.isEmpty())
        val ids=r.coverage.filter { it.span in words }.map { it.id }.toSet()
        assertEquals(4,ids.size);assertTrue(r.regions.any { it.kind==OcrRegionKind.ANNOTATION && it.spanIds.toSet()==ids })
        assertEquals(SpanCoverageStatus.ANNOTATION,r.coverage.single { it.span==symbol }.status)
        assertTrue(r.formatResult.draft!!.courses.none { it.name.contains("10:00") || it.name=="示例数学" })
    }
    @Test fun splitMatrixCancellationDoesNotConsumeAnAdjacentIndependentCourseCell() {
        val words=listOf(word("停课:",350f,90f,40f),word("2026-12-30",400f,90f,100f),word("示例数学",510f,90f,80f),word("10:00-11:30",600f,90f,100f))
        val independent=word("独立课程/周次:1/教师:乙",900f,90f,100f)
        val body=matrix().filterNot { it.rawText.startsWith("矩阵示例/") && it.position!!.bounds.top==90f }
        val r=parse(body+words+independent)
        assertEquals(1,r.formatResult.pendingExceptions.size)
        assertEquals(words.map { it.rawText }.toSet(),r.formatResult.pendingExceptions.single().sources.map { it.rawText }.toSet())
        val m=r.formatResult.draft!!.courses.single { it.name=="独立课程" }.meetings.single()
        assertEquals(MeetingKind.RECURRING,m.kind);assertEquals(3,m.dayOfWeek);assertEquals(setOf(1),m.weeks)
        assertTrue(m.sources.any { it.rawText==independent.rawText })
        assertTrue(r.regions.filter { it.kind==OcrRegionKind.ANNOTATION }.none { region -> r.coverage.single { it.span==independent }.id in region.spanIds })
    }
    @Test fun namedHolidayWithExplicitDateRangeIsNeverACourseOrGlobalCancellation() {
        val raw="国庆节放假:2026-10-01至2026-10-07"
        val r=parse(matrix()+word(raw,350f,1510f,200f))
        assertEquals(20,r.counts.meetings);assertTrue(r.formatResult.pendingExceptions.isEmpty())
        assertTrue(r.coverage.single { it.span.rawText==raw }.status in setOf(SpanCoverageStatus.ANNOTATION,SpanCoverageStatus.UNPARSED))
        assertTrue(r.formatResult.draft!!.courses.none { it.name.contains("国庆节放假") })
    }
    @Test fun nativeWordSplitNamedHolidayIsNotInterpretedAsASpecialDateRow() {
        val words=listOf(word("国庆节",100f,1510f,60f),word("放假:",160f,1510f,40f),word("2026-10-01",210f,1510f,100f),word("至",320f,1510f,12f),word("2026-10-07",340f,1510f,100f))
        val r=parse(matrix()+words)
        assertEquals(20,r.counts.meetings);assertTrue(r.formatResult.pendingExceptions.isEmpty())
        assertTrue(r.coverage.filter { it.span in words }.all { it.status in setOf(SpanCoverageStatus.ANNOTATION,SpanCoverageStatus.UNPARSED) })
        assertTrue(r.formatResult.draft!!.courses.flatMap { it.meetings }.none { LocalDate.of(2026,10,1) in it.dates || LocalDate.of(2026,10,7) in it.dates })
    }
    private fun edgeGrid(body:OcrSpan,secondSection:Int=2)=listOf(word("星期一",300f,20f),word("星期二",700f,20f),word("第1节",10f,70f),word("第${secondSection}节",10f,180f),body)
    @Test fun gridDayEdgeOverlapWithoutAnchorCentersPreservesOnlyDeclaredFields() {
        for((x,w) in listOf(480f to 80f,520f to 40f)) {
            val raw="跨列课/周次:1/教师:甲"
            val r=parse(edgeGrid(word(raw,x,70f,w)))
            val m=r.formatResult.draft!!.courses.single().meetings.single()
            assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertNull(m.dayOfWeek);assertTrue(m.sections.isEmpty())
            assertEquals(setOf(1),m.weeks);assertEquals("甲",m.teacher);assertTrue(m.sources.any { it.rawText==raw })
            assertTrue(r.regions.any { it.alternatives.isNotEmpty() })
        }
    }
    @Test fun gridRowEdgeOverlapWithoutAnchorCentersNeverChoosesTheCenterSection() {
        val raw="跨行课/周次:1/教师:甲"
        val r=parse(edgeGrid(word(raw,300f,110f,80f,40f))) // Crosses row edge130, no row anchor center inside.
        val m=r.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertNull(m.dayOfWeek);assertTrue(m.sections.isEmpty())
        assertEquals(setOf(1),m.weeks);assertEquals("甲",m.teacher);assertTrue(m.sources.any { it.rawText==raw })
    }
    @Test fun gridExplicitSparseSectionsCanResolveTheExactMeasuredRowScope() {
        val r=parse(edgeGrid(word("明确跨行课/周次:1/节次:1,3/教师:甲",300f,110f,80f,40f),3))
        val m=r.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(MeetingKind.RECURRING,m.kind);assertEquals(setOf(1,3),m.sections)
    }
    @Test fun originalGridFixtureCrossingBoxIsRetainedAsAnAmbiguousNegative() {
        val raw="示例0/课程代码:D0/周次:1,3,5/节次:1,3/教师:师0/教室:室0"
        val r=parse(listOf(word("星期1",250f,20f),word("星期2",400f,20f),word("第1节",20f,70f),word("第2节",20f,150f),word(raw,260f,70f,120f)))
        val c=r.formatResult.draft!!.courses.single();val m=c.meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertEquals("D0",c.sourceCourseCode)
        assertEquals(setOf(1,3),m.sections);assertEquals(setOf(1,3,5),m.weeks);assertEquals("师0",m.teacher);assertEquals("室0",m.room)
        assertTrue(m.sources.any { it.rawText==raw })
    }
    @Test fun originalNaturalFixtureCrossingBoxKeepsItsExplicitFieldsForReview() {
        val spans=edgeGrid(word("示例数学",300f,70f))+listOf(word("(1-2节)1-3,5-7(单),10周/校区:示例校区",300f,90f,300f),word("场地:示例楼101/教师:示例教师",300f,110f,300f))
        val r=parse(spans);val m=r.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertEquals(setOf(1,2),m.sections)
        assertEquals(setOf(1,2,3,5,7,10),m.weeks);assertEquals("示例楼101",m.room);assertEquals("示例教师",m.teacher)
        assertTrue(m.sources.any { it.position.bounds.right==600f })
    }
    @Test fun originalSharedTitleCrossingBoxesKeepBothExplicitMeetingsAsReviewCandidates() {
        val spans=listOf(word("星期一",350f,20f),word("星期三",850f,20f),word("第1节",10f,70f),word("第5节",10f,300f),
            word("示例分段",350f,70f),word("(1,3节)1,3周/教师:甲/场地:甲室",350f,90f,300f),word("(2,4节)2,4周/教师:乙/场地:乙室",350f,120f,300f))
        val r=parse(spans);val m=r.formatResult.draft!!.courses.single().meetings
        assertEquals(2,m.size);assertTrue(m.all { it.kind==MeetingKind.UNSCHEDULED && it.dayOfWeek==null })
        assertEquals(listOf(setOf(1,3),setOf(2,4)),m.map { it.sections });assertEquals(listOf("甲","乙"),m.map { it.teacher })
        assertTrue(m.all { meeting -> meeting.sources.any { it.position.bounds.right==650f } })
    }
    @Test fun nativeWordSplitHolidayKeywordInACourseNameIsNotAnAnnotationMarker() {
        val words=listOf(word("节日",350f,90f,24f),word("文化研究/周次:1/教师:甲",374f,90f,150f))
        val r=parse(matrix().filterNot { it.rawText.startsWith("矩阵示例/") && it.position!!.bounds.top==90f }+words)
        val m=r.formatResult.draft!!.courses.single { it.name=="节日文化研究" }.meetings.single()
        assertEquals(MeetingKind.RECURRING,m.kind);assertEquals(setOf(1),m.weeks)
        assertTrue(words.all { w -> m.sources.any { it.rawText==w.rawText } })
        assertTrue(r.formatResult.pendingExceptions.isEmpty())
    }
    @Test fun nativeGridWordAndSymbolEvidenceSurvivesAnAmbiguousDayBoundary() {
        val words=edgeGrid(word("跨列课",480f,70f,80f))+listOf(word("周次:1",480f,90f,40f),word("教师:甲",480f,110f,40f))
        val symbol=word("跨",480f,70f,8f).copy(level=OcrLevel.SYMBOL)
        val r=parse(words+symbol);val m=r.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertNull(m.dayOfWeek);assertTrue(m.sections.isEmpty())
        assertEquals("甲",m.teacher);assertEquals(setOf(1),m.weeks)
        assertEquals(words.size+1,r.coverage.size)
        assertTrue(r.coverage.single { it.span==symbol }.regionIds.isNotEmpty())
        assertTrue(m.sources.any { it.rawText=="跨列课" });assertTrue(m.sources.any { it.rawText=="教师:甲" })
    }
    @Test fun unrelatedMatrixFooterCannotInheritTheLastSpecialDate() {
        val raw="说明:日期范围待人工核对"
        val r=parse(matrix()+word(raw,350f,1510f,200f))
        assertEquals(20,r.counts.meetings)
        assertEquals(SpanCoverageStatus.UNPARSED,r.coverage.single { it.span.rawText==raw }.status)
        assertTrue(r.formatResult.unparsedSources.any { it.rawText==raw })
    }
    @Test fun groupedCancellationFieldsCannotBecomeStructuralPeriodAnchors() {
        val words=listOf(word("停课:",10f,65f,40f),word("2026-12-30",60f,65f,100f),word("示例数学",350f,65f,80f),word("第1节",500f,65f,40f),word("10:00-11:30",550f,65f,100f))
        val r=parse(matrix()+words)
        assertEquals(1,r.formatResult.pendingExceptions.size)
        val ids=r.coverage.filter { it.span in words }.map { it.id }.toSet()
        assertTrue(r.layoutCandidates.flatMap { it.anchorSpanIds }.none { it in ids })
        assertEquals(setOf(1),r.formatResult.pendingExceptions.single().exception.sections)
        assertEquals(words.map { it.rawText }.toSet(),r.formatResult.pendingExceptions.single().sources.map { it.rawText }.toSet())
    }
    private fun mergedMatrixLines(continuous:Boolean=false)=listOf(
        TableLineEvidence(true,TextBox(399f,30f,401f,220f),2),TableLineEvidence(true,TextBox(699f,30f,701f,220f),2),
        TableLineEvidence(false,TextBox(400f,79f,700f,81f),2),TableLineEvidence(false,TextBox(400f,139f,700f,141f),2),TableLineEvidence(false,TextBox(400f,219f,700f,221f),2))+
        if(continuous) listOf(TableLineEvidence(true,TextBox(535f,30f,537f,220f),2)) else listOf(TableLineEvidence(true,TextBox(535f,30f,537f,80f),2),TableLineEvidence(true,TextBox(535f,140f,537f,220f),2))
    @Test fun matrixEnclosingMergedCellLinesOverrideAShortTitleBoxScope() {
        val raw="短标题/教师:甲"
        val r=OcrTimetableParser().parse(OcrLayoutDocument(listOf(page(twoPeriodMatrix(raw,420f,50f),lines=mergedMatrixLines()))),context)
        val m=r.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertTrue(m.sections.isEmpty());assertNull(m.dayOfWeek)
        assertTrue(m.sources.any { it.rawText==raw });assertEquals("甲",m.teacher)
        assertTrue(r.regions.any { "ENCLOSED_MERGED_CELL_SCOPE" in it.alternatives })
    }
    @Test fun continuousDividerKeepsAShortTitleInItsSingleMeasuredMatrixCell() {
        val r=OcrTimetableParser().parse(OcrLayoutDocument(listOf(page(twoPeriodMatrix("短标题/教师:甲",420f,50f),lines=mergedMatrixLines(true)))),context)
        val m=r.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(MeetingKind.RECURRING,m.kind);assertEquals(setOf(1),m.sections)
        assertTrue(r.regions.none { "ENCLOSED_MERGED_CELL_SCOPE" in it.alternatives })
    }
    @Test fun gridEnclosingMergedDayCellLinesOverrideAShortTitleBoxScope() {
        val raw="短标题/周次:1/教师:甲"
        val lines=listOf(TableLineEvidence(true,TextBox(135f,30f,137f,220f),2),TableLineEvidence(true,TextBox(935f,30f,937f,220f),2),
            TableLineEvidence(false,TextBox(136f,54f,936f,56f),2),TableLineEvidence(false,TextBox(136f,129f,936f,131f),2),TableLineEvidence(false,TextBox(136f,219f,936f,221f),2),
            TableLineEvidence(true,TextBox(535f,30f,537f,55f),2),TableLineEvidence(true,TextBox(535f,130f,537f,220f),2))
        val r=OcrTimetableParser().parse(OcrLayoutDocument(listOf(page(edgeGrid(word(raw,300f,70f,80f)),lines=lines))),context)
        val m=r.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertNull(m.dayOfWeek);assertTrue(m.sections.isEmpty())
        assertEquals(setOf(1),m.weeks);assertEquals("甲",m.teacher);assertTrue(m.sources.any { it.rawText==raw })
        assertTrue(r.regions.any { "ENCLOSED_MERGED_CELL_SCOPE" in it.alternatives })
    }
    @Test fun genericHolidayDateCannotConsumeADistantIndependentTeacherOnlyCourse() {
        val raw="独立课程/教师:甲"
        val r=parse(twoPeriodMatrix(raw,700f,100f)+word("节假日:2026-10-01",100f,100f,100f))
        assertEquals(1,r.counts.meetings)
        val m=r.formatResult.draft!!.courses.single { it.name=="独立课程" }.meetings.single()
        assertEquals(MeetingKind.RECURRING,m.kind);assertEquals("甲",m.teacher)
        assertTrue(m.sources.any { it.rawText==raw });assertTrue(r.formatResult.pendingExceptions.isEmpty())
    }
    @Test fun nativeGenericNoteCannotConsumeDistantCourseWordsWithoutSlashLabels() {
        val note=listOf(word("节假日:",100f,100f,50f),word("2026-10-01",150f,100f,100f))
        val course=listOf(word("独立课程",700f,100f,80f),word("教师:甲",700f,120f,80f))
        val base=twoPeriodMatrix("占位",700f,100f).dropLast(1)
        val r=parse(base+note+course)
        assertEquals(1,r.counts.meetings)
        val m=r.formatResult.draft!!.courses.single { it.name=="独立课程" }.meetings.single()
        assertEquals("甲",m.teacher);assertTrue(course.all { w -> m.sources.any { it.rawText==w.rawText } })
        val ids=r.coverage.filter { it.span in course }.map { it.id }.toSet()
        assertTrue(r.regions.filter { it.kind==OcrRegionKind.ANNOTATION }.none { region -> region.spanIds.any { it in ids } })
    }
    @Test fun incompleteCancellationCannotClaimADistantCourseAsItsUnresolvedTail() {
        val course=word("独立课程/教师:甲",700f,100f,100f)
        val r=parse(twoPeriodMatrix(course.rawText,700f,100f)+word("停课:2026-10-01",100f,100f,100f))
        assertEquals(1,r.counts.meetings);assertTrue(r.formatResult.pendingExceptions.isEmpty())
        assertEquals("独立课程",r.formatResult.draft!!.courses.single().name)
        assertTrue(r.formatResult.unparsedSources.any { it.rawText=="停课:2026-10-01" })
    }
    @Test fun splitAnonymousSectionDeclarationSurvivesAnUnscheduledGridScope() {
        val words=edgeGrid(word("跨列课",480f,70f,80f))+listOf(word("(1,3",480f,90f,25f),word("节)1周",505f,90f,30f),word("教师:甲",480f,110f,40f))
        val r=parse(words);val m=r.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertNull(m.dayOfWeek)
        assertEquals(setOf(1,3),m.sections);assertEquals(setOf(1),m.weeks);assertEquals("甲",m.teacher)
        assertTrue(m.sources.any { it.rawText=="(1,3" });assertTrue(m.sources.any { it.rawText=="节)1周" })
    }
    private fun splitGridFields(x:Float,titleWidth:Float)=edgeGrid(word("字段示例",x,70f,titleWidth))+listOf(
        word("节",x,90f,10f),word("次:1,3",x+10f,90f,45f),word("周",x,105f,10f),word("次:1,3",x+10f,105f,40f),
        word("时",x,120f,10f),word("间:10:00-11:30",x+10f,120f,45f))
    @Test fun splitExplicitGridClockWeeksAndSectionsRemainKnownWhenDayScopeIsAmbiguous() {
        val r=parse(splitGridFields(480f,80f));val m=r.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(MeetingKind.UNSCHEDULED,m.kind);assertNull(m.dayOfWeek)
        assertEquals(setOf(1,3),m.sections);assertEquals(setOf(1,3),m.weeks)
        assertEquals("10:00",m.customTime!!.start.toString());assertEquals("11:30",m.customTime!!.end.toString())
        assertTrue(m.sources.any { it.rawText=="星期一" });assertTrue(m.sources.any { it.rawText=="星期二" })
    }
    @Test fun splitExplicitGridFieldsKeepTheProvenWeekdayInAnUnambiguousCell() {
        val r=parse(splitGridFields(300f,80f));val m=r.formatResult.draft!!.courses.single().meetings.single()
        assertEquals(MeetingKind.RECURRING,m.kind);assertEquals(1,m.dayOfWeek)
        assertEquals(setOf(1,3),m.sections);assertEquals(setOf(1,3),m.weeks)
        assertEquals("10:00",m.customTime!!.start.toString());assertEquals("11:30",m.customTime!!.end.toString())
    }
    @Test fun cancellationBridgeCannotUseADistantWideCourseToAuthorizeItsOwnGap() {
        val raw="独立课程/教师:甲/时间:10:00-11:30"
        val course=word(raw,700f,100f,600f)
        val r=parse(twoPeriodMatrix(raw,700f,600f)+word("停课:2026-10-01",100f,100f,100f))
        assertEquals(1,r.counts.meetings);assertTrue(r.formatResult.pendingExceptions.isEmpty())
        val m=r.formatResult.draft!!.courses.single { it.name=="独立课程" }.meetings.single()
        assertEquals("甲",m.teacher);assertEquals("10:00",m.customTime!!.start.toString())
        assertTrue(m.sources.any { it.rawText==raw })
        val id=r.coverage.single { it.span==course }.id
        assertTrue(r.regions.filter { it.kind==OcrRegionKind.ANNOTATION }.none { id in it.spanIds })
    }
}
