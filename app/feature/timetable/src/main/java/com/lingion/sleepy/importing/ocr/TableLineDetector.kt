/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.TextBox
import kotlinx.coroutines.ensureActive

data class LineDetectionParameters(val darkThreshold: Int = 80, val minLength: Int = 40, val maxGap: Int = 2, val maxThickness: Int = 8, val maxRuns: Int = 100_000) {
    init { require(darkThreshold in 0..255 && minLength in 1..20_000 && maxGap in 0..16 && maxThickness in 1..32 && maxRuns in 1..100_000) }
}
data class RasterMaskRun(val vertical: Boolean, val fixed: Int, val start: Int, val endExclusive: Int)
data class TableLineEvidence(val vertical: Boolean, val bounds: TextBox, val thickness: Int)
/** Evidence only: neither recognized cells nor modified pixels. */
data class TableLineDetection(val runs: List<RasterMaskRun>, val lines: List<TableLineEvidence>, val horizontalProjection: List<Int>, val verticalProjection: List<Int>, val rasterToPage: PageAffineTransform, val parameters: LineDetectionParameters)

class TableLineDetector(private val parameters: LineDetectionParameters = LineDetectionParameters()) {
    suspend fun detect(page: OcrRasterPage, resources: RasterResources): TableLineDetection = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val context = kotlinx.coroutines.currentCoroutineContext()
        val bitmap = page.bitmap
        detect(bitmap.width,bitmap.height,page.rasterToPage,page.originalWidth,page.originalHeight,resources,{ vertical,fixed,buffer ->
            if (vertical) bitmap.getPixels(buffer,0,1,fixed,0,1,bitmap.height)
            else bitmap.getPixels(buffer,0,bitmap.width,0,fixed,bitmap.width,1)
        }, { context.ensureActiveRaster() })
    }
    internal fun detect(width: Int, height: Int, transform: PageAffineTransform, pageWidth: Float, pageHeight: Float,
        resources: RasterResources, read: (Boolean, Int, IntArray) -> Unit, checkCancellation: () -> Unit = {}): TableLineDetection {
        checkCancellation()
        if (transform.mapBox(RasterBox(0,0,width,height),pageWidth,pageHeight) == null) throw RasterException(RasterFailure.INVALID_IMAGE)
        resources.reserveStage(width,height); resources.reserveStage(width,height)
        val buffer = IntArray(maxOf(width,height))
        val runs = mutableListOf<RasterMaskRun>()
        val projections = Array(2) { IntArray(if (it == 0) height else width) }
        for (axis in 0..1) {
            val vertical = axis == 1; val count = if (vertical) width else height; val length = if (vertical) height else width
            for (fixed in 0 until count) {
                checkCancellation(); read(vertical,fixed,buffer)
                var start = -1; var lastDark = -1; var darkCount = 0
                fun finish() {
                    if (start >= 0 && lastDark - start + 1 >= parameters.minLength) {
                        if (runs.size == parameters.maxRuns) throw RasterException(RasterFailure.LIMIT_EXCEEDED)
                        runs += RasterMaskRun(vertical,fixed,start,lastDark+1)
                    }
                    start = -1; lastDark = -1
                }
                for (i in 0 until length) {
                    if (i % 1024 == 0) checkCancellation()
                    val argb = buffer[i]; val alpha = argb ushr 24
                    val luminance = (77 * (argb ushr 16 and 255) + 150 * (argb ushr 8 and 255) + 29 * (argb and 255) + 128) ushr 8
                    val whiteComposite = (luminance * alpha + 255 * (255-alpha) + 127) / 255
                    if (whiteComposite <= parameters.darkThreshold) { darkCount++; if (start < 0) start = i; lastDark = i }
                    else if (start >= 0 && i - lastDark > parameters.maxGap) finish()
                }
                finish(); projections[axis][fixed] = darkCount
            }
        }
        // Identical neighboring long runs form a thickness group. Sorting is bounded by
        // maxRuns; thick filled regions remain mask evidence but are not proposed as lines.
        val sorted = runs.sortedWith(compareBy<RasterMaskRun>({it.vertical},{it.start},{it.endExclusive},{it.fixed}))
        val lines = mutableListOf<TableLineEvidence>()
        var index = 0
        while (index < sorted.size) {
            checkCancellation()
            val first = sorted[index]; var last = first; index++
            while (index < sorted.size) {
                val next = sorted[index]
                if (next.vertical != first.vertical || next.start != first.start || next.endExclusive != first.endExclusive || next.fixed != last.fixed+1) break
                last = next; index++
            }
            val thickness = last.fixed-first.fixed+1
            if (thickness <= parameters.maxThickness) {
                val box = if (first.vertical) RasterBox(first.fixed,first.start,last.fixed+1,first.endExclusive) else RasterBox(first.start,first.fixed,first.endExclusive,last.fixed+1)
                val mapped = transform.mapBox(box,pageWidth,pageHeight) ?: throw RasterException(RasterFailure.INVALID_IMAGE)
                lines += TableLineEvidence(first.vertical,mapped,thickness)
            }
        }
        return TableLineDetection(runs.toList(),lines.toList(),projections[0].toList(),projections[1].toList(),transform,parameters)
    }
}

internal fun kotlin.coroutines.CoroutineContext.ensureActiveRaster() { ensureActive() }
