/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.*

internal data class MatrixRow(val token:GeometryToken,val weeks:Set<Int> = emptySet(),val dateToken:String?=null)
internal data class MatrixPlan(val index:OcrGeometryIndex,val header:GeometryLine,val days:List<GeometryToken>,val periods:List<GeometryToken>,val weekHeader:GeometryToken,val rows:List<MatrixRow>,val annotations:List<List<GeometryToken>>,val left:Float) {
    val candidate get()=OcrLayoutCandidate("week-matrix",index.page.pageNumber,setOf("TEACHING_WEEK_ROWS","WEEKDAY_PERIOD_GROUPS"),days.flatMap {it.originalParts}.map { it.id }+periods.flatMap {it.originalParts}.map { it.id }+weekHeader.originalParts.map {it.id}+rows.flatMap {it.token.originalParts}.map {it.id})
}
/** Week/day/period matrix. Sparse printed scopes stay sparse. A physically broad box
 * without textual scope is an unresolved hypothesis, never a filled rectangle. */
class WeekMatrixAdapter internal constructor() {
    private val printedWeekLabel=Regex("第?[0-9]{1,4}(?:[-,、][0-9]{1,4})*周")
    internal fun detect(index:OcrGeometryIndex):MatrixPlan? {
        val annotations=annotationLines(index)
        val annotationIds=annotations.flatten().map { it.id }.toSet()
        for(rawHeader in index.lines) {
            val header=exactWeekHeaderLine(GeometryLine(rawHeader.tokens.filter { index.work.compare();it.id !in annotationIds }),index)
            if(header.tokens.isEmpty()) continue
            val days=header.tokens.filter { isDayAnchor(it.text) }
            if(days.isEmpty() || days.map { layoutDay(it.text) }.distinct().size!=days.size) continue
            // A vertical 周/次 heading can occupy the second header row while weekdays
            // occupy the first. Use the actual nearby label, never an inferred week column.
            val nearby=index.tokens.filter {t->index.work.compare();
                compactMatch(t.text) in setOf("教学周","周次","周") && t.x<days.first().box.left &&
                    t.box.top>=header.top-index.glyphHeight && t.box.bottom<=header.bottom+index.glyphHeight*4f}
            val weekHeader=(header.tokens.filter {compactMatch(it.text) in setOf("教学周","周次","周")}+nearby)
                .distinctBy {it.id}.firstOrNull {anchor->
                    val line=index.lines.firstOrNull {it.tokens.any {t->index.work.compare();t.id==anchor.id}} ?: header
                    !isPrintedWeekLabel(line,anchor,index)
                } ?: continue
            val tolerance=index.glyphHeight*.6f
            // A centered weekday title is not the left edge of its period columns.
            val left=index.page.lines.filter {line->index.work.compare();line.vertical &&
                line.bounds.top<=header.top+tolerance && line.bounds.bottom>=header.bottom+index.glyphHeight*3f}
                .map {(it.bounds.left+it.bounds.right)/2f}
                .filter {index.work.compare();it>weekHeader.box.right+tolerance && it<days.first().x}
                .maxOrNull() ?: (days.first().box.left-index.glyphHeight*2f)
            val rows=index.tokens.mapNotNull { t ->
                index.work.compare()
                if(t.id in annotationIds || t.box.top<=header.bottom || t.x>=left) null else {
                    val ws=weekAxis(t.text,index.work.context.format.maxWeek)
                    when { ws!=null -> MatrixRow(t,ws);isFullDateToken(t.text) -> MatrixRow(t,dateToken=t.text);else -> null }
                }
            }.toMutableList()
            val numeric=index.tokens.filter {t->index.work.compare();t.id !in annotationIds && t.box.top>header.bottom && t.x<left &&
                compactMatch(t.text).matches(Regex("[0-9]{1,4}")) && kotlin.math.abs(t.x-weekHeader.x)<=maxOf(index.glyphHeight,(weekHeader.box.right-weekHeader.box.left)/2f)}
            index.groupLines(numeric).forEach {line->
                val text=line.tokens.joinToString(""){compactMatch(it.text)}
                if(text.length in 1..4) weekAxis(text+"周",index.work.context.format.maxWeek)?.let {weeks->
                    val parts=line.tokens.flatMap {it.originalParts}
                    val token=if(parts.size==1) parts.single() else parts.first().copy(span=parts.first().span.copy(
                        rawText=parts.joinToString(""){it.span.rawText},position=SourcePosition(index.page.pageNumber,boundsOf(parts)!!)),headerParts=parts)
                    rows+=MatrixRow(token,weeks)
                }
            }
            rows.sortBy {it.token.y}
            val first=rows.firstOrNull()?.token?.box?.top ?: continue
            val periodTokens=index.tokens.filter { t -> index.work.compare();t.id !in annotationIds && t.box.top>header.bottom && t.box.bottom<first && t.x>=left }
            val periods=joinPrintedZeroSuffix(periodTokens,index).filter {sectionAxis(it.text)!=null}
            if(periods.isEmpty() || rows.none { it.weeks.isNotEmpty() }) continue
            return MatrixPlan(index,header,days,periods,weekHeader,rows,annotations,left)
        }
        return null
    }
    /** A native WORD may split 第 / 10 / 周 in a data row. Its 周 is not a heading. */
    private fun isPrintedWeekLabel(header:GeometryLine,anchor:GeometryToken,index:OcrGeometryIndex):Boolean {
        if(compactMatch(anchor.text)!="周") return false
        val position=header.tokens.indexOfFirst {index.work.compare();it.id==anchor.id}
        var text="周";var right=anchor.box.left
        for(i in position-1 downTo maxOf(0,position-6)) {
            index.work.compare();val token=header.tokens[i]
            if(right-token.box.right>index.glyphHeight*2f) break
            text=compactMatch(token.text)+text
            if(text.length>32) break
            if(printedWeekLabel.matches(text)) return true
            right=token.box.left
        }
        return false
    }
    private fun joinPrintedZeroSuffix(tokens:List<GeometryToken>,index:OcrGeometryIndex):List<GeometryToken> = index.groupLines(tokens).flatMap {line->
        val out=mutableListOf<GeometryToken>();var i=0
        while(i<line.tokens.size) {
            index.work.compare();val first=line.tokens[i];val next=line.tokens.getOrNull(i+1)
            if(next!=null && compactMatch(first.text).matches(Regex("[1-9]")) && compactMatch(next.text)=="0" &&
                next.box.left-first.box.right<=minOf(first.box.right-first.box.left,next.box.right-next.box.left)*.5f) {
                val parts=first.originalParts+next.originalParts
                out+=first.copy(span=first.span.copy(rawText=parts.joinToString(""){it.span.rawText},
                    position=SourcePosition(index.page.pageNumber,boundsOf(parts)!!)),headerParts=parts)
                i+=2
            } else {out+=first;i++}
        }
        out
    }
    internal fun parse(plan:MatrixPlan,session:OcrLayoutSession) {
        val index=plan.index
        plan.annotations.forEach { session.matrixAnnotation(index,it) }
        val specialNotices=parseSpecialNotices(index,session)
        session.region(index,plan.header.tokens.flatMap {it.originalParts}+plan.weekHeader.originalParts+plan.periods.flatMap {it.originalParts}+plan.rows.flatMap {it.token.originalParts},OcrRegionKind.STRUCTURAL_ANCHOR)
        if(plan.periods.any {it.originalParts.size>1}) session.issues+=layoutIssue("JOINED_PERIOD_LABEL_REVIEW_REQUIRED")
        if(plan.rows.any {it.token.originalParts.size>1}) session.issues+=layoutIssue("JOINED_WEEK_LABEL_REVIEW_REQUIRED")
        val left=plan.left
        val dayAxis=index.measuredAxis(plan.days.map { it.x to layoutDay(it.text)!! },left,index.page.width,plan.header.bottom,plan.rows.last().token.box.bottom)
        val last=plan.rows.last().token.y
        val lastGap=plan.rows.getOrNull(plan.rows.lastIndex-1)?.let { last-it.token.y } ?: index.glyphHeight*4f
        val rowAxis=GeometryAxis(plan.rows.map { it.token.y to it },plan.periods.maxOf { it.box.bottom },minOf(index.page.height,last+lastGap/2f),index.work)
        val periodAxes=plan.days.indices.associateWith { day ->
            val ts=plan.periods.filter { index.work.compare();dayAxis.bucket(it.x)==day }.sortedBy { it.x }
            if(ts.isEmpty()) null else index.measuredAxis(ts.map { it.x to sectionAxis(it.text)!! },dayAxis.edges[day],dayAxis.edges[day+1],plan.periods.maxOf { it.box.bottom },plan.rows.last().token.box.bottom)
        }
        val cells=linkedMapOf<Triple<Int,Int,Int>,MutableList<GeometryToken>>()
        val structural=(plan.header.tokens.flatMap {it.originalParts}+plan.weekHeader.originalParts+plan.periods.flatMap {it.originalParts}+plan.rows.flatMap {it.token.originalParts}+plan.annotations.flatten()).map { it.id }.toSet()+specialNotices
        for(t in index.tokens) {
            index.work.compare()
            if(t.id in structural) continue
            if(t.box.top<=plan.header.bottom) continue
            if(t.x<left) {
                // Literal date-range axis tokens (including missing year) are annotations;
                // retain tokens and never infer a year from the runtime clock.
                if(t.text.matches(Regex("[0-9/.-]+[-~～至][0-9/.-]+"))) session.region(index,listOf(t),OcrRegionKind.STRUCTURAL_ANCHOR,
                    issues=if(!t.text.contains(Regex("[0-9]{4}"))) listOf(layoutIssue("DATE_RANGE_YEAR_REVIEW_REQUIRED")) else emptyList())
                continue
            }
            val day=dayAxis.bucket(t.x) ?: continue
            val periods=periodAxes[day] ?: continue
            val section=periods.bucket(t.x) ?: continue
            val row=rowAxis.bucket(t.y) ?: continue
            cells.getOrPut(Triple(day,row,section)){mutableListOf()}+=t
        }
        for((cell,tokens) in cells) {
            index.work.check()
            index.checkCellText(tokens)
            val periodAxis=periodAxes[cell.first]!!
            val row=plan.rows[cell.second]
            val box=boundsOf(tokens)!!
            val mergedCell=enclosingMergedCell(index,box,dayAxis.edges.drop(1).dropLast(1)+periodAxis.edges.drop(1).dropLast(1),rowAxis.edges.drop(1).dropLast(1))
            val ruledSections=ruledSections(index,box,cell.first,cell.second,dayAxis,rowAxis,periodAxis,plan)
            // Inspect the complete box before trusting the center-selected cell. A
            // boundary crossing can be unresolved even without covering both anchors.
            val touchedDays=dayAxis.anchors.indices.filter { day -> index.work.compare();box.left<dayAxis.edges[day+1] && box.right>dayAxis.edges[day] }
            val touchedRows=plan.rows.indices.filter { r -> index.work.compare();box.top<rowAxis.edges[r+1] && box.bottom>rowAxis.edges[r] }
            // Explicit period/week sets stay sparse; never fill min..max holes.
            val touchedPeriods=periodAxis.anchors.indices.filter { p -> index.work.compare();box.left<periodAxis.edges[p+1] && box.right>periodAxis.edges[p] }.flatMap { periodAxis.anchors[it].second }.toSet()
            val touchedWeeks=touchedRows.flatMap { plan.rows[it].weeks }.toSet()
            val text=index.groupLines(tokens).joinToString("/"){it.text}
            val explicitSections=Regex("(?:^|/)节次[:：]([^/]+)").find(text)?.groupValues?.get(1)?.let { LayoutFields(session.context).sections(it) }
            val explicitWeeks=Regex("(?:^|/)周次[:：]([^/]+)").find(text)?.groupValues?.get(1)?.let { LayoutFields(session.context).weeks(it) }
            val broadPeriods=touchedPeriods.isNotEmpty() && touchedPeriods!=periodAxis.anchors[cell.third].second
            val broadDays=touchedDays.size>1
            val broadRows=touchedRows.size>1
            val broadWeeks=touchedWeeks!=row.weeks
            // A week expression cannot resolve ordinary/date or multiple-date rows.
            val broadDates=broadRows && touchedRows.any { plan.rows[it].dateToken!=null }
            val scopeAmbiguous=ruledSections==null && (mergedCell!=null || broadDays || broadDates || broadPeriods && explicitSections!=touchedPeriods || broadWeeks && explicitWeeks!=touchedWeeks)
            val d=LayoutFields(session.context)
            val date=row.dateToken?.takeUnless { broadRows }?.let { d.date(it) }
            d.dateDeclarations(date,dayAxis.anchors[cell.first].second,row.weeks)
            val decoded=LayoutRecordDecoder(session.context).decode(text,tokens,dayAxis.anchors[cell.first].second.takeUnless { broadDays },
                explicitSections ?: ruledSections ?: if(mergedCell!=null || broadDays || broadPeriods) emptySet() else periodAxis.anchors[cell.third].second,
                explicitWeeks ?: if(broadRows && ruledSections==null) emptySet() else row.weeks,date,scopeAmbiguous,scopeSupportedByClosedCell=ruledSections!=null)
            if(decoded==null) session.region(index,tokens,OcrRegionKind.UNPARSED)
            else {
                val meeting=decoded.course.meetings.single()
                val warnings=meeting.warnings+d.issues+if(ruledSections!=null) listOf(layoutIssue("RULED_CELL_SCOPE_REVIEW_REQUIRED")) else emptyList()
                val updated=decoded.course.copy(meetings=listOf(meeting.copy(warnings=warnings)))
                val usedAnchors=periodAxis.anchors.filter {index.work.compare();ruledSections?.let {sections->it.second.any(sections::contains)} ?: (it.first==periodAxis.anchors[cell.third].first)}.map {it.first}.toSet()
                val supports=touchedRows.flatMap {plan.rows[it].token.originalParts}+touchedDays.flatMap { plan.days[it].originalParts }+plan.periods.filter { index.work.compare();it.x in usedAnchors }.flatMap {it.originalParts}
                session.record(index,tokens,updated,decoded.alternatives+if(mergedCell!=null) listOf("ENCLOSED_MERGED_CELL_SCOPE") else emptyList(),decoded.hasUnknown,decoded.fields,supportTokens=supports,completeTitle=decoded.completeTitle)
            }
        }
        for((day,axis) in periodAxes) if(axis!=null) for(row in plan.rows.indices) for(section in axis.anchors.indices) {
            index.work.compare()
            if(Triple(day,row,section) in cells) continue
            val box=TextBox(axis.edges[section],rowAxis.edges[row],axis.edges[section+1],rowAxis.edges[row+1])
            if(enclosedByLines(index,box)) session.region(index,emptyList(),OcrRegionKind.EMPTY_BODY,box=box,issues=listOf(layoutIssue("EMPTY_BODY_REGION_REVIEW_REQUIRED")))
        }
    }
    /** A weekend mini-table reuses page space, not the ordinary weekday/period axes.
     * Date text remains original provenance on an untimed lesson; never invent a
     * full day or borrow a neighboring teaching week. The existing model keeps
     * UNSCHEDULED dates empty, so retain the complete date heading as source text.
     */
    private fun parseSpecialNotices(index:OcrGeometryIndex,session:OcrLayoutSession):Set<OcrSpanId> {
        val consumed=linkedSetOf<OcrSpanId>()
        val label=Regex("^[0-9]{1,2}[./][0-9]{1,2}[（(](?:周|星期)[一二三四五六日天][）)]的课程安排$")
        val tolerance=index.glyphHeight*.4f
        for(header in index.tokens.filter {index.work.compare();label.matches(compactMatch(it.text))}) {
            consumed+=header.originalParts.map {it.id}
            session.region(index,header.originalParts,OcrRegionKind.STRUCTURAL_ANCHOR)
            val heading=enclosingRuledCell(index,header.box) ?: continue
            val groups=linkedMapOf<TextBox,MutableList<GeometryToken>>()
            for(token in index.tokens) {
                index.work.compare()
                if(token.id in consumed || token.box.top<heading.bottom-tolerance || token.x !in heading.left..heading.right) continue
                val cell=enclosingRuledCell(index,token.box) ?: continue
                if(kotlin.math.abs(cell.top-heading.bottom)>tolerance || kotlin.math.abs(cell.left-heading.left)>tolerance ||
                    kotlin.math.abs(cell.right-heading.right)>tolerance) continue
                groups.getOrPut(cell){mutableListOf()}+=token
            }
            for(tokens in groups.values) {
                index.checkCellText(tokens)
                val text=index.groupLines(tokens).joinToString("/"){it.text}
                val decoded=LayoutRecordDecoder(session.context).decode(text,tokens,null,emptySet(),forceUnscheduled=true) ?: continue
                val course=decoded.course.copy(meetings=decoded.course.meetings.map {it.copy(warnings=it.warnings+layoutIssue("SPECIAL_DATE_TIME_UNSPECIFIED"))})
                session.record(index,tokens,course,decoded.alternatives,decoded.hasUnknown,decoded.fields,supportTokens=header.originalParts,completeTitle=decoded.completeTitle)
                consumed+=tokens.flatMap {it.originalParts}.map {it.id}
            }
        }
        return consumed
    }
    /** Reuse measured four-side cells; never derive a range from title width alone.
     * Four connected edges are the criterion used by pdfplumber intersections_to_cells
     * (4c64b92d5caccd71c645e98e0fabb0c4dba7ff45, MIT). This reuses our bounded line
     * implementation, not its Python runtime or full TableFinder.
     */
    private fun ruledSections(index:OcrGeometryIndex,box:TextBox,day:Int,row:Int,days:GeometryAxis<Int>,rows:GeometryAxis<MatrixRow>,periods:GeometryAxis<Set<Int>>,plan:MatrixPlan):Set<Int>? {
        if(periods.anchors.size<3 || plan.rows[row].weeks.isEmpty() || plan.rows[row].dateToken!=null) return null
        val cell=enclosingRuledCell(index,box,index.glyphHeight*.2f,index.glyphHeight*1.1f) ?: return null
        val tolerance=index.glyphHeight*.2f
        if(cell.left<days.edges[day]-tolerance || cell.right>days.edges[day+1]+tolerance ||
            cell.top<rows.edges[row]-tolerance || cell.bottom>rows.edges[row+1]+tolerance ||
            plan.rows[row].token.y !in cell.top..cell.bottom) return null
        // A full internal divider means this is not one cell, even if OCR joined titles.
        if(index.page.lines.any {line->index.work.compare();line.vertical &&
            (line.bounds.left+line.bounds.right)/2f>cell.left+tolerance &&
            (line.bounds.left+line.bounds.right)/2f<cell.right-tolerance &&
            line.bounds.top<=cell.top+tolerance && line.bounds.bottom>=cell.bottom-tolerance}) return null
        val gaps=periods.anchors.zipWithNext().map {it.second.first-it.first.first}.filter {it>tolerance}.sorted()
        val typical=gaps.getOrNull((gaps.size-1)/2)
        if(typical!=null && periods.anchors.zipWithNext().any {(a,b)->index.work.compare();
                b.first-a.first>typical*1.8f && (a.first+b.first)/2f in cell.left..cell.right}) return null
        // Tolerance joins detected line segments, never expands a cell to include
        // the center of the neighboring printed period column.
        return periods.anchors.filter {index.work.compare();it.first>=cell.left && it.first<cell.right}
            .flatMap {it.second}.toSet().takeIf {it.isNotEmpty()}
    }
    /** Recognize explicit note forms on a measured baseline before date-axis
     * extraction. A note is a real token group, never a fabricated WORD/span. */
    private fun annotationLines(index:OcrGeometryIndex):List<List<GeometryToken>> {
        val result=mutableListOf<List<GeometryToken>>()
        val labelled=Regex("^(?:停课|取消|节假日(?:注记|说明)?|节日|放假|假日|假期|休假|调休)[:：]")
        val namedHoliday=Regex("^[^:：/]{1,64}(?:放假|假期|休假|调休)[:：]")
        val datedHoliday=Regex("^(?:元旦|春节|清明节|劳动节|端午节|中秋节|国庆节)(?:放假)?[（(][0-9]{1,2}[./][0-9]{1,2}[-~～至—–][0-9]{1,2}[./][0-9]{1,2}[）)]$")
        val numbered=Regex("^节假日(?:注记|说明)[0-9]+$")
        val bare=Regex("^(?:节假日|节日|放假|假日|停课|取消|调休)$")
        val clock=Regex("[0-9]{2}:[0-9]{2}[-~～至][0-9]{2}:[0-9]{2}")
        val cancellation=Regex("^(?:停课|取消)[:：]")
        for(line in index.lines) {
            val ts=line.tokens
            fun markerEnd(start:Int):Int? {
                val prefix=StringBuilder()
                for(end in start..minOf(ts.lastIndex,start+15)) {
                    index.work.compare()
                    if(end>start && ts[end].box.left-ts[end-1].box.right>index.glyphHeight*2f) break
                    prefix.append(ts[end].text.take(64-prefix.length))
                    index.work.spend(prefix.length.toLong())
                    val text=prefix.toString()
                    if(labelled.containsMatchIn(text) || namedHoliday.containsMatchIn(text) || datedHoliday.matches(text) || end==start && (numbered.matches(text) || ts.size==1 && bare.matches(text))) return end
                    if(prefix.length>=64) break
                }
                return null
            }
            var start=0
            while(start<ts.size) {
                val marker=markerEnd(start)
                if(marker==null) { start++;continue }
                val group=ts.subList(start,marker+1).toMutableList()
                index.checkCellText(group)
                val text=StringBuilder(group.joinToString(""){it.text})
                // This complete literal holiday note is not an instruction to cancel
                // courses and must not consume a neighboring course on the same row.
                if(datedHoliday.matches(text)) {result+=group;start=marker+1;continue}
                // A loose date/title column gap is allowed only for a cancellation
                // with a clock in the same spatially bounded continuation. Generic
                // notes and incomplete tails cannot claim another record's WORDs.
                fun boundedClockTail(from:Int):Boolean {
                    val tail=StringBuilder()
                    for(i in from..ts.lastIndex) {
                        index.work.compare();index.work.spend(tail.length.toLong())
                        val before=ts[i-1].box;val next=ts[i].box
                        val gap=next.left-before.right
                        // An incoming record cannot authorize its own separation by
                        // having a wide box. Trust only the established prior field.
                        val fieldWidth=before.right-before.left
                        if(gap>maxOf(index.glyphHeight*2f,fieldWidth*2f) || markerEnd(i)!=null) return false
                        if(tail.length.toLong()+ts[i].text.length>index.work.context.format.limits.maxFieldUnits) throw LayoutRejected("LIMIT_EXCEEDED")
                        tail.append(ts[i].text)
                        if(clock.containsMatchIn(tail)) return true
                    }
                    return false
                }
                var end=marker+1
                while(end<ts.size) {
                    index.work.compare();index.work.spend(text.length.toLong())
                    val next=ts[end]
                    val gap=next.box.left-ts[end-1].box.right
                    if(markerEnd(end)!=null || gap>index.glyphHeight*2f &&
                        (!cancellation.containsMatchIn(text) || clock.containsMatchIn(text) || !boundedClockTail(end))) break
                    if(text.length.toLong()+next.text.length>index.work.context.format.limits.maxFieldUnits) throw LayoutRejected("LIMIT_EXCEEDED")
                    group+=next;text.append(next.text);end++
                }
                index.checkCellText(group)
                if(result.size>=index.work.context.limits.maxRegions) throw LayoutRejected("LIMIT_EXCEEDED")
                result+=group
                start=end
            }
        }
        return result
    }
}
