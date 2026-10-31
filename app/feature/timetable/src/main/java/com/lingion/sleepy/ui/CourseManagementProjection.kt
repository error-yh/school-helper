/* SPDX-License-Identifier: GPL-3.0-only
 * Stage 4 display-only grouping for the course management tab.
 * Persisted courses and meetings stay the single writable entity; this projection never mutates,
 * merges or deletes rows and never rebuilds an editable "combined course".
 */
package com.lingion.sleepy.ui

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.formatNumberSet
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.ExceptionKind
import com.lingion.sleepy.domain.model.MeetingException
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimetableSnapshot
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** The display scope a projection was built for: the local database/profile token plus the semester id. */
internal data class CourseGroupScope(val scopeToken: String, val semesterId: Long)

/** One persisted [CourseMeeting] of one persisted [ScheduledCourse], rendered as a compact row. */
internal data class CourseGroupMeetingRow(
    /** Stable within the projection: course id, meeting id and the persisted list position. */
    val key: String,
    val course: ScheduledCourse,
    val meeting: CourseMeeting,
    val meetingIndex: Int,
    val kindLabel: String,
    /** Dates or weeks only; never expanded into one line per weekly occurrence. */
    val scheduleText: String,
    val sectionsText: String,
    /** Real clock times from [TimetableRules.periodIntervals]; a custom time is never replaced by period times. */
    val timeText: String,
    val teacherText: String,
    val roomText: String,
    val projectText: String?,
    val groupText: String?,
    val exceptionTexts: List<String>,
    val missingSectionsText: String?,
    /** How many meetings the owning record has, so the user can see the edit/delete scope. */
    val recordMeetingCount: Int,
)

/** A display group: one card. It references raw records and rows; it is not a writable course. */
internal data class CourseGroup(
    val key: String,
    val displayName: String,
    val courses: List<ScheduledCourse>,
    val scheduledRows: List<CourseGroupMeetingRow>,
    val unscheduledRows: List<CourseGroupMeetingRow>,
    /** Set when the user confirmed this group; null means it came from the conservative automatic rules. */
    val manualId: String? = null,
) {
    val recordCount: Int get() = courses.size
    val meetingCount: Int get() = scheduledRows.size + unscheduledRows.size
    val rows: List<CourseGroupMeetingRow> get() = scheduledRows + unscheduledRows
    val color: Long get() = courses.first().color
}

/**
 * Read-only list projection of [TimetableSnapshot.courses].
 *
 * Counts are deliberately explicit: a group is a display unit built from confirmable identity, a
 * record is a persisted [ScheduledCourse], and an arrangement ("安排") is one persisted
 * [CourseMeeting] (a multi-date meeting stays one arrangement; exceptions are reported separately).
 */
internal data class CourseManagementProjection(
    val scope: CourseGroupScope,
    val groups: List<CourseGroup>,
) {
    val groupCount: Int get() = groups.size
    val recordCount: Int get() = groups.sumOf { it.recordCount }
    val meetingCount: Int get() = groups.sumOf { it.meetingCount }

    /** True when the conservative rules had to keep same-named records apart. */
    val hasConservativeSplit: Boolean
        get() = groupCount > groups.distinctBy { normalizedCourseName(it.displayName) }.size

    /** Records that carry no course code and therefore can never join another record. */
    val unattributedRecordCount: Int
        get() = groups.sumOf { group -> group.courses.count { courseIdentity(it) == null } }
}

internal fun projectCourseManagement(
    snapshot: TimetableSnapshot,
    manualGroups: List<EffectiveManualGroup> = emptyList(),
): CourseManagementProjection =
    projectCourseManagement(
        snapshot.courses,
        snapshot.periods,
        CourseGroupScope(snapshot.scopeToken, snapshot.settings.id),
        manualGroups,
    )

/**
 * Groups [courses] into display cards.
 *
 * Identity rules, in order:
 * 1. One persisted course id is always one group; the same id is never split or re-keyed by name.
 * 2. Two different ids may share a group only when both carry a non-blank course code, the code and
 *    the class code (or, without class codes, one identical import document context) match exactly,
 *    and the normalised course names are compatible. A blank field never acts as a wildcard.
 * 3. Everything else stays separate: same name without a code, same name with different codes,
 *    numeric name suffixes, blank ids, blank bounds, raw text or OCR-local positions are never
 *    evidence of the same course.
 * 4. Scope and semester are part of every key, so records from another profile or semester can
 *    never share a display key.
 * 5. User-confirmed groups are applied first and win over the automatic rules; their members are
 *    removed before the automatic pass, so manual and automatic cards always partition every
 *    persisted record exactly once, with no duplicate or missing card.
 */
