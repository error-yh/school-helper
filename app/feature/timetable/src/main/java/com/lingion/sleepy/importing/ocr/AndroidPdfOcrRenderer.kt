/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import com.lingion.sleepy.importing.AndroidPdfTextExtractor
import com.lingion.sleepy.importing.PdfTextExtraction
import com.lingion.sleepy.importing.PdfPageRasterMetadata
import java.io.InputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/** No public boolean/file capability can bypass extraction permission assessment.
 * Consumer output is provisional until this function returns normally: discard all
 * page results on failure/cancellation. Keep raster work inside the awaited consumer.
 */
class AndroidPdfOcrRenderer(context: Context) {
    private val appContext = context.applicationContext
    private val directory = File(appContext.noBackupFilesDir,"import-raster")
    suspend fun withRenderedPages(input: InputStream, selectedPages: List<Int>, resources: RasterResources,
        consume: suspend (OcrRasterPage) -> Unit): PdfTextExtraction = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext(); val probe = { caller.ensureActiveRaster() }
        if (selectedPages.size > 30) throw RasterException(RasterFailure.PDF_REJECTED)
        val selection = selectedPages.toList()
        probe()
        if (selection.size > 30 || selection.distinct().size != selection.size || selection.any { it <= 0 }) throw RasterException(RasterFailure.PDF_REJECTED)
        BoundedPrivateRasterSource.copyTo(directory,input,resources.limits.maxBytes,probe).use { source ->
            var metadata = emptyList<PdfPageRasterMetadata>()
            val assessment = rasterRead(RasterFailure.PDF_REJECTED) {
                source.openRead().use { AndroidPdfTextExtractor(appContext).extractChecked(it,probe) { pages -> metadata=pages } }
            }
            if (assessment.document == null) {
                val code = if (assessment.issues.any { it.code == "LIMIT_EXCEEDED" }) RasterFailure.LIMIT_EXCEEDED else RasterFailure.PDF_REJECTED
                throw RasterException(code)
            }
            if (selection.any { it !in assessment.pagesNeedingOcr || it > metadata.size }) throw RasterException(RasterFailure.PDF_REJECTED)
            // A successful text extraction is necessary but not sufficient for geometry.
            val geometries = selection.associateWith { number ->
                val m = metadata[number-1]
                if (m.userUnit != 1f || listOf(m.mediaLeft,m.mediaBottom,m.mediaRight,m.mediaTop).any { !it.isFinite() } ||
                    m.left < m.mediaLeft || m.bottom < m.mediaBottom || m.right > m.mediaRight || m.top > m.mediaTop)
                    throw RasterException(RasterFailure.PDF_GEOMETRY_UNSUPPORTED)
                PdfRasterGeometry.create(m.left,m.bottom,m.right,m.top,m.rotation,2)
            }
            probe()
            val descriptor = rasterRead(RasterFailure.PDF_REJECTED) { source.openDescriptor() }
            val renderer = try { rasterRead(RasterFailure.PDF_REJECTED) { PdfRenderer(descriptor) } }
                catch (failure: Throwable) { descriptor.close(); throw failure }
            renderer.use { document ->
                if (document.pageCount != metadata.size) throw RasterException(RasterFailure.PDF_GEOMETRY_UNSUPPORTED)
                for (number in selection) {
                    probe()
                    rasterRead(RasterFailure.RASTER_UNREADABLE) { document.openPage(number-1) }.use { nativePage ->
                        val geometry = geometries.getValue(number)
                        if (!geometry.matchesRendererSize(nativePage.width,nativePage.height)) throw RasterException(RasterFailure.PDF_GEOMETRY_UNSUPPORTED)
                        probe(); resources.reserveStage(geometry.rasterWidth,geometry.rasterHeight)
                        resources.acquireBitmap(geometry.rasterWidth,geometry.rasterHeight).use {
                            val bitmap = rasterRead(RasterFailure.RASTER_UNREADABLE) { Bitmap.createBitmap(geometry.rasterWidth,geometry.rasterHeight,Bitmap.Config.ARGB_8888) }
                            try {
                                bitmap.density=Bitmap.DENSITY_NONE;bitmap.eraseColor(Color.WHITE)
                                val matrix=Matrix().also { it.setScale(geometry.rasterWidth/geometry.pageWidth,geometry.rasterHeight/geometry.pageHeight) }
                                probe()
                                rasterRead(RasterFailure.RASTER_UNREADABLE) { nativePage.render(bitmap,null,matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                                probe()
                                consume(OcrRasterPage(number,bitmap,geometry.pageWidth,geometry.pageHeight,geometry.rasterToPage))
                                probe()
                            } finally { bitmap.recycle() }
                        }
                    }
                }
            }
            assessment
        }
    }
}

/** Keep library/source exceptions out of the public result, but never catch OOM or cancellation. */
internal inline fun <T> rasterRead(code: RasterFailure, operation: () -> T): T = try { operation() }
    catch (e: RasterException) { throw e }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: java.io.IOException) { throw RasterException(code) }
    catch (_: RuntimeException) { throw RasterException(code) }
