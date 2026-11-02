/* SPDX-License-Identifier: GPL-3.0-only
 * UI Screen for manual single-occurrence course adjustments (adjust, cancel, extra).
 * Integrates BackHandler for system back navigation, explicit preview and confirmation before writing.
 */
package com.lingion.sleepy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.domain.notice.NoticeOccurrenceView
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualOccurrenceScreen(
    dependencies: TimetableDependencies,
    courseId: Long,
    initialMode: ManualOccurrenceMode = ManualOccurrenceMode.ADJUST,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 拦截物理/手势系统返回键，返回零写入并回到上层
    BackHandler(onBack = onClose)

    // 显式根据 courseId 和 initialMode 绑定 VM key，彻底隔离不同课程和不同操作入口
    val viewModelKey = remember(courseId, initialMode) {
        "manual_occurrence_${courseId}_${initialMode.name}"
    }
    val factory = remember(dependencies, courseId, initialMode) {
        ManualOccurrenceViewModel.Factory(dependencies, courseId, initialMode)
    }
    val viewModel: ManualOccurrenceViewModel = viewModel(key = viewModelKey, factory = factory)
    LaunchedEffect(courseId, initialMode) {
        viewModel.bindCourse(courseId, initialMode)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("单次课程管理", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.courseName} · 仅影响单次",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("manual-occurrence-btn-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize().testTag("manual-occurrence-screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 元数据加载失败提示与重试
            if (state.metadataError != null) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().testTag("manual-metadata-error-card")
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("无法读取课表信息：${state.metadataError}", color = MaterialTheme.colorScheme.onErrorContainer)
                        Button(onClick = viewModel::retryLoadMetadata, modifier = Modifier.testTag("manual-btn-retry-metadata")) {
                            Text("重试加载")
                        }
                    }
                }
            }

            // 操作类型切换（调整一次 / 停课一次 / 补课一次）
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ManualOccurrenceMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ManualOccurrenceMode.entries.size),
                        onClick = { viewModel.setMode(mode) },
                        selected = state.mode == mode,
                        modifier = Modifier.testTag("manual-mode-${mode.name.lowercase()}")
                    ) {
                        Text(mode.label)
                    }
                }
            }

            // 说明条
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when (state.mode) {
                            ManualOccurrenceMode.ADJUST -> "调整一次：仅调整选定日期的该次课时安排（时间/节次/地点），不修改基础课表。具有有条件撤销保障。"
                            ManualOccurrenceMode.CANCEL -> "停课一次：仅取消选定日期的该次课时，整门课程及其它周次安排保持不变。具有有条件撤销保障。"
                            ManualOccurrenceMode.EXTRA -> "补课一次：在指定日期为「${state.courseName}」新增一次课时安排，不改动已有周期安排。"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 原日期选择与候选列表（仅在 ADJUST 与 CANCEL 模式下需要）
            if (state.mode != ManualOccurrenceMode.EXTRA) {
                Text("第一步：选择需要操作的原上课日期与课时", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

                if (state.courseOccurrenceDates.isNotEmpty()) {
                    Text("该课程在学期内的上课日期（点击快速选择）：", style = MaterialTheme.typography.labelMedium)
                    // 上课日期快捷芯片列表
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.courseOccurrenceDates.forEach { date ->
                            FilterChip(
                                selected = state.originalDate == date,
                                onClick = { viewModel.setOriginalDate(date) },
                                label = { Text(date.toString()) },
                                modifier = Modifier.testTag("manual-date-chip-$date")
                            )
                        }
                    }
                }

                // 候选列表展示
                if (state.candidates.isNotEmpty()) {
                    Text("选定日期的课时安排（请明确单选确认）：", style = MaterialTheme.typography.titleSmall)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.candidates.forEach { cand ->
                            val isSelected = state.selectedCandidateId == cand.id
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectCandidate(cand.id) }
                                    .testTag("manual-candidate-${cand.meetingId}"),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = isSelected,
                                        onClick = { viewModel.selectCandidate(cand.id) },
                                        modifier = Modifier.testTag("manual-candidate-radio-${cand.meetingId}")
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            if (cand.currentSections.isNotEmpty()) {
                                                "第${cand.currentSections.sorted().joinToString(",")}节 · ${cand.currentRanges.joinToString { "${it.start}-${it.end}" }.ifEmpty { "按作息" }}"
                                            } else {
                                                "无指定节次 · ${cand.currentRanges.joinToString { "${it.start}-${it.end}" }.ifEmpty { "自定义时间" }}"
                                            },
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "地点: ${cand.currentRoom.ifBlank { "未填写" }} · 教师: ${cand.currentTeacher.ifBlank { "未填写" }}",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (cand.alreadyAdjusted) {
                                            Text(
                                                "提示：该课时此前已被调整过",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (state.candidateMessage != null) {
                    Text(state.candidateMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                // 已调整课时的替换确认
                if (state.alreadyAdjustedCandidate) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = state.replaceExistingAdjustment,
                                onCheckedChange = viewModel::setReplaceExistingAdjustment,
                                modifier = Modifier.testTag("manual-replace-adjustment-checkbox")
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "该课时此前已被调整过。勾选此项确认用本次调整替换既有调整记录。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            // 目标安排表单输入（仅在 ADJUST 与 EXTRA 模式下需要）
            if (state.mode != ManualOccurrenceMode.CANCEL) {
                Text(
                    if (state.mode == ManualOccurrenceMode.ADJUST) "第二步：设置调整后的新安排" else "填写补课时间与地点",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

                // 目标日期
                OutlinedTextField(
                    value = state.targetDateInput,
                    onValueChange = viewModel::setTargetDateInput,
                    label = { Text("目标日期（年-月-日，如 2026-11-13）") },
                    modifier = Modifier.fillMaxWidth().testTag("manual-input-target-date"),
                    singleLine = true
                )

                // 目标节次
                OutlinedTextField(
                    value = state.targetSectionsInput,
                    onValueChange = viewModel::setTargetSectionsInput,
                    label = { Text("节次（如 3-4 或 5 或 1,3；留空且启用自定义时钟可支持无节次课程）") },
                    modifier = Modifier.fillMaxWidth().testTag("manual-input-target-sections"),
                    singleLine = true
                )

                // 自定义时钟开关
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = state.useCustomTime,
                        onCheckedChange = viewModel::setUseCustomTime,
                        modifier = Modifier.testTag("manual-toggle-custom-time")
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("指定具体起止时间（覆盖默认作息表或用于无节次课程）", style = MaterialTheme.typography.bodySmall)
                }

                // 自定义起止时间输入框（仅勾选时展开）
                AnimatedVisibility(visible = state.useCustomTime) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.customTimeStartInput,
                            onValueChange = viewModel::setCustomTimeStartInput,
                            label = { Text("开始时间（HH:mm）") },
                            modifier = Modifier.weight(1f).testTag("manual-input-time-start"),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = state.customTimeEndInput,
                            onValueChange = viewModel::setCustomTimeEndInput,
                            label = { Text("结束时间（HH:mm）") },
                            modifier = Modifier.weight(1f).testTag("manual-input-time-end"),
                            singleLine = true
                        )
                    }
                }

                // 目标地点
                OutlinedTextField(
                    value = state.targetRoomInput,
                    onValueChange = viewModel::setTargetRoomInput,
                    label = { Text("地点（教室/实训室）") },
                    modifier = Modifier.fillMaxWidth().testTag("manual-input-target-room"),
                    singleLine = true
                )
            }

            // 预览按钮
            val canPreview = when (state.mode) {
                ManualOccurrenceMode.ADJUST -> state.selectedCandidateId != null && (state.targetSectionsInput.isNotBlank() || state.useCustomTime)
                ManualOccurrenceMode.CANCEL -> state.selectedCandidateId != null
                ManualOccurrenceMode.EXTRA -> state.targetDateInput.isNotBlank() && (state.targetSectionsInput.isNotBlank() || state.useCustomTime)
            } && !state.isPreviewing && !state.isApplying

            Button(
                onClick = viewModel::preview,
                modifier = Modifier.fillMaxWidth().testTag("manual-btn-preview"),
                enabled = canPreview
            ) {
                if (state.isPreviewing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在生成方案预览…")
                } else {
                    Text("生成方案预览")
                }
            }

            // 预览阻断或问题
            if (state.previewProblems.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.testTag("manual-preview-problems-card")
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.previewProblems.forEach { problem ->
                            Text(
                                "• ${formatNoticeCode(problem.code, problem.detail)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // 冲突展示与确认
            if (state.detectedConflicts.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "检测到时间冲突：请逐项核对并显式确认",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                        state.detectedConflicts.forEach { conflict ->
                            val isAck = conflict.fingerprint in state.acknowledgedConflictFingerprints
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isAck,
                                    onCheckedChange = { viewModel.acknowledgeConflict(conflict.fingerprint, it) },
                                    modifier = Modifier.testTag("manual-conflict-check-${conflict.fingerprint}")
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "与课程【${conflict.otherTitle}】发生重叠",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        "日期: ${conflict.date} · 重叠区间: ${conflict.overlap.joinToString { "${it.start}-${it.end}" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 方案预览与确认卡片（仅当 Ready 时展示）
            state.preview?.let { preview ->
                val proposal = preview.proposal
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().testTag("manual-preview-card"),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "原安排 → 新安排（仅修改这一次）",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Text(
                            "课程: ${proposal.courseTitle ?: state.courseName}",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )

                        // 原安排
                        Text("【原安排】", style = MaterialTheme.typography.labelLarge)
                        if (proposal.before != null) {
                            OccurrenceDetail(proposal.before)
                        } else {
                            Text("（无原课时，此调整为补课/新增课时）", style = MaterialTheme.typography.bodyMedium)
                        }

                        HorizontalDivider()

                        // 新安排
                        Text("【新安排】", style = MaterialTheme.typography.labelLarge)
                        if (proposal.after != null) {
                            OccurrenceDetail(proposal.after)
                        } else {
                            Text("（已停课：仅取消该日期课时，不删除整门课程）", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }

                        // 提示
                        Text(
                            "确认后将更新本机课表，今日/本周课表将自动刷新。本操作具有有条件撤销保障。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )

                        // 核心确认执行按钮
                        Button(
                            onClick = viewModel::apply,
                            modifier = Modifier.fillMaxWidth().testTag("manual-btn-confirm-apply"),
                            enabled = !state.isApplying
                        ) {
                            if (state.isApplying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("正在保存变更…")
                            } else {
                                Text(
                                    when (state.mode) {
                                        ManualOccurrenceMode.ADJUST -> "确认修改本次安排"
                                        ManualOccurrenceMode.CANCEL -> "确认停课本次"
                                        ManualOccurrenceMode.EXTRA -> "确认添加补课"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 执行成功卡片反馈
            state.applySuccessMessage?.let { msg ->
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.testTag("manual-apply-success-card")
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(msg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onClose, modifier = Modifier.testTag("manual-btn-return-timetable")) {
                                Text("返回课表")
                            }
                        }
                    }
                }
            }

            // 执行错误反馈
            state.applyErrorMessage?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.testTag("manual-apply-error-card")
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun OccurrenceDetail(view: NoticeOccurrenceView) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("• 日期: ${view.date}", style = MaterialTheme.typography.bodyMedium)
        Text("• 节次: 第${view.sections.sorted().joinToString(",")}节", style = MaterialTheme.typography.bodyMedium)
        if (view.ranges.isNotEmpty()) {
            Text("• 时间: ${view.ranges.joinToString { "${it.start}-${it.end}" }}", style = MaterialTheme.typography.bodyMedium)
        }
        Text("• 地点: ${view.room.ifBlank { "未指定地点" }}", style = MaterialTheme.typography.bodyMedium)
        Text("• 教师: ${view.teacher.ifBlank { "未指定教师" }}", style = MaterialTheme.typography.bodyMedium)
    }
}
