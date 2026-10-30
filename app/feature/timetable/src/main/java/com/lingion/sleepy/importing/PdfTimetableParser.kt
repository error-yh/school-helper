/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing

import java.text.Normalizer
import java.time.LocalTime
import kotlin.math.abs

/** Coordinates -> weekday columns -> content-selected cell adapter -> immutable drafts. No IO or DB access. */
class PdfTimetableParser(private val maxPages: Int = 30, private val maxGlyphs: Int = 500_000) {
    private data class Line(val spans: List<PositionedText>) {
        val text = spans.joinToString("") { it.text }.trim()
        val bottom = spans.maxOf { it.position.bounds.bottom }
        fun source() = excerpt(spans)
    }
    private data class Header(val centers: List<Float>, val bottom: Float)
    private data class Candidate(val course: RichCourseDraft, val identity: String)
    private val labelled = LabelledSectionAdapter()
    private val coded = CodedTimeAdapter()
    /** Reuse the validated labelled-course grammar after an OCR adapter measured a column. */
    internal fun parseLabelledColumn(lines:List<SourceExcerpt>,day:Int,maxWeek:Int,maxWrappedTitleGap:Float?=null):ParsedCells =
        LabelledSectionAdapter(maxWeek,maxWrappedTitleGap).parse(lines,day)

