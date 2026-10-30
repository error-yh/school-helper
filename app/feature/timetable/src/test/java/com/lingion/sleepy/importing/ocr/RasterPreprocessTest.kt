/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class RasterPreprocessTest {
    @Test fun nativePassUsesImportStageBudgetAndPreservesSingleAlternatePolicy() = runBlocking {
        val budget = OcrWorkBudget(36)
        val resources = RasterResources(budget)
        resources.reserveStage(3,4) // decode
        resources.reserveStage(3,4) // orientation/composite
        val options = resources.recognitionOptions()
        val result = OcrVariantCoordinator(OcrLimits()).recognize(1,3f,4f,
            OcrPassSpec(OcrVariantId.ORIGINAL,3,4,PageAffineTransform.IDENTITY),Any(),options,
            recognizeOnce = { _,_ -> OcrPassOutcome(OcrResultAssembler(OcrLimits()).assemble(1,3f,4f,PageAffineTransform.IDENTITY,emptyList()),OcrWordQuality.EMPTY) },
            createHalf = { _,_,_ -> error("No half-scale requested") }, releaseHalf = {})
        assertEquals(36L,budget.consumedPixels)
        assertSame(budget,options.workBudget)
        assertEquals(12L,result.workPixels)
        assertEquals(listOf(OcrVariantId.ORIGINAL),result.variants.map { it.id })
        assertTrue(options.allowHalfScaleAlternate)
        assertFalse(resources.recognitionOptions(false).allowHalfScaleAlternate)
        assertFalse(budget.reserve(1,1,20_000_000))
    }
    @Test fun rejectsInvalidAndOverflowDimensionsBeforeReserving() {
        val budget = OcrWorkBudget()
        val resources = RasterResources(budget)
        for ((w, h) in listOf(0 to 4, -1 to 4, Int.MAX_VALUE to Int.MAX_VALUE, 20_001 to 1, 5_000 to 4_001)) {
            assertThrows(RasterException::class.java) { resources.reserveStage(w, h) }
        }
        assertEquals(0L, budget.consumedPixels)
    }
    @Test fun sharesStageBudgetAndBoundsLiveBitmapBytesWithoutRefundingWork() {
        val budget = OcrWorkBudget(100)
        val resources = RasterResources(budget, RasterLimits(maxLiveBitmapBytes = 300))
        resources.reserveStage(5, 10)
        resources.acquireBitmap(5, 10).use {
            assertThrows(RasterException::class.java) { resources.acquireBitmap(5, 10) }
        }
        resources.acquireBitmap(5, 10).close()
        resources.reserveStage(5, 10)
        assertThrows(RasterException::class.java) { resources.reserveStage(1, 1) }
        assertEquals(100L, budget.consumedPixels)
        assertEquals(0L, resources.liveBitmapBytes)
    }
    @Test fun allEightExifMappingsPreserveAsymmetricCorners() {
        val expected = listOf(
            listOf(0 to 0, 2 to 0, 0 to 1, 2 to 1), listOf(2 to 0, 0 to 0, 2 to 1, 0 to 1),
            listOf(2 to 1, 0 to 1, 2 to 0, 0 to 0), listOf(0 to 1, 2 to 1, 0 to 0, 2 to 0),
            listOf(0 to 0, 0 to 2, 1 to 0, 1 to 2), listOf(1 to 0, 1 to 2, 0 to 0, 0 to 2),
            listOf(1 to 2, 1 to 0, 0 to 2, 0 to 0), listOf(0 to 2, 0 to 0, 1 to 2, 1 to 0))
        val corners = listOf(0 to 0, 2 to 0, 0 to 1, 2 to 1)
        for (orientation in 1..8) {
            val geometry = ImageOrientation.of(orientation, 3, 2)
            val transform=geometry.edgeTransform()
            assertEquals(expected[orientation - 1], corners.map { (x,y) ->
                // Convert pixel centers through the exact matrix used by Canvas.
                (transform.a*(x+0.5)+transform.c*(y+0.5)+transform.e-0.5).roundToInt() to
                    (transform.b*(x+0.5)+transform.d*(y+0.5)+transform.f-0.5).roundToInt()
            })
            assertEquals(if (orientation >= 5) 2 to 3 else 3 to 2, geometry.width to geometry.height)
        }
        assertThrows(RasterException::class.java) { ImageOrientation.of(0, 3, 2) }
    }
    @Test fun pdfGeometryUsesFractionalRotatedCropPointsAndExactInverse() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val geometry = PdfRasterGeometry.create(10.25f, 20.5f, 110.75f, 221.25f, rotation, 2)
            val expected = if (rotation % 180 == 0) 100.5f to 200.75f else 200.75f to 100.5f
            assertEquals(expected, geometry.pageWidth to geometry.pageHeight)
            val box = geometry.rasterToPage.mapBox(RasterBox(0, 0, geometry.rasterWidth, geometry.rasterHeight), expected.first, expected.second)!!
            assertEquals(expected.first, box.right, 0.0001f)
            assertEquals(expected.second, box.bottom, 0.0001f)
            assertTrue(geometry.matchesRendererSize(expected.first.toInt(), expected.second.toInt()))
            assertFalse(geometry.matchesRendererSize(expected.first.toInt() + 2, expected.second.toInt()))
        }
        assertThrows(RasterException::class.java) { PdfRasterGeometry.create(0f, 0f, 10f, 10f, 45, 2) }
    }
    @Test fun privateCopyCountsActualBytesLeavesCallerOpenAndCleansFailure() {
        val directory = Files.createTempDirectory("raster-unit").toFile()
        var closed = false
        val stream = object : ByteArrayInputStream(ByteArray(13)) { override fun close() { closed = true } }
        try {
            assertThrows(RasterException::class.java) { BoundedPrivateRasterSource.copyTo(directory, stream, 12) }
            assertFalse(closed)
            assertTrue(directory.list()!!.isEmpty())
            BoundedPrivateRasterSource.copyTo(directory, ByteArrayInputStream(ByteArray(12)), 12).use { source ->
                assertEquals(12L, source.byteCount)
                assertEquals(1, directory.list()!!.size)
            }
            assertTrue(directory.list()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun cancellationAndZeroReadCleanupOnlyOwnedCopy() {
        val directory = Files.createTempDirectory("raster-unit").toFile()
        val sentinel = java.io.File(directory, "keep").also { it.writeText("synthetic") }
        var probes = 0
        try {
            assertThrows(CancellationException::class.java) {
                BoundedPrivateRasterSource.copyTo(directory, ByteArrayInputStream(ByteArray(20_000)), 30_000) {
                    if (++probes > 2) throw CancellationException()
                }
            }
            assertEquals(listOf(sentinel.name), directory.list()!!.toList())
            val zeroReader = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) { override fun read(b: ByteArray, off: Int, len: Int) = 0 }
            BoundedPrivateRasterSource.copyTo(directory, zeroReader, 3).use { assertEquals(3L, it.byteCount) }
        } finally { directory.deleteRecursively() }
    }
    @Test fun enforcesActualTwelveMillionByteBoundaryAndSanitizesInputFailure() {
        val directory = Files.createTempDirectory("raster-unit").toFile()
        fun repeated(size: Int) = object : java.io.InputStream() {
            var remaining = size
            override fun read(): Int = if (remaining-- > 0) 0 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining <= 0) return -1
                val count = minOf(length,remaining);remaining -= count;buffer.fill(0,offset,offset+count);return count
            }
        }
        try {
            BoundedPrivateRasterSource.copyTo(directory,repeated(12_000_000),12_000_000).use { assertEquals(12_000_000L,it.byteCount) }
            val tooLarge = assertThrows(RasterException::class.java) { BoundedPrivateRasterSource.copyTo(directory,repeated(12_000_001),12_000_000) }
            assertEquals(RasterFailure.LIMIT_EXCEEDED,tooLarge.code)
            val failure = assertThrows(RasterException::class.java) {
                BoundedPrivateRasterSource.copyTo(directory,object:java.io.InputStream() { override fun read(): Int = throw java.io.IOException("synthetic-private-path") },12_000_000)
            }
            assertEquals("RASTER_UNREADABLE",failure.message)
            assertNull(failure.cause);assertTrue(directory.list()!!.isEmpty())
        } finally { directory.deleteRecursively() }
    }
    @Test fun runtimeReaderFailureIsSanitizedCleansOwnedFileAndLeavesCallerOpen() {
        val directory=Files.createTempDirectory("raster-unit").toFile()
        try {
            for (original in listOf(IllegalStateException("synthetic/private/path"),IllegalArgumentException("synthetic raw source"))) {
                var closed=false
                val input=object:java.io.InputStream() {
                    override fun read(): Int = throw original
                    override fun close() { closed=true }
                }
                val failure=assertThrows(RasterException::class.java) { BoundedPrivateRasterSource.copyTo(directory,input,12_000_000) }
                assertEquals(RasterFailure.RASTER_UNREADABLE,failure.code)
                assertEquals("RASTER_UNREADABLE",failure.message)
                assertNull(failure.cause);assertTrue(failure.suppressed.isEmpty())
                assertTrue(directory.list()!!.isEmpty());assertFalse(closed)
            }
        } finally { directory.deleteRecursively() }
    }
    @Test fun lineEvidenceIsDeterministicBoundedAndNeverChangesPixels() {
        val pixels = IntArray(100 * 80) { -1 }
        for (y in 10..11) for (x in 5..94) pixels[y * 100 + x] = 0xff000000.toInt()
        for (y in 3..75) if (y % 10 != 0) pixels[y * 100 + 30] = 0xff000000.toInt()
        pixels[10 * 100 + 50] = -1 // finite gap, retained in evidence only
        val before = pixels.copyOf()
        val scanner = TableLineDetector(LineDetectionParameters(minLength = 40))
        fun detect() = scanner.detect(100, 80, PageAffineTransform.IDENTITY, 100f, 80f,
            RasterResources(OcrWorkBudget()), { vertical, fixed, buffer ->
                val n = if (vertical) 80 else 100
                for (i in 0 until n) buffer[i] = if (vertical) pixels[i * 100 + fixed] else pixels[fixed * 100 + i]
            })
        val a = detect(); val b = detect()
        assertEquals(a, b)
        assertTrue(a.runs.any { !it.vertical && it.start == 5 && it.endExclusive == 95 })
        assertTrue(a.runs.any { it.vertical && it.fixed == 30 })
        assertTrue(a.lines.any { it.thickness == 2 })
        assertArrayEquals(before, pixels)
        assertEquals(80, a.horizontalProjection.size)
        assertEquals(100, a.verticalProjection.size)
    }
    @Test fun scanStopsOnCancellationRunLimitAndSharedBudgetBeforePixelRead() {
        var reads = 0
        val reader: (Boolean, Int, IntArray) -> Unit = { _, _, b -> reads++; b.fill(0xff000000.toInt()) }
        val scanner = TableLineDetector(LineDetectionParameters(minLength = 1, maxRuns = 2))
        assertThrows(RasterException::class.java) { scanner.detect(10, 10, PageAffineTransform.IDENTITY, 10f, 10f, RasterResources(OcrWorkBudget(199)), reader) }
        assertEquals(0, reads)
        assertThrows(RasterException::class.java) { scanner.detect(10, 10, PageAffineTransform.IDENTITY, 10f, 10f, RasterResources(OcrWorkBudget()), reader) }
        assertThrows(CancellationException::class.java) { scanner.detect(10, 10, PageAffineTransform.IDENTITY, 10f, 10f, RasterResources(OcrWorkBudget()), reader) { throw CancellationException() } }
    }
}
