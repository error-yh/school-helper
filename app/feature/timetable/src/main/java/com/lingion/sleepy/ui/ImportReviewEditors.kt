/* SPDX-License-Identifier: GPL-3.0-only
 * Reuses the Stage 2/Sleepy-derived MeetingForm, saver and FormField; source correction is separate from persistence.
 */
package com.lingion.sleepy.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lingion.sleepy.domain.formatNumberSet
import com.lingion.sleepy.domain.imports.*
import com.lingion.sleepy.domain.model.MeetingKind as StoredKind
import com.lingion.sleepy.importing.MeetingKind as SourceKind
import com.lingion.sleepy.importing.ImportField
import com.lingion.sleepy.importing.RichMeetingDraft

@Composable
internal fun ImportIdentityPane(state:ImportFlowState,model:ImportFlowViewModel) {
    val session=state.session ?: return
    Text("确认课程归属",style=MaterialTheme.typography.titleMedium)
    Text("同组安排归入同一门课程。组号可以自行修改；同名课程不会自动合并。")
    val assignments=session.selectedMeetings.sortedWith(compareBy({it.courseIndex},{it.meetingIndex})).associateWith { state.identityAssignments[it] ?: (it.courseIndex!!+1).toString() }
    assignments.forEach { (source,group)->
        val title=session.source.format.draft!!.courses[source.courseIndex!!].name
        FormField("${title} · 安排${source.meetingIndex!!+1}归属组",group,{model.identityGroup(source,it)},enabled=!state.busy)
    }
    assignments.values.distinct().forEach { group ->
        FormField("组 $group 最终课程名",state.identityNames[group].orEmpty(),{model.identityName(group,it)},hint="同一原课程可留空；合并不同名称时请填写",enabled=!state.busy)
        if(state.mode==ImportMode.APPEND) {
            Text("组 $group 关联：")
            TextButton(onClick={model.identityBinding(group,null)},enabled=!state.busy) { Text(if(group !in state.identityBindings) "✓ 本次独立课程" else "本次独立课程") }
            state.target?.logicalBases.orEmpty().distinctBy { it.courseId }.forEach { base ->
                val course=state.target?.snapshot?.courses?.singleOrNull { it.id==base.courseId }
                if(course!=null) TextButton(onClick={model.identityBinding(group,base)},enabled=!state.busy) {
                    Text("${if(state.identityBindings[group]==base) "✓ " else ""}关联已有：${course.courseName}")
                }
            }
        }
    }
    Button(onClick=model::confirmIdentityDraft,enabled=!state.busy && assignments.isNotEmpty()) { Text(if(state.identityReview!=null) "课程归属已确认" else "确认上述课程归属") }
}

