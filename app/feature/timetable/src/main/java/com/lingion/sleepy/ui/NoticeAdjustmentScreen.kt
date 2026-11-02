/* SPDX-License-Identifier: GPL-3.0-only
 * Confirmation UI for single-occurrence notice adjustments.
 * Read-only preview with explicit confirmation; writes happen strictly on user action.
 */
package com.lingion.sleepy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.notice.NoticeIntentResult
import com.lingion.sleepy.domain.notice.NoticeOccurrenceView
import com.lingion.sleepy.importing.NoticeDayPart
import java.time.LocalDate
import kotlinx.coroutines.flow.first

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeAdjustmentScreen(
    dependencies: TimetableDependencies,
    onNavigateToHistory: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 处理系统返回键，与顶部返回箭头行为一致，直接回到课表主界面
    BackHandler(onBack = onClose)

    val factory = remember(dependencies) { NoticeAdjustmentViewModel.Factory(dependencies) }
    val viewModel: NoticeAdjustmentViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showCorrections by rememberSaveable { mutableStateOf(false) }

    // 读取当前课表设置用于一键复用
    var currentTermMonday by remember { mutableStateOf<LocalDate?>(null) }
    var currentTotalWeeks by remember { mutableIntStateOf(0) }
    LaunchedEffect(dependencies.repository) {
        val snapshot = dependencies.repository.observeTimetable().first()
        currentTermMonday = snapshot.settings.startDate?.let(TimetableRules::mondayOf)
        currentTotalWeeks = snapshot.settings.totalWeeks
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文字通知调课") },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("notice-btn-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = onNavigateToHistory, modifier = Modifier.testTag("notice-btn-go-history")) {
                        Text("调整记录")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize().testTag("notice-adjustment-screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 说明与指引（有条件撤销，严谨保留边界说明）
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "单次调课仅影响选定日期的一次课时安排，不会修改整学期的基础课表规则。调课具有有条件撤销保障：若后续课表安排未发生冲突变动，可随时还原原安排。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 1. 输入原文
            OutlinedTextField(
                value = state.rawText,
                onValueChange = viewModel::updateRawText,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp, max = 220.dp)
                    .testTag("notice-text-input"),
                label = { Text("粘贴教务通知或调课文字") },
                placeholder = { Text("例如：本周五的数据可视化课程由原来的第3-4节调整为第5-6节，教室不变。") },
                enabled = !state.isApplying
            )

            // 2. 日期依据卡片
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("日期依据与学期基准", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "通知中的相对表述（如“本周五”、“下周二”）需结合发布日期推算。请确认或指定基准：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 参考日期输入
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.referenceDateInput,
                            onValueChange = viewModel::updateReferenceDateInput,
                            modifier = Modifier.weight(1f).testTag("notice-reference-date-input"),
                            label = { Text("通知发布日期 / 基准日") },
                            placeholder = { Text("年-月-日，如 2026-11-09") },
                            singleLine = true,
                            enabled = !state.isApplying
                        )
                        Button(
                            onClick = viewModel::useTodayAsReferenceDate,
                            enabled = !state.isApplying,
                            modifier = Modifier.testTag("notice-btn-use-today")
                        ) {
                            Text("填入今天")
                        }
                    }

                    // 学期设置复用按钮
                    if (currentTermMonday != null) {
                        OutlinedButton(
                            onClick = { viewModel.useCurrentSemesterBasis(currentTermMonday, currentTotalWeeks) },
                            enabled = !state.isApplying,
                            modifier = Modifier.fillMaxWidth().testTag("notice-btn-use-current-term")
                        ) {
                            Text("复用当前学期设置（起点: $currentTermMonday，共 ${currentTotalWeeks} 周）")
                        }
                    }

                    // 第一周周一、总周数与年份
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.termMondayInput,
                            onValueChange = viewModel::updateTermMondayInput,
                            modifier = Modifier.weight(1.2f).testTag("notice-term-monday-input"),
                            label = { Text("第一周周一") },
                            placeholder = { Text("如 2026-09-07") },
                            singleLine = true,
                            enabled = !state.isApplying
                        )
                        OutlinedTextField(
                            value = state.totalWeeksInput,
                            onValueChange = viewModel::updateTotalWeeksInput,
                            modifier = Modifier.weight(0.8f).testTag("notice-total-weeks-input"),
                            label = { Text("总周数") },
                            placeholder = { Text("如 16") },
                            singleLine = true,
                            enabled = !state.isApplying
                        )
                    }

                    // 年份输入
                    OutlinedTextField(
                        value = state.yearInput,
                        onValueChange = viewModel::updateYearInput,
                        modifier = Modifier.fillMaxWidth().testTag("notice-year-input"),
                        label = { Text("指定年份（选填，如通知仅有几月几日缺少周次时必填）") },
                        placeholder = { Text("如 2026") },
                        singleLine = true,
                        enabled = !state.isApplying
                    )

                    // 大节映射
                    val bigSec = state.needTimeBigSection
                    val bigSecTitle = if (bigSec != null) {
                        val partName = if (bigSec.part == NoticeDayPart.MORNING) "上午" else "下午"
                        "通知涉及【$partName 第${bigSec.ordinal}大节】，请指定映射节次："
                    } else {
                        "若通知使用“大节”（如第1或第2大节），请指定映射节次（选填）："
                    }
                    Text(
                        bigSecTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // 如果需要第2大节及以上，提供前置大节的明确补充输入入口
                    if (bigSec != null && bigSec.ordinal > 1) {
                        for (prior in 1 until bigSec.ordinal) {
                            val priorPair = state.bigSectionOrdinalInputs[prior] ?: Pair("", "")
                            val partName = if (bigSec.part == NoticeDayPart.MORNING) "上午" else "下午"
                            Text(
                                "补充前置【$partName 第${prior}大节】节次：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = priorPair.first,
                                    onValueChange = { viewModel.updateBigSectionMappingForOrdinal(prior, it, priorPair.second) },
                                    modifier = Modifier.weight(1f).testTag("notice-big-section-prior-$prior-start"),
                                    label = { Text("第${prior}大节 起始节") },
                                    placeholder = { Text("如 1") },
                                    singleLine = true,
                                    enabled = !state.isApplying
                                )
                                OutlinedTextField(
                                    value = priorPair.second,
                                    onValueChange = { viewModel.updateBigSectionMappingForOrdinal(prior, priorPair.first, it) },
                                    modifier = Modifier.weight(1f).testTag("notice-big-section-prior-$prior-end"),
                                    label = { Text("第${prior}大节 终止节") },
                                    placeholder = { Text("如 2") },
                                    singleLine = true,
                                    enabled = !state.isApplying
                                )
                            }
                        }
                    }

                    val targetLabel = if (bigSec != null) "第${bigSec.ordinal}大节" else "当前大节"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = state.bigSectionStartInput,
                            onValueChange = { viewModel.updateBigSectionMapping(it, state.bigSectionEndInput) },
                            modifier = Modifier.weight(1f).testTag("notice-big-section-start"),
                            label = { Text("$targetLabel 起始节") },
                            placeholder = { Text("如 3") },
                            singleLine = true,
                            enabled = !state.isApplying
                        )
                        OutlinedTextField(
                            value = state.bigSectionEndInput,
                            onValueChange = { viewModel.updateBigSectionMapping(state.bigSectionStartInput, it) },
                            modifier = Modifier.weight(1f).testTag("notice-big-section-end"),
                            label = { Text("$targetLabel 终止节") },
                            placeholder = { Text("如 4") },
                            singleLine = true,
                            enabled = !state.isApplying
                        )
                    }

                    val mappingErr = state.bigSectionMappingError
                    if (mappingErr != null) {
                        Text(
                            text = mappingErr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // 解析按钮
            Button(
                onClick = viewModel::parse,
                modifier = Modifier.fillMaxWidth().testTag("notice-btn-parse"),
                enabled = state.rawText.isNotBlank() && !state.isParsing && !state.isApplying
            ) {
                if (state.isParsing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在解析通知…")
                } else {
                    Text("解析通知内容")
                }
            }

            // 全局/通用错误展示
            state.generalErrorMessage?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // 解析问题或提示
            if (state.parseProblems.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.parseProblems.forEach { problem ->
                            Text(
                                "• ${formatNoticeCode(problem.code, problem.detail)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            // 多条意图选择
            if (state.parseResults.size > 1) {
                Text("识别到多条调课信息，请选择本次要处理的条目：", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.parseResults.forEachIndexed { index, result ->
                        val isSelected = state.selectedIntentIndex == index
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectIntentIndex(index) }
                                .testTag("notice-intent-choice-$index"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.selectIntentIndex(index) }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    when (result) {
                                        is NoticeIntentResult.Ready -> "条目 #${index + 1}: ${result.intent.rawText}"
                                        is NoticeIntentResult.CorrectionRequired -> "条目 #${index + 1} (需纠正): ${result.intent.rawText}"
                                        else -> "条目 #${index + 1}"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }

            // 纠正输入折叠区（包含原课时与调整后课时字段纠正）
            OutlinedButton(
                onClick = { showCorrections = !showCorrections },
                modifier = Modifier.fillMaxWidth().testTag("notice-toggle-corrections")
            ) {
                Text(if (showCorrections) "收起手动纠正" else "展开手动纠正（纠正原安排/新时间/节次/地点）")
            }
            AnimatedVisibility(visible = showCorrections) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("手动纠正解析字段", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)

                        // 纠正错误提示
                        state.correctionError?.let { err ->
                            Text(
                                "输入错误: $err",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Text("【原课时安排纠正（若通知中的原节次或时间格式异常时填写）】", style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(
                            value = state.correctionOriginalSections,
                            onValueChange = viewModel::updateCorrectionOriginalSections,
                            label = { Text("原课时节次 (如 3-4 或 3,4)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("notice-correct-original-sections")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.correctionOriginalTimeStart,
                                onValueChange = { viewModel.updateCorrectionOriginalTime(it, state.correctionOriginalTimeEnd) },
                                label = { Text("原起始时间 (如 08:00)") },
                                modifier = Modifier.weight(1f).testTag("notice-correct-original-time-start"),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = state.correctionOriginalTimeEnd,
                                onValueChange = { viewModel.updateCorrectionOriginalTime(state.correctionOriginalTimeStart, it) },
                                label = { Text("原结束时间 (如 09:40)") },
                                modifier = Modifier.weight(1f).testTag("notice-correct-original-time-end"),
                                singleLine = true
                            )
                        }

                        HorizontalDivider()

                        Text("【调整后新安排纠正】", style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(
                            value = state.correctionTargetDate,
                            onValueChange = viewModel::updateCorrectionTargetDate,
                            label = { Text("调整后目标日期 (年-月-日)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("notice-correct-date")
                        )
                        OutlinedTextField(
                            value = state.correctionTargetSections,
                            onValueChange = viewModel::updateCorrectionTargetSections,
                            label = { Text("调整后节次 (如 5-8 或 5,6,7,8)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("notice-correct-sections")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.correctionTargetTimeStart,
                                onValueChange = { viewModel.updateCorrectionTargetTime(it, state.correctionTargetTimeEnd) },
                                label = { Text("新起始时间 (如 14:00)") },
                                modifier = Modifier.weight(1f).testTag("notice-correct-time-start"),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = state.correctionTargetTimeEnd,
                                onValueChange = { viewModel.updateCorrectionTargetTime(state.correctionTargetTimeStart, it) },
                                label = { Text("新结束时间 (如 15:30)") },
                                modifier = Modifier.weight(1f).testTag("notice-correct-time-end"),
                                singleLine = true
                            )
                        }
                        OutlinedTextField(
                            value = state.correctionRoom,
                            onValueChange = viewModel::updateCorrectionRoom,
                            label = { Text("新教室地点 (留空保持原教室)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("notice-correct-room")
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = state.correctionRoomKeep,
                                onCheckedChange = viewModel::setCorrectionRoomKeep,
                                modifier = Modifier.testTag("notice-correct-room-keep")
                            )
                            Text("明确保持原教室不变", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // 候选选择（Candidate）
            if (state.candidates.isNotEmpty()) {
                Text(
                    "匹配到的课时候选（请明确点击选择本次要调整的课时）：",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.candidates.forEach { cand ->
                        val isSelected = state.selectedCandidateId == cand.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectCandidate(cand.id) }
                                .testTag("notice-candidate-item-${cand.id.logicalBaseId}"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.selectCandidate(cand.id) }
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(cand.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "原日期: ${cand.originalDate} · 节次: 第${cand.currentSections.sorted().joinToString(",")}节",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    Text(
                                        "地点: ${cand.currentRoom} · 教师: ${cand.currentTeacher}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (cand.alreadyAdjusted) {
                                        Text(
                                            "提示：该节课在当天已有调课记录",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 补课情况下的课程选择
            if (state.availableCourses.isNotEmpty()) {
                Text("补课必须绑定已有的课程（请选择）：", style = MaterialTheme.typography.titleSmall)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.availableCourses.forEach { course ->
                        val isSelected = state.selectedExtraCourseId == course.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectExtraCourse(course.id) }
                                .testTag("notice-extra-course-${course.id}"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.selectExtraCourse(course.id) }
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(course.courseName, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
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
                            modifier = Modifier.testTag("notice-replace-adjustment-checkbox")
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

            // 预览按钮
            val canPreview = (state.selectedCandidateId != null || state.selectedExtraCourseId != null) &&
                !state.isPreviewing && !state.isApplying
            Button(
                onClick = viewModel::preview,
                modifier = Modifier.fillMaxWidth().testTag("notice-btn-preview"),
                enabled = canPreview
            ) {
                if (state.isPreviewing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("正在生成调整方案预览…")
                } else {
                    Text("生成调整方案预览")
                }
            }

            // 预览阻断或问题
            if (state.previewProblems.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
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
                                    modifier = Modifier.testTag("notice-conflict-check-${conflict.fingerprint}")
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

            // 3. 方案预览与确认卡片（仅当 Ready 时展示）
            state.preview?.let { preview ->
                val proposal = preview.proposal
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth().testTag("notice-preview-card"),
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
                            "课程: ${proposal.courseTitle ?: "未命名"}",
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
                            modifier = Modifier.fillMaxWidth().testTag("notice-btn-confirm-apply"),
                            enabled = !state.isApplying
                        ) {
                            if (state.isApplying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("正在保存…")
                            } else {
                                Text("确认仅修改这一次")
                            }
                        }
                    }
                }
            }

            // 执行成功反馈
            state.appliedSuccessMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier.testTag("notice-apply-success-card")
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(msg, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onNavigateToHistory, modifier = Modifier.testTag("notice-btn-view-history")) {
                                Text("查看调整记录")
                            }
                            OutlinedButton(onClick = onClose) {
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
                    modifier = Modifier.testTag("notice-apply-error-card")
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
