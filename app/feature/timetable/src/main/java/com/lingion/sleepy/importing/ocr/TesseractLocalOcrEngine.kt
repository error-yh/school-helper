/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import android.content.Context
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Fully local Chinese+English OCR; bundled LSTM models, no download or network client.
 * Native calls are serialized off Main. Cancellation waits for JNI to return: initialization
 * and non-callback stages have no hard deadline. Neither this engine nor cleanup recycles input.
 */
class TesseractLocalOcrEngine internal constructor(private val models: OcrModelStore, private val limits: OcrLimits = OcrLimits()) : LocalOcrEngine {
    constructor(context: Context, limits: OcrLimits = OcrLimits()) : this(
        OcrModelStore(File(context.applicationContext.noBackupFilesDir, "ocr-models-v1")) { filename ->
            context.applicationContext.assets.open("ocr/models/$filename")
        }, limits,
    )
    private val worker = SerializedOcrWorker()
    override suspend fun recognize(page: OcrRasterPage): OcrPageResult = recognize(page, OcrRecognitionOptions())

    /** One original plus at most one large-glyph HALF alternate. An import orchestrator can
     * share its 80M OCR-pixel budget and disable this alternate when it owns line cleanup.
     */
    override suspend fun recognize(page: OcrRasterPage, options: OcrRecognitionOptions): OcrPageResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        if (page.bitmap.isRecycled) return@withContext OcrPageResult.failed(page.pageNumber, OcrFailure.INVALID_RASTER)
        val width = page.bitmap.width; val height = page.bitmap.height
        if (!limits.acceptsRaster(width, height)) return@withContext OcrPageResult.failed(page.pageNumber, OcrFailure.RESOURCE_LIMIT)
        val spec = OcrPassSpec(OcrVariantId.ORIGINAL, width, height, page.rasterToPage, limits)
        OcrVariantCoordinator(limits).recognize(page.pageNumber, page.originalWidth, page.originalHeight, spec, page.bitmap, options,
            recognizeOnce = { raster, pass -> recognizeOnce(page, raster, pass) },
            createHalf = { raster, targetWidth, targetHeight -> android.graphics.Bitmap.createScaledBitmap(raster, targetWidth, targetHeight, true) },
            releaseHalf = { it.recycle() })
    }

    private suspend fun recognizeOnce(original: OcrRasterPage, raster: android.graphics.Bitmap, spec: OcrPassSpec): OcrPassOutcome {
        currentCoroutineContext().ensureActive()
        val passPage = OcrRasterPage(original.pageNumber, raster, original.originalWidth, original.originalHeight, spec.rasterToPage)
        val raw = worker.run { cancelled -> TesseractNativeSession(models, passPage, spec.limits, cancelled) }
        currentCoroutineContext().ensureActive()
        val parent = currentCoroutineContext()
        val result = OcrResultAssembler(spec.limits).assemble(original.pageNumber, original.originalWidth, original.originalHeight, spec.rasterToPage, raw) { parent.ensureActive() }
        return OcrPassOutcome(result, OcrWordQuality.from(raw, limits.reviewConfidence))
        // OutOfMemoryError is deliberately not converted into a normal parse/resource result.
    }
}
private class TesseractNativeSession(private val models: OcrModelStore, private val page: OcrRasterPage, private val limits: OcrLimits, private val cancelled: () -> Boolean) : NativeOcrSession {
    private lateinit var api: TessBaseAPI
    init {
        api = TessBaseAPI {
            // Called by JNI synchronously on the owning native worker. The persistent
            // parent request survives JNI's cancellation reset at HOCR entry; stop never
            // races another thread deleting the iterator/recycling the API.
            if (cancelled()) api.stop()
        }
    }
    private fun checkCancellation() { if (cancelled()) throw CancellationException("OCR_CANCELLED") }
    override fun initialize() {
        val root = models.prepare(::checkCancellation)
        checkCancellation()
        check(api.init(root.absolutePath, "chi_sim+eng", TessBaseAPI.OEM_LSTM_ONLY)) { "OCR_NATIVE_INIT_FAILED" }
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
    }
    override fun setImage() { api.setImage(page.bitmap) }
    override fun recognize() {
        checkCancellation()
        // This triggers recognition with a monitor; getUTF8Text alone is not interruptible.
        // hOCR is transient engine output: never render as HTML, persist it, or log it.
        val hocr = api.getHOCRText(page.pageNumber - 1)
        checkCancellation()
        checkNotNull(hocr) { "OCR_NATIVE_RECOGNITION_FAILED" }
        if (hocr.length.toLong() > limits.maxTextCharacters.toLong() * 64L) throw OcrResourceLimitException()
    }
    override fun readSpans(checkCancellation: () -> Unit): List<NativeOcrSpan> {
        checkCancellation()
        val iterator = api.resultIterator ?: return emptyList()
        try {
            val spans = ArrayList<NativeOcrSpan>()
            var characters = 0L
            var visited = 0L
            for ((level, nativeLevel) in listOf(OcrLevel.WORD to TessBaseAPI.PageIteratorLevel.RIL_WORD, OcrLevel.SYMBOL to TessBaseAPI.PageIteratorLevel.RIL_SYMBOL)) {
                iterator.begin()
                do {
                    checkCancellation()
                    if (++visited > limits.maxSpans.toLong() * 2L) throw OcrResourceLimitException()
                    val text = iterator.getUTF8Text(nativeLevel)
                    if (!text.isNullOrEmpty()) {
                        characters += text.length
                        if (characters > limits.maxTextCharacters || spans.size >= limits.maxSpans) throw OcrResourceLimitException()
                        val coordinates = iterator.getBoundingBox(nativeLevel)
                        val box = coordinates?.takeIf { it.size == 4 }?.let { RasterBox(it[0], it[1], it[2], it[3]) }?.takeIf {
                            it.left >= 0 && it.top >= 0 && it.right <= page.bitmap.width && it.bottom <= page.bitmap.height && it.left < it.right && it.top < it.bottom
                        }
                        spans += NativeOcrSpan(text, level, iterator.confidence(nativeLevel), box)
                    }
                } while (iterator.next(nativeLevel))
            }
            return spans
        } finally { iterator.delete() }
    }
    override fun close() { api.recycle() }
}
