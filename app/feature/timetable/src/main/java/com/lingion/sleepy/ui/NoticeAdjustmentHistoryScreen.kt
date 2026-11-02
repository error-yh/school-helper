/* SPDX-License-Identifier: GPL-3.0-only
 * Adjustment history and guarded undo screen.
 * Shows occurrence-level event timeline and provides secondary confirmation before undo.
 */
package com.lingion.sleepy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
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
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryEntry
import com.lingion.sleepy.importing.NoticeIntentKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeAdjustmentHistoryScreen(
    dependencies: TimetableDependencies,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 处理系统返回键，与顶部返回箭头行为一致
    BackHandler(onBack = onClose)

    val factory = remember(dependencies) { NoticeAdjustmentHistoryViewModel.Factory(dependencies) }
    val viewModel: NoticeAdjustmentHistoryViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 每次进入页面时刷新，保证看到最新调课事件
    LaunchedEffect(Unit) {
        viewModel.loadHistory(clearSuccessMessage = false)
    }

    // 撤销二次确认对话框
    state.confirmUndoEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUndoPrompt,
            title = { Text("确认撤销此调课记录？") },
            text = {
                Text(
                    "撤销调课记录 #${entry.eventId}（${entry.courseTitle ?: "课程"}）将还原该日期课时的原先安排。\n" +
                        "其他日期的课时及独立的修改将不受影响。若后续安排已有变动，撤销将被安全拒绝。是否继续？"
                )
            },
            confirmButton = {
                Button(
                    onClick = viewModel::confirmUndo,
                    modifier = Modifier.testTag("notice-dialog-confirm-undo")
                ) {
                    Text("确认撤销")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissUndoPrompt) {
                    Text("取消")
                }
            },
            modifier = Modifier.testTag("notice-undo-dialog")
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("调整记录与撤销") },
                navigationIcon = {
                    IconButton(onClick = onClose, modifier = Modifier.testTag("notice-history-btn-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.loadHistory(clearSuccessMessage = true) },
                        enabled = !state.isLoading,
                        modifier = Modifier.testTag("notice-history-btn-refresh")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize().testTag("notice-history-screen")
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // 撤销成功或错误横幅
            state.undoSuccessMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("notice-undo-success-card")
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(msg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            state.errorMessage?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("notice-history-error-card")
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(err, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (state.isLoading && state.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("暂无任何单次调课记录", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { viewModel.loadHistory(clearSuccessMessage = true) }) {
                            Text("重新加载")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(state.entries, key = { it.eventId }) { entry ->
                        HistoryEntryCard(
                            entry = entry,
                            isSourceExpanded = entry.eventId in state.expandedSourceEventIds,
                            onToggleSource = { viewModel.toggleSourceExpand(entry.eventId) },
                            onUndo = { viewModel.promptUndo(entry) },
                            isUndoing = state.isUndoing
                        )
                    }

                    if (state.nextBeforeEventId != null) {
                        item {
                            Button(
                                onClick = viewModel::loadMore,
                                modifier = Modifier.fillMaxWidth().testTag("notice-history-btn-load-more"),
                                enabled = !state.isLoading
                            ) {
                                if (state.isLoading) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                    Text("正在加载…")
                                } else {
                                    Text("加载更早的记录")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryCard(
    entry: NoticeAdjustmentHistoryEntry,
    isSourceExpanded: Boolean,
    onToggleSource: () -> Unit,
    onUndo: () -> Unit,
    isUndoing: Boolean,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().testTag("notice-history-item-${entry.eventId}"),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (entry.state == "UNDONE") MaterialTheme.colorScheme.surfaceVariant
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 头部：编号、课程名、状态徽章
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        entry.courseTitle ?: "未命名课程",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "记录 #${entry.eventId} · ${formatIntentKind(entry.kind)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 状态徽章
                val (badgeText, badgeColor, textColor) = if (entry.state == "APPLIED") {
                    Triple("已生效", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                } else {
                    Triple("已撤销", MaterialTheme.colorScheme.outlineVariant, MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = badgeColor
                ) {
                    Text(
                        badgeText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            HorizontalDivider()

            // 变动内容对比
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("【原安排】", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    if (entry.before != null) {
                        Text("日期: ${entry.before.date}", style = MaterialTheme.typography.bodySmall)
                        Text("节次: 第${entry.before.sections.sorted().joinToString(",")}节", style = MaterialTheme.typography.bodySmall)
                        Text("地点: ${entry.before.room.ifBlank { "未指定" }}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("（无原安排）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Column(Modifier.weight(1f)) {
                    Text("【调整后】", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    if (entry.after != null) {
                        Text("日期: ${entry.after.date}", style = MaterialTheme.typography.bodySmall)
                        Text("节次: 第${entry.after.sections.sorted().joinToString(",")}节", style = MaterialTheme.typography.bodySmall)
                        Text("地点: ${entry.after.room.ifBlank { "未指定" }}", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("（已停课取消）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // 原文折叠
            entry.sourceText?.let { raw ->
                TextButton(
                    onClick = onToggleSource,
                    modifier = Modifier.testTag("notice-history-expand-${entry.eventId}")
                ) {
                    Text(if (isSourceExpanded) "收起操作说明/原文" else "查看操作说明/原文")
                }
                AnimatedVisibility(visible = isSourceExpanded) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            raw,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            // 撤销按钮（仅当 canUndo 时可操作）
            if (entry.canUndo && entry.state == "APPLIED") {
                Button(
                    onClick = onUndo,
                    modifier = Modifier.align(Alignment.End).testTag("notice-btn-undo-${entry.eventId}"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    enabled = !isUndoing
                ) {
                    Text("撤销此调整")
                }
            }
        }
    }
}

private fun formatIntentKind(kind: NoticeIntentKind): String = when (kind) {
    NoticeIntentKind.CANCEL -> "停课/取消课时"
    NoticeIntentKind.CHANGE_DATE -> "调课/调整日期"
    NoticeIntentKind.RESCHEDULE_TIME -> "调整时间"
    NoticeIntentKind.ROOM_ONLY -> "仅调整教室"
    NoticeIntentKind.EXTRA -> "补课/加课"
}
