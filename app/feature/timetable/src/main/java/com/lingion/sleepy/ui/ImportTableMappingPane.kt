/* SPDX-License-Identifier: GPL-3.0-only
 * Reuses HeaderMappingInspector and TabularTimetableParser's explicit mapping contract.
 */
package com.lingion.sleepy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingion.sleepy.domain.formatNumberSet
import com.lingion.sleepy.domain.parseNumberSet
import com.lingion.sleepy.importing.formats.*

@Composable
internal fun ImportTableMappingPane(document: LoadedImportDocument.Tables, enabled: Boolean,
    onMap: (Int, ConfirmedColumnMapping) -> Unit) {
    var selected by remember(document.context.sourceId) { mutableStateOf<Int?>(null) }
    Text("选择工作表并核对字段",style=MaterialTheme.typography.titleMedium)
    document.sheets.forEach { sheet ->
        TextButton(onClick={selected=sheet.number},enabled=enabled && sheet.table!=null) {
            Text("${if(selected==sheet.number) "✓ " else ""}${sheet.number}. ${sheet.name}${if(sheet.visibility!=SheetVisibility.VISIBLE) "（隐藏表）" else ""}")
        }
    }
    val sheet=document.sheets.singleOrNull { it.number==selected } ?: return
    val table=sheet.table ?: return
    val proposal=remember(table) { HeaderMappingInspector().inspect(table,document.context) }
    val columns=remember(table) { mutableStateMapOf<ImportColumnField,String>().apply {
        proposal.candidates.groupBy { it.field }.filterValues { it.size==1 }.forEach { (field,matches)->put(field,matches.single().column.toString()) }
    } }
    var headers by remember(table) { mutableStateOf(formatNumberSet(proposal.headerRows)) }
    var ignored by remember(table) { mutableStateOf(formatNumberSet(proposal.ignoredColumns)) }
    var error by remember(table) { mutableStateOf<String?>(null) }
    Text("原表预览（前5行）：",style=MaterialTheme.typography.labelLarge)
    table.rows.take(5).forEach { row -> Text("行${row.number}："+row.cells.take(12).joinToString(" | ") { "列${it.column} ${it.rawText.take(80)}${if(it.formula!=null) " [公式，需核对]" else ""}" },style=MaterialTheme.typography.bodySmall) }
    Text("列号从1开始。下方仅为建议，确认后才读取课程；未映射内容会继续保留为待核对来源。",style=MaterialTheme.typography.bodySmall)
    FormField("表头所在行",headers,{headers=it},hint="例如1或1-2；留空表示无表头",enabled=enabled)
    ImportColumnField.entries.forEach { field ->
        FormField(importColumnLabel(field)+"对应列",columns[field].orEmpty(),{columns[field]=it},hint="列号；不使用可留空",enabled=enabled)
    }
    FormField("明确忽略的列号",ignored,{ignored=it},hint="多个列号用逗号分隔",enabled=enabled)
    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    Button(onClick={
        try {
            val mapped=columns.filterValues { it.isNotBlank() }.mapValues { (_,v)->v.trim().toIntOrNull()?.takeIf { it in 1..16384 } ?: throw IllegalArgumentException("列号请填写1到16384。") }
            require(mapped.values.toSet().size==mapped.size) { "同一列不能重复映射多个字段。" }
            val headerRows=if(headers.isBlank()) emptySet() else parseNumberSet(headers,100000,"表头行")
            val ignoredColumns=if(ignored.isBlank()) emptySet() else parseNumberSet(ignored,16384,"忽略列")
            require(mapped.values.none { it in ignoredColumns }) { "已映射列不能同时忽略。" }
            error=null;onMap(sheet.number,ConfirmedColumnMapping(headerRows,mapped,ignoredColumns))
        } catch(failure:IllegalArgumentException) { error=failure.message ?: "请核对工作表和字段。" }
    },enabled=enabled) { Text("确认工作表和字段映射") }
}

internal fun importColumnLabel(field:ImportColumnField):String = when(field) {
    ImportColumnField.COURSE_KEY->"课程键";ImportColumnField.MEETING_KEY->"安排键";ImportColumnField.NAME->"课程名称"
    ImportColumnField.SOURCE_COURSE_CODE->"课程代码";ImportColumnField.SOURCE_CLASS_CODE->"教学班代码";ImportColumnField.KIND->"安排类型"
    ImportColumnField.DAY_OF_WEEK->"星期";ImportColumnField.WEEKS->"周次";ImportColumnField.SECTIONS->"节次";ImportColumnField.DATES->"指定日期"
    ImportColumnField.START_TIME->"开始时间";ImportColumnField.END_TIME->"结束时间";ImportColumnField.TIME_RANGE->"时间范围"
    ImportColumnField.TEACHER->"教师";ImportColumnField.ROOM->"地点";ImportColumnField.GROUP->"分组";ImportColumnField.EXPERIMENT_PROJECT->"实验项目"
    ImportColumnField.IS_EXPERIMENT->"是否实验";ImportColumnField.EXCEPTION_KIND->"停调课类型";ImportColumnField.ORIGINAL_DATE->"原日期";ImportColumnField.TARGET_DATE->"目标日期"
}
