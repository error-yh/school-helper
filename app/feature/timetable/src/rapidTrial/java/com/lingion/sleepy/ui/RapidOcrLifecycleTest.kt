package com.lingion.sleepy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hzkitty.RapidOCR
import io.github.hzkitty.ch_ppocr_cls.TextClassifier
import io.github.hzkitty.ch_ppocr_det.TextDetector
import io.github.hzkitty.ch_ppocr_rec.TextRecognizer
import io.github.hzkitty.entity.OcrConfig
import io.github.hzkitty.entity.ParamConfig
import io.github.hzkitty.utils.MatScope
import io.github.hzkitty.utils.OrtInferSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.Utils
import org.opencv.core.Mat
import java.io.File

/** Opt-in device checks use generated pixels, never a personal timetable or stored fixture. */
@RunWith(AndroidJUnit4::class)
class RapidOcrLifecycleTest {
    @Test fun engineExposesExplicitResourceOwnership() {
        listOf(RapidOCR::class.java, TextDetector::class.java, TextClassifier::class.java,
            TextRecognizer::class.java, OrtInferSession::class.java).forEach { type ->
            assertTrue("${type.simpleName} must expose an explicit close lifecycle",
                AutoCloseable::class.java.isAssignableFrom(type))
        }
    }

    @Test fun repeatedRecognitionAndReconstructionReleaseTheirTemporaryMats() {
        val bitmap = syntheticText()
        try {
            // The second construction must still work after the first engine closes:
            // OrtEnvironment belongs to the process, not to an individual engine.
            repeat(2) { cycle ->
                val engine = newEngine(useClassifier = cycle == 1)
                try {
                    var previousText: String? = null
                    repeat(2) {
                        val result = engine.run(bitmap, ParamConfig().apply { returnWordBox = true })
                        assertTrue("Generated high-contrast text should exercise actual recognition",
                            result.strRes.isNotBlank() && result.recRes.isNotEmpty())
                        previousText?.let { assertEquals("Reuse must preserve recognized text", it, result.strRes) }
                        previousText = result.strRes
                        assertReleasedScope()
                    }
                } finally {
                    engine.close()
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun closeIsRepeatableAndPreventsFurtherRecognition() {
        val bitmap = syntheticText()
        val engine = newEngine()
        try {
            engine.close()
            engine.close()
            assertThrows(IllegalStateException::class.java) { engine.run(bitmap) }
            assertEquals(0, MatScope.activeOwnedCount())
        } finally {
            engine.close()
            bitmap.recycle()
        }
    }

    @Test fun failedImageDecodeReleasesMatsAndKeepsEngineUsable() {
        val bitmap = syntheticText()
        val engine = newEngine()
        try {
            assertThrows(Exception::class.java) { engine.run(byteArrayOf(0, 1, 2, 3, 4, 5)) }
            assertReleasedScope()
            val result = engine.run(bitmap)
            assertTrue("A bad input must not poison the reusable inference sessions", result.strRes.isNotBlank())
            assertReleasedScope()
        } finally {
            engine.close()
            bitmap.recycle()
        }
    }

    @Test fun laterModelConstructionFailureAllowsAHealthyEngineAfterward() {
        val context = testContext()
        val badConfig = trialConfig(context).apply {
            // Detection and classification sessions have already been constructed when
            // recognition model loading fails. The constructor must unwind them.
            rec.modelPath = File(context.cacheDir,
                "ocr/rapid/lifecycle-missing-${System.nanoTime()}.onnx").absolutePath
        }
        assertThrows(RuntimeException::class.java) { RapidOCR.create(context, badConfig) }
        assertEquals(0, MatScope.activeOwnedCount())

        val bitmap = syntheticText()
        val engine = newEngine()
        try {
            assertTrue(engine.run(bitmap).strRes.isNotBlank())
            assertReleasedScope()
        } finally {
            engine.close()
            bitmap.recycle()
        }
    }

    @Test fun callerOwnedRgbaMatSurvivesConversionAndRecognition() {
        val bitmap = syntheticText()
        val engine = newEngine()
        val borrowed = Mat()
        try {
            // RGBA intentionally exercises the Core.split output ownership branch.
            Utils.bitmapToMat(bitmap, borrowed)
            val before = ByteArray((borrowed.total() * borrowed.channels()).toInt())
            borrowed.get(0, 0, before)
            engine.run(borrowed)
            assertReleasedScope()
            assertFalse("An input Mat is borrowed from the caller", borrowed.empty())
            val after = ByteArray(before.size)
            borrowed.get(0, 0, after)
            assertArrayEquals("OCR must leave caller-owned pixel storage intact", before, after)
        } finally {
            borrowed.release()
            engine.close()
            bitmap.recycle()
        }
    }

    private fun assertReleasedScope() {
        assertEquals("No recognition-owned Mat may escape the invocation", 0, MatScope.activeOwnedCount())
        assertTrue("The invocation must explicitly release the Mats it created", MatScope.lastReleasedCount() > 0)
    }

    private fun newEngine(useClassifier: Boolean = false): RapidOCR {
        val context = testContext()
        return RapidOCR.create(context, trialConfig(context).apply { global.useCls = useClassifier })
    }

    private fun testContext(): Context = InstrumentationRegistry.getInstrumentation().context

    private fun trialConfig(context: Context): OcrConfig {
        return rapidTrialConfig(context,720,160)
    }

    private fun syntheticText(): Bitmap = Bitmap.createBitmap(720, 160, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).apply {
            drawColor(Color.WHITE)
            drawText("MATH 123", 72f, 108f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 76f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            })
        }
    }
}