    fun parse(document: PositionedDocument): RichTimetableDraft {
        if(document.pages.size > maxPages || document.pages.sumOf { it.text.sumOf { t -> t.text.length } } > maxGlyphs)
            return RichTimetableDraft(issues=listOf(ParseIssue("LIMIT_EXCEEDED", "Document exceeds parser limits")))
        val candidates = mutableListOf<Candidate>()
        val issues = mutableListOf<ParseIssue>()
        val unparsed = mutableListOf<SourceExcerpt>()
        val adapters = mutableSetOf<String>()
        val periodProposals=mutableListOf<PeriodProposal>()
        val columns=(1..7).associateWith { mutableListOf<SourceExcerpt>() }
        var previousHeader: Header? = null
        var previousWidth: Float? = null
        for(page in document.pages) {
            if(page.width <= 0 || page.height <= 0 || page.text.any { !validPosition(it.position, page) }) {
                issues += ParseIssue("INVALID_COORDINATES", "Invalid page or text coordinates")
                continue
            }
            val normalized = page.text.flatMap { span ->
                val parts = normalize(span.text).split('\n')
                parts.mapIndexed { index, part ->
                    val b=span.position.bounds
                    val h=(b.bottom-b.top)/parts.size
                    span.copy(text=part, rawText=span.rawText.split('\n').getOrElse(index){span.rawText}, position=span.position.copy(bounds=b.copy(top=b.top+index*h,bottom=b.top+(index+1)*h)))
                }
            }.filter { it.text.isNotEmpty() }
            val rows=lines(normalized)
            val measuredHeader=findHeader(rows)
            val header=measuredHeader ?: previousHeader?.takeIf { previousWidth == page.width }?.copy(bottom=-1f)
            if(header == null) {
                issues += ParseIssue("UNSUPPORTED_LAYOUT", "Seven weekday headers were not found", rows.firstOrNull()?.source())
                unparsed += rows.map { it.source() }
                continue
            }
            previousHeader=header
            previousWidth=page.width
            val spacing=header.centers.zipWithNext { a,b -> b-a }.average().toFloat()
            val boundaries=listOf(header.centers.first()-spacing/2)+header.centers.zipWithNext { a,b -> (a+b)/2 }+listOf(header.centers.last()+spacing/2)
            val footerRows=rows.filter { row ->
                row.text.trim().contains(Regex("^(?:(?:实践|其他)课程|备注)[:：]")) &&
                    row.spans.minOf { it.position.bounds.left } < boundaries.first()-spacing/4
            }
            val footerTop=footerRows.minOfOrNull { it.spans.minOf { s -> s.position.bounds.top } } ?: Float.MAX_VALUE
            footerRows.forEach { line ->
                val drafts=parseUnscheduled(line.source())
                drafts.forEach { candidates += candidate(it) }
                if(drafts.any { c -> c.warnings.any { it.code=="UNPARSED_NOTE" } }) unparsed+=line.source()
                adapters += "labelled-practice"
            }
            val clockLines=lines(normalized.filter { (it.position.bounds.left+it.position.bounds.right)/2 < boundaries.first() })
            val clockPattern=Regex("^(\\d{1,2})\\s+(\\d{1,2}:\\d{2})\\s*[-~]\\s*(\\d{1,2}:\\d{2})$")
            for(line in clockLines) {
                val match=clockPattern.matchEntire(line.text) ?: continue
                val first=clockTime(match.groupValues[2]);val last=clockTime(match.groupValues[3])
                if(first!=null && last!=null && last>first)
                    periodProposals+=PeriodProposal(match.groupValues[1].toInt(),DraftTimeRange(first,last),listOf(line.source()))
                else issues+=ParseIssue("INVALID_PERIOD_CLOCK","Individual period clock requires correction",line.source())
            }
            val body=normalized.filter { it.position.bounds.top > header.bottom && it.position.bounds.bottom < footerTop &&
                !it.text.contains("打印时间") && !it.text.contains("打印日期") }
            for(day in 1..7) {
                val col=body.filter { val center=(it.position.bounds.left+it.position.bounds.right)/2; center >= boundaries[day-1] && center < boundaries[day] }
                val columnLines=lines(col)
                columns.getValue(day) += columnLines.map { it.source() }
            }
        }
        for((day,columnLines) in columns) {
            val unknown=columnLines.filter { it.parseText.contains(Regex("(?:时间|时段)(?:待定|未定)|待排课|未排课")) }
            unparsed += unknown
            val available=columnLines.filterNot { it in unknown }
            val selected=listOf(coded,labelled).filter { adapter -> adapter.supports(available) }
            val consumed=mutableSetOf<Int>()
            if(selected.isEmpty()) {
                unparsed += available.filterNot { boilerplate(it.parseText) }
            } else for(adapter in selected) {
                adapters += adapter.id
                val parsed=adapter.parse(available,day)
                candidates += parsed.courses.map { candidate(it) }
                unparsed += parsed.unparsed
                consumed += parsed.consumed
            }
            if(selected.isNotEmpty()) unparsed+=available.filterIndexed { i,line -> i !in consumed && !boilerplate(line.parseText) }
        }
        val clocks=periodProposals.groupBy { it.section to it.time }.values.map { group -> group.first().copy(sources=group.flatMap { it.sources }) }
        val conflictingClocks=clocks.groupBy { it.section }.filterValues { it.size>1 }.keys
        if(conflictingClocks.isNotEmpty()) issues+=ParseIssue("CONFLICTING_PERIOD_CLOCKS","Source has conflicting individual section clocks")
        val reliableSections=clocks.map { it.section }.toSet()-conflictingClocks
        val grouped=candidates.groupBy { it.identity }.values.map { group ->
            val first=group.first().course
            // Exact repetition across merged PDF cells is one meeting, with all provenance retained.
            val meetings=group.flatMap { it.course.meetings }.groupBy {
                listOf(it.kind,it.dayOfWeek,it.weeks,it.sections,it.customTime,it.dates,it.teacher,it.room,it.isExperiment,it.group,it.experimentProject)
            }.values.map { repeated ->
                val m=repeated.first()
                m.copy(sources=repeated.flatMap { it.sources },missingFields=if(m.sections.isNotEmpty() && reliableSections.containsAll(m.sections)) m.missingFields-ImportField.TIME else m.missingFields)
            }
            val identityWarnings=if(first.sourceCourseCode==null && first.sourceClassCode==null)
                listOf(ParseIssue("COURSE_IDENTITY_UNVERIFIED","Same-title source records need archive identity confirmation",first.sources.firstOrNull())) else emptyList()
            first.copy(meetings=meetings,sources=group.flatMap { it.course.sources },warnings=first.warnings+identityWarnings)
        }
        return RichTimetableDraft(courses=grouped,periodProposals=clocks,issues=issues,unparsed=unparsed.distinct(),adapterIds=adapters)
    }

