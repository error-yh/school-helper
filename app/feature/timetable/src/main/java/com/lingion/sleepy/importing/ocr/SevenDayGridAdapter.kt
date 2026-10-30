/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.exactTime
import com.lingion.sleepy.importing.formats.exactDate
import kotlin.math.abs

internal data class GridBlock(
    val header:GeometryLine,val days:List<GeometryToken>,val periods:List<Pair<GeometryToken,Set<Int>>>,val end:Float,
    val headerBottom:Float=header.bottom,val dayCenters:List<Float> = days.map { it.x },
    val inherited:Boolean=false,
    val dayEdges:List<Float>?=null,
    val axisSupport:List<GeometryToken> = emptyList(),
)
internal data class GridPlan(val index:OcrGeometryIndex,val blocks:List<GridBlock>) {
    val candidate get()=OcrLayoutCandidate("seven-day-grid",index.page.pageNumber,setOf("WEEKDAY_COLUMNS","VERTICAL_SECTION_AXIS")+
        if(blocks.any { it.inherited }) setOf("MEASURED_CONTINUATION_BOUNDARIES","SEQUENTIAL_SECTION_AXIS") else emptySet(),
        blocks.flatMap { b->b.days.flatMap {it.originalParts}.map { it.id }+b.periods.map { it.first.id }+b.axisSupport.map {it.id} })
}
/** Each measured block gets fresh weekday and section axes. No page-width inheritance. */
class SevenDayGridAdapter internal constructor() {
    private val axisClock=Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    internal fun detect(index:OcrGeometryIndex):GridPlan? {
        val headers=index.lines.mapNotNull { rawLine ->
            val line=exactWeekHeaderLine(rawLine,index)
            val days=line.tokens.filter { isDayAnchor(it.text) }
            if(days.isEmpty() || days.map { layoutDay(it.text) }.distinct().size!=days.size) null else line to days
        }
        val blocks=mutableListOf<GridBlock>()
        for((i,pair) in headers.withIndex()) {
            val (line,days)=pair
            val end=headers.getOrNull(i+1)?.first?.top ?: index.page.height
            val left=dayLeft(days,index)
            val periods=index.tokens.mapNotNull { t ->
                index.work.compare()
                sectionAxis(t.text)?.takeIf { t.x<left && t.box.top>line.bottom && t.box.bottom<end }?.let { t to it }
            }.sortedBy { it.first.y }
            if(periods.isNotEmpty() && periods.flatMap { it.second }.distinct().size==periods.sumOf { it.second.size }) {
                // A week/day matrix also has numbered rows left of weekday columns.
                // Bare numbers cannot independently tell a teaching week from a section.
                val labelled=periods.any {compactMatch(it.first.text).endsWith("节") || individualAxisClock(it.first.text)!=null}
                val label=index.tokens.firstOrNull {t->index.work.compare();
                    compactMatch(t.text) in setOf("节次","节数") && t.x<left &&
                        t.y>=line.top-index.glyphHeight*2f && t.y<=line.bottom+index.glyphHeight*2f}
                val minX=periods.minOf {it.first.x}-index.glyphHeight*4f
                val maxX=periods.maxOf {it.first.x}+index.glyphHeight*4f
                val clocks=if(labelled || label!=null) emptyList() else index.tokens.filter {t->index.work.compare();
                    t.x in minX..maxX && t.x<left && t.y>=periods.first().first.y-index.glyphHeight &&
                        t.y<=periods.last().first.y+index.glyphHeight &&
                        compactMatch(t.text).matches(axisClock)}
                if(!labelled && label==null && clocks.map {compactMatch(it.text)}.distinct().size<2) continue
                val axis=index.measuredAxis(days.map { it.x to layoutDay(it.text)!! },left,dayRight(days.map { it.x },index),line.bottom,periods.last().first.box.bottom)
                blocks+=GridBlock(line,days,periods,end,dayEdges=axis.edges,axisSupport=listOfNotNull(label)+clocks)
            }
        }
        return blocks.takeIf { it.isNotEmpty() }?.let { GridPlan(index,it) }
    }
    private fun dayLeft(days:List<GeometryToken>,index:OcrGeometryIndex):Float {
        val first=days.first().x
        return if(days.size>1) maxOf(0f,first-(days[1].x-first)/2f) else maxOf(0f,days.first().box.left-index.glyphHeight*3f)
    }
    private fun dayRight(centers:List<Float>,index:OcrGeometryIndex):Float =
        if(centers.size>1) minOf(index.page.width,centers.last()+(centers.last()-centers[centers.lastIndex-1])/2f) else index.page.width