internal fun projectCourseManagement(
    courses: List<ScheduledCourse>,
    periods: List<Period>,
    scope: CourseGroupScope,
    manualGroups: List<EffectiveManualGroup> = emptyList(),
): CourseManagementProjection {
    val remaining = courses.toMutableList()
    val manualCards = ArrayList<CourseGroup>(manualGroups.size)
    manualGroups.forEach { manual ->
        // Every persisted record with a confirmed member id is claimed, so no record is ever dropped.
        val members = remaining.filter { candidate -> manual.courses.any { it.id == candidate.id } }
        // Defensive: a degenerate group never hides records from the automatic rules.
        if (members.size < 2) return@forEach
        remaining.removeAll { candidate -> members.any { it.id == candidate.id } }
        manualCards += buildCourseGroup(
            key = "manual:" + manual.id,
            displayName = manual.name,
            courses = members.sortedWith(compareBy<ScheduledCourse> { it.id }),
            periods = periods,
            manualId = manual.id,
        )
    }
    val automatic = automaticCourseGroups(remaining, periods, scope)
    return CourseManagementProjection(
        scope,
        manualCards.sortedWith(compareBy<CourseGroup>({ normalizedCourseName(it.displayName) }, { it.key })) + automatic,
    )
}

private fun automaticCourseGroups(
    courses: List<ScheduledCourse>,
    periods: List<Period>,
    scope: CourseGroupScope,
): List<CourseGroup> {
    // Rule 1: bucket by real id first, so a repeated id can never be split by a later rule.
    val records = LinkedHashMap<Long, MutableList<IndexedValue<ScheduledCourse>>>()
    courses.forEachIndexed { index, course ->
        records.getOrPut(course.id) { mutableListOf() }.add(IndexedValue(index, course))
    }
    val buckets = records.values.toList()
    // A bucket only has an identity when every member agrees on the same non-null one.
    val bucketIdentities = buckets.map { bucket ->
        bucket.map { courseIdentity(it.value) }.distinct().singleOrNull()
    }

    val parent = IntArray(buckets.size) { it }
    fun rootOf(index: Int): Int {
        var root = index
        while (parent[root] != root) root = parent[root]
        var cursor = index
        while (parent[cursor] != cursor) {
            val next = parent[cursor]
            parent[cursor] = root
            cursor = next
        }
        return root
    }
    fun merge(first: Int, second: Int) {
        val left = rootOf(first)
        val right = rootOf(second)
        if (left != right) parent[maxOf(left, right)] = minOf(left, right)
    }
    bucketIdentities.withIndex().filter { it.value != null }.groupBy({ it.value!! }, { it.index })
        .forEach { (_, members) ->
            for (i in members.indices) {
                for (j in i + 1 until members.size) {
                    // Rule 2: the explicit code/class match is still gated by a compatible name.
                    if (compatibleCourseNames(
                            buckets[members[i]].first().value.courseName,
                            buckets[members[j]].first().value.courseName,
                        )
                    ) {
                        merge(members[i], members[j])
                    }
                }
            }
        }

    val components = LinkedHashMap<Int, MutableList<Int>>()
    buckets.indices.forEach { index -> components.getOrPut(rootOf(index)) { mutableListOf() }.add(index) }

    return components.values.map { component ->
        val members = component.flatMap { buckets[it] }
            .sortedWith(compareBy<IndexedValue<ScheduledCourse>>({ it.value.id }, { it.index }))
        val memberCourses = members.map { it.value }
        val identity = bucketIdentities[component.first()]
        val displayName = memberCourses.first().courseName.trim()
        buildCourseGroup(
            key = groupKey(scope, displayName, identity, memberCourses.first().id),
            displayName = displayName,
            courses = memberCourses,
            periods = periods,
        )
    }.sortedWith(compareBy<CourseGroup>({ normalizedCourseName(it.displayName) }, { it.key }))
}

private fun buildCourseGroup(
    key: String,
    displayName: String,
    courses: List<ScheduledCourse>,
    periods: List<Period>,
    manualId: String? = null,
): CourseGroup {
    val periodNumbers = periods.map { it.number }.toSet()
    val rows = courses.flatMap { rowsFor(it, periods, periodNumbers) }.sortedWith(rowOrder())
    return CourseGroup(
        key = key,
        displayName = displayName,
        courses = courses,
        scheduledRows = rows.filter { it.row.meeting.kind != MeetingKind.UNSCHEDULED }.map { it.row },
        unscheduledRows = rows.filter { it.row.meeting.kind == MeetingKind.UNSCHEDULED }.map { it.row },
        manualId = manualId,
    )
}

