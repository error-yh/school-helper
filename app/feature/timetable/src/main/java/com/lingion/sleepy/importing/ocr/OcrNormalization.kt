/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.PositionedPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class OcrVariantId { ORIGINAL, HALF_SCALE }

/** Diagnostic evidence is retained even on structural failure; usable=false forbids consuming
 * its page as a successful import. Every usable variant still needs source review.
 */
data class OcrVariantEvidence(
    val id: OcrVariantId,
    val rasterWidth: Int,
    val rasterHeight: Int,
    val rasterToPage: PageAffineTransform,
    val page: PositionedPage?,
    val spans: List<OcrSpan>,
    val issues: Set<OcrIssue>,
    val failure: OcrFailure?,
    val quality: OcrWordQuality,
    val usable: Boolean = false,
)

/** Native WORD statistics only. Confidence scores and this ranking are heuristics, not probabilities. */
data class OcrWordQuality private constructor(
    val wordCount: Int,
    val lowOrMissingWords: Int,
    val medianNativeWordHeight: Double?,
    val meanAvailableConfidence: Double?,
) {
    val requiresHalfScale: Boolean get() = (medianNativeWordHeight ?: 0.0) > 60.0 && lowOrMissingWords > 0
    internal fun isBetterThan(other: OcrWordQuality): Boolean {
        if (wordCount == 0) return false
        if (other.wordCount == 0) return true
        val thisFraction = lowOrMissingWords.toLong() * other.wordCount
        val otherFraction = other.lowOrMissingWords.toLong() * wordCount
        return thisFraction < otherFraction || (thisFraction == otherFraction &&
            (meanAvailableConfidence ?: Double.NEGATIVE_INFINITY) > (other.meanAvailableConfidence ?: Double.NEGATIVE_INFINITY))
    }
    companion object {
        internal val EMPTY = OcrWordQuality(0, 0, null, null)
        internal fun from(raw: List<NativeOcrSpan>, threshold: Float): OcrWordQuality {
            val words = raw.filter { it.level == OcrLevel.WORD && it.rawText.isNotBlank() }
            val confidences = words.map { it.confidence?.takeIf { score -> score.isFinite() && score in 0f..100f } }
            val heights = words.mapNotNull { it.box?.takeIf { box -> box.left < box.right && box.top < box.bottom }?.let { box -> box.bottom.toLong() - box.top } }.sorted()
            val median = if (heights.isEmpty()) null else if (heights.size % 2 == 1) heights[heights.size / 2].toDouble()
                else (heights[heights.size / 2 - 1] + heights[heights.size / 2]).toDouble() / 2.0
            val available = confidences.filterNotNull()
            return OcrWordQuality(words.size, confidences.count { it == null || it < threshold }, median,
                available.takeIf { it.isNotEmpty() }?.map { it.toDouble() }?.average())
        }
    }
}

/** One import owns one shared budget. Count OCR raster pixels once per native pass here;
 * callers must not also reserve this same pass. Decoding/line-cleanup memory limits are separate.
 * Reservations are conservative and never refunded after failure or cancellation.
 */
class OcrWorkBudget(val maxPixels: Long = 80_000_000L) {
    init { require(maxPixels in 1..80_000_000L) }
    private var consumed = 0L
    val consumedPixels: Long @Synchronized get() = consumed
    @Synchronized fun reserve(width: Int, height: Int, maxPassPixels: Long): Boolean {
        if (width <= 0 || height <= 0 || maxPassPixels !in 1..20_000_000L) return false
        val pixels = width.toLong() * height.toLong()
        if (pixels > maxPassPixels || pixels > maxPixels - consumed) return false
        consumed += pixels
        return true
    }
}

/** The engine owns at most one alternate for this page. Future layout/line-cleanup callers
 * either supply an already transformed raster with allowHalfScaleAlternate=false, or let
 * this engine choose the sole alternate. Never chain alternate-enabled calls for one page.
 */
data class OcrRecognitionOptions(val allowHalfScaleAlternate: Boolean = true, val workBudget: OcrWorkBudget = OcrWorkBudget())

internal data class OcrPassSpec(val id: OcrVariantId, val rasterWidth: Int, val rasterHeight: Int, val rasterToPage: PageAffineTransform, val limits: OcrLimits = OcrLimits())
internal data class OcrPassOutcome(val result: OcrPageResult, val quality: OcrWordQuality)

/** Pure orchestration seam: Android owns the raster operation; all native cleanup is awaited
 * by recognizeOnce before the coordinator releases its owned alternate, including cancellation.
 */