    private fun candidate(c: RichCourseDraft): Candidate {
        val identity=c.name+"|class="+c.sourceClassCode.orEmpty()+"|course="+c.sourceCourseCode.orEmpty()
        return Candidate(c,identity)
    }
    private fun findHeader(rows: List<Line>): Header? {
        val regex=Regex("星期([一二三四五六日天])")
        for(row in rows) {
            val hits=regex.findAll(row.text).toList()
            if(hits.size != 7 || hits.map { it.groupValues[1] }.take(6) != listOf("一","二","三","四","五","六")) continue
            val chars=row.spans.flatMap { s -> s.text.mapIndexed { i,_ -> s.position.bounds.left+(s.position.bounds.right-s.position.bounds.left)*(i+.5f)/s.text.length } }
            return Header(hits.map { hit -> chars.subList(hit.range.first,hit.range.last+1).average().toFloat() },row.bottom)
        }
        return null
    }
    private fun parseUnscheduled(source: SourceExcerpt): List<RichCourseDraft> {
        if(source.parseText.contains(Regex("备注[:：]"))) {
            val notePattern=Regex("(?:\\d+\\.)?([^\\d:：]+?)\\s+([A-Za-z0-9]+\\.\\d+)\\s*\\(([\\d~,-]+)\\)")
            val notes=notePattern.findAll(source.parseText.substringAfter(':',source.parseText.substringAfter('：'))).toList()
            if(notes.isEmpty()) return listOf(RichCourseDraft("待确认备注",sources=listOf(source),warnings=listOf(ParseIssue("UNPARSED_NOTE","Footnote requires confirmation",source))))
            val body=source.parseText.substringAfter(':',source.parseText.substringAfter('：'))
            val residue=notePattern.replace(body,"").replace(Regex("[\\s;；,，.\\d]"),"")
            return notes.mapIndexed { index,note ->
                val weeks=WeekExpressionParser.parse(note.groupValues[3])
                RichCourseDraft(note.groupValues[1].trim(),sourceCourseCode=note.groupValues[2],meetings=listOf(RichMeetingDraft(MeetingKind.UNSCHEDULED,weeks=weeks.weeks,
                    sources=listOf(source),warnings=if(weeks.errors.isEmpty()) emptyList() else listOf(ParseIssue("INVALID_WEEKS","Footnote weeks require correction",source)),
                    missingFields=setOf(ImportField.TIME,ImportField.DAY_OF_WEEK,ImportField.TEACHER,ImportField.ROOM))),sources=listOf(source),
                    warnings=if(index==0 && residue.isNotEmpty()) listOf(ParseIssue("UNPARSED_NOTE","Some footer text was not recognized",source)) else emptyList())
            }
        }
        val text=source.parseText.substringAfter('：',source.parseText.substringAfter(':'))
        return text.split(';','；').mapNotNull { raw ->
            val item=raw.trim(); if(item.isEmpty()) return@mapNotNull null
            val symbol=Regex("[▲◆●○■△]").find(item)
            val name=if(symbol != null) item.substring(0,symbol.range.first).trim() else item.substringBefore('(').trim()
            if(name.isEmpty()) return@mapNotNull null
            val teacher=if(symbol != null) item.substring(symbol.range.last+1).substringBefore('(').trim().ifEmpty { null } else null
            val weekText=item.substringAfter(")/", "").substringBefore('/').trim()
            val weeks=WeekExpressionParser.parse(weekText)
            val warnings=if(weeks.errors.isEmpty()) emptyList() else listOf(ParseIssue("INVALID_WEEKS","Unscheduled item has unrecognized weeks",source))
            RichCourseDraft(name,meetings=listOf(RichMeetingDraft(MeetingKind.UNSCHEDULED,weeks=weeks.weeks,teacher=teacher,sources=listOf(source),warnings=warnings,
                missingFields=setOf(ImportField.TIME,ImportField.DAY_OF_WEEK))),sources=listOf(source))
        }
    }
    companion object {
        internal fun normalize(text: String): String = Normalizer.normalize(text,Normalizer.Form.NFKC).replace('\u00a0',' ')
            .replace('⻝','食').replace('⻰','龙').replace('⻚','页')
        private fun clockTime(raw: String): LocalTime? = try { val p=raw.split(':');LocalTime.of(p[0].toInt(),p[1].toInt()) } catch(_:IllegalArgumentException) { null }
        private fun validPosition(p: SourcePosition,page: PositionedPage): Boolean {
            val b=p.bounds
            return p.page==page.number && listOf(b.left,b.top,b.right,b.bottom).all { it.isFinite() } && b.right>=b.left && b.bottom>=b.top
        }
        private fun excerpt(spans: List<PositionedText>) = SourceExcerpt(SourcePosition(spans.first().position.page,
            TextBox(spans.minOf { it.position.bounds.left },spans.minOf { it.position.bounds.top },spans.maxOf { it.position.bounds.right },spans.maxOf { it.position.bounds.bottom })),spans.joinToString(""){it.rawText})
        private fun lines(spans: List<PositionedText>): List<Line> {
            val groups=mutableListOf<MutableList<PositionedText>>()
            for(span in spans.sortedWith(compareBy({it.position.bounds.bottom},{it.position.bounds.left}))) {
                val found=groups.lastOrNull { abs(it.first().position.bounds.bottom-span.position.bounds.bottom)<2f }
                if(found==null) groups += mutableListOf(span) else found += span
            }
            return groups.map { Line(it.sortedBy { s -> s.position.bounds.left }) }
        }
    }
}

