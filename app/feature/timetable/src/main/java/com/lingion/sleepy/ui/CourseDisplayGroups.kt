/* SPDX-License-Identifier: GPL-3.0-only
 * Pure logic for user-confirmed display groups: binding signatures, validity, conflicts and edits.
 * Nothing here writes courses; a group is only a display relation between persisted records.
 */
package com.lingion.sleepy.ui

import com.lingion.sleepy.data.COURSE_DISPLAY_GROUP_MEMBERS_MAX
import com.lingion.sleepy.data.COURSE_DISPLAY_GROUP_NAME_MAX
import com.lingion.sleepy.data.CourseDisplayGroup
import com.lingion.sleepy.data.CourseDisplayGroupKey
import com.lingion.sleepy.data.CourseGroupMember
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.ScheduledCourse
import java.security.MessageDigest

/** Scope plus generation that a write was computed against; stale values are rejected by the session. */
internal data class GroupWriteScope(val key: CourseDisplayGroupKey, val generation: Long)

/** A manual group whose members are still present and unchanged in the current snapshot. */
internal data class EffectiveManualGroup(
    val id: String,
    val name: String,
    val courses: List<ScheduledCourse>,
)

internal data class ManualGroupReconcile(
    val effective: List<EffectiveManualGroup>,
    /** Members whose record is gone or was replaced by an unrelated record (id reuse). */
    val invalidMemberCount: Int,
    /** Groups that dropped below two valid members and therefore are not applied. */
    val staleGroupCount: Int,
)

/**
 * A display/maintenance view bound to the scope and generation it was computed for, plus the
 * snapshot revision used to resolve it. Writes and maintenance must carry this binding.
 */
internal data class ManualGroupView(
    val scope: GroupWriteScope,
    val groups: List<CourseDisplayGroup>,
    val reconcile: ManualGroupReconcile,
    val snapshotRevision: Long,
)

/**
 * Builds the manual view only when the loaded configuration belongs to the scope currently shown.
 * A configuration loaded for another profile/semester, a failed read or a loading timetable yields
 * null, so the list falls back to the conservative automatic rules instead of applying foreign groups.
 */
internal fun manualGroupView(
    loaded: Boolean,
    loadedKey: CourseDisplayGroupKey?,
    generation: Long,
    groups: List<CourseDisplayGroup>,
    currentKey: CourseDisplayGroupKey,
    courses: List<ScheduledCourse>,
    snapshotRevision: Long,
    unavailable: Boolean,
): ManualGroupView? {
    if (unavailable || !loaded || loadedKey != currentKey) return null
    return ManualGroupView(
        scope = GroupWriteScope(currentKey, generation),
        groups = groups,
        reconcile = reconcileManualGroups(groups, courses),
        snapshotRevision = snapshotRevision,
    )
}

/** Identifies one maintenance attempt, so a failed write is never retried by the same input state. */
internal data class PruneAttempt(val key: CourseDisplayGroupKey, val generation: Long, val revision: Long)

/**
 * Allows at most one stale-binding cleanup per (scope, generation, snapshot revision). A failed
 * attempt is remembered, so a state change is required — a new load, an explicit retry or a new
 * snapshot — before maintenance runs again.
 */
internal class PruneAttemptTracker {
    private var attempted: PruneAttempt? = null

    fun begin(view: ManualGroupView): Boolean {
        if (view.reconcile.invalidMemberCount == 0) return false
        val attempt = PruneAttempt(view.scope.key, view.scope.generation, view.snapshotRevision)
        if (attempt == attempted) return false
        attempted = attempt
        return true
    }
}

/** Plain signatures are kept as-is (compatible with stored v1 values); only long identities are digested. */
private const val SIGNATURE_PLAIN_MAX = 400

/**
 * Identity snapshot of one persisted record for manual grouping. It only distinguishes "the same
 * record" from "an unrelated record that happens to reuse the id": renaming, recoding or moving the
 * record between classes makes the old binding stale, while changing times, rooms or teachers keeps it.
 *
 * A very long but legitimate name/code/class is hashed over a length-prefixed, unambiguous encoding
 * so the binding stays bounded without truncating any field. Short identities keep the plain form,
 * so configurations written before this change stay readable.
 */
fun computeCourseDisplaySignature(
    courseName: String,
    sourceCourseCode: String?,
    sourceClassCode: String?,
): String {
    val name = normalizedCourseName(courseName)
    val code = normalizedSourceCode(sourceCourseCode).orEmpty()
    val classCode = normalizedSourceCode(sourceClassCode).orEmpty()
    val plain = "$name|$code|$classCode"
    if (plain.length <= SIGNATURE_PLAIN_MAX) return plain
    val encoded = "${name.length}:$name|${code.length}:$code|${classCode.length}:$classCode"
    return "d1:" + MessageDigest.getInstance("SHA-256")
        .digest(encoded.toByteArray(Charsets.UTF_8))
        .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}

internal fun courseDisplaySignature(course: ScheduledCourse): String {
    return computeCourseDisplaySignature(course.courseName, course.sourceCourseCode, course.sourceClassCode)
}

/**
 * Resolves stored groups against the current snapshot. Invalid members are only hidden, never
 * deleted from storage here, so a momentarily empty or failed snapshot can never erase user data.
 */
