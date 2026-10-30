/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*

class ImportSourceCorrection internal constructor(val source:ReviewLocator,val fields:Set<ImportField>,
    internal val issueDigests:Set<String>,val beforeDigest:String,val afterDigest:String)

internal fun correctMeetingSource(format:FormatParseResult,p:ReviewLocator,replacement:RichMeetingDraft,fields:Set<ImportField>,maxWeek:Int):Pair<FormatParseResult,ImportSourceCorrection> {
    require(maxWeek in 1..1000 && p.courseIndex!=null && p.meetingIndex!=null && p.exceptionIndex==null && p.pendingIndex==null)
    val allowed=setOf(ImportField.TIME,ImportField.DAY_OF_WEEK,ImportField.WEEKS,ImportField.TARGET_DATE,ImportField.TEACHER,ImportField.ROOM,ImportField.GROUP,ImportField.EXPERIMENT_PROJECT)
    require(fields.isNotEmpty() && fields.all { it in allowed }) { "请选择实际修正或重新核对的字段。" }
    val draft=requireNotNull(format.draft)
    val course=draft.courses.getOrNull(p.courseIndex) ?: throw IllegalArgumentException("未找到课程。")
    val before=course.meetings.getOrNull(p.meetingIndex) ?: throw IllegalArgumentException("未找到安排。")
    if(before.kind!=replacement.kind) require(fields.containsAll(setOf(ImportField.TIME,ImportField.DAY_OF_WEEK,ImportField.WEEKS,ImportField.TARGET_DATE))) { "变更授课类型时须重新核对日期、星期、周次和时间。" }
    val after=before.copy(kind=replacement.kind,
        dayOfWeek=if(ImportField.DAY_OF_WEEK in fields) replacement.dayOfWeek else before.dayOfWeek,
        weeks=if(ImportField.WEEKS in fields) fSet(replacement.weeks) else before.weeks,
        dates=if(ImportField.TARGET_DATE in fields) fSet(replacement.dates) else before.dates,
        sections=if(ImportField.TIME in fields) fSet(replacement.sections) else before.sections,
        customTime=if(ImportField.TIME in fields) replacement.customTime else before.customTime,
        teacher=if(ImportField.TEACHER in fields) replacement.teacher else before.teacher,
        room=if(ImportField.ROOM in fields) replacement.room else before.room,
        group=if(ImportField.GROUP in fields) replacement.group else before.group,
        experimentProject=if(ImportField.EXPERIMENT_PROJECT in fields) replacement.experimentProject else before.experimentProject,
        isExperiment=if(ImportField.EXPERIMENT_PROJECT in fields) replacement.isExperiment else before.isExperiment,
        missingFields=fSet(before.missingFields-fields))
    require(after.sections.all { it in 1..1000 }) { "节次必须在1到1000之间。" }
    require(after.weeks.all { it in 1..(if(after.kind==MeetingKind.RECURRING) maxWeek else 1000) }) { "周次超出允许范围。" }
    require(after.dates.size<=10_000 && after.dates.all { it.year in 1..9999 }) { "指定日期无效或数量过多。" }
    require(after.dayOfWeek==null || after.dayOfWeek in 1..7) { "星期必须在1到7之间，或留空。" }
    after.customTime?.let { require(it.start.second==0 && it.start.nano==0 && it.end.second==0 && it.end.nano==0 && it.end>it.start) { "时间须精确到分钟，结束必须晚于同日开始。" } }
    when(after.kind) {
        MeetingKind.RECURRING -> require(after.dayOfWeek in 1..7 && after.weeks.isNotEmpty() && after.dates.isEmpty()) { "周期安排需要星期和周次，不能带指定日期。" }
        MeetingKind.DATE_SPECIFIC -> require(after.dates.isNotEmpty() && after.dates.all { after.dayOfWeek==null || it.dayOfWeek.value==after.dayOfWeek }) { "请填写实际日期并核对星期。" }
        MeetingKind.UNSCHEDULED -> require(after.dates.isEmpty() && after.exceptions.isEmpty()) { "待排期安排不能携带实际日期或停调课。" }
    }
    require(after.kind==MeetingKind.UNSCHEDULED || after.customTime!=null || after.sections.isNotEmpty()) { "请选择节次或填写真实时间。" }
    require(listOfNotNull(after.teacher,after.room,after.group,after.experimentProject).all { it.length<=2048 }) { "补充信息过长。" }
    fun issueField(code:String):ImportField?=when(code) {
        "INVALID_TIME","INVALID_TIME_RANGE","INCOMPLETE_TIME","AMBIGUOUS_TIME_MAPPING","INVALID_SECTIONS"->ImportField.TIME
        "INVALID_WEEKS"->ImportField.WEEKS;"INVALID_DAY_OF_WEEK"->ImportField.DAY_OF_WEEK;"INVALID_DATE"->ImportField.TARGET_DATE
        else->null
    }
    val owned=before.warnings+(format.issues+draft.issues).filter { it.source!=null && it.source in before.sources }
    val issueDigests=fSet(owned.filter { issueField(it.code) in fields }.map(ImportCanonical::digest))
    val changedCourse=course.copy(meetings=course.meetings.mapIndexed { i,m->if(i==p.meetingIndex) after else m })
    val changed=draft.copy(courses=draft.courses.mapIndexed { i,c->if(i==p.courseIndex) changedCourse else c })
    val location="manual:course:${p.courseIndex}:meeting:${p.meetingIndex}"
    val annotation=ImportSourceRef("manual-correction",location,"人工修正字段："+fields.sortedBy { it.name }.joinToString { it.name },DraftPath(p.courseIndex,p.meetingIndex))
    val sources=format.sources.filterNot { it.sourceId==annotation.sourceId && it.sourceLocator==location }+annotation
    return format.copy(draft=changed,sources=sources) to ImportSourceCorrection(p,fSet(fields),issueDigests,ImportCanonical.digest(before),ImportCanonical.digest(after))
}
