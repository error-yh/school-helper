/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing

import java.time.LocalDate

/** What a notice states about ONE occurrence. It never means "delete the course". */
enum class NoticeIntentKind {
    /** 停课/取消: the original occurrence is removed, nothing is added. */
    CANCEL,
    /** 同一天换节次/时间(including "only time" without a new date). */
    RESCHEDULE_TIME,
    /** 换到另一天 (optionally with new sections/time). */
    CHANGE_DATE,
    /** 仅改地点, date/sections/time unchanged. */
    ROOM_ONLY,
    /** 补课/加课: one added occurrence, nothing is removed. */
    EXTRA,
}

/** Explicit old/new role values. Empty means "not stated" and is never read as keep or erase. */
data class NoticeScope(val sections: Set<Int> = emptySet(), val time: DraftTimeRange? = null) {
    init { require(sections.all { it > 0 }) }
    val specified: Boolean get() = sections.isNotEmpty() || time != null
}

enum class NoticeRoomRole { KEEP, CHANGE }

/** 教室不变 is KEEP; 教室改为X is CHANGE. A null role means the notice says nothing. */
data class NoticeRoomChange(val role: NoticeRoomRole, val from: String? = null, val to: String? = null) {
    init {
        require(role == NoticeRoomRole.KEEP || to != null) { "改地点必须给出目标地点" }
        require(role == NoticeRoomRole.KEEP || (to?.length ?: 0) <= 256)
        require((from?.length ?: 0) <= 256)
    }
}

enum class NoticeDayPart { MORNING, AFTERNOON }

/** 下午第二大节 needs a user-confirmed period mapping; it is never guessed as two periods. */
data class NoticeBigSection(val part: NoticeDayPart, val ordinal: Int, val originalSide: Boolean = false) {
    init { require(ordinal in 1..4) }
}

/**
 * Role metadata produced together with an existing pending exception. It is optional and
 * additive: older callers keep reading [com.lingion.sleepy.importing.formats.PendingExceptionAttachment.exception]
 * exactly as before, and no role here authorizes a write by itself.
 */
data class NoticeExceptionRoles(
    val intent: NoticeIntentKind,
    val original: NoticeScope = NoticeScope(),
    val target: NoticeScope = NoticeScope(),
    val room: NoticeRoomChange? = null,
    val bigSection: NoticeBigSection? = null,
    /** The notice changed time/sections only; the original date is kept explicitly. */
    val sameDayAdjustment: Boolean = false,
    /** Explicit but malformed declarations; distinct from "not stated" and blocking until corrected. */
    val originalTimeInvalid: Boolean = false,
    val originalSectionsInvalid: Boolean = false,
    val targetTimeInvalid: Boolean = false,
    val targetSectionsInvalid: Boolean = false,
)

/** A date basis that the user must confirm; nothing here is derived from the device clock. */
data class NoticeDateBasis(
    val referenceDate: LocalDate? = null,
    val termMonday: LocalDate? = null,
    val totalWeeks: Int? = null,
    val confirmedYear: Int? = null,
) {
    init {
        require(referenceDate == null || referenceDate.year in 1..9999) { "参考日期无效" }
        require(termMonday == null || termMonday.dayOfWeek.value == 1) { "学期起点必须是周一" }
        require(totalWeeks == null || totalWeeks in 1..1000) { "学期周数必须在1到1000之间" }
        require(confirmedYear == null || confirmedYear in 1..9999) { "年份无效" }
    }
}

/** User-confirmed mapping from 上午/下午 big sections to concrete sections. */
data class NoticeBigSectionMapping(
    val morning: List<Set<Int>> = emptyList(),
    val afternoon: List<Set<Int>> = emptyList(),
) {
    init {
        val groups = morning + afternoon
        require(groups.all { group -> group.isNotEmpty() && group.all { it > 0 } }) { "大节映射必须是非空的正节次集合" }
        require(groups.flatten().size == groups.flatten().toSet().size) { "大节映射不能重复节次" }
    }
    fun sections(part: NoticeDayPart, ordinal: Int): Set<Int>? =
        when (part) {
            NoticeDayPart.MORNING -> morning.getOrNull(ordinal - 1)
            NoticeDayPart.AFTERNOON -> afternoon.getOrNull(ordinal - 1)
        }
}