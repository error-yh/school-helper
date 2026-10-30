/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

/** Supplemental native observations, never inferred period values or boxes. */
internal object MatrixHeaderEvidence {
    fun additions(plan:MatrixPlan,observed:List<OcrSpan>):List<OcrSpan> {
        val index=plan.index
        val days=index.measuredAxis(plan.days.map {it.x to layoutDay(it.text)!!},plan.left,index.page.width,plan.header.bottom,plan.rows.last().token.box.bottom)
        val top=plan.periods.minOf {it.box.top};val bottom=plan.periods.maxOf {it.box.bottom}
        val accepted=mutableListOf<OcrSpan>()
        for(day in days.anchors.indices) {
            val anchors=plan.periods.filter {index.work.compare();days.bucket(it.x)==day}.sortedBy {it.x}
            val gaps=anchors.zipWithNext().map {it.second.x-it.first.x}.filter {it>index.glyphHeight*.4f}.sorted()
            val typical=gaps.getOrNull((gaps.size-1)/2) ?: continue
            val missing=anchors.zipWithNext().filter {index.work.compare();it.second.x-it.first.x>typical*1.8f}
            for(span in observed.sortedByDescending {it.confidence ?: -1f}) {
                index.work.compare()
                val box=span.position?.takeIf {it.page==index.page.pageNumber}?.bounds ?: continue
                val confidence=span.confidence ?: continue
                if(span.level!=OcrLevel.WORD || !confidence.isFinite() || confidence !in OcrLimits().reviewConfidence..100f || box.top<top-index.glyphHeight*.2f || box.bottom>bottom+index.glyphHeight*.2f) continue
                val value=compactMatch(span.rawText).toIntOrNull()?.takeIf {it in 1..1000} ?: continue
                val x=(box.left+box.right)/2f
                if(days.bucket(x)!=day) continue
                val supported=missing.any {(a,b)->index.work.compare();
                    val before=sectionAxis(a.text)?.singleOrNull();val after=sectionAxis(b.text)?.singleOrNull()
                    before!=null && after!=null && value>before && value<after && x>a.x+typical*.35f && x<b.x-typical*.35f}
                if(!supported || accepted.any {other->index.work.compare();kotlin.math.abs((other.position!!.bounds.left+other.position!!.bounds.right)/2f-x)<typical*.35f}) continue
                if(accepted.size==32) throw LayoutRejected("LIMIT_EXCEEDED")
                accepted+=span
            }
        }
        return accepted.sortedWith(compareBy({it.position!!.bounds.top},{it.position!!.bounds.left}))
    }
}
