/*
 * SPDX-License-Identifier: GPL-3.0-only
 * inWeek adapted from Sleepy contributors (lingion/sleepy), 2026-10-23.
 * https://github.com/lingion/sleepy/blob/3dafa110f74ac4f32464ce1efddb24197a4091d9/app/src/main/java/com/lingion/sleepy/data/entity/CourseEntity.kt
 * Only week filtering is reused. This minimal model is a new Stage 1 schema.
 */
package com.lingion.sleepy.domain.model

data class Course(
    val id: Long = 0,
    val courseName: String,
    val day: Int,
    val startNode: Int,
    val step: Int,
    val startWeek: Int,
    val endWeek: Int,
    val type: Int = 0,
) {
    init {
        require(day in 1..7) { "day must be between 1 and 7" }
        require(startNode > 0) { "startNode must be positive" }
        require(step > 0) { "step must be positive" }
        require(startWeek > 0 && startWeek <= endWeek) {
            "week range must start at 1 or later and be ordered"
        }
    }

    /** Whether this course is held in the requested absolute week number. */
    fun inWeek(week: Int): Boolean {
        if (week < startWeek || week > endWeek) return false
        return when (type) {
            0 -> true  // 每周
            1 -> week % 2 == 1  // 单周
            2 -> week % 2 == 0  // 双周
            3 -> true  // 按周次列实际指定的周; 上面已限定 [startWeek, endWeek] 区间
            else -> true  // 防御性: 旧数据可能存了非法 type, 不丢失
        }
    }
}
