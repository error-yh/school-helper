/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.exactDate
import com.lingion.sleepy.importing.formats.exactTime
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** B5 field decoding only. Shares exact date/time and the authoritative week grammar;
 * does not construct a fictitious ConfirmedColumnMapping or change A/B3 behavior. */
internal class LayoutFields(private val context:OcrLayoutContext) {
    val issues=mutableListOf<ParseIssue>()
    fun issue(code:String) { issues+=layoutIssue(code) }
    fun weeks(text:String?):Set<Int> {
        if(text.isNullOrBlank()) return emptySet()
        val result=WeekExpressionParser.parse(compactMatch(text),context.format.maxWeek)
        if(result.errors.isNotEmpty()) issue("INVALID_WEEKS")
        return result.weeks
    }
    /** The printed red calendar icon has been observed as 曲/兰 in native OCR.
     * Resolve only in a declared dated-row week field, with an exact literal 第...周
     * expression AND independent semester/date/day agreement. Never repair digits,
     * parity, unknown prefixes, or a mismatch; keep full source and a review warning.
     */
    fun datedWeeks(text:String?,date:LocalDate?,day:Int?):Set<Int> {
        if(text.isNullOrBlank()) return emptySet()
        val raw=compactMatch(text)
        val plain=WeekExpressionParser.parse(raw,context.format.maxWeek)
        if(plain.errors.isEmpty()) return plain.weeks
        val monday=context.confirmedTermMonday
        if(date!=null && monday!=null && (day==null || day==date.dayOfWeek.value) &&
            raw.length>2 && raw.first() in setOf('曲','兰') && raw[1]=='第') {
            val literal=WeekExpressionParser.parse(raw.drop(1),context.format.maxWeek)
            val teachingWeek=Math.floorDiv(ChronoUnit.DAYS.between(monday,date),7L)+1L
            if(literal.errors.isEmpty() && teachingWeek in literal.weeks.map {it.toLong()}) {
                issue("WEEK_ICON_PREFIX_REVIEW_REQUIRED")
                return literal.weeks
            }
        }
        return weeks(text)
    }
    fun sections(text:String?):Set<Int> {
        if(text.isNullOrBlank()) return emptySet()
        val result=WeekExpressionParser.parse(compactMatch(text).removeSuffix("节"),100)
        if(result.errors.isNotEmpty()) issue("INVALID_SECTIONS")
        return result.weeks
    }
    fun date(text:String?):LocalDate? {
        if(text.isNullOrBlank()) return null
        val t=compactMatch(text)
        val iso=if(t.matches(Regex("[0-9]{8}"))) "${t.take(4)}-${t.substring(4,6)}-${t.takeLast(2)}" else t
        return exactDate(iso) ?: run { issue("INVALID_DATE");null }
    }
    fun time(text:String?):DraftTimeRange? {
        if(text.isNullOrBlank()) return null
        val m=Regex("([0-9]{2}:[0-9]{2})[-~～至]([0-9]{2}:[0-9]{2})").matchEntire(compactMatch(text))
        val start=m?.let { exactTime(it.groupValues[1]) };val end=m?.let { exactTime(it.groupValues[2]) }
        if(start==null || end==null || end<=start) { issue("INVALID_TIME");return null }
        return DraftTimeRange(start,end)
    }
    fun dateDeclarations(date:LocalDate?,day:Int?,weeks:Set<Int>) {
        if(date==null) return
        if(day!=null && date.dayOfWeek.value!=day) issue("DATE_WEEKDAY_MISMATCH")
        context.confirmedTermMonday?.let { start ->
            val teachingWeek=Math.floorDiv(ChronoUnit.DAYS.between(start,date),7L)+1L
            if(weeks.isNotEmpty() && teachingWeek !in weeks.map { it.toLong() }) issue("DATE_DECLARED_WEEK_MISMATCH")
        }
    }
}
internal fun layoutDay(text:String):Int? {
    val t=compactMatch(text).removePrefix("星期").removePrefix("礼拜").removePrefix("周")
    val n=t.toIntOrNull() ?: mapOf("一" to 1,"二" to 2,"三" to 3,"四" to 4,"五" to 5,"六" to 6,"日" to 7,"天" to 7)[t]
    return n?.takeIf { it in 1..7 }
}
internal fun isDayAnchor(text:String)=compactMatch(text).matches(Regex("(?:星期|礼拜|周)[一二三四五六日天1-7]"))
internal fun isFullDateToken(text:String)=compactMatch(text).matches(Regex("[0-9]{8}|[0-9]{4}-[0-9]{2}-[0-9]{2}"))
internal fun sectionAxis(text:String):Set<Int>? {
    val t=compactMatch(text).replace(Regex("(?:[0-9]{2}:[0-9]{2})[-~～至](?:[0-9]{2}:[0-9]{2})$"),"")
    if(!t.matches(Regex("第?[0-9]{1,3}(?:[-,、][0-9]{1,3})*节?"))) return null
    val result=WeekExpressionParser.parse(t.removeSuffix("节"),100)
    return result.weeks.takeIf { result.errors.isEmpty() }
}
internal fun individualAxisClock(text:String):Pair<Int,DraftTimeRange>? {
    val m=Regex("^第?([0-9]{1,3})节?\\s*([0-9]{2}:[0-9]{2})[-~～至]([0-9]{2}:[0-9]{2})$").matchEntire(layoutMatch(text)) ?: return null
    val section=m.groupValues[1].toIntOrNull()?.takeIf { it in 1..100 } ?: return null
    val start=exactTime(m.groupValues[2]) ?: return null
    val end=exactTime(m.groupValues[3])?.takeIf { it>start } ?: return null
    return section to DraftTimeRange(start,end)
}
internal fun weekAxis(text:String,maxWeek:Int):Set<Int>? {
    val t=compactMatch(text)
    if(!t.matches(Regex("第?[0-9]{1,4}(?:[-,、][0-9]{1,4})*周"))) return null
    val result=WeekExpressionParser.parse(t,maxWeek)
    return result.weeks.takeIf { result.errors.isEmpty() }
}
internal data class LayoutDecodedRecord(val course:RichCourseDraft,val fields:Set<String>,val alternatives:List<String> = emptyList(),val hasUnknown:Boolean=false,val completeTitle:String=course.name,
    val declaredSections:List<Boolean> = course.meetings.map { false })

