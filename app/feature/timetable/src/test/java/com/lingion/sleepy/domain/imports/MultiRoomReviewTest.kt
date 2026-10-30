/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.FormatParseResult
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class MultiRoomReviewTest {
    private fun meeting(room:String)=RichMeetingDraft(MeetingKind.RECURRING,dayOfWeek=1,
        weeks=setOf(1,3,5,8),sections=setOf(5,6),customTime=DraftTimeRange(LocalTime.of(14,0),LocalTime.of(15,40)),
        teacher="测试教师",room=room,isExperiment=true,sources=listOf(SourceExcerpt(SourcePosition(1,TextBox(1f,1f,20f,20f)),room)))
    private fun session(meetings:List<RichMeetingDraft>,separateArchives:Boolean=false):ImportReviewSession {
        val courses=if(separateArchives) meetings.map {RichCourseDraft("测试实验",meetings=listOf(it))}
            else listOf(RichCourseDraft("测试实验",meetings=meetings))
        return ImportReviewSession.create(FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses))),
            ImportReviewContext(ImportTarget("synthetic",1,1,3),ImportMode.APPEND,LocalDate.of(2026,9,7),targetPeriods=com.lingion.sleepy.domain.model.defaultPeriods()))
    }
    private fun reviewed(s:ImportReviewSession):ReviewedImport {
        val decisions=ImportReviewDecisions(evidence=s.requirements.filter {it.kind==ReviewRequirementKind.EVIDENCE}.map {s.reviewEvidence(it.fingerprint)})
        val result=RichDraftConverter.convert(s,decisions)
        assertTrue(result is ImportReviewResult.Ready)
        return (result as ImportReviewResult.Ready).reviewed
    }
    @Test fun identicalLessonDifferentRoomsRequiresExplicitEvidenceReview() {
        val s=session(listOf(meeting("实验室A"),meeting("实验室B")))
        assertEquals(1,s.requirements.count {it.code=="SAME_LESSON_MULTIPLE_ROOMS_REVIEW_REQUIRED"})
        assertTrue(RichDraftConverter.convert(s) is ImportReviewResult.Blocked)
    }
    @Test fun reviewedRoomsBecomeOneLessonWithoutDroppingLocationsOrSourceMappings() {
        val s=session(listOf(meeting("实验室B"),meeting("实验室A")))
        val r=reviewed(s);val m=r.courses.single().meetings.single()
        assertEquals("实验室A/实验室B",m.room)
        assertEquals(setOf(1,3,5,8),m.weeks)
        assertEquals(setOf(5,6),m.originalSections)
        assertEquals(2,r.counts.selectedSourceMeetings)
        assertEquals(2,r.sourceMappings.size)
        assertTrue(m.provenance.any {it.rawText=="实验室A"} && m.provenance.any {it.rawText=="实验室B"})
        assertEquals(r.sourceMappings[ReviewLocator(courseIndex=0,meetingIndex=0)],r.sourceMappings[ReviewLocator(courseIndex=0,meetingIndex=1)])
    }
    @Test fun oddEvenOrSparseDifferentWeeksAreNeverCombined() {
        val a=meeting("A").copy(weeks=setOf(1,3,5));val b=meeting("B").copy(weeks=setOf(2,4,6))
        val s=session(listOf(a,b));assertEquals(2,reviewed(s).courses.single().meetings.size)
        assertFalse(s.requirements.any {it.code=="SAME_LESSON_MULTIPLE_ROOMS_REVIEW_REQUIRED"})
    }
    @Test fun differentEffectiveSectionsTeachersGroupsOrProjectsStaySeparate() {
        val a=meeting("A")
        for(b in listOf(meeting("B").copy(sections=setOf(5,7)),meeting("B").copy(teacher="另一教师"),
            meeting("B").copy(group="另一实验组"),meeting("B").copy(experimentProject="另一项目"))) {
            val sectionOnly=b.sections!=a.sections
            assertEquals(2,reviewed(session(listOf(if(sectionOnly) a.copy(customTime=null) else a,
                if(sectionOnly) b.copy(customTime=null) else b))).courses.single().meetings.size)
        }
    }
    @Test fun sameNameInDifferentSourceArchivesIsNeverCombined() {
        assertEquals(2,reviewed(session(listOf(meeting("A"),meeting("B")),true)).courses.size)
    }
    @Test fun reviewedMultiRoomLessonHasNoFalseOccurrenceConflicts() {
        val r=reviewed(session(listOf(meeting("A"),meeting("B"))))
        val target=com.lingion.sleepy.domain.model.TimetableSnapshot(
            settings=com.lingion.sleepy.domain.model.SemesterSettings(startDate=LocalDate.of(2026,9,7)),
            revision=1,scopeToken="synthetic")
        val plan=ImportOccurrencePlanner.plan(r,LoadedImportTarget(target,3))
        assertTrue(plan.problems.toString(),plan.ready)
        assertTrue(plan.conflicts.isEmpty())
        assertEquals(1,plan.courses.single().draft.meetings.size)
        assertEquals("A/B",plan.courses.single().draft.meetings.single().room)
    }
    @Test fun separateSourceArchivesStillProduceRealConflicts() {
        val r=reviewed(session(listOf(meeting("A"),meeting("B")),true))
        val target=com.lingion.sleepy.domain.model.TimetableSnapshot(
            settings=com.lingion.sleepy.domain.model.SemesterSettings(startDate=LocalDate.of(2026,9,7)),
            revision=1,scopeToken="synthetic")
        val plan=ImportOccurrencePlanner.plan(r,LoadedImportTarget(target,3))
        assertFalse(plan.ready)
        assertTrue(plan.conflicts.isNotEmpty())
    }    @Test fun exactOwnClockFragmentsKeepSparseSectionUnionWithoutDuplicateLesson() {
        val a=meeting("A").copy(sections=setOf(5,7))
        val b=meeting("A").copy(sections=setOf(7,9))
        val s=session(listOf(a,b))
        assertTrue(RichDraftConverter.convert(s) is ImportReviewResult.Blocked)
        val m=reviewed(s).courses.single().meetings.single()
        assertEquals(setOf(5,7,9),m.originalSections)
        assertEquals("A",m.room)
        assertEquals(ReviewedTimeSpec.Continuous(com.lingion.sleepy.domain.model.TimeRange(LocalTime.of(14,0),LocalTime.of(15,40))),m.time)
    }
    @Test fun ownClockWithMissingAndPrintedSectionsKeepsBothRoomsAndKnownSections() {
        val a=meeting("A").copy(sections=emptySet())
        val b=meeting("B").copy(sections=setOf(5,6))
        val m=reviewed(session(listOf(a,b))).courses.single().meetings.single()
        assertEquals("A/B",m.room)
        assertEquals(setOf(5,6),m.originalSections)
    }    @Test fun partiallyOverlappingWeekSetsAreNeverUnioned() {
        val a=meeting("A").copy(weeks=setOf(1,3));val b=meeting("B").copy(weeks=setOf(3,5))
        assertEquals(2,reviewed(session(listOf(a,b))).courses.single().meetings.size)
    }
    @Test fun differentOwnClocksAreNeverUnioned() {
        val a=meeting("A");val b=meeting("B").copy(customTime=DraftTimeRange(LocalTime.of(14,30),LocalTime.of(16,0)))
        assertEquals(2,reviewed(session(listOf(a,b))).courses.single().meetings.size)
    }}
