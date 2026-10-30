/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.*

internal data class LiteralDateRow(val tokens:List<GeometryToken>,val value:String,val decorated:Boolean) {
    val y:Float get()=tokens.map {it.y}.average().toFloat()
}
internal data class DateListPlan(val index:OcrGeometryIndex,val headers:List<Pair<GeometryToken,String>>,val headerTokens:List<GeometryToken>,val headerBottom:Float,val dateColumn:Int,val axis:GeometryAxis<String>,val dateRows:List<LiteralDateRow>) {
    val rowYs get()=dateRows.map {it.y}
    val candidate get()=OcrLayoutCandidate("date-list",index.page.pageNumber,setOf("DECLARED_TABLE_FIELDS","ALIGNED_EXPLICIT_DATE_ROWS"),headerTokens.map { it.id })
}
/** Literal dated rows. A generic 时间 header becomes a date column only through actual
 * full-date tokens plus independently measured table/header alignment. */
class DateListAdapter internal constructor() {
    private val names=mapOf("日期" to "date","上课日期" to "date","时间" to "time","上课时间" to "time","星期" to "day","周次" to "weeks","教学周" to "weeks",
        "节次" to "sections","节次/时间" to "sectiontime","节次时间" to "sectiontime","课程名称" to "name","课程" to "name","课程代码" to "code","课程编号" to "code",
        "教学班代码" to "class","项目" to "project","实验项目" to "project","项目名称" to "project","实验室" to "room","教室" to "room","场地" to "room",
        "班组" to "group","班级名称" to "group","班级" to "group","教师" to "teacher","任课教师" to "teacher")
    private val maxHeaderCharacters=names.keys.maxOf {it.length}
    private fun literalDate(text:String):Pair<String,Boolean>? {
        val value=compactMatch(text)
        if(isFullDateToken(value)) return value to false
        // A clock icon may be recognized as @. Keep a review warning and original text;
        // never extract date substrings from email addresses or other arbitrary strings.
        if(value.startsWith('@') && isFullDateToken(value.drop(1))) return value.drop(1) to true
        return null
    }
    private fun dateRow(tokens:List<GeometryToken>,header:GeometryToken,index:OcrGeometryIndex):LiteralDateRow? {
        // groupBy preserves the already x-sorted GeometryLine encounter order.
        val ordered=tokens
        literalDate(ordered.joinToString("") {it.text})?.let {return LiteralDateRow(ordered,it.first,it.second)}
        val candidates=ordered.mapNotNull {token->index.work.compare();literalDate(token.text)?.let {token to it}}
        if(candidates.size!=1) return null
        val (token,value)=candidates.single()
        // A missing neighboring header can widen the bucket. Claim only an aligned
        // complete date; the neighboring tokens remain explicitly unparsed.
        val gap=maxOf(0f,header.box.left-token.box.right,token.box.left-header.box.right)
        if(gap>index.glyphHeight*2f) return null
        return LiteralDateRow(listOf(token),value.first,value.second)
    }
    internal fun detect(index:OcrGeometryIndex):DateListPlan? {
        for(line in index.lines) {
            index.work.check()
            val headers=mutableListOf<Pair<GeometryToken,String>>()
            val unknownHeaders=mutableListOf<GeometryToken>()
            var i=0
            while(i<line.tokens.size) {
                val t=line.tokens[i]
                var bestEnd=-1;var bestField:String?=null;var joined=""
                // Native Chinese WORDs can split one heading into more than two parts.
                // Match the longest exact known label within the existing gap bound.
                for(end in i until minOf(line.tokens.size,i+maxHeaderCharacters)) {
                    index.work.compare()
                    if(end>i && line.tokens[end].box.left-line.tokens[end-1].box.right>index.glyphHeight*2f) break
                    joined+=compactMatch(line.tokens[end].text)
                    if(joined.length>maxHeaderCharacters) break
                    names[joined]?.let {bestEnd=end;bestField=it}
                }
                if(bestEnd>=i) {
                    val members=line.tokens.subList(i,bestEnd+1)
                    headers+=t.copy(span=t.span.copy(rawText=members.joinToString("") {it.span.rawText},position=SourcePosition(index.page.pageNumber,boundsOf(members)!!))) to bestField!!
                    i=bestEnd+1;continue
                }
                unknownHeaders+=t;i++
            }
            if(headers.size<3 || headers.none { it.second=="name" } || headers.none { it.second in setOf("date","time") }) continue
            if(headers.map { it.second }.groupingBy { it }.eachCount().any { it.key!="time" && it.value>1 }) continue
            // Unknown columns get their own measured bucket; their body cannot be
            // accidentally concatenated into the nearest recognized field.
            val sorted=(headers+unknownHeaders.map { it to "unknown:${it.id.index}" }).sortedBy { it.first.x }
            val axis=index.measuredAxis(sorted.map { it.first.x to it.second },0f,index.page.width,line.bottom,index.tokens.maxOfOrNull { it.box.bottom } ?: line.bottom)
            val columnRows=mutableMapOf<Int,MutableList<LiteralDateRow>>()
            for(body in index.lines) if(body.top>line.bottom) {
                val buckets=body.tokens.groupBy { axis.bucket(it.x) }
                for((col,ts) in buckets) if(col!=null && sorted[col].second in setOf("date","time")) {
                    index.work.compare();dateRow(ts,sorted[col].first,index)?.let {columnRows.getOrPut(col){mutableListOf()}+=it}
                }
            }
            val dates=sorted.indices.filter { c -> sorted[c].second in setOf("date","time") && columnRows[c].orEmpty().isNotEmpty() }
            val declared=sorted.indices.filter { sorted[it].second=="date" }
            val dateColumn=when { dates.size==1 -> dates.single();dates.isEmpty() && declared.size==1 -> declared.single();else -> continue }
            val rows=columnRows[dateColumn].orEmpty().sortedBy {it.y}
            if(rows.isEmpty()) continue
            return DateListPlan(index,sorted,line.tokens,line.bottom,dateColumn,axis,rows)
        }
        return null
    }
    internal fun parse(plan:DateListPlan,session:OcrLayoutSession) {
        val index=plan.index
        session.region(index,plan.headerTokens,OcrRegionKind.STRUCTURAL_ANCHOR)
        val ys=plan.rowYs.sorted()
        for((row,y) in ys.withIndex()) {
            index.work.check()
            val top=if(row==0) plan.headerBottom else (ys[row-1]+y)/2f
            val bottom=ys.getOrNull(row+1)?.let { (it+y)/2f } ?: (y+(if(row>0) y-ys[row-1] else index.glyphHeight*4f)/2f)
            val tokens=index.inBox(TextBox(0f,top,index.page.width,bottom))
            val cells=tokens.groupBy { plan.axis.bucket(it.x) }
            val values=linkedMapOf<String,String>();val used=mutableListOf<GeometryToken>();val assignments=mutableMapOf<OcrSpanId,Set<String>>()
            for((column,ts) in cells) if(column!=null) {
                val field=if(column==plan.dateColumn) "date" else plan.headers[column].second
                if(field.startsWith("unknown:")) {
                    session.region(index,ts,OcrRegionKind.UNPARSED,issues=listOf(layoutIssue("UNPARSED_COLUMN")))
                    continue
                }
                val selected=if(column==plan.dateColumn) plan.dateRows[row].tokens else ts
                val remaining=if(column==plan.dateColumn) {
                    val selectedIds=selected.mapTo(hashSetOf()) {index.work.compare();it.id}
                    ts.filter {index.work.compare();it.id !in selectedIds}
                } else emptyList()
                if(remaining.isNotEmpty()) session.region(index,remaining,OcrRegionKind.UNPARSED,issues=listOf(layoutIssue("UNPARSED_COLUMN")))
                index.checkCellText(selected)
                val text=if(column==plan.dateColumn) plan.dateRows[row].value else index.groupLines(selected).joinToString("") {it.text}
                values[field]=text;used+=selected
                selected.forEach { assignments[it.id]=setOf(field) }
            }
            // A genuinely merged code/title cell can cover several row baselines. Source
            // bounds must explicitly contain this row; blank cells are never filled down.
            for((col,header) in plan.headers.withIndex()) if(header.second in setOf("code","class","name") && values[header.second].isNullOrBlank()) {
                val merged=index.inBox(TextBox(plan.axis.edges[col],plan.headerBottom,plan.axis.edges[col+1],index.page.height)).filter {
                    index.work.compare();it.box.top<=y && it.box.bottom>=y
                }
                if(merged.size==1) { values[header.second]=merged.single().text;used+=merged;assignments[merged.single().id]=setOf(header.second) }
            }
            val d=LayoutFields(session.context)
            if(plan.dateRows[row].decorated) d.issue("DATE_ICON_PREFIX_REVIEW_REQUIRED")
            val date=d.date(values["date"])
            val day=values["day"]?.let { layoutDay(it) ?: run { d.issue("INVALID_DAY_OF_WEEK");null } }
            val weeks=d.datedWeeks(values["weeks"],date,day)
            var sectionText=values["sections"]
            var timeText=values["time"]
            values["sectiontime"]?.let { combined ->
                val m=Regex("^([^()]+)\\(([^()]+)\\)$").matchEntire(compactMatch(combined))
                if(m!=null) { sectionText=m.groupValues[1];timeText=m.groupValues[2] }
                else { sectionText=combined;d.issue("COMBINED_TIME_REVIEW_REQUIRED") }
            }
            val sections=d.sections(sectionText);val time=d.time(timeText)
            d.dateDeclarations(date,day,weeks)
            var name=values["name"].orEmpty()
            val completeTitle=name
            val embedded=Regex("^(.+?)\\(([0-9]{6,})\\)$").matchEntire(name)
            val code=values["code"]?.takeIf { it.isNotBlank() } ?: embedded?.groupValues?.get(2)
            if(embedded!=null) name=embedded.groupValues[1].trim()
            for(col in plan.headers.indices) {
                index.work.compare()
                val field=plan.headers[col].second
                if(!values[field].isNullOrBlank() || cells[col]?.isNotEmpty()==true) continue
                val box=TextBox(plan.axis.edges[col],top,plan.axis.edges[col+1],bottom)
                if(enclosedByLines(index,box)) session.region(index,emptyList(),OcrRegionKind.EMPTY_BODY,box=box,issues=listOf(layoutIssue("EMPTY_BODY_REGION_REVIEW_REQUIRED")))
            }
            if(name.isBlank()) { session.region(index,used,OcrRegionKind.UNPARSED,issues=listOf(layoutIssue("MISSING_COURSE_NAME")));continue }
            val sources=used.distinctBy { it.id }.map { it.excerpt() }
            val kind=if(date!=null) MeetingKind.DATE_SPECIFIC else MeetingKind.UNSCHEDULED
            val missing=buildSet { if(date==null) add(ImportField.TARGET_DATE);if(sections.isEmpty() && time==null) add(ImportField.TIME) }
            val meeting=RichMeetingDraft(kind,if(date!=null) day else null,weeks,sections,time,setOfNotNull(date),values["teacher"],values["room"],values["group"],values["project"],values["project"]!=null,sources,d.issues.toList(),missing)
            val identityWarnings=if(embedded!=null && values["code"].isNullOrBlank()) listOf(layoutIssue("COURSE_CODE_SUFFIX_REVIEW_REQUIRED")) else emptyList()
            session.record(index,used,RichCourseDraft(name,code,values["class"],listOf(meeting),sources,warnings=identityWarnings),assignments=assignments,completeTitle=completeTitle)
        }
    }
}
