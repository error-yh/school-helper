package com.lingion.sleepy.domain

import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.model.defaultPeriods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class TimetableRulesTest {
    private val settings = SemesterSettings(startDate = date("2026-09-01"), totalWeeks = 10)

    @Test
    fun mondayNormalizationUsesTheContainingWeekEvenAcrossTheYear() {
        assertEquals(date("2026-08-31"), TimetableRules.mondayOf(date("2026-09-01")))
        assertEquals(date("2026-08-31"), TimetableRules.mondayOf(date("2026-09-06")))
        assertEquals(date("2025-12-29"), TimetableRules.mondayOf(date("2026-01-01")))
    }

    @Test
    fun teachingWeekIncludesFirstMondayAndLastSundayButNothingOutside() {
        assertNull(TimetableRules.teachingWeek(settings, date("2026-08-30")))
        assertEquals(1, TimetableRules.teachingWeek(settings, date("2026-08-31")))
        assertEquals(1, TimetableRules.teachingWeek(settings, date("2026-09-06")))
        assertEquals(10, TimetableRules.teachingWeek(settings, date("2026-11-08")))
        assertNull(TimetableRules.teachingWeek(settings, date("2026-11-09")))
    }

    @Test
    fun unsetSemesterProducesNoTeachingWeekOrDate() {
        assertNull(TimetableRules.teachingWeek(SemesterSettings(), date("2026-10-23")))
        assertNull(TimetableRules.dateOfWeek(SemesterSettings(), 1, 1))
    }

    @Test
    fun datesAndTeachingWeeksRoundTripAcrossYearBoundary() {
        val crossYear = SemesterSettings(startDate = date("2025-12-31"), totalWeeks = 3)
        assertEquals(date("2025-12-29"), TimetableRules.dateOfWeek(crossYear, 1, 1))
        assertEquals(date("2026-01-18"), TimetableRules.dateOfWeek(crossYear, 3, 7))
        assertEquals(3, TimetableRules.teachingWeek(crossYear, date("2026-01-18")))
        assertNull(TimetableRules.teachingWeek(crossYear, date("2026-01-19")))
    }

    @Test
    fun dateOfWeekRejectsInvalidWeekAndDayRatherThanClamping() {
        for ((week, day) in listOf(0 to 1, 11 to 1, 1 to 0, 1 to 8)) {
            assertThrows(IllegalArgumentException::class.java) {
                TimetableRules.dateOfWeek(settings, week, day)
            }
        }
    }

    @Test
    fun todayUsesTheInjectedClockZone() {
        val instant = Instant.parse("2026-10-23T16:30:00Z")
        assertEquals(date("2026-10-24"), TimetableRules.today(Clock.fixed(instant, ZoneId.of("Asia/Shanghai"))))
        assertEquals(date("2026-10-23"), TimetableRules.today(Clock.fixed(instant, ZoneId.of("UTC"))))
    }

    @Test
    fun sparseWeeksAreNeverFilledBetweenMinimumAndMaximum() {
        val course = course(1, listOf(meeting(weeks = setOf(1, 3, 5, 8, 10))))
        val snapshot = snapshot(listOf(course))
        val included = (1..10).filter { week ->
            TimetableRules.occurrencesOn(snapshot, date("2026-08-31").plusWeeks((week - 1).toLong())).isNotEmpty()
        }
        assertEquals(listOf(1, 3, 5, 8, 10), included)
    }

    @Test
    fun inertLegacyMeetingStaysStoredButHasNoOccurrenceAndCannotBeSavedAsANewDraft() {
        val inert = meeting(weeks = emptySet())
        val snapshot = snapshot(listOf(course(15, listOf(inert))))
        assertEquals(1, snapshot.courses.size)
        assertEquals(emptySet<Int>(), snapshot.courses.single().meetings.single().weeks)
        assertTrue(TimetableRules.occurrencesOn(snapshot, date("2026-08-31")).isEmpty())
        assertFalse(TimetableRules.validateCourse(CourseDraft("惰性旧课程", meetings = listOf(inert)), settings, defaultPeriods()).isEmpty())
    }

    @Test
    fun expandedOddAndEvenWeeksRemainSeparateOccurrences() {
        val odd = (1..10).filter { it % 2 == 1 }.toSet()
        val even = (1..10).filter { it % 2 == 0 }.toSet()
        val snapshot = snapshot(listOf(course(1, listOf(meeting(weeks = odd))), course(2, listOf(meeting(weeks = even)))))
        assertEquals(listOf(1L), TimetableRules.occurrencesOn(snapshot, date("2026-08-31")).map { it.course.id })
        assertEquals(listOf(2L), TimetableRules.occurrencesOn(snapshot, date("2026-09-07")).map { it.course.id })
    }

    @Test
    fun individualSectionIntervalsKeepNoncontiguousHolesAndRealBreaks() {
        assertEquals(listOf(range("08:00", "08:45"), range("10:00", "10:45")),
            TimetableRules.periodIntervals(meeting(sections = setOf(3, 1)), defaultPeriods()))
        assertEquals(listOf(range("08:00", "08:45"), range("08:55", "09:40")),
            TimetableRules.periodIntervals(meeting(sections = setOf(1, 2)), defaultPeriods()))
    }

    @Test
    fun customTimeIsIndependentOfSectionIndicesAndPeriodTable() {
        val custom = range("07:10", "07:35")
        val meeting = meeting(sections = setOf(1500), customTime = custom)
        assertEquals(listOf(custom), TimetableRules.periodIntervals(meeting, emptyList()))
    }

    @Test
    fun occurrencesRetainUnknownSectionsInsteadOfDroppingTheCourse() {
        val meeting = meeting(sections = setOf(1, 99))
        val result = TimetableRules.occurrencesOn(snapshot(listOf(course(1, listOf(meeting)))), date("2026-08-31"))
        assertEquals(1, result.size)
        assertEquals(setOf(99), result.single().missingSections)
        assertEquals(listOf(range("08:00", "08:45")), result.single().timeRanges)
    }

    @Test
    fun occurrenceWithOnlyMissingSectionsRemainsVisibleAfterTimedOccurrences() {
        val result = TimetableRules.occurrencesOn(snapshot(listOf(
            course(1, listOf(meeting(sections = setOf(99)))),
            course(2, listOf(meeting())),
        )), date("2026-08-31"))
        assertEquals(listOf(2L, 1L), result.map { it.course.id })
        assertEquals(emptyList<TimeRange>(), result.last().timeRanges)
        assertEquals(setOf(99), result.last().missingSections)
    }

    @Test
    fun occurrencesSortByActualTimeThenSectionThenStableId() {
        val courses = listOf(
            course(30, listOf(meeting(sections = setOf(3)))),
            course(20, listOf(meeting())),
            course(10, listOf(meeting())),
            course(40, listOf(meeting(sections = emptySet(), customTime = range("07:30", "07:50")))),
        )
        assertEquals(listOf(40L, 10L, 20L, 30L),
            TimetableRules.occurrencesOn(snapshot(courses), date("2026-08-31")).map { it.course.id })
    }

    @Test
    fun multipleMeetingDaysBelongToOneStableCourseAndUseEffectiveOverrides() {
        val meetings = listOf(meeting(), meeting(day = 3).copy(teacher = "实验教师", room = "实验室"))
        val snapshot = snapshot(listOf(course(17, meetings)))
        val monday = TimetableRules.occurrencesOn(snapshot, date("2026-08-31")).single()
        val wednesday = TimetableRules.occurrencesOn(snapshot, date("2026-09-02")).single()
        assertEquals(17L, monday.course.id)
        assertEquals(17L, wednesday.course.id)
        assertEquals("默认教师", monday.effectiveTeacher)
        assertEquals("默认教室", monday.effectiveRoom)
        assertEquals("实验教师", wednesday.effectiveTeacher)
        assertEquals("实验室", wednesday.effectiveRoom)
    }

    @Test
    fun noCoursesWrongDayUnsetTermAndOutOfTermHaveNoOccurrences() {
        assertTrue(TimetableRules.occurrencesOn(snapshot(emptyList()), date("2026-08-31")).isEmpty())
        val snapshot = snapshot(listOf(course(1, listOf(meeting()))))
        assertTrue(TimetableRules.occurrencesOn(snapshot, date("2026-09-01")).isEmpty())
        assertTrue(TimetableRules.occurrencesOn(snapshot, date("2026-08-24")).isEmpty())
        assertTrue(TimetableRules.occurrencesOn(snapshot.copy(settings = SemesterSettings()), date("2026-08-31")).isEmpty())
    }

    @Test
    fun parserSupportsExactSetsChineseCommasAndInclusiveDashRanges() {
        assertEquals(setOf(1, 3, 5, 8, 9, 10), parseNumberSet("1, 3，5,8-10", 20, "周次"))
        assertEquals(setOf(1, 2, 3), parseNumberSet("1-3,2,3", 20, "节次"))
    }

    @Test
    fun parserRejectsEmptyMalformedReversedAndOutOfRangeValuesWithTheFieldLabel() {
        for (input in listOf("", " ", "0", "-1", "21", "3-1", "1-", "1,,2", "1,", "单周", "1.5", "1-2-3", "999999999999", "1-999999999999")) {
            val error = assertThrows("input=$input", IllegalArgumentException::class.java) {
                parseNumberSet(input, 20, "周次")
            }
            assertTrue(error.message.orEmpty().contains("周次"))
        }
    }

    @Test
    fun parserRejectsMoreThanTenThousandExpandedValuesWithTheFieldLabel() {
        for (input in listOf("1-10001", "1-5000,5001-10001")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                parseNumberSet(input, Int.MAX_VALUE, "节次")
            }
            assertTrue(error.message.orEmpty().contains("节次"))
        }
    }

    @Test
    fun parserLimitCountsDistinctValuesWithoutLimitingSectionNumbers() {
        assertEquals(setOf(Int.MAX_VALUE), parseNumberSet("2147483647", Int.MAX_VALUE, "节次"))
        assertEquals(setOf(Int.MAX_VALUE - 1, Int.MAX_VALUE), parseNumberSet("2147483646-2147483647", Int.MAX_VALUE, "节次"))
        val overlapping = parseNumberSet("1-10000,1-10000,5000-10000", Int.MAX_VALUE, "节次")
        assertEquals(10000, overlapping.size)
        assertEquals(1, overlapping.minOrNull())
        assertEquals(10000, overlapping.maxOrNull())
    }

    @Test(timeout = 1000)
    fun parserRejectsHugeAndHighEndRangesBeforeEnumeration() {
        for (input in listOf("1-2147483647", "2147473647-2147483647")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                parseNumberSet(input, Int.MAX_VALUE, "节次")
            }
            assertTrue(error.message.orEmpty().contains("节次"))
        }
    }

    @Test
    fun formatterProducesSortedCompactRunsWithoutFillingGaps() {
        assertEquals("1,3,5,8-10", formatNumberSet(setOf(10, 1, 9, 3, 8, 5)))
        assertEquals("", formatNumberSet(emptySet()))
        assertEquals(setOf(1, 3, 5, 8, 9, 10), parseNumberSet(formatNumberSet(setOf(10, 1, 9, 3, 8, 5)), 20, "周次"))
    }

    @Test
    fun saveValidationReportsBlankNameMissingMeetingsAndOutOfTermWeeks() {
        assertFalse(TimetableRules.validateCourse(CourseDraft(" ", meetings = emptyList()), settings, defaultPeriods()).isEmpty())
        val invalid = CourseDraft("有效课程", meetings = listOf(meeting(weeks = setOf(1, 11))))
        assertFalse(TimetableRules.validateCourse(invalid, settings, defaultPeriods()).isEmpty())
        val valid = invalid.copy(meetings = listOf(meeting(weeks = setOf(1, 3, 5, 8, 10))))
        assertEquals(emptyList<String>(), TimetableRules.validateCourse(valid, settings, defaultPeriods()))
    }

    @Test
    fun missingSectionIsASaveErrorButCustomTimeNeedsNoPeriod() {
        val unknown = CourseDraft("课程", meetings = listOf(meeting(sections = setOf(99))))
        assertFalse(TimetableRules.validateCourse(unknown, settings, defaultPeriods()).isEmpty())
        val custom = unknown.copy(meetings = listOf(meeting(sections = emptySet(), customTime = range("07:00", "07:30"))))
        assertTrue(TimetableRules.validateCourse(custom, settings, emptyList()).isEmpty())
    }

    @Test
    fun settingsValidationBoundsWeeksAndRequiresNameButAllowsUnsetStart() {
        assertTrue(TimetableRules.validateSettings(SemesterSettings()).isEmpty())
        assertFalse(TimetableRules.validateSettings(settings.copy(name = " ")).isEmpty())
        assertFalse(TimetableRules.validateSettings(settings.copy(totalWeeks = 0)).isEmpty())
        assertFalse(TimetableRules.validateSettings(settings.copy(totalWeeks = 1001)).isEmpty())
    }

    @Test
    fun settingsValidationRejectsDatesWhoseCompleteSemesterCannotBeRepresented() {
        assertFalse(TimetableRules.validateSettings(settings.copy(startDate = LocalDate.MAX, totalWeeks = 1)).isEmpty())
        assertFalse(TimetableRules.validateSettings(settings.copy(startDate = LocalDate.MAX.minusDays(7), totalWeeks = 1000)).isEmpty())
    }

    @Test
    fun settingsValidationAcceptsCompleteWeeksNearBothDateLimits() {
        val earliestMonday = LocalDate.MIN.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
        val latestSunday = LocalDate.MAX.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        for (startDate in listOf(earliestMonday, latestSunday)) {
            val boundary = settings.copy(startDate = startDate, totalWeeks = 1)
            assertTrue(TimetableRules.validateSettings(boundary).isEmpty())
            assertEquals(DayOfWeek.MONDAY, TimetableRules.dateOfWeek(boundary, 1, 1)?.dayOfWeek)
            assertEquals(DayOfWeek.SUNDAY, TimetableRules.dateOfWeek(boundary, 1, 7)?.dayOfWeek)
        }
    }

    @Test
    fun periodValidationRejectsDuplicateNumbersAndOverlappingPeriods() {
        val first = Period(1, time("08:00"), time("08:45"))
        assertFalse(TimetableRules.validatePeriods(listOf(first, first.copy(start = time("09:00"), end = time("09:45")))).isEmpty())
        assertFalse(TimetableRules.validatePeriods(listOf(first, Period(2, time("08:30"), time("09:00")))).isEmpty())
        assertTrue(TimetableRules.validatePeriods(defaultPeriods()).isEmpty())
    }

    private fun snapshot(courses: List<ScheduledCourse>) = TimetableSnapshot(settings, defaultPeriods(), courses)
    private fun course(id: Long, meetings: List<CourseMeeting>) =
        ScheduledCourse(id, "同名课程", "默认教师", "默认教室", 0xFF6750A4, meetings)
    private fun meeting(day: Int = 1, weeks: Set<Int> = setOf(1), sections: Set<Int> = setOf(1), customTime: TimeRange? = null) =
        CourseMeeting(day = day, weeks = weeks, sections = sections, customTime = customTime)
    private fun date(value: String) = LocalDate.parse(value)
    private fun time(value: String) = LocalTime.parse(value)
    private fun range(start: String, end: String) = TimeRange(time(start), time(end))
}