private interface CellLayoutAdapter {
    val id: String
    fun detect(text: String): Boolean
    fun supports(lines: List<SourceExcerpt>): Boolean = lines.any { detect(it.parseText) }
    fun parse(lines: List<SourceExcerpt>,day: Int): ParsedCells
}
internal data class ParsedCells(val courses: List<RichCourseDraft>,val unparsed: List<SourceExcerpt>,val consumed: Set<Int>)

private class LabelledSectionAdapter(private val maxWeek:Int=53,private val maxWrappedTitleGap:Float?=null): CellLayoutAdapter {
    override val id="labelled-section-grid"
    private val schedule=Regex("\\((\\d{1,2})(?:-(\\d{1,2}))?节\\)")
    override fun detect(text: String)=schedule.containsMatchIn(text)
    override fun parse(lines: List<SourceExcerpt>,day: Int): ParsedCells {
        val starts=lines.indices.filter { detect(lines[it].parseText) }
        val courses=mutableListOf<RichCourseDraft>()
        val unparsed=mutableListOf<SourceExcerpt>()
        val consumed=mutableSetOf<Int>()
        fun titleStart(start:Int):Int {
            var position=start-1
            while(position>0 && !lines[position-1].parseText.contains(Regex("[/：:]|\\d")) && !detect(lines[position-1].parseText)) {
                if(maxWrappedTitleGap!=null && lines[position].position.bounds.top-lines[position-1].position.bounds.bottom>maxWrappedTitleGap) break
                position--
            }
            return position
        }
        for((i,start) in starts.withIndex()) {
            val nameStart=titleStart(start)
            if(nameStart < 0) { unparsed += lines[start]; continue }
            // A wrapped course name follows a completed slash-delimited previous record.
            val name=lines.subList(nameStart,start).joinToString(""){it.parseText}.replace(Regex("[▲◆●○■△]"),"").trim()
            val next=starts.getOrNull(i+1)
            // OCR supplies measured line spacing. Teacher tails before the next
            // separated title remain with this record; PDF's original path is unchanged.
            var end=if(maxWrappedTitleGap!=null) (next?.let(::titleStart) ?: lines.size).coerceAtLeast(start+1) else next?.minus(1) ?: lines.size
            if(maxWrappedTitleGap==null && next != null) while(end>start+1 && !lines[end-1].parseText.contains(Regex("[/：:]|\\d"))) end--
            val creditEnd=(start until end).firstOrNull { lines[it].parseText.contains(Regex("学分[:：]\\s*\\d+(?:\\.\\d+)?\\s*$")) }?.plus(1)
            if(creditEnd!=null && creditEnd<end) {
                unparsed+=lines.subList(creditEnd,end).filterNot { boilerplate(it.parseText) }
                end=creditEnd
            }
            // A labelled field may start on a new OCR/PDF line without a printed slash.
            // Normalize only the derived parsing string; source excerpts remain verbatim.
            val raw=lines.subList(start,end).joinToString("") {
                val text=it.parseText
                if(text.contains(Regex("^(?:教师|场地|教室|校区|教学班)[:：]"))) "/$text" else text
            }
            val sources=pageSources(lines.subList(nameStart,end))
            val src=sources.first()
            val m=schedule.find(raw) ?: continue
            val first=m.groupValues[1].toInt(); val last=m.groupValues[2].toIntOrNull() ?: first
            val sectionValid=first in 1..30 && last in first..30
            val weekText=raw.substring(m.range.last+1).substringBefore('/')
            val weeks=WeekExpressionParser.parse(weekText,maxWeek)
            val warnings=mutableListOf<ParseIssue>()
            if(weeks.errors.isNotEmpty()) warnings+=ParseIssue("INVALID_WEEKS","Week expression requires correction",src)
            if(!sectionValid) warnings+=ParseIssue("INVALID_SECTIONS","Section expression requires correction",src)
            val teacher=field(raw,"教师")
            val room=field(raw,"场地")
            val missing=mutableSetOf(ImportField.TIME)
            if(teacher==null) missing+=ImportField.TEACHER
            if(room==null || room.contains("未定") || room=="无") missing+=ImportField.ROOM
            if(weeks.weeks.isEmpty()) missing+=ImportField.WEEKS
            if(!sectionValid) missing+=ImportField.TIME
            val meeting=RichMeetingDraft(MeetingKind.RECURRING,day,weeks.weeks,if(sectionValid)(first..last).toSet() else emptySet(),teacher=teacher,room=room,
                isExperiment=lines.subList(nameStart,start).any { it.parseText.contains('◆') || it.parseText.contains('○') },sources=sources,warnings=warnings,missingFields=missing)
            courses+=RichCourseDraft(name,sourceClassCode=field(raw,"教学班"),meetings=listOf(meeting),sources=sources)
            consumed+=(nameStart until end)
        }
        return ParsedCells(courses,unparsed,consumed)
    }
}

