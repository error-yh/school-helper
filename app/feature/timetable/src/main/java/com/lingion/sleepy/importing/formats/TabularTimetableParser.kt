/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import com.lingion.sleepy.importing.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Converts only the selected table and explicit mapping. No sheet choice, lookup, or persistence. */
class TabularTimetableParser {
    fun parse(table:ImportTable,mapping:ConfirmedColumnMapping,context:FormatParseContext):FormatParseResult = try {
        Decoder(table,mapping,context).decode()
    } catch(failure:ImportRejected) { FormatParseResult(null,issues=listOf(if(failure.code=="INVALID_MAPPING") mappingIssue(failure.code) else publicIssue(failure.code))) }

    private class Decoder(val table:ImportTable,val mapping:ConfirmedColumnMapping,val context:FormatParseContext) {
        val sources=mutableListOf<ImportSourceRef>()
        val issues=mutableListOf<ParseIssue>()
        val unparsed=mutableListOf<ImportSourceRef>()
        val courses=mutableListOf<RichCourseDraft>()
        val courseKeys=linkedMapOf<Int,String>()
        val meetingKeys=linkedMapOf<DraftPath,String>()
        val identities=linkedMapOf<List<String>,Int>()
        val pending=mutableListOf<PendingExceptionAttachment>()
        val attachedSourcePaths=hashMapOf<String,DraftPath>()
        val localMeetingPaths=hashMapOf<Pair<String,String>,DraftPath>()
        var meetingCount=0
        var exceptionCount=0

