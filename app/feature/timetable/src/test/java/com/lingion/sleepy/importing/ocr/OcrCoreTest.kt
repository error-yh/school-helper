/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.TextBox
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class OcrGeometryTest {
    @Test fun mapsAllFourCornersForShearAndRotation() {
        val transform = PageAffineTransform(0.0, 2.0, -3.0, 1.0, 60.0, 0.0)
        assertEquals(TextBox(0f, 0f, 60f, 40f), transform.mapBox(RasterBox(0, 0, 10, 20), 100f, 100f))
    }
    @Test fun clipsOnlyRoundingToleranceAndRejectsInvalidBoxes() {
        val t = PageAffineTransform(1.0, 0.0, 0.0, 1.0, -0.25, 0.0)
        assertEquals(TextBox(0f, 0f, 9.75f, 10f), t.mapBox(RasterBox(0, 0, 10, 10), 10f, 10f))
        assertNull(t.mapBox(RasterBox(-1, 0, 10, 10), 10f, 10f))
        assertNull(t.mapBox(RasterBox(5, 0, 4, 10), 10f, 10f))
    }
    @Test fun rejectsSingularAndNonFiniteTransforms() {
        assertThrows(IllegalArgumentException::class.java) { PageAffineTransform(1.0, 2.0, 2.0, 4.0, 0.0, 0.0) }
        assertThrows(IllegalArgumentException::class.java) { PageAffineTransform(Double.NaN, 0.0, 0.0, 1.0, 0.0, 0.0) }
    }
    @Test fun retainsBothLevelsButPositionsOnlyWordsAndFlagsLowConfidence() {
        val result = OcrResultAssembler(OcrLimits()).assemble(3, 100f, 100f, PageAffineTransform.IDENTITY, listOf(
            NativeOcrSpan("Raw word\n", OcrLevel.WORD, 10f, RasterBox(0, 0, 30, 10)),
            NativeOcrSpan("字", OcrLevel.SYMBOL, 99f, RasterBox(1, 0, 9, 10)),
            NativeOcrSpan("unpositioned", OcrLevel.WORD, Float.NaN, null)))
        assertEquals(listOf("Raw word\n"), result.page!!.text.map { it.rawText })
        assertEquals(3, result.spans.size)
        assertNull(result.spans.last().position)
        assertNull(result.spans.last().confidence)
        assertTrue(result.issues.containsAll(listOf(OcrIssue.OCR_SOURCE_REVIEW_REQUIRED, OcrIssue.OCR_REVIEW_REQUIRED, OcrIssue.OCR_UNPOSITIONED_TEXT)))
    }
    @Test fun blankRetainsPageAndLimitsNeverReturnPartialSuccess() {
        val assembler = OcrResultAssembler(OcrLimits(maxTextCharacters = 3, maxSpans = 2))
        val blank = assembler.assemble(7, 20f, 30f, PageAffineTransform.IDENTITY, emptyList())
        assertEquals(7, blank.page!!.number)
        assertTrue(OcrIssue.OCR_BLANK_PAGE in blank.issues)
        val limited = assembler.assemble(7, 20f, 30f, PageAffineTransform.IDENTITY, listOf(NativeOcrSpan("long", OcrLevel.WORD, 90f, null)))
        assertNull(limited.page)
        assertTrue(limited.spans.isEmpty())
        assertEquals(OcrFailure.RESOURCE_LIMIT, limited.failure)
    }
}

class OcrModelStoreTest {
    @Test fun copiesVerifiedAssetsAndRepairsCorruptionWithoutLeavingTemporaryFiles() {
        val root = Files.createTempDirectory("ocr-model-unit").toFile()
        try {
            val bytes = "public-test-model".toByteArray()
            val spec = OcrModelSpec("eng.traineddata", bytes.size.toLong(), sha(bytes))
            var opens = 0
            val store = OcrModelStore(root, listOf(spec)) { opens++; ByteArrayInputStream(bytes) }
            assertEquals(root, store.prepare())
            assertArrayEquals(bytes, File(root, "tessdata/eng.traineddata").readBytes())
            store.prepare(); assertEquals(1, opens)
            File(root, "tessdata/eng.traineddata").writeText("corrupt")
            store.prepare(); assertEquals(2, opens)
            assertEquals(listOf("eng.traineddata"), File(root, "tessdata").list()!!.toList())
        } finally { root.deleteRecursively() }
    }
    @Test fun rejectsWrongHashAndUnsafeNameWithoutPublishingModel() {
        val root = Files.createTempDirectory("ocr-model-unit").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) { OcrModelSpec("../eng.traineddata", 1, "0".repeat(64)) }
            val store = OcrModelStore(root, listOf(OcrModelSpec("eng.traineddata", 1, "0".repeat(64)))) { ByteArrayInputStream(byteArrayOf(1)) }
            assertThrows(OcrModelException::class.java) { store.prepare() }
            assertFalse(File(root, "tessdata/eng.traineddata").exists())
            assertTrue(File(root, "tessdata").list()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

class OcrLifecycleTest {
    @Test fun ordersNativeStagesAndClosesOnFailure() = runBlocking {
        val events = mutableListOf<String>()
        val worker = SerializedOcrWorker()
        val spans = worker.run { cancelled -> FakeSession(events, cancelled) }
        assertTrue(spans.isEmpty())
        assertEquals(listOf("init", "image", "hocr", "iterate", "close"), events)
        events.clear()
        assertThrows(IllegalStateException::class.java) { runBlocking { worker.run { FakeSession(events, { false }, fail = true) } } }
        assertEquals(listOf("init", "close"), events)
    }
    @Test fun cancellationDuringInitializationWaitsForNativeReturnBeforeClose() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val job = launch(Dispatchers.Default) { SerializedOcrWorker().run { cancelled -> FakeSession(events, cancelled, init = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS)); job.cancel()
        assertFalse("close" in events); release.countDown(); job.join()
        assertEquals(listOf("init", "close"), events)
    }
    @Test fun persistentCancellationSurvivesNativeEntryResetAndStopsFromProgressWorker() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val job = launch(Dispatchers.Default) { SerializedOcrWorker().run { cancelled -> FakeSession(events, cancelled, hocr = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); if (cancelled()) events += "stop-from-progress" }) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS)); job.cancel(); release.countDown(); job.join()
        assertEquals(listOf("init", "image", "hocr", "stop-from-progress", "close"), events)
    }
    @Test fun cancellationDuringIterationStillClosesAndConcurrentSessionsSerialize() = runBlocking {
        val worker = SerializedOcrWorker(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val first = launch(Dispatchers.Default) { worker.run { cancelled -> FakeSession(events, cancelled, iteration = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }) } }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) { worker.run { cancelled -> FakeSession(events, cancelled) } }
        first.cancel(); release.countDown(); first.join(); second.await()
        assertEquals(listOf("init", "image", "hocr", "iterate", "close", "init", "image", "hocr", "iterate", "close"), events)
    }
    private class FakeSession(val events: MutableList<String>, val cancelled: () -> Boolean, val fail: Boolean = false, val init: () -> Unit = {}, val hocr: () -> Unit = {}, val iteration: () -> Unit = {}) : NativeOcrSession {
        override fun initialize() { events += "init"; init(); if (fail) error("invented failure") }
        override fun setImage() { events += "image" }
        override fun recognize() { events += "hocr"; hocr() }
        override fun readSpans(checkCancellation: () -> Unit): List<NativeOcrSpan> { events += "iterate"; iteration(); checkCancellation(); return emptyList() }
        override fun close() { events += "close" }
    }
}
