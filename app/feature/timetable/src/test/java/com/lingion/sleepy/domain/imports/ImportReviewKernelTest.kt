/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import com.lingion.sleepy.importing.ocr.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ImportReviewKernelTest {
    @Test fun relativeExtraNoticeKeepsExactClockAndRequiresSeparateDateConfirmation() {
        val text="明日 通知验收123 10:00-11:30 在测试室 加课"
        val parsed=NoticeTimetableParser().parse(text,NoticeParseContext(FormatParseContext("synthetic-extra",20),LocalDate.of(2026,12,30)))
        val source=FrozenImportSource.fromFormat(parsed)
        val review=session(source)
        val attachment=review.attachException(ReviewLocator(pendingIndex=0),ExceptionAttachment.AsNewCourse(ReviewedCourseIdentity("通知验收123")))
        val partial=ImportReviewDecisions(evidence=evidence(review),attachments=listOf(attachment))
        val blocked=RichDraftConverter.convert(review,partial) as ImportReviewResult.Blocked
        assertTrue(blocked.problems.any {it.code.contains("RELATIVE_DATE")})
        val date=review.resolveRelative(0,RelativeDateBasis.ReferenceDate(LocalDate.of(2026,12,30)),RelativeDateRule.DayOffset(1),LocalDate.of(2026,12,31))
        val complete=ready(review,partial.copy(relativeDates=listOf(date)))
        val meeting=complete.courses.single().meetings.single()
        assertEquals(setOf(LocalDate.of(2026,12,31)),meeting.dates)
        val clock=meeting.time as ReviewedTimeSpec.Continuous
        assertEquals(LocalTime.of(10,0),clock.range.start)
        assertEquals(LocalTime.of(11,30),clock.range.end)
        assertEquals("测试室",meeting.room)
        assertTrue(meeting.provenance.any {it.rawText?.contains("明日")==true})
    }
    private val p0 = ReviewLocator(courseIndex = 0,meetingIndex = 0)
    private val p1 = ReviewLocator(courseIndex = 1,meetingIndex = 0)
    private val target = ImportTarget("local",1,7,3)
    private fun time(a:String = "08:00",b:String = "08:45") = DraftTimeRange(LocalTime.parse(a),LocalTime.parse(b))
    private fun meeting(weeks:Set<Int> = setOf(1,3),teacher:String? = "A", t:DraftTimeRange = time()) = RichMeetingDraft(MeetingKind.RECURRING,1,weeks,customTime = t,teacher = teacher)
    private fun format(courses:List<RichCourseDraft> = listOf(RichCourseDraft("Course",meetings = listOf(meeting())))) = FormatParseResult(RichTimetableDraft(courses = courses))
    private fun session(source:FrozenImportSource, mode:ImportMode = ImportMode.APPEND) = ImportReviewSession.create(source,ImportReviewContext(target,mode,LocalDate.parse("2026-09-07")))
    private fun evidence(s:ImportReviewSession) = s.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { s.reviewEvidence(it.fingerprint) }
    private fun ready(s:ImportReviewSession,d:ImportReviewDecisions = ImportReviewDecisions(evidence = evidence(s))):ReviewedImport {
        val r = RichDraftConverter.convert(s,d)
        assertTrue(r.toString(),r is ImportReviewResult.Ready)
        return (r as ImportReviewResult.Ready).reviewed
    }
    private fun outer():OcrLayoutParseResult {
        val courses = listOf(RichCourseDraft("Same title",meetings = listOf(meeting())),RichCourseDraft("Same title",meetings = listOf(meeting(setOf(2,4),"B",time("10:00","10:45")))))
        val candidate = courses.mapIndexed { i,c -> OcrCandidateCourse(CandidateCourseId("candidate-$i"),c,listOf("r$i"),emptyList(),DraftPath(i)) }
        return OcrLayoutParseResult(format(courses),OcrLayoutDocument(emptyList()),candidateCourses = candidate,identityProposals = listOf(ArchiveIdentityProposal(candidate.map { it.id },ArchiveIdentityEvidence.SAME_TITLE,emptyList())))
    }
    @Test fun `source issues are derived and cannot be replaced by empty or arbitrary acknowledgements`() {
        val src = FrozenImportSource.fromFormat(format().copy(issues = listOf(ParseIssue("UNKNOWN_FOOTER","review me")),unparsedSources = listOf(ImportSourceRef("s","row:8","left over",DraftPath()))))
        val s = session(src)
        assertTrue(s.requirements.any { it.code == "UNKNOWN_FOOTER" })
        assertTrue(s.requirements.any { it.code == "UNPARSED_SOURCE" })
        assertTrue(RichDraftConverter.convert(s) is ImportReviewResult.Blocked)
        assertTrue(RichDraftConverter.convert(s,ImportReviewDecisions(evidence = listOf(SourceEvidenceReview(s.binding,"made-up")))) is ImportReviewResult.Blocked)
        assertEquals(1,ready(s).courses.size)
    }
    @Test fun `same title candidates stay unresolved until typed identity review`() {
        val s = session(FrozenImportSource.fromOcr(outer()))
        assertTrue(RichDraftConverter.convert(s,ImportReviewDecisions(evidence = evidence(s))) is ImportReviewResult.Blocked)
        val merge = s.reviewIdentity(IdentityReviewKind.MERGE,listOf(IdentityArchive(setOf(p0,p1))))
        val out = ready(s,ImportReviewDecisions(evidence = evidence(s),identity = merge))
        assertEquals(2,out.counts.candidateCourses);assertEquals(1,out.counts.confirmedArchives)
        assertEquals(listOf("A","B"),out.courses.single().meetings.map { it.teacher })
        assertEquals(listOf(setOf(1,3),setOf(2,4)),out.courses.single().meetings.map { it.weeks })
        assertEquals(2,out.sourceMappings.size)
        assertEquals(ReviewedArchivePosition(0,1),out.sourceMappings[p1])
    }
    @Test fun `explicit independent archives preserve both sources and meetings`() {
        val s = session(FrozenImportSource.fromOcr(outer()))
        val independent = s.reviewIdentity(IdentityReviewKind.INDEPENDENT,listOf(IdentityArchive(setOf(p0)),IdentityArchive(setOf(p1))))
        val out = ready(s,ImportReviewDecisions(evidence = evidence(s),identity = independent))
        assertEquals(2,out.courses.size);assertEquals(2,out.counts.confirmedArchives)
        assertEquals(ReviewedArchivePosition(1,0),out.sourceMappings[p1])
    }
    @Test fun `identity groups must partition exact selected members`() {
        val s = session(FrozenImportSource.fromOcr(outer()))
        assertThrows(IllegalArgumentException::class.java) { s.reviewIdentity(IdentityReviewKind.MERGE,listOf(IdentityArchive(setOf(p0)))) }
        assertThrows(IllegalArgumentException::class.java) { s.reviewIdentity(IdentityReviewKind.PARTITION,listOf(IdentityArchive(setOf(p0,p1)),IdentityArchive(setOf(p1)))) }
    }
    @Test fun `membership changes invalidate old identity decisions`() {
        val source = FrozenImportSource.fromOcr(outer())
        val old = session(source)
        val decision = old.reviewIdentity(IdentityReviewKind.MERGE,listOf(IdentityArchive(setOf(p0,p1))))
        val changed = ImportReviewSession.create(source,old.context,selectedMeetings = setOf(p0))
        assertTrue(RichDraftConverter.convert(changed,ImportReviewDecisions(evidence = evidence(changed),identity = decision)) is ImportReviewResult.Blocked)
    }
    @Test fun `new parse same semantic values has fresh review binding`() {
        val a = session(FrozenImportSource.fromFormat(format().copy(issues = listOf(ParseIssue("REVIEW","x")))))
        val b = session(FrozenImportSource.fromFormat(format().copy(issues = listOf(ParseIssue("REVIEW","x")))))
        val first = ready(a)
        val second = ready(b)
        assertEquals(first.semanticFingerprint,second.semanticFingerprint)
        assertNotEquals(first.reviewDigest,second.reviewDigest)
        assertTrue(RichDraftConverter.convert(b,ImportReviewDecisions(evidence = evidence(a))) is ImportReviewResult.Blocked)
    }
    @Test fun `different source ids do not change semantic idempotence`() {
        fun parse(id:String) = FrozenImportSource.fromFormat(format().copy(sources = listOf(ImportSourceRef(id,"json:/course/0/meeting/0","A",DraftPath(0,0)))))
        val a = ready(session(parse("random-a")))
        val b = ready(session(parse("random-b")))
        assertEquals(a.semanticFingerprint,b.semanticFingerprint)
        assertNotEquals(a.reviewDigest,b.reviewDigest)
    }
    @Test fun `colors defaults and target revision participate in review binding`() {
        val source = FrozenImportSource.fromFormat(format())
        val a = session(source)
        val red = ImportReviewSession.create(source,a.context.copy(courseIdentities = mapOf(0 to ReviewedCourseIdentity("Course",teacher = "default",color = 9))))
        assertNotEquals(a.binding,red.binding)
        assertNotEquals(ready(a).reviewDigest,ready(red).reviewDigest)
        assertEquals("default",ready(red).courses.single().identity.teacher)
        assertNotEquals(a.binding,ImportReviewSession.create(source,a.context.copy(target = target.copy(expectedRevision = 8))).binding)
    }
    @Test fun `selected pending cancellation needs base and explicit relative basis`() {
        val r = NoticeTimetableParser().parse("课程:Course 明天 08:00-08:45 停课",NoticeParseContext(FormatParseContext("notice-session",20),LocalDate.parse("2026-10-27")))
        assertEquals(1,r.pendingExceptions.size); assertEquals(1,r.relativeDates.size)
        val s = session(FrozenImportSource.fromFormat(r))
        val p = ReviewLocator(pendingIndex = 0)
        val base = LogicalBaseReference.StoredLogicalBase("local",1,12,"logical-a","stable-base")
        val attached = s.attachException(p,ExceptionAttachment.Bound(base))
        assertTrue(RichDraftConverter.convert(s,ImportReviewDecisions(evidence = evidence(s),attachments = listOf(attached))) is ImportReviewResult.Blocked)
        val date = s.resolveRelative(0,RelativeDateBasis.ReferenceDate(LocalDate.parse("2026-10-27")),RelativeDateRule.DayOffset(1),LocalDate.parse("2026-10-28"))
        val out = ready(s,ImportReviewDecisions(evidence = evidence(s),attachments = listOf(attached),relativeDates = listOf(date)))
        val intent = out.exceptions.single()
        assertEquals(ReviewedExceptionKind.CANCEL,intent.kind)
        assertEquals(LocalTime.parse("08:00"),intent.originalScope.time!!.start)
        assertEquals(LocalTime.parse("08:45"),intent.originalScope.time!!.end)
        assertNull(intent.targetTime)
        assertEquals(LocalDate.parse("2026-10-28"),intent.originalDate)
    }
    @Test fun `relative review requires computed rule resolution not arbitrary confirmation flag`() {
        val r = NoticeTimetableParser().parse("Course 明天 08:00-08:45 停课",NoticeParseContext(FormatParseContext("n",20),LocalDate.parse("2026-10-27")))
        val s = session(FrozenImportSource.fromFormat(r))
        assertThrows(IllegalArgumentException::class.java) { s.resolveRelative(0,RelativeDateBasis.ReferenceDate(LocalDate.parse("2026-10-27")),RelativeDateRule.DayOffset(1),LocalDate.parse("2026-11-28")) }
        assertThrows(IllegalArgumentException::class.java) { s.resolveRelative(0,RelativeDateBasis.ReferenceDate(LocalDate.parse("2026-10-27")),RelativeDateRule.DayOffset(2),LocalDate.parse("2026-10-29")) }
    }
    @Test fun `only explicit extra as new course yields a date specific archive`() {
        val r = NoticeTimetableParser().parse("课程:Course 2026-11-01 08:00-08:45 加课",NoticeParseContext(FormatParseContext("n",20),LocalDate.parse("2026-10-27")))
        val s = session(FrozenImportSource.fromFormat(r))
        assertTrue(RichDraftConverter.convert(s,ImportReviewDecisions(evidence = evidence(s))) is ImportReviewResult.Blocked)
        val review = s.attachException(ReviewLocator(pendingIndex = 0),ExceptionAttachment.AsNewCourse(ReviewedCourseIdentity("Course")))
        val out = ready(s,ImportReviewDecisions(evidence = evidence(s),attachments = listOf(review)))
        assertEquals(com.lingion.sleepy.domain.model.MeetingKind.DATE_SPECIFIC,out.courses.single().meetings.single().kind)
        assertEquals(setOf(LocalDate.parse("2026-11-01")),out.courses.single().meetings.single().dates)
    }
    @Test fun `cancel and reschedule cannot create fake new bases`() {
        for(kind in listOf(RichExceptionKind.CANCEL,RichExceptionKind.RESCHEDULE)) {
            val e = RichExceptionDraft(kind,LocalDate.parse("2026-11-01"),if(kind == RichExceptionKind.RESCHEDULE) LocalDate.parse("2026-11-02") else null)
            val s = session(FrozenImportSource.fromFormat(format(emptyList()).copy(pendingExceptions = listOf(PendingExceptionAttachment(exception = e,reason = "pending")))))
            assertThrows(IllegalArgumentException::class.java) { s.attachException(ReviewLocator(pendingIndex = 0),ExceptionAttachment.AsNewCourse(ReviewedCourseIdentity("fake"))) }
        }
    }
    @Test fun `replace attachment cannot reference an unselected stored base`() {
        val e = RichExceptionDraft(RichExceptionKind.CANCEL,LocalDate.parse("2026-11-01"))
        val s = session(FrozenImportSource.fromFormat(format().copy(pendingExceptions = listOf(PendingExceptionAttachment(exception = e,reason = "pending")))),ImportMode.REPLACE)
        assertThrows(IllegalArgumentException::class.java) { s.attachException(ReviewLocator(pendingIndex = 0),ExceptionAttachment.Bound(LogicalBaseReference.StoredLogicalBase("local",1,12,"base","fp"))) }
        val a = s.attachException(ReviewLocator(pendingIndex = 0),ExceptionAttachment.Bound(s.inputBase(p0)))
        assertEquals(1,ready(s,ImportReviewDecisions(evidence = evidence(s),attachments = listOf(a))).exceptions.size)
    }
    @Test fun `deselecting unresolved pending item permits unrelated valid course`() {
        val e = RichExceptionDraft(RichExceptionKind.CANCEL)
        val raw = format().copy(pendingExceptions = listOf(PendingExceptionAttachment(exception = e,reason = "unknown")))
        val source = FrozenImportSource.fromFormat(raw)
        val s = ImportReviewSession.create(source,session(source).context,selectedPending = emptySet())
        assertEquals(1,ready(s).courses.size)
    }
    @Test fun `conflict keep skip choices change final digest and stale context is rejected`() {
        val s = session(FrozenImportSource.fromFormat(format()))
        val keep = s.reviewConflict("actual-conflict",ConflictReviewChoice.KEEP)
        val skip = s.reviewConflict("actual-conflict",ConflictReviewChoice.SKIP)
        assertNotEquals(ready(s,ImportReviewDecisions(conflicts = listOf(keep))).reviewDigest,ready(s,ImportReviewDecisions(conflicts = listOf(skip))).reviewDigest)
        val changed = ImportReviewSession.create(s.source,s.context.copy(target = target.copy(expectedRevision = 99)))
        assertTrue(RichDraftConverter.convert(changed,ImportReviewDecisions(conflicts = listOf(keep))) is ImportReviewResult.Blocked)
    }
    @Test fun `actual versioned json output crosses conversion boundary without losing fields`() {
        val json = """{"schemaVersion":1,"courses":[{"key":"c","name":"Actual","sourceCourseCode":"0007","meetings":[{"key":"m","kind":"DATE_SPECIFIC","dates":["2026-11-01"],"weeks":[1,3],"time":{"start":"08:00","end":"08:45"},"teacher":"","group":"G"}]}]}"""
        val r = VersionedJsonTimetableParser().parse(json.byteInputStream(),FormatParseContext("actual-json",20))
        assertNotNull(r.draft)
        val out = ready(session(FrozenImportSource.fromFormat(r)))
        assertEquals("0007",out.courses.single().identity.sourceCourseCode)
        assertEquals(setOf(1,3),out.courses.single().meetings.single().declaredWeeks)
        assertEquals("",out.courses.single().meetings.single().teacher)
        assertTrue(out.courses.single().meetings.single().provenance.isNotEmpty())
    }
    @Test fun `source period review required for exception target and cannot bridge source gaps`() {
        val e = RichExceptionDraft(RichExceptionKind.EXTRA,targetDate = LocalDate.parse("2026-11-01"),sections = setOf(1,2))
        val raw = format(emptyList()).copy(draft = RichTimetableDraft(periodProposals = listOf(PeriodProposal(1,time(),emptyList()),PeriodProposal(2,time("08:55","09:40"),emptyList()))),pendingExceptions = listOf(PendingExceptionAttachment(exception = e,reason = "pending")))
        val s = session(FrozenImportSource.fromFormat(raw))
        val p = ReviewLocator(pendingIndex = 0)
        assertTrue(s.requirements.any { it.kind == ReviewRequirementKind.TIME && it.source == p })
        val a = s.attachException(p,ExceptionAttachment.AsNewCourse(ReviewedCourseIdentity("Extra")))
        val d = ImportReviewDecisions(evidence = evidence(s),attachments = listOf(a),times = listOf(s.reviewTime(p,TimeResolutionChoice.USE_SOURCE_PERIODS)))
        assertEquals(2,(ready(s,d).exceptions.single().targetTime as ReviewedTimeSpec.SourcePeriods).intervals.size)
    }
    @Test fun `selected source formula needs review before keeping its raw provenance`() {
        val r = format().copy(sources = listOf(ImportSourceRef("sheet","R3C4","cached",DraftPath(0,0),3,4,"A1+A2")))
        val s = session(FrozenImportSource.fromFormat(r))
        assertTrue(s.requirements.any { it.code == "FORMULA_UNVERIFIED_SOURCE" })
        assertTrue(RichDraftConverter.convert(s) is ImportReviewResult.Blocked)
        assertTrue(ready(s).courses.single().meetings.single().provenance.any { it.sourceLocator == "R3C4:formula" && it.rawText == "A1+A2" })
    }
    @Test fun `generic factory rejects OCR format projection rather than losing outer identity`() {
        val raw = format().copy(issues = listOf(ParseIssue("OCR_SOURCE_REVIEW_REQUIRED","review")))
        assertThrows(IllegalArgumentException::class.java) { FrozenImportSource.fromFormat(raw) }
        assertNotNull(FrozenImportSource.fromOcr(outer().copy(formatResult = raw)).ocr)
    }

    @Test fun `unknown numeric title suffix stays title evidence not a formal code`() {
        val o = outer()
        val courses = o.formatResult.draft!!.courses.map { it.copy(sourceCourseCode = "00123456") }
        val candidates = o.candidateCourses.mapIndexed { i,c -> c.copy(course = courses[i],completeTitle = "Same title(00123456)",identityEvidence = OcrCourseIdentityEvidence.UNCONFIRMED_SUFFIX) }
        val source = FrozenImportSource.fromOcr(o.copy(formatResult = o.formatResult.copy(draft = o.formatResult.draft!!.copy(courses = courses)),candidateCourses = candidates))
        val review = session(source)
        val identity = review.reviewIdentity(IdentityReviewKind.MERGE,listOf(IdentityArchive(setOf(p0,p1))))
        val out = ready(review,ImportReviewDecisions(evidence = evidence(review),identity = identity))
        assertNull(out.courses.single().identity.sourceCourseCode)
        assertEquals("Same title(00123456)",out.courses.single().identity.name)
        assertEquals("00123456",out.source.ocr!!.candidateCourses.first().course.sourceCourseCode)
    }
    @Test fun `deselected uncertain OCR group does not block explicit coded selected group`() {
        val o = outer()
        val courses = o.formatResult.draft!!.courses.toMutableList()
        courses[0] = courses[0].copy(sourceCourseCode = "FORMAL")
        val candidates = o.candidateCourses.mapIndexed { i,c -> if(i == 0) c.copy(course = courses[0],identityEvidence = OcrCourseIdentityEvidence.EXPLICIT_CODES) else c }
        val source = FrozenImportSource.fromOcr(o.copy(formatResult = o.formatResult.copy(draft = o.formatResult.draft!!.copy(courses = courses)),candidateCourses = candidates))
        val s = ImportReviewSession.create(source,session(source).context,selectedMeetings = setOf(p0))
        assertFalse(s.requirements.any { it.kind == ReviewRequirementKind.IDENTITY })
        assertEquals("FORMAL",ready(s).courses.single().identity.sourceCourseCode)
    }
    @Test fun `reviewed exception time cannot replace source periods with a bridged continuous interval`() {
        val raw = RichExceptionDraft(RichExceptionKind.EXTRA,targetDate = LocalDate.parse("2026-11-01"),sections = setOf(1,2))
        val result = FormatParseResult(RichTimetableDraft(periodProposals = listOf(PeriodProposal(1,time(),emptyList()),PeriodProposal(2,time("08:55","09:40"),emptyList()))),pendingExceptions = listOf(PendingExceptionAttachment(exception = raw,reason = "pending")))
        val s = session(FrozenImportSource.fromFormat(result))
        val p = ReviewLocator(pendingIndex = 0)
        val bridged = ReviewedTimeSpec.Continuous(com.lingion.sleepy.domain.model.TimeRange(LocalTime.parse("08:00"),LocalTime.parse("09:40")))
        val attachment = s.attachException(p,ExceptionAttachment.AsNewCourse(ReviewedCourseIdentity("Extra")),bridged)
        val d = ImportReviewDecisions(evidence = evidence(s),attachments = listOf(attachment),times = listOf(s.reviewTime(p,TimeResolutionChoice.USE_SOURCE_PERIODS)))
        assertTrue(RichDraftConverter.convert(s,d) is ImportReviewResult.Blocked)
    }
    @Test fun `actual OCR outer parse output retains identity coverage raw variants and source paths`() {
        fun word(text:String,x:Float,y:Float) = OcrSpan(text,OcrLevel.WORD,93f,SourcePosition(5,TextBox(x,y,x+110f,y+12f)))
        val spans = mutableListOf<OcrSpan>()
        val headers = listOf("日期","星期","周次","节次","时间","课程名称","课程代码","教师","实验项目","实验室","班组")
        headers.forEachIndexed { i,t -> spans += word(t,i*140f,20f) }
        repeat(2) { row ->
            val date = LocalDate.parse("2026-11-02").plusDays(row.toLong())
            val cells = listOf(date.toString(),date.dayOfWeek.value.toString(),"1,3","1,3","08:00-09:30","Actual OCR","0000123","Ada","P","R","G")
            cells.forEachIndexed { column,text -> spans += word(text,column*140f,60f+row*30f) }
        }
        val page = PositionedPage(5,2000f,2000f,emptyList())
        val variant = OcrVariantEvidence(OcrVariantId.ORIGINAL,2000,2000,PageAffineTransform.IDENTITY,page,spans,emptySet(),null,OcrWordQuality.EMPTY,true)
        val parsed = OcrTimetableParser().parse(OcrLayoutDocument(listOf(OcrLayoutPage(5,2000f,2000f,listOf(variant),OcrVariantId.ORIGINAL))),OcrLayoutContext(FormatParseContext("actual-ocr",20)))
        assertNotNull(parsed.formatResult.draft);assertEquals(2,parsed.candidateCourses.size)
        assertThrows(IllegalArgumentException::class.java) { FrozenImportSource.fromFormat(parsed.formatResult) }
        val s = session(FrozenImportSource.fromOcr(parsed))
        val out = ready(s)
        assertEquals(1,out.courses.size);assertEquals(2,out.courses.single().meetings.size)
        assertEquals(2,out.counts.candidateCourses);assertEquals(1,out.counts.confirmedArchives)
        assertEquals(spans.size,out.source.ocr!!.coverage.size)
        assertEquals(spans.map { it.rawText },out.source.ocr!!.document.pages.single().variants.single().spans.map { it.rawText })
        assertTrue(out.courses.single().meetings.flatMap { it.provenance }.all { it.page == null || it.page == 5 })
        assertTrue(out.source.format.sources.all { it.sourceLocator.startsWith("ocr:5:") })
    }
    @Test fun `explicit empty cancellation scope is invalid rather than whole-base scope`() {
        val raw = RichExceptionDraft(RichExceptionKind.CANCEL,LocalDate.parse("2026-11-01"),sections = emptySet())
        val s = session(FrozenImportSource.fromFormat(format().copy(pendingExceptions = listOf(PendingExceptionAttachment(exception = raw,reason = "pending")))))
        val a = s.attachException(ReviewLocator(pendingIndex = 0),ExceptionAttachment.Bound(s.inputBase(p0)))
        assertTrue(RichDraftConverter.convert(s,ImportReviewDecisions(evidence = evidence(s),attachments = listOf(a))) is ImportReviewResult.Blocked)
    }
    @Test fun `selected invalid provenance fails closed as a typed review problem`() {
        val m = meeting().copy(sources = listOf(SourceExcerpt(SourcePosition(0,TextBox(-1f,0f,2f,3f)),"bad")))
        val s = session(FrozenImportSource.fromFormat(format(listOf(RichCourseDraft("Bad source",meetings = listOf(m))))))
        assertTrue(RichDraftConverter.convert(s) is ImportReviewResult.Blocked)
    }

    @Test fun `relative basis change requires reparse rather than mutating frozen dates`() {
        val old = LocalDate.parse("2026-10-28")
        val m = meeting().copy(kind = MeetingKind.DATE_SPECIFIC,dayOfWeek = null,dates = setOf(old))
        val ref = ImportSourceRef("relative","row:1","明天",DraftPath(0,0))
        val r = format(listOf(RichCourseDraft("Relative",meetings = listOf(m)))).copy(relativeDates = listOf(RelativeDateProposal("明天",ref,LocalDate.parse("2026-10-27"),old,"REFERENCE_DAY_OFFSET")))
        val s = session(FrozenImportSource.fromFormat(r))
        assertThrows(IllegalArgumentException::class.java) { s.resolveRelative(0,RelativeDateBasis.ReferenceDate(LocalDate.parse("2026-11-01")),RelativeDateRule.DayOffset(1),LocalDate.parse("2026-11-02")) }
        val correct = s.resolveRelative(0,RelativeDateBasis.ReferenceDate(LocalDate.parse("2026-10-27")),RelativeDateRule.DayOffset(1),old)
        val out = ready(s,ImportReviewDecisions(evidence = evidence(s),relativeDates = listOf(correct)))
        assertEquals(setOf(old),out.courses.single().meetings.single().dates)
        assertEquals(setOf(old),out.source.format.draft!!.courses.single().meetings.single().dates)
    }
    @Test fun `term week resolution cannot substitute a different source week`() {
        val ref = ImportSourceRef("relative","row:1","第1周星期一",DraftPath(0,0))
        val r = format().copy(relativeDates = listOf(RelativeDateProposal("第1周星期一",ref,LocalDate.parse("2026-09-07"),LocalDate.parse("2026-09-07"),"CONFIRMED_TERM_WEEK_DAY",confirmedTermMonday = LocalDate.parse("2026-09-07"))))
        val s = session(FrozenImportSource.fromFormat(r))
        assertEquals(LocalDate.parse("2026-09-07"),s.resolveRelative(0,RelativeDateBasis.TermMonday(LocalDate.parse("2026-09-07")),RelativeDateRule.TermWeekDay(1,1),LocalDate.parse("2026-09-07")).resolvedDate)
        assertThrows(IllegalArgumentException::class.java) { s.resolveRelative(0,RelativeDateBasis.TermMonday(LocalDate.parse("2026-09-07")),RelativeDateRule.TermWeekDay(2,1),LocalDate.parse("2026-09-14")) }
    }

    @Test fun `cancellation section scope needs source clock review and keeps disjoint original intervals`() {
        val raw = RichExceptionDraft(RichExceptionKind.CANCEL,originalDate = LocalDate.parse("2026-11-02"),sections = setOf(1,2))
        val r = format().copy(draft = format().draft!!.copy(periodProposals = listOf(PeriodProposal(1,time(),emptyList()),PeriodProposal(2,time("08:55","09:40"),emptyList()))),pendingExceptions = listOf(PendingExceptionAttachment(exception = raw,reason = "pending")))
        val s = session(FrozenImportSource.fromFormat(r))
        val p = ReviewLocator(pendingIndex = 0)
        assertTrue(s.requirements.any { it.kind == ReviewRequirementKind.TIME && it.source == p })
        val a = s.attachException(p,ExceptionAttachment.Bound(s.inputBase(p0)))
        val d = ImportReviewDecisions(evidence = evidence(s),attachments = listOf(a),times = listOf(s.reviewTime(p,TimeResolutionChoice.USE_SOURCE_PERIODS)))
        val intent = ready(s,d).exceptions.single()
        assertEquals(setOf(1,2),intent.originalScope.sections)
        assertNull(intent.originalScope.time);assertNull(intent.targetTime)
        val intervals = (intent.originalScope.resolvedTime as ReviewedTimeSpec.SourcePeriods).intervals
        assertEquals(2,intervals.size);assertEquals(LocalTime.parse("08:45"),intervals[0].range.end);assertEquals(LocalTime.parse("08:55"),intervals[1].range.start)
    }
    @Test fun `relative confirmation cannot repair an ambiguously missing original date`() {
        val r = NoticeTimetableParser().parse("课程:Course 2026-11-99 明天 停课",NoticeParseContext(FormatParseContext("ambiguous",20),LocalDate.parse("2026-10-27")))
        assertNull(r.pendingExceptions.single().exception.originalDate)
        val s = session(FrozenImportSource.fromFormat(r))
        val attachment = s.attachException(ReviewLocator(pendingIndex = 0),ExceptionAttachment.Bound(LogicalBaseReference.StoredLogicalBase("local",1,12,"base","fp")))
        val relative = s.resolveRelative(0,RelativeDateBasis.ReferenceDate(LocalDate.parse("2026-10-27")),RelativeDateRule.DayOffset(1),LocalDate.parse("2026-10-28"))
        assertTrue(RichDraftConverter.convert(s,ImportReviewDecisions(evidence = evidence(s),attachments = listOf(attachment),relativeDates = listOf(relative))) is ImportReviewResult.Blocked)
    }
    @Test fun `empty reviewed time cannot masquerade as exact new extra time`() {
        val raw = RichExceptionDraft(RichExceptionKind.EXTRA,targetDate = LocalDate.parse("2026-11-01"))
        val s = session(FrozenImportSource.fromFormat(format(emptyList()).copy(pendingExceptions = listOf(PendingExceptionAttachment(exception = raw,reason = "pending")))))
        assertThrows(IllegalArgumentException::class.java) { s.attachException(ReviewLocator(pendingIndex = 0),ExceptionAttachment.AsNewCourse(ReviewedCourseIdentity("Extra")),ReviewedTimeSpec.SourcePeriods(emptySet(),emptyList(),emptyList())) }
    }
    @Test fun `partition review can split one candidate archive into two without dropping meetings`() {
        val o = outer()
        val sourceCourse = o.formatResult.draft!!.courses.first().copy(meetings = o.formatResult.draft!!.courses.flatMap { it.meetings })
        val candidate = o.candidateCourses.first().copy(course = sourceCourse,path = DraftPath(0,0),identityEvidence = OcrCourseIdentityEvidence.TITLE_CONTAINER)
        val source = FrozenImportSource.fromOcr(o.copy(formatResult = format(listOf(sourceCourse)),candidateCourses = listOf(candidate),identityProposals = emptyList()))
        val s = session(source)
        val second = ReviewLocator(courseIndex = 0,meetingIndex = 1)
        val identity = s.reviewIdentity(IdentityReviewKind.PARTITION,listOf(IdentityArchive(setOf(p0)),IdentityArchive(setOf(second))))
        val out = ready(s,ImportReviewDecisions(evidence = evidence(s),identity = identity))
        assertEquals(2,out.courses.size);assertEquals(2,out.courses.sumOf { it.meetings.size });assertEquals(ReviewedArchivePosition(1,0),out.sourceMappings[second])
    }

    @Test fun `reschedule target overrides never become original cancellation scope`() {
        val raw = RichExceptionDraft(RichExceptionKind.RESCHEDULE,LocalDate.parse("2026-11-02"),LocalDate.parse("2026-11-03"),time("10:00","10:45"),setOf(2))
        val s = session(FrozenImportSource.fromFormat(format(listOf(RichCourseDraft("Course",meetings = listOf(meeting().copy(exceptions = listOf(raw))))))))
        val p = p0.copy(exceptionIndex = 0)
        val attachment = s.attachException(p,ExceptionAttachment.Bound(s.inputBase(p0)))
        val intent = ready(s,ImportReviewDecisions(evidence = evidence(s),attachments = listOf(attachment))).exceptions.single()
        assertNull(intent.originalScope.sections);assertNull(intent.originalScope.time)
        assertEquals(LocalTime.parse("10:00"),(intent.targetTime as ReviewedTimeSpec.Continuous).range.start)
    }
    @Test fun `reschedule clocks keep lexical roles and the target override`() {
        val r = NoticeTimetableParser().parse("课程:Course 2026-11-02 08:00-08:45 调至 2026-11-03 10:00-10:45",NoticeParseContext(FormatParseContext("lexical-clocks",20),LocalDate.parse("2026-10-27")))
        assertTrue(r.issues.none { it.code == "AMBIGUOUS_TIME_MAPPING" })
        val pending = r.pendingExceptions.single()
        assertEquals("08:00",pending.roles!!.original.time!!.start.toString())
        assertEquals("10:00",pending.roles!!.target.time!!.start.toString())
        assertEquals("10:00",pending.exception.customTime!!.start.toString())
        val s = session(FrozenImportSource.fromFormat(r))
        val attachment = s.attachException(ReviewLocator(pendingIndex = 0),ExceptionAttachment.Bound(LogicalBaseReference.StoredLogicalBase("local",1,12,"base","fp")))
        val intent = ready(s,ImportReviewDecisions(evidence = evidence(s),attachments = listOf(attachment))).exceptions.single()
        assertEquals(LocalTime.parse("10:00"),(intent.targetTime as ReviewedTimeSpec.Continuous).range.start)
        // The old-entry review carries the stated original clock, so the planner can remove only
        // 08:00-08:45 instead of the whole stored logical interval.
        assertEquals(LocalTime.parse("08:00"),intent.originalScope.time!!.start)
        assertEquals(LocalTime.parse("08:45"),intent.originalScope.time!!.end)
    }

    @Test fun `new course materialized from extra retains explicit unknown metadata state`() {
        val raw = RichExceptionDraft(RichExceptionKind.EXTRA,targetDate = LocalDate.parse("2026-11-01"),customTime = time(),missingFields = setOf(ImportField.TEACHER,ImportField.ROOM))
        val s = session(FrozenImportSource.fromFormat(format(emptyList()).copy(pendingExceptions = listOf(PendingExceptionAttachment(exception = raw,reason = "pending")))))
        val p = ReviewLocator(pendingIndex = 0)
        val attachment = s.attachException(p,ExceptionAttachment.AsNewCourse(ReviewedCourseIdentity("MetaExtra")))
        val unknown = s.retainUnknownOptionalMetadata(p,setOf(ImportField.TEACHER,ImportField.ROOM))
        val out = ready(s,ImportReviewDecisions(evidence = evidence(s),attachments = listOf(attachment),optionalMetadata = listOf(unknown)))
        assertEquals(out.exceptions.single().retainedUnknownFields,out.courses.single().meetings.single().retainedUnknownFields)
        assertEquals(setOf(ImportField.TEACHER,ImportField.ROOM),out.courses.single().meetings.single().retainedUnknownFields)
    }
}
