/* SPDX-License-Identifier: GPL-3.0-only
 * Adapted layout patterns from Sleepy contributors, pinned 3dafa110f74ac4f32464ce1efddb24197a4091d9:
 * ui/screen/today/TodayScreen.kt:418-505 (time column + metadata card),
 * ui/component/CourseTableView.kt:105-121,690-738 (weekday columns and offset geometry).
 * Exact-set occurrences, continuous minute geometry and independent conflict lanes are new.
 */
package com.lingion.sleepy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.formatNumberSet
import com.lingion.sleepy.domain.model.*
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

@Composable
internal fun TodayPane(snapshot: TimetableSnapshot, date: LocalDate, today: LocalDate,
    onDate: (LocalDate?) -> Unit, onCourse: (ScheduledCourse) -> Unit) {
    var dateDialog by remember { mutableStateOf(false) }
    var dateText by remember(date) { mutableStateOf(date.toString()) }
    var dateError by remember { mutableStateOf<String?>(null) }
    val week = TimetableRules.teachingWeek(snapshot.settings, date)
    val occurrences = TimetableRules.occurrencesOn(snapshot, date)
    fun move(days: Long) {
        try { onDate(date.plusDays(days)); dateError = null }
        catch (_: DateTimeException) { dateError = "日期已超出支持范围，请选择其他日期。" }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(if (date == today) "今天 · $date" else date.toString(), style = MaterialTheme.typography.titleLarge)
            Text("周${weekdays[date.dayOfWeek.value - 1]} · ${week?.let { "第 $it 周" } ?: "无教学周"}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { move(-1) }) { Text("前一天") }
                TextButton(onClick = { dateDialog = true }) { Text("选择日期") }
                TextButton(onClick = { move(1) }) { Text("后一天") }
            }
            TextButton(onClick = { onDate(null); dateError = null }) { Text("回到今天") }
            dateError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item { TermState(snapshot, week, occurrences.isEmpty()) }
        items(occurrences) { occurrence -> OccurrenceCard(occurrence, onCourse) }
    }
    if (dateDialog) AlertDialog(onDismissRequest = { dateDialog = false }, title = { Text("查看日期") },
        text = { Column { FormField("浏览日期", dateText, { dateText = it }, hint = "YYYY-MM-DD")
            dateError?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
        confirmButton = { TextButton(onClick = {
            try { onDate(parseDate(dateText)); dateDialog = false; dateError = null }
            catch (_: IllegalArgumentException) { dateError = "请填写有效日期，例如 2026-10-19。" }
            catch (_: DateTimeException) { dateError = "请填写有效日期，例如 2026-10-19。" }
        }) { Text("查看") } },
        dismissButton = { TextButton(onClick = { dateDialog = false; dateError = null }) { Text("取消") } })
}

@Composable
private fun TermState(snapshot: TimetableSnapshot, week: Int?, empty: Boolean) {
    when {
        !empty && week == null -> Text("以下为本日实际安排；指定日期课程可在学期外显示。")
        !empty -> Unit
        snapshot.settings.startDate == null -> Text("尚未设置开学日期，请在设置中配置学期后查看今日和本周课程。")
        week == null -> Text("当前日期不在本学期内。")
        empty -> Text("当天没有课程。")
    }
}

@Composable
internal fun OccurrenceCard(occurrence: CourseOccurrence, onCourse: (ScheduledCourse) -> Unit) {
    val bg = Color(occurrence.course.color.toInt())
    val fg = if (bg.luminance() < .4f) Color.White else Color.Black
    Card(onClick = { onCourse(occurrence.course) }, colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.width(100.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (occurrence.effectiveCustomTime != null) "自定义时间" else "第 ${formatNumberSet(occurrence.effectiveSections)} 节", style = MaterialTheme.typography.labelLarge)
                occurrence.timeRanges.forEach { Text("${it.start}–${it.end}", style = MaterialTheme.typography.bodySmall) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(occurrence.course.courseName, Modifier.clickable { onCourse(occurrence.course) }, style = MaterialTheme.typography.titleMedium)
                if (occurrence.effectiveTeacher.isNotBlank()) Text(occurrence.effectiveTeacher)
                if (occurrence.effectiveRoom.isNotBlank()) Text(occurrence.effectiveRoom)
                occurrence.effectiveProject?.takeIf { it.isNotBlank() }?.let { Text("项目：$it") }
                occurrence.effectiveGroup?.takeIf { it.isNotBlank() }?.let { Text("分组：$it") }
                occurrence.exception?.let { Text(if (it.kind == ExceptionKind.EXTRA) "加课安排" else "调课安排", style = MaterialTheme.typography.labelSmall) }
                if (occurrence.missingSections.isNotEmpty()) Text("未定位节次 ${formatNumberSet(occurrence.missingSections)}：缺少作息，请编辑设置。")
            }
        }
    }
}

private data class GridBlock(val occurrence: CourseOccurrence, val range: TimeRange, val section: Int?, val lane: Int = 0)
private data class DayGrid(val date: LocalDate, val blocks: List<GridBlock>, val laneCount: Int)

internal fun occurrenceGridIntervals(occurrence: CourseOccurrence, periods: List<Period>): List<Pair<TimeRange, Int?>> =
    if (occurrence.effectiveCustomTime != null) occurrence.timeRanges.map { it to null }
    else periods.filter { it.number in occurrence.effectiveSections }.map { TimeRange(it.start, it.end) to it.number }

internal fun meetingScheduleSummary(meeting: CourseMeeting): String = buildString {
    when (meeting.kind) {
        MeetingKind.RECURRING -> append("周${weekdays.getOrNull(meeting.day - 1) ?: "未指定"} · 周次 ${formatNumberSet(meeting.weeks).ifEmpty { "无有效周次，请编辑" }}")
        MeetingKind.DATE_SPECIFIC -> {
            append("指定日期 ${meeting.dates.sorted().take(30).joinToString("、")}")
            if (meeting.dates.size > 30) append("…共${meeting.dates.size}天")
        }
        MeetingKind.UNSCHEDULED -> append("待排期 · 尚不产生上课记录")
    }
    if (meeting.customTime != null) append(" · ${meeting.customTime.start}–${meeting.customTime.end}")
    else if (meeting.sections.isNotEmpty()) append(" · 节次 ${formatNumberSet(meeting.sections)}")
}

@Composable
internal fun WeekPane(
    snapshot: TimetableSnapshot,
    monday: LocalDate,
    onMonday: (LocalDate?) -> Unit,
    onCourse: (ScheduledCourse) -> Unit,
) {
    var navigationError by remember { mutableStateOf<String?>(null) }
    fun shift(weeks: Long) {
        try {
            val target = monday.plusWeeks(weeks)
            target.plusDays(6)
            onMonday(target)
            navigationError = null
        } catch (_: DateTimeException) {
            navigationError = "周次已超出日期支持范围，请选择其他周。"
        }
    }
    val dates = remember(monday) { (0..6).mapNotNull { try { monday.plusDays(it.toLong()) } catch (_: DateTimeException) { null } } }
    val week = TimetableRules.teachingWeek(snapshot.settings, monday)
    val occurrences = dates.flatMap { TimetableRules.occurrencesOn(snapshot, it) }
    val compactState = remember(snapshot, monday) {
        projectCompactWeek(snapshot, monday)
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = week?.let { "第 $it 周" } ?: "无教学周",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "$monday 至 ${dates.lastOrNull() ?: monday}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        onClick = { shift(-1) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("上一周", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { onMonday(null); navigationError = null },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("回本周", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = { shift(1) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text("下一周", fontSize = 12.sp)
                    }
                }
            }
            navigationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (week == null && occurrences.isNotEmpty()) {
                Text("显示本周指定日期与调课安排；周期课程仍按学期计算。", style = MaterialTheme.typography.bodySmall)
            } else if (snapshot.settings.startDate == null) {
                Text("请先设置开学日期。", style = MaterialTheme.typography.bodySmall)
            } else if (week == null) {
                Text("当前周不在本学期内。", style = MaterialTheme.typography.bodySmall)
            } else if (occurrences.isEmpty()) {
                Text("本周没有课程。", style = MaterialTheme.typography.bodySmall)
            }
        }
        CompactWeekGrid(
            state = compactState,
            onCourse = onCourse,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
internal fun CourseManagementPane(snapshot: TimetableSnapshot, enabled: Boolean, onAdd: () -> Unit, onCourse: (ScheduledCourse) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("全部课程 · ${snapshot.courses.size}", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onAdd, enabled = enabled) { Text("新增课程") }
        }
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (snapshot.courses.isEmpty()) item { Text("还没有课程，点击新增课程录入真实安排。") }
            items(snapshot.courses, key = { it.id }) { course ->
                Card(onClick = { onCourse(course) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(course.courseName, Modifier.clickable { onCourse(course) }, style = MaterialTheme.typography.titleMedium)
                        if (course.teacher.isNotBlank()) Text(course.teacher)
                        if (course.room.isNotBlank()) Text(course.room)
                        course.meetings.forEach { meeting ->
                            Text(meetingScheduleSummary(meeting))
                            meeting.experimentProject?.takeIf { it.isNotBlank() }?.let { Text("项目：$it") }
                            meeting.group?.takeIf { it.isNotBlank() }?.let { Text("分组：$it") }
                            if (meeting.exceptions.isNotEmpty()) Text("含 ${meeting.exceptions.size} 条停课、调课或加课记录")
                            if (meeting.kind != MeetingKind.UNSCHEDULED && meeting.customTime == null && meeting.sections.any { number -> snapshot.periods.none { it.number == number } }) {
                                Text("含未定位节次，请补充作息或编辑课程。", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
private fun LocalTime.minutes() = hour * 60 + minute