@Composable
internal fun ImportMeetingCorrectionDialog(session:ImportReviewSession,source:ReviewLocator,busy:Boolean,error:String?,
    onSave:(RichMeetingDraft,Set<ImportField>)->Unit,onClose:()->Unit) {
    val raw=session.source.format.draft!!.courses[source.courseIndex!!].meetings[source.meetingIndex!!]
    val rows=rememberSaveable(session.source.contentDigest,source.courseIndex,source.meetingIndex,saver=meetingFormsSaver) {
        mutableStateListOf(MeetingForm(null,session.context.maxWeek).apply {
            kind=StoredKind.valueOf(raw.kind.name);day=raw.dayOfWeek?.toString() ?: "0"
            weeks=if(raw.kind==SourceKind.RECURRING) raw.weeks.sorted().joinToString(",") else ""
            declaredWeeks=if(raw.kind!=SourceKind.RECURRING) raw.weeks.sorted().joinToString(",") else ""
            sections=raw.sections.sorted().joinToString(",");custom=raw.customTime!=null
            start=raw.customTime?.start?.toString().orEmpty();end=raw.customTime?.end?.toString().orEmpty()
            dates=raw.dates.sorted().joinToString(",");teacher=raw.teacher.orEmpty();overrideTeacher=raw.teacher!=null
            room=raw.room.orEmpty();overrideRoom=raw.room!=null;group=raw.group.orEmpty();project=raw.experimentProject.orEmpty();isExperiment=raw.isExperiment
        })
    }
    val form=rows.single()
    var touched by rememberSaveable(session.source.contentDigest,source.courseIndex,source.meetingIndex) { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    fun fields()=touched.split(',').filter { it.isNotEmpty() }.map(ImportField::valueOf).toSet()
    fun touch(vararg values:ImportField) { touched=(fields()+values).joinToString(",") { it.name } }
    val critical=setOf(ImportField.TIME,ImportField.DAY_OF_WEEK,ImportField.WEEKS,ImportField.TARGET_DATE)
    AlertDialog(onDismissRequest={if(!busy) onClose()},title={Text("修正识别安排")},text={
        Column(Modifier.heightIn(max=520.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("原文及原识别警告继续保留。只对已修改或重新核对、且验证有效的字段解除错误；保存课表前仍须重新审核。")
            Row {
                StoredKind.entries.forEach { kind -> TextButton(onClick={form.kind=kind;touch(*critical.toTypedArray())},enabled=!busy) {
                    Text("${if(form.kind==kind) "✓ " else ""}${when(kind) { StoredKind.RECURRING->"周期";StoredKind.DATE_SPECIFIC->"指定日期";StoredKind.UNSCHEDULED->"待排期" }}")
                } }
            }
            FormField("修正星期",form.day,{form.day=it;touch(ImportField.DAY_OF_WEEK)},hint="1到7；非周期可填0",enabled=!busy)
            if(form.kind==StoredKind.RECURRING) FormField("修正周次",form.weeks,{form.weeks=it;touch(ImportField.WEEKS)},hint="例如1,3,5,8",enabled=!busy)
            else FormField("来源标注周次",form.declaredWeeks,{form.declaredWeeks=it;touch(ImportField.WEEKS)},enabled=!busy)
            if(form.kind==StoredKind.DATE_SPECIFIC) FormField("修正日期",form.dates,{form.dates=it;touch(ImportField.TARGET_DATE)},hint="YYYY-MM-DD，逗号分隔",enabled=!busy)
            FormField("修正节次",form.sections,{form.sections=it;touch(ImportField.TIME)},enabled=!busy)
            Row { Checkbox(checked=form.custom,onCheckedChange={form.custom=it;touch(ImportField.TIME)},enabled=!busy);Text("使用真实时间（优先于节次）") }
            if(form.custom) {
                FormField("修正开始时间",form.start,{form.start=it;touch(ImportField.TIME)},hint="HH:mm",enabled=!busy)
                FormField("修正结束时间",form.end,{form.end=it;touch(ImportField.TIME)},hint="HH:mm",enabled=!busy)
            }
            FormField("修正教师",form.teacher,{form.teacher=it;form.overrideTeacher=true;touch(ImportField.TEACHER)},enabled=!busy)
            FormField("修正地点",form.room,{form.room=it;form.overrideRoom=true;touch(ImportField.ROOM)},enabled=!busy)
            FormField("修正分组",form.group,{form.group=it;touch(ImportField.GROUP)},enabled=!busy)
            FormField("修正实验项目",form.project,{form.project=it;touch(ImportField.EXPERIMENT_PROJECT)},enabled=!busy)
            Row { Checkbox(checked=form.isExperiment,onCheckedChange={form.isExperiment=it;touch(ImportField.EXPERIMENT_PROJECT)},enabled=!busy);Text("实验或实践") }
            Row { Checkbox(checked=fields().containsAll(critical),onCheckedChange={checked->touched=(if(checked) fields()+critical else fields()-critical).joinToString(",") { it.name }},enabled=!busy);Text("已重新核对星期、周次、日期和时间") }
            (localError ?: error)?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        }
    },confirmButton={TextButton(onClick={
        try {
            require(fields().isNotEmpty()) { "请先修改字段或勾选重新核对。" }
            val parsed=form.asMeeting(session.context.maxWeek,source.meetingIndex!!+1)
            val replacement=raw.copy(kind=SourceKind.valueOf(parsed.kind.name),dayOfWeek=parsed.day.takeIf { it>0 },
                weeks=if(parsed.kind==StoredKind.RECURRING) parsed.weeks else parsed.declaredWeeks,sections=parsed.sections,
                customTime=parsed.customTime?.let { com.lingion.sleepy.importing.DraftTimeRange(it.start,it.end) },dates=parsed.dates,
                teacher=parsed.teacher,room=parsed.room,group=parsed.group,experimentProject=parsed.experimentProject,isExperiment=parsed.isExperiment)
            localError=null;onSave(replacement,fields())
        } catch(failure:IllegalArgumentException) { localError=failure.message ?: "请检查修正内容。" }
        catch(_:java.time.DateTimeException) { localError="日期或时间无效，请检查。" }
    },enabled=!busy) { Text("应用修正") }},dismissButton={TextButton(onClick=onClose,enabled=!busy) { Text("取消修正") }})
}
