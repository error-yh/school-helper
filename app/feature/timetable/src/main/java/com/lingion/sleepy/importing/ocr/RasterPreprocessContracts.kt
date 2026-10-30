/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

/** Stable failures deliberately carry no provider names, file paths or library messages. */
enum class RasterFailure { LIMIT_EXCEEDED, UNSUPPORTED_FORMAT, INVALID_IMAGE, INVALID_ORIENTATION, PDF_REJECTED, PDF_GEOMETRY_UNSUPPORTED, RASTER_UNREADABLE }
class RasterException(val code: RasterFailure) : java.io.IOException(code.name)

data class RasterLimits(val maxBytes: Int = 12_000_000, val maxPixels: Long = 20_000_000,
    val maxAxis: Int = 20_000, val maxLiveBitmapBytes: Long = 160_000_000) {
    init { require(maxBytes in 1..12_000_000 && maxPixels in 1..20_000_000 && maxAxis in 1..20_000 && maxLiveBitmapBytes in 4..160_000_000) }
}

/** Import-scoped shared stage work, plus a separate bound on simultaneously owned ARGB bytes.
 * Each decode, orientation/white composite, render and directional scan reserves its own work.
 * OCR itself reserves only its native passes in the same [workBudget]. No work is refunded.
 */
class RasterResources(val workBudget: OcrWorkBudget, val limits: RasterLimits = RasterLimits()) {
    /** Downstream OCR must use these options to keep native passes in this import's budget. */
    fun recognitionOptions(allowHalfScaleAlternate: Boolean = true): OcrRecognitionOptions = OcrRecognitionOptions(allowHalfScaleAlternate,workBudget)
    private var live = 0L
    val liveBitmapBytes: Long @Synchronized get() = live
    internal fun checkDimensions(width: Int, height: Int) {
        if (width !in 1..limits.maxAxis || height !in 1..limits.maxAxis || width.toLong() * height > limits.maxPixels)
            throw RasterException(RasterFailure.LIMIT_EXCEEDED)
    }
    fun reserveStage(width: Int, height: Int) {
        checkDimensions(width, height)
        if (!workBudget.reserve(width, height, limits.maxPixels)) throw RasterException(RasterFailure.LIMIT_EXCEEDED)
    }
    @Synchronized fun acquireBitmap(width: Int, height: Int): AutoCloseable {
        checkDimensions(width, height)
        val bytes = width.toLong() * height * 4L
        if (bytes > limits.maxLiveBitmapBytes - live) throw RasterException(RasterFailure.LIMIT_EXCEEDED)
        live += bytes
        return object : AutoCloseable {
            private var closed = false
            override fun close() = synchronized(this@RasterResources) { if (!closed) { live -= bytes; closed = true } }
        }
    }
}

internal data class ImageOrientation(val orientation: Int, val sourceWidth: Int, val sourceHeight: Int, val width: Int, val height: Int) {
    /** Pixel-edge coordinates translate by the full dimension. */
    fun edgeTransform(): PageAffineTransform = when (orientation) {
        1 -> PageAffineTransform.IDENTITY
        2 -> PageAffineTransform(-1.0,0.0,0.0,1.0,sourceWidth.toDouble(),0.0)
        3 -> PageAffineTransform(-1.0,0.0,0.0,-1.0,sourceWidth.toDouble(),sourceHeight.toDouble())
        4 -> PageAffineTransform(1.0,0.0,0.0,-1.0,0.0,sourceHeight.toDouble())
        5 -> PageAffineTransform(0.0,1.0,1.0,0.0,0.0,0.0)
        6 -> PageAffineTransform(0.0,1.0,-1.0,0.0,sourceHeight.toDouble(),0.0)
        7 -> PageAffineTransform(0.0,-1.0,-1.0,0.0,sourceHeight.toDouble(),sourceWidth.toDouble())
        else -> PageAffineTransform(0.0,-1.0,1.0,0.0,0.0,sourceWidth.toDouble())
    }
    companion object {
        fun of(value: Int, width: Int, height: Int): ImageOrientation {
            if (value !in 1..8 || width <= 0 || height <= 0) throw RasterException(RasterFailure.INVALID_ORIENTATION)
            return ImageOrientation(value, width, height, if (value >= 5) height else width, if (value >= 5) width else height)
        }
    }
}

internal data class PdfRasterGeometry(val pageWidth: Float, val pageHeight: Float, val rasterWidth: Int, val rasterHeight: Int, val rasterToPage: PageAffineTransform) {
    // API 26+ truncates native floating point page dimensions to integer points. Never
    // use these rounded values to derive the inverse frame or content scale.
    fun matchesRendererSize(width: Int, height: Int): Boolean = width == pageWidth.toInt() && height == pageHeight.toInt()
    companion object {
        fun create(left: Float, bottom: Float, right: Float, top: Float, rotation: Int, scale: Int): PdfRasterGeometry {
            if (listOf(left,bottom,right,top).any { !it.isFinite() } || right <= left || top <= bottom || rotation !in setOf(0,90,180,270) || scale !in 1..4)
                throw RasterException(RasterFailure.PDF_GEOMETRY_UNSUPPORTED)
            val width = if (rotation % 180 == 0) right - left else top - bottom
            val height = if (rotation % 180 == 0) top - bottom else right - left
            val rw = kotlin.math.ceil(width.toDouble() * scale)
            val rh = kotlin.math.ceil(height.toDouble() * scale)
            if (!validPageSize(width,height) || width < 1f || height < 1f || rw > 20_000 || rh > 20_000 || rw * rh > 20_000_000)
                throw RasterException(RasterFailure.LIMIT_EXCEEDED)
            return PdfRasterGeometry(width,height,rw.toInt(),rh.toInt(),PageAffineTransform(width.toDouble()/rw,0.0,0.0,height.toDouble()/rh,0.0,0.0))
        }
    }
}
