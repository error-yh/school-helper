package com.aggapp.app.widget

import com.lingion.sleepy.api.TimetableOccurrenceKey
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.ExceptionKind
import com.lingion.sleepy.domain.model.MeetingException
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimetableSnapshot
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetScheduleTest {
    private val date = LocalDate.parse("2026-11-10")
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun at(time: String, on: LocalDate = date): Instant =
        on.atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun snapshot(vararg ranges: Pair<String, String>): TimetableSnapshot =
        TimetableSnapshot(
            settings = SemesterSettings(startDate = date),
            periods = ranges.mapIndexed { index, (start, end) ->
                Period(index + 1, LocalTime.parse(start), LocalTime.parse(end))
            },
            courses = ranges.indices.map { index ->
                ScheduledCourse(index + 1L, "同名课程", "", "", 0xFF6750A4, listOf(
                    CourseMeeting(id = index + 1L, day = date.dayOfWeek.value, weeks = setOf(1),
                        sections = setOf(index + 1))
                ))
            }
        )

    @Test fun usesEachConfiguredStartAndEndAtExactBoundaries() {
        for ((start, end) in listOf("08:00" to "09:40", "08:30" to "10:05",
            "09:00" to "10:30", "13:30" to "15:10")) {
            val data = snapshot(start to end)
            val soon = at(start).minusSeconds(600)
            assertEquals(soon, widgetSchedule(data, soon.minusSeconds(1), zone).nextRefresh)
            assertEquals(WidgetStatus.SOON, widgetSchedule(data, soon, zone).rows.single().status)
            val running = widgetSchedule(data, at(start), zone)
            assertEquals(WidgetStatus.RUNNING, running.rows.single().status)
            assertEquals(at(end), running.nextRefresh)
            val ended = widgetSchedule(data, at(end), zone)
            assertTrue(ended.rows.isEmpty())
            assertEquals(WidgetDay.ENDED, ended.day)
            assertEquals(date.plusDays(1).atStartOfDay(zone).toInstant(), ended.nextRefresh)
        }
    }

    @Test fun keepsOverlapsAndReplacesEveryExpiredRowUsingStableIdentity() {
        val data = snapshot("08:00" to "09:40", "08:00" to "09:45", "10:00" to "11:40",
            "14:00" to "15:40")
        fun ids(time: String) = widgetSchedule(data, at(time), zone).rows.map { it.occurrence.course.id }
        assertEquals(listOf(1L, 2L), ids("08:00"))
        assertEquals(listOf(2L, 3L), ids("09:40"))
        assertEquals(listOf(3L, 4L), ids("10:00"))
        assertEquals(listOf(4L), ids("11:40"))
        assertTrue(ids("16:00").isEmpty())
    }

    @Test fun cancelAndRescheduleReplaceTheOriginalAndTomorrowUsesTheEarliestEffectiveTime() {
        val original = snapshot("08:30" to "10:05", "09:00" to "10:30")
        val tomorrow = date.plusDays(1)
        val moved = MeetingException(31, ExceptionKind.RESCHEDULE, date, tomorrow,
            customTime = TimeRange(LocalTime.of(13, 30), LocalTime.of(15, 10)), room = "实验楼 301")
        val cancelled = MeetingException(32, ExceptionKind.CANCEL, date)
        val extra = MeetingException(33, ExceptionKind.EXTRA, targetDate = tomorrow,
            customTime = TimeRange(LocalTime.of(9, 10), LocalTime.of(10, 20)), group = "实验 B 组")
        val data = original.copy(courses = original.courses.mapIndexed { index, course ->
            val overrides = if (index == 0) listOf(moved) else listOf(cancelled, extra)
            course.copy(meetings = listOf(course.meetings.single().copy(exceptions = overrides)))
        })
        val empty = widgetSchedule(data, at("08:00"), zone)
        assertEquals(WidgetDay.EMPTY, empty.day)
        assertEquals(33L, empty.tomorrow?.exception?.id)
        val midnight = widgetSchedule(data, tomorrow.atStartOfDay(zone).toInstant(), zone)
        assertEquals(listOf(33L, 31L), midnight.rows.map { it.occurrence.exception?.id })
        assertEquals("实验 B 组", midnight.rows.first().occurrence.effectiveGroup)
        assertEquals("实验楼 301", midnight.rows.last().occurrence.effectiveRoom)
        val after = widgetSchedule(data, at("15:10", tomorrow), zone)
        assertEquals(WidgetDay.ENDED, after.day)
        assertNull(after.tomorrow)
    }

    @Test fun changingPeriodsMovesDeadlineAndConsecutiveSectionsRemainOneRow() {
        val original = snapshot("08:30" to "09:15", "09:20" to "10:05")
        val course = original.courses.first()
        val data = original.copy(courses = listOf(course.copy(
            meetings = listOf(course.meetings.single().copy(sections = setOf(1, 2)))
        )))
        val gap = widgetSchedule(data, at("09:17"), zone)
        assertEquals(WidgetStatus.RUNNING, gap.rows.single().status)
        assertEquals(at("10:05"), gap.nextRefresh)
        val edited = data.copy(periods = data.periods.map {
            if (it.number == 2) it.copy(end = LocalTime.of(10, 15)) else it
        })
        assertEquals(at("10:15"), widgetSchedule(edited, at("09:17"), zone).nextRefresh)
    }

    @Test fun occurrenceLinkChecksScopeMeetingDateAndExceptionIndependentlyOfName() {
        val data = snapshot("08:30" to "10:05", "09:00" to "10:30").copy(scopeToken = "profile-a")
        val key = TimetableOccurrenceKey("profile-a", 2L, 2L, null)
        assertEquals(2L, key.resolve(data, date)?.course?.id)
        assertNull(key.resolve(data.copy(scopeToken = "profile-b"), date))
        assertNull(key.resolve(data, date.plusDays(1)))
        assertNull(key.copy(meetingId = 1L).resolve(data, date))
        assertNull(key.copy(exceptionId = 1L).resolve(data, date))
        assertNull(key.resolve(data.copy(courses = data.courses.take(1)), date))
    }

    @Test fun weeksAndUntimedLegacyOccurrencesDoNotGuessTimes() {
        val data = snapshot("08:30" to "10:05")
        assertEquals(1, widgetSchedule(data, at("08:00"), zone).week)
        val nextWeek = widgetSchedule(data, at("08:00", date.plusWeeks(1)), zone)
        assertEquals(2, nextWeek.week)
        assertEquals(WidgetDay.EMPTY, nextWeek.day)
        val untimed = widgetSchedule(data.copy(periods = emptyList()), at("08:00"), zone)
        assertEquals(WidgetDay.INCOMPLETE, untimed.day)
        assertTrue(untimed.rows.isEmpty())
        val outsideTerm = widgetSchedule(data, at("08:00", date.plusWeeks(25)), zone)
        assertNull(outsideTerm.week)
        assertEquals(WidgetDay.EMPTY, outsideTerm.day)
    }
}
