/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import android.graphics.Bitmap
import com.lingion.sleepy.importing.PositionedPage
import com.lingion.sleepy.importing.PositionedText
import com.lingion.sleepy.importing.SourcePosition
import com.lingion.sleepy.importing.TextBox
import kotlin.math.max
import kotlin.math.min

/** Inverse raster transform: x'=a*x+c*y+e, y'=b*x+d*y+f.
 * Canonical coordinates use the top-left of the rotation-adjusted original page.
 * PDF units are original points. Standalone images use virtual 72 dpi: oriented
 * original pixels equal points, without implying a physical print size.
 */
data class PageAffineTransform(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
    init {
        require(listOf(a, b, c, d, e, f).all { it.isFinite() })
        val determinant = a * d - b * c
        require(determinant.isFinite() && determinant != 0.0)
    }
    /** Map every corner, retain only valid positive rectangles; clip rounding drift <=0.5 point. */
    fun mapBox(box: RasterBox, pageWidth: Float, pageHeight: Float): TextBox? {
        if (!validPageSize(pageWidth, pageHeight) || box.left >= box.right || box.top >= box.bottom) return null
        val corners = listOf(box.left to box.top, box.right to box.top, box.left to box.bottom, box.right to box.bottom)
        val xs = corners.map { (x, y) -> a * x + c * y + e }
        val ys = corners.map { (x, y) -> b * x + d * y + f }
        if ((xs + ys).any { !it.isFinite() }) return null
        val left = xs.min(); val right = xs.max(); val top = ys.min(); val bottom = ys.max()
        if (left < -0.5 || top < -0.5 || right > pageWidth + 0.5 || bottom > pageHeight + 0.5) return null
        val clipped = TextBox(max(0.0, left).toFloat(), max(0.0, top).toFloat(), min(pageWidth.toDouble(), right).toFloat(), min(pageHeight.toDouble(), bottom).toFloat())
        return clipped.takeIf { it.left < it.right && it.top < it.bottom }
    }
    /** Compose the exact new-raster -> old-raster resize before this inverse transform. */
    fun forResizedRaster(oldWidth: Int, oldHeight: Int, newWidth: Int, newHeight: Int): PageAffineTransform {
        require(oldWidth > 0 && oldHeight > 0 && newWidth > 0 && newHeight > 0)
        val xScale = oldWidth.toDouble() / newWidth
        val yScale = oldHeight.toDouble() / newHeight
        return PageAffineTransform(a * xScale, b * xScale, c * yScale, d * yScale, e, f)
    }
    companion object { val IDENTITY = PageAffineTransform(1.0, 0.0, 0.0, 1.0, 0.0, 0.0) }
}

data class RasterBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Bitmap remains owned by caller, who must keep it alive and unmodified until recognize returns. */
data class OcrRasterPage(val pageNumber: Int, val bitmap: Bitmap, val originalWidth: Float, val originalHeight: Float, val rasterToPage: PageAffineTransform) {
    init { require(pageNumber > 0); require(validPageSize(originalWidth, originalHeight)) }
}

fun interface LocalOcrEngine {
    suspend fun recognize(page: OcrRasterPage): OcrPageResult
    /** Preserve existing SAM consumers; implementations supporting budgets/alternates override this overload. */
    suspend fun recognize(page: OcrRasterPage, options: OcrRecognitionOptions): OcrPageResult = recognize(page)
}
enum class OcrLevel { WORD, SYMBOL }
enum class OcrIssue { OCR_SOURCE_REVIEW_REQUIRED, OCR_REVIEW_REQUIRED, OCR_UNPOSITIONED_TEXT, OCR_BLANK_PAGE, OCR_ALTERNATE_REVIEW_REQUIRED, OCR_VARIANT_DISAGREEMENT }
/** Stable public codes; no source text, storage paths or native exception messages. */
enum class OcrFailure { RESOURCE_LIMIT, INVALID_RASTER, MODEL_UNAVAILABLE, NATIVE_UNAVAILABLE, NATIVE_FAILED }

/** Engine-specific heuristic score, never a probability; null means unavailable. */
data class OcrSpan(val rawText: String, val level: OcrLevel, val confidence: Float?, val position: SourcePosition?)

/** A structural failure has no usable partial page. A successful blank page retains its number. */
data class OcrPageResult(
    val pageNumber: Int,
    val page: PositionedPage?,
    val spans: List<OcrSpan>,
    val issues: Set<OcrIssue>,
    val failure: OcrFailure? = null,
    val variants: List<OcrVariantEvidence> = emptyList(),
    val recommendedVariantId: OcrVariantId? = null,
    /** Pixels reserved by this call; the injected budget may also contain other pages. */
    val workPixels: Long = 0,
) {
    companion object {
        internal fun failed(pageNumber: Int, failure: OcrFailure) = OcrPageResult(pageNumber, null, emptyList(), setOf(OcrIssue.OCR_SOURCE_REVIEW_REQUIRED), failure)
    }
}

data class OcrLimits(val maxTextCharacters: Int = 500_000, val maxSpans: Int = 100_000, val reviewConfidence: Float = 70f, val maxRasterPixels: Long = 20_000_000L) {
    init {
        require(maxTextCharacters in 1..500_000 && maxSpans in 1..100_000)
        require(reviewConfidence.isFinite() && reviewConfidence in 0f..100f)
        require(maxRasterPixels in 1..20_000_000L)
    }
    fun acceptsRaster(width: Int, height: Int): Boolean = width > 0 && height > 0 && width.toLong() * height.toLong() <= maxRasterPixels
}

internal fun validPageSize(width: Float, height: Float) = width.isFinite() && height.isFinite() && width > 0f && height > 0f
internal data class NativeOcrSpan(val rawText: String, val level: OcrLevel, val confidence: Float?, val box: RasterBox?)
internal class OcrResourceLimitException : RuntimeException("OCR_RESOURCE_LIMIT")
internal class OcrResultAssembler(private val limits: OcrLimits) {
    fun assemble(pageNumber: Int, width: Float, height: Float, transform: PageAffineTransform, raw: List<NativeOcrSpan>, checkCancellation: () -> Unit = {}): OcrPageResult {
        require(pageNumber > 0 && validPageSize(width, height))
        if (raw.size > limits.maxSpans || raw.sumOf { it.rawText.length.toLong() } > limits.maxTextCharacters) return OcrPageResult.failed(pageNumber, OcrFailure.RESOURCE_LIMIT)
        val issues = linkedSetOf(OcrIssue.OCR_SOURCE_REVIEW_REQUIRED)
        val spans = raw.map { native ->
            checkCancellation()
            val confidence = native.confidence?.takeIf { it.isFinite() && it in 0f..100f }
            if (confidence == null || confidence < limits.reviewConfidence) issues += OcrIssue.OCR_REVIEW_REQUIRED
            val position = native.box?.let { transform.mapBox(it, width, height) }?.let { SourcePosition(pageNumber, it) }
            if (position == null) issues += OcrIssue.OCR_UNPOSITIONED_TEXT
            OcrSpan(native.rawText, native.level, confidence, position)
        }
        val words = spans.filter { it.level == OcrLevel.WORD && it.position != null }.map { PositionedText(it.rawText, it.position!!, it.rawText) }
        if (spans.none { it.rawText.isNotBlank() }) issues += OcrIssue.OCR_BLANK_PAGE
        return OcrPageResult(pageNumber, PositionedPage(pageNumber, width, height, words), spans, issues)
    }
}
