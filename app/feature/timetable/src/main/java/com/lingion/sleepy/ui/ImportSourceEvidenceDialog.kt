/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingion.sleepy.domain.imports.FrozenImportSource
import com.lingion.sleepy.importing.SourceExcerpt

@Composable
internal fun ImportSourceEvidenceDialog(source:FrozenImportSource,onClose:()->Unit) {
    val lines=remember(source.contentDigest) { buildList<String> {
        fun excerpt(value:SourceExcerpt) { add("第${value.position.page}页 · 区域${value.position.bounds}\n${value.rawText}") }
        source.corrections.forEach { add("已人工修正：课程${(it.source.courseIndex ?: 0)+1}安排${(it.source.meetingIndex ?: 0)+1}；字段 ${it.fields.joinToString()}。原文和原警告继续保留。") }
        val draft=source.format.draft
        (source.format.issues+draft?.issues.orEmpty()).distinct().forEach { add(importReviewMessage(it.code)) }
        draft?.courses?.forEach { course ->
            course.sources.forEach(::excerpt)
            course.meetings.forEach { meeting ->
                meeting.sources.forEach(::excerpt)
                meeting.warnings.forEach { add(importReviewMessage(it.code));it.source?.let(::excerpt) }
                meeting.exceptions.forEach { change -> change.sources.forEach(::excerpt) }
            }
        }
        draft?.unparsed?.forEach { add("未解析内容：");excerpt(it) }
        (source.format.sources+source.format.unparsedSources+source.format.pendingExceptions.flatMap { it.sources }).distinct().forEach { add("${it.sourceLocator}\n${it.rawText}") }
        source.ocr?.document?.pages?.forEach { page ->
            page.variants.forEach { variant ->
                add("第${page.pageNumber}页 · ${if(variant.id.name=="ORIGINAL") "原图识别" else "复核识别"}")
                variant.spans.forEach { span -> add("${span.position?.bounds ?: "位置待核对"}：${span.rawText}") }
            }
        }
    }.distinct().flatMap { it.chunked(1600) } }
    AlertDialog(onDismissRequest=onClose,title={Text("原文与识别证据")},text={
        LazyColumn(Modifier.heightIn(max=480.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(lines) { Text(it,style=MaterialTheme.typography.bodySmall) }
        }
    },confirmButton={TextButton(onClick=onClose) { Text("关闭原文") }})
}

internal fun importReviewMessage(code:String):String = when {
    code=="RULED_CELL_SCOPE_REVIEW_REQUIRED" -> "已根据完整表格边界识别跨节次安排，请在预览中核对。"
    code=="UNEXPLAINED_MARKER_REVIEW_REQUIRED" -> "原文标记已保留，不自动解释为停课或调课。"
    code=="SPECIAL_DATE_TIME_UNSPECIFIED" -> "周末课程未写明节次，日期原文已保留，暂不排入课表。"
    code.any { it.code>127 } -> code
    code=="SAME_LESSON_SOURCE_FRAGMENTS_REVIEW_REQUIRED" -> "相同精确时间的表格分段：归并为一次授课，保留原节次及全部原文"
    code=="SAME_LESSON_MULTIPLE_ROOMS_REVIEW_REQUIRED" -> "同一次上课的多个教室：合并为一次授课，保留全部地点及原文"
    code=="USER_CORRECTION_REVIEW_REQUIRED" -> "人工修正后的字段与保留的原文"
    code=="WEEK_ICON_PREFIX_REVIEW_REQUIRED" -> "周次前的日历图标识别有偏差，日期与原文周次已交叉核对，原图标注继续保留。"
    code.startsWith("SOURCE_FIELD_CORRECTION") || code.startsWith("MEETING_MISSING") || code.contains("INVALID_TIME") || code.contains("INVALID_WEEKS") || code.contains("INVALID_SECTIONS") || code.contains("INVALID_DATE") -> "来源的日期、周次或时间待修正，请编辑对应安排后重新检查。"
    code.contains("CALENDAR_REQUIRED") -> "周期课程需要开学日期，请先在课表设置中填写，再重新读取来源。"
    code.contains("STALE") || code.startsWith("TARGET_") -> "课表或目标设置已变化，请重新读取并核对。"
    code.contains("CONFLICT_REVIEW_REQUIRED") -> "请选择本次导入的冲突处理方式。"
    code.contains("ARCHIVE") || code.contains("IDENTITY") -> "请核对各条安排的课程归属。"
    code.contains("TIME") || code.contains("PERIOD") -> "请核对原文时间及本机作息，明确选择使用哪一组时间。"
    code.contains("EXCEPTION") || code.contains("ATTACHMENT") -> "请明确停课、调课或加课对应的原安排与日期。"
    code.contains("RELATIVE") || code.contains("BASIS") -> "请核对相对日期的基准和实际日期。"
    code.contains("NOTHING_SELECTED") -> "请至少选择一条课程安排或通知。"
    code.contains("UNPARSED") -> "尚有未解析原文，请打开证据页核对。"
    code.contains("OCR") -> "图片识别可能存在不确定内容，请核对原图识别与复核文本。"
    code.contains("FORMULA") -> "公式缓存不是已验证的课程数据，请核对原单元格。"
    else -> "来源含待核对信息，请对照原文、安排和归属后重新检查。"
}