    /** A headerless continuation needs all measured column boundaries, enclosing
     * horizontal structure, and a consecutive section sequence. Equal width is irrelevant. */
    internal fun continuation(index:OcrGeometryIndex,previous:GridPlan?):GridPlan? {
        val prior=previous?.takeIf { index.page.pageNumber==it.index.page.pageNumber+1 } ?: return null
        val old=prior.blocks.last();val ratio=index.page.width/prior.index.page.width
        val centers=old.dayCenters.map { it*ratio }
        val inheritedEdges=old.dayEdges?.map { it*ratio }
        val left=inheritedEdges?.first() ?: (if(centers.size>1) centers.first()-(centers[1]-centers[0])/2f else old.days.first().box.left*ratio-index.glyphHeight*3f).coerceAtLeast(0f)
        val right=inheritedEdges?.last() ?: dayRight(centers,index)
        val axis=GeometryAxis(centers.mapIndexed { i,x -> x to layoutDay(old.days[i].text)!! },left,right,index.work,inheritedEdges)
        val vertical=index.page.lines.filter { it.vertical }
        val tolerance=index.glyphHeight*.6f
        val matched=axis.edges.map { edge -> vertical.firstOrNull { line -> index.work.compare();abs((line.bounds.left+line.bounds.right)/2f-edge)<=tolerance } ?: return null }
        val horizontal=index.page.lines.filter { line -> index.work.compare();!line.vertical && line.bounds.left<=left+tolerance && line.bounds.right>=right-tolerance }
        if(horizontal.size<2) return null
        val top=horizontal.minOf { it.bounds.top };val bottom=horizontal.maxOf { it.bounds.bottom }
        if(matched.any { it.bounds.top>top+tolerance || it.bounds.bottom<bottom-tolerance }) return null
        val periods=index.tokens.mapNotNull { t -> index.work.compare();sectionAxis(t.text)?.takeIf { t.x<left && t.y>top && t.y<bottom }?.let { t to it } }.sortedBy { it.first.y }
        if(periods.isEmpty()) return null
        var expected=old.periods.last().second.max()+1
        for((_,sections) in periods) {
            if(sections.min()!=expected || sections!= (sections.min()..sections.max()).toSet()) return null
            expected=sections.max()+1
        }
        if(index.tokens.none { index.work.compare();it.x>=left && it.x<right && it.y>top && it.y<bottom }) return null
        return GridPlan(index,listOf(GridBlock(old.header,old.days,periods,bottom,top,centers,true,axis.edges)))
    }
    internal fun parse(plan:GridPlan,session:OcrLayoutSession) {
        val index=plan.index
        val annotations=index.tokens.filter {index.work.compare();exportAnnotation(it.text)}
        annotations.forEach {session.region(index,listOf(it),OcrRegionKind.ANNOTATION)}
        val annotationIds=annotations.mapTo(hashSetOf()) {it.id}
        for(block in plan.blocks) {
            session.region(index,(if(block.inherited) emptyList() else block.days.flatMap {it.originalParts})+block.periods.map { it.first }+block.axisSupport,OcrRegionKind.STRUCTURAL_ANCHOR)
            block.periods.forEach { session.period(it.first) }
            val left=block.dayEdges?.first() ?: if(block.inherited && block.dayCenters.size>1) (block.dayCenters.first()-(block.dayCenters[1]-block.dayCenters.first())/2f).coerceAtLeast(0f) else dayLeft(block.days,index)
            val right=block.dayEdges?.last() ?: dayRight(block.dayCenters,index)
            val stackedClocks=stackedPeriodClocks(index,block,left,session)
            stackedClocks.forEach(session::period)
            val clockSections=block.periods.mapNotNull {individualAxisClock(it.first.text)?.first}.toSet()+stackedClocks.map {it.section}
            val days=GeometryAxis(block.dayCenters.mapIndexed { i,x -> x to layoutDay(block.days[i].text)!! },left,right,index.work,block.dayEdges)
            val periodAxis=GeometryAxis(block.periods.map { it.first.y to it.second },block.headerBottom,block.end,index.work)
            val labelledTokens=parseLabelledColumns(index,block,days,periodAxis,clockSections,session,annotationIds)
            val cells=linkedMapOf<Pair<Int,Int>,MutableList<GeometryToken>>()
            for(token in index.tokens) {
                index.work.compare()
                if(token.id in labelledTokens || token.id in annotationIds) continue
                if(token.box.top<=block.headerBottom || token.box.bottom>=block.end || practiceText(token.text)!=null) continue
                val day=days.bucket(token.x) ?: continue
                val row=periodAxis.bucket(token.y) ?: continue
                cells.getOrPut(day to row){mutableListOf()}+=token
            }
            for((cell,tokens) in cells) {
                index.work.check()
                index.checkCellText(tokens)
                val lines=index.groupLines(tokens)
                val records=splitRecords(lines,session.context)
                for(record in records) {
                    val all=record.flatMap { it.tokens }.distinctBy { it.id }
                    val box=boundsOf(all)!!
                    val touchedDays=days.anchors.indices.filter { d -> index.work.compare();box.left<days.edges[d+1] && box.right>days.edges[d] }
                    val touchedRows=periodAxis.anchors.indices.filter { r -> index.work.compare();box.top<periodAxis.edges[r+1] && box.bottom>periodAxis.edges[r] }
                    val scope=touchedRows.flatMap { periodAxis.anchors[it].second }.toSet()
                    val mergedCell=enclosingMergedCell(index,box,days.edges.drop(1).dropLast(1),periodAxis.edges.drop(1).dropLast(1))
                    val decoded=decodeContainer(record,session.context,days.anchors[cell.first].second,periodAxis.anchors[cell.second].second,index.work)?.let {
                        reviewScope(it,touchedDays.size>1,touchedRows.size>1,scope,mergedCell!=null)
                    }
                    if(decoded==null) session.region(index,all,OcrRegionKind.UNPARSED)
                    else session.record(index,all,decoded.course,decoded.alternatives,decoded.hasUnknown,decoded.fields,supportTokens=touchedDays.flatMap { block.days[it].originalParts }+touchedRows.map { block.periods[it].first },completeTitle=decoded.completeTitle)
                }
            }
            // A box supported on all four sides is a blank-body hypothesis for review,
            // never proof that OCR saw an empty lesson. Lines alone cannot create courses.
            for(day in days.anchors.indices) for(row in periodAxis.anchors.indices) {
                index.work.compare()
                if((day to row) in cells) continue
                val box=TextBox(days.edges[day],periodAxis.edges[row],days.edges[day+1],periodAxis.edges[row+1])
                if(enclosedByLines(index,box)) session.region(index,emptyList(),OcrRegionKind.EMPTY_BODY,box=box,issues=listOf(layoutIssue("EMPTY_BODY_REGION_REVIEW_REQUIRED")))
            }
        }
        parsePractices(index,session)
    }
    /** The PDF importer already handles labelled schedules and wrapped teacher/room
     * lines. Reuse that grammar on a measured weekday column before row bucketing. */
    private fun parseLabelledColumns(index:OcrGeometryIndex,block:GridBlock,days:GeometryAxis<Int>,periods:GeometryAxis<Set<Int>>,clocks:Set<Int>,session:OcrLayoutSession,excluded:Set<OcrSpanId>):Set<OcrSpanId> {
        val consumed=mutableSetOf<OcrSpanId>();val parser=PdfTimetableParser()
        for(day in days.anchors.indices) {
            val tokens=index.tokens.filter {t->index.work.compare();t.id !in excluded && t.box.top>block.headerBottom && t.box.bottom<block.end &&
                t.box.left>=days.edges[day] && t.box.right<=days.edges[day+1] && practiceText(t.text)==null}
            if(tokens.none {it.text.contains(Regex("\\([0-9]{1,2}(?:-[0-9]{1,2})?节\\)"))}) continue
            index.checkCellText(tokens)
            val lines=index.groupLines(tokens)
            val excerpts=lines.map {SourceExcerpt(SourcePosition(index.page.pageNumber,boundsOf(it.tokens)!!),it.tokens.joinToString(""){t->t.span.rawText})}
            val parsed=parser.parseLabelledColumn(excerpts,days.anchors[day].second,session.context.format.maxWeek,maxWrappedTitleGap=index.glyphHeight*.65f)
            if(parsed.courses.any {it.name.contains(Regex("(?:教师|场地|教室|校区)[:：]"))}) continue
            for(course in parsed.courses) {
                val owned=tokens.filter {t->course.sources.any {s->index.work.compare();val b=s.position.bounds;
                    t.box.left>=b.left && t.box.right<=b.right && t.box.top>=b.top && t.box.bottom<=b.bottom}}
                if(owned.isEmpty()) continue
                val declared=course.meetings.flatMap {it.sections}.toSet()
                val box=boundsOf(owned)!!
                val measured=periods.anchors.indices.filter {r->box.top<periods.edges[r+1] && box.bottom>periods.edges[r]}
                    .flatMap {periods.anchors[it].second}.toSet()
                val supports=block.days[day].originalParts+block.periods.filter {it.second.any {section->section in declared}}.flatMap {it.first.originalParts}
                val withEvidence=course.copy(meetings=course.meetings.map {m->m.copy(
                    sources=(m.sources+owned.map {it.excerpt()}).distinct(),
                    warnings=m.warnings+if(m.sections!=measured) listOf(layoutIssue("SECTION_AXIS_MISMATCH")) else emptyList(),
                    missingFields=if(m.sections.isNotEmpty() && clocks.containsAll(m.sections)) m.missingFields-ImportField.TIME else m.missingFields)})
                session.record(index,owned,withEvidence,fields=setOf("record","sections","weeks","teacher","room"),supportTokens=supports)
                consumed+=owned.map {it.id}
            }
        }
        return consumed
    }
    /** Recognized section number followed by exactly two literal clocks in its axis
     * column. Incomplete pairs stay unknown; no host/default clock is substituted. */
    private fun stackedPeriodClocks(index:OcrGeometryIndex,block:GridBlock,left:Float,session:OcrLayoutSession):List<PeriodProposal> =
        block.periods.mapIndexedNotNull {i,(number,sections)->
            if(sections.size!=1 || individualAxisClock(number.text)!=null) return@mapIndexedNotNull null
            val end=block.periods.getOrNull(i+1)?.first?.box?.top ?: block.end
            val clocks=index.tokens.filter {t->index.work.compare();t.x<left && t.box.top>=number.box.top && t.box.bottom<=end &&
                abs(t.x-number.x)<=index.glyphHeight*4f && compactMatch(t.text).matches(axisClock)}.sortedBy {it.y}
            if(clocks.size!=2) return@mapIndexedNotNull null
            val start=exactTime(compactMatch(clocks[0].text)) ?: return@mapIndexedNotNull null
            val finish=exactTime(compactMatch(clocks[1].text))?.takeIf {it>start} ?: return@mapIndexedNotNull null
            session.region(index,listOf(number)+clocks,OcrRegionKind.STRUCTURAL_ANCHOR)
            PeriodProposal(sections.single(),DraftTimeRange(start,finish),(listOf(number)+clocks).map {it.excerpt()})
        }
    private fun reviewScope(record:LayoutDecodedRecord,broadDays:Boolean,broadRows:Boolean,scope:Set<Int>,mergedCell:Boolean):LayoutDecodedRecord {
        val meetings=record.course.meetings
        // Provenance comes from the same joined record the decoder understood;
        // a native WORD boundary can split any field label or declaration.
        val explicit=record.declaredSections
        val resolvedRows=explicit.all { it } && meetings.flatMap { it.sections }.toSet()==scope
        if(!mergedCell && !broadDays && (!broadRows || resolvedRows)) return record
        val revised=meetings.mapIndexed { i,m -> m.copy(kind=MeetingKind.UNSCHEDULED,dayOfWeek=null,
            sections=if(explicit[i]) m.sections else emptySet(),warnings=(m.warnings+layoutIssue("AMBIGUOUS_CELL_SPAN")).distinctBy { it.code },
            missingFields=m.missingFields+setOf(ImportField.TIME,ImportField.DAY_OF_WEEK)) }
        return record.copy(course=record.course.copy(meetings=revised),alternatives=(record.alternatives+"UNRESOLVED_SCOPE"+if(mergedCell) listOf("ENCLOSED_MERGED_CELL_SCOPE") else emptyList()).distinct())
    }
    private fun decodeContainer(lines:List<GeometryLine>,context:OcrLayoutContext,day:Int,sections:Set<Int>,work:LayoutWork):LayoutDecodedRecord? {
        val decoder=LayoutRecordDecoder(context)
        val starts=lines.indices.filter { i -> i>0 && (lines[i].text.startsWith('(') || lines[i].text.startsWith("周次:")) }
        if(starts.size<2) return decoder.decode(lines.joinToString("/"){it.text},lines.flatMap { it.tokens },day,sections)
        // One real title container supplies identity only within this cell. Repeated
        // same-title containers elsewhere remain independent candidates/proposals.
        val title=lines.take(starts.first())
        val titleUnits=title.sumOf { it.text.length.toLong() }
        val decoded=starts.mapIndexedNotNull { i,start ->
            val tail=lines.subList(start,starts.getOrNull(i+1) ?: lines.size)
            // Charge repeated shared-title work before building each candidate string.
            work.spend(titleUnits+tail.sumOf { it.text.length.toLong() })
            val part=title+tail
            decoder.decode(part.joinToString("/"){it.text},part.flatMap { it.tokens },day,sections)
        }
        if(decoded.isEmpty()) return null
        return decoded.first().copy(course=decoded.first().course.copy(meetings=decoded.flatMap { it.course.meetings }),fields=decoded.flatMap { it.fields }.toSet(),alternatives=decoded.flatMap { it.alternatives },hasUnknown=decoded.any { it.hasUnknown },declaredSections=decoded.flatMap { it.declaredSections })
    }
    private fun splitRecords(lines:List<GeometryLine>,context:OcrLayoutContext):List<List<GeometryLine>> {
        val decoder=LayoutRecordDecoder(context)
        val records=mutableListOf<MutableList<GeometryLine>>()
        for(line in lines) {
            val continuation=decoder.isContinuation(line.text)
            if(records.isEmpty() || !continuation && records.last().any { decoder.isContinuation(it.text) || it.text.contains(':') }) records+=mutableListOf(line)
            else if(!continuation && records.last().last().text.contains('/')) records+=mutableListOf(line)
            else records.last()+=line
        }
        return records
    }
    /** Complete export fields only: retain malformed/partial labels as unknown evidence. */
    private fun exportAnnotation(text:String):Boolean {
        val value=compactMatch(text)
        if(value.startsWith("打印时间:")) return exactDate(value.removePrefix("打印时间:"))!=null
        val labels=value.split(':')
        return labels.size>=3 && labels.distinct().size==labels.size &&
            labels.all {it in setOf("理论","实践","实验","上机","讨论辅导","设计作业","课外")}
    }
    private fun practiceText(text:String):String? = Regex("^(?:实践课程|其他课程)[:：](.*)$").matchEntire(text.trim())?.groupValues?.get(1)
    private fun parsePractices(index:OcrGeometryIndex,session:OcrLayoutSession) {
        for(line in index.lines) {
            val body=practiceText(line.text) ?: continue
            for(part in body.split(';','；')) {
                index.work.check()
                if(part.isBlank()) continue
                val decoded=LayoutRecordDecoder(session.context).decode(part,line.tokens,null,emptySet(),practice=true)
                if(decoded==null) session.region(index,line.tokens,OcrRegionKind.UNPARSED)
                else session.record(index,line.tokens,decoded.course,decoded.alternatives,decoded.hasUnknown,decoded.fields,completeTitle=decoded.completeTitle)
            }
        }
    }
}