internal class OcrVariantCoordinator(private val limits: OcrLimits) {
    suspend fun <R : Any> recognize(
        pageNumber: Int, pageWidth: Float, pageHeight: Float, initial: OcrPassSpec, callerRaster: R, options: OcrRecognitionOptions,
        recognizeOnce: suspend (R, OcrPassSpec) -> OcrPassOutcome,
        createHalf: (R, Int, Int) -> R,
        releaseHalf: (R) -> Unit,
    ): OcrPageResult {
        require(initial.id == OcrVariantId.ORIGINAL)
        val evidence = mutableListOf<OcrVariantEvidence>()
        var work = 0L
        var activeSpec = initial.copy(limits = limits)
        var ownedHalf: R? = null
        fun failed(code: OcrFailure): OcrPageResult {
            if (evidence.none { it.id == activeSpec.id }) evidence += toEvidence(activeSpec, OcrPassOutcome(OcrPageResult.failed(pageNumber, code), OcrWordQuality.EMPTY))
            return OcrPageResult.failed(pageNumber, code).copy(variants = evidence.map { it.copy(usable = false) }, workPixels = work)
        }
        fun reserve(spec: OcrPassSpec): Boolean {
            if (!options.workBudget.reserve(spec.rasterWidth, spec.rasterHeight, limits.maxRasterPixels)) return false
            work += spec.rasterWidth.toLong() * spec.rasterHeight
            return true
        }
        try {
            currentCoroutineContext().ensureActive()
            require(pageNumber > 0 && validPageSize(pageWidth, pageHeight))
            if (initial.rasterToPage.mapBox(RasterBox(0, 0, initial.rasterWidth, initial.rasterHeight), pageWidth, pageHeight) == null) return failed(OcrFailure.INVALID_RASTER)
            if (!reserve(activeSpec)) return failed(OcrFailure.RESOURCE_LIMIT)
            val original = recognizeOnce(callerRaster, activeSpec)
            currentCoroutineContext().ensureActive()
            evidence += toEvidence(activeSpec, original)
            original.result.failure?.let { return failed(it) }
            if (options.allowHalfScaleAlternate && original.quality.requiresHalfScale) {
                val width = maxOf(1, initial.rasterWidth / 2)
                val height = maxOf(1, initial.rasterHeight / 2)
                activeSpec = OcrPassSpec(OcrVariantId.HALF_SCALE, width, height,
                    initial.rasterToPage.forResizedRaster(initial.rasterWidth, initial.rasterHeight, width, height), limits)
                val remainingText = limits.maxTextCharacters - original.result.spans.sumOf { it.rawText.length }
                val remainingSpans = limits.maxSpans - original.result.spans.size
                if (remainingText <= 0 || remainingSpans <= 0) return failed(OcrFailure.RESOURCE_LIMIT)
                activeSpec = activeSpec.copy(limits = limits.copy(maxTextCharacters = remainingText, maxSpans = remainingSpans))
                currentCoroutineContext().ensureActive()
                if (!reserve(activeSpec)) return failed(OcrFailure.RESOURCE_LIMIT)
                // Synchronous allocation/assignment: cancellation cannot discard an allocated
                // raster at a dispatcher return boundary before this owner receives it.
                ownedHalf = createHalf(callerRaster, width, height)
                currentCoroutineContext().ensureActive()
                val alternate = recognizeOnce(ownedHalf, activeSpec)
                currentCoroutineContext().ensureActive()
                evidence += toEvidence(activeSpec, alternate)
                alternate.result.failure?.let { return failed(it) }
            }
            val recommended = evidence.reduce { best, candidate -> if (candidate.quality.isBetterThan(best.quality)) candidate else best }
            val issues = evidence.flatMap { it.issues }.toMutableSet()
            if (evidence.size > 1) {
                issues += OcrIssue.OCR_ALTERNATE_REVIEW_REQUIRED
                issues += OcrIssue.OCR_REVIEW_REQUIRED
                val originalWords = evidence.first().spans.filter { it.level == OcrLevel.WORD }.map { it.rawText }
                val alternateWords = evidence.last().spans.filter { it.level == OcrLevel.WORD }.map { it.rawText }
                if (originalWords != alternateWords) issues += OcrIssue.OCR_VARIANT_DISAGREEMENT
            }
            return OcrPageResult(pageNumber, recommended.page, recommended.spans, issues,
                variants = evidence.map { it.copy(usable = true) }, recommendedVariantId = recommended.id, workPixels = work)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: OcrResourceLimitException) { currentCoroutineContext().ensureActive(); return failed(OcrFailure.RESOURCE_LIMIT)
        } catch (_: OcrModelException) { currentCoroutineContext().ensureActive(); return failed(OcrFailure.MODEL_UNAVAILABLE)
        } catch (_: LinkageError) { currentCoroutineContext().ensureActive(); return failed(OcrFailure.NATIVE_UNAVAILABLE)
        } catch (_: RuntimeException) { currentCoroutineContext().ensureActive(); return failed(OcrFailure.NATIVE_FAILED)
        } finally { ownedHalf?.let(releaseHalf) }
    }
    private fun toEvidence(spec: OcrPassSpec, pass: OcrPassOutcome) = OcrVariantEvidence(spec.id, spec.rasterWidth, spec.rasterHeight, spec.rasterToPage,
        pass.result.page, pass.result.spans, pass.result.issues, pass.result.failure, pass.quality)
}
