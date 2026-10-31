/* SPDX-License-Identifier: GPL-3.0-only
 * Organize screen: pick persisted course records once and confirm a display-only group.
 * Nothing is selected or saved automatically, and cancelling writes nothing.
 */
package com.lingion.sleepy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingion.sleepy.data.COURSE_DISPLAY_GROUP_MEMBERS_MAX
import com.lingion.sleepy.data.COURSE_DISPLAY_GROUP_NAME_MAX
import com.lingion.sleepy.data.CourseDisplayGroup
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimetableSnapshot

private data class CourseCandidate(
    val course: ScheduledCourse,
    val summary: String,
    val currentGroup: CourseDisplayGroup?,
)

@Composable
internal fun CourseGroupEditor(
    snapshot: TimetableSnapshot,
    groups: List<CourseDisplayGroup>,
    initialGroupId: String?,
    saving: Boolean,
    error: String?,
    enabled: Boolean,
    onSave: (editingGroupId: String?, name: String, courseIds: List<Long>, allowMove: Boolean) -> Unit,
    onRemoveGroup: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val coursesById = remember(snapshot.courses) { snapshot.courses.associateBy { it.id } }
    val initialGroup = remember(initialGroupId) { groups.firstOrNull { it.id == initialGroupId } }
    var editingId by rememberSaveable(initialGroupId) { mutableStateOf(initialGroupId) }
    var selected by rememberSaveable(initialGroupId) {
        // Records that no longer exist are never preselected; they are reported by the existing-group row.
        mutableStateOf<List<Long>>(initialGroup?.members?.map { it.courseId }?.filter { it in coursesById }.orEmpty())
    }
    var name by rememberSaveable(initialGroupId) { mutableStateOf(initialGroup?.name.orEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmRemoveId by remember { mutableStateOf<String?>(null) }
    var pendingConflicts by remember { mutableStateOf<List<GroupMemberConflict>>(emptyList()) }

    val groupOfCourse = remember(groups) { groups.flatMap { group -> group.members.map { it.courseId to group } }.toMap() }
    val candidates = remember(snapshot.courses, snapshot.periods, query, groupOfCourse) {
        val needle = query.trim().lowercase()
        snapshot.courses.filter { course ->
            needle.isEmpty() || searchableText(course).contains(needle)
        }.map { course ->
            CourseCandidate(course, courseRecordSummary(course, snapshot.periods), groupOfCourse[course.id])
        }
    }
    val tooManyMembers = selected.size > COURSE_DISPLAY_GROUP_MEMBERS_MAX
    val canSave = enabled && !saving && selected.size >= 2 && !tooManyMembers &&
        name.isNotBlank() && name.trim().length <= COURSE_DISPLAY_GROUP_NAME_MAX

    // The default name is only filled while the user has not typed one.
    LaunchedEffect(selected) {
        if (name.isBlank() && selected.isNotEmpty()) {
            name = coursesById[selected.first()]?.courseName?.trim()?.take(COURSE_DISPLAY_GROUP_NAME_MAX).orEmpty()
        }
    }

    BackHandler(enabled = !saving) { onDismiss() }

    fun toggle(courseId: Long) {
        selected = if (courseId in selected) selected - courseId else selected + courseId
    }

    fun requestSave(allowMove: Boolean) {
        val conflicts = if (allowMove) emptyList() else findMemberConflicts(groups, selected, editingId)
        if (conflicts.isEmpty()) {
            onSave(editingId, name, selected, allowMove)
            pendingConflicts = emptyList()
        } else {
            pendingConflicts = conflicts
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("整理课程", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !saving) { Text("关闭") }
            }
            Text(
                "选择两条以上记录作为同一门课；只影响本页分组，不改变课程和安排。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (groups.isNotEmpty()) {
                item(key = "existing-title") {
                    Text("已有分组", style = MaterialTheme.typography.titleSmall)
                }
                items(groups, key = { "existing:" + it.id }) { group ->
                    ExistingGroupRow(
                        group = group,
                        validCount = group.members.count { member ->
                            coursesById[member.courseId]?.let { courseDisplaySignature(it) == member.signature } == true
                        },
                        saving = saving,
                        onEdit = {
                            editingId = group.id
                            selected = group.members.map { it.courseId }.filter { it in coursesById }
                            name = group.name
                        },
                        onRemove = { confirmRemoveId = group.id },
                    )
                }
                item(key = "existing-divider") { HorizontalDivider() }
            }
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索课程名、代码、班级或项目") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "group-name") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(if (editingId == null) "新组名称" else "组名称") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            if (tooManyMembers) {
                                "最多 $COURSE_DISPLAY_GROUP_MEMBERS_MAX 条记录（已选 ${selected.size} 条）"
                            } else {
                                "已选 ${selected.size} 条记录"
                            },
                        )
                    },
                    isError = name.length > COURSE_DISPLAY_GROUP_NAME_MAX || tooManyMembers,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (candidates.isEmpty()) {
                item(key = "no-candidate") { Text("没有匹配的课程记录。") }
            }
            items(candidates, key = { "candidate:" + it.course.id }) { candidate ->
                CandidateRow(
                    candidate = candidate,
                    editingGroupId = editingId,
                    checked = candidate.course.id in selected,
                    onToggle = { toggle(candidate.course.id) },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onDismiss, enabled = !saving, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = { requestSave(allowMove = false) }, enabled = canSave, modifier = Modifier.weight(1f)) {
                Text(if (editingId == null) "保存分组" else "保存修改")
            }
        }
    }

    pendingConflicts.takeIf { it.isNotEmpty() }?.let { conflicts ->
        AlertDialog(
            onDismissRequest = { pendingConflicts = emptyList() },
            title = { Text("课程已在其他分组") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    conflicts.forEach { conflict ->
                        val courseName = coursesById[conflict.courseId]?.courseName?.trim().orEmpty()
                        Text("「$courseName」已在「${conflict.groupName}」中。")
                    }
                    Text("继续会把它们从原分组移出，并加入当前分组。")
                }
            },
            confirmButton = {
                TextButton(onClick = { pendingConflicts = emptyList(); onSave(editingId, name, selected, true) }) {
                    Text("移出并加入")
                }
            },
            dismissButton = { TextButton(onClick = { pendingConflicts = emptyList() }) { Text("取消") } },
        )
    }

    confirmRemoveId?.let { groupId ->
        val groupName = groups.firstOrNull { it.id == groupId }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { confirmRemoveId = null },
            title = { Text("解除分组？") },
            text = { Text("只解除「$groupName」的展示关系，课程和安排不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemoveId = null
                        onRemoveGroup(groupId)
                        if (editingId == groupId) {
                            editingId = null
                            selected = emptyList()
                            name = ""
                        }
                    },
                ) { Text("解除") }
            },
            dismissButton = { TextButton(onClick = { confirmRemoveId = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ExistingGroupRow(
    group: CourseDisplayGroup,
    validCount: Int,
    saving: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(group.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append("${group.members.size} 条记录")
                    if (validCount < group.members.size) append(" · ${group.members.size - validCount} 条已失效")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
        TextButton(onClick = onEdit, enabled = !saving) { Text("调整") }
        TextButton(onClick = onRemove, enabled = !saving) { Text("解除") }
    }
}

@Composable
private fun CandidateRow(
    candidate: CourseCandidate,
    editingGroupId: String?,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val course = candidate.course
    val otherGroup = candidate.currentGroup?.takeIf { it.id != editingGroupId }
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Text(
                    course.courseName.ifBlank { "未命名课程" },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                courseMetaLine(course).takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(candidate.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                otherGroup?.let {
                    Text(
                        "已在「${it.name}」中",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private fun searchableText(course: ScheduledCourse): String = buildString {
    append(course.courseName)
    append('\n')
    append(course.sourceCourseCode.orEmpty())
    append('\n')
    append(course.sourceClassCode.orEmpty())
    append('\n')
    append(course.teacher)
    append('\n')
    append(course.room)
    course.meetings.forEach { meeting ->
        append('\n')
        append(meeting.experimentProject.orEmpty())
        append('\n')
        append(meeting.group.orEmpty())
    }
}.lowercase()

private fun courseMetaLine(course: ScheduledCourse): String = buildList {
    course.sourceCourseCode?.trim()?.takeIf { it.isNotEmpty() }?.let { add("代码 $it") }
    course.sourceClassCode?.trim()?.takeIf { it.isNotEmpty() }?.let { add("班级 $it") }
    course.teacher.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
    course.room.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
    course.meetings.mapNotNull { it.experimentProject?.trim()?.takeIf(String::isNotEmpty) }.distinct()
        .takeIf { it.isNotEmpty() }?.let { add("项目 " + it.joinToString("、")) }
}.joinToString(" · ")