        fun decode():FormatParseResult {
            validate()
            val lastHeader=mapping.headerRows.maxOrNull() ?: 0
            for(row in table.rows) {
                context.cancellation.check()
                val rowSources=row.cells.map { cell -> context.cancellation.check(); ImportSourceRef(table.sourceId,cell.sourceLocator,cell.rawText,DraftPath(field=mapping.columns.entries.firstOrNull { it.value==cell.column }?.key?.name),row.number,cell.column,cell.formula) }
                val d=RowDecoder(row,mapping,context)
                issues+=row.cells.flatMap { it.issues }
                if(row.cells.any { it.formula!=null }) issues+=mappingIssue("FORMULA_UNSUPPORTED")
                if(row.number<=lastHeader) {
                    sources+=rowSources
                    if(row.number !in mapping.headerRows && row.cells.any { it.rawText.isNotBlank() }) { issues+=mappingIssue("PRE_HEADER_TEXT"); unparsed+=rowSources }
                    continue
                }
                if(row.cells.all { it.rawText.isBlank() && (it.typedValue==null || it.typedValue==CellValue.Empty) && it.formula==null }) { sources+=rowSources; continue }
                val unknown=row.cells.filter { it.column !in mapping.columns.values && it.column !in mapping.ignoredColumns && (it.rawText.isNotEmpty() || it.formula!=null) }
                if(unknown.isNotEmpty()) { issues+=mappingIssue("UNMAPPED_COLUMN"); unparsed+=rowSources.filter { ref->unknown.any { it.sourceLocator==ref.sourceLocator } } }
                val explicitKind=d.text(ImportColumnField.EXCEPTION_KIND)
                if(!explicitKind.isNullOrBlank()) {
                    if(++exceptionCount>context.limits.maxExceptions) reject("LIMIT_EXCEEDED")
                    val kind=RichExceptionKind.entries.firstOrNull { it.name==explicitKind.trim() }
                    if(kind==null) { issues+=mappingIssue("INVALID_EXCEPTION_KIND"); unparsed+=rowSources; sources+=rowSources; continue }
                    val original=d.date(ImportColumnField.ORIGINAL_DATE)
                    val target=d.date(ImportColumnField.TARGET_DATE)
                    if(kind!=RichExceptionKind.EXTRA && original==null || kind!=RichExceptionKind.CANCEL && target==null) d.issue("MISSING_EXCEPTION_DATE")
                    if(kind==RichExceptionKind.CANCEL && target!=null || kind==RichExceptionKind.EXTRA && original!=null) d.issue("EXCEPTION_DATE_KIND_MISMATCH")
                    val sections=d.sections()
                    val time=d.time()
                    val exception=RichExceptionDraft(kind,original,target,time,if(mapping.columns.containsKey(ImportColumnField.SECTIONS) && !d.text(ImportColumnField.SECTIONS).isNullOrBlank()) sections else null,
                        d.metadata(ImportColumnField.TEACHER),d.metadata(ImportColumnField.ROOM),d.metadata(ImportColumnField.EXPERIMENT_PROJECT),d.metadata(ImportColumnField.GROUP),warnings=d.issues.toList(),
                        missingFields=buildSet { if(kind!=RichExceptionKind.EXTRA && original==null) add(ImportField.ORIGINAL_DATE); if(kind!=RichExceptionKind.CANCEL && target==null) add(ImportField.TARGET_DATE) })
                    pending+=PendingExceptionAttachment(d.key(ImportColumnField.COURSE_KEY),d.key(ImportColumnField.MEETING_KEY),exception,rowSources,"DOCUMENT_LOCAL_KEYS_REQUIRED",d.text(ImportColumnField.NAME)?.takeIf { it.isNotBlank() },d.metadata(ImportColumnField.TEACHER),d.metadata(ImportColumnField.ROOM),d.metadata(ImportColumnField.GROUP),d.metadata(ImportColumnField.EXPERIMENT_PROJECT),d.weeks(),d.day())
                    issues+=d.issues
                    sources+=rowSources
                    continue
                }
                val name=d.text(ImportColumnField.NAME).orEmpty()
                val key=d.key(ImportColumnField.COURSE_KEY)
                val courseCode=d.key(ImportColumnField.SOURCE_COURSE_CODE)
                val classCode=d.key(ImportColumnField.SOURCE_CLASS_CODE)
                val identity=when { key!=null -> listOf("key",key); courseCode!=null || classCode!=null -> listOf("codes",courseCode.orEmpty(),classCode.orEmpty()); else -> null }
                val existing=identity?.let { identities[it] }
                val ci=existing ?: courses.size
                if(existing==null) {
                    if(courses.size>=context.limits.maxCourses) reject("LIMIT_EXCEEDED")
                    if(identity==null) issues+=mappingIssue("UNKEYED_COURSE_IDENTITY") else identities[identity]=ci
                    courses+=RichCourseDraft(name,courseCode,classCode,missingFields=if(name.isBlank()) setOf(ImportField.COURSE_NAME) else emptySet())
                    if(key!=null) courseKeys[ci]=key
                } else if(courses[ci].name!=name || courses[ci].sourceCourseCode!=courseCode || courses[ci].sourceClassCode!=classCode) reject("CONFLICTING_COURSE_KEY")
                if(++meetingCount>context.limits.maxMeetings) reject("LIMIT_EXCEEDED")
                val mi=courses[ci].meetings.size
                val meetingKey=d.key(ImportColumnField.MEETING_KEY)
                if(meetingKey!=null) {
                    if(meetingKeys.any { it.key.courseIndex==ci && it.value==meetingKey }) reject("CONFLICTING_MEETING_KEY")
                    meetingKeys[DraftPath(ci,mi)]=meetingKey
                    if(key!=null) localMeetingPaths[key to meetingKey]=DraftPath(ci,mi)
                }
                val meeting=d.meeting()
                courses[ci]=courses[ci].copy(meetings=courses[ci].meetings+meeting)
                issues+=d.issues
                sources+=rowSources.map { it.copy(path=it.path.copy(courseIndex=ci,meetingIndex=mi)) }
            }
            attachExceptions()
            val finalSources=sources.map { ref->context.cancellation.check(); attachedSourcePaths[ref.sourceLocator]?.let { ref.copy(path=it.copy(field=ref.path.field)) } ?: ref }
            return FormatParseResult(RichTimetableDraft(courses=courses.toList(),issues=issues.toList(),adapterIds=setOf("confirmed-tabular")),finalSources,issues.toList(),courseKeys=courseKeys.toMap(),meetingKeys=meetingKeys.toMap(),pendingExceptions=pending.toList(),unparsedSources=unparsed.toList())
        }

