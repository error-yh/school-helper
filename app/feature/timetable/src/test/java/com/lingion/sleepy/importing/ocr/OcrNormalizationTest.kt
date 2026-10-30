/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.TextBox
import kotlinx.coroutines.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class OcrNormalizationTest {
    private fun raw(text: String = "invented", height: Int = 80, confidence: Float? = 20f, level: OcrLevel = OcrLevel.WORD) = NativeOcrSpan(text, level, confidence, RasterBox(0, 0, 20, height))
    private fun geometry(width: Int = 101, height: Int = 201) = OcrPassSpec(OcrVariantId.ORIGINAL, width, height, PageAffineTransform.IDENTITY)
    private fun once(spec: OcrPassSpec, spans: List<NativeOcrSpan> = listOf(raw())) = OcrPassOutcome(
        OcrResultAssembler(OcrLimits()).assemble(3, 101f, 201f, spec.rasterToPage, spans), OcrWordQuality.from(spans, 70f))

    @Test fun retriesOnlyLargeNativeWordsWithLowOrMissingScoresRegardlessOfText() {
        assertTrue(OcrWordQuality.from(listOf(raw("alpha")), 70f).requiresHalfScale)
        assertTrue(OcrWordQuality.from(listOf(raw("字", confidence = null)), 70f).requiresHalfScale)
        assertFalse(OcrWordQuality.from(listOf(raw(height = 60)), 70f).requiresHalfScale)
        assertFalse(OcrWordQuality.from(listOf(raw(confidence = 70f)), 70f).requiresHalfScale)
        assertFalse(OcrWordQuality.from(listOf(raw(level = OcrLevel.SYMBOL)), 70f).requiresHalfScale)
        assertFalse(OcrWordQuality.from(listOf(raw(text = " ")), 70f).requiresHalfScale)
        assertFalse(OcrWordQuality.from(listOf(raw(height = 20), raw(height = 80)), 70f).requiresHalfScale)
    }
    @Test fun resizeCompositionHandlesOddDimensionsAndAllAffineCoefficients() {
        val t = PageAffineTransform(0.0, 1.0, -1.0, 0.0, 201.0, 0.0)
        val resized = t.forResizedRaster(101, 201, 50, 100)
        val mapped = resized.mapBox(RasterBox(0, 0, 50, 100), 201f, 101f)!!
        assertEquals(0f, mapped.left, 1e-4f); assertEquals(0f, mapped.top, 1e-4f)
        assertEquals(201f, mapped.right, 1e-4f); assertEquals(101f, mapped.bottom, 1e-4f)
        val roundTrip = resized.forResizedRaster(50, 100, 101, 201)
        assertEquals(t.a, roundTrip.a, 1e-12); assertEquals(t.b, roundTrip.b, 1e-12)
        assertEquals(t.c, roundTrip.c, 1e-12); assertEquals(t.d, roundTrip.d, 1e-12)
        assertEquals(t.e, roundTrip.e, 1e-12); assertEquals(t.f, roundTrip.f, 1e-12)
        val roundTripBox = roundTrip.mapBox(RasterBox(0, 0, 101, 201), 201f, 101f)!!
        assertEquals(0f, roundTripBox.left, 1e-4f); assertEquals(0f, roundTripBox.top, 1e-4f)
        assertEquals(201f, roundTripBox.right, 1e-4f); assertEquals(101f, roundTripBox.bottom, 1e-4f)
    }
    @Test fun sharedBudgetCapsEveryPassAndTotalWithoutOverflowOrRefund() {
        val budget = OcrWorkBudget(25_000_000)
        assertFalse(budget.reserve(Int.MAX_VALUE, Int.MAX_VALUE, 20_000_000))
        assertFalse(budget.reserve(5001, 4000, 20_000_000))
        assertTrue(budget.reserve(5000, 4000, 20_000_000))
        assertTrue(budget.reserve(2500, 2000, 20_000_000))
        assertFalse(budget.reserve(1, 1, 20_000_000))
        assertEquals(25_000_000L, budget.consumedPixels)
        assertThrows(IllegalArgumentException::class.java) { OcrWorkBudget(80_000_001) }
    }
    @Test fun retainsBothCompleteVariantsWithExplicitDisagreementAndRecommendsQuality() = runBlocking {
        val events = mutableListOf<String>()
        val result = OcrVariantCoordinator(OcrLimits()).recognize(3, 101f, 201f, geometry(), "caller", OcrRecognitionOptions(),
            recognizeOnce = { raster, spec -> events += "native:$raster"; if (spec.id == OcrVariantId.ORIGINAL) once(spec) else once(spec, listOf(raw("alternate", 40, 95f))) },
            createHalf = { _, w, h -> events += "allocate:${w}x$h"; "owned" }, releaseHalf = { events += "recycle:$it" })
        assertEquals(listOf("native:caller", "allocate:50x100", "native:owned", "recycle:owned"), events)
        assertEquals(listOf(OcrVariantId.ORIGINAL, OcrVariantId.HALF_SCALE), result.variants.map { it.id })
        assertEquals(listOf("invented", "alternate"), result.variants.map { it.spans.single().rawText })
        assertTrue(result.variants.all { it.usable })
        assertEquals(OcrVariantId.HALF_SCALE, result.recommendedVariantId)
        assertEquals("alternate", result.spans.single().rawText)
        assertTrue(result.issues.containsAll(listOf(OcrIssue.OCR_SOURCE_REVIEW_REQUIRED, OcrIssue.OCR_REVIEW_REQUIRED, OcrIssue.OCR_ALTERNATE_REVIEW_REQUIRED, OcrIssue.OCR_VARIANT_DISAGREEMENT)))
        assertEquals(25_301L, result.workPixels)
    }
    @Test fun disabledRetryDoesNotAllocateAndOptionsCanShareBudgetAcrossCalls() = runBlocking {
        val budget = OcrWorkBudget(20_301)
        val options = OcrRecognitionOptions(allowHalfScaleAlternate = false, workBudget = budget)
        val coordinator = OcrVariantCoordinator(OcrLimits())
        val first = coordinator.recognize(3, 101f, 201f, geometry(), "caller", options, { _, spec -> once(spec) }, { _, _, _ -> error("must not allocate") }, { error("must not recycle caller") })
        assertEquals(1, first.variants.size)
        val second = coordinator.recognize(3, 101f, 201f, geometry(), "caller", options, { _, _ -> error("must not start native") }, { _, _, _ -> error("must not allocate") }, {})
        assertEquals(OcrFailure.RESOURCE_LIMIT, second.failure)
        assertNull(second.page); assertTrue(second.spans.isEmpty()); assertEquals(0L, second.workPixels)
    }
    @Test fun worseAlternateCannotReplaceOriginalAndNeverStartsAThirdPass() = runBlocking {
        var calls = 0
        val result = OcrVariantCoordinator(OcrLimits()).recognize(3, 101f, 201f, geometry(), "caller", OcrRecognitionOptions(),
            { _, spec -> calls++; once(spec, listOf(raw(if (spec.id == OcrVariantId.ORIGINAL) "before" else "after", 80, if (spec.id == OcrVariantId.ORIGINAL) 20f else 10f))) },
            { _, _, _ -> "owned" }, {})
        assertEquals(2, calls); assertEquals(2, result.variants.size)
        assertEquals(OcrVariantId.ORIGINAL, result.recommendedVariantId)
        assertEquals("before", result.spans.single().rawText)
        assertEquals("after", result.variants.last().spans.single().rawText)
        assertTrue(OcrIssue.OCR_VARIANT_DISAGREEMENT in result.issues)
    }
    @Test fun alternateBudgetFailurePreservesOriginalAsUnusableDiagnosticWithoutAllocating() = runBlocking {
        val result = OcrVariantCoordinator(OcrLimits()).recognize(3, 101f, 201f, geometry(), "caller", OcrRecognitionOptions(workBudget = OcrWorkBudget(20_302)),
            { _, spec -> once(spec) }, { _, _, _ -> error("budget must precede allocation") }, {})
        assertEquals(OcrFailure.RESOURCE_LIMIT, result.failure); assertNull(result.page); assertTrue(result.spans.isEmpty())
        assertTrue(result.variants.all { !it.usable })
        assertEquals("invented", result.variants.first().spans.single().rawText)
        assertEquals(OcrFailure.RESOURCE_LIMIT, result.variants.last().failure)
        assertEquals(20_301L, result.workPixels)
    }
    @Test fun alternateUsesRemainingPageTextAndSpanAllowanceRatherThanDoublingLimits() = runBlocking {
        val coordinator = OcrVariantCoordinator(OcrLimits(maxTextCharacters = 9, maxSpans = 2))
        val result = coordinator.recognize(3, 101f, 201f, geometry(), "caller", OcrRecognitionOptions(),
            { _, spec ->
                val raw = listOf(raw(if (spec.id == OcrVariantId.ORIGINAL) "invented" else "alternate"))
                if (spec.id == OcrVariantId.HALF_SCALE) {
                    assertEquals(1, spec.limits.maxTextCharacters); assertEquals(1, spec.limits.maxSpans)
                }
                OcrPassOutcome(OcrResultAssembler(spec.limits).assemble(3, 101f, 201f, spec.rasterToPage, raw), OcrWordQuality.from(raw, 70f))
            }, { _, _, _ -> "owned" }, {})
        assertEquals(OcrFailure.RESOURCE_LIMIT, result.failure)
        assertNull(result.page); assertTrue(result.spans.isEmpty()); assertTrue(result.variants.all { !it.usable })
        assertEquals("invented", result.variants.first().spans.single().rawText)
    }
    @Test fun structuralAlternateFailureNeverExposesUsablePartialSuccessAndStillRecycles() = runBlocking {
        val releases = mutableListOf<String>()
        val result = OcrVariantCoordinator(OcrLimits()).recognize(3, 101f, 201f, geometry(), "caller", OcrRecognitionOptions(),
            { _, spec -> if (spec.id == OcrVariantId.ORIGINAL) once(spec) else throw IllegalStateException("invented native failure") }, { _, _, _ -> "owned" }, { releases += it })
        assertEquals(OcrFailure.NATIVE_FAILED, result.failure); assertNull(result.page); assertTrue(result.spans.isEmpty())
        assertTrue(result.variants.all { !it.usable }); assertEquals("invented", result.variants.first().spans.single().rawText)
        assertEquals(listOf("owned"), releases)
    }
    @Test fun cancellationBeforeAllocationDoesNotAllocateAnAlternate() = runBlocking {
        val job = launch { OcrVariantCoordinator(OcrLimits()).recognize(3, 101f, 201f, geometry(), "caller", OcrRecognitionOptions(),
            { _, spec -> once(spec).also { currentCoroutineContext().cancel() } }, { _, _, _ -> error("must not allocate after cancellation") }, {}) }
        job.join(); assertTrue(job.isCancelled)
    }
    @Test fun cancellationDuringAllocationReleasesOwnedRasterBeforeReturningWithoutStartingAlternateNative() = runBlocking {
        val releases = mutableListOf<String>()
        var nativeCalls = 0
        lateinit var request: Job
        request = launch(start = CoroutineStart.LAZY) {
            OcrVariantCoordinator(OcrLimits()).recognize(3, 101f, 201f, geometry(), "caller", OcrRecognitionOptions(),
                { _, spec -> nativeCalls++; once(spec) },
                { _, _, _ -> request.cancel(); "owned" }, { releases += it })
        }
        request.start(); request.join()
        assertTrue(request.isCancelled); assertEquals(1, nativeCalls); assertEquals(listOf("owned"), releases)
    }
    @Test fun cancellationDuringAlternateNativeWaitsForWorkerCleanupBeforeOwnedRecycle() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val job = launch(Dispatchers.Default) {
            OcrVariantCoordinator(OcrLimits()).recognize(3, 101f, 201f, geometry(), "caller", OcrRecognitionOptions(),
                recognizeOnce = { _, spec ->
                    if (spec.id == OcrVariantId.HALF_SCALE) SerializedOcrWorker().run { _ -> object : NativeOcrSession {
                        override fun initialize() { events += "init" }
                        override fun setImage() { events += "image" }
                        override fun recognize() { events += "hocr"; entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); events += "native-return" }
                        override fun readSpans(checkCancellation: () -> Unit) = emptyList<NativeOcrSpan>()
                        override fun close() { events += "native-close" }
                    } }
                    once(spec)
                }, createHalf = { _, _, _ -> "owned" }, releaseHalf = { events += "recycle-owned" })
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS)); job.cancel(); assertFalse("recycle-owned" in events)
        release.countDown(); job.join()
        assertEquals(listOf("init", "image", "hocr", "native-return", "native-close", "recycle-owned"), events)
    }
}
