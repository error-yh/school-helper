/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.*
import kotlin.math.abs
import java.util.TreeMap

internal class LayoutRejected(val code:String):RuntimeException()
internal fun layoutIssue(code:String)=ParseIssue(code,"OCR layout requires review")
internal class LayoutWork(val context:OcrLayoutContext) {
    private var comparisons=0L
    fun check()=context.format.cancellation.check()
    fun spend(units:Long) { check();if(units<0 || units>context.limits.maxComparisons-comparisons) throw LayoutRejected("LIMIT_EXCEEDED");comparisons+=units }
    fun compare()=spend(1)
}
internal data class GeometryToken(val id:OcrSpanId,val span:OcrSpan,val headerParts:List<GeometryToken> = emptyList()) {
    val originalParts:List<GeometryToken> get()=headerParts.ifEmpty {listOf(this)}
    val box:TextBox get()=span.position!!.bounds
    val x:Float get()=box.left+(box.right-box.left)/2f
    val y:Float get()=box.top+(box.bottom-box.top)/2f
    val text:String get()=layoutMatch(span.rawText)
    fun excerpt()=SourceExcerpt(span.position!!,span.rawText)
}
internal data class GeometryLine(val tokens:List<GeometryToken>) {
    val y:Float get()=tokens.map { it.y }.average().toFloat()
    val top:Float get()=tokens.minOf { it.box.top }
    val bottom:Float get()=tokens.maxOf { it.box.bottom }
    val text:String get()=tokens.joinToString("") { it.text }
}
/** Exact adjacent structural labels only. Never infer a missing day or fix a wrong
 * numeral from its column order. Retain every original WORD as supporting evidence. */
internal fun exactWeekHeaderLine(line:GeometryLine,index:OcrGeometryIndex):GeometryLine {
    val output=mutableListOf<GeometryToken>();var start=0
    fun known(value:String)=isDayAnchor(value) || value in setOf("教学周","周次","周")
    while(start<line.tokens.size) {
        var joined="";var best=-1
        for(end in start until minOf(line.tokens.size,start+3)) {
            index.work.compare()
            if(end>start && line.tokens[end].box.left-line.tokens[end-1].box.right>index.glyphHeight*2f) break
            joined+=compactMatch(line.tokens[end].text)
            if(joined.length>3) break
            if(known(joined)) best=end
        }
        if(best>start) {
            val parts=line.tokens.subList(start,best+1)
            output+=parts.first().copy(span=parts.first().span.copy(rawText=parts.joinToString("") {it.span.rawText},
                position=SourcePosition(index.page.pageNumber,boundsOf(parts)!!)),headerParts=parts.toList())
            start=best+1
        } else {output+=line.tokens[start];start++}
    }
    return GeometryLine(output)
}
/** Sorted y buckets with bounded comparisons, no all-pairs clustering. Glyph height is
 * measured from this page, so translation/scaling and canonical HALF boxes are equivalent. */