        fun validate() {
            context.cancellation.check()
            if(table.sourceId!=context.sourceId || table.rows.isEmpty()) reject("INVALID_MAPPING")
            if(table.rows.size>context.limits.maxRows) reject("LIMIT_EXCEEDED")
            val rowNumbers=hashSetOf<Int>(); val columns=hashSetOf<Int>(); var count=0; var text=0L
            for(row in table.rows) {
                context.cancellation.check()
                if(row.number !in 1..context.limits.maxRows || !rowNumbers.add(row.number)) reject()
                val used=hashSetOf<Int>()
                for(cell in row.cells) {
                    context.cancellation.check()
                    if(cell.column !in 1..context.limits.maxColumns || !used.add(cell.column)) reject()
                    columns+=cell.column
                    if(++count>context.limits.maxCells) reject("LIMIT_EXCEEDED")
                    text+=cell.rawText.length
                    text+=cell.formula?.length ?: 0
                    if(cell.rawText.length>context.limits.maxFieldUnits || (cell.formula?.length ?: 0)>context.limits.maxFieldUnits || text>context.limits.maxTextUnits) reject("LIMIT_EXCEEDED")
                }
            }
            if(mapping.columns.isEmpty() || !mapping.columns.containsKey(ImportColumnField.NAME) || mapping.headerRows.any { it !in rowNumbers } || mapping.columns.values.any { it !in columns } || mapping.ignoredColumns.any { it !in columns || it in mapping.columns.values }) reject("INVALID_MAPPING")
            for(fields in mapping.columns.entries.groupBy { it.value }.values) {
                if(fields.size<=1) continue
                // A manually mapped date-time cell may supply one date and one time component.
                val pair=fields.map { it.key }.toSet()
                if(pair!=setOf(ImportColumnField.DATES,ImportColumnField.START_TIME) && pair!=setOf(ImportColumnField.DATES,ImportColumnField.END_TIME)) reject("INVALID_MAPPING")
                if(table.rows.filter { it.number !in mapping.headerRows }.flatMap { it.cells }.filter { it.column==fields[0].value && it.rawText.isNotBlank() }.any { it.style?.kind!=NumberFormatKind.DATE_TIME }) reject("INVALID_MAPPING")
            }
        }

        fun attachExceptions() {
            val unresolved=mutableListOf<PendingExceptionAttachment>()
            for(p in pending) {
                context.cancellation.check()
                val candidates=if(p.courseKey==null || p.meetingKey==null) emptyList() else listOfNotNull(localMeetingPaths[p.courseKey to p.meetingKey])
                val path=candidates.singleOrNull()
                val m=path?.let { courses[it.courseIndex!!].meetings[it.meetingIndex!!] }
                val nameMatches=path==null || p.courseName==null || courses[path.courseIndex!!].name==p.courseName
                val valid=m!=null && when(m.kind) { MeetingKind.RECURRING -> m.dayOfWeek!=null && m.weeks.isNotEmpty() && (m.sections.isNotEmpty() || m.customTime!=null); MeetingKind.DATE_SPECIFIC -> m.dates.isNotEmpty() && (m.sections.isNotEmpty() || m.customTime!=null); MeetingKind.UNSCHEDULED -> false }
                val shape=p.exception.missingFields.isEmpty() && p.exception.warnings.none { it.code in setOf("EXCEPTION_DATE_KIND_MISMATCH","INVALID_DATE","INVALID_TIME","INVALID_TIME_RANGE") }
                if(path==null || !valid || !shape || !nameMatches) { unresolved+=p.copy(reason=if(path==null) "DOCUMENT_LOCAL_KEYS_REQUIRED" else if(!nameMatches) "CONFLICTING_COURSE_NAME_CONSTRAINT" else "INVALID_BASE_OR_EXCEPTION",candidatePaths=candidates); issues+=mappingIssue("PENDING_EXCEPTION_ATTACHMENT"); continue }
                val ci=path.courseIndex!!; val mi=path.meetingIndex!!
                val warnings=p.exception.warnings.toMutableList()
                if(p.exception.originalDate!=null && m!!.dayOfWeek!=null && p.exception.originalDate.dayOfWeek.value!=m.dayOfWeek) warnings+=mappingIssue("DATE_WEEKDAY_MISMATCH")
                if(m!!.kind==MeetingKind.DATE_SPECIFIC && p.exception.originalDate!=null && p.exception.originalDate !in m.dates) warnings+=mappingIssue("ORIGINAL_DATE_NOT_IN_BASE")
                val updated=m.copy(exceptions=m.exceptions+p.exception.copy(warnings=warnings))
                for(ref in p.sources) { context.cancellation.check(); attachedSourcePaths[ref.sourceLocator]=DraftPath(ci,mi,m.exceptions.size) }
                courses[ci]=courses[ci].copy(meetings=courses[ci].meetings.mapIndexed { index,meeting -> if(index==mi) updated else meeting })
            }
            pending.clear(); pending+=unresolved
        }
    }
}

