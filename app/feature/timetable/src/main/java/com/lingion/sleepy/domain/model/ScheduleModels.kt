/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Default period values adapted from Sleepy contributors (lingion/sleepy).
 * https://github.com/lingion/sleepy/blob/3dafa110f74ac4f32464ce1efddb24197a4091d9/app/src/main/java/com/lingion/sleepy/util/TimeTableUtils.kt#L31-L44
 * Aggregate/meeting separation is new; the Stage 1 Course model remains the v1 migration model.
 */
package com.lingion.sleepy.domain.model

import java.time.LocalDate
import java.time.LocalTime

data class SemesterSettings(
    val id: Long = 1,
    val name: String = "我的学期",
    val startDate: LocalDate? = null,
    val totalWeeks: Int = 20,
)

data class Period(val number: Int, val start: LocalTime, val end: LocalTime) {
    init {
        require(number > 0) { "节次必须为正整数" }
        requireMinuteRange(start, end)
    }
}

/** A same-day half-open interval: [start, end). */
data class TimeRange(val start: LocalTime, val end: LocalTime) {
    init { requireMinuteRange(start, end) }
}

data class CourseMeeting(
    val id: Long = 0,
    val day: Int,
    val weeks: Set<Int>,
    val sections: Set<Int>,
    val customTime: TimeRange? = null,
    val teacher: String? = null,
    val room: String? = null,
    val kind: MeetingKind = MeetingKind.RECURRING,
    val dates: Set<LocalDate> = emptySet(),
    val experimentProject: String? = null,
    val group: String? = null,
    val isExperiment: Boolean = false,
    val provenance: List<ScheduleProvenance> = emptyList(),
    val exceptions: List<MeetingException> = emptyList(),
    val declaredWeeks: Set<Int> = emptySet(),
) {
    init {
        require(day in (if (kind == MeetingKind.RECURRING) 1..7 else 0..7)) { "星期无效，非周期安排的0表示未指定" }
        // Empty weeks retain legal inert v1 rows (for example an odd-only week-2 row).
        // The new/edit save validator rejects empty weeks without losing archived data.
        require(weeks.all { it > 0 }) { "周次必须为正整数" }
        require(sections.all { it > 0 }) { "节次必须为正整数" }
        require(declaredWeeks.all { it in 1..1000 }) { "来源标注周次必须在1到1000之间" }
        when (kind) {
            MeetingKind.RECURRING -> require(dates.isEmpty()) { "周期安排不能带指定日期" }
            MeetingKind.DATE_SPECIFIC -> require(weeks.isEmpty() && dates.isNotEmpty()) { "指定日期安排需要日期，不能带周期周次" }
            MeetingKind.UNSCHEDULED -> require(weeks.isEmpty() && dates.isEmpty() && exceptions.isEmpty()) { "未排期安排不能带周期、日期或例外" }
        }
        if (customTime == null && kind != MeetingKind.UNSCHEDULED) {
            require(sections.isNotEmpty()) { "节次不能为空" }
        }
    }
}

data class CourseDraft(
    val courseName: String,
    val teacher: String = "",
    val room: String = "",
    val color: Long = 0xFF6750A4,
    val meetings: List<CourseMeeting>,
    val sourceCourseCode: String? = null,
    val sourceClassCode: String? = null,
    val provenance: List<ScheduleProvenance> = emptyList(),
)

data class ScheduledCourse(
    val id: Long,
    val courseName: String,
    val teacher: String,
    val room: String,
    val color: Long,
    val meetings: List<CourseMeeting>,
    val sourceCourseCode: String? = null,
    val sourceClassCode: String? = null,
    val provenance: List<ScheduleProvenance> = emptyList(),
) {
    fun asDraft(): CourseDraft = CourseDraft(courseName, teacher, room, color, meetings, sourceCourseCode, sourceClassCode, provenance)
}

data class TimetableSnapshot(
    val settings: SemesterSettings = SemesterSettings(),
    val periods: List<Period> = defaultPeriods(),
    val courses: List<ScheduledCourse> = emptyList(),
    val revision: Long = 0,
    val scopeToken: String = "local",
)

data class CourseOccurrence(
    val course: ScheduledCourse,
    val meeting: CourseMeeting,
    val date: LocalDate,
    val week: Int?,
    val timeRanges: List<TimeRange>,
    val missingSections: Set<Int> = emptySet(),
    val exception: MeetingException? = null,
) {
    val effectiveTeacher: String get() = exception?.teacher ?: meeting.teacher ?: course.teacher
    val effectiveRoom: String get() = exception?.room ?: meeting.room ?: course.room
    val effectiveProject: String? get() = exception?.experimentProject ?: meeting.experimentProject
    val effectiveGroup: String? get() = exception?.group ?: meeting.group
    val effectiveSections: Set<Int> get() = exception?.sections ?: meeting.sections
    val effectiveCustomTime: TimeRange? get() = exception?.customTime
        ?: if (exception?.sections != null) null else meeting.customTime
}

fun defaultPeriods(): List<Period> = listOf(
    "08:00" to "08:45", "08:55" to "09:40", "10:00" to "10:45", "10:55" to "11:40",
    "14:00" to "14:45", "14:55" to "15:40", "16:00" to "16:45", "16:55" to "17:40",
    "19:00" to "19:45", "19:55" to "20:40", "20:50" to "21:35", "21:45" to "22:30",
).mapIndexed { index, (start, end) -> Period(index + 1, LocalTime.parse(start), LocalTime.parse(end)) }

private fun requireMinuteRange(start: LocalTime, end: LocalTime) {
    require(start.second == 0 && start.nano == 0 && end.second == 0 && end.nano == 0) {
        "时间必须精确到分钟，不能包含秒或纳秒"
    }
    require(start < end) { "结束时间必须晚于同一天的开始时间" }
}
