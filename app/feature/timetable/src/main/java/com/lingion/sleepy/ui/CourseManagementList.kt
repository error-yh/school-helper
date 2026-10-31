/* SPDX-License-Identifier: GPL-3.0-only
 * Stage 4 course card list: one expandable card per confirmable course group.
 * The card only expands/collapses; every child row opens the details of its own persisted record.
 */
package com.lingion.sleepy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lingion.sleepy.domain.model.ScheduledCourse

/** Saved expansion state is a plain string set so it survives tab switches, recreation and process death. */
internal val ExpandedCourseGroupKeys: Saver<Set<String>, Any> = listSaver(
    save = { it.toList() },
    restore = { saved -> saved.filterIsInstance<String>().toSet() },
)

@Composable
internal fun CourseManagementList(
    projection: CourseManagementProjection,
    enabled: Boolean,
    organizeEnabled: Boolean = enabled,
    expandedKeys: Set<String>,
    onAdd: () -> Unit,
    onOrganize: () -> Unit,
    onToggleGroup: (String) -> Unit,
    onDetails: (ScheduledCourse) -> Unit,
    groupsError: String? = null,
    onRetryGroups: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "课程组 ${projection.groupCount} 门 · 安排 ${projection.meetingCount} 条",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (projection.recordCount != projection.groupCount) {
                    Text("底层记录 ${projection.recordCount} 条", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(
                    onClick = onOrganize,
                    enabled = organizeEnabled,
                    contentPadding = PaddingValues(0.dp),
                ) { Text("整理课程") }
            }
            Button(onClick = onAdd, enabled = enabled) { Text("新增课程") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "安排数按已保存安排统计，含多日期与例外；课程组按可确认身份或手动整理。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (groupsError != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        groupsError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetryGroups) { Text("重试") }
                }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (projection.groups.isEmpty()) {
                item(key = "empty") { Text("还没有课程，点击新增课程录入真实安排。") }
            }
            items(projection.groups, key = { "group:" + it.key }) { group ->
                CourseGroupCard(
                    group = group,
                    expanded = group.key in expandedKeys,
                    onToggle = { onToggleGroup(group.key) },
                    onDetails = onDetails,
                )
            }
        }
    }
}

@Composable
private fun CourseGroupCard(
    group: CourseGroup,
    expanded: Boolean,
    onToggle: () -> Unit,
    onDetails: (ScheduledCourse) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            // Only the header toggles the card, so tapping a child row or its button cannot collapse it.
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.size(4.dp, 44.dp).background(Color(group.color.toInt()), RoundedCornerShape(2.dp)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        group.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        buildString {
                            append("安排 ${group.meetingCount} 条")
                            if (group.recordCount > 1) append(" · 底层记录 ${group.recordCount} 条")
                            if (group.manualId != null) append(" · 手动组")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!expanded) {
                        group.scheduledRows.firstOrNull()?.let { row ->
                            Text(
                                "${row.kindLabel} · ${row.scheduleText}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Text(
                    if (expanded) "收起" else "展开",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                HorizontalDivider()
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    group.scheduledRows.forEach { row -> CourseMeetingRow(row, onDetails) }
                    if (group.unscheduledRows.isNotEmpty()) {
                        Text("未排定安排", style = MaterialTheme.typography.labelMedium)
                        group.unscheduledRows.forEach { row -> CourseMeetingRow(row, onDetails) }
                    }
                    if (group.recordCount > 1) {
                        Text(
                            "同一展示组含 ${group.recordCount} 条独立记录：详情、编辑、删除只作用于所选安排所属的那条记录。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CourseMeetingRow(row: CourseGroupMeetingRow, onDetails: (ScheduledCourse) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${row.kindLabel} · ${row.scheduleText}", style = MaterialTheme.typography.bodyMedium)
                Text("${row.sectionsText} · ${row.timeText}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(
                onClick = { onDetails(row.course) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) { Text("详情") }
        }
        if (row.teacherText.isNotEmpty()) Text("教师：${row.teacherText}", style = MaterialTheme.typography.bodySmall)
        if (row.roomText.isNotEmpty()) Text("地点：${row.roomText}", style = MaterialTheme.typography.bodySmall)
        row.projectText?.let { Text("项目：$it", style = MaterialTheme.typography.bodySmall) }
        row.groupText?.let { Text("分组：$it", style = MaterialTheme.typography.bodySmall) }
        row.exceptionTexts.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        row.missingSectionsText?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (row.recordMeetingCount > 1) {
            Text(
                "该记录含 ${row.recordMeetingCount} 条安排，编辑或删除会作用于整条记录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}