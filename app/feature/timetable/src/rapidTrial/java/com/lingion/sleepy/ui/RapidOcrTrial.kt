/* Test adapter for hzkitty/RapidOCR4j-Android 8e11959786c0fe609c68f4b133ee3210940a0df8.
 * Recognizer/preprocessing/model inference is reused from upstream, not reimplemented.
 * This source set is opt-in and is not included in production APKs.
 */
package com.lingion.sleepy.ui

import android.content.Context
import android.net.Uri
import com.lingion.sleepy.domain.imports.FrozenImportSource
import com.lingion.sleepy.importing.PositionedPage
import com.lingion.sleepy.importing.SourcePosition
import com.lingion.sleepy.importing.formats.FormatParseContext
import com.lingion.sleepy.importing.ocr.*
import io.github.hzkitty.RapidOCR
import io.github.hzkitty.entity.ParamConfig
import org.opencv.core.Point
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.runBlocking

internal object RapidOcrTrial {
    @JvmStatic fun recognize(context:Context,uri:Uri):FrozenImportSource = runBlocking {
        val resources=RasterResources(OcrWorkBudget())
        context.contentResolver.openInputStream(uri)!!.use {input->
            AndroidImportImageDecoder(context).withDecoded(input,resources) {decoded->
                val page=decoded.page
                resources.reserveStage(page.bitmap.width,page.bitmap.height)
                RapidOCR.create(context,rapidTrialConfig(context,page.bitmap.width,page.bitmap.height)).use {engine ->
                val lines=TableLineDetector().detect(page,resources)
                val mode=InstrumentationRegistry.getArguments().getString("probeEngine")
                val wordMode=mode=="rapid-words" || mode=="rapid-structure"
                val recognized=engine.run(page.bitmap,ParamConfig().apply {returnWordBox=wordMode})
                data class NativeWord(val text:String,val points:Array<Point>,val score:Float)
                val words=recognized.recRes.orEmpty().flatMap {row->
                    val parts=row.wordBoxResult
                    // Trial: use upstream character geometry for literal numeric axes,
                    // retaining upstream line boxes for course text. Splitting every
                    // Chinese title into characters creates one false course per section.
                    // No boxes/text are interpolated and no expected answers are supplied.
                    val split=mode=="rapid-words" || mode=="rapid-structure" && row.text.filterNot {it.isWhitespace()}.matches(Regex("[0-9]+"))
                    if(split && parts!=null && parts.wordBoxContentList.isNotEmpty() &&
                        parts.wordBoxContentList.size==parts.sortedWordBoxList.size && parts.confList.size==parts.sortedWordBoxList.size)
                        parts.wordBoxContentList.indices.map {i->NativeWord(parts.wordBoxContentList[i],parts.sortedWordBoxList[i],parts.confList[i])}
                    else listOf(NativeWord(row.text,row.dtBoxes,row.confidence))
                }
                val spans=words.mapNotNull {row->
                    val points=row.points
                    if(points.isEmpty() || row.text.isBlank()) return@mapNotNull null
                    val box=RasterBox(floor(points.minOf {it.x}).toInt(),floor(points.minOf {it.y}).toInt(),
                        ceil(points.maxOf {it.x}).toInt(),ceil(points.maxOf {it.y}).toInt())
                    val position=page.rasterToPage.mapBox(box,page.originalWidth,page.originalHeight) ?: return@mapNotNull null
                    OcrSpan(row.text,OcrLevel.WORD,(row.score*100).coerceIn(0f,100f),SourcePosition(1,position))
                }
                val positioned=PositionedPage(1,page.originalWidth,page.originalHeight,emptyList())
                val variant=OcrVariantEvidence(OcrVariantId.ORIGINAL,page.bitmap.width,page.bitmap.height,page.rasterToPage,
                    positioned,spans,emptySet(),null,OcrWordQuality.EMPTY,true)
                val layoutContext=OcrLayoutContext(FormatParseContext("rapid-private-trial",20),confirmedTermMonday=LocalDate.of(2026,9,7))
                val preliminary=OcrLayoutPage(1,page.originalWidth,page.originalHeight,listOf(variant),OcrVariantId.ORIGINAL,lines.lines)
                val matrixPlan=WeekMatrixAdapter().detect(OcrGeometryIndex(preliminary,variant,LayoutWork(layoutContext)))
                val matrix=matrixPlan!=null
                val glyphs=spans.mapNotNull {it.position?.bounds?.let {b->(b.bottom-b.top)*page.bitmap.height/page.originalHeight}}.sorted()
                val minimum=ceil((glyphs.getOrNull(glyphs.size/2) ?: 40f)*1.1f).toInt().coerceIn(12,40)
                // Reuse the same detector. A single matrix row may be only 25px
                // tall, below the default 40px run threshold. Ordinary grids keep
                // their already verified parameters; glyphs are never OCR answers.
                val measured=if(matrix && minimum<lines.parameters.minLength)
                    TableLineDetector(lines.parameters.copy(minLength=minimum)).detect(page,resources) else lines
                val observations=if(matrixPlan!=null && mode=="rapid-structure") RapidMatrixHeaderProbe.observe(context,page,resources,matrixPlan,measured) else emptyList()
                val added=matrixPlan?.let {MatrixHeaderEvidence.additions(it,observations)}.orEmpty()
                val finalVariant=variant.copy(spans=spans+added)
                FrozenImportSource.fromOcr(OcrTimetableParser().parse(OcrLayoutDocument(listOf(preliminary.copy(variants=listOf(finalVariant),lines=measured.lines))),layoutContext))
                }
            }
        }
    }
}