/** Literal values are never header-normalized. Shared by CSV and typed XLSX tables. */
internal class RowDecoder(val row:ImportRow,val mapping:ConfirmedColumnMapping,val context:FormatParseContext) {
    val issues=mutableListOf<ParseIssue>()
    private val consumedTimeColumns=hashSetOf<Int>()
    fun issue(code:String) { issues+=mappingIssue(code) }
    fun cell(field:ImportColumnField)=mapping.columns[field]?.let { column->row.cells.firstOrNull { it.column==column } }
    fun text(field:ImportColumnField):String? {
        context.cancellation.check()
        val c=cell(field) ?: return null
        if(c.formula!=null || c.typedValue==CellValue.Empty) return null
        if(c.mergeAnchor!=null || c.typedValue==null && (c.style!=null || c.issues.isNotEmpty())) return null
        return c.rawText
    }
    fun key(field:ImportColumnField)=text(field)?.takeIf { it.isNotBlank() }
    fun metadata(field:ImportColumnField):String? {
        val c=cell(field) ?: return null
        if(c.typedValue==CellValue.Empty && c.formula==null && c.mergeAnchor==null && c.issues.isEmpty()) return ""
        return text(field)
    }
    fun weeks():Set<Int> {
        val t=text(ImportColumnField.WEEKS)?.trim().orEmpty()
        if(t.isEmpty()) return emptySet()
        val parsed=WeekExpressionParser.parse(t,context.maxWeek)
        if(parsed.errors.isNotEmpty()) issue("INVALID_WEEKS")
        return parsed.weeks
    }
    fun sections():Set<Int> {
        val t=text(ImportColumnField.SECTIONS)?.trim().orEmpty()
        if(t.isEmpty()) return emptySet()
        val parsed=WeekExpressionParser.parse(t.removeSuffix("节"),100)
        if(parsed.errors.isNotEmpty()) issue("INVALID_SECTIONS")
        return parsed.weeks
    }
    fun day():Int? {
        val t=text(ImportColumnField.DAY_OF_WEEK)?.trim().orEmpty()
        if(t.isEmpty()) return null
        val n=t.toIntOrNull() ?: mapOf("一" to 1,"二" to 2,"三" to 3,"四" to 4,"五" to 5,"六" to 6,"日" to 7,"天" to 7)[t.removePrefix("星期").removePrefix("周")]
        if(n==null || n !in 1..7) { issue("INVALID_DAY_OF_WEEK"); return null }
        return n
    }
    private fun temporal(field:ImportColumnField):ExcelTemporalValue? {
        val c=cell(field) ?: return null
        if(c.formula!=null) return null
        return when(val v=c.typedValue) { is CellValue.Temporal -> v.value; is CellValue.Number -> if(c.style?.kind in setOf(NumberFormatKind.DATE,NumberFormatKind.TIME,NumberFormatKind.DATE_TIME)) v.temporal else null; else->null }
    }
    fun date(field:ImportColumnField):LocalDate? {
        temporal(field)?.let { value ->
            if(value.time!=null && cell(field)?.column !in consumedTimeColumns) issue("UNUSED_TIME_COMPONENT")
            return value.date ?: run { issue("INVALID_DATE"); null }
        }
        val t=text(field)?.trim().orEmpty()
        if(t.isEmpty()) return null
        return exactDate(t) ?: run { issue("INVALID_DATE"); null }
    }
    fun dates():Set<LocalDate> {
        val t=text(ImportColumnField.DATES)?.trim().orEmpty()
        if(t.isEmpty()) return emptySet()
        temporal(ImportColumnField.DATES)?.let { return setOfNotNull(date(ImportColumnField.DATES)) }
        val result=linkedSetOf<LocalDate>()
        for(part in t.split(';')) { context.cancellation.check(); val value=exactDate(part.trim()); if(value==null) { issue("INVALID_DATE"); return emptySet() }; result+=value }
        return result
    }
    fun time():DraftTimeRange? {
        val range=text(ImportColumnField.TIME_RANGE)?.trim().orEmpty()
        if(range.isNotEmpty() && (!text(ImportColumnField.START_TIME).isNullOrBlank() || !text(ImportColumnField.END_TIME).isNullOrBlank())) { issue("AMBIGUOUS_TIME_MAPPING"); return null }
        val pair=if(range.isNotEmpty()) {
            val m=Regex("^(\\d{2}:\\d{2})\\s*[-~～至]\\s*(\\d{2}:\\d{2})$").matchEntire(range)
            if(m==null) { issue("INVALID_TIME"); return null }
            exactTime(m.groupValues[1]) to exactTime(m.groupValues[2])
        } else {
            fun clock(field:ImportColumnField):LocalTime? {
                temporal(field)?.let { v ->
                    if(v.date!=null && mapping.columns[ImportColumnField.DATES]!=mapping.columns[field]) issue("UNUSED_DATE_COMPONENT")
                    if(v.time==null || v.time.second!=0 || v.time.nano!=0) { issue("INVALID_TIME"); return null }
                    return v.time
                }
                val s=text(field)?.trim().orEmpty()
                if(s.isEmpty()) return null
                return exactTime(s) ?: run { issue("INVALID_TIME"); null }
            }
            clock(ImportColumnField.START_TIME) to clock(ImportColumnField.END_TIME)
        }
        if(pair.first==null && pair.second==null) return null
        if(pair.first==null || pair.second==null) { issue("INCOMPLETE_TIME"); return null }
        if(pair.second!!<=pair.first!!) { issue("INVALID_TIME_RANGE"); return null }
        for(field in listOf(ImportColumnField.START_TIME,ImportColumnField.END_TIME)) {
            if(temporal(field)?.time!=null) cell(field)?.column?.let { consumedTimeColumns+=it }
        }
        return DraftTimeRange(pair.first!!,pair.second!!)
    }
    fun meeting():RichMeetingDraft {
        val day=day(); val weeks=weeks(); val sections=sections(); val time=time(); val dates=dates()
        val declared=text(ImportColumnField.KIND)?.trim().orEmpty()
        val explicit=MeetingKind.entries.firstOrNull { it.name==declared }
        if(declared.isNotEmpty() && explicit==null) issue("INVALID_MEETING_KIND")
        val timed=time!=null || sections.isNotEmpty()
        val kind=if(!timed) MeetingKind.UNSCHEDULED else explicit ?: when { dates.isNotEmpty() -> MeetingKind.DATE_SPECIFIC; day!=null && weeks.isNotEmpty() -> MeetingKind.RECURRING; else -> MeetingKind.UNSCHEDULED }
        if(explicit!=null && explicit!=kind) issue("MEETING_KIND_MISMATCH")
        if(kind==MeetingKind.RECURRING && (day==null || weeks.isEmpty() || !timed) || kind==MeetingKind.DATE_SPECIFIC && (dates.isEmpty() || !timed) || kind==MeetingKind.UNSCHEDULED && timed || kind==MeetingKind.RECURRING && dates.isNotEmpty()) issue("MEETING_KIND_MISMATCH")
        if(dates.isNotEmpty() && day!=null && dates.any { it.dayOfWeek.value!=day }) issue("DATE_WEEKDAY_MISMATCH")
        val boolean=text(ImportColumnField.IS_EXPERIMENT)?.trim().orEmpty()
        if(boolean.isNotEmpty() && boolean !in setOf("true","false","是","否","1","0")) issue("INVALID_IS_EXPERIMENT")
        val missing=buildSet {
            if(!timed) add(ImportField.TIME)
            if(kind!=MeetingKind.DATE_SPECIFIC && day==null) add(ImportField.DAY_OF_WEEK)
            if(kind==MeetingKind.RECURRING && weeks.isEmpty()) add(ImportField.WEEKS)
        }
        return RichMeetingDraft(kind,day,weeks,sections,time,dates,metadata(ImportColumnField.TEACHER)?.takeIf { it.isNotBlank() },metadata(ImportColumnField.ROOM)?.takeIf { it.isNotBlank() },metadata(ImportColumnField.GROUP)?.takeIf { it.isNotBlank() },metadata(ImportColumnField.EXPERIMENT_PROJECT)?.takeIf { it.isNotBlank() },boolean in setOf("true","是","1"),warnings=issues.toList(),missingFields=missing)
    }
}

internal fun exactDate(text:String):LocalDate? = if(!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(text)) null else try { LocalDate.parse(text).takeIf { it.year in 1..9999 } } catch(_:DateTimeParseException) { null }
internal fun exactTime(text:String):LocalTime? = if(!Regex("[0-9]{2}:[0-9]{2}").matches(text)) null else try { LocalTime.parse(text) } catch(_:DateTimeParseException) { null }
