/* SPDX-License-Identifier: GPL-3.0-only
 * SAF picker and preview lifetime adapted from Sleepy ImportSheet.kt. */
package com.lingion.sleepy.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lingion.sleepy.domain.imports.sameLessonSourceGroups
import com.lingion.sleepy.domain.imports.ImportMode
import com.lingion.sleepy.domain.imports.ConflictReviewChoice
import com.lingion.sleepy.domain.imports.ExceptionAttachment
import com.lingion.sleepy.domain.imports.ReviewedCourseIdentity
import com.lingion.sleepy.domain.imports.TimeResolutionChoice
import com.lingion.sleepy.importing.RichExceptionKind
import com.lingion.sleepy.domain.imports.ReviewRequirementKind
import com.lingion.sleepy.domain.imports.ReviewLocator
import com.lingion.sleepy.domain.repository.ImportRepository
import java.time.LocalDate

@Composable
internal fun ImportFlowScreen(repository: ImportRepository, onNavigateToNoticeAdjustment: (() -> Unit)? = null, onClose: () -> Unit) {
    val model: ImportFlowViewModel = viewModel(factory=remember(repository) { ImportFlowViewModel.Factory(repository) })
    val state by model.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val loader=remember(context.applicationContext) { AndroidImportDocumentLoader(context.applicationContext) }
    var documentKind by rememberSaveable { mutableStateOf(ImportDocumentKind.AUTO.name) }
    var dateText by remember { mutableStateOf(state.referenceDate.toString()) }
    var editingCourse by rememberSaveable { mutableIntStateOf(-1) }
    var editingMeeting by rememberSaveable { mutableIntStateOf(-1) }
    var showSource by rememberSaveable { mutableStateOf(false) }
    var showAdjustments by rememberSaveable { mutableStateOf(false) }
    val sourceRequester=remember { BringIntoViewRequester() }
    val errorRequester=remember { BringIntoViewRequester() }
    val completionRequester=remember { BringIntoViewRequester() }
    LaunchedEffect(state.correctionSerial) { editingCourse=-1;editingMeeting=-1 }
    LaunchedEffect(state.session?.source?.sessionId) {
        if(state.session!=null) { withFrameNanos { };sourceRequester.bringIntoView() }
    }
    LaunchedEffect(state.problems,state.preview) {
        if(state.problems.isNotEmpty() && state.preview==null && editingCourse<0) { withFrameNanos { };errorRequester.bringIntoView() }
    }
    LaunchedEffect(state.completed) {
        if(state.completed) { showAdjustments=false;withFrameNanos { };completionRequester.bringIntoView() }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) model.loadDocument(loader,uri,ImportDocumentKind.valueOf(documentKind))
    }
    Column(Modifier.semantics {testTagsAsResourceId=true}.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("导入课表",style=MaterialTheme.typography.titleLarge)
            TextButton(onClick=onClose,enabled=!state.busy) { Text("关闭") }
        }
        Text("来源在本机处理。检查结果并确认后才会保存。")
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            ImportDocumentKind.entries.forEach { kind ->
                FilterChip(selected=documentKind==kind.name,onClick={documentKind=kind.name;model.documentKind(kind)},enabled=!state.busy,
                    label={Text(when(kind) { ImportDocumentKind.AUTO->"自动识别";ImportDocumentKind.IMAGE->"图片";ImportDocumentKind.TEXT->"文字文件";else->kind.name })})
            }
        }
        OutlinedButton(onClick={picker.launch(arrayOf("*/*"))},enabled=!state.busy) { Text("选择课表文件或图片") }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=state.kind==ImportTextKind.JSON,onClick={model.kind(ImportTextKind.JSON)},label={Text("JSON")},enabled=!state.busy)
            FilterChip(selected=state.kind==ImportTextKind.NOTICE,onClick={model.kind(ImportTextKind.NOTICE)},label={Text("通知文字")},enabled=!state.busy)
        }
        if(state.documentSelected) Text("已选择本机文件。更换格式、模式或日期依据后，请重新读取并审核。")
        else OutlinedTextField(value=state.text,onValueChange=model::text,modifier=Modifier.fillMaxWidth().heightIn(min=120.dp,max=260.dp).semantics { contentDescription=if(state.kind==ImportTextKind.JSON) "粘贴 JSON" else "粘贴通知" },enabled=!state.busy,label={Text(if(state.kind==ImportTextKind.JSON) "粘贴 JSON" else "粘贴通知")})
        if(state.kind==ImportTextKind.NOTICE && onNavigateToNoticeAdjustment!=null) {
            Text("提示：此处用于批量导入新课程。若需对已有课程进行单次调课、停课或补课，请使用【文字通知调课】。", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.primary)
            TextButton(onClick=onNavigateToNoticeAdjustment, modifier=Modifier.testTag("import-btn-switch-to-notice-adjustment")) {
                Text("前往文字通知调课 →")
            }
        }
        if(state.kind==ImportTextKind.NOTICE || state.documentSelected) {
            OutlinedTextField(value=dateText,onValueChange={ value -> dateText=value; val date=runCatching { LocalDate.parse(value) }.getOrNull(); if(date==null) model.invalidReferenceDate() else model.referenceDate(date) },label={Text("相对日期基准（年-月-日）")},modifier=Modifier.semantics {contentDescription="相对日期基准（年-月-日）"},singleLine=true,enabled=!state.busy)
        }
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilterChip(selected=state.mode==ImportMode.APPEND,onClick={model.mode(ImportMode.APPEND)},label={Text("追加")},enabled=!state.busy)
            FilterChip(selected=state.mode==ImportMode.REPLACE,onClick={model.mode(ImportMode.REPLACE)},label={Text("替换本学期")},enabled=!state.busy)
        }
        Button(onClick=model::parse,enabled=!state.busy && (state.text.isNotBlank() || state.documentSelected) && runCatching { LocalDate.parse(dateText) }.isSuccess) { Text("读取内容") }
        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.tables?.let { tables -> ImportTableMappingPane(tables,!state.busy,model::mapDocument) }
        state.session?.let { session ->
            HorizontalDivider()
            Text("来源课程条目：${session.source.format.draft?.courses?.size ?: 0} · 安排：${session.source.format.draft?.courses.orEmpty().sumOf {it.meetings.size}}",style=MaterialTheme.typography.labelLarge)
            Text("来源内容",modifier=Modifier.bringIntoViewRequester(sourceRequester),style=MaterialTheme.typography.titleMedium)
            Row {
                TextButton(onClick={showSource=true}) {Text("查看原文")}
                TextButton(onClick={showAdjustments=!showAdjustments},enabled=!state.busy) {Text(if(showAdjustments) "收起修改" else "修改识别结果")}
            }
            Text("已选 ${session.selectedMeetings.size} 条安排，课程明细将在预览中展示。",style=MaterialTheme.typography.bodySmall)
            Text("文件中有明确时间的按原文保留；只有节次的按独立作息设置安排。",style=MaterialTheme.typography.bodySmall)
            val unplanned=session.requirements.count {it.kind==ReviewRequirementKind.KEEP_UNSCHEDULED}
            if(unplanned>0) Text("$unplanned 条暂不排期，仍会保留到课程管理。",style=MaterialTheme.typography.bodySmall)
            val grouped=session.requirements.count {it.code.startsWith("SAME_LESSON_")}
            if(grouped>0) Text("$grouped 组同次授课记录会合并展示，保留全部地点、原节次与原文。",style=MaterialTheme.typography.bodySmall)
            val notices=session.requirements.filter {it.kind==ReviewRequirementKind.EVIDENCE && !it.code.startsWith("SAME_LESSON_")}
                .map {importReviewMessage(it.code)}.distinct()
            notices.forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
            Button(onClick=model::check,enabled=!state.busy) {Text("核对并预览")}
            if(showAdjustments || session.source.format.pendingExceptions.isNotEmpty()) {
                Row {
                    TextButton(onClick={model.selectAll(true)},enabled=!state.busy) {Text("全选")}
                    TextButton(onClick={model.selectAll(false)},enabled=!state.busy) {Text("全不选")}
                }
            session.source.format.draft?.courses?.forEachIndexed { courseIndex, course ->
                Text(session.context.courseIdentities[courseIndex]?.name ?: course.name.ifBlank { "未命名课程（待补充）" },modifier=Modifier,style=MaterialTheme.typography.titleSmall)
                var correctedName by rememberSaveable(session.source.contentDigest,courseIndex) { mutableStateOf(session.context.courseIdentities[courseIndex]?.name ?: course.name) }
                FormField("课程${courseIndex+1}使用名称",correctedName,{correctedName=it;model.clearIdentityReview()},enabled=!state.busy)
                TextButton(onClick={model.renameCourse(courseIndex,correctedName)},enabled=!state.busy && correctedName.isNotBlank(),modifier=Modifier.semantics {contentDescription="应用课程${courseIndex+1}名称"}) { Text("应用课程名称") }
                session.context.courseIdentities[courseIndex]?.let { Text("当前使用名称：${it.name}") }
                course.meetings.forEachIndexed { meetingIndex,m ->
                    val locator=ReviewLocator(courseIndex=courseIndex,meetingIndex=meetingIndex)
                    Row {
                        Checkbox(checked=locator in session.selectedMeetings,onCheckedChange={model.selectMeeting(locator,it)},enabled=!state.busy)
                        Text("${when(m.kind) { com.lingion.sleepy.importing.MeetingKind.RECURRING->"周期";com.lingion.sleepy.importing.MeetingKind.DATE_SPECIFIC->"指定日期";else->"待排期" }} · 星期 ${m.dayOfWeek ?: "—"} · 周次 ${m.weeks.sorted().joinToString()} · 节次 ${m.sections.sorted().joinToString()}")
                    }
                    if(m.dates.isNotEmpty()) Text("日期：${m.dates.sorted().joinToString()}")
                    m.customTime?.let { Text("时间：${it.start}–${it.end}") }
                    Text("教师：${m.teacher ?: "待补充"} · 地点：${m.room ?: "待补充"}")
                    m.experimentProject?.let { Text("项目：$it") };m.group?.let { Text("分组：$it") }
                    TextButton(onClick={editingCourse=courseIndex;editingMeeting=meetingIndex},enabled=!state.busy) { Text("修正安排 ${courseIndex+1}-${meetingIndex+1}") }
                    if(locator in session.selectedMeetings) m.exceptions.forEachIndexed { exceptionIndex, change ->
                        val exception=locator.copy(exceptionIndex=exceptionIndex)
                        val attachment=ExceptionAttachment.Bound(session.inputBase(locator))
                        Text("例外：${change.kind} ${change.originalDate ?: ""} → ${change.targetDate ?: ""}")
                        FilterChip(selected=state.attachments[exception]==attachment,onClick={model.attach(exception,attachment)},label={Text("确认关联此安排")},enabled=!state.busy)
                    }
                }
            }
            session.source.format.pendingExceptions.forEachIndexed { index, pending ->
                val locator=ReviewLocator(pendingIndex=index)
                Row {
                    Checkbox(checked=index in session.selectedPending,onCheckedChange={model.selectPending(index,it)},enabled=!state.busy)
                    Text("通知：${pending.exception.kind} ${pending.exception.originalDate ?: ""} ${pending.exception.targetDate ?: ""}",modifier=if(index==0 && session.source.format.draft?.courses.isNullOrEmpty()) Modifier.bringIntoViewRequester(sourceRequester) else Modifier)
                }
                if(index in session.selectedPending) {
                    Text("选择要关联的安排：")
                    session.selectedMeetings.forEach { base ->
                        val title=session.source.format.draft!!.courses[base.courseIndex!!].name
                        val attachment=ExceptionAttachment.Bound(session.inputBase(base))
                        FilterChip(selected=state.attachments[locator]==attachment,onClick={model.attach(locator,attachment)},label={Text("本次导入：$title · 安排 ${base.meetingIndex!!+1}")},enabled=!state.busy)
                    }
                    if(state.mode==ImportMode.APPEND) state.target?.logicalBases.orEmpty().forEach { base ->
                        val title=state.target?.snapshot?.courses?.singleOrNull { it.id==base.courseId }?.courseName ?: "课程"
                        val attachment=ExceptionAttachment.Bound(base)
                        val detail=state.target?.logicalBaseDetails?.singleOrNull { it.reference==base }
                        FilterChip(selected=state.attachments[locator]==attachment,onClick={model.attach(locator,attachment)},label={Text("已有课程：$title · ${detail?.let(::logicalBaseSummary) ?: "原安排"}")},enabled=!state.busy)
                    }
                    if(pending.exception.kind==RichExceptionKind.EXTRA) {
                        var newName by remember(index) { mutableStateOf("") }
                        OutlinedTextField(value=newName,onValueChange={newName=it},label={Text("作为新课程时的名称")},modifier=Modifier.semantics {contentDescription="作为新课程时的名称"},singleLine=true)
                        TextButton(onClick={if(newName.isNotBlank()) model.attach(locator,ExceptionAttachment.AsNewCourse(ReviewedCourseIdentity(newName)))},enabled=newName.isNotBlank() && !state.busy) { Text("确认作为新课程") }
                    }
                    if(locator in state.attachments) Text("已选择关联；请核对原日期与目标日期。")
                }
            }
            session.source.format.relativeDates.forEachIndexed { index, proposal ->
                Text("日期原文：${proposal.originalToken}；基准 ${proposal.basisDate}；解析结果 ${proposal.candidate ?: "需要补充依据"}")
            }
                if(session.requirements.any {it.kind==ReviewRequirementKind.IDENTITY}) ImportIdentityPane(state,model)
            }
        }
        state.problems.forEachIndexed { i,value -> Text(importReviewMessage(value),modifier=if(i==0) Modifier.bringIntoViewRequester(errorRequester) else Modifier,color=MaterialTheme.colorScheme.error) }
        if(state.completed) Column(Modifier.bringIntoViewRequester(completionRequester)) {
            Text("导入完成。可关闭此页查看课程。")
            Button(onClick=onClose) { Text("查看课表") }
        }
    }
    if(state.preview!=null && !state.confirming) ImportPlanPreviewDialog(state,model)
    state.session?.let { session ->
        if(showSource) ImportSourceEvidenceDialog(session.source) { showSource=false }
        if(editingCourse>=0 && editingMeeting>=0) {
            val source=ReviewLocator(courseIndex=editingCourse,meetingIndex=editingMeeting)
            ImportMeetingCorrectionDialog(session,source,state.busy,state.problems.firstOrNull()?.let(::importReviewMessage),
                onSave={replacement,fields->model.correctMeeting(source,replacement,fields)},onClose={editingCourse=-1;editingMeeting=-1})
        }
    }
    if(state.confirming) AlertDialog(onDismissRequest=model::cancelConfirmation,title={Text(if(state.mode==ImportMode.REPLACE) "确认替换本学期？" else "确认追加课程？")},
        text={Column {
            Text("目标：本机课表 · ${state.target?.snapshot?.settings?.name ?: "当前学期"}")
            if(state.mode==ImportMode.REPLACE) {
                val existing=state.target?.snapshot?.courses.orEmpty()
                Text("将替换本学期 ${existing.size} 门课程：")
                existing.forEach { Text("• ${it.courseName}") }
            } else Text("将按预览结果追加到本学期。")
        }},
        confirmButton={TextButton(onClick=model::apply) { Text(if(state.mode==ImportMode.REPLACE) "确认替换" else "确认追加") }},
        dismissButton={TextButton(onClick=model::cancelConfirmation) { Text("取消") }})
}