/** A title plus schedule/metadata can occupy multiple real spans/lines. All original
 * boxes are retained; splitting semantic records never fabricates sub-span coordinates. */
internal class LayoutRecordDecoder(private val context:OcrLayoutContext) {
    private val aliases=mapOf("课程代码" to "code","课程码" to "code","课程编号" to "code","教学班" to "class","教学班代码" to "class",
        "教师" to "teacher","任课教师" to "teacher","场地" to "room","教室" to "room","实验室" to "room","班组" to "group","班级" to "group","组别" to "group",
        "项目" to "project","实验项目" to "project","项目名称" to "project","周次" to "weeks","节次" to "sections","时间" to "time","校区" to "campus")
    fun isContinuation(text:String):Boolean {
        val t=layoutMatch(text)
        return t.substringBefore(':') in aliases || t.startsWith('(') || t.matches(Regex("第?[0-9][0-9,()单双周节~至-]*"))
    }
    fun decode(raw:String,tokens:List<GeometryToken>,day:Int?,axisSections:Set<Int>,axisWeeks:Set<Int> = emptySet(),date:LocalDate?=null,forceUnscheduled:Boolean=false,practice:Boolean=false,scopeSupportedByClosedCell:Boolean=false):LayoutDecodedRecord? {
        val text=layoutMatch(raw)
        // A slash inside one observed bare title is not a fabricated line separator.
        // Labelled fields/schedules retain the existing grammar and unknown evidence.
        val literalTitle=tokens.singleOrNull()?.text?.takeIf {it==text && it.matches(Regex("[\\p{L}]+(?:/[\\p{L}]+)+"))}
        val parts=if(literalTitle!=null) listOf(text) else text.split('/', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        if(parts.isEmpty()) return null
        val fields=linkedMapOf<String,String>()
        val unknown=mutableListOf<String>()
        var title:String?=null
        var anonymousSchedule:String?=null
        for(part in parts) {
            context.format.cancellation.check()
            val label=part.substringBefore(':');val key=aliases[label]
            when {
                key!=null && part.contains(':') -> if(fields.put(key,part.substringAfter(':'))!=null) unknown+=part
                title==null && !isContinuation(part) -> title=part
                anonymousSchedule==null && (part.startsWith('(') || part.contains(Regex("[0-9]周"))) -> anonymousSchedule=part
                else -> unknown+=part
            }
        }
        var name=title ?: return null
        val d=LayoutFields(context)
        // Numeric parenthetical code belongs only to the title/container, never a room.
        val completeTitle=name
        val codeMatch=Regex("^(.+?)\\(([0-9]{6,})\\)$").matchEntire(name)
        val code=fields["code"]?.takeIf { it.isNotBlank() } ?: codeMatch?.groupValues?.get(2)
        if(codeMatch!=null) name=codeMatch.groupValues[1].trim()
        if(practice) name=name.replace(Regex("\\(共[0-9]+周\\)$"),"").trim()
        val schedule=anonymousSchedule
        val sectionHit=schedule?.let { Regex("^\\(([^()]+)节\\)(.*)$").matchEntire(it) }
        val declaredSections=fields["sections"] ?: sectionHit?.groupValues?.get(1)
        val explicitSections=declaredSections?.let { d.sections(it) }
        val sections=explicitSections ?: axisSections
        val declaredWeeks=fields["weeks"] ?: sectionHit?.groupValues?.get(2) ?: schedule
        val weeks=if(declaredWeeks!=null) d.weeks(declaredWeeks) else axisWeeks
        if(explicitSections!=null && axisSections.isNotEmpty() && explicitSections!=axisSections) d.issue("SECTION_AXIS_MISMATCH")
        if(declaredWeeks!=null && axisWeeks.isNotEmpty() && weeks!=axisWeeks) d.issue("WEEK_AXIS_MISMATCH")
        val time=d.time(fields["time"])
        val triangle=text.contains(Regex("[△▲]"))
        val ambiguous=forceUnscheduled || triangle && !scopeSupportedByClosedCell || text.contains(Regex("上半天|下半天|上午组|下午组")) || unknown.isNotEmpty() && fields.isEmpty() && schedule==null
        if(triangle && scopeSupportedByClosedCell) d.issue("UNEXPLAINED_MARKER_REVIEW_REQUIRED")
        if(ambiguous) d.issue("AMBIGUOUS_CELL_SPAN")
        if(unknown.isNotEmpty()) d.issue("UNPARSED_RECORD_CONTENT")
        val kind=when { practice || ambiguous -> MeetingKind.UNSCHEDULED; date!=null -> MeetingKind.DATE_SPECIFIC; day!=null && weeks.isNotEmpty() && (sections.isNotEmpty() || time!=null) -> MeetingKind.RECURRING;else -> MeetingKind.UNSCHEDULED }
        val sources=tokens.distinctBy { it.id }.map { it.excerpt() }
        val missing=buildSet {
            if(kind==MeetingKind.UNSCHEDULED || sections.isEmpty() && time==null) add(ImportField.TIME)
            if(kind==MeetingKind.UNSCHEDULED || day==null && date==null) add(ImportField.DAY_OF_WEEK)
            if(kind==MeetingKind.RECURRING && weeks.isEmpty()) add(ImportField.WEEKS)
            if(fields["teacher"].isNullOrBlank()) add(ImportField.TEACHER)
            if(fields["room"].isNullOrBlank()) add(ImportField.ROOM)
        }
        val m=RichMeetingDraft(kind,if(kind==MeetingKind.UNSCHEDULED) null else day,weeks,if(practice) emptySet() else sections,time,setOfNotNull(date),
            fields["teacher"],fields["room"],fields["group"],fields["project"],fields["project"]!=null,sources,d.issues.toList(),missing)
        val identityWarnings=if(codeMatch!=null && fields["code"].isNullOrBlank()) listOf(layoutIssue("COURSE_CODE_SUFFIX_REVIEW_REQUIRED")) else emptyList()
        return LayoutDecodedRecord(RichCourseDraft(name,code,fields["class"],listOf(m),sources,warnings=identityWarnings),fields.keys+setOf("name","weeks","sections"),
            if(ambiguous) listOf("UNRESOLVED_SCOPE","INDEPENDENT_SUBGROUPS")+unknown else emptyList(),unknown.isNotEmpty(),completeTitle,listOf(declaredSections!=null))
    }
}
