/* SPDX-License-Identifier: GPL-3.0-only
 * Meeting-block draft/edit pattern adapted from Sleepy contributors:
 * https://github.com/lingion/sleepy/blob/3dafa110f74ac4f32464ce1efddb24197a4091d9/app/src/main/java/com/lingion/sleepy/ui/screen/edit/AddCourseScreen.kt#L128-L170
 * and L256-L308 (independent temporary arrangements, populated for editing).
 * Exact-set parsing, local forms and atomic semester/period editor are new Stage 2 code.
 */
package com.lingion.sleepy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.formatNumberSet
import com.lingion.sleepy.domain.parseNumberSet
import com.lingion.sleepy.domain.model.*
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

@Composable
internal fun FormField(label: String, value: String, onChange: (String) -> Unit,
    modifier: Modifier = Modifier, hint: String? = null, enabled: Boolean = true) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) },
        placeholder = hint?.let { { Text(it) } }, singleLine = true, enabled = enabled,
        modifier = modifier.fillMaxWidth().semantics { contentDescription = label })
}

internal class MeetingForm(private val original: CourseMeeting?, totalWeeks: Int) {
    private val meeting get() = original
    var id = meeting?.id ?: 0
    var day by mutableStateOf(meeting?.day?.toString() ?: "1")
    var weeks by mutableStateOf(meeting?.weeks?.let(::formatNumberSet) ?: "1-$totalWeeks")
    var sections by mutableStateOf(meeting?.sections?.let(::formatNumberSet) ?: "1")
    var custom by mutableStateOf(meeting?.customTime != null)
    var start by mutableStateOf(meeting?.customTime?.start?.toString() ?: "")
    var end by mutableStateOf(meeting?.customTime?.end?.toString() ?: "")
    var overrideTeacher by mutableStateOf(meeting?.teacher != null)
    var teacher by mutableStateOf(meeting?.teacher.orEmpty())
    var overrideRoom by mutableStateOf(meeting?.room != null)
    var room by mutableStateOf(meeting?.room.orEmpty())
    var expanded by mutableStateOf(custom || overrideTeacher || overrideRoom)
    var kind by mutableStateOf(meeting?.kind ?: MeetingKind.RECURRING)
    var dates by mutableStateOf(meeting?.dates?.sorted()?.joinToString(",").orEmpty())
    var declaredWeeks by mutableStateOf(meeting?.declaredWeeks?.let(::formatNumberSet).orEmpty())
    var project by mutableStateOf(meeting?.experimentProject.orEmpty())
    var group by mutableStateOf(meeting?.group.orEmpty())
    var isExperiment by mutableStateOf(meeting?.isExperiment ?: false)
    fun asMeeting(totalWeeks: Int, index: Int, retained: CourseMeeting? = original): CourseMeeting {
        val base = retained?.takeIf { it.id == id }
        val dayNumber = day.toIntOrNull()?.takeIf { it in (if (kind == MeetingKind.RECURRING) 1..7 else 0..7) }
            ?: throw IllegalArgumentException("安排$index 的星期请填写1到7；非周期安排可填0表示未指定。")
        val selectedWeeks = if (kind == MeetingKind.RECURRING) parseNumberSet(weeks, totalWeeks, "安排$index 周次") else emptySet()
        val selectedSections = if ((custom || kind == MeetingKind.UNSCHEDULED) && sections.isBlank()) emptySet() else parseNumberSet(sections, Int.MAX_VALUE, "安排$index 节次")
        val range = if (custom) TimeRange(parseTime(start), parseTime(end)) else null
        val actualDates = if (kind == MeetingKind.DATE_SPECIFIC) parseCourseDates(dates) else emptySet()
        val declared = if (declaredWeeks.isBlank()) emptySet() else parseNumberSet(declaredWeeks, 1000, "来源周次")
        fun optional(value: String, previous: String?) = if (value == previous.orEmpty()) previous else value.trim().takeIf { it.isNotEmpty() }
        // Keep the complete original record, including unedited exceptions and source evidence.
        val seed = base ?: CourseMeeting(day = dayNumber, weeks = selectedWeeks, sections = selectedSections,
            customTime = range, kind = kind, dates = actualDates)
        return seed.copy(id = id, day = dayNumber, weeks = selectedWeeks, sections = selectedSections,
            customTime = range, teacher = teacher.takeIf { overrideTeacher }, room = room.takeIf { overrideRoom },
            kind = kind, dates = actualDates, declaredWeeks = declared,
            experimentProject = optional(project, base?.experimentProject), group = optional(group, base?.group),
            isExperiment = isExperiment)
    }
}

