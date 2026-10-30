/* SPDX-License-Identifier: GPL-3.0-only
 * New Stage 2 orchestration; existing host remains the sole NavHost/Hilt owner.
 */
package com.lingion.sleepy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.formatNumberSet
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.ui.component.SectionHeader
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun TimetableRoute(dependencies: TimetableDependencies, modifier: Modifier = Modifier) {
    var importOpen by rememberSaveable { mutableStateOf(false) }
    val importRepository = dependencies.imports
    if (importOpen && importRepository != null) {
        ImportFlowScreen(importRepository) { importOpen = false }
        return
    }
    val factory = remember(dependencies.repository) { TimetableViewModel.Factory(dependencies.repository) }
    val model: TimetableViewModel = viewModel(factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var browsingDate by rememberSaveable { mutableStateOf<String?>(null) }
    var browsingMonday by rememberSaveable { mutableStateOf<String?>(null) }
    var deviceDate by remember { mutableStateOf(LocalDate.now(ZoneId.systemDefault())) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) deviceDate = LocalDate.now(ZoneId.systemDefault())
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            deviceDate = LocalDate.now(ZoneId.systemDefault())
            delay(30_000)
        }
    }
    val date = browsingDate?.let(LocalDate::parse) ?: deviceDate
    val monday = browsingMonday?.let(LocalDate::parse) ?: TimetableRules.mondayOf(deviceDate)
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editorSession by rememberSaveable { mutableIntStateOf(0) }
    val editorStateHolder = rememberSaveableStateHolder()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(state.operationSerial) {
        if (model.consumeCompletedOperation()) {
            when (state.completedOperation) {
                TimetableOperation.COURSE_SAVED -> {
                    editorStateHolder.removeState(editorSession)
                    editorOpen = false; selectedId = null; tab = 2
                }
                TimetableOperation.COURSE_DELETED -> { selectedId = null; confirmDelete = false }
                else -> Unit
            }
        }
    }
    fun openEditor(course: ScheduledCourse?) {
        editorStateHolder.removeState(editorSession)
        model.clearMessage(); editingId = course?.id; selectedId = null
        editorSession++; editorOpen = true
    }
    val settingsStateHolder=rememberSaveableStateHolder()
    var periodEditorOpen by rememberSaveable {mutableStateOf(false)}
    val canWrite = !state.isSaving && !state.isLoading && !state.hasReadError
    if(periodEditorOpen && !state.isLoading && !state.hasReadError) {
        PeriodTableEditor(state.snapshot,canWrite,state.isSaving,state.errorMessage,model::saveSettings) {periodEditorOpen=false}
        return
    }
    Column(modifier.fillMaxSize().testTag("timetable-page")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SectionHeader("本机课表", "课程仅保存在本机，可离线查看。")
            if (importRepository != null) TextButton(onClick = { importOpen = true }, enabled = !editorOpen && !state.isSaving) { Text("导入课表") }
        }
        if (!editorOpen) {
            TabRow(selectedTabIndex = tab) {
                listOf("今日", "本周", "课程", "设置").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
        }
        state.message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.primary) }
        if (!editorOpen && tab != 3) state.errorMessage?.let { ErrorText(it) }
        if (state.hasReadError) {
            Column(Modifier.padding(16.dp)) {
                Text("课表读取失败，已有数据不会被清空。")
                Button(onClick = model::retryLoading, enabled = !state.isLoading) { Text("重新读取") }
            }
        }
        if (state.isLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在读取本机课表…", Modifier.padding(16.dp))
        }
        if (editorOpen && (state.isLoading || state.hasReadError)) {
            Text("正在恢复课程草稿，请先完成课表读取。", Modifier.padding(16.dp))
            TextButton(onClick = { editorStateHolder.removeState(editorSession); editorOpen = false }) { Text("取消编辑") }
        } else if (editorOpen && editingId != null && state.snapshot.courses.none { it.id == editingId }) {
            ErrorText("未找到要编辑的课程，无法保存此草稿。请返回课程列表重新选择。")
            TextButton(onClick = { editorStateHolder.removeState(editorSession); editorOpen = false; editingId = null }) { Text("返回课程列表") }
        } else if (editorOpen) {
            editorStateHolder.SaveableStateProvider(editorSession) {
                CourseEditor(
                    initial = state.snapshot.courses.singleOrNull { it.id == editingId },
                    snapshot = state.snapshot,
                    enabled = canWrite,
                    saving = state.isSaving,
                    error = state.errorMessage,
                    onSave = { model.saveCourse(it, editingId) },
                    onCancel = { if (!state.isSaving) {
                        editorStateHolder.removeState(editorSession); editorOpen = false; model.clearMessage()
                    } },
                    modifier = Modifier.weight(1f),
                )
            }
        } else if (!state.isLoading && !state.hasReadError) {
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> TodayPane(state.snapshot, date, deviceDate,
                        onDate = { browsingDate = it?.toString() }, onCourse = { selectedId = it.id })
                    1 -> WeekPane(state.snapshot, monday,
                        onMonday = { browsingMonday = it?.toString() }, onCourse = { selectedId = it.id })
                    2 -> CourseManagementPane(state.snapshot, canWrite,
                        onAdd = { openEditor(null) }, onCourse = { selectedId = it.id })
                    3 -> settingsStateHolder.SaveableStateProvider("semester") {
                        SettingsEditor(state.snapshot, canWrite, state.isSaving, state.errorMessage,
                        onSave = model::saveSettings,onEditPeriods={model.clearMessage();periodEditorOpen=true})
                    }
                }
            }
        }
    }
    val selected = state.snapshot.courses.singleOrNull { it.id == selectedId }
    if (selected != null && !editorOpen) {
        if (confirmDelete) {
            AlertDialog(onDismissRequest = { if (!state.isSaving) confirmDelete = false },
                title = { Text("删除课程？") },
                text = { Column { Text("将删除「${selected.courseName}」及其全部上课安排。")
                    state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
                confirmButton = { TextButton(onClick = { model.deleteCourse(selected.id) }, enabled = canWrite) { Text("确认删除") } },
                dismissButton = { TextButton(onClick = { confirmDelete = false }, enabled = !state.isSaving) { Text("取消") } })
        } else {
            CourseDetails(selected, state.snapshot, onClose = { selectedId = null },
                onEdit = { openEditor(selected) }, onDelete = { model.clearMessage(); confirmDelete = true }, enabled = canWrite)
        }
    }
}

@Composable
private fun CourseDetails(course: ScheduledCourse, snapshot: TimetableSnapshot,
    onClose: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, enabled: Boolean) {
    AlertDialog(onDismissRequest = onClose,
        title = { Text("课程详情") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(course.courseName, style = MaterialTheme.typography.titleLarge)
                Text("教师：${course.teacher.ifBlank { "未填写" }}")
                Text("地点：${course.room.ifBlank { "未填写" }}")
                course.meetings.forEachIndexed { index, meeting ->
                    HorizontalDivider()
                    Text("安排 ${index + 1} · ${meetingScheduleSummary(meeting)}")
                    Text("节次：${formatNumberSet(meeting.sections).ifEmpty { "自定义" }}")
                    val times = TimetableRules.periodIntervals(meeting, snapshot.periods)
                    Text("时间：${times.joinToString(" / ") { "${it.start}–${it.end}" }.ifEmpty { "未设置" }}${if (meeting.customTime != null) "（自定义）" else ""}")
                    val missing = if (meeting.customTime == null) meeting.sections - snapshot.periods.map { it.number }.toSet() else emptySet()
                    if (missing.isNotEmpty()) Text("未定位节次：${formatNumberSet(missing)}，请在设置中补充作息。", color = MaterialTheme.colorScheme.error)
                    meeting.teacher?.let { Text("本次教师：${it.ifBlank { "未填写" }}") }
                    meeting.room?.let { Text("本次地点：${it.ifBlank { "未填写" }}") }
                    meeting.experimentProject?.let { Text("项目：$it") }
                    meeting.group?.let { Text("分组：$it") }
                    meeting.exceptions.forEach { Text("例外：${it.kind} ${it.originalDate ?: ""} ${it.targetDate ?: ""}") }
                    meeting.provenance.forEach { source -> source.rawText?.let { Text("来源：$it") } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭详情") } },
        dismissButton = { Row { TextButton(onClick = onEdit, enabled = enabled) { Text("编辑课程") }
            TextButton(onClick = onDelete, enabled = enabled) { Text("删除课程") } } })
}

@Composable
internal fun ErrorText(message: String) {
    Text(message, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error)
}
internal val weekdays = listOf("一", "二", "三", "四", "五", "六", "日")