internal class OcrGeometryIndex(val page:OcrLayoutPage,val variant:OcrVariantEvidence,val work:LayoutWork) {
    val tokens:List<GeometryToken>
    val glyphHeight:Float
    val lines:List<GeometryLine>
    private val rows=TreeMap<Float,List<GeometryToken>>()
    private val byTop:List<GeometryToken>
    private val prefixBottom:List<Float>
    init {
        tokens=variant.spans.mapIndexedNotNull { i,s -> work.check();if(s.level==OcrLevel.WORD && s.position!=null && s.rawText.isNotBlank()) GeometryToken(OcrSpanId(page.pageNumber,variant.id,i),s) else null }
        val heights=tokens.map { it.box.bottom-it.box.top }.sortedWith { a,b -> work.compare();a.compareTo(b) }
        glyphHeight=heights.getOrNull(heights.size/2) ?: 1f
        lines=groupLines(tokens)
        lines.forEach { rows[it.y]=it.tokens }
        byTop=tokens.sortedWith { a,b -> work.compare();compareValuesBy(a,b,{it.box.top},{it.box.left},{it.id.index}) }
        var bottom=Float.NEGATIVE_INFINITY
        prefixBottom=byTop.map { work.compare();bottom=maxOf(bottom,it.box.bottom);bottom }
    }
    fun groupLines(input:List<GeometryToken>):List<GeometryLine> {
        val sorted=input.sortedWith { a,b -> work.compare();compareValuesBy(a,b,{it.y},{it.box.left},{it.box.right},{it.text},{it.id.index}) }
        val groups=mutableListOf<MutableList<GeometryToken>>()
        var baseline=Float.NEGATIVE_INFINITY
        for(token in sorted) {
            work.compare()
            if(groups.isEmpty() || abs(token.y-baseline)>glyphHeight*.6f) { groups+=mutableListOf(token);baseline=token.y }
            else groups.last()+=token
        }
        return groups.map { group -> GeometryLine(group.sortedWith { a,b -> work.compare();compareValuesBy(a,b,{it.box.left},{it.box.top},{it.text},{it.id.index}) }) }
    }
    fun inBox(box:TextBox):List<GeometryToken> {
        val out=mutableListOf<GeometryToken>()
        for(row in rows.subMap(box.top,true,box.bottom,false).values) for(t in row) {
            work.compare();if(t.x>=box.left && t.x<box.right) out+=t
        }
        return out
    }
    /** A tiny SYMBOL may lie at either end of a wide WORD, far from its center.
     * Query containing vertical intervals, then real horizontal bounds. Prefix
     * maxima stop the scan once earlier words cannot reach this symbol. */
    fun nearbyWords(box:TextBox):List<GeometryToken> {
        var low=0;var high=byTop.size
        while(low<high) { work.compare();val mid=(low+high)/2;if(byTop[mid].box.top<=box.top) low=mid+1 else high=mid }
        val result=mutableListOf<GeometryToken>()
        var i=low-1
        while(i>=0) {
            work.compare();if(prefixBottom[i]<box.bottom) break
            val t=byTop[i];val b=t.box
            if(b.bottom>=box.bottom && b.left<=box.left && b.right>=box.right) result+=t
            i--
        }
        return result
    }
    fun checkCellText(input:List<GeometryToken>) {
        var units=0L
        for(t in input) { work.compare();units+=t.span.rawText.length;if(units>work.context.format.limits.maxFieldUnits) throw LayoutRejected("LIMIT_EXCEEDED") }
    }
    /** Lines support a boundary only together with already recognized independent
     * axes. Short/fake internal strokes cannot move a whole table column. */
    fun <T> measuredAxis(anchors:List<Pair<Float,T>>,left:Float,right:Float,top:Float,bottom:Float):GeometryAxis<T> {
        val tolerance=glyphHeight*.4f
        val xs=page.lines.filter { line -> work.compare();line.vertical && line.bounds.top<=top+tolerance && line.bounds.bottom>=bottom-tolerance && line.bounds.bottom-line.bounds.top>=glyphHeight*2f }
            .map { it.bounds.left+(it.bounds.right-it.bounds.left)/2f }.sortedWith { a,b -> work.compare();a.compareTo(b) }
        val distinct=mutableListOf<Float>()
        for(x in xs) { work.compare();if(distinct.isEmpty() || x-distinct.last()>tolerance) distinct+=x }
        val edges=mutableListOf(distinct.lastOrNull { it<anchors.first().first } ?: left)
        for((a,b) in anchors.zipWithNext()) {
            val candidates=distinct.filter { work.compare();it>a.first && it<b.first }
            edges+=candidates.singleOrNull() ?: (a.first+(b.first-a.first)/2f)
        }
        edges+=distinct.firstOrNull { it>anchors.last().first } ?: right
        return GeometryAxis(anchors,edges.first(),edges.last(),work,edges)
    }
}
/** Only reversible fullwidth ASCII and whitespace matching. Never OCR character correction. */
internal fun layoutMatch(raw:String):String = buildString(raw.length) {
    raw.forEach { c -> append(when { c=='\u3000' || c=='\u00a0' -> ' ';c in '\uff01'..'\uff5e' -> (c.code-0xfee0).toChar();else -> c }) }
}.replace(Regex("\\s+")," ").trim()
internal fun compactMatch(raw:String)=layoutMatch(raw).replace(" ","")
internal fun boundsOf(tokens:List<GeometryToken>):TextBox? = if(tokens.isEmpty()) null else TextBox(tokens.minOf { it.box.left },tokens.minOf { it.box.top },tokens.maxOf { it.box.right },tokens.maxOf { it.box.bottom })

/** A measured ordered axis uses variable-width Voronoi boundaries, never equal page columns. */
internal class GeometryAxis<T>(val anchors:List<Pair<Float,T>>, val left:Float,val right:Float,private val work:LayoutWork,measuredEdges:List<Float>?=null) {
    val edges:List<Float> = measuredEdges ?: (listOf(left)+anchors.zipWithNext { a,b -> a.first+(b.first-a.first)/2f }+listOf(right))
    init {
        if(edges.size!=anchors.size+1 || edges.any { !it.isFinite() } || edges.zipWithNext().any { it.first>=it.second } || anchors.indices.any { anchors[it].first !in edges[it]..<edges[it+1] }) throw LayoutRejected("INVALID_COORDINATES")
    }
    fun bucket(center:Float):Int? {
        var low=0;var high=anchors.size
        while(low<high) { work.compare();val mid=(low+high)/2;if(center<edges[mid+1]) high=mid else low=mid+1 }
        return low.takeIf { it<anchors.size && center>=left && center<right }
    }
}
