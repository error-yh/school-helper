package com.lingion.sleepy.ui

import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.domain.TimetableRules
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class CourseEditingTest {
    private val date = LocalDate.parse("2026-09-07")
    private val source = ScheduleProvenance(rawText = "Synthetic source", page = 1)

    @Test fun recurringEditorPreservesProjectExceptionsAndSource() {
        val meeting = CourseMeeting(7, 1, setOf(1, 3), setOf(1, 3),
            experimentProject = "Project", group = "Group", isExperiment = true,
            provenance = listOf(source), exceptions = listOf(MeetingException(
                kind = ExceptionKind.CANCEL, originalDate = date)), declaredWeeks = setOf(1, 3))
        assertEquals(meeting, MeetingForm(meeting, 20).asMeeting(20, 1))
    }

    @Test fun dateEditorDoesNotTurnActualDatesIntoRecurringWeeks() {
        val meeting = CourseMeeting(8, 0, emptySet(), setOf(2), kind = MeetingKind.DATE_SPECIFIC,
            dates = setOf(date, date.plusWeeks(2)), declaredWeeks = setOf(25), provenance = listOf(source))
        assertEquals(meeting, MeetingForm(meeting, 20).asMeeting(20, 1))
    }

    @Test fun unscheduledEditorKeepsUnknownTimeInert() {
        val meeting = CourseMeeting(9, 0, emptySet(), emptySet(), kind = MeetingKind.UNSCHEDULED,
            experimentProject = "Practice", provenance = listOf(source))
        assertEquals(meeting, MeetingForm(meeting, 20).asMeeting(20, 1))
    }

    @Test fun restoredRawEditorReattachesFullOriginalBeforeSaving() {
        val meeting = CourseMeeting(17, 0, emptySet(), setOf(2), kind = MeetingKind.DATE_SPECIFIC,
            dates = setOf(date), provenance = listOf(source), exceptions = listOf(MeetingException(
                kind = ExceptionKind.EXTRA, targetDate = date.plusDays(1), sections = setOf(3))))
        val restored = MeetingForm(null, 20).apply {
            id = meeting.id; day = "0"; kind = MeetingKind.DATE_SPECIFIC
            dates = date.toString(); sections = "2"
        }
        assertEquals(meeting, restored.asMeeting(20, 1, meeting))
    }

    @Test fun editedDatesCrossYearAndRejectImpossibleCalendarDay() {
        assertEquals(setOf(LocalDate.parse("2026-12-31"), LocalDate.parse("2027-01-04")),
            parseCourseDates("2026-12-31，2027-01-04"))
        try { parseCourseDates("2027-02-29"); fail("Invalid calendar day must not be normalized") }
        catch (_: java.time.DateTimeException) { }
    }

    @Test fun weekGridUsesRescheduledSectionsInsteadOfBaseSections() {
        val meeting = CourseMeeting(day = 1, weeks = setOf(1), sections = setOf(1),
            exceptions = listOf(MeetingException(kind = ExceptionKind.RESCHEDULE,
                originalDate = date, targetDate = date.plusDays(1), sections = setOf(3))))
        val course = ScheduledCourse(1, "Synthetic", "", "", 0, listOf(meeting))
        val snapshot = TimetableSnapshot(settings = SemesterSettings(startDate = date), courses = listOf(course))
        val occurrence = TimetableRules.occurrencesOn(snapshot, date.plusDays(1)).single()
        assertEquals(listOf(3), occurrenceGridIntervals(occurrence, snapshot.periods).map { it.second })
        assertEquals(occurrence.timeRanges, occurrenceGridIntervals(occurrence, snapshot.periods).map { it.first })
    }

    @Test fun weekGridUsesExtraCustomTimeInsteadOfBaseSections() {
        val time = TimeRange(LocalTime.of(12, 0), LocalTime.of(12, 45))
        val meeting = CourseMeeting(day = 1, weeks = setOf(1), sections = setOf(1),
            exceptions = listOf(MeetingException(kind = ExceptionKind.EXTRA,
                targetDate = date.plusDays(2), customTime = time)))
        val course = ScheduledCourse(1, "Synthetic", "", "", 0, listOf(meeting))
        val snapshot = TimetableSnapshot(settings = SemesterSettings(startDate = date), courses = listOf(course))
        val occurrence = TimetableRules.occurrencesOn(snapshot, date.plusDays(2)).single()
        assertEquals(listOf(time to null), occurrenceGridIntervals(occurrence, snapshot.periods))
    }
}
