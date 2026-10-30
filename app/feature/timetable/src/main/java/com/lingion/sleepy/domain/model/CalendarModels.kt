package com.lingion.sleepy.domain.model

import java.time.LocalDate

enum class MeetingKind { RECURRING, DATE_SPECIFIC, UNSCHEDULED }
enum class ExceptionKind { CANCEL, RESCHEDULE, EXTRA }

data class SourceBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..10_000_000f }) { "来源坐标无效" }
        require(left <= right && top <= bottom) { "来源坐标顺序无效" }
    }
}

data class ScheduleProvenance(
    val sourceId: String? = null,
    val adapterId: String? = null,
    val page: Int? = null,
    val bounds: SourceBounds? = null,
    val rawText: String,
    val sourceLocator: String? = null,
) {
    init {
        require(page == null || page in 1..100_000) { "来源页码无效" }
        require((sourceId?.length ?: 0) <= 2048 && (adapterId?.length ?: 0) <= 256 && rawText.length <= 500_000) { "来源记录超过长度限制" }
        require((sourceLocator?.length ?: 0) <= 4096) { "来源定位超过长度限制" }
    }
}

data class MeetingException(
    val id: Long = 0,
    val kind: ExceptionKind,
    val originalDate: LocalDate? = null,
    val targetDate: LocalDate? = null,
    val customTime: TimeRange? = null,
    val sections: Set<Int>? = null,
    val teacher: String? = null,
    val room: String? = null,
    val experimentProject: String? = null,
    val group: String? = null,
    val provenance: List<ScheduleProvenance> = emptyList(),
) {
    init {
        require(id >= 0 && id < Long.MAX_VALUE) { "例外标识无效" }
        require(sections == null || sections.all { it > 0 }) { "例外节次必须为正整数" }
        when (kind) {
            ExceptionKind.CANCEL -> require(originalDate != null && targetDate == null && customTime == null && sections == null &&
                teacher == null && room == null && experimentProject == null && group == null) { "取消记录不能带有目标或覆盖字段" }
            ExceptionKind.RESCHEDULE -> require(originalDate != null && targetDate != null) { "调课需要原日期和目标日期" }
            ExceptionKind.EXTRA -> require(originalDate == null && targetDate != null) { "加课仅需要目标日期" }
        }
    }
}

data class OccurrenceConflict(val first: CourseOccurrence, val second: CourseOccurrence, val overlap: TimeRange)
