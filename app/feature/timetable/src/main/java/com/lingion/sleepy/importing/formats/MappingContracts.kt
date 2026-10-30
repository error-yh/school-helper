/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import com.lingion.sleepy.importing.*
import java.time.DayOfWeek
import java.time.LocalDate

enum class ImportColumnField {
    COURSE_KEY, MEETING_KEY, NAME, SOURCE_COURSE_CODE, SOURCE_CLASS_CODE, KIND, DAY_OF_WEEK,
    WEEKS, SECTIONS, DATES, START_TIME, END_TIME, TIME_RANGE, TEACHER, ROOM, GROUP,
    EXPERIMENT_PROJECT, IS_EXPERIMENT, EXCEPTION_KIND, ORIGINAL_DATE, TARGET_DATE,
}

/** Coordinates are one based, matching the selected reader table, never list offsets. */
data class HeaderCellEvidence(val row:Int,val column:Int,val sourceLocator:String,val rawText:String,val mergeAnchor:String?=null)
data class ColumnMappingCandidate(val field:ImportColumnField,val column:Int,val sources:List<HeaderCellEvidence>)
/** Suggestions carry no confirmation and never select a workbook sheet. */
data class HeaderMappingProposal(val headerRows:Set<Int>,val candidates:List<ColumnMappingCandidate>,val ignoredColumns:Set<Int>,val issues:List<ParseIssue>)
/** Explicit caller action. Empty headerRows means the caller declares a headerless table. */
data class ConfirmedColumnMapping(val headerRows:Set<Int>,val columns:Map<ImportColumnField,Int>,val ignoredColumns:Set<Int> = emptySet())

data class PendingExceptionAttachment(
    val courseKey:String? = null,
    val meetingKey:String? = null,
    val exception:RichExceptionDraft,
    val sources:List<ImportSourceRef> = emptyList(),
    val reason:String,
    val courseName:String? = null,
    val teacher:String? = null,
    val room:String? = null,
    val group:String? = null,
    val experimentProject:String? = null,
    val declaredWeeks:Set<Int> = emptySet(),
    val dayOfWeek:Int? = null,
    val candidatePaths:List<DraftPath> = emptyList(),
)

data class RelativeDateProposal(
    val originalToken:String,
    val source:ImportSourceRef,
    val basisDate:LocalDate,
    val candidate:LocalDate?,
    val ruleId:String,
    val unresolvedReason:String? = null,
    val referenceDate:LocalDate = basisDate,
    val confirmedTermMonday:LocalDate? = null,
    val confirmedYear:Int? = null,
) { val requiresReview:Boolean get() = true }

data class NoticeParseContext(
    val formatContext:FormatParseContext,
    val referenceDate:LocalDate,
    val confirmedTermMonday:LocalDate? = null,
    val confirmedYear:Int? = null,
) {
    init {
        require(confirmedTermMonday==null || confirmedTermMonday.dayOfWeek==DayOfWeek.MONDAY && confirmedTermMonday.year in 1..9999)
        require(confirmedYear==null || confirmedYear in 1..9999)
        require(referenceDate.year in 1..9999)
    }
}

internal fun mappingIssue(code:String) = ParseIssue(code, when(code) {
    "AMBIGUOUS_HEADER" -> "Several header cells propose the same field; confirm columns manually"
    "INVALID_MAPPING" -> "Header rows or column mapping require correction"
    "UNMAPPED_COLUMN" -> "Unmapped column retained for review"
    "UNKEYED_COURSE_IDENTITY" -> "Row has no explicit course identity; same titles remain separate"
    "PENDING_EXCEPTION_ATTACHMENT" -> "Exception requires an explicit valid course and meeting attachment"
    "RELATIVE_DATE_REVIEW" -> "Date candidate requires explicit review using the displayed date basis"
    else -> code.lowercase().replace('_',' ') + "; review required"
})

internal fun normalizeHeader(text:String) = text.lowercase().replace(Regex("[\\s_\\-:：()（）]+"),"")

