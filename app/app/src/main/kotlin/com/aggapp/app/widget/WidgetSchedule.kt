package com.aggapp.app.widget

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.CourseOccurrence
import com.lingion.sleepy.domain.model.TimetableSnapshot
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal enum class WidgetStatus { UPCOMING, SOON, RUNNING }
internal enum class WidgetDay { ACTIVE, EMPTY, ENDED, INCOMPLETE }

internal data class WidgetRow(
    val occurrence: CourseOccurrence,
    val start: Instant,
    val end: Instant,
) {
    val soon: Instant get() = start.minus(Duration.ofMinutes(10))
    fun statusAt(now: Instant): WidgetStatus = when {
        now >= start -> WidgetStatus.RUNNING
        now >= soon -> WidgetStatus.SOON
        else -> WidgetStatus.UPCOMING
    }
}

internal data class VisibleWidgetRow(val occurrence: CourseOccurrence, val status: WidgetStatus)

internal data class WidgetSchedule(
    val date: LocalDate,
    val week: Int?,
    val rows: List<VisibleWidgetRow>,
    val day: WidgetDay,
    val tomorrow: CourseOccurrence?,
    val nextRefresh: Instant,
)

internal fun widgetSchedule(snapshot: TimetableSnapshot, now: Instant, zone: ZoneId): WidgetSchedule {
    val today = now.atZone(zone).toLocalDate()
    val tomorrow = today.plusDays(1)
    val midnight = tomorrow.atStartOfDay(zone).toInstant()
    val occurrences = TimetableRules.occurrencesOn(snapshot, today)
    val timed = occurrences.mapNotNull { it.toWidgetRow(zone) }.sortedBy { it.start }
    val visible = timed.asSequence().filter { now < it.end }.take(2).toList()
    val day = when {
        visible.isNotEmpty() -> WidgetDay.ACTIVE
        occurrences.isEmpty() -> WidgetDay.EMPTY
        timed.size < occurrences.size -> WidgetDay.INCOMPLETE
        else -> WidgetDay.ENDED
    }
    val preview = if (day == WidgetDay.EMPTY || day == WidgetDay.ENDED) {
        val next = TimetableRules.occurrencesOn(snapshot, tomorrow)
        val nextTimed = next.mapNotNull { it.toWidgetRow(zone) }
        if (nextTimed.size == next.size) nextTimed.minByOrNull { it.start }?.occurrence else null
    } else null
    val nextRefresh = visible.flatMap { listOf(it.soon, it.start, it.end) }
        .filter { it > now }.minOrNull()?.let { minOf(it, midnight) } ?: midnight
    return WidgetSchedule(
        today, TimetableRules.teachingWeek(snapshot.settings, today),
        visible.map { VisibleWidgetRow(it.occurrence, it.statusAt(now)) },
        day, preview, nextRefresh,
    )
}

private fun CourseOccurrence.toWidgetRow(zone: ZoneId): WidgetRow? {
    if (missingSections.isNotEmpty()) return null
    val start = timeRanges.minOfOrNull { it.start } ?: return null
    val end = timeRanges.maxOf { it.end }
    val actualStart = date.atTime(start).atZone(zone).toInstant()
    val actualEnd = date.atTime(end).atZone(zone).toInstant()
    return if (actualStart < actualEnd) WidgetRow(this, actualStart, actualEnd) else null
}