private fun rowOrder(): Comparator<SortableRow> = compareBy<SortableRow> { it.kindRank }
    .thenBy { it.day }
    .thenBy { it.date }
    .thenBy { it.start }
    .thenBy { it.minSection }
    .thenBy { it.minWeek }
    .thenBy { it.row.course.id }
    .thenBy { it.row.meeting.id }
    .thenBy { it.row.meetingIndex }

/** Every arrangement of one record, ordered; shared with the organize screen's short summary. */
internal fun orderedMeetingRows(course: ScheduledCourse, periods: List<Period>): List<CourseGroupMeetingRow> =
    rowsFor(course, periods, periods.map { it.number }.toSet()).sortedWith(rowOrder()).map { it.row }

private data class SortableRow(
    val row: CourseGroupMeetingRow,
    val kindRank: Int,
    val day: Int,
    val date: LocalDate,
    val start: LocalTime,
    val minSection: Int,
    val minWeek: Int,
)

private data class CourseIdentity(val code: String, val discriminator: String)

/** Decorations that do not distinguish two titles; a suffix shorter than this may still be the same course. */
private const val MAX_COMPATIBLE_NAME_SUFFIX = 12

private val nameDecoration = setOf(
    '·', '•', '・', '-', '—', '–', '_', '/', '／', '（', '）', '(', ')', '【', '】', '[', ']', '《', '》', '"', '\'',
)

/** Case/space/decoration-insensitive name key used for ordering and the conservative-input notice. */
internal fun normalizedCourseName(raw: String): String =
    raw.trim().lowercase(Locale.ROOT).filterNot { it.isWhitespace() || it in nameDecoration }

/**
 * Names are compatible when equal, or when the longer one only extends the shorter by a short
 * qualifier ("实验二", "(2)", "上机"). Names never establish identity on their own; a code match is
 * always required first, so a numeric suffix alone cannot join two records.
 */
internal fun compatibleCourseNames(first: String, second: String): Boolean {
    val left = normalizedCourseName(first)
    val right = normalizedCourseName(second)
    if (left.isEmpty() || right.isEmpty()) return false
    if (left == right) return true
    val (shorter, longer) = if (left.length <= right.length) left to right else right to left
    return longer.startsWith(shorter) && longer.length - shorter.length <= MAX_COMPATIBLE_NAME_SUFFIX
}

/** Trimmed, case-folded, whitespace-free code; blank is absent, never a wildcard. */
internal fun normalizedSourceCode(raw: String?): String? =
    raw?.filterNot { it.isWhitespace() }?.lowercase(Locale.ROOT)?.ifEmpty { null }

/**
 * One import document (adapter id plus source id) as a reliable context. The synthetic
 * "document-metadata" source is a generic label, so it is excluded; a course whose provenance
 * covers several documents has no single context and cannot use this fallback.
 */
internal fun reliableSourceContext(course: ScheduledCourse): String? {
    val sources = course.provenance +
        course.meetings.flatMap { meeting -> meeting.provenance + meeting.exceptions.flatMap { it.provenance } }
    val pairs = sources.mapNotNull { source ->
        val sourceId = source.sourceId?.takeIf { it.isNotBlank() && it != "document-metadata" }
        val adapterId = source.adapterId?.takeIf { it.isNotBlank() }
        if (sourceId != null && adapterId != null) "$adapterId|$sourceId" else null
    }.distinct()
    return pairs.singleOrNull()
}

private fun courseIdentity(course: ScheduledCourse): CourseIdentity? {
    val code = normalizedSourceCode(course.sourceCourseCode) ?: return null
    val classCode = normalizedSourceCode(course.sourceClassCode)
    val discriminator = if (classCode != null) {
        "class=$classCode"
    } else {
        reliableSourceContext(course)?.let { "context=$it" } ?: return null
    }
    return CourseIdentity(code, discriminator)
}

private fun groupKey(
    scope: CourseGroupScope,
    displayName: String,
    identity: CourseIdentity?,
    courseId: Long,
): String {
    val base = "s=${scope.scopeToken}|t=${scope.semesterId}"
    return if (identity == null) {
        "$base|id=$courseId"
    } else {
        "$base|code=${identity.code}|${identity.discriminator}|name=${normalizedCourseName(displayName)}"
    }
}

