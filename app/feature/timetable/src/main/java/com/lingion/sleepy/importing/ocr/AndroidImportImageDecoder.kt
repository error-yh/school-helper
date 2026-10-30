/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

enum class RasterPixelChange { EXIF_ORIENTATION, WHITE_BACKGROUND }
data class DecodedImportRaster(val page: OcrRasterPage, val pixelChanges: Set<RasterPixelChange>)

/** Decoder owns the scoped raster; the suspending consumer must await downstream native work. */
class AndroidImportImageDecoder(context: Context) {
    private val directory = File(context.applicationContext.noBackupFilesDir,"import-raster")
    suspend fun <T> withDecoded(input: InputStream, resources: RasterResources, consume: suspend (DecodedImportRaster) -> T): T = withContext(Dispatchers.IO) {
        val caller = currentCoroutineContext(); val probe = { caller.ensureActiveRaster() }
        BoundedPrivateRasterSource.copyTo(directory,input,resources.limits.maxBytes,probe).use { source ->
            val orientationValue = rasterRead(RasterFailure.INVALID_IMAGE) { source.openRead().use { ImageContainerOrientation.read(it,source.byteCount,probe) } }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds=true;inScaled=false;inDensity=0;inTargetDensity=0 }
            probe();rasterRead(RasterFailure.INVALID_IMAGE) { source.openRead().use { BitmapFactory.decodeStream(it,null,bounds) } }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw RasterException(RasterFailure.INVALID_IMAGE)
            resources.checkDimensions(bounds.outWidth,bounds.outHeight)
            val geometry = ImageOrientation.of(orientationValue,bounds.outWidth,bounds.outHeight)
            probe();resources.reserveStage(bounds.outWidth,bounds.outHeight)
            resources.acquireBitmap(bounds.outWidth,bounds.outHeight).use {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig=Bitmap.Config.ARGB_8888;inMutable=true;inSampleSize=1;inScaled=false
                    inDensity=0;inTargetDensity=0;inScreenDensity=0
                }
                val original = rasterRead(RasterFailure.INVALID_IMAGE) { source.openRead().use { BitmapFactory.decodeStream(it,null,options) } }
                    ?: throw RasterException(RasterFailure.INVALID_IMAGE)
                var ownedOriginal: Bitmap? = original
                try {
                    probe()
                    if (original.width != bounds.outWidth || original.height != bounds.outHeight || original.config != Bitmap.Config.ARGB_8888 ||
                        original.allocationByteCount.toLong() > bounds.outWidth.toLong()*bounds.outHeight*4) throw RasterException(RasterFailure.INVALID_IMAGE)
                    original.density=Bitmap.DENSITY_NONE
                    val changes = linkedSetOf<RasterPixelChange>()
                    if (orientationValue != 1) changes += RasterPixelChange.EXIF_ORIENTATION
                    if (original.hasAlpha()) changes += RasterPixelChange.WHITE_BACKGROUND
                    if (changes.isEmpty()) {
                        consume(DecodedImportRaster(OcrRasterPage(1,original,original.width.toFloat(),original.height.toFloat(),PageAffineTransform.IDENTITY),changes)).also { probe() }
                    } else {
                        probe();resources.reserveStage(geometry.width,geometry.height)
                        resources.acquireBitmap(geometry.width,geometry.height).use {
                            val output = rasterRead(RasterFailure.INVALID_IMAGE) { Bitmap.createBitmap(geometry.width,geometry.height,Bitmap.Config.ARGB_8888) }
                            try {
                                output.density=Bitmap.DENSITY_NONE;output.eraseColor(Color.WHITE)
                                val t=geometry.edgeTransform()
                                val matrix=Matrix().also { it.setValues(floatArrayOf(t.a.toFloat(),t.c.toFloat(),t.e.toFloat(),t.b.toFloat(),t.d.toFloat(),t.f.toFloat(),0f,0f,1f)) }
                                rasterRead(RasterFailure.INVALID_IMAGE) { Canvas(output).drawBitmap(original,matrix,Paint().apply { isFilterBitmap=false;isAntiAlias=false }) }
                                original.recycle();ownedOriginal=null
                                probe()
                                consume(DecodedImportRaster(OcrRasterPage(1,output,geometry.width.toFloat(),geometry.height.toFloat(),PageAffineTransform.IDENTITY),changes))
                                    .also { probe() }
                            } finally { output.recycle() }
                        }
                    }
                } finally { ownedOriginal?.recycle() }
            }
        }
    }
}
