package com.lingion.sleepy.domain

import com.lingion.sleepy.domain.model.*
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class CalendarOccurrenceTest {
    private val original = LocalDate.parse("2026-09-07")
    private val outside = LocalDate.parse("2027-02-03")
    private fun snapshot(meeting: CourseMeeting, start: LocalDate? = original) = TimetableSnapshot(
        settings = SemesterSettings(startDate = start, totalWeeks = 3),
        courses = listOf(ScheduledCourse(1, "Synthetic", "Base", "Room", 0, listOf(meeting))),
    )
    private fun recurring() = CourseMeeting(8, 1, setOf(1, 2, 3), setOf(1, 3))
    private fun dateMeeting() = CourseMeeting(8, 0, emptySet(), setOf(1), kind = MeetingKind.DATE_SPECIFIC, dates = setOf(outside), declaredWeeks = setOf(6))

    @Test fun exactDatesWorkWithoutCalendarAndOutsideTermExactlyOnce() {
        for (start in listOf(null, original)) {
            val state = snapshot(dateMeeting(), start)
            val occurrence = TimetableRules.occurrencesOn(state, outside).single()
            assertNull(occurrence.week)
            assertEquals(setOf(6), occurrence.meeting.declaredWeeks)
            assertEquals(outside, occurrence.date)
            assertTrue(TimetableRules.occurrencesOn(state, outside.plusWeeks(1)).isEmpty())
            assertEquals(1, TimetableRules.occurrencesBetween(state, outside.minusDays(1), outside.plusDays(2)).size)
        }
    }
    @Test fun unscheduledHasNoInventedTimeOrWeekday() {
        val meeting = CourseMeeting(0, 0, emptySet(), emptySet(), kind = MeetingKind.UNSCHEDULED)
        assertTrue(TimetableRules.validateCourse(snapshot(meeting).courses.single().asDraft(), SemesterSettings(), defaultPeriods()).isEmpty())
        assertTrue(TimetableRules.occurrencesOn(snapshot(meeting), original).isEmpty())
    }
    @Test fun cancellationAndRescheduleOnlyChangeTheirOriginalAndKeepIdentity() {
        val meeting = recurring().copy(exceptions = listOf(
            MeetingException(1, ExceptionKind.CANCEL, originalDate = original),
            MeetingException(2, ExceptionKind.RESCHEDULE, originalDate = original.plusWeeks(1), targetDate = outside,
                sections = setOf(2), teacher = "Override", room = "", experimentProject = "Project", group = "G"),
        ))
        val state = snapshot(meeting)
        assertTrue(TimetableRules.occurrencesOn(state, original).isEmpty())
        assertTrue(TimetableRules.occurrencesOn(state, original.plusWeeks(1)).isEmpty())
        assertEquals(1, TimetableRules.occurrencesOn(state, original.plusWeeks(2)).size)
        val moved = TimetableRules.occurrencesOn(state, outside).single()
        assertEquals(8L, moved.meeting.id)
        assertEquals("Override", moved.effectiveTeacher)
        assertEquals("", moved.effectiveRoom)
        assertEquals("Project", moved.effectiveProject)
        assertEquals("G", moved.effectiveGroup)
        assertEquals(listOf(TimeRange(LocalTime.parse("08:55"), LocalTime.parse("09:40"))), moved.timeRanges)
    }
    @Test fun extraWorksWithoutCalendarAndSectionOverrideReplacesCustomTime() {
        val meeting = recurring().copy(customTime = TimeRange(LocalTime.NOON, LocalTime.of(13, 0)), exceptions = listOf(
            MeetingException(kind = ExceptionKind.EXTRA, targetDate = outside, sections = setOf(1, 3)),
        ))
        val occurrence = TimetableRules.occurrencesOn(snapshot(meeting, null), outside).single()
        assertNull(occurrence.week)
        assertEquals(2, occurrence.timeRanges.size)
        assertEquals(LocalTime.of(8, 0), occurrence.timeRanges.first().start)
    }
    @Test fun actualDateConflictsRespectBreaksAndAdjacentEndpoints() {
        val first = TimetableRules.occurrencesOn(snapshot(dateMeeting()), outside).single()
        val adjacent = first.copy(timeRanges = listOf(TimeRange(LocalTime.of(8,45), LocalTime.of(8,55))))
        assertTrue(TimetableRules.conflicts(first, adjacent).isEmpty())
        val overlap = first.copy(timeRanges = listOf(TimeRange(LocalTime.of(8,44), LocalTime.of(8,56))))
        assertEquals(TimeRange(LocalTime.of(8,44), LocalTime.of(8,45)), TimetableRules.conflicts(first, overlap).single().overlap)
        assertTrue(TimetableRules.conflicts(first, overlap.copy(date = outside.plusDays(1))).isEmpty())
    }
    @Test fun ambiguousAndNonexistentOriginalExceptionsAreRejected() {
        val cancel = MeetingException(kind = ExceptionKind.CANCEL, originalDate = original)
        for (exceptions in listOf(listOf(cancel, cancel), listOf(cancel.copy(originalDate = original.plusDays(1))))) {
            val state = snapshot(recurring().copy(exceptions = exceptions))
            assertTrue(TimetableRules.validateCourse(state.courses.single().asDraft(), state.settings, state.periods).isNotEmpty())
        }
    }
    @Test fun metadataSurvivesAsDraftAndLegacyPositionalArguments() {
        val source = ScheduleProvenance(rawText = "Synthetic", page = 1, bounds = SourceBounds(1f,2f,3f,4f))
        val legacy = CourseMeeting(3, 1, setOf(1), setOf(1), null, "Teacher", "Room")
        val course = ScheduledCourse(1, "Synthetic", "", "", 0, listOf(legacy), "C", "K", listOf(source))
        assertEquals(MeetingKind.RECURRING, legacy.kind)
        assertEquals("C", course.asDraft().sourceCourseCode)
        assertEquals(listOf(source), course.asDraft().provenance)
        assertThrows(IllegalArgumentException::class.java) { SourceBounds(Float.NaN,0f,1f,1f) }
        assertThrows(IllegalArgumentException::class.java) { CourseMeeting(0, 0, setOf(1), setOf(1)) }
    }
    @Test fun extraDuplicatesUseActualIntervalsNotUnusedSectionMetadata() {
        val time = TimeRange(LocalTime.NOON,LocalTime.of(13,0))
        val extra = MeetingException(kind=ExceptionKind.EXTRA,targetDate=outside,customTime=time,sections=setOf(1))
        val state = snapshot(recurring().copy(exceptions=listOf(extra,extra.copy(sections=setOf(2)))))
        assertTrue(TimetableRules.validateCourse(state.courses.single().asDraft(),state.settings,state.periods).isNotEmpty())
    }
    @Test fun contradictoryStoredKindsFailClosedInsteadOfHidingArrangements() {
        assertThrows(IllegalArgumentException::class.java) { recurring().copy(dates=setOf(outside)) }
        assertThrows(IllegalArgumentException::class.java) { dateMeeting().copy(weeks=setOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { dateMeeting().copy(kind=MeetingKind.UNSCHEDULED) }
        assertThrows(IllegalArgumentException::class.java) { dateMeeting().copy(dates=emptySet()) }
    }
    @Test fun nonGeometricSourceLocatorRetainsItsMeaningWithoutInventedPdfCoordinates() {
        val source=ScheduleProvenance(rawText="Synthetic cell",sourceLocator="Sheet1!A3")
        assertNull(source.page); assertNull(source.bounds)
        assertEquals("Sheet1!A3",source.sourceLocator)
        assertThrows(IllegalArgumentException::class.java) { source.copy(sourceLocator="x".repeat(4097)) }
    }
    @Test fun exceptionReadValidationKeepsLegacyBasesButRejectsInvalidRelationships() {
        val legacy = recurring().copy(weeks = emptySet(), sections = setOf(999))
        val state = snapshot(legacy, null)
        assertTrue(TimetableRules.validateExceptions(state.courses.single().asDraft(), state.settings, emptyList()).isEmpty())
        val invalidChanges = listOf(
            listOf(MeetingException(kind = ExceptionKind.RESCHEDULE, originalDate = original.plusDays(1), targetDate = outside)),
            listOf(MeetingException(kind = ExceptionKind.EXTRA, targetDate = outside, sections = setOf(999))),
            List(2) { MeetingException(kind = ExceptionKind.EXTRA, targetDate = outside) },
        )
        invalidChanges.forEach { changes ->
            val validBase = snapshot(recurring().copy(exceptions = changes))
            assertTrue(TimetableRules.validateExceptions(validBase.courses.single().asDraft(), validBase.settings, validBase.periods).isNotEmpty())
        }
    }
}