private fun rowsFor(
    course: ScheduledCourse,
    periods: List<Period>,
    periodNumbers: Set<Int>,
): List<SortableRow> = course.meetings.mapIndexed { index, meeting ->
    val intervals = TimetableRules.periodIntervals(meeting, periods)
    val missing = if (meeting.customTime == null && meeting.kind != MeetingKind.UNSCHEDULED) {
        meeting.sections - periodNumbers
    } else {
        emptySet()
    }
    val row = CourseGroupMeetingRow(
        key = "${course.id}#${meeting.id}#$index",
        course = course,
        meeting = meeting,
        meetingIndex = index,
        kindLabel = when (meeting.kind) {
            MeetingKind.RECURRING -> "周期安排"
            MeetingKind.DATE_SPECIFIC -> "指定日期"
            MeetingKind.UNSCHEDULED -> "未排定"
        },
        scheduleText = scheduleText(meeting),
        sectionsText = sectionsText(meeting),
        timeText = intervals.joinToString("、") { "${it.start}–${it.end}" }.ifEmpty { "未设置时间" },
        teacherText = effectiveText(meeting.teacher, course.teacher),
        roomText = effectiveText(meeting.room, course.room),
        projectText = meeting.experimentProject?.trim()?.takeIf { it.isNotEmpty() },
        groupText = meeting.group?.trim()?.takeIf { it.isNotEmpty() },
        exceptionTexts = meeting.exceptions.map(::describeException),
        missingSectionsText = if (missing.isEmpty()) {
            null
        } else {
            "未定位节次 ${formatNumberSet(missing)}：缺少作息，请补充设置或编辑课程。"
        },
        recordMeetingCount = course.meetings.size,
    )
    SortableRow(
        row = row,
        kindRank = when (meeting.kind) {
            MeetingKind.RECURRING -> 0
            MeetingKind.DATE_SPECIFIC -> 1
            MeetingKind.UNSCHEDULED -> 2
        },
        day = if (meeting.kind == MeetingKind.RECURRING) meeting.day else 0,
        date = meeting.dates.minOrNull() ?: LocalDate.MAX,
        start = intervals.firstOrNull()?.start ?: meeting.customTime?.start ?: LocalTime.MAX,
        minSection = meeting.sections.minOrNull() ?: Int.MAX_VALUE,
        minWeek = meeting.weeks.minOrNull() ?: Int.MAX_VALUE,
    )
}

private fun scheduleText(meeting: CourseMeeting): String = when (meeting.kind) {
    MeetingKind.RECURRING -> buildString {
        append("周")
        append(weekdays.getOrNull(meeting.day - 1) ?: "未指定")
        append(" · ")
        append(if (meeting.weeks.isEmpty()) "周次未设置" else "第 ${formatNumberSet(meeting.weeks)} 周")
    }
    // A multi-date meeting stays one row and lists every stored date.
    MeetingKind.DATE_SPECIFIC -> "指定日期 " + meeting.dates.sorted().joinToString("、")
    MeetingKind.UNSCHEDULED -> "待排期 · 尚不产生上课记录"
}

private fun sectionsText(meeting: CourseMeeting): String = when {
    meeting.customTime != null -> "自定义时间"
    meeting.sections.isNotEmpty() -> "第 ${formatNumberSet(meeting.sections)} 节"
    meeting.kind == MeetingKind.UNSCHEDULED -> "节次待定"
    else -> "节次未设置"
}

/** A meeting-level override wins; a blank override falls back to the course default. */
private fun effectiveText(override: String?, fallback: String): String =
    override?.trim()?.takeIf { it.isNotEmpty() } ?: fallback.trim()

/** Exceptions are reported next to the arrangement; they are never counted as extra arrangements. */
private fun describeException(exception: MeetingException): String {
    val label = when (exception.kind) {
        ExceptionKind.CANCEL -> "停课"
        ExceptionKind.RESCHEDULE -> "调课"
        ExceptionKind.EXTRA -> "加课"
    }
    val dates = when (exception.kind) {
        ExceptionKind.CANCEL -> exception.originalDate?.toString().orEmpty()
        ExceptionKind.RESCHEDULE -> "${exception.originalDate} → ${exception.targetDate}"
        ExceptionKind.EXTRA -> exception.targetDate?.toString().orEmpty()
    }
    val overrides = buildList {
        exception.sections?.takeIf { it.isNotEmpty() }?.let { add("第 ${formatNumberSet(it)} 节") }
        exception.customTime?.let { add("${it.start}–${it.end}") }
        exception.teacher?.trim()?.takeIf { it.isNotEmpty() }?.let { add("教师 $it") }
        exception.room?.trim()?.takeIf { it.isNotEmpty() }?.let { add("地点 $it") }
        exception.experimentProject?.trim()?.takeIf { it.isNotEmpty() }?.let { add("项目 $it") }
        exception.group?.trim()?.takeIf { it.isNotEmpty() }?.let { add("分组 $it") }
    }
    return if (overrides.isEmpty()) "$label $dates".trim() else "$label $dates（${overrides.joinToString("，")}）"
}