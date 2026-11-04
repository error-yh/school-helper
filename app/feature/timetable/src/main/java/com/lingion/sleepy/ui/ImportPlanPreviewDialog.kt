/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted from Sleepy contributors, frozen 3dafa110f74ac4f32464ce1efddb24197a4091d9:
 * ImportSheet.kt:924-972 (preview dialog), 1229-1248 (metric card).
 * Host MaterialTheme replaces SleepyTheme; counts/conflicts use the actual Room-loaded plan.
 * Every mode keeps a separate final confirmation and never merges global periods implicitly.
 */
package com.lingion.sleepy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lingion.sleepy.domain.imports.*
import com.lingion.sleepy.domain.repository.ImportOccurrenceView

@Composable
internal fun ImportPlanPreviewDialog(state:ImportFlowState,model:ImportFlowViewModel) {
    val preview=state.preview ?: return
    var showConflictDetails by remember {mutableStateOf(false)}
    val counts=preview.preview.counts
    val colors=MaterialTheme.colorScheme
    AlertDialog(modifier=Modifier.semantics {testTagsAsResourceId=true},onDismissRequest={if(!state.busy) model.dismissPreview()},
        titleContentColor=colors.onSurface,textContentColor=colors.onSurfaceVariant,
        title={Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("导入预览",style=MaterialTheme.typography.titleLarge)
            Text("目标：${state.target?.snapshot?.settings?.name ?: "本机学期"}",style=MaterialTheme.typography.bodySmall)
            state.target?.snapshot?.settings?.startDate?.let { Text("开学日期：$it",style=MaterialTheme.typography.bodySmall) }
        }},
        text={Column(Modifier.testTag("import-preview-content").heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ImportMetricCard("课程档案",counts.confirmedArchives.toString(),colors.primaryContainer,colors.onPrimaryContainer,Modifier.weight(1f))
                ImportMetricCard("已选安排",counts.selectedSourceMeetings.toString(),colors.secondaryContainer,colors.onSecondaryContainer,Modifier.weight(1f))
                ImportMetricCard("冲突条目",preview.conflicts.size.toString(),colors.tertiaryContainer,colors.onTertiaryContainer,Modifier.weight(1f))
            }
            Row {
                FilterChip(selected=state.mode==ImportMode.APPEND,onClick={model.mode(ImportMode.APPEND)},label={Text("追加")},enabled=!state.busy)
                Spacer(Modifier.width(8.dp))
                FilterChip(selected=state.mode==ImportMode.REPLACE,onClick={model.mode(ImportMode.REPLACE)},label={Text("替换本学期")},enabled=!state.busy)
            }
            Text("切换模式会保留修正草稿，返回重新核对。",style=MaterialTheme.typography.bodySmall)
            preview.changes?.let { change ->
                Text("预计新增课程 ${change.newCourses}，更新 ${change.updatedCourses}，替换移除 ${change.removedCourses}。")
                Text("实际写入授课安排 ${change.writtenMeetingFragments} 条；已去除重复部分，一条原文可能按时间拆成多条。",style=MaterialTheme.typography.bodySmall)
                if(change.skippedSourceMeetings>0) Text("跳过冲突安排 ${change.skippedSourceMeetings} 条。")
            }
            state.problems.map(::importReviewMessage).distinct().forEach {Text(it,color=colors.error)}
            if(preview.sourceRelationRequired) {
                Text("本次来源与已有来源位置重合但内容不同：可能确实是另一份来源（新图片或新文件），也可能是同一份来源的内容被修改过。已存证据无法区分这两种情况，因此需要你确认一次，不会逐条询问。",style=MaterialTheme.typography.bodySmall)
                Text("若这是另一份来源，确认后按新来源追加；若同一份来源改了内容，请返回修正对应安排，或重新读取来源后再核对。",style=MaterialTheme.typography.bodySmall)
                Button(onClick=model::confirmDifferentSource,enabled=!state.busy) { Text("确认这是另一个来源") }
            }
            if(preview.conflicts.isNotEmpty()) {
                Text("请选择本次导入的冲突处理方式：")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=preview.conflicts.all {state.conflictChoices[it.fingerprint]==ConflictReviewChoice.KEEP},onClick={model.resolveAllConflicts(ConflictReviewChoice.KEEP)},label={Text("全部保留")},enabled=!state.busy)
                    FilterChip(selected=preview.conflicts.all {state.conflictChoices[it.fingerprint]==ConflictReviewChoice.SKIP},onClick={model.resolveAllConflicts(ConflictReviewChoice.SKIP)},label={Text("跳过冲突安排")},enabled=!state.busy)
                }
                TextButton(onClick={showConflictDetails=!showConflictDetails}) {Text(if(showConflictDetails) "收起明细" else "查看冲突明细")}
                if(showConflictDetails) preview.conflicts.forEach {conflict ->
                    HorizontalDivider()
                    Text("${conflict.incoming.date} · 重叠 ${conflict.overlap.start}–${conflict.overlap.end}")
                    Text("本条：${conflictSideSummary(conflict.incoming)}")
                    Text("${if(conflict.other.courseId!=null) "已有" else "本次另一条"}：${conflictSideSummary(conflict.other)}")
                }
            }
            if(preview.unchanged) Text(if((preview.changes?.skippedSourceMeetings ?: 0)>0) "已跳过所选冲突，本次不写入课程。" else "这些课程安排已存在，无需重复导入。")
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }},
        confirmButton={TextButton(onClick=model::confirm,enabled=!state.busy && preview.ready) { Text("继续确认") }},
        dismissButton={TextButton(onClick=model::dismissPreview,enabled=!state.busy) { Text("返回修改") }})
}

@Composable
private fun ImportMetricCard(label:String,value:String,bg:Color,fg:Color,modifier:Modifier=Modifier) {
    Column(modifier.semantics(mergeDescendants=true) {contentDescription="$label $value"}.clip(MaterialTheme.shapes.large).background(bg).padding(vertical=12.dp,horizontal=10.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(label,style=MaterialTheme.typography.labelSmall,color=fg.copy(alpha=.9f))
        Text(value,style=MaterialTheme.typography.titleLarge.copy(fontWeight=FontWeight.Bold),color=fg)
    }
}
private fun conflictSideSummary(value:ImportOccurrenceView):String = listOfNotNull(value.courseName.takeIf { it.isNotBlank() },
    value.project?.takeIf { it.isNotBlank() }?.let { "项目 $it" },value.group?.takeIf { it.isNotBlank() }?.let { "分组 $it" },
    value.teacher.takeIf { it.isNotBlank() },value.room.takeIf { it.isNotBlank() },"${value.range.start}–${value.range.end}").joinToString(" · ")

internal fun logicalBaseSummary(value:ImportLogicalBaseDisplay):String = meetingScheduleSummary(value.original)+
    " · "+value.timeRanges.joinToString(" / ") { "${it.start}–${it.end}" }+
    listOfNotNull(value.project?.takeIf { it.isNotBlank() },value.group?.takeIf { it.isNotBlank() }).joinToString(" · ",prefix=" · ")
