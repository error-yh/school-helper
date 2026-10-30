/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Public invented text rendered locally. No student screenshots, PDF or reference inputs. */
@RunWith(AndroidJUnit4::class)
class TesseractLocalOcrEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun store(root: File) = OcrModelStore(root) { context.assets.open("ocr/models/$it") }
    private fun fixture(): Bitmap = Bitmap.createBitmap(1400, 600, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 90f }
        canvas.drawText("课程 测试", 90f, 160f, paint)
        canvas.drawText("TEST 1234", 90f, 340f, paint)
    }
    private fun page(bitmap: Bitmap, number: Int = 1) = OcrRasterPage(number, bitmap, bitmap.width.toFloat(), bitmap.height.toFloat(), PageAffineTransform.IDENTITY)
    private suspend fun <T> isolated(block: suspend (File) -> T): T {
        val root = File(context.noBackupFilesDir, "ocr-instrumented-${System.nanoTime()}")
        try { return block(root) } finally { root.deleteRecursively() }
    }
    @Test fun bundledModelsHavePinnedBytesAndHashesAndRepairLocalCorruption() = runBlocking {
        isolated { root ->
            for (spec in OcrModelStore.BUNDLED_MODELS) {
                val bytes = context.assets.open("ocr/models/${spec.filename}").use { it.readBytes() }
                assertEquals(spec.bytes, bytes.size.toLong())
                assertEquals(spec.sha256, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            }
            val models = store(root); models.prepare()
            val target = File(root, "tessdata/chi_sim.traineddata")
            target.writeText("invented corruption"); models.prepare()
            assertEquals(OcrModelStore.BUNDLED_MODELS.first().bytes, target.length())
            assertEquals(2, File(root, "tessdata").list()!!.size)
        }
    }
    @Test fun realNativeChineseEnglishPreservesRawLevelsGeometryAndCallerBitmapAcrossRepeats() = runBlocking {
        isolated { root ->
            val bitmap = fixture()
            try {
                val engine = TesseractLocalOcrEngine(store(root))
                val callerPixelsBefore = syntheticPixelsSha(bitmap)
                repeat(3) {
                    val result = engine.recognize(page(bitmap, 4))
                    // Assertion-only diagnostics for this invented fixture. Never add raw
                    // OCR diagnostics to production or apply this to student input.
                    val diagnostic = buildString {
                        append("fixtureRepeat=$it failure=${result.failure} issues=${result.issues} ")
                        append("WORD=${result.spans.count { it.level == OcrLevel.WORD }} ")
                        append("SYMBOL=${result.spans.count { it.level == OcrLevel.SYMBOL }} ")
                        result.spans.forEach { span ->
                            val escaped = span.rawText.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                            val codepoints = span.rawText.codePoints().toArray().joinToString(",") { "U+" + it.toString(16).uppercase() }
                            append("[${span.level} raw=<$escaped> codepoints=<$codepoints> confidence=${span.confidence} position=${span.position}] ")
                        }
                    }
                    assertNull(diagnostic, result.failure); assertEquals(diagnostic, 4, result.page!!.number)
                    val words = result.spans.filter { it.level == OcrLevel.WORD }.joinToString("") { it.rawText }
                    assertTrue("Invented Chinese course label must be recognized: $diagnostic", words.contains("课程"))
                    assertTrue("Invented English label must be recognized: $diagnostic", words.contains("TEST"))
                    assertTrue("Invented digits must be recognized: $diagnostic", words.contains("1234"))
                    assertEquals(diagnostic, listOf(OcrVariantId.ORIGINAL, OcrVariantId.HALF_SCALE), result.variants.map { it.id })
                    assertEquals(diagnostic, OcrVariantId.HALF_SCALE, result.recommendedVariantId)
                    val original = result.variants.first()
                    val alternate = result.variants.last()
                    assertTrue(diagnostic, result.variants.all { it.usable && it.failure == null && it.page!!.number == 4 })
                    assertTrue(diagnostic, original.quality.requiresHalfScale)
                    assertTrue(diagnostic, original.spans.any { it.level == OcrLevel.WORD && (it.confidence == null || it.confidence < 70f) })
                    assertTrue(diagnostic, original.spans.any { it.level == OcrLevel.SYMBOL })
                    assertEquals(diagnostic, 1400, original.rasterWidth); assertEquals(diagnostic, 600, original.rasterHeight)
                    assertEquals(diagnostic, 700, alternate.rasterWidth); assertEquals(diagnostic, 300, alternate.rasterHeight)
                    assertEquals(diagnostic, 1_050_000L, result.workPixels)
                    assertEquals(diagnostic, result.page, alternate.page)
                    assertTrue(diagnostic, result.issues.containsAll(listOf(OcrIssue.OCR_ALTERNATE_REVIEW_REQUIRED, OcrIssue.OCR_VARIANT_DISAGREEMENT)))
                    assertTrue(diagnostic, result.variants.flatMap { it.spans }.all { span -> span.position?.bounds?.let { it.left >= 0f && it.top >= 0f && it.right <= 1400f && it.bottom <= 600f } ?: true })
                    assertTrue(result.spans.any { it.level == OcrLevel.SYMBOL })
                    assertTrue(result.spans.all { it.confidence == null || it.confidence in 0f..100f })
                    assertTrue(result.spans.any { it.position != null })
                    assertEquals(result.spans.count { it.level == OcrLevel.WORD && it.position != null }, result.page!!.text.size)
                    assertTrue(OcrIssue.OCR_SOURCE_REVIEW_REQUIRED in result.issues)
                    assertFalse(bitmap.isRecycled); assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
                    assertEquals("All caller pixels must remain unchanged: $diagnostic", callerPixelsBefore, syntheticPixelsSha(bitmap))
                }
            } finally { bitmap.recycle() }
        }
    }
    @Test fun realNativeBlankRetainsPositiveOriginalPageNumber() = runBlocking {
        isolated { root ->
            val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            try {
                val result = TesseractLocalOcrEngine(store(root)).recognize(page(bitmap, 9))
                assertNull(result.failure); assertEquals(9, result.page!!.number)
                assertTrue(result.spans.isEmpty()); assertTrue(OcrIssue.OCR_BLANK_PAGE in result.issues)
                assertFalse(bitmap.isRecycled)
            } finally { bitmap.recycle() }
        }
    }
    @Test fun resourceLimitHasNoPartialPageAndLeavesCallerBitmapAlive() = runBlocking {
        isolated { root ->
            val bitmap = fixture()
            try {
                val result = TesseractLocalOcrEngine(store(root), OcrLimits(maxRasterPixels = 16)).recognize(page(bitmap))
                assertEquals(OcrFailure.RESOURCE_LIMIT, result.failure); assertNull(result.page); assertTrue(result.spans.isEmpty())
                assertFalse(bitmap.isRecycled)
                val spanLimit = TesseractLocalOcrEngine(store(root), OcrLimits(maxSpans = 1)).recognize(page(bitmap))
                assertEquals(OcrFailure.RESOURCE_LIMIT, spanLimit.failure); assertNull(spanLimit.page); assertTrue(spanLimit.spans.isEmpty())
                assertFalse(bitmap.isRecycled)
                val budget = OcrWorkBudget(bitmap.width.toLong() * bitmap.height + 1)
                val alternateBudget = TesseractLocalOcrEngine(store(root)).recognize(page(bitmap), OcrRecognitionOptions(workBudget = budget))
                assertEquals(OcrFailure.RESOURCE_LIMIT, alternateBudget.failure)
                assertNull(alternateBudget.page); assertTrue(alternateBudget.spans.isEmpty())
                assertEquals(listOf(OcrVariantId.ORIGINAL, OcrVariantId.HALF_SCALE), alternateBudget.variants.map { it.id })
                assertTrue(alternateBudget.variants.all { !it.usable })
                assertTrue(alternateBudget.variants.first().spans.isNotEmpty())
                assertEquals(OcrFailure.RESOURCE_LIMIT, alternateBudget.variants.last().failure)
                assertEquals(840_000L, alternateBudget.workPixels)
                assertFalse(bitmap.isRecycled)
            } finally { bitmap.recycle() }
        }
    }
    @Test fun cancellationAwaitsCleanupThenNextNativeRequestSucceeds() = runBlocking {
        isolated { root ->
            val bitmap = fixture()
            try {
                val engine = TesseractLocalOcrEngine(store(root))
                // Real caller cancellation; phase/timing is intentionally not claimed here.
                val job = launch(Dispatchers.Default) { engine.recognize(page(bitmap)) }
                delay(20); job.cancelAndJoin()
                assertTrue(job.isCancelled); assertFalse(bitmap.isRecycled)
                val next = engine.recognize(page(bitmap, 2))
                assertNull(next.failure); assertEquals(2, next.page!!.number); assertFalse(bitmap.isRecycled)
            } finally { bitmap.recycle() }
        }
    }

    /** Diagnostic comparison only: original mixed-script correctness test stays unchanged. */
    @Test fun diagnosticSamePixelsCompareSegmentationModesAndLanguageControl() = runBlocking {
        isolated { root ->
            val bitmap = fixture()
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val row = IntArray(bitmap.width)
                for (y in 0 until bitmap.height) {
                    bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    for (pixel in row) {
                        digest.update((pixel ushr 24).toByte()); digest.update((pixel ushr 16).toByte())
                        digest.update((pixel ushr 8).toByte()); digest.update(pixel.toByte())
                    }
                }
                val pixelsSha = digest.digest().joinToString("") { "%02x".format(it) }
                syntheticProbeStatus("expected=课程 测试 / TEST 1234; sameBitmap=${bitmap.width}x${bitmap.height}; ARGB-row-pixels-SHA256=$pixelsSha; CanvasDefaultTypeface; textSize=90px; baselineProductionUnchanged")
                val configurations = listOf(
                    Triple("AUTO", TessBaseAPI.PageSegMode.PSM_AUTO, "chi_sim+eng"),
                    Triple("SPARSE_TEXT", TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, "chi_sim+eng"),
                    Triple("SINGLE_BLOCK", TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK, "chi_sim+eng"),
                    Triple("AUTO_LANGUAGE_CONTROL", TessBaseAPI.PageSegMode.PSM_AUTO, "chi_sim"),
                )
                for ((name, mode, languages) in configurations) {
                    val spans = SerializedOcrWorker().run { cancelled ->
                        object : NativeOcrSession {
                            private lateinit var api: TessBaseAPI
                            init { api = TessBaseAPI { if (cancelled()) api.stop() } }
                            override fun initialize() {
                                val installed = store(root).prepare()
                                check(api.init(installed.absolutePath, languages, TessBaseAPI.OEM_LSTM_ONLY)) { "OCR_SYNTHETIC_PROBE_INIT_FAILED" }
                                api.pageSegMode = mode
                            }
                            override fun setImage() { api.setImage(bitmap) }
                            override fun recognize() { checkNotNull(api.getHOCRText(3)) }
                            override fun readSpans(checkCancellation: () -> Unit): List<NativeOcrSpan> {
                                val iterator = api.resultIterator ?: return emptyList()
                                try {
                                    val result = mutableListOf<NativeOcrSpan>()
                                    for ((level, nativeLevel) in listOf(OcrLevel.WORD to TessBaseAPI.PageIteratorLevel.RIL_WORD, OcrLevel.SYMBOL to TessBaseAPI.PageIteratorLevel.RIL_SYMBOL)) {
                                        iterator.begin()
                                        do {
                                            checkCancellation()
                                            check(result.size < 1000) { "OCR_SYNTHETIC_PROBE_BOUND" }
                                            val text = iterator.getUTF8Text(nativeLevel)
                                            if (!text.isNullOrEmpty()) {
                                                val bounds = iterator.getBoundingBox(nativeLevel)
                                                result += NativeOcrSpan(text, level, iterator.confidence(nativeLevel), bounds?.takeIf { it.size == 4 }?.let { RasterBox(it[0], it[1], it[2], it[3]) })
                                            }
                                        } while (iterator.next(nativeLevel))
                                    }
                                    return result
                                } finally { iterator.delete() }
                            }
                            override fun close() { api.recycle() }
                        }
                    }
                    val raw = spans.joinToString(" ") { span ->
                        val escaped = span.rawText.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                        "[${span.level} raw=<$escaped> confidence=${span.confidence} box=${span.box}]"
                    }
                    syntheticProbeStatus("preset=$name languages=$languages LSTM_ONLY WORD=${spans.count { it.level == OcrLevel.WORD }} SYMBOL=${spans.count { it.level == OcrLevel.SYMBOL }} $raw")
                    assertFalse("Probe must preserve the same caller bitmap", bitmap.isRecycled)
                }
            } finally { bitmap.recycle() }
        }
    }

    /** Rescaling/DPI hypothesis from official tessdoc/ImproveQuality; no production preset change. */
    @Test fun diagnosticSameOriginalCompareRasterScaleAndDpi() = runBlocking {
        isolated { root ->
            val original = fixture()
            val ownedVariants = mutableListOf<Bitmap>()
            val exported = File(context.filesDir, "ocr-synthetic-original.png")
            try {
                // Exact invented fixture only. Root already inspected O15's export;
                // subsequent diagnostic/full-class runs remove their own file in finally.
                exported.outputStream().use { output -> check(original.compress(Bitmap.CompressFormat.PNG, 100, output)) }
                val pngSha = exported.inputStream().use { input ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }
                syntheticProbeStatus("SCALE_DPI_PROBE expected=课程 测试 / TEST 1234; exportedExactOriginal=files/ocr-synthetic-original.png PNG-SHA256=$pngSha; ownedFileDeletedInFinally")
                val half = Bitmap.createScaledBitmap(original, original.width / 2, original.height / 2, true).also { ownedVariants += it }
                val twice = Bitmap.createScaledBitmap(original, original.width * 2, original.height * 2, true).also { ownedVariants += it }
                data class Case(val name: String, val bitmap: Bitmap, val scale: Double, val dpi: String?)
                for (case in listOf(Case("ORIGINAL", original, 1.0, null), Case("HALF", half, 0.5, null), Case("DOUBLE", twice, 2.0, null), Case("ORIGINAL_DPI300", original, 1.0, "300"))) {
                    var dpiAccepted: Boolean? = null
                    var dpiReadBack: String? = null
                    val spans = SerializedOcrWorker().run { cancelled ->
                        object : NativeOcrSession {
                            private lateinit var api: TessBaseAPI
                            init { api = TessBaseAPI { if (cancelled()) api.stop() } }
                            override fun initialize() {
                                check(api.init(store(root).prepare().absolutePath, "chi_sim+eng", TessBaseAPI.OEM_LSTM_ONLY)) { "OCR_SYNTHETIC_PROBE_INIT_FAILED" }
                                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                                if (case.dpi != null) dpiAccepted = api.setVariable("user_defined_dpi", case.dpi)
                                dpiReadBack = api.getVariable("user_defined_dpi")
                            }
                            override fun setImage() { api.setImage(case.bitmap) }
                            override fun recognize() { checkNotNull(api.getHOCRText(3)) }
                            override fun readSpans(checkCancellation: () -> Unit): List<NativeOcrSpan> {
                                val iterator = api.resultIterator ?: return emptyList()
                                try {
                                    val result = mutableListOf<NativeOcrSpan>()
                                    iterator.begin()
                                    do {
                                        checkCancellation(); check(result.size < 1000) { "OCR_SYNTHETIC_PROBE_BOUND" }
                                        val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                                        val text = iterator.getUTF8Text(level)
                                        if (!text.isNullOrEmpty()) {
                                            val bounds = iterator.getBoundingBox(level)
                                            result += NativeOcrSpan(text, OcrLevel.WORD, iterator.confidence(level), bounds?.takeIf { it.size == 4 }?.let { RasterBox(it[0], it[1], it[2], it[3]) })
                                        }
                                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
                                    return result
                                } finally { iterator.delete() }
                            }
                            override fun close() { api.recycle() }
                        }
                    }
                    val inverse = PageAffineTransform(1.0 / case.scale, 0.0, 0.0, 1.0 / case.scale, 0.0, 0.0)
                    val words = spans.joinToString(" ") { span ->
                        val escaped = span.rawText.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                        "[WORD raw=<$escaped> confidence=${span.confidence} rasterBox=${span.box} canonicalBox=${span.box?.let { inverse.mapBox(it, original.width.toFloat(), original.height.toFloat()) }}]"
                    }
                    syntheticProbeStatus("SCALE_DPI_PROBE variant=${case.name} scale=${case.scale} raster=${case.bitmap.width}x${case.bitmap.height} ARGB-row-pixels-SHA256=${syntheticPixelsSha(case.bitmap)} languages=chi_sim+eng AUTO LSTM_ONLY requestedDpi=${case.dpi} setVariableAccepted=$dpiAccepted readBackDpi=$dpiReadBack WORD=${spans.size} $words")
                    assertFalse("Original caller bitmap must stay alive", original.isRecycled)
                    assertFalse("Variant must stay alive until native completion", case.bitmap.isRecycled)
                }
            } finally {
                ownedVariants.forEach { it.recycle() }
                original.recycle()
                exported.delete()
                check(!exported.exists()) { "OCR_SYNTHETIC_EXPORT_CLEANUP_FAILED" }
            }
        }
    }

    private fun syntheticPixelsSha(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (pixel in row) {
                digest.update((pixel ushr 24).toByte()); digest.update((pixel ushr 16).toByte())
                digest.update((pixel ushr 8).toByte()); digest.update(pixel.toByte())
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun syntheticProbeStatus(message: String) {
        // Explicitly scoped to the invented fixture: instrumentation stream, never product logging.
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "SYNTHETIC_OCR_MODE_PROBE $message\n") })
    }
}