internal fun reconcileManualGroups(
    groups: List<CourseDisplayGroup>,
    courses: List<ScheduledCourse>,
): ManualGroupReconcile {
    val byId = courses.groupBy { it.id }
    var invalidMembers = 0
    var staleGroups = 0
    val effective = groups.mapNotNull { group ->
        val members = group.members.mapNotNull { member ->
            byId[member.courseId]?.firstOrNull { courseDisplaySignature(it) == member.signature }
        }
        invalidMembers += group.members.size - members.size
        if (members.size < 2) {
            staleGroups++
            null
        } else {
            EffectiveManualGroup(group.id, group.name, members)
        }
    }
    return ManualGroupReconcile(effective, invalidMembers, staleGroups)
}

internal data class GroupMemberConflict(val courseId: Long, val groupId: String, val groupName: String)

/** A record may only belong to one manual group; the caller must confirm any cross-group move. */
internal fun findMemberConflicts(
    groups: List<CourseDisplayGroup>,
    courseIds: Collection<Long>,
    editingGroupId: String?,
): List<GroupMemberConflict> {
    val wanted = courseIds.toSet()
    return groups.asSequence()
        .filter { it.id != editingGroupId }
        .flatMap { group -> group.members.filter { it.courseId in wanted }.map { GroupMemberConflict(it.courseId, group.id, group.name) } }
        .sortedBy { it.courseId }
        .toList()
}

internal sealed interface GroupSaveResult {
    data class Saved(val groups: List<CourseDisplayGroup>) : GroupSaveResult
    data class Rejected(val conflicts: List<GroupMemberConflict>) : GroupSaveResult
    /** The write was computed for an older scope, generation or snapshot and must be recomputed. */
    data object Stale : GroupSaveResult
    data object Invalid : GroupSaveResult
}

/**
 * Creates or updates one manual group. Members of other groups are only moved when [allowMove] is
 * true, which the UI sets after the user explicitly confirmed the move. A group that falls below two
 * members is dropped, so its remaining records return to the conservative automatic rules.
 */
internal fun upsertManualGroup(
    groups: List<CourseDisplayGroup>,
    editingGroupId: String?,
    groupId: String,
    name: String,
    members: List<CourseGroupMember>,
    allowMove: Boolean,
): GroupSaveResult {
    val trimmedName = name.trim()
    val distinctIds = members.map { it.courseId }.distinct()
    if (trimmedName.isEmpty() || trimmedName.length > COURSE_DISPLAY_GROUP_NAME_MAX) return GroupSaveResult.Invalid
    if (members.size < 2 || distinctIds.size != members.size) return GroupSaveResult.Invalid
    if (members.size > COURSE_DISPLAY_GROUP_MEMBERS_MAX) return GroupSaveResult.Invalid
    val conflicts = findMemberConflicts(groups, distinctIds, editingGroupId)
    if (conflicts.isNotEmpty() && !allowMove) return GroupSaveResult.Rejected(conflicts)
    val others = groups.filter { it.id != editingGroupId }.mapNotNull { other ->
        val kept = other.members.filterNot { member -> member.courseId in distinctIds }
        when {
            kept.size == other.members.size -> other
            kept.size >= 2 -> other.copy(members = kept)
            else -> null
        }
    }
    val updated = CourseDisplayGroup(
        id = groupId,
        name = trimmedName,
        members = members.map { CourseGroupMember(it.courseId, it.signature) },
    )
    return GroupSaveResult.Saved((others + updated).sortedWith(compareBy({ it.name }, { it.id })))
}

/** Removing a group only drops the display relation; courses and arrangements are untouched. */
internal fun removeManualGroup(groups: List<CourseDisplayGroup>, groupId: String): List<CourseDisplayGroup> =
    groups.filterNot { it.id == groupId }

/** Removing one member drops the group when fewer than two records remain. */
internal fun removeManualMember(
    groups: List<CourseDisplayGroup>,
    groupId: String,
    courseId: Long,
): List<CourseDisplayGroup> = groups.mapNotNull { group ->
    if (group.id != groupId) return@mapNotNull group
    val kept = group.members.filterNot { it.courseId == courseId }
    if (kept.size >= 2) group.copy(members = kept) else null
}

/**
 * Drops bindings that no longer match a persisted record and removes groups left with fewer than two
 * members. Returns null when nothing changes.
 *
 * Callers may only apply this to a successfully loaded, non-empty snapshot: an empty list, a loading
 * state or a failed read must never be treated as "everything was deleted", and the courses
 * themselves are never touched.
 */
internal fun pruneInvalidMembers(
    groups: List<CourseDisplayGroup>,
    courses: List<ScheduledCourse>,
): List<CourseDisplayGroup>? {
    if (courses.isEmpty()) return null
    val byId = courses.groupBy { it.id }
    var changed = false
    val kept = groups.mapNotNull { group ->
        val valid = group.members.filter { member ->
            byId[member.courseId]?.any { courseDisplaySignature(it) == member.signature } == true
        }
        when {
            valid.size == group.members.size -> group
            valid.size >= 2 -> {
                changed = true
                group.copy(members = valid)
            }
            else -> {
                changed = true
                null
            }
        }
    }
    return if (changed) kept else null
}

/** A short, non-authoritative summary used only to tell records apart in the organize screen. */
internal fun courseRecordSummary(course: ScheduledCourse, periods: List<Period>): String {
    val rows = orderedMeetingRows(course, periods)
    if (rows.isEmpty()) return "无安排"
    val first = rows.take(2).joinToString("；") { "${it.kindLabel} ${it.scheduleText}" }
    return if (rows.size > 2) "$first 等 ${rows.size} 条" else first
}