/** A localized divider gap plus four real enclosing sides supports a merged-cell
 * hypothesis. An outer table rectangle alone cannot make every short title merged. */
internal fun enclosingRuledCell(index:OcrGeometryIndex,box:TextBox,tolerance:Float=index.glyphHeight*.6f,minimumSide:Float=index.glyphHeight*2f):TextBox? {
    val horizontal=index.page.lines.filter { line -> index.work.compare();!line.vertical && line.bounds.left<=box.left+tolerance && line.bounds.right>=box.right-tolerance && line.bounds.right-line.bounds.left>=minimumSide }
    fun y(line:TableLineEvidence)=(line.bounds.top+line.bounds.bottom)/2f
    val top=horizontal.filter { index.work.compare();y(it)<=box.top+tolerance }.maxByOrNull { y(it) } ?: return null
    val bottom=horizontal.filter { index.work.compare();y(it)>=box.bottom-tolerance }.minByOrNull { y(it) } ?: return null
    if(y(top)>=y(bottom)) return null
    val vertical=index.page.lines.filter { line -> index.work.compare();line.vertical && line.bounds.top<=y(top)+tolerance && line.bounds.bottom>=y(bottom)-tolerance && line.bounds.bottom-line.bounds.top>=minimumSide }
    fun x(line:TableLineEvidence)=(line.bounds.left+line.bounds.right)/2f
    val left=vertical.filter { index.work.compare();x(it)<=box.left+tolerance }.maxByOrNull { x(it) } ?: return null
    val right=vertical.filter { index.work.compare();x(it)>=box.right-tolerance }.minByOrNull { x(it) } ?: return null
    val cell=TextBox(x(left),y(top),x(right),y(bottom))
    if(cell.left>=cell.right || listOf(top,bottom).any { it.bounds.left>cell.left+tolerance || it.bounds.right<cell.right-tolerance }) return null
    return cell
}