class HeaderMappingInspector {
    fun inspect(table:ImportTable,context:FormatParseContext = FormatParseContext(table.sourceId,53)):HeaderMappingProposal {
        val candidates=mutableListOf<ColumnMappingCandidate>()
        val rows=linkedSetOf<Int>()
        val issues=mutableListOf<ParseIssue>()
        fun limited() = HeaderMappingProposal(emptySet(),emptyList(),emptySet(),listOf(publicIssue("LIMIT_EXCEEDED")))
        // Discovery may stop at the first body row; resource validation must inspect the entire table.
        context.cancellation.check()
        if(table.rows.size>context.limits.maxRows) return limited()
        var total=0L
        var cellCount=0
        for(row in table.rows) {
            context.cancellation.check()
            if(row.number !in 1..context.limits.maxRows) return limited()
            for(cell in row.cells) {
                context.cancellation.check()
                if(++cellCount>context.limits.maxCells || cell.column !in 1..context.limits.maxColumns) return limited()
                total+=cell.rawText.length
                total+=cell.formula?.length ?: 0
                if(total>context.limits.maxTextUnits || cell.rawText.length>context.limits.maxFieldUnits || (cell.formula?.length ?: 0)>context.limits.maxFieldUnits) return limited()
            }
        }
        for(row in table.rows) {
            context.cancellation.check()
            var matched=false
            for(cell in row.cells) {
                context.cancellation.check()
                if(row.number<=8 && cell.formula==null && cell.mergeAnchor==null) {
                    val field=aliases[normalizeHeader(cell.rawText)]
                    if(field!=null) {
                        matched=true
                        candidates+=ColumnMappingCandidate(field,cell.column,listOf(HeaderCellEvidence(row.number,cell.column,cell.sourceLocator,cell.rawText,cell.mergeAnchor)))
                    }
                }
            }
            if(matched) rows+=row.number else if(rows.isNotEmpty()) break
        }
        if(candidates.groupBy { it.field }.any { it.value.size>1 } || candidates.groupBy { it.column }.any { it.value.map { c->c.field }.distinct().size>1 }) issues+=mappingIssue("AMBIGUOUS_HEADER")
        val ignored=table.rows.filter { it.number in rows }.flatMap { it.cells }.map { it.column }.toSet()-candidates.map { it.column }.toSet()
        if(candidates.isEmpty()) issues+=mappingIssue("MANUAL_MAPPING_REQUIRED")
        return HeaderMappingProposal(rows,candidates,ignored,issues)
    }

    companion object {
        private val aliases=buildMap<String,ImportColumnField> {
            fun names(field:ImportColumnField,vararg values:String) { values.forEach { put(normalizeHeader(it),field) } }
            names(ImportColumnField.COURSE_KEY,"courseKey","课程键")
            names(ImportColumnField.MEETING_KEY,"meetingKey","安排键")
            names(ImportColumnField.NAME,"name","courseName","课程名称","课程","课名")
            names(ImportColumnField.SOURCE_COURSE_CODE,"sourceCourseCode","courseCode","课程代码","课程编号")
            names(ImportColumnField.SOURCE_CLASS_CODE,"sourceClassCode","classCode","教学班代码")
            names(ImportColumnField.KIND,"kind","安排类型")
            names(ImportColumnField.DAY_OF_WEEK,"dayOfWeek","weekday","星期","星期几")
            names(ImportColumnField.WEEKS,"weeks","周次","上课周次")
            names(ImportColumnField.SECTIONS,"sections","节次")
            names(ImportColumnField.DATES,"dates","date","日期")
            names(ImportColumnField.START_TIME,"startTime","开始时间")
            names(ImportColumnField.END_TIME,"endTime","结束时间")
            names(ImportColumnField.TIME_RANGE,"timeRange","时间","上课时间")
            names(ImportColumnField.TEACHER,"teacher","教师","任课教师")
            names(ImportColumnField.ROOM,"room","教室","实验室","地点")
            names(ImportColumnField.GROUP,"group","班级","组别")
            names(ImportColumnField.EXPERIMENT_PROJECT,"experimentProject","项目","实验项目")
            names(ImportColumnField.IS_EXPERIMENT,"isExperiment","是否实验")
            names(ImportColumnField.EXCEPTION_KIND,"exceptionKind","变更类型")
            names(ImportColumnField.ORIGINAL_DATE,"originalDate","原日期")
            names(ImportColumnField.TARGET_DATE,"targetDate","目标日期")
        }
    }
}
