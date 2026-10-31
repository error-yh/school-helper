/* SPDX-License-Identifier: GPL-3.0-only
 * State holder for the persisted, display-only course grouping.
 * It never writes courses: only the separate grouping configuration is loaded and saved.
 */
package com.lingion.sleepy.ui

import com.lingion.sleepy.data.COURSE_DISPLAY_GROUP_MEMBERS_MAX
import com.lingion.sleepy.data.CourseDisplayGroup
import com.lingion.sleepy.data.CourseDisplayGroupKey
import com.lingion.sleepy.data.CourseDisplayGroupRead
import com.lingion.sleepy.data.CourseDisplayGroupStore
import com.lingion.sleepy.data.CourseDisplayGroupWrite
import com.lingion.sleepy.data.CourseGroupMember
import com.lingion.sleepy.domain.model.ScheduledCourse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

internal data class CourseDisplayGroupsUiState(
    val loading: Boolean = false,
    /** True only after a successful read; a failed read never counts as an empty configuration. */
    val loaded: Boolean = false,
    /** The scope whose configuration occupies [groups]; a different scope never sees it. */
    val loadedKey: CourseDisplayGroupKey? = null,
    /**
     * Monotonic version of [groups]. A display or maintenance result computed for an older
     * generation is stale, even when the scope itself did not change. The value is drawn from a
     * session-lifetime counter and is never reset or reused, so re-entering a scope after a round trip
     * cannot make an old [GroupWriteScope] valid again.
     */
    val generation: Long = 0,
    val groups: List<CourseDisplayGroup> = emptyList(),
    val error: String? = null,
    val saving: Boolean = false,
    /** Incremented after a successful save so the editor can close itself. */
    val savedSerial: Long = 0,
)

/**
 * Loads and saves the grouping configuration for exactly one scope and semester.
 *
 * A late result of another scope can never be applied or written: every asynchronous request
 * captures the key and a generation token, and results are dropped when either moved on. Writes also
 * carry the [GroupWriteScope] the caller computed them for, so a result prepared for one scope,
 * generation or snapshot is rejected instead of landing in another entry. The generation comes from a
 * session-lifetime counter that scope switches never reset, so A→B→A cannot revive an old write
 * scope. The store keys entries per scope, so even an already started write can only touch its own
 * scope's entry.
 */
