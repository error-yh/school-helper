/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.importing.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ImportUnscheduledReviewTest {
    private val p = ReviewLocator(courseIndex = 0,meetingIndex = 0)
    private fun session(m:RichMeetingDraft):ImportReviewSession = ImportReviewSession.create(FrozenImportSource.fromPdf(RichTimetableDraft(courses = listOf(RichCourseDraft("Practice",meetings = listOf(m))))),ImportReviewContext(ImportTarget("local",1,0,3)))
    private fun unplanned(missing:Set<ImportField> = setOf(ImportField.TIME,ImportField.DAY_OF_WEEK)) = RichMeetingDraft(MeetingKind.UNSCHEDULED,weeks = setOf(17),missingFields = missing)
    @Test fun `practice row retains declarations after dedicated keep unscheduled review`() {
        val s = session(unplanned())
        assertTrue(RichDraftConverter.convert(s) is ImportReviewResult.Blocked)
        val decision = s.keepUnscheduled(p)
        val result = RichDraftConverter.convert(s,ImportReviewDecisions(unscheduled = listOf(decision)))
        assertTrue(result.toString(),result is ImportReviewResult.Ready)
        val reviewed = (result as ImportReviewResult.Ready).reviewed
        val m = reviewed.courses.single().meetings.single()
        assertEquals(setOf(17),m.declaredWeeks);assertTrue(m.weeks.isEmpty());assertTrue(m.dates.isEmpty());assertEquals(0,m.day)
        assertEquals(ReviewedTimeSpec.Unscheduled,m.time)
        assertFalse(reviewed.requiresCalendarForOccurrencePlanning)
        assertEquals(setOf(ImportField.TIME,ImportField.DAY_OF_WEEK),reviewed.source.format.draft!!.courses.single().meetings.single().missingFields)
    }
    @Test fun `cleared missing flags do not bypass actual unscheduled review requirement`() {
        val s = session(unplanned(emptySet()))
        assertTrue(s.requirements.any { it.kind == ReviewRequirementKind.KEEP_UNSCHEDULED })
        assertTrue(RichDraftConverter.convert(s) is ImportReviewResult.Blocked)
        assertTrue(RichDraftConverter.convert(s,ImportReviewDecisions(unscheduled = listOf(s.keepUnscheduled(p)))) is ImportReviewResult.Ready)
    }
    @Test fun `keep unscheduled cannot discard explicit dates or invalid time sections`() {
        val cases = listOf(unplanned().copy(dates = setOf(LocalDate.parse("2026-11-01"))),unplanned().copy(customTime = DraftTimeRange(LocalTime.parse("09:00"),LocalTime.parse("08:00"))),unplanned().copy(sections = setOf(0)))
        for(m in cases) {
            val s = session(m)
            assertTrue(RichDraftConverter.convert(s,ImportReviewDecisions(unscheduled = listOf(s.keepUnscheduled(p)))) is ImportReviewResult.Blocked)
        }
    }
    @Test fun `changed weeks or missing fields reject old keep unscheduled decision`() {
        val first = session(unplanned())
        val decision = first.keepUnscheduled(p)
        val changed = session(unplanned().copy(weeks = setOf(18)))
        val result = RichDraftConverter.convert(changed,ImportReviewDecisions(unscheduled = listOf(decision))) as ImportReviewResult.Blocked
        assertTrue(result.problems.any { it.code == "STALE_REVIEW_BINDING" })
    }
    @Test fun `keep unscheduled cannot authorize a recurring meeting`() {
        val s = session(RichMeetingDraft(MeetingKind.RECURRING,1,setOf(1),setOf(1)))
        assertThrows(IllegalArgumentException::class.java) { s.keepUnscheduled(p) }
    }

    @Test fun `practice missing optional metadata needs explicit retain unknown choices`() {
        val s = session(unplanned(setOf(ImportField.TIME,ImportField.DAY_OF_WEEK,ImportField.TEACHER,ImportField.ROOM)))
        val keep = s.keepUnscheduled(p)
        assertTrue(RichDraftConverter.convert(s,ImportReviewDecisions(unscheduled = listOf(keep))) is ImportReviewResult.Blocked)
        val metadata = s.retainUnknownOptionalMetadata(p,setOf(ImportField.TEACHER,ImportField.ROOM))
        val result = RichDraftConverter.convert(s,ImportReviewDecisions(unscheduled = listOf(keep),optionalMetadata = listOf(metadata)))
        assertTrue(result.toString(),result is ImportReviewResult.Ready)
        val out = (result as ImportReviewResult.Ready).reviewed
        val meeting = out.courses.single().meetings.single()
        assertNull(meeting.teacher);assertNull(meeting.room)
        assertEquals(setOf(ImportField.TEACHER,ImportField.ROOM),meeting.retainedUnknownFields)
        assertEquals(4,out.source.format.draft!!.courses.single().meetings.single().missingFields.size)
    }
    @Test fun `explicit empty metadata stays distinct from retained unknown null`() {
        val s = session(unplanned(setOf(ImportField.TIME,ImportField.DAY_OF_WEEK,ImportField.TEACHER,ImportField.ROOM)).copy(teacher = ""))
        val d = ImportReviewDecisions(unscheduled = listOf(s.keepUnscheduled(p)),optionalMetadata = listOf(s.retainUnknownOptionalMetadata(p,setOf(ImportField.ROOM))),evidence = s.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { s.reviewEvidence(it.fingerprint) })
        val result = RichDraftConverter.convert(s,d)
        assertTrue(result.toString(),result is ImportReviewResult.Ready)
        val m = (result as ImportReviewResult.Ready).reviewed.courses.single().meetings.single()
        assertEquals("",m.teacher);assertNull(m.room);assertEquals(setOf(ImportField.ROOM),m.retainedUnknownFields)
    }
    @Test fun `unknown optional review cannot approve a missing course name`() {
        val s = session(unplanned(setOf(ImportField.TIME,ImportField.DAY_OF_WEEK,ImportField.COURSE_NAME)))
        assertThrows(IllegalArgumentException::class.java) { s.retainUnknownOptionalMetadata(p,setOf(ImportField.COURSE_NAME)) }
    }
    @Test fun `unknown weeks can stay unplanned without inventing week one`() {
        val s = session(unplanned(setOf(ImportField.TIME,ImportField.DAY_OF_WEEK,ImportField.WEEKS)).copy(weeks = emptySet()))
        val result = RichDraftConverter.convert(s,ImportReviewDecisions(unscheduled = listOf(s.keepUnscheduled(p))))
        assertTrue(result.toString(),result is ImportReviewResult.Ready)
        val m = (result as ImportReviewResult.Ready).reviewed.courses.single().meetings.single()
        assertTrue(m.weeks.isEmpty());assertTrue(m.declaredWeeks.isEmpty());assertTrue(m.dates.isEmpty())
    }
}