internal fun enclosingMergedCell(index:OcrGeometryIndex,box:TextBox,verticalEdges:List<Float>,horizontalEdges:List<Float>):TextBox? {
    if(verticalEdges.isEmpty() && horizontalEdges.isEmpty()) return null
    val cell=enclosingRuledCell(index,box) ?: return null
    val tolerance=index.glyphHeight*.6f
    fun y(line:TableLineEvidence)=(line.bounds.top+line.bounds.bottom)/2f
    fun x(line:TableLineEvidence)=(line.bounds.left+line.bounds.right)/2f
    fun gap(edge:Float,isVertical:Boolean):Boolean {
        val a=if(isVertical) cell.top else cell.left;val b=if(isVertical) cell.bottom else cell.right
        var before=false;var after=false
        for(line in index.page.lines) {
            index.work.compare();if(line.vertical!=isVertical) continue
            val position=if(isVertical) x(line) else y(line)
            if(abs(position-edge)>tolerance) continue
            val start=if(isVertical) line.bounds.top else line.bounds.left;val end=if(isVertical) line.bounds.bottom else line.bounds.right
            if(start<a-tolerance && abs(end-a)<=tolerance) before=true
            if(end>b+tolerance && abs(start-b)<=tolerance) after=true
        }
        return before && after
    }
    return cell.takeIf {
        verticalEdges.any { edge -> index.work.compare();edge>cell.left+tolerance && edge<cell.right-tolerance && gap(edge,true) } ||
            horizontalEdges.any { edge -> index.work.compare();edge>cell.top+tolerance && edge<cell.bottom-tolerance && gap(edge,false) }
    }
}

internal fun enclosedByLines(index:OcrGeometryIndex,box:TextBox):Boolean {
    var left=false;var right=false;var top=false;var bottom=false
    val tolerance=index.glyphHeight*.6f
    for(line in index.page.lines) {
        index.work.compare();val b=line.bounds
        if(line.vertical && b.top<=box.top+tolerance && b.bottom>=box.bottom-tolerance) {
            if(abs((b.left+b.right)/2f-box.left)<=tolerance) left=true
            if(abs((b.left+b.right)/2f-box.right)<=tolerance) right=true
        }
        if(!line.vertical && b.left<=box.left+tolerance && b.right>=box.right-tolerance) {
            if(abs((b.top+b.bottom)/2f-box.top)<=tolerance) top=true
            if(abs((b.top+b.bottom)/2f-box.bottom)<=tolerance) bottom=true
        }
    }
    return left && right && top && bottom
}
