/* SPDX-License-Identifier: GPL-3.0-only
 * New Stage 2 orchestration; existing host remains the sole NavHost/Hilt owner.
 */
package com.lingion.sleepy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.data.CourseDisplayGroupKey
import com.lingion.sleepy.data.SharedPreferencesCourseDisplayGroupStore
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
    var noticeAdjustmentOpen by rememberSaveable { mutableStateOf(false) }
    var noticeHistoryOpen by rememberSaveable { mutableStateOf(false) }
    var manualOccurrenceOpen by rememberSaveable { mutableStateOf(false) }
    var manualOccurrenceCourseId by rememberSaveable { mutableStateOf<Long?>(null) }
    var manualOccurrenceInitialMode by rememberSaveable { mutableStateOf(ManualOccurrenceMode.ADJUST) }
    val noticeRepo = dependencies.noticeAdjustments
    if (manualOccurrenceOpen && noticeRepo != null && manualOccurrenceCourseId != null) {
        ManualOccurrenceScreen(
            dependencies = dependencies,
            courseId = manualOccurrenceCourseId!!,
            initialMode = manualOccurrenceInitialMode,
            onClose = {
                manualOccurrenceOpen = false
                manualOccurrenceCourseId = null
            }
        )
        return
    }
    if (noticeAdjustmentOpen && noticeRepo != null) {
        NoticeAdjustmentScreen(
            dependencies = dependencies,
            onNavigateToHistory = {
                noticeAdjustmentOpen = false
                noticeHistoryOpen = true
            },
            onClose = { noticeAdjustmentOpen = false }
        )
        return
    }
    if (noticeHistoryOpen && noticeRepo != null) {
        NoticeAdjustmentHistoryScreen(
            dependencies = dependencies,
            onClose = { noticeHistoryOpen = false }
        )
        return
    }
    val importRepository = dependencies.imports
    if (importOpen && importRepository != null) {
        ImportFlowScreen(
            repository = importRepository,
            onNavigateToNoticeAdjustment = if (noticeRepo != null) {
                {
                    importOpen = false
                    noticeAdjustmentOpen = true
                }
            } else null,
            onClose = { importOpen = false }
        )
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
    // Keeps the course card expansion state while the tab is not composed.
    val courseStateHolder=rememberSaveableStateHolder()
    var periodEditorOpen by rememberSaveable {mutableStateOf(false)}
    val canWrite = !state.isSaving && !state.isLoading && !state.hasReadError
    // Display-only grouping: a separate preference file, never part of the timetable database.
    val appContext = LocalContext.current.applicationContext
    val displayGroupStore = remember(appContext) { SharedPreferencesCourseDisplayGroupStore(appContext) }
    val displayGroupScope = rememberCoroutineScope()
    val displayGroupKey = CourseDisplayGroupKey(state.snapshot.scopeToken, state.snapshot.settings.id)
    val groupSession = remember(displayGroupStore, displayGroupScope) {
        CourseDisplayGroupSession(displayGroupStore, displayGroupScope, initialKey = displayGroupKey)
    }
    LaunchedEffect(groupSession, displayGroupKey) { groupSession.activate(displayGroupKey) }
    val groupsState by groupSession.state.collectAsStateWithLifecycle()
    // A display/maintenance view is only valid for the scope and generation it was computed for.
    val manualView = remember(
        displayGroupKey,
        groupsState.loaded,
        groupsState.loadedKey,
        groupsState.generation,
        groupsState.groups,
        state.snapshot.courses,
        state.snapshot.revision,
        state.isLoading,
        state.hasReadError,
    ) {
        manualGroupView(
            loaded = groupsState.loaded,
            loadedKey = groupsState.loadedKey,
            generation = groupsState.generation,
            groups = groupsState.groups,
            currentKey = displayGroupKey,
            courses = state.snapshot.courses,
            snapshotRevision = state.snapshot.revision,
            unavailable = state.isLoading || state.hasReadError,
        )
    }
    var groupEditorOpen by rememberSaveable { mutableStateOf(false) }
    var groupEditorTarget by rememberSaveable { mutableStateOf<String?>(null) }
    // The write scope is captured when the organizer opens, so a later scope switch or reload cannot
    // apply this editor's selection to another scope's configuration.
    var editorScopeToken by rememberSaveable { mutableStateOf<String?>(null) }
    var editorSemester by rememberSaveable { mutableStateOf(0L) }
    var editorGeneration by rememberSaveable { mutableStateOf(-1L) }
    fun editorWriteScope(): GroupWriteScope? {
        val capturedToken = editorScopeToken ?: return null
        val capturedGeneration = editorGeneration
        if (capturedGeneration < 0) return null
        return GroupWriteScope(CourseDisplayGroupKey(capturedToken, editorSemester), capturedGeneration)
    }
    // Deleted or replaced records are cleaned from the stored groups, but only from a successfully
    // loaded, non-empty snapshot, never while a save is in flight, and at most once per
    // (scope, generation, snapshot revision) so a failed write cannot loop.
    val pruneTracker = remember { PruneAttemptTracker() }
    LaunchedEffect(manualView, groupsState.saving) {
        val view = manualView ?: return@LaunchedEffect
        if (groupsState.saving) return@LaunchedEffect
        if (state.snapshot.revision != view.snapshotRevision) return@LaunchedEffect
        if (!pruneTracker.begin(view)) return@LaunchedEffect
        val pruned = pruneInvalidMembers(view.groups, state.snapshot.courses) ?: return@LaunchedEffect
        groupSession.persistIfCurrent(pruned, view.scope)
    }
    LaunchedEffect(groupsState.savedSerial) {
        if (groupsState.savedSerial > 0) {
            groupEditorOpen = false
            groupEditorTarget = null
        }
    }
    if (groupEditorOpen && !state.isLoading && !state.hasReadError) {
        CourseGroupEditor(
            snapshot = state.snapshot,
            groups = groupsState.groups,
            initialGroupId = groupEditorTarget,
            saving = groupsState.saving,
            error = groupsState.error,
            enabled = canWrite,
            onSave = { editingGroupId, name, courseIds, allowMove ->
                groupSession.saveGroup(
                    editingGroupId = editingGroupId,
                    name = name,
                    courseIds = courseIds,
                    courses = state.snapshot.courses,
                    allowMove = allowMove,
                    expected = editorWriteScope(),
                )
            },
            onRemoveGroup = { groupSession.removeGroup(it, editorWriteScope()) },
            onDismiss = {
                if (!groupsState.saving) {
                    groupEditorOpen = false
                    groupEditorTarget = null
                }
            },
        )
        return
    }
    if(periodEditorOpen && !state.isLoading && !state.hasReadError) {
        PeriodTableEditor(state.snapshot,canWrite,state.isSaving,state.errorMessage,model::saveSettings) {periodEditorOpen=false}
        return
    }
    Column(modifier.fillMaxSize().testTag("timetable-page")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SectionHeader("本机课表", "课程仅保存在本机，可离线查看。")
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (importRepository != null) {
                    TextButton(
                        onClick = { importOpen = true },
                        enabled = !editorOpen && !state.isSaving,
                        modifier = Modifier.testTag("timetable-btn-import")
                    ) {
                        Text("导入课表")
                    }
                }
                if (noticeRepo != null) {
                    TextButton(
                        onClick = { noticeAdjustmentOpen = true },
                        enabled = !editorOpen && !state.isSaving,
                        modifier = Modifier.testTag("timetable-btn-notice-adjustment")
                    ) {
                        Text("文字通知调课")
                    }
                    TextButton(
                        onClick = { noticeHistoryOpen = true },
                        enabled = !editorOpen && !state.isSaving,
                        modifier = Modifier.testTag("timetable-btn-notice-history")
                    ) {
                        Text("调整记录")
                    }
                }
            }
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
                    2 -> courseStateHolder.SaveableStateProvider("courses") {
                        CourseManagementPane(
                            snapshot = state.snapshot,
                            enabled = canWrite,
                            organizeEnabled = canWrite && !groupsState.loading && (groupsState.loaded || groupsState.error != null),
                            manualGroups = manualView?.reconcile?.effective.orEmpty(),
                            groupsError = groupsState.error,
                            onAdd = { openEditor(null) },
                            onOrganize = {
                                model.clearMessage()
                                groupEditorTarget = null
                                val writeScope = groupSession.currentWriteScope
                                editorScopeToken = writeScope?.key?.scopeToken
                                editorSemester = writeScope?.key?.semesterId ?: 0L
                                editorGeneration = writeScope?.generation ?: -1L
                                groupEditorOpen = true
                            },
                            onRetryGroups = { groupSession.retry() },
                            onCourse = { selectedId = it.id },
                        )
                    }
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
            CourseDetails(
                course = selected,
                snapshot = state.snapshot,
                onClose = { selectedId = null },
                onEdit = { openEditor(selected) },
                onDelete = { model.clearMessage(); confirmDelete = true },
                onManualOccurrence = if (noticeRepo != null) { mode ->
                    manualOccurrenceCourseId = selected.id
                    manualOccurrenceInitialMode = mode
                    manualOccurrenceOpen = true
                    selectedId = null
                } else null,
                enabled = canWrite
            )
        }
    }
}

@Composable
private fun CourseDetails(
    course: ScheduledCourse,
    snapshot: TimetableSnapshot,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onManualOccurrence: ((ManualOccurrenceMode) -> Unit)? = null,
    enabled: Boolean
) {
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

                if (onManualOccurrence != null) {
                    HorizontalDivider()
                    Text("单次操作（仅此一次，不影响周期规则）：", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onManualOccurrence(ManualOccurrenceMode.ADJUST) },
                            modifier = Modifier.weight(1f).testTag("course-details-btn-adjust-once"),
                            enabled = enabled,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Text("调整一次", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { onManualOccurrence(ManualOccurrenceMode.CANCEL) },
                            modifier = Modifier.weight(1f).testTag("course-details-btn-cancel-once"),
                            enabled = enabled,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Text("停课一次", style = MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(
                            onClick = { onManualOccurrence(ManualOccurrenceMode.EXTRA) },
                            modifier = Modifier.weight(1f).testTag("course-details-btn-extra-once"),
                            enabled = enabled,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Text("补课一次", style = MaterialTheme.typography.labelSmall)
                        }
                    }
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