internal fun parseCourseDates(text: String): Set<LocalDate> {
    require(text.length <= 110_000) { "日期列表过长。" }
    val parts = text.replace('，', ',').split(',').map(String::trim)
    require(parts.size in 1..10_000 && parts.all { it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) }) {
        "日期请使用 YYYY-MM-DD，多个日期用逗号分隔。"
    }
    return parts.map(::parseDate).toSortedSet()
}

// Save raw text rather than parsed meetings: incomplete and invalid drafts must also survive recreation.
internal val meetingFormsSaver = listSaver<SnapshotStateList<MeetingForm>, Any>(
    save = { rows -> listOf("calendar-v2") + rows.flatMap { listOf(it.id, it.day, it.weeks, it.sections, it.custom, it.start, it.end,
        it.overrideTeacher, it.teacher, it.overrideRoom, it.room, it.expanded,
        it.kind.name, it.dates, it.declaredWeeks, it.project, it.group, it.isExperiment) } },
    restore = { values -> mutableStateListOf<MeetingForm>().apply {
        val current = values.firstOrNull() == "calendar-v2"
        (if (current) values.drop(1) else values).chunked(if (current) 18 else 12).forEach { row -> add(MeetingForm(null, 1).apply {
            id = row[0] as Long; day = row[1] as String; weeks = row[2] as String; sections = row[3] as String
            custom = row[4] as Boolean; start = row[5] as String; end = row[6] as String
            overrideTeacher = row[7] as Boolean; teacher = row[8] as String
            overrideRoom = row[9] as Boolean; room = row[10] as String; expanded = row[11] as Boolean
            if (current) {
                kind = MeetingKind.valueOf(row[12] as String); dates = row[13] as String
                declaredWeeks = row[14] as String; project = row[15] as String
                group = row[16] as String; isExperiment = row[17] as Boolean
            }
        }) }
    } },
)