private class CodedTimeAdapter: CellLayoutAdapter {
    override val id="coded-time-grid"
    private val title=Regex("^([^:：/]+?)\\s*\\.(\\d{3})$")
    private val occurrence=Regex("\\((第?[\\d~\\-,周单双()]+)\\)\\s*\\((?:(\\d{1,2})(?:-(\\d{1,2}))?节\\s*)?(\\d{1,2}:\\d{2})-(\\d{1,2}:\\d{2})\\)")
    override fun detect(text: String)=title.matches(text)
    override fun supports(lines: List<SourceExcerpt>): Boolean = lines.indices.any { i ->
        detect(lines[i].parseText.trim()) || (i+1<lines.size && detect(lines[i].parseText.trim()+lines[i+1].parseText.trim()))
    }
    override fun parse(lines: List<SourceExcerpt>,day: Int): ParsedCells {
        val starts=lines.indices.filter { title.matches(lines[it].parseText.trim()) ||
            (it+1<lines.size && title.matches(lines[it].parseText.trim()+lines[it+1].parseText.trim())) }
            .filter { it==0 || !title.matches(lines[it-1].parseText.trim()+lines[it].parseText.trim()) }
        val courses=mutableListOf<RichCourseDraft>(); val unparsed=mutableListOf<SourceExcerpt>()
        val consumed=mutableSetOf<Int>()
        for((i,start) in starts.withIndex()) {
            val one=title.matchEntire(lines[start].parseText.trim())
            val nameLines=if(one!=null) 1 else 2
            val nameMatch=one ?: title.matchEntire(lines[start].parseText.trim()+lines[start+1].parseText.trim()) ?: continue
            var end=starts.getOrNull(i+1) ?: lines.size
            val fullRaw=lines.subList(start+nameLines,end).joinToString(""){it.parseText}
            val lastTimeOffset=occurrence.findAll(fullRaw).lastOrNull()?.range?.last ?: -1
            var length=0
            val lastTimeLine=(start+nameLines until end).firstOrNull { length+=lines[it].parseText.length; length>lastTimeOffset } ?: start+nameLines
            // A completed earlier lab segment is not the end of a later wrapped segment.
            val countEnd=(lastTimeLine until end).lastOrNull { lines[it].parseText.contains(Regex("人数:\\d+/\\d+\\s*$")) }?.plus(1)
            val teacherEnd=(lastTimeLine until end).lastOrNull { lines[it].parseText.contains(Regex("\\d{6,}[^)]*\\)\\s*$")) }?.plus(1)
            val recordEnd=listOfNotNull(countEnd,teacherEnd).maxOrNull()
            if(recordEnd!=null && recordEnd<end) { unparsed+=lines.subList(recordEnd,end).filterNot { boilerplate(it.parseText) };end=recordEnd }
            val sources=pageSources(lines.subList(start,end))
            val source=sources.first()
            val raw=lines.subList(start+nameLines,end).joinToString(""){it.parseText}
            val hits=occurrence.findAll(raw).toList()
            if(hits.isEmpty()) { unparsed+=source; continue }
            val meetings=hits.mapIndexed { j,hit ->
                val tail=raw.substring(hit.range.last+1,hits.getOrNull(j+1)?.range?.first ?: raw.length)
                val weeks=WeekExpressionParser.parse(hit.groupValues[1])
                val startTime=parseTime(hit.groupValues[4]);val endTime=parseTime(hit.groupValues[5])
                val validTime=startTime!=null && endTime!=null && endTime>startTime
                val teacherMatch=Regex("([^\\s()]+)\\(\\d{6,}").find(tail)
                val teacher=teacherMatch?.groupValues?.get(1)
                val room=teacherMatch?.let { tail.substring(0,it.range.first).trim().ifEmpty { null } }
                val first=hit.groupValues[2].toIntOrNull();val last=hit.groupValues[3].toIntOrNull() ?: first
                val sections=if(first!=null && last!=null && first in 1..30 && last in first..30)(first..last).toSet() else emptySet()
                val warnings=mutableListOf<ParseIssue>()
                if(weeks.errors.isNotEmpty()) warnings+=ParseIssue("INVALID_WEEKS","Week expression requires correction",source)
                if(!validTime) warnings+=ParseIssue("INVALID_TIME","Time requires correction",source)
                val missing=mutableSetOf<ImportField>()
                if(teacher==null) missing+=ImportField.TEACHER
                if(room==null) missing+=ImportField.ROOM
                if(weeks.weeks.isEmpty()) missing+=ImportField.WEEKS
                if(!validTime) missing+=ImportField.TIME
                RichMeetingDraft(MeetingKind.RECURRING,day,weeks.weeks,sections,if(validTime)DraftTimeRange(startTime!!,endTime!!) else null,
                    teacher=teacher,room=room,sources=sources,warnings=warnings,missingFields=missing)
            }
            courses+=RichCourseDraft(nameMatch.groupValues[1].trim(),sourceCourseCode=nameMatch.groupValues[2],meetings=meetings,sources=sources)
            consumed+=(start until end)
        }
        return ParsedCells(courses,unparsed,consumed)
    }
    private fun parseTime(raw: String): LocalTime? = try { val parts=raw.split(':');LocalTime.of(parts[0].toInt(),parts[1].toInt()) } catch(_: IllegalArgumentException) { null }
}

private fun field(raw: String,name: String): String? = Regex("(?:^|/)"+name+"[:：]([^/]*)").find(raw)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
private fun pageSources(lines: List<SourceExcerpt>) = lines.groupBy { it.position.page }.values.map { mergeSources(it) }
private fun mergeSources(lines: List<SourceExcerpt>): SourceExcerpt = SourceExcerpt(SourcePosition(lines.first().position.page,
    TextBox(lines.minOf { it.position.bounds.left },lines.minOf { it.position.bounds.top },lines.maxOf { it.position.bounds.right },lines.maxOf { it.position.bounds.bottom })),lines.joinToString("\n"){it.rawText})




private fun boilerplate(text: String): Boolean = text.isBlank() || text.trim().matches(Regex("\\d{1,2}|上午|下午|晚上|时间段|节次")) || text.contains(Regex("打印(?:时间|日期)|^https?://|^页码|^总学分"))
