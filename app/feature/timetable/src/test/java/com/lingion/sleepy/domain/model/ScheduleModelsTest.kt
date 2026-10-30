package com.lingion.sleepy.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalTime

class ScheduleModelsTest {
    @Test
    fun defaultSnapshotHasUnsetSemesterAndTheTwelveUpstreamPeriods() {
        val snapshot = TimetableSnapshot()
        assertEquals(SemesterSettings(1, "我的学期", null, 20), snapshot.settings)
        assertEquals(0L, snapshot.revision)
        assertEquals(emptyList<ScheduledCourse>(), snapshot.courses)
        assertEquals(
            listOf("08:00-08:45", "08:55-09:40", "10:00-10:45", "10:55-11:40",
                "14:00-14:45", "14:55-15:40", "16:00-16:45", "16:55-17:40",
                "19:00-19:45", "19:55-20:40", "20:50-21:35", "21:45-22:30"),
            snapshot.periods.map { "${it.start}-${it.end}" },
        )
        assertEquals((1..12).toList(), snapshot.periods.map { it.number })
    }

    @Test
    fun sparseWeeksAndSectionsRemainExactSets() {
        val meeting = meeting(weeks = setOf(1, 3, 5, 8, 10), sections = setOf(1, 3))
        assertEquals(setOf(1, 3, 5, 8, 10), meeting.weeks)
        assertEquals(setOf(1, 3), meeting.sections)
    }

    @Test
    fun meetingRequiresAWeekdayAndPositiveWeeksButCanRetainAnInertLegacyRow() {
        assertThrows(IllegalArgumentException::class.java) { meeting(day = 0) }
        assertThrows(IllegalArgumentException::class.java) { meeting(day = 8) }
        assertEquals(emptySet<Int>(), meeting(weeks = emptySet()).weeks)
        assertThrows(IllegalArgumentException::class.java) { meeting(weeks = setOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { meeting(weeks = setOf(-1, 3)) }
    }

    @Test
    fun sectionBasedMeetingRequiresPositiveSectionsWithoutAnArtificialMaximum() {
        assertThrows(IllegalArgumentException::class.java) { meeting(sections = emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { meeting(sections = setOf(0)) }
        assertThrows(IllegalArgumentException::class.java) { meeting(sections = setOf(-2)) }
        assertEquals(setOf(1500), meeting(sections = setOf(1500)).sections)
    }

    @Test
    fun customTimeDoesNotRequireSectionIndices() {
        val custom = TimeRange(time("07:15"), time("07:50"))
        assertEquals(custom, meeting(sections = emptySet(), customTime = custom).customTime)
    }

    @Test
    fun customTimeStillRejectsNonpositiveReferenceSections() {
        val custom = TimeRange(time("07:15"), time("07:50"))
        assertThrows(IllegalArgumentException::class.java) { meeting(sections = setOf(0), customTime = custom) }
        assertThrows(IllegalArgumentException::class.java) { meeting(sections = setOf(-1), customTime = custom) }
        assertEquals(setOf(1500), meeting(sections = setOf(1500), customTime = custom).sections)
    }

    @Test
    fun timeRangesAreStrictlyIncreasingWithinOneDay() {
        assertThrows(IllegalArgumentException::class.java) { TimeRange(time("08:00"), time("08:00")) }
        assertThrows(IllegalArgumentException::class.java) { TimeRange(time("23:30"), time("00:30")) }
        TimeRange(time("00:00"), time("23:59"))
    }

    @Test
    fun periodsRequirePositiveNumbersAndIncreasingTimes() {
        assertThrows(IllegalArgumentException::class.java) { Period(0, time("08:00"), time("08:45")) }
        assertThrows(IllegalArgumentException::class.java) { Period(1, time("08:45"), time("08:00")) }
        assertThrows(IllegalArgumentException::class.java) { Period(1, time("08:00"), time("08:00")) }
    }

    @Test
    fun periodAndCustomTimesRejectSecondsRatherThanSilentlyTruncating() {
        assertThrows(IllegalArgumentException::class.java) { Period(1, time("08:00:01"), time("08:45")) }
        assertThrows(IllegalArgumentException::class.java) { TimeRange(time("08:00"), time("08:45:00.000000001")) }
    }

    @Test
    fun convertingToDraftPreservesAllMeetingsAndPerMeetingOverrides() {
        val meetings = listOf(meeting(), meeting(day = 3).copy(id = 4, teacher = "实验教师", room = "实验室"))
        val scheduled = ScheduledCourse(23, "同名课程", "教师", "教室", 0xFF112233, meetings)
        assertEquals(CourseDraft("同名课程", "教师", "教室", 0xFF112233, meetings), scheduled.asDraft())
    }

    @Test
    fun sameCourseNameNeverCollapsesStableIdentity() {
        val first = ScheduledCourse(10, "同名课程", "", "", 0xFF6750A4, listOf(meeting()))
        val second = first.copy(id = 11)
        assertEquals(2, setOf(first, second).size)
        assertEquals(listOf(10L, 11L), listOf(first, second).map { it.id })
    }

    private fun time(value: String) = LocalTime.parse(value)
    private fun meeting(
        day: Int = 1,
        weeks: Set<Int> = setOf(1),
        sections: Set<Int> = setOf(1),
        customTime: TimeRange? = null,
    ) = CourseMeeting(day = day, weeks = weeks, sections = sections, customTime = customTime)
}