@Composable
internal fun CourseEditor(initial: ScheduledCourse?, snapshot: TimetableSnapshot, enabled: Boolean, saving: Boolean,
    error: String?, onSave: (CourseDraft) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var name by rememberSaveable { mutableStateOf(initial?.courseName.orEmpty()) }
    var teacher by rememberSaveable { mutableStateOf(initial?.teacher.orEmpty()) }
    var room by rememberSaveable { mutableStateOf(initial?.room.orEmpty()) }
    var color by rememberSaveable { mutableLongStateOf(initial?.color ?: 0xFF6750A4) }
    val meetings = rememberSaveable(saver = meetingFormsSaver) { mutableStateListOf<MeetingForm>().apply {
        if (initial == null) add(MeetingForm(null, snapshot.settings.totalWeeks))
        else addAll(initial.meetings.map { MeetingForm(it, snapshot.settings.totalWeeks) })
    } }
    var formError by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize()) {
        Text(if (initial == null) "新增课程" else "编辑课程", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { FormField("课程名称", name, { name = it }, enabled = !saving) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormField("教师", teacher, { teacher = it }, Modifier.weight(1f), enabled = !saving)
                    FormField("地点", room, { room = it }, Modifier.weight(1f), enabled = !saving)
                }
            }
            item {
                Text("课程颜色", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    listOf(0xFF6750A4, 0xFF006C4C, 0xFF00639B, 0xFF984061, 0xFF805600, 0xFF5A5F70).forEachIndexed { index, option ->
                        Box(Modifier.size(32.dp).background(Color(option.toInt()), CircleShape)
                            .border(if (color == option) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            .semantics { contentDescription = "课程颜色${index + 1}${if (color == option) " 已选" else ""}" }
                            .clickable(enabled = !saving) { color = option })
                    }
                }
            }
            itemsIndexed(meetings) { index, meeting ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("安排 ${index + 1}", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { meetings.remove(meeting) }, enabled = !saving) { Text("移除安排") }
                        }
                        Row {
                            listOf(MeetingKind.RECURRING to "周期", MeetingKind.DATE_SPECIFIC to "指定日期", MeetingKind.UNSCHEDULED to "待排期").forEach { (kind, label) ->
                                TextButton(onClick = { meeting.kind = kind }, enabled = !saving) {
                                    Text(if (meeting.kind == kind) "✓ $label" else label)
                                }
                            }
                        }
                        Text("星期：1=周一，7=周日；周次与节次可填 1,3,5 或 1-5。非周期安排可填0表示未指定星期。", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FormField("安排${index + 1}星期", meeting.day, { meeting.day = it }, Modifier.weight(.38f), enabled = !saving)
                            if (meeting.kind == MeetingKind.RECURRING) FormField("安排${index + 1}周次", meeting.weeks, { meeting.weeks = it }, Modifier.weight(.62f), enabled = !saving)
                        }
                        if (meeting.kind == MeetingKind.DATE_SPECIFIC) FormField("安排${index + 1}日期", meeting.dates, { meeting.dates = it }, hint = "YYYY-MM-DD，多个日期用逗号分隔", enabled = !saving)
                        if (meeting.kind == MeetingKind.UNSCHEDULED) Text("待排期只保留课程信息，不在今日或本周产生上课记录。")
                        if (meeting.kind == MeetingKind.RECURRING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { meeting.weeks = formatNumberSet((1..snapshot.settings.totalWeeks).toSet()) }, enabled = !saving) { Text("全周") }
                            TextButton(onClick = { meeting.weeks = formatNumberSet((1..snapshot.settings.totalWeeks).filter { it % 2 == 1 }.toSet()) }, enabled = !saving) { Text("单周") }
                            TextButton(onClick = { meeting.weeks = formatNumberSet((1..snapshot.settings.totalWeeks).filter { it % 2 == 0 }.toSet()) }, enabled = !saving) { Text("双周") }
                        }
                        }
                        FormField("安排${index + 1}节次", meeting.sections, { meeting.sections = it }, enabled = !saving)
                        TextButton(onClick = { meeting.expanded = !meeting.expanded }, enabled = !saving) {
                            Text(if (meeting.expanded) "收起时间与单次信息" else "自定义时间与单次信息")
                        }
                        if (meeting.expanded) {
                            ToggleRow("自定义时间（优先于节次作息）", meeting.custom, !saving) { meeting.custom = it }
                            if (meeting.custom) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FormField("安排${index + 1}开始时间", meeting.start, { meeting.start = it }, Modifier.weight(1f), "HH:mm", !saving)
                                    FormField("安排${index + 1}结束时间", meeting.end, { meeting.end = it }, Modifier.weight(1f), "HH:mm", !saving)
                                }
                                Text("仅支持同一天，结束须晚于开始。节次可留空或保留参考编号。", style = MaterialTheme.typography.bodySmall)
                            }
                            ToggleRow("单独设置教师", meeting.overrideTeacher, !saving) { meeting.overrideTeacher = it }
                            if (meeting.overrideTeacher) FormField("安排${index + 1}教师", meeting.teacher, { meeting.teacher = it }, enabled = !saving)
                            ToggleRow("单独设置地点", meeting.overrideRoom, !saving) { meeting.overrideRoom = it }
                            if (meeting.overrideRoom) FormField("安排${index + 1}地点", meeting.room, { meeting.room = it }, enabled = !saving)
                            Text("未单独设置时沿用课程教师和地点。", style = MaterialTheme.typography.bodySmall)
                            FormField("安排${index + 1}实验项目", meeting.project, { meeting.project = it }, enabled = !saving)
                            FormField("安排${index + 1}分组", meeting.group, { meeting.group = it }, enabled = !saving)
                            ToggleRow("实验或实践", meeting.isExperiment, !saving) { meeting.isExperiment = it }
                            FormField("安排${index + 1}来源标注周次", meeting.declaredWeeks, { meeting.declaredWeeks = it }, hint = "仅作来源说明，不替代指定日期", enabled = !saving)
                            val retained = initial?.meetings?.singleOrNull { it.id == meeting.id }
                            if (!retained?.exceptions.isNullOrEmpty()) Text("保留 ${retained!!.exceptions.size} 条停课、调课或加课记录；变更原安排需要重新核对。", style = MaterialTheme.typography.bodySmall)
                            if (!retained?.provenance.isNullOrEmpty()) Text("原始来源记录会随课程保留。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item { OutlinedButton(onClick = { meetings += MeetingForm(null, snapshot.settings.totalWeeks) }, enabled = !saving) { Text("添加上课安排") } }
        }
        formError?.let { ErrorText(it) }
        error?.let { ErrorText(it) }
        Row(Modifier.fillMaxWidth().imePadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, enabled = !saving, modifier = Modifier.weight(1f)) { Text("取消") }
            Button(onClick = {
                try {
                    val editedMeetings = meetings.mapIndexed { index, form ->
                        form.asMeeting(snapshot.settings.totalWeeks, index + 1, initial?.meetings?.singleOrNull { it.id == form.id })
                    }
                    val draft = (initial?.asDraft() ?: CourseDraft(name, meetings = editedMeetings)).copy(
                        courseName = name, teacher = teacher, room = room, color = color, meetings = editedMeetings)
                    val errors = TimetableRules.validateCourse(draft, snapshot.settings, snapshot.periods)
                    require(errors.isEmpty()) { errors.joinToString("\n") }
                    formError = null; onSave(draft)
                } catch (failure: IllegalArgumentException) { formError = failure.message ?: "请检查课程内容。" }
                catch (_: DateTimeException) { formError = "请使用有效的 HH:mm 同日时间，结束须晚于开始。" }
            }, enabled = enabled, modifier = Modifier.weight(1f)) { Text(if (saving) "正在保存…" else "保存课程") }
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(title, Modifier.weight(1f))
    }
}

