/*
 * SPDX-License-Identifier: GPL-3.0-only
 * mondayOf/dateOfWeek adapted from Sleepy contributors (lingion/sleepy), with strict term bounds.
 * https://github.com/lingion/sleepy/blob/3dafa110f74ac4f32464ce1efddb24197a4091d9/app/src/main/java/com/lingion/sleepy/util/DateUtils.kt#L56-L71
 */
package com.lingion.sleepy.domain

import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.CourseOccurrence
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.ExceptionKind
import com.lingion.sleepy.domain.model.MeetingException
import com.lingion.sleepy.domain.model.OccurrenceConflict
import java.time.Clock
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object TimetableRules {
    fun mondayOf(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** A teaching week exists only within the Monday-normalized semester, inclusive of its last Sunday. */
    fun teachingWeek(settings: SemesterSettings, date: LocalDate): Int? {
        val start = settings.startDate?.let(::mondayOf) ?: return null
        if (settings.totalWeeks !in 1..1000) return null
        val days = ChronoUnit.DAYS.between(start, date)
        if (days < 0 || days >= settings.totalWeeks * 7L) return null
        return (days / 7).toInt() + 1
    }

    fun dateOfWeek(settings: SemesterSettings, week: Int, day: Int): LocalDate? {
        require(settings.totalWeeks in 1..1000) { "学期总周数必须在1到1000之间" }
        require(week in 1..settings.totalWeeks) { "周次超出学期范围" }
        require(day in 1..7) { "星期必须在1到7之间" }
        val start = settings.startDate?.let(::mondayOf) ?: return null
        return start.plusWeeks((week - 1).toLong()).plusDays((day - 1).toLong())
    }

    fun today(clock: Clock): LocalDate = LocalDate.now(clock)

    fun occurrencesOn(snapshot: TimetableSnapshot, date: LocalDate): List<CourseOccurrence> {
        val week = teachingWeek(snapshot.settings, date)
        val periodNumbers = snapshot.periods.map { it.number }.toSet()
        return snapshot.courses.flatMap { course ->
            course.meetings.flatMap { meeting ->
                if (meeting.kind == MeetingKind.UNSCHEDULED) return@flatMap emptyList()
                val removed = meeting.exceptions.any { it.originalDate == date && it.kind != ExceptionKind.EXTRA }
                val changes: List<MeetingException?> = buildList {
                    if (baseOccursOn(meeting, snapshot.settings, date) && !removed) add(null)
                    addAll(meeting.exceptions.filter { it.targetDate == date && it.kind != ExceptionKind.CANCEL })
                }
                changes.map { change ->
                val effectiveTime = change?.customTime ?: if (change?.sections != null) null else meeting.customTime
                val effectiveSections = change?.sections ?: meeting.sections
                CourseOccurrence(
                    course = course,
                    meeting = meeting,
                    date = date,
                    week = week,
                    timeRanges = intervals(effectiveTime, effectiveSections, snapshot.periods),
                    missingSections = if (effectiveTime == null) effectiveSections - periodNumbers else emptySet(),
                    exception = change,
                )
                }
            }
        }.sortedWith(
            compareBy<CourseOccurrence> { it.timeRanges.firstOrNull()?.start ?: LocalTime.MAX }
                .thenBy { it.meeting.sections.minOrNull() ?: Int.MAX_VALUE }
                .thenBy { it.course.id }
                .thenBy { it.meeting.id },
        )
    }

    fun baseOccursOn(meeting: CourseMeeting, settings: SemesterSettings, date: LocalDate): Boolean = when (meeting.kind) {
        MeetingKind.RECURRING -> meeting.day == date.dayOfWeek.value && teachingWeek(settings, date)?.let { it in meeting.weeks } == true
        MeetingKind.DATE_SPECIFIC -> date in meeting.dates
        MeetingKind.UNSCHEDULED -> false
    }

    /** A bounded, half-open local-date range; all callers share occurrencesOn. */
    fun occurrencesBetween(snapshot: TimetableSnapshot, startInclusive: LocalDate, endExclusive: LocalDate): List<CourseOccurrence> {
        val length = ChronoUnit.DAYS.between(startInclusive, endExclusive)
        require(length in 0..10_000) { "日期查询范围必须在0到10000天之间" }
        return (0 until length).flatMap { occurrencesOn(snapshot, startInclusive.plusDays(it)) }
    }

    fun conflicts(first: CourseOccurrence, second: CourseOccurrence): List<OccurrenceConflict> {
        if (first.date != second.date) return emptyList()
        return first.timeRanges.flatMap { left -> second.timeRanges.mapNotNull { right ->
            val start = maxOf(left.start, right.start)
            val end = minOf(left.end, right.end)
            if (start < end) OccurrenceConflict(first, second, TimeRange(start, end)) else null
        } }.distinct()
    }

    /** Each selected period stays an independent interval; breaks and unselected sections stay empty. */
    fun periodIntervals(meeting: CourseMeeting, periods: List<Period>): List<TimeRange> {
        return intervals(meeting.customTime, meeting.sections, periods)
    }

    private fun intervals(customTime: TimeRange?, sections: Set<Int>, periods: List<Period>): List<TimeRange> {
        customTime?.let { return listOf(it) }
        return periods.filter { it.number in sections }
            .map { TimeRange(it.start, it.end) }
            .sortedWith(compareBy<TimeRange> { it.start }.thenBy { it.end })
    }

    fun validateSettings(settings: SemesterSettings): List<String> = buildList {
        if (settings.id <= 0) add("学期标识必须为正整数")
        if (settings.name.isBlank()) add("请填写学期名称")
        if (settings.totalWeeks !in 1..1000) add("学期总周数必须在1到1000之间")
        if (settings.startDate != null && settings.totalWeeks in 1..1000) {
            try {
                // Every day from the normalized first Monday through the final Sunday must exist.
                mondayOf(settings.startDate).plusDays(settings.totalWeeks * 7L - 1)
            } catch (_: DateTimeException) {
                add("开学日期与学期周数超出可支持的日期范围")
            }
        }
    }

    fun validatePeriods(periods: List<Period>): List<String> = buildList {
        if (periods.map { it.number }.distinct().size != periods.size) add("节次编号不能重复")
        val byTime = periods.sortedBy { it.start }
        if (byTime.zipWithNext().any { (first, second) -> first.end > second.start }) {
            add("节次时间不能重叠")
        }
    }
    fun validateCourse(
        draft: CourseDraft,
        settings: SemesterSettings,
        periods: List<Period>,
    ): List<String> = buildList {
        if (draft.courseName.isBlank()) add("请填写课程名称")
        if (draft.color !in 0..0xFFFFFFFFL) add("课程颜色必须为有效ARGB颜色")
        if (draft.meetings.isEmpty()) add("请至少添加一个上课安排")
        if (settings.totalWeeks !in 1..1000) add("请先设置有效的学期总周数")
        val availableSections = periods.map { it.number }.toSet()
        draft.meetings.forEachIndexed { index, meeting ->
            val prefix = "第${index + 1}个安排"
            if (meeting.kind == MeetingKind.RECURRING) {
                if (meeting.weeks.isEmpty()) add("${prefix}请选择周次")
                if (meeting.weeks.any { it !in 1..settings.totalWeeks }) add("${prefix}的周次超出学期范围")
                if (meeting.dates.isNotEmpty()) add("${prefix}周期安排不能带指定日期")
            } else {
                if (meeting.weeks.isNotEmpty()) add("${prefix}非周期安排不能带周次")
                if (meeting.kind == MeetingKind.DATE_SPECIFIC && meeting.dates.isEmpty()) add("${prefix}请选择日期")
                if (meeting.kind == MeetingKind.UNSCHEDULED && (meeting.dates.isNotEmpty() || meeting.exceptions.isNotEmpty())) add("${prefix}未排期安排不能带日期或例外")
            }
            if (meeting.customTime == null && meeting.kind != MeetingKind.UNSCHEDULED) {
                val missing = meeting.sections - availableSections
                if (missing.isNotEmpty()) add("${prefix}包含未设置时间的节次：${formatNumberSet(missing)}")
            }

        }
        addAll(validateExceptions(draft, settings, periods))
        addAll(validateMetadata(draft))
    }

    /** Aggregate metadata limits apply to both new saves and decoded legacy courses. */
    fun validateMetadata(draft: CourseDraft): List<String> = buildList {
        val sources = draft.provenance + draft.meetings.flatMap { it.provenance + it.exceptions.flatMap { e -> e.provenance } }
        if (sources.size > 10_000 || sources.sumOf { it.rawText.length.toLong() } > 1_000_000) add("来源信息超过安全容量")
        if ((draft.sourceCourseCode?.length ?: 0) > 2048 || (draft.sourceClassCode?.length ?: 0) > 2048) add("来源课程代码过长")
    }

    /** Validate new exception relationships without rejecting preserved legacy base rows. */
    fun validateExceptions(
        draft: CourseDraft,
        settings: SemesterSettings,
        periods: List<Period>,
    ): List<String> = buildList {
        val availableSections = periods.map { it.number }.toSet()
        draft.meetings.forEachIndexed { index, meeting ->
            val prefix = "第${index + 1}个安排"
            if (meeting.exceptions.filter { it.originalDate != null }.groupBy { it.originalDate }.any { it.value.size > 1 }) add("${prefix}同一原日期存在多个例外")
            meeting.exceptions.forEach { change ->
                if (change.originalDate != null && !baseOccursOn(meeting, settings, change.originalDate)) add("${prefix}例外原日期没有基础安排")
                val time = change.customTime ?: if (change.sections != null) null else meeting.customTime
                val sections = change.sections ?: meeting.sections
                if (change.kind != ExceptionKind.CANCEL && time == null && (sections.isEmpty() || !availableSections.containsAll(sections))) add("${prefix}例外节次没有有效时间")
            }
            val extras = meeting.exceptions.filter { it.kind == ExceptionKind.EXTRA }.map {
                val time = it.customTime ?: if (it.sections != null) null else meeting.customTime
                listOf(it.targetDate, intervals(time, it.sections ?: meeting.sections, periods), it.teacher ?: meeting.teacher ?: draft.teacher,
                    it.room ?: meeting.room ?: draft.room, it.experimentProject ?: meeting.experimentProject, it.group ?: meeting.group)
            }
            if (extras.distinct().size != extras.size) add("${prefix}加课例外重复")
        }
    }

}

const val MAX_EXPANDED_VALUES = 10_000

fun parseNumberSet(input: String, max: Int, label: String): Set<Int> {
    fun invalid(): Nothing = throw IllegalArgumentException("${label}必须使用1到${max}之间的数字、逗号或递增范围")
    if (max <= 0 || input.isBlank()) invalid()
    val result = sortedSetOf<Int>()
    for (part in input.replace('，', ',').split(',')) {
        val token = part.trim()
        val match = Regex("([0-9]+)(?:\\s*-\\s*([0-9]+))?").matchEntire(token) ?: invalid()
        val start = match.groupValues[1].toIntOrNull() ?: invalid()
        val endText = match.groupValues[2]
        val end = if (endText.isEmpty()) start else endText.toIntOrNull() ?: invalid()
        if (start !in 1..max || end !in start..max) invalid()
        // Count the union before enumeration: a high number is valid, a huge expansion is not.
        val rangeCardinality = end.toLong() - start.toLong() + 1L
        val existingInRange = result.count { it >= start && it <= end }.toLong()
        val expandedCardinality = result.size.toLong() + rangeCardinality - existingInRange
        if (expandedCardinality > MAX_EXPANDED_VALUES) {
            throw IllegalArgumentException("${label}最多包含${MAX_EXPANDED_VALUES}个不同数字")
        }
        result.addAll(start..end)
    }
    return result
}

fun formatNumberSet(numbers: Set<Int>): String {
    require(numbers.all { it > 0 }) { "数字集合只能包含正整数" }
    val sorted = numbers.sorted()
    if (sorted.isEmpty()) return ""
    val runs = mutableListOf<String>()
    var first = sorted.first()
    var last = first
    fun addRun() { runs += if (first == last) "$first" else "$first-$last" }
    for (number in sorted.drop(1)) {
        if (number.toLong() == last.toLong() + 1) {
            last = number
        } else {
            addRun()
            first = number
            last = number
        }
    }
    addRun()
    return runs.joinToString(",")
}