internal class CourseDisplayGroupSession(
    private val store: CourseDisplayGroupStore,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val groupIdProvider: () -> String = { UUID.randomUUID().toString() },
    initialKey: CourseDisplayGroupKey,
) {
    private var key = initialKey
    private var token = 0
    /** Never reset by a scope switch; every value handed out is final and cannot be reused. */
    private var generationCounter = 0L
    private val mutableState = MutableStateFlow(CourseDisplayGroupsUiState())
    val state: StateFlow<CourseDisplayGroupsUiState> = mutableState.asStateFlow()

    private fun nextGeneration(): Long = ++generationCounter

    val activeKey: CourseDisplayGroupKey get() = key

    /**
     * The binding a write must echo back. Null while the configuration for the active scope is still
     * loading or was never attempted; a failed read still allows an explicit re-organize to replace it.
     */
    val currentWriteScope: GroupWriteScope?
        get() = mutableState.value.let { current ->
            val ready = !current.loading && (current.loaded || current.error != null)
            if (ready) GroupWriteScope(key, current.generation) else null
        }

    /** Switches scope when needed and loads once; repeated calls with the same scope do not reload. */
    fun activate(newKey: CourseDisplayGroupKey) {
        if (newKey != key) {
            key = newKey
            token++
            // The UI state is reset, but the identity version is not: a scope switch must not make an
            // earlier GroupWriteScope for this scope valid again.
            mutableState.value = CourseDisplayGroupsUiState(generation = nextGeneration())
        }
        if (!mutableState.value.loaded && !mutableState.value.loading) load()
    }

    fun load() {
        val requestKey = key
        val requestToken = ++token
        mutableState.update { it.copy(loading = true) }
        scope.launch {
            val result = withContext(ioDispatcher) { store.read(requestKey) }
            if (requestToken != token || requestKey != key) return@launch
            val next = nextGeneration()
            mutableState.update { current ->
                when (result) {
                    is CourseDisplayGroupRead.Loaded -> current.copy(
                        loading = false,
                        loaded = true,
                        loadedKey = requestKey,
                        generation = next,
                        groups = result.groups,
                        error = null,
                    )
                    CourseDisplayGroupRead.Empty -> current.copy(
                        loading = false,
                        loaded = true,
                        loadedKey = requestKey,
                        generation = next,
                        groups = emptyList(),
                        error = null,
                    )
                    is CourseDisplayGroupRead.Failed -> current.copy(
                        loading = false,
                        loaded = false,
                        loadedKey = null,
                        generation = next,
                        groups = emptyList(),
                        error = result.message,
                    )
                }
            }
        }
    }

    fun retry() {
        if (!mutableState.value.loading) load()
    }

    /** Creates or updates a group from the current selection; only a successful write is reported. */
    fun saveGroup(
        editingGroupId: String?,
        name: String,
        courseIds: List<Long>,
        courses: List<ScheduledCourse>,
        allowMove: Boolean,
        expected: GroupWriteScope?,
    ): GroupSaveResult {
        if (expected == null) return stale("整理页面尚未加载完成，请稍后重试。")
        if (expected != currentWriteScope) return stale("整理页面已过期，请重新打开后再保存。")
        val distinctIds = courseIds.distinct()
        if (distinctIds.size > COURSE_DISPLAY_GROUP_MEMBERS_MAX) {
            return invalid("一个分组最多包含 $COURSE_DISPLAY_GROUP_MEMBERS_MAX 条课程记录。")
        }
        val members = try {
            distinctIds.mapNotNull { id ->
                courses.firstOrNull { it.id == id }?.let { course -> CourseGroupMember(id, courseDisplaySignature(course)) }
            }
        } catch (_: IllegalArgumentException) {
            return invalid("课程记录信息过长，暂时无法加入分组。")
        }
        if (members.size < 2) return invalid("请至少选择两条现有课程记录。")
        val upsert = try {
            upsertManualGroup(
                groups = mutableState.value.groups,
                editingGroupId = editingGroupId,
                groupId = editingGroupId ?: groupIdProvider(),
                name = name,
                members = members,
                allowMove = allowMove,
            )
        } catch (_: IllegalArgumentException) {
            return invalid("分组信息无效，请检查组名称与所选课程。")
        }
        return when (upsert) {
            is GroupSaveResult.Saved -> {
                // Explicit saves also drop bindings that no longer match a persisted record.
                val cleaned = try {
                    pruneInvalidMembers(upsert.groups, courses)
                } catch (_: IllegalArgumentException) {
                    null
                } ?: upsert.groups
                if (!persist(cleaned, expected, closeEditor = true)) {
                    stale("整理页面已过期，请重新打开后再保存。")
                } else {
                    GroupSaveResult.Saved(cleaned)
                }
            }
            is GroupSaveResult.Rejected -> {
                mutableState.update { it.copy(error = "分组已被其他操作更改，请重新整理后再保存。") }
                upsert
            }
            GroupSaveResult.Invalid -> invalid("请至少选择两条现有课程记录，并填写有效的组名称。")
            GroupSaveResult.Stale -> stale("整理页面已过期，请重新打开后再保存。")
        }
    }

    /** Removing a group only drops the display relation; the persisted courses stay untouched. */
    fun removeGroup(groupId: String, expected: GroupWriteScope?): Boolean {
        if (expected == null || expected != currentWriteScope) {
            stale("整理页面已过期，请重新打开后再操作。")
            return false
        }
        val removed = persist(removeManualGroup(mutableState.value.groups, groupId), expected)
        if (!removed) stale("整理页面已过期，请重新打开后再操作。")
        return removed
    }

    fun removeMember(groupId: String, courseId: Long, expected: GroupWriteScope?): Boolean {
        if (expected == null || expected != currentWriteScope) {
            stale("整理页面已过期，请重新打开后再操作。")
            return false
        }
        val removed = persist(removeManualMember(mutableState.value.groups, groupId, courseId), expected)
        if (!removed) stale("整理页面已过期，请重新打开后再操作。")
        return removed
    }

    /**
     * Writes a maintenance result (for example a stale-binding cleanup) only when the calling
     * composition computed it for the current scope and generation. Returns false when it is stale.
     */
    fun persistIfCurrent(groups: List<CourseDisplayGroup>, expected: GroupWriteScope): Boolean =
        persist(groups, expected)

    private fun persist(
        groups: List<CourseDisplayGroup>,
        expected: GroupWriteScope,
        closeEditor: Boolean = false,
    ): Boolean {
        if (expected.key != key) return false
        val current = mutableState.value
        if (current.generation != expected.generation || current.saving) return false
        val requestKey = expected.key
        val requestToken = ++token
        mutableState.update { it.copy(saving = true, error = null) }
        scope.launch {
            val result = withContext(ioDispatcher) { store.write(requestKey, groups) }
            if (requestToken != token || requestKey != key) return@launch
            val next = nextGeneration()
            mutableState.update { state ->
                when (result) {
                    CourseDisplayGroupWrite.Saved -> state.copy(
                        saving = false,
                        loaded = true,
                        loadedKey = requestKey,
                        generation = next,
                        groups = groups,
                        error = null,
                        savedSerial = if (closeEditor) state.savedSerial + 1 else state.savedSerial,
                    )
                    is CourseDisplayGroupWrite.Failed -> state.copy(saving = false, error = result.message)
                }
            }
        }
        return true
    }

    private fun stale(message: String): GroupSaveResult {
        mutableState.update { it.copy(error = message) }
        return GroupSaveResult.Stale
    }

    private fun invalid(message: String): GroupSaveResult {
        mutableState.update { it.copy(error = message) }
        return GroupSaveResult.Invalid
    }
}