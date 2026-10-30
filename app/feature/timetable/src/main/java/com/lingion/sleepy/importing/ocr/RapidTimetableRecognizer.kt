/* SPDX-License-Identifier: GPL-3.0-only
 * Promotes the W179 device-tested adapter of RapidOCR4j 8e11959786c0fe609c68f4b133ee3210940a0df8.
 * Native OCR, model math and character boxes remain upstream implementations.
 */
package com.lingion.sleepy.importing.ocr

import android.content.Context
import com.lingion.sleepy.importing.PositionedPage
import com.lingion.sleepy.importing.SourcePosition
import com.lingion.sleepy.importing.formats.FormatParseContext
import io.github.hzkitty.RapidOCR
import io.github.hzkitty.entity.ParamConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.core.Point
import kotlin.math.ceil
import kotlin.math.floor

/** One native owner at a time; cancellation never races bitmap/session disposal. No diagnostic files. */
internal object RapidTimetableRecognizer {
    private val nativeOwner=Mutex()
    suspend fun recognize(context:Context,page:OcrRasterPage,resources:RasterResources):OcrLayoutPage = withContext(Dispatchers.Default) {
        nativeOwner.withLock {
            val request=currentCoroutineContext()
            request.ensureActive()
            if(page.bitmap.isRecycled) throw RasterException(RasterFailure.INVALID_IMAGE)
            // Account for the bounded upstream detector envelope, including 32px alignment.
            resources.reserveStage(1952,1952)
            resources.acquireBitmap(1952,1952).use {
                RapidOCR.create(context,rapidOcrConfig(context,page.bitmap.width,page.bitmap.height){request.ensureActive()}).use {engine->
                    val lines=TableLineDetector().detect(page,resources)
                    val recognized=engine.run(page.bitmap,ParamConfig().apply {returnWordBox=true})
                    request.ensureActive()
                    data class NativeWord(val text:String,val points:Array<Point>,val score:Float)
                    fun spans(structure:Boolean):List<OcrSpan> {
                        val words=recognized.recRes.orEmpty().flatMap {row->
                            val parts=row.wordBoxResult
                            val split=structure && row.text.filterNot {it.isWhitespace()}.matches(Regex("[0-9]+"))
                            if(split && parts!=null && parts.wordBoxContentList.isNotEmpty() &&
                                parts.wordBoxContentList.size==parts.sortedWordBoxList.size && parts.confList.size==parts.sortedWordBoxList.size)
                                parts.wordBoxContentList.indices.map {i->NativeWord(parts.wordBoxContentList[i],parts.sortedWordBoxList[i],parts.confList[i])}
                            else listOf(NativeWord(row.text,row.dtBoxes,row.confidence))
                        }
                        val limits=OcrLimits()
                        if(words.size>limits.maxSpans || words.sumOf {it.text.length.toLong()}>limits.maxTextCharacters)
                            throw RasterException(RasterFailure.LIMIT_EXCEEDED)
                        return words.mapNotNull {row->
                            request.ensureActive()
                            if(row.points.isEmpty() || row.text.isBlank()) return@mapNotNull null
                            val box=RasterBox(floor(row.points.minOf {it.x}).toInt(),floor(row.points.minOf {it.y}).toInt(),
                                ceil(row.points.maxOf {it.x}).toInt(),ceil(row.points.maxOf {it.y}).toInt())
                            val position=page.rasterToPage.mapBox(box,page.originalWidth,page.originalHeight) ?: return@mapNotNull null
                            OcrSpan(row.text,OcrLevel.WORD,(row.score*100).coerceIn(0f,100f),SourcePosition(page.pageNumber,position))
                        }
                    }
                    val structural=spans(true)
                    val variant=OcrVariantEvidence(OcrVariantId.ORIGINAL,page.bitmap.width,page.bitmap.height,page.rasterToPage,
                        PositionedPage(page.pageNumber,page.originalWidth,page.originalHeight,emptyList()),structural,emptySet(),null,OcrWordQuality.EMPTY,true)
                    // Layout detection does not supply a semester/date. The loader parses with actual user settings.
                    val layoutContext=OcrLayoutContext(FormatParseContext("native-layout",1000))
                    val preliminary=OcrLayoutPage(page.pageNumber,page.originalWidth,page.originalHeight,listOf(variant),OcrVariantId.ORIGINAL,lines.lines)
                    val matrix=WeekMatrixAdapter().detect(OcrGeometryIndex(preliminary,variant,LayoutWork(layoutContext)))
                    val glyphs=structural.mapNotNull {it.position?.bounds?.let {b->(b.bottom-b.top)*page.bitmap.height/page.originalHeight}}.sorted()
                    val minimum=ceil((glyphs.getOrNull(glyphs.size/2) ?: 40f)*1.1f).toInt().coerceIn(12,40)
                    val measured=if(matrix!=null && minimum<lines.parameters.minLength)
                        TableLineDetector(lines.parameters.copy(minLength=minimum)).detect(page,resources) else lines
                    val observations=if(matrix!=null) RapidMatrixHeaderReader.observe(context,page,resources,matrix,measured) else emptyList()
                    request.ensureActive()
                    val selected=if(matrix!=null) structural+MatrixHeaderEvidence.additions(matrix,observations) else spans(false)
                    preliminary.copy(variants=listOf(variant.copy(spans=selected)),lines=measured.lines)
                }
            }
        }
    }
}
