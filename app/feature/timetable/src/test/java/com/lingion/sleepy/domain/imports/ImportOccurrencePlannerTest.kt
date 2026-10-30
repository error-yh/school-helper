/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.importing.RichCourseDraft
import com.lingion.sleepy.importing.RichMeetingDraft
import com.lingion.sleepy.importing.RichTimetableDraft
import com.lingion.sleepy.importing.RichExceptionDraft
import com.lingion.sleepy.importing.RichExceptionKind
import com.lingion.sleepy.importing.DraftTimeRange
import com.lingion.sleepy.importing.formats.FormatParseResult
import com.lingion.sleepy.importing.formats.PendingExceptionAttachment
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ImportOccurrencePlannerTest {
    private val monday=LocalDate.parse("2026-09-07")
    private fun r(a:String="08:00",b:String="08:45")=TimeRange(LocalTime.parse(a),LocalTime.parse(b))
    private fun base(weeks:Set<Int> = setOf(1,2),sections:Set<Int> = setOf(1,2))=CourseMeeting(id=10,day=1,weeks=weeks,sections=sections,customTime=if(sections.isEmpty()) r() else null)
    private fun snapshot(meeting:CourseMeeting?=null,name:String="Course")=TimetableSnapshot(
        SemesterSettings(startDate=monday,totalWeeks=20),defaultPeriods(),
        if(meeting==null) emptyList() else listOf(ScheduledCourse(1,name,"","",9,listOf(meeting))),7,"local")
    private fun rich(m:CourseMeeting)=RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.valueOf(m.kind.name),m.day.takeIf { it>0 },if(m.kind==MeetingKind.RECURRING) m.weeks else m.declaredWeeks,m.sections,
        m.customTime?.let { DraftTimeRange(it.start,it.end) },m.dates,m.teacher,m.room,m.group,m.experimentProject,m.isExperiment)
    private fun review(s:TimetableSnapshot,meetings:List<CourseMeeting>,name:String="Course",mode:ImportMode=ImportMode.APPEND,
        notice:RichExceptionDraft?=null,binding:LogicalBaseReference.StoredLogicalBase?=null):ReviewedImport {
        val source=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses=if(meetings.isEmpty()) emptyList() else listOf(RichCourseDraft(name,meetings=meetings.map(::rich)))),
            pendingExceptions=if(notice==null) emptyList() else listOf(PendingExceptionAttachment(exception=notice,reason="test"))))
        val session=ImportReviewSession.create(source,ImportReviewContext(ImportTarget(s.scopeToken,s.settings.id,s.revision,3),mode,s.settings.startDate?.let(TimetableRules::mondayOf),s.settings.totalWeeks,s.periods))
        val attachments=if(notice==null) emptyList() else listOf(session.attachException(ReviewLocator(pendingIndex=0),ExceptionAttachment.Bound(binding ?: session.inputBase(ReviewLocator(courseIndex=0,meetingIndex=0)))))
        val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },attachments=attachments,
            unscheduled=session.selectedMeetings.filter { meetings[it.meetingIndex!!].kind==MeetingKind.UNSCHEDULED }.map { session.keepUnscheduled(it) })
        val result=RichDraftConverter.convert(session,decisions)
        assertTrue(result.toString(),result is ImportReviewResult.Ready)
        return (result as ImportReviewResult.Ready).reviewed
    }
    private fun plan(s:TimetableSnapshot,meetings:List<CourseMeeting>,name:String="Course")=ImportOccurrencePlanner.plan(review(s,meetings,name),LoadedImportTarget(s,3))
    private fun after(s:TimetableSnapshot,p:ImportOccurrencePlan):TimetableSnapshot {
        assertTrue(p.problems.toString(),p.ready)
        var id=100L
        val updates=p.courses.map { c -> ScheduledCourse(c.existingCourseId ?: id++,c.draft.courseName,c.draft.teacher,c.draft.room,c.draft.color,c.draft.meetings,c.draft.sourceCourseCode,c.draft.sourceClassCode,c.draft.provenance) }
        return s.copy(courses=s.courses.filter { it.id !in p.removedCourseIds && updates.none { u->u.id==it.id } }+updates)
    }
    private fun ranges(s:TimetableSnapshot,date:LocalDate)=TimetableRules.occurrencesOn(s,date).flatMap { it.timeRanges }.sortedBy { it.start }
    private fun notice(kind:RichExceptionKind,date:LocalDate=monday,target:LocalDate?=null,time:TimeRange?=null,sections:Set<Int>?=null)=
        RichExceptionDraft(kind,date,target,time?.let { DraftTimeRange(it.start,it.end) },sections)

    @Test fun `actual date subtraction avoids the weeks sections Cartesian bug`() {
        val s=snapshot(base(setOf(1),setOf(1)))
        val p=plan(s,listOf(base()))
        val a=after(s,p)
        assertEquals(listOf(r(),r("08:55","09:40")),ranges(a,monday))
        assertEquals(listOf(r(),r("08:55","09:40")),ranges(a,monday.plusWeeks(1)))
        assertEquals(3,p.courses.single().draft.meetings.sumOf { it.weeks.size*it.sections.size })
    }
    @Test fun `continuous time subtracts actual coverage including break`() {
        val s=snapshot(base(setOf(1),setOf(1)))
        val p=plan(s,listOf(base(setOf(1),emptySet()).copy(customTime=r("08:00","09:40"))))
        assertEquals(listOf(r("08:45","09:40")),p.courses.single().draft.meetings.mapNotNull { it.customTime })
    }
    @Test fun `strict metadata identity does not swallow different projects classes or explicit empty values`() {
        val s=snapshot(base(setOf(1),setOf(1)).copy(experimentProject="P",group=null))
        val p=plan(s,listOf(base(setOf(1),setOf(1)).copy(experimentProject="P",group="")))
        assertEquals(1,p.courses.size);assertEquals(1,p.conflicts.size)
        assertEquals(monday,p.conflicts.single().incoming.date);assertEquals(r(),p.conflicts.single().overlap)
        assertTrue(p.problems.any { it.code=="CONFLICT_REVIEW_REQUIRED" })
    }
    @Test fun `same title alone never binds or overwrites stored course metadata`() {
        val s=snapshot(base(setOf(1),setOf(1)))
        val p=plan(s,listOf(base(setOf(2),setOf(1))))
        assertNull(p.courses.single().existingCourseId)
        assertEquals(9L,s.courses.single().color)
    }
    @Test fun `confirmed independent and partition archives keep separate notice targets`() {
        val s=snapshot();val p0=ReviewLocator(courseIndex=0,meetingIndex=0);val p1=ReviewLocator(courseIndex=1,meetingIndex=0)
        val courses=listOf(RichCourseDraft("Course",meetings=listOf(rich(base(setOf(1),setOf(1))))),RichCourseDraft("Course",meetings=listOf(rich(base(setOf(1),setOf(1))))))
        val pending=listOf(PendingExceptionAttachment(exception=notice(RichExceptionKind.CANCEL),reason="test"))
        val format=FormatParseResult(RichTimetableDraft(courses=courses),pendingExceptions=pending)
        val candidates=courses.mapIndexed { i,c->com.lingion.sleepy.importing.ocr.OcrCandidateCourse(com.lingion.sleepy.importing.ocr.CandidateCourseId("candidate-$i"),c,listOf("r$i"),emptyList(),com.lingion.sleepy.importing.formats.DraftPath(i)) }
        val outer=com.lingion.sleepy.importing.ocr.OcrLayoutParseResult(format,com.lingion.sleepy.importing.ocr.OcrLayoutDocument(emptyList()),candidateCourses=candidates,identityProposals=listOf(com.lingion.sleepy.importing.ocr.ArchiveIdentityProposal(candidates.map { it.id },com.lingion.sleepy.importing.ocr.ArchiveIdentityEvidence.SAME_TITLE,emptyList())))
        for(kind in listOf(IdentityReviewKind.INDEPENDENT,IdentityReviewKind.PARTITION)) {
            val session=ImportReviewSession.create(FrozenImportSource.fromOcr(outer),ImportReviewContext(ImportTarget("local",1,7,3),termMonday=monday,targetPeriods=s.periods))
            val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },identity=session.reviewIdentity(kind,listOf(IdentityArchive(setOf(p0)),IdentityArchive(setOf(p1)))),attachments=listOf(session.attachException(ReviewLocator(pendingIndex=0),ExceptionAttachment.Bound(session.inputBase(p1)))))
            val reviewed=(RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
            val plan=ImportOccurrencePlanner.plan(reviewed,LoadedImportTarget(s,3))
            assertTrue(plan.problems.toString(),plan.ready)
            assertEquals(2,plan.courses.size)
            assertTrue(plan.courses.single { it.key=="input:0" }.state.removes.isEmpty())
            assertEquals(1,plan.courses.single { it.key=="input:1" }.state.removes.size)
        }
    }
    @Test fun `fully duplicate append has no course state or revision write`() {
        val s=snapshot(base())
        val p=plan(s,listOf(base()))
        assertTrue(p.unchanged);assertTrue(p.courses.isEmpty());assertTrue(p.removedCourseIds.isEmpty())
    }
    @Test fun `batch and stored comparison give equal residual and selection replans original`() {
        val a=base(setOf(1),setOf(1));val b=base()
        val stored=plan(snapshot(a),listOf(b)).courses.single().draft.meetings
        val input=plan(snapshot(),listOf(a,b)).courses.single().draft.meetings
        val full=after(snapshot(),plan(snapshot(),listOf(b)))
        assertEquals(listOf(r(),r("08:55","09:40")),ranges(full,monday))
        assertEquals(3,stored.sumOf { it.weeks.size*it.sections.size })
        assertEquals(4,input.sumOf { it.weeks.size*it.sections.size })
    }
    @Test fun `date specific outside term expands with no calendar`() {
        val s=snapshot().copy(settings=SemesterSettings(startDate=null))
        val d=LocalDate.parse("2027-01-02")
        val m=CourseMeeting(day=0,weeks=emptySet(),sections=emptySet(),customTime=r(),kind=MeetingKind.DATE_SPECIFIC,dates=setOf(d),declaredWeeks=setOf(99))
        val a=after(s,plan(s,listOf(m)))
        assertEquals(listOf(r()),ranges(a,d));assertNull(TimetableRules.teachingWeek(a.settings,d))
        assertEquals(setOf(99),a.courses.single().meetings.single().declaredWeeks)
    }
    @Test fun `loaded context differences fail closed even if target revision matches`() {
        val s=snapshot();val reviewed=review(s,listOf(base()))
        for(other in listOf(s.copy(scopeToken="other"),s.copy(revision=8),s.copy(settings=s.settings.copy(totalWeeks=19)),s.copy(periods=defaultPeriods().dropLast(1)))) {
            assertFalse(ImportOccurrencePlanner.plan(reviewed,LoadedImportTarget(other,3)).ready)
        }
        assertFalse(ImportOccurrencePlanner.plan(reviewed,LoadedImportTarget(s,4)).ready)
    }
    @Test fun `partial cancellation creates live sparse and cancelled fragments without restoration extras`() {
        val s=snapshot(base(setOf(1,2),setOf(1,2,3)))
        val target=LoadedImportTarget(s,3);val binding=ImportOccurrencePlanner.logicalBases(target).single()
        val p=ImportOccurrencePlanner.plan(review(s,emptyList(),notice=notice(RichExceptionKind.CANCEL,sections=setOf(2)),binding=binding),target)
        val a=after(s,p)
        assertEquals(listOf(r(),r("10:00","10:45")),ranges(a,monday))
        assertEquals(3,ranges(a,monday.plusWeeks(1)).size)
        assertEquals(3,p.courses.single().draft.meetings.size)
        assertEquals(listOf(ExceptionKind.CANCEL),p.courses.single().draft.meetings.flatMap { it.exceptions }.map { it.kind })
        assertTrue(TimetableRules.validateCourse(p.courses.single().draft,s.settings,s.periods).isEmpty())
    }
    @Test fun `grouped sparse cancellations preserve both notice sources`() {
        val s=snapshot(base(setOf(1),setOf(1,3)));val target=LoadedImportTarget(s,3)
        val binding=ImportOccurrencePlanner.logicalBases(target).single()
        val box=com.lingion.sleepy.importing.TextBox(0f,0f,10f,10f)
        val pending=listOf(1,3).map { section ->
            val excerpt=com.lingion.sleepy.importing.SourceExcerpt(com.lingion.sleepy.importing.SourcePosition(section,box),"notice $section")
            PendingExceptionAttachment(exception=notice(RichExceptionKind.CANCEL,sections=setOf(section)).copy(sources=listOf(excerpt)),reason="test")
        }
        val source=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(),pendingExceptions=pending))
        val session=ImportReviewSession.create(source,ImportReviewContext(ImportTarget("local",1,7,3),termMonday=monday,targetPeriods=s.periods))
        val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },attachments=pending.indices.map { session.attachException(ReviewLocator(pendingIndex=it),ExceptionAttachment.Bound(binding)) })
        val reviewed=(RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
        val p=ImportOccurrencePlanner.plan(reviewed,target)
        assertTrue(p.problems.toString(),p.ready)
        val meeting=p.courses.single().draft.meetings.single()
        assertEquals(setOf(1,3),meeting.sections)
        assertEquals(setOf("notice 1","notice 3"),meeting.exceptions.single().provenance.map { it.rawText }.toSet())
    }
    @Test fun `custom partial cancellation only changes its actual date`() {
        val m=base(setOf(1,2),emptySet()).copy(customTime=r("08:00","10:00"));val s=snapshot(m);val t=LoadedImportTarget(s,3)
        val p=ImportOccurrencePlanner.plan(review(s,emptyList(),notice=notice(RichExceptionKind.CANCEL,time=r("08:30","09:00")),binding=ImportOccurrencePlanner.logicalBases(t).single()),t)
        val a=after(s,p)
        assertEquals(listOf(r("08:00","08:30"),r("09:00","10:00")),ranges(a,monday))
        assertEquals(listOf(r("08:00","10:00")),ranges(a,monday.plusWeeks(1)))
    }
    @Test fun `all Removes happen before Adds so same day move retains its overlap`() {
        val s=snapshot(base(setOf(1),emptySet()).copy(customTime=r("08:00","09:00")));val t=LoadedImportTarget(s,3)
        val binding=ImportOccurrencePlanner.logicalBases(t).single()
        for(target in listOf(r("08:00","09:00"),r("09:00","10:00"),r("08:30","09:30"))) {
            val p=ImportOccurrencePlanner.plan(review(s,emptyList(),notice=notice(RichExceptionKind.RESCHEDULE,target=monday,time=target),binding=binding),t)
            assertEquals(listOf(target),ranges(after(s,p),monday))
            assertEquals(target==r("08:00","09:00"),p.unchanged)
        }
    }
    @Test fun `existing reschedule keeps its target when another move only adds a target`() {
        val d2=monday.plusDays(1);val d3=monday.plusDays(2)
        val s=snapshot(base(setOf(1),setOf(1)).copy(exceptions=listOf(MeetingException(20,ExceptionKind.RESCHEDULE,monday,d2))))
        val t=LoadedImportTarget(s,3);val binding=ImportOccurrencePlanner.logicalBases(t).single()
        val p=ImportOccurrencePlanner.plan(review(s,emptyList(),notice=notice(RichExceptionKind.RESCHEDULE,target=d3),binding=binding),t)
        val a=after(s,p)
        assertTrue(ranges(a,monday).isEmpty());assertEquals(listOf(r()),ranges(a,d2));assertEquals(listOf(r()),ranges(a,d3))
        assertEquals(2,p.courses.single().state.groups.size)
        assertTrue(p.courses.single().state.groups.last().fulfilledRemoveKeys.isNotEmpty())
    }
    @Test fun `genuine old EXTRA survives original cancellation`() {
        val s=snapshot(base(setOf(1),setOf(1)).copy(exceptions=listOf(MeetingException(20,ExceptionKind.EXTRA,targetDate=monday,customTime=r("10:00","10:45")))))
        val t=LoadedImportTarget(s,3)
        val p=ImportOccurrencePlanner.plan(review(s,emptyList(),notice=notice(RichExceptionKind.CANCEL),binding=ImportOccurrencePlanner.logicalBases(t).single()),t)
        assertEquals(listOf(r("10:00","10:45")),ranges(after(s,p),monday))
    }
    @Test fun `InputBase absorbed by existing course cannot silently attach to that stored family`() {
        val s=snapshot(base(setOf(1),setOf(1)))
        val p=ImportOccurrencePlanner.plan(review(s,listOf(base(setOf(1),setOf(1))),notice=notice(RichExceptionKind.CANCEL)),LoadedImportTarget(s,3))
        assertTrue(p.problems.toString(),p.problems.any { it.code=="INPUT_BASE_ABSORBED_REQUIRES_STORED_BINDING" })
        assertTrue(plan(s,listOf(base(setOf(1),setOf(1)))).unchanged)
    }
    @Test fun `invalid original date blocks only selected notice and replace rejects missing stored base`() {
        val s=snapshot(base(setOf(1),setOf(1)));val t=LoadedImportTarget(s,3);val b=ImportOccurrencePlanner.logicalBases(t).single()
        val p=ImportOccurrencePlanner.plan(review(s,emptyList(),notice=notice(RichExceptionKind.CANCEL,date=monday.plusDays(1)),binding=b),t)
        assertTrue(p.problems.any { it.code=="ORIGINAL_DATE_OUTSIDE_LOGICAL_BASE" })
        assertThrows(IllegalArgumentException::class.java) { review(s,listOf(base(setOf(2),setOf(1))),mode=ImportMode.REPLACE,notice=notice(RichExceptionKind.CANCEL),binding=b) }
        // Defensive C2 boundary beyond the public C1 factory, which already refuses this attachment.
        val append=review(s,listOf(base(setOf(2),setOf(1))),notice=notice(RichExceptionKind.CANCEL),binding=b)
        val forged=ReviewedImport(append.source,append.context.copy(mode=ImportMode.REPLACE),append.binding,append.courses,append.exceptions,append.sourceMappings,append.decisions,append.counts,append.semanticFingerprint,append.reviewDigest,false)
        val replace=ImportOccurrencePlanner.plan(forged,t)
        assertTrue(replace.problems.any { it.code=="STORED_BASE_ABSENT_IN_REPLACE" })
    }
    @Test fun `skip reveals formerly duplicate input and its new conflict can be reviewed`() {
        val s=snapshot(base(setOf(1),setOf(1)).copy(teacher="Stored"))
        val incoming=base(setOf(1),setOf(1)).copy(teacher="Incoming")
        val reviewed=review(s,listOf(incoming,incoming))
        val first=ImportOccurrencePlanner.plan(reviewed,LoadedImportTarget(s,3))
        assertEquals(1,first.conflicts.size)
        val skip=ConflictReviewDecision(reviewed.binding,first.conflicts.single().fingerprint,ConflictReviewChoice.SKIP)
        val second=ImportOccurrencePlanner.plan(reviewed,LoadedImportTarget(s,3),listOf(skip))
        val revealed=second.conflicts.single { it.incoming.source?.meetingIndex==1 }
        assertTrue(second.problems.any { it.code=="CONFLICT_REVIEW_REQUIRED" })
        val keep=ConflictReviewDecision(reviewed.binding,revealed.fingerprint,ConflictReviewChoice.KEEP)
        val resolved=ImportOccurrencePlanner.plan(reviewed,LoadedImportTarget(s,3),listOf(skip,keep))
        assertTrue(resolved.problems.toString(),resolved.ready)
        assertEquals("Incoming",resolved.courses.single().draft.meetings.single().teacher)
        val stale=ConflictReviewDecision(reviewed.binding.copy(requestDigest="stale"),revealed.fingerprint,ConflictReviewChoice.KEEP)
        assertTrue(ImportOccurrencePlanner.plan(reviewed,LoadedImportTarget(s,3),listOf(skip,stale)).problems.any { it.code=="STALE_CONFLICT_DECISION" })
    }
    @Test fun `bound cancellation persists negative ownership when another family covers identical visible time`() {
        val first=base(setOf(1),setOf(1));val second=first.copy(id=11)
        val s=snapshot(first).let { it.copy(courses=listOf(it.courses.single().copy(meetings=listOf(first,second)))) }
        val t=LoadedImportTarget(s,3);val ref=ImportOccurrencePlanner.logicalBases(t).first()
        val p=ImportOccurrencePlanner.plan(review(s,emptyList(),notice=notice(RichExceptionKind.CANCEL),binding=ref),t)
        assertFalse(p.unchanged);assertEquals(1,p.courses.size)
        assertEquals(1,p.courses.single().state.removes.size)
        assertEquals(1,p.courses.single().draft.meetings.flatMap { it.exceptions }.count { it.kind==ExceptionKind.CANCEL })
        assertEquals(listOf(r()),ranges(after(s,p),monday))
    }
    @Test fun `semantic effect fingerprint excludes transport provenance but plan digest binds it`() {
        val s=snapshot()
        fun withId(id:String):ReviewedImport {
            val format=FormatParseResult(RichTimetableDraft(courses=listOf(RichCourseDraft("Course",meetings=listOf(rich(base()))))),
                sources=listOf(com.lingion.sleepy.importing.formats.ImportSourceRef(id,"json:/courses/0/meetings/0","same raw",com.lingion.sleepy.importing.formats.DraftPath(0,0))))
            val session=ImportReviewSession.create(FrozenImportSource.fromFormat(format),ImportReviewContext(ImportTarget("local",1,7,3),termMonday=monday,targetPeriods=s.periods))
            return (RichDraftConverter.convert(session,ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) })) as ImportReviewResult.Ready).reviewed
        }
        val a=ImportOccurrencePlanner.plan(withId("transport-a"),LoadedImportTarget(s,3))
        val b=ImportOccurrencePlanner.plan(withId("transport-b"),LoadedImportTarget(s,3))
        assertEquals(a.preview.semanticFingerprint,b.preview.semanticFingerprint)
        assertNotEquals(a.preview.planDigest,b.preview.planDigest)
    }
    @Test fun `already cancelled original is not neutralized with a newly explicit identical Add`() {
        val s=snapshot(base(setOf(1),setOf(1)).copy(exceptions=listOf(MeetingException(20,ExceptionKind.CANCEL,originalDate=monday))))
        val t=LoadedImportTarget(s,3)
        val p=ImportOccurrencePlanner.plan(review(s,emptyList(),notice=notice(RichExceptionKind.RESCHEDULE,target=monday),binding=ImportOccurrencePlanner.logicalBases(t).single()),t)
        assertFalse(p.unchanged);assertEquals(listOf(r()),ranges(after(s,p),monday))
        assertEquals(1,p.courses.single().state.adds.size)
    }
    @Test fun `existing recurring archive without calendar does not block an independent date input`() {
        val s=snapshot(base()).copy(settings=SemesterSettings(startDate=null))
        val d=LocalDate.parse("2027-01-02")
        val m=CourseMeeting(day=0,weeks=emptySet(),sections=setOf(1),kind=MeetingKind.DATE_SPECIFIC,dates=setOf(d))
        val p=plan(s,listOf(m));assertTrue(p.problems.toString(),p.ready)
        assertEquals(listOf(r()),ranges(after(s,p),d))
    }
    @Test fun `unscheduled has declared weeks but no active atoms and repeats without a state write`() {
        val s=snapshot();val m=CourseMeeting(day=0,weeks=emptySet(),sections=emptySet(),kind=MeetingKind.UNSCHEDULED,declaredWeeks=setOf(1,3))
        val p=plan(s,listOf(m));val a=after(s,p)
        assertEquals(setOf(1,3),p.courses.single().draft.meetings.single().declaredWeeks)
        assertTrue(ranges(a,monday).isEmpty());assertTrue(plan(a,listOf(m)).unchanged)
    }
    @Test fun `unscheduled raw content distinguishes records while random source ids do not`() {
        val box=com.lingion.sleepy.importing.TextBox(0f,0f,10f,10f)
        fun reviewed(s:TimetableSnapshot,raw:String,id:String):ReviewedImport {
            val excerpt=com.lingion.sleepy.importing.SourceExcerpt(com.lingion.sleepy.importing.SourcePosition(1,box),raw)
            val m=RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.UNSCHEDULED,weeks=setOf(1,3),sources=listOf(excerpt))
            val path=com.lingion.sleepy.importing.formats.DraftPath(0,0)
            val source=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses=listOf(RichCourseDraft("Course",meetings=listOf(m)))),sources=listOf(com.lingion.sleepy.importing.formats.ImportSourceRef(id,"row:1",raw,path))))
            val session=ImportReviewSession.create(source,ImportReviewContext(ImportTarget("local",1,7,3),termMonday=monday,targetPeriods=s.periods))
            val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },unscheduled=listOf(session.keepUnscheduled(ReviewLocator(courseIndex=0,meetingIndex=0))))
            return (RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
        }
        val first=ImportOccurrencePlanner.plan(reviewed(snapshot(),"first pending arrangement","random-a"),LoadedImportTarget(snapshot(),3))
        val stored=after(snapshot(),first)
        val repeat=ImportOccurrencePlanner.plan(reviewed(stored,"first pending arrangement","random-b"),LoadedImportTarget(stored,3))
        assertTrue(repeat.problems.toString(),repeat.ready);assertTrue(repeat.unchanged)
        val different=ImportOccurrencePlanner.plan(reviewed(stored,"second pending arrangement","random-c"),LoadedImportTarget(stored,3))
        assertTrue(different.problems.toString(),different.ready);assertFalse(different.unchanged)
        assertEquals("second pending arrangement",different.courses.single().draft.meetings.single().provenance.first().rawText)
    }
    @Test fun `replace accepts a selected InputBase cancellation and preserves global calendar and periods`() {
        val s=snapshot(base(setOf(3),setOf(3)))
        val p=ImportOccurrencePlanner.plan(review(s,listOf(base()),mode=ImportMode.REPLACE,notice=notice(RichExceptionKind.CANCEL)),LoadedImportTarget(s,3))
        assertTrue(p.problems.toString(),p.ready);assertEquals(setOf(1L),p.removedCourseIds)
        val a=after(s,p);assertTrue(ranges(a,monday).isEmpty());assertEquals(2,ranges(a,monday.plusWeeks(1)).size)
        assertEquals(s.settings,a.settings);assertEquals(s.periods,a.periods)
    }
    @Test fun `bounded generated week and section residuals equal independent minute sets`() {
        val random=java.util.Random(774)
        repeat(40) {
            fun subset(max:Int)= (1..max).filter { random.nextBoolean() }.toSet().ifEmpty { setOf(1) }
            val known=base(subset(4),subset(4));val incoming=base(subset(4),subset(4));val s=snapshot(known)
            val p=plan(s,listOf(incoming));val a=after(s,p)
            val input=ScheduledCourse(2,"Course","","",9,listOf(incoming))
            fun minutes(snapshot:TimetableSnapshot,date:LocalDate)=ranges(snapshot,date).flatMap { it.start.toSecondOfDay()/60 until it.end.toSecondOfDay()/60 }.toSet()
            for(week in 0..3) {
                val date=monday.plusWeeks(week.toLong())
                assertEquals(minutes(s,date)+minutes(s.copy(courses=listOf(input)),date),minutes(a,date))
                val newOnly=a.copy(courses=a.courses.filter { it.id!=1L })
                assertEquals(minutes(s.copy(courses=listOf(input)),date)-minutes(s,date),minutes(newOnly,date))
            }
        }
    }
    @Test fun `date and operation bounds fail clearly instead of truncating`() {
        val start=LocalDate.parse("2000-01-01")
        val m=CourseMeeting(day=0,weeks=emptySet(),sections=setOf(1),kind=MeetingKind.DATE_SPECIFIC,dates=(0L until 10_000L).map { start.plusDays(it) }.toSet())
        val p=plan(snapshot(),listOf(m,m.copy(dates=setOf(start.plusDays(10_000)))))
        assertTrue(p.problems.toString(),p.problems.any { it.code=="OCCURRENCE_LIMIT" });assertTrue(p.courses.isEmpty())
        val budget=OccurrenceBudget();repeat(200_000) { budget.compare() }
        assertThrows(ImportPlanningLimit::class.java) { budget.compare() }
    }
    @Test fun `AsNewCourse EXTRA is already materialized by C1 and is not added twice`() {
        val s=snapshot();val target=monday.plusDays(1)
        val raw=RichExceptionDraft(RichExceptionKind.EXTRA,targetDate=target,customTime=DraftTimeRange(r().start,r().end))
        val source=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(),pendingExceptions=listOf(PendingExceptionAttachment(exception=raw,reason="test"))))
        val session=ImportReviewSession.create(source,ImportReviewContext(ImportTarget("local",1,7,3),termMonday=monday,targetPeriods=s.periods))
        val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },attachments=listOf(session.attachException(ReviewLocator(pendingIndex=0),ExceptionAttachment.AsNewCourse(ReviewedCourseIdentity("Course")))))
        val reviewed=(RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
        val p=ImportOccurrencePlanner.plan(reviewed,LoadedImportTarget(s,3))
        assertEquals(1,ranges(after(s,p),target).size);assertTrue(p.courses.single().state.adds.isEmpty())
    }
    @Test fun `known exception override replaces retained unknown identity state when target is already covered`() {
        val d2=monday.plusDays(1)
        val known=CourseMeeting(30,0,emptySet(),setOf(1),teacher="A",kind=MeetingKind.DATE_SPECIFIC,dates=setOf(d2))
        val s=snapshot(known)
        val rawBase=RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,1,setOf(1),setOf(1),missingFields=setOf(com.lingion.sleepy.importing.ImportField.TEACHER))
        val raw=RichExceptionDraft(RichExceptionKind.RESCHEDULE,monday,d2,teacher="A")
        val source=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses=listOf(RichCourseDraft("Course",meetings=listOf(rawBase)))),pendingExceptions=listOf(PendingExceptionAttachment(exception=raw,reason="test"))))
        val session=ImportReviewSession.create(source,ImportReviewContext(ImportTarget("local",1,7,3),termMonday=monday,targetPeriods=s.periods))
        val locator=ReviewLocator(courseIndex=0,meetingIndex=0)
        val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },
            optionalMetadata=listOf(session.retainUnknownOptionalMetadata(locator,setOf(com.lingion.sleepy.importing.ImportField.TEACHER))),
            attachments=listOf(session.attachException(ReviewLocator(pendingIndex=0),ExceptionAttachment.Bound(session.inputBase(locator)))))
        val reviewed=(RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
        val p=ImportOccurrencePlanner.plan(reviewed,LoadedImportTarget(s,3))
        assertTrue(p.problems.toString(),p.ready);assertTrue(p.courses.single().state.adds.isEmpty())
        assertEquals(1,p.courses.single().state.groups.single().witnesses.size)
    }
}