internal class PeriodForm(period: Period?) {
    var number by mutableStateOf(period?.number?.toString().orEmpty())
    var start by mutableStateOf(period?.start?.toString().orEmpty())
    var end by mutableStateOf(period?.end?.toString().orEmpty())
    fun asPeriod(): Period = Period(number.toIntOrNull()?.takeIf { it > 0 }
        ?: throw IllegalArgumentException("节次编号请填写正整数。"), parseTime(start), parseTime(end))
}

// Persist raw values, including incomplete period numbers/times, until the user commits or discards them.
internal val periodFormsSaver = listSaver<SnapshotStateList<PeriodForm>, String>(
    save = { rows -> rows.flatMap { listOf(it.number, it.start, it.end) } },
    restore = { values -> mutableStateListOf<PeriodForm>().apply {
        values.chunked(3).forEach { row -> add(PeriodForm(null).apply {
            number = row[0]; start = row[1]; end = row[2]
        }) }
    } },
)

@Composable
internal fun SettingsEditor(snapshot: TimetableSnapshot, enabled: Boolean, saving: Boolean, error: String?,
    onSave: (SemesterSettings, List<Period>) -> Unit, onEditPeriods: () -> Unit) {
    // Use committed configuration content as the reset key, never the course-only snapshot revision.
    var name by rememberSaveable(snapshot.settings) { mutableStateOf(snapshot.settings.name) }
    var start by rememberSaveable(snapshot.settings) { mutableStateOf(snapshot.settings.startDate?.toString().orEmpty()) }
    var weeks by rememberSaveable(snapshot.settings) { mutableStateOf(snapshot.settings.totalWeeks.toString()) }
    var formError by rememberSaveable(snapshot.settings, snapshot.periods) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("学期与作息", style = MaterialTheme.typography.titleLarge) }
            item { FormField("学期名称", name, { name = it }, enabled = !saving) }
            item { FormField("开学日期", start, { start = it }, hint = "YYYY-MM-DD，可留空", enabled = !saving) }
            item { FormField("学期周数", weeks, { weeks = it }, enabled = !saving) }
            item {
                Text("开学日期所属周的周一为第1周，周日为每周最后一天。日期留空时不推测校历。", style = MaterialTheme.typography.bodySmall)
                Text("作息时间单独设置，支持统一课时或每节独立调整。", style = MaterialTheme.typography.bodySmall)
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("作息时间 · ${snapshot.periods.size} 节",style=MaterialTheme.typography.titleMedium)
                        Button(onClick=onEditPeriods,enabled=!saving) {Text("编辑时间表")}
                    }
                }
            }
        }
        formError?.let { ErrorText(it) }
        error?.let { ErrorText(it) }
        Button(onClick = {
            try {
                val total = weeks.toIntOrNull() ?: throw IllegalArgumentException("学期周数请填写1到1000之间的整数。")
                val settings = snapshot.settings.copy(name = name, startDate = start.takeIf { it.isNotBlank() }?.let(::parseDate), totalWeeks = total)
                val parsedPeriods = snapshot.periods
                val errors = TimetableRules.validateSettings(settings) + TimetableRules.validatePeriods(parsedPeriods)
                require(errors.isEmpty()) { errors.joinToString("\n") }
                formError = null; onSave(settings, parsedPeriods)
            } catch (failure: IllegalArgumentException) { formError = failure.message ?: "请检查设置内容。" }
            catch (_: DateTimeException) { formError = "请检查日期与时间格式，日期如 2026-10-19，时间如 08:00。" }
        }, enabled = enabled, modifier = Modifier.fillMaxWidth().imePadding().padding(12.dp)) {
            Text(if (saving) "正在保存…" else "保存设置")
        }
    }
}

internal fun parseDate(value: String): LocalDate = LocalDate.parse(value.trim())
private fun parseTime(value: String): LocalTime {
    require(Regex("[0-9]{2}:[0-9]{2}").matches(value.trim())) { "时间请使用 HH:mm 格式，例如 08:00。" }
    return LocalTime.parse(value.trim())
}
