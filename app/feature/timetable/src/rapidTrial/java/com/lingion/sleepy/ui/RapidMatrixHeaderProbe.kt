/* SPDX-License-Identifier: GPL-3.0-only
 * Read-only experiment: real source pixels -> the same pinned upstream OCR.
 * Does not modify the final parser input, supply expected digits or invent boxes.
 */
package com.lingion.sleepy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.lingion.sleepy.importing.SourcePosition
import com.lingion.sleepy.importing.ocr.*
import io.github.hzkitty.RapidOCR
import io.github.hzkitty.entity.ParamConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor

internal object RapidMatrixHeaderProbe {
    suspend fun observe(context:Context,page:OcrRasterPage,resources:RasterResources,plan:MatrixPlan,lines:TableLineDetection):List<OcrSpan> {
        // This diagnostic is limited to the observed identity frame. No approximate
        // inverse transform is silently used for rotated/scaled input.
        if(page.rasterToPage!=PageAffineTransform.IDENTITY) return emptyList()
        val index=plan.index
        val days=index.measuredAxis(plan.days.map {it.x to layoutDay(it.text)!!},plan.left,index.page.width,
            plan.header.bottom,plan.rows.last().token.box.bottom)
        val counts=days.anchors.indices.map {day->plan.periods.filter {days.bucket(it.x)==day}.flatMap {sectionAxis(it.text).orEmpty()}.distinct().size}
        val results=JSONArray()
        val observations=mutableListOf<OcrSpan>()
        for(day in days.anchors.indices.filter {counts[it]<(counts.maxOrNull() ?: 0)}.take(2)) {
            val anchors=plan.periods.filter {days.bucket(it.x)==day}.sortedBy {it.x}
            val gaps=anchors.zipWithNext().map {it.second.x-it.first.x}.filter {it>index.glyphHeight*.4f}.sorted()
            val typical=gaps.getOrNull((gaps.size-1)/2) ?: continue
            if(anchors.zipWithNext().none {it.second.x-it.first.x>typical*1.8f}) continue
            val left=floor(days.edges[day]).toInt().coerceIn(0,page.bitmap.width-1)
            val right=ceil(days.edges[day+1]).toInt().coerceIn(left+1,page.bitmap.width)
            val top=(floor(plan.periods.minOf {it.box.top}).toInt()-2).coerceAtLeast(0)
            val bottom=(ceil(plan.periods.maxOf {it.box.bottom}).toInt()+2).coerceAtMost(page.bitmap.height)
            val width=right-left;val height=bottom-top
            resources.reserveStage(width,height)
            resources.acquireBitmap(width,height).use {
                val crop=Bitmap.createBitmap(page.bitmap,left,top,width,height)
                try {
                    RapidOCR.create(context,rapidTrialConfig(context,width,height)).use {engine->
                        for(clean in listOf(true)) {
                            // Charge the bounded upstream detector envelope, including
                            // its alignment allowance, not just the tiny source crop.
                            resources.reserveStage(1952,1952)
                            resources.acquireBitmap(width,height).use {
                                val input=crop.copy(Bitmap.Config.ARGB_8888,true)
                                try {
                                    if(clean) {
                                        val canvas=Canvas(input);val white=Paint().apply {color=Color.WHITE}
                                        lines.lines.filter {it.thickness<=3}.forEach {line->
                                            val b=line.bounds
                                            if(b.right>left && b.left<right && b.bottom>top && b.top<bottom)
                                                canvas.drawRect(b.left-left,b.top-top,b.right-left,b.bottom-top,white)
                                        }
                                    }
                                    val result=engine.run(input,ParamConfig().apply {returnWordBox=true})
                                    for(row in result.recRes.orEmpty()) {
                                        val parts=row.wordBoxResult ?: continue
                                        if(parts.wordBoxContentList.size!=parts.sortedWordBoxList.size || parts.confList.size!=parts.sortedWordBoxList.size) continue
                                        for(i in parts.wordBoxContentList.indices) {
                                            val points=parts.sortedWordBoxList[i]
                                            if(points.isEmpty()) continue
                                            val raster=RasterBox(floor(points.minOf {it.x}).toInt()+left,floor(points.minOf {it.y}).toInt()+top,
                                                ceil(points.maxOf {it.x}).toInt()+left,ceil(points.maxOf {it.y}).toInt()+top)
                                            val mapped=page.rasterToPage.mapBox(raster,page.originalWidth,page.originalHeight) ?: continue
                                            observations+=OcrSpan(parts.wordBoxContentList[i],OcrLevel.WORD,(parts.confList[i]*100).coerceIn(0f,100f),SourcePosition(page.pageNumber,mapped))
                                        }
                                    }
                                    results.put(JSONObject().put("weekday",days.anchors[day].second)
                                        .put("method",if(clean) "same-detector-line-cleanup" else "source-crop")
                                        .put("sourceBox",JSONArray(listOf(left,top,right,bottom)))
                                        .put("rows",JSONArray(result.recRes.orEmpty().map {r->JSONObject()
                                            .put("text",r.text).put("confidence",r.confidence)
                                            .put("box",JSONArray(r.dtBoxes.map {p->JSONArray(listOf(p.x+left,p.y+top))}))})))
                                } finally {input.recycle()}
                            }
                        }
                    }
                } finally {crop.recycle()}
            }
        }
        val folder=File(context.filesDir,"stage3-private-probe").apply {mkdirs()}
        File(folder,"matrix-header-probe.json").writeText(JSONObject().put("nativeObservationsOnly",true).put("observedSpans",observations.size).put("results",results).toString())
        return observations
    }
}
