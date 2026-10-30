/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ImportNormalizationCodecTest {
    private val monday=LocalDate.parse("2026-09-07")
    private val original=CourseMeeting(10,1,setOf(1,2),setOf(1,2,3),provenance=listOf(ScheduleProvenance(rawText="original",sourceLocator="row:2")))
    private fun initial()=TimetableSnapshot(SemesterSettings(startDate=monday),defaultPeriods(),listOf(ScheduledCourse(1,"Course","","",9,listOf(original))),7,"local")
    private fun reviewed(s:TimetableSnapshot,ref:LogicalBaseReference.StoredLogicalBase,kind:RichExceptionKind=RichExceptionKind.CANCEL,
        sections:Set<Int>?=setOf(2),target:LocalDate?=null):ReviewedImport {
        val raw=RichExceptionDraft(kind,if(kind==RichExceptionKind.EXTRA) null else monday,target,sections=sections)
        val source=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(),pendingExceptions=listOf(PendingExceptionAttachment(exception=raw,reason="test"))))
        val session=ImportReviewSession.create(source,ImportReviewContext(ImportTarget(s.scopeToken,s.settings.id,s.revision,3),termMonday=monday,targetPeriods=s.periods))
        val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },attachments=listOf(session.attachException(ReviewLocator(pendingIndex=0),ExceptionAttachment.Bound(ref))))
        val ready=RichDraftConverter.convert(session,decisions)
        assertTrue(ready.toString(),ready is ImportReviewResult.Ready)
        return (ready as ImportReviewResult.Ready).reviewed
    }
    private data class Realized(val snapshot:TimetableSnapshot,val state:StoredNormalizationState,val plan:PlannedCourse)
    private fun realize(s:TimetableSnapshot,p:PlannedCourse,newId:Long=1,plannedCourseIds:Map<String,Long> = emptyMap()):Realized {
        val fragmentIds=p.fragments.mapIndexed { i,f->f.key to i+100L }.toMap()
        var exceptionId=500L;val addIds=mutableMapOf<String,Long>()
        val meetings=p.fragments.map { f->f.meeting.copy(id=fragmentIds.getValue(f.key),exceptions=f.meeting.exceptions.map { e->
            val id=exceptionId++
            if(e.kind==ExceptionKind.EXTRA) {
                val add=p.state.adds.single { it.ownerFragmentKey==f.key && it.scope.date==e.targetDate && it.scope.range==e.customTime && it.identity.teacher==e.teacher && it.identity.room==e.room && it.identity.group==e.group && it.identity.project==e.experimentProject }
                addIds[add.exceptionKey]=id
            }
            e.copy(id=id)
        }) }
        val c=ScheduledCourse(p.existingCourseId ?: newId,p.draft.courseName,p.draft.teacher,p.draft.room,p.draft.color,meetings,p.draft.sourceCourseCode,p.draft.sourceClassCode,p.draft.provenance)
        val snapshot=s.copy(courses=s.courses.filter { it.id!=c.id }+c)
        return Realized(snapshot,ImportNormalizationCodec.hydrate(p,c,s.scopeToken,s.settings.id,snapshot,fragmentIds,addIds,plannedCourseIds),p)
    }
    private fun partial():Realized {
        val s=initial();val target=LoadedImportTarget(s,3);val ref=ImportOccurrencePlanner.logicalBases(target).single()
        val p=ImportOccurrencePlanner.plan(reviewed(s,ref),target)
        assertTrue(p.problems.toString(),p.ready)
        return realize(s,p.courses.single())
    }
    private fun ranges(s:TimetableSnapshot,d:LocalDate)=TimetableRules.occurrencesOn(s,d).flatMap { it.timeRanges }.sortedBy { it.start }
    @Test fun `typed state round trips canonically and repeated partial cancellation is no op after reopen`() {
        val a=partial();val course=a.snapshot.courses.single();val json=ImportNormalizationCodec.encode(a.state)
        val state=ImportNormalizationCodec.decode(json,"local",1,course,a.snapshot)
        assertEquals(a.state,state);assertEquals(json,ImportNormalizationCodec.encode(state))
        val target=LoadedImportTarget(a.snapshot,3,mapOf(1L to state));val ref=ImportOccurrencePlanner.logicalBases(target).single()
        val repeated=ImportOccurrencePlanner.plan(reviewed(a.snapshot,ref),target)
        assertTrue(repeated.problems.toString(),repeated.ready);assertTrue(repeated.unchanged);assertTrue(repeated.courses.isEmpty())
        assertEquals(setOf(10L),state.graph.originals.single().oldMeetingAliases)
        assertEquals(3,state.fragments.size)
    }
    @Test fun `second section cancel only splits remaining original day and preserves week two`() {
        val a=partial();val target=LoadedImportTarget(a.snapshot,3,mapOf(1L to a.state));val ref=ImportOccurrencePlanner.logicalBases(target).single()
        val p=ImportOccurrencePlanner.plan(reviewed(a.snapshot,ref,sections=setOf(1)),target)
        assertTrue(p.problems.toString(),p.ready)
        val b=realize(a.snapshot,p.courses.single())
        assertEquals(listOf(TimeRange(LocalTime.parse("10:00"),LocalTime.parse("10:45"))),ranges(b.snapshot,monday))
        assertEquals(3,ranges(b.snapshot,monday.plusWeeks(1)).size)
        assertEquals(2,b.state.graph.removes.size)
        ImportNormalizationCodec.decode(ImportNormalizationCodec.encode(b.state),"local",1,b.snapshot.courses.single(),b.snapshot)
    }
    @Test fun `unknown corrupt foreign oversized and wrong actual shape states fail closed`() {
        val a=partial();val c=a.snapshot.courses.single();val json=ImportNormalizationCodec.encode(a.state)
        for(payload in listOf(json.replace("\"version\":1","\"version\":99"),json.dropLast(1)," ".repeat(4_194_305)))
            assertThrows(InvalidNormalizationState::class.java) { ImportNormalizationCodec.decode(payload,"local",1,c,a.snapshot) }
        assertThrows(InvalidNormalizationState::class.java) { ImportNormalizationCodec.decode(json,"foreign",1,c,a.snapshot) }
        assertThrows(InvalidNormalizationState::class.java) { ImportNormalizationCodec.decode(json,"local",2,c,a.snapshot) }
        assertThrows(InvalidNormalizationState::class.java) { ImportNormalizationCodec.decode(json,"local",1,c.copy(id=2),a.snapshot) }
        assertThrows(InvalidNormalizationState::class.java) { ImportNormalizationCodec.decode(json,"local",1,c.copy(meetings=c.meetings.drop(1)),a.snapshot) }
        assertThrows(InvalidNormalizationState::class.java) { ImportNormalizationCodec.decode(json,"local",1,c.copy(meetings=c.meetings.mapIndexed { i,m->if(i==0) m.copy(teacher="edited") else m }),a.snapshot) }
    }
    @Test fun `source position content references and original provenance survive codec`() {
        val a=partial();val c=a.snapshot.courses.single();val decoded=ImportNormalizationCodec.decode(ImportNormalizationCodec.encode(a.state),"local",1,c,a.snapshot)
        assertEquals(original.provenance,decoded.graph.originals.single().original.provenance)
        assertTrue(c.meetings.all { it.provenance==original.provenance })
        assertTrue(c.meetings.flatMap { it.provenance }.none { it.sourceLocator?.contains("family") == true })
    }
    @Test fun `hydration refuses missing assignments and never invents database ids`() {
        val a=partial();val c=a.snapshot.courses.single()
        assertThrows(InvalidNormalizationState::class.java) { ImportNormalizationCodec.hydrate(a.plan,c,"local",1,a.snapshot,emptyMap(),emptyMap()) }
        assertTrue(a.plan.fragments.all { it.meeting.id==0L })
    }
    @Test fun `stored provenance references validate actual owner list position and raw content`() {
        val a=partial();val c=a.snapshot.courses.single();val ref=a.state.sourceReferences.first()
        for(bad in listOf(ref.copy(courseId=999),ref.copy(meetingId=999),ref.copy(position=999),ref.copy(contentFingerprint="0".repeat(64)))) {
            val altered=a.state.copy(sourceReferences=listOf(bad)+a.state.sourceReferences.drop(1))
            assertThrows(InvalidNormalizationState::class.java) { ImportNormalizationCodec.decode(ImportNormalizationCodec.encode(altered),"local",1,c,a.snapshot) }
        }
    }
    @Test fun `original base reimport cannot resurrect a negative interval`() {
        val a=partial();val source=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses=listOf(RichCourseDraft("Course",meetings=listOf(RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,1,setOf(1,2),setOf(1,2,3))))))))
        val session=ImportReviewSession.create(source,ImportReviewContext(ImportTarget("local",1,7,3),termMonday=monday,targetPeriods=a.snapshot.periods))
        val reviewed=(RichDraftConverter.convert(session,ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) })) as ImportReviewResult.Ready).reviewed
        val p=ImportOccurrencePlanner.plan(reviewed,LoadedImportTarget(a.snapshot,3,mapOf(1L to a.state)))
        assertTrue(p.problems.toString(),p.ready);assertTrue(p.unchanged);assertTrue(p.courses.isEmpty())
    }
    @Test fun `explicit reimport fills coverage formerly owned by a deleted other course`() {
        val s=initial().copy(courses=listOf(initial().courses.single().copy(meetings=listOf(original.copy(weeks=setOf(1),sections=setOf(1))))))
        val full=RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,1,setOf(1,2),setOf(1))
        fun reviewed(snapshot:TimetableSnapshot):ReviewedImport {
            val source=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses=listOf(RichCourseDraft("Course",meetings=listOf(full))))))
            val session=ImportReviewSession.create(source,ImportReviewContext(ImportTarget("local",1,7,3),termMonday=monday,targetPeriods=snapshot.periods))
            return (RichDraftConverter.convert(session,ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) })) as ImportReviewResult.Ready).reviewed
        }
        val first=ImportOccurrencePlanner.plan(reviewed(s),LoadedImportTarget(s,3))
        assertTrue(first.problems.toString(),first.ready)
        val realized=realize(s,first.courses.single(),2)
        val withoutOther=realized.snapshot.copy(courses=realized.snapshot.courses.filter { it.id==2L })
        val second=ImportOccurrencePlanner.plan(reviewed(withoutOther),LoadedImportTarget(withoutOther,3,mapOf(2L to realized.state)))
        assertTrue(second.problems.toString(),second.ready);assertFalse(second.unchanged)
        val result=realize(withoutOther,second.courses.single(),2)
        assertEquals(1,ranges(result.snapshot,monday).size);assertEquals(1,ranges(result.snapshot,monday.plusWeeks(1)).size)
        val conflicting=withoutOther.copy(courses=withoutOther.courses+ScheduledCourse(3,"Other","","",9,listOf(original.copy(id=30,weeks=setOf(1),sections=setOf(1)))))
        val conflictInput=reviewed(conflicting);val conflictTarget=LoadedImportTarget(conflicting,3,mapOf(2L to realized.state))
        val reviewedConflict=ImportOccurrencePlanner.plan(conflictInput,conflictTarget)
        assertTrue(reviewedConflict.conflicts.toString(),reviewedConflict.conflicts.any { it.incoming.date==monday })
        assertTrue(reviewedConflict.problems.any { it.code=="CONFLICT_REVIEW_REQUIRED" })
        val skip=ConflictReviewDecision(conflictInput.binding,reviewedConflict.conflicts.single().fingerprint,ConflictReviewChoice.SKIP)
        val skipped=ImportOccurrencePlanner.plan(conflictInput,conflictTarget,listOf(skip))
        assertTrue(skipped.problems.toString(),skipped.ready);assertTrue(skipped.unchanged)
    }
    @Test fun `independent archive identity and notice binding survive hydration and repeat`() {
        val s=initial().copy(courses=emptyList());val p0=ReviewLocator(courseIndex=0,meetingIndex=0);val p1=ReviewLocator(courseIndex=1,meetingIndex=0)
        val m=RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,1,setOf(1),setOf(1))
        val courses=listOf(RichCourseDraft("Course",meetings=listOf(m)),RichCourseDraft("Course",meetings=listOf(m)))
        val format=FormatParseResult(RichTimetableDraft(courses=courses),pendingExceptions=listOf(PendingExceptionAttachment(exception=RichExceptionDraft(RichExceptionKind.CANCEL,monday),reason="test")))
        val candidates=courses.mapIndexed { i,c->com.lingion.sleepy.importing.ocr.OcrCandidateCourse(com.lingion.sleepy.importing.ocr.CandidateCourseId("candidate-$i"),c,listOf("r$i"),emptyList(),DraftPath(i)) }
        val outer=com.lingion.sleepy.importing.ocr.OcrLayoutParseResult(format,com.lingion.sleepy.importing.ocr.OcrLayoutDocument(emptyList()),candidateCourses=candidates,identityProposals=listOf(com.lingion.sleepy.importing.ocr.ArchiveIdentityProposal(candidates.map { it.id },com.lingion.sleepy.importing.ocr.ArchiveIdentityEvidence.SAME_TITLE,emptyList())))
        fun reviewed(snapshot:TimetableSnapshot):ReviewedImport {
            val session=ImportReviewSession.create(FrozenImportSource.fromOcr(outer),ImportReviewContext(ImportTarget("local",1,7,3),termMonday=monday,targetPeriods=snapshot.periods))
            val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },identity=session.reviewIdentity(IdentityReviewKind.INDEPENDENT,listOf(IdentityArchive(setOf(p0)),IdentityArchive(setOf(p1)))),attachments=listOf(session.attachException(ReviewLocator(pendingIndex=0),ExceptionAttachment.Bound(session.inputBase(p1)))))
            return (RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
        }
        val first=ImportOccurrencePlanner.plan(reviewed(s),LoadedImportTarget(s,3));assertTrue(first.problems.toString(),first.ready);assertEquals(2,first.courses.size)
        val a=realize(s,first.courses.single { it.key=="input:0" },1)
        val b=realize(a.snapshot,first.courses.single { it.key=="input:1" },2)
        val states=mapOf(1L to ImportNormalizationCodec.decode(ImportNormalizationCodec.encode(a.state),"local",1,b.snapshot.courses.single { it.id==1L },b.snapshot),2L to ImportNormalizationCodec.decode(ImportNormalizationCodec.encode(b.state),"local",1,b.snapshot.courses.single { it.id==2L },b.snapshot))
        val repeat=ImportOccurrencePlanner.plan(reviewed(b.snapshot),LoadedImportTarget(b.snapshot,3,states))
        assertTrue(repeat.problems.toString(),repeat.ready);assertTrue(repeat.unchanged)
        assertEquals(0,states.getValue(1L).graph.removes.size);assertEquals(1,states.getValue(2L).graph.removes.size)
    }
    @Test fun `changed partition or source evidence cannot reuse historical archive aliases`() {
        val s=initial().copy(courses=emptyList())
        val locators=(0..2).map { ReviewLocator(courseIndex=it,meetingIndex=0) }
        val courses=(1..3).map { week->RichCourseDraft("Course",meetings=listOf(RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,1,setOf(week),setOf(1)))) }
        fun reviewed(groups:List<Set<ReviewLocator>>,id:String,raw2:String="raw 2",withNotice:Boolean=false,locator2:String="row:2",kind:IdentityReviewKind=IdentityReviewKind.PARTITION):ReviewedImport {
            val refs=(0..2).map { i->ImportSourceRef("$id-$i",if(i==2) locator2 else "row:$i",if(i==2) raw2 else "raw $i",DraftPath(i,0)) }
            val pending=if(withNotice) listOf(PendingExceptionAttachment(exception=RichExceptionDraft(RichExceptionKind.CANCEL,monday.plusWeeks(2)),reason="test")) else emptyList()
            val format=FormatParseResult(RichTimetableDraft(courses=courses),sources=refs,pendingExceptions=pending)
            val candidates=courses.mapIndexed { i,c->com.lingion.sleepy.importing.ocr.OcrCandidateCourse(com.lingion.sleepy.importing.ocr.CandidateCourseId("candidate-$i"),c,listOf("r$i"),emptyList(),DraftPath(i)) }
            val outer=com.lingion.sleepy.importing.ocr.OcrLayoutParseResult(format,com.lingion.sleepy.importing.ocr.OcrLayoutDocument(emptyList()),candidateCourses=candidates,identityProposals=listOf(com.lingion.sleepy.importing.ocr.ArchiveIdentityProposal(candidates.map { it.id },com.lingion.sleepy.importing.ocr.ArchiveIdentityEvidence.SAME_TITLE,emptyList())))
            val session=ImportReviewSession.create(FrozenImportSource.fromOcr(outer),ImportReviewContext(ImportTarget("local",1,7,3),termMonday=monday,targetPeriods=s.periods),selectedMeetings=groups.flatten().toSet())
            val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },identity=session.reviewIdentity(kind,groups.map { IdentityArchive(it) }),attachments=if(withNotice) listOf(session.attachException(ReviewLocator(pendingIndex=0),ExceptionAttachment.Bound(session.inputBase(locators[2])))) else emptyList())
            return (RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
        }
        val original=listOf(setOf(locators[0],locators[1]),setOf(locators[2]))
        val first=ImportOccurrencePlanner.plan(reviewed(original,"first"),LoadedImportTarget(s,3));assertTrue(first.problems.toString(),first.ready)
        val a=realize(s,first.courses.single { it.key=="input:0" },1);val b=realize(a.snapshot,first.courses.single { it.key=="input:1" },2)
        val target=LoadedImportTarget(b.snapshot,3,mapOf(1L to a.state,2L to b.state))
        val same=ImportOccurrencePlanner.plan(reviewed(original,"new-random-ids"),target)
        assertTrue(same.problems.toString(),same.ready);assertTrue(same.unchanged)
        val subset=ImportOccurrencePlanner.plan(reviewed(listOf(setOf(locators[0]),setOf(locators[2])),"subset"),target)
        assertTrue(subset.problems.toString(),subset.ready);assertTrue(subset.unchanged)
        val changedRaw=ImportOccurrencePlanner.plan(reviewed(original,"fourth",raw2="different evidence",withNotice=true),target)
        assertFalse(changedRaw.ready);assertTrue(changedRaw.problems.any { it.code=="ARCHIVE_MEMBERSHIP_REVIEW_REQUIRED" && it.source==locators[2] });assertTrue(changedRaw.courses.isEmpty())
        val movedRef=ImportOccurrencePlanner.plan(reviewed(original,"fifth",locator2="row:changed"),target)
        assertFalse(movedRef.ready);assertTrue(movedRef.problems.any { it.code=="ARCHIVE_MEMBERSHIP_REVIEW_REQUIRED" && it.source==locators[2] })
        val merged=ImportOccurrencePlanner.plan(reviewed(listOf(locators.toSet()),"merge",withNotice=true,kind=IdentityReviewKind.MERGE),target)
        assertFalse(merged.ready);assertTrue(merged.problems.any { it.code=="ARCHIVE_MEMBERSHIP_REVIEW_REQUIRED" && it.source in locators });assertTrue(merged.courses.isEmpty())
        val regrouped=listOf(setOf(locators[0],locators[2]),setOf(locators[1]))
        val changed=ImportOccurrencePlanner.plan(reviewed(regrouped,"third",withNotice=true),target)
        assertFalse(changed.ready);assertTrue(changed.problems.any { it.code=="ARCHIVE_MEMBERSHIP_REVIEW_REQUIRED" && it.source in locators });assertTrue(changed.courses.isEmpty())
    }
    private fun ocrReviewed(id:String,courses:List<RichCourseDraft>,groups:List<Set<ReviewLocator>>,kind:IdentityReviewKind,periods:List<Period>,
        refs:List<ImportSourceRef>?=null,differentSource:Boolean=false):ReviewedImport {
        val sources=refs ?: courses.indices.map { i->ImportSourceRef("$id-$i","$id-row:$i","$id raw $i",DraftPath(i,0)) }
        val format=FormatParseResult(RichTimetableDraft(courses=courses),sources=sources)
        val candidates=courses.mapIndexed { i,c->com.lingion.sleepy.importing.ocr.OcrCandidateCourse(com.lingion.sleepy.importing.ocr.CandidateCourseId("$id-candidate-$i"),c,listOf("r$i"),emptyList(),DraftPath(i)) }
        val outer=com.lingion.sleepy.importing.ocr.OcrLayoutParseResult(format,com.lingion.sleepy.importing.ocr.OcrLayoutDocument(emptyList()),candidateCourses=candidates,identityProposals=listOf(com.lingion.sleepy.importing.ocr.ArchiveIdentityProposal(candidates.map { it.id },com.lingion.sleepy.importing.ocr.ArchiveIdentityEvidence.SAME_TITLE,emptyList())))
        val session=ImportReviewSession.create(FrozenImportSource.fromOcr(outer),ImportReviewContext(ImportTarget("local",1,7,3),termMonday=monday,targetPeriods=periods),selectedMeetings=groups.flatten().toSet())
        val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },identity=session.reviewIdentity(kind,groups.map { IdentityArchive(it) }),sourceRelation=if(differentSource) session.reviewSourceRelation(SourceRelationChoice.DIFFERENT_SOURCE) else null)
        val ready=RichDraftConverter.convert(session,decisions)
        assertTrue(ready.toString(),ready is ImportReviewResult.Ready)
        return (ready as ImportReviewResult.Ready).reviewed
    }
    private fun paperCourses()=(1..2).map { week->RichCourseDraft("Paper Course",meetings=listOf(RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,1,setOf(week),setOf(1)))) }
    private fun paperRefs()=(0..1).map { i->ImportSourceRef("paper-$i","paper-row:$i","paper raw $i",DraftPath(i,0)) }
    /** A source of one file, persisted with its local source aliases at (0,0) and (1,0). */
    private fun seededPaperSnapshot():Triple<TimetableSnapshot,Map<Long,StoredNormalizationState>,List<ReviewLocator>> {
        val s=initial().copy(courses=emptyList())
        val locators=(0..1).map { ReviewLocator(courseIndex=it,meetingIndex=0) }
        val paper=paperCourses()
        val first=ImportOccurrencePlanner.plan(ocrReviewed("paper",paper,locators.map { setOf(it) },IdentityReviewKind.PARTITION,s.periods),LoadedImportTarget(s,3))
        assertTrue(first.problems.toString(),first.ready)
        val a=realize(s,first.courses.single { it.key=="input:0" },1)
        val b=realize(a.snapshot,first.courses.single { it.key=="input:1" },2)
        return Triple(b.snapshot,mapOf(1L to a.state,2L to b.state),locators)
    }
    /** Zero-based positions collide with the stored source, but weekday/week and evidence are unrelated. */
    private fun imageCourses()=(0..1).map { i->RichCourseDraft("Image Course",meetings=listOf(RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,3,setOf(3+i),setOf(2)))) }
    @Test fun `appending an unrelated image needs one whole-batch source answer`() {
        val (snapshot,states,locators)=seededPaperSnapshot()
        val target=LoadedImportTarget(snapshot,3,states)
        val groups=locators.map { setOf(it) }
        // Positions collide, no content matches: the relationship cannot be proven from stored evidence.
        val unresolved=ImportOccurrencePlanner.plan(ocrReviewed("image",imageCourses(),groups,IdentityReviewKind.INDEPENDENT,snapshot.periods),target)
        assertFalse(unresolved.problems.toString(),unresolved.ready)
        assertTrue(unresolved.sourceRelationRequired)
        assertTrue(unresolved.courses.isEmpty())
        val decided=ImportOccurrencePlanner.plan(ocrReviewed("image",imageCourses(),groups,IdentityReviewKind.INDEPENDENT,snapshot.periods,differentSource=true),target)
        assertTrue(decided.problems.toString(),decided.ready)
        assertFalse(decided.sourceRelationRequired)
        assertEquals(2,decided.courses.size)
        assertTrue(decided.courses.all { it.existingCourseId==null })
        assertTrue(decided.removedCourseIds.isEmpty())
        // A single-course image defaults to MERGE and must accept the same batch answer.
        val merged=ImportOccurrencePlanner.plan(ocrReviewed("merged",listOf(imageCourses()[0]),listOf(setOf(locators[0])),IdentityReviewKind.MERGE,snapshot.periods,differentSource=true),target)
        assertTrue(merged.problems.toString(),merged.ready)
    }
    @Test fun `wholly changed old source requires the batch answer then appends idempotently`() {
        val (snapshot,states,locators)=seededPaperSnapshot()
        val target=LoadedImportTarget(snapshot,3,states)
        val groups=locators.map { setOf(it) }
        val unresolved=ImportOccurrencePlanner.plan(ocrReviewed("other",imageCourses(),groups,IdentityReviewKind.INDEPENDENT,snapshot.periods),target)
        assertFalse(unresolved.problems.toString(),unresolved.ready)
        assertTrue(unresolved.sourceRelationRequired)
        assertTrue(unresolved.problems.all { it.code=="ARCHIVE_MEMBERSHIP_REVIEW_REQUIRED" })
        assertEquals(locators.toSet(),unresolved.problems.mapNotNull { it.source }.toSet())
        assertTrue(unresolved.courses.isEmpty())
        val first=ImportOccurrencePlanner.plan(ocrReviewed("other",imageCourses(),groups,IdentityReviewKind.INDEPENDENT,snapshot.periods,differentSource=true),target)
        assertTrue(first.problems.toString(),first.ready)
        assertEquals(2,first.courses.size)
        val c=realize(snapshot,first.courses.single { it.key=="input:0" },3)
        val d=realize(c.snapshot,first.courses.single { it.key=="input:1" },4)
        assertEquals(4,d.snapshot.courses.size)
        val repeat=ImportOccurrencePlanner.plan(ocrReviewed("other",imageCourses(),groups,IdentityReviewKind.INDEPENDENT,d.snapshot.periods),LoadedImportTarget(d.snapshot,3,states+mapOf(3L to c.state,4L to d.state)))
        assertTrue(repeat.problems.toString(),repeat.ready)
        assertTrue(repeat.unchanged)
        assertTrue(repeat.courses.isEmpty())
        assertFalse(repeat.sourceRelationRequired)
    }
    @Test fun `partially coinciding new source dedups the shared course and appends the rest`() {
        val (snapshot,states,locators)=seededPaperSnapshot()
        val target=LoadedImportTarget(snapshot,3,states)
        val groups=locators.map { setOf(it) }
        // (0,0) is identical to the stored source; (1,0) is unrelated, so the batch is not one source.
        val mixed=listOf(paperCourses()[0],RichCourseDraft("Image Course",meetings=listOf(RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,3,setOf(5),setOf(2)))))
        val refs=listOf(paperRefs()[0],ImportSourceRef("mixed-1","mixed-row:1","mixed raw 1",DraftPath(1,0)))
        val unresolved=ImportOccurrencePlanner.plan(ocrReviewed("mixed",mixed,groups,IdentityReviewKind.INDEPENDENT,snapshot.periods,refs),target)
        assertFalse(unresolved.problems.toString(),unresolved.ready)
        assertTrue(unresolved.sourceRelationRequired)
        val decided=ImportOccurrencePlanner.plan(ocrReviewed("mixed",mixed,groups,IdentityReviewKind.INDEPENDENT,snapshot.periods,refs,true),target)
        assertTrue(decided.problems.toString(),decided.ready)
        assertEquals(1,decided.courses.size)
        assertNull(decided.courses.single().existingCourseId)
        assertTrue(decided.removedCourseIds.isEmpty())
        val c=realize(snapshot,decided.courses.single(),3)
        assertEquals(3,c.snapshot.courses.size)
        val repeat=ImportOccurrencePlanner.plan(ocrReviewed("mixed",mixed,groups,IdentityReviewKind.INDEPENDENT,c.snapshot.periods,refs),LoadedImportTarget(c.snapshot,3,states+mapOf(3L to c.state)))
        assertTrue(repeat.problems.toString(),repeat.ready)
        assertTrue(repeat.unchanged)
        assertTrue(repeat.courses.isEmpty())
        assertFalse(repeat.sourceRelationRequired)
    }
    @Test fun `batch source answer cannot bypass genuine regrouped archive ambiguity`() {
        val (snapshot,states,locators)=seededPaperSnapshot()
        // One archive whose members each match a DIFFERENT stored course stays a real conflict.
        val merged=ImportOccurrencePlanner.plan(ocrReviewed("merge",paperCourses(),listOf(locators.toSet()),IdentityReviewKind.MERGE,snapshot.periods,paperRefs(),true),LoadedImportTarget(snapshot,3,states))
        assertFalse(merged.problems.toString(),merged.ready)
        assertTrue(merged.problems.any { it.code=="ARCHIVE_MEMBERSHIP_REVIEW_REQUIRED" })
        assertTrue(merged.courses.isEmpty())
        assertFalse(merged.sourceRelationRequired)
    }
    @Test fun `archive membership scanning shares invocation comparison budget`() {
        val a=partial();val first=a.state.graph.originals.single().familyId
        val aliases=(0 until 10_000).map { i->SourceContentReference(first,ReviewLocator(courseIndex=i,meetingIndex=0,field="other"),"0".repeat(64)) }
        val state=a.state.copy(graph=a.state.graph.copy(sources=aliases))
        val courses=(0 until 21).map { RichCourseDraft("Course",meetings=listOf(RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,1,setOf(1),setOf(1)))) }
        val format=FormatParseResult(RichTimetableDraft(courses=courses))
        val candidates=courses.mapIndexed { i,c->com.lingion.sleepy.importing.ocr.OcrCandidateCourse(com.lingion.sleepy.importing.ocr.CandidateCourseId("candidate-$i"),c,listOf("r$i"),emptyList(),DraftPath(i)) }
        val outer=com.lingion.sleepy.importing.ocr.OcrLayoutParseResult(format,com.lingion.sleepy.importing.ocr.OcrLayoutDocument(emptyList()),candidateCourses=candidates,identityProposals=listOf(com.lingion.sleepy.importing.ocr.ArchiveIdentityProposal(candidates.map { it.id },com.lingion.sleepy.importing.ocr.ArchiveIdentityEvidence.SAME_TITLE,emptyList())))
        val session=ImportReviewSession.create(FrozenImportSource.fromOcr(outer),ImportReviewContext(ImportTarget("local",1,7,3),termMonday=monday,targetPeriods=a.snapshot.periods))
        val groups=courses.indices.map { IdentityArchive(setOf(ReviewLocator(courseIndex=it,meetingIndex=0))) }
        val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },identity=session.reviewIdentity(IdentityReviewKind.PARTITION,groups))
        val reviewed=(RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
        val result=ImportOccurrencePlanner.plan(reviewed,LoadedImportTarget(a.snapshot,3,mapOf(1L to state)))
        assertFalse(result.ready);assertTrue(result.problems.any { it.code=="OCCURRENCE_LIMIT" });assertTrue(result.courses.isEmpty())
    }
    @Test fun `external satisfaction witness has no live ownership or replay obligation after other course deletion`() {
        val s=initial().let { snapshot->
            val f1=snapshot.courses.single().copy(meetings=listOf(original.copy(weeks=setOf(1),sections=setOf(1))))
            val f2=ScheduledCourse(2,"Course","","",9,listOf(CourseMeeting(50,0,emptySet(),setOf(1),kind=com.lingion.sleepy.domain.model.MeetingKind.DATE_SPECIFIC,dates=setOf(monday.plusDays(1)))))
            snapshot.copy(courses=listOf(f1,f2))
        }
        val t=LoadedImportTarget(s,3);val ref=ImportOccurrencePlanner.logicalBases(t).first()
        val p=ImportOccurrencePlanner.plan(reviewed(s,ref,RichExceptionKind.RESCHEDULE,sections=null,target=monday.plusDays(1)),t)
        assertTrue(p.problems.toString(),p.ready);assertEquals(1,p.courses.size)
        val a=realize(s,p.courses.single());assertTrue(a.state.graph.adds.isEmpty())
        assertEquals(1,a.state.graph.groups.single().witnesses.size)
        assertTrue(a.state.fragments.all { f->a.snapshot.courses.single { it.id==1L }.meetings.any { it.id==f.meetingId } })
        val withoutOther=a.snapshot.copy(courses=a.snapshot.courses.filter { it.id==1L });val c=withoutOther.courses.single()
        val reopened=ImportNormalizationCodec.decode(ImportNormalizationCodec.encode(a.state),"local",1,c,withoutOther)
        assertTrue(ranges(withoutOther,monday.plusDays(1)).isEmpty())
        val target=LoadedImportTarget(withoutOther,3,mapOf(1L to reopened));val binding=ImportOccurrencePlanner.logicalBases(target).single()
        val repeat=ImportOccurrencePlanner.plan(reviewed(withoutOther,binding,RichExceptionKind.RESCHEDULE,sections=null,target=monday.plusDays(1)),target)
        assertTrue(repeat.problems.toString(),repeat.ready);assertFalse(repeat.unchanged)
        val b=realize(withoutOther,repeat.courses.single())
        assertEquals(1,b.state.graph.adds.size)
        assertEquals(1,ranges(b.snapshot,monday.plusDays(1)).size)
    }
    @Test fun `new batch coverage retains a historical satisfaction witness without fake owner ids`() {
        val s=initial().copy(courses=emptyList());val d2=monday.plusDays(1)
        val courses=listOf(
            RichCourseDraft("Course",meetings=listOf(RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.DATE_SPECIFIC,dates=setOf(d2),sections=setOf(1)))),
            RichCourseDraft("Course",meetings=listOf(RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,1,setOf(1),setOf(1)))))
        val raw=RichExceptionDraft(RichExceptionKind.RESCHEDULE,monday,d2)
        val source=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses=courses),pendingExceptions=listOf(PendingExceptionAttachment(exception=raw,reason="test"))))
        val session=ImportReviewSession.create(source,ImportReviewContext(ImportTarget("local",1,7,3),termMonday=monday,targetPeriods=s.periods))
        val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },attachments=listOf(session.attachException(ReviewLocator(pendingIndex=0),ExceptionAttachment.Bound(session.inputBase(ReviewLocator(courseIndex=1,meetingIndex=0))))))
        val reviewed=(RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
        val p=ImportOccurrencePlanner.plan(reviewed,LoadedImportTarget(s,3));assertTrue(p.problems.toString(),p.ready)
        val removed=p.courses.single { it.state.removes.isNotEmpty() }
        assertTrue(removed.state.adds.isEmpty());assertEquals(1,removed.state.groups.single().witnesses.size)
        val covering=p.courses.single { it.key!=removed.key }
        assertEquals(ObservationOwner.Planned(covering.key),removed.state.groups.single().witnesses.single().owner)
        val first=realize(s,covering,2)
        assertThrows(InvalidNormalizationState::class.java) { realize(first.snapshot,removed,1) }
        assertThrows(InvalidNormalizationState::class.java) { realize(first.snapshot,removed,1,mapOf(covering.key to 99L)) }
        val second=realize(first.snapshot,removed,1,mapOf(covering.key to 2L))
        assertEquals(ObservationOwner.Stored(2),second.state.graph.groups.single().witnesses.single().owner)
        val withoutCover=second.snapshot.copy(courses=second.snapshot.courses.filter { it.id==1L })
        val reopened=ImportNormalizationCodec.decode(ImportNormalizationCodec.encode(second.state),"local",1,withoutCover.courses.single(),withoutCover)
        assertEquals(second.state,reopened)
    }
}
