/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RasterPreprocessAndroidTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun fixture(format: Bitmap.CompressFormat, orientation: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(90, 60, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = android.graphics.Paint()
        listOf(Triple(0f, 0f, Color.RED), Triple(60f, 0f, Color.GREEN), Triple(0f, 30f, Color.BLUE), Triple(60f, 30f, Color.YELLOW)).forEach { (x,y,c) ->
            paint.color = c; canvas.drawRect(x,y,x+30,y+30,paint)
        }
        try {
            val original=ByteArrayOutputStream().also { bitmap.compress(format,100,it) }.toByteArray()
            val tiff=ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
                put('I'.code.toByte());put('I'.code.toByte());putShort(42);putInt(8);putShort(1)
                putShort(274);putShort(3);putInt(1);putShort(orientation.toShort());putShort(0);putInt(0)
            }.array()
            return if(format==Bitmap.CompressFormat.JPEG) {
                val exif="Exif\u0000\u0000".toByteArray()+tiff
                original.copyOfRange(0,2)+byteArrayOf(-1,-31,((exif.size+2) ushr 8).toByte(),(exif.size+2).toByte())+exif+original.copyOfRange(2,original.size)
            } else {
                val type="eXIf".toByteArray()
                val chunk=ByteBuffer.allocate(tiff.size+12).apply {
                    putInt(tiff.size);put(type);put(tiff);putInt(CRC32().also { it.update(type);it.update(tiff) }.value.toInt())
                }.array()
                original.copyOfRange(0,33)+chunk+original.copyOfRange(33,original.size)
            }
        } finally { bitmap.recycle() }
    }
    @Test fun jpegAndPngAllEightOrientationsHaveExpectedCornersAndCanonicalFrame() = runBlocking {
        val expected = listOf(listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW), listOf(Color.GREEN,Color.RED,Color.YELLOW,Color.BLUE),
            listOf(Color.YELLOW,Color.BLUE,Color.GREEN,Color.RED), listOf(Color.BLUE,Color.YELLOW,Color.RED,Color.GREEN),
            listOf(Color.RED,Color.BLUE,Color.GREEN,Color.YELLOW), listOf(Color.BLUE,Color.RED,Color.YELLOW,Color.GREEN),
            listOf(Color.YELLOW,Color.GREEN,Color.BLUE,Color.RED), listOf(Color.GREEN,Color.YELLOW,Color.RED,Color.BLUE))
        for (format in listOf(Bitmap.CompressFormat.JPEG, Bitmap.CompressFormat.PNG)) for (orientation in 1..8) {
            val bytes = fixture(format, orientation); val original = bytes.copyOf()
            AndroidImportImageDecoder(context).withDecoded(ByteArrayInputStream(bytes), RasterResources(OcrWorkBudget())) { decoded ->
                val b=decoded.page.bitmap
                assertEquals(if (orientation >= 5) 60 to 90 else 90 to 60, b.width to b.height)
                assertEquals(b.width.toFloat(),decoded.page.originalWidth)
                assertEquals(PageAffineTransform.IDENTITY,decoded.page.rasterToPage)
                val actual=listOf(b.getPixel(10,10),b.getPixel(b.width-11,10),b.getPixel(10,b.height-11),b.getPixel(b.width-11,b.height-11))
                actual.zip(expected[orientation-1]).forEach { (a,e) ->
                    assertTrue(kotlin.math.abs(Color.red(a)-Color.red(e))<25 && kotlin.math.abs(Color.green(a)-Color.green(e))<25 && kotlin.math.abs(Color.blue(a)-Color.blue(e))<25)
                }
            }
            assertArrayEquals(original,bytes)
        }
    }
    @Test fun transparentOddImageCompositesWhiteAndScopedOwnerWaitsForConsumer() = runBlocking {
        val b=Bitmap.createBitmap(91,61,Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.TRANSPARENT); b.setPixel(3,4,Color.BLACK)
        val bytes=ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray(); b.recycle()
        val resources=RasterResources(OcrWorkBudget())
        var owned: Bitmap?=null
        AndroidImportImageDecoder(context).withDecoded(ByteArrayInputStream(bytes), resources) { decoded ->
            owned=decoded.page.bitmap
            assertEquals(Color.WHITE,owned!!.getPixel(0,0)); assertEquals(Color.BLACK,owned!!.getPixel(3,4))
            assertTrue(RasterPixelChange.WHITE_BACKGROUND in decoded.pixelChanges)
            delay(10); assertFalse(owned!!.isRecycled)
            val before=IntArray(91*61); owned!!.getPixels(before,0,91,0,0,91,61)
            TableLineDetector().detect(decoded.page,resources)
            val after=IntArray(91*61); owned!!.getPixels(after,0,91,0,0,91,61)
            assertArrayEquals(before,after)
        }
        assertTrue(owned!!.isRecycled); assertEquals(0L,resources.liveBitmapBytes)
    }
    @Test fun invalidSignatureOversizeAndBudgetFailWithoutLeakingOrClosingCaller() = runBlocking {
        val decoder=AndroidImportImageDecoder(context)
        val bytes=fixture(Bitmap.CompressFormat.PNG,1)
        var closed=false
        val input=object:ByteArrayInputStream(bytes) { override fun close() { closed=true } }
        for (resources in listOf(RasterResources(OcrWorkBudget(1)),RasterResources(OcrWorkBudget(),RasterLimits(maxPixels=100)))) {
            input.reset()
            try { decoder.withDecoded(input,resources) { fail("must reject") }; fail("must reject") } catch (e:RasterException) { assertEquals(RasterFailure.LIMIT_EXCEEDED,e.code) }
            assertEquals(0L,resources.liveBitmapBytes)
        }
        assertFalse(closed)
        for (bad in listOf(byteArrayOf(),byteArrayOf(0xff.toByte(),0xd8.toByte(),0xff.toByte()),"fake.png".toByteArray())) {
            try { decoder.withDecoded(ByteArrayInputStream(bad),RasterResources(OcrWorkBudget())) { fail("must reject") }; fail("must reject") } catch (e:RasterException) { assertTrue(e.code in setOf(RasterFailure.INVALID_IMAGE,RasterFailure.UNSUPPORTED_FORMAT)) }
        }
    }
    @Test fun undefinedExifOrientationKeepsTheDeclaredRasterUnrotated() = runBlocking {
        val expected=listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW)
        for (declared in listOf(0,9,0xFFFF)) {
            val bytes=fixture(Bitmap.CompressFormat.JPEG,declared)
            val resources=RasterResources(OcrWorkBudget())
            AndroidImportImageDecoder(context).withDecoded(ByteArrayInputStream(bytes),resources) { decoded ->
                val b=decoded.page.bitmap
                assertEquals(90 to 60,b.width to b.height)
                assertEquals(90f,decoded.page.originalWidth);assertEquals(60f,decoded.page.originalHeight)
                assertTrue(RasterPixelChange.EXIF_ORIENTATION !in decoded.pixelChanges)
                val actual=listOf(b.getPixel(10,10),b.getPixel(b.width-11,10),b.getPixel(10,b.height-11),b.getPixel(b.width-11,b.height-11))
                actual.zip(expected).forEach { (a,e) ->
                    assertTrue(kotlin.math.abs(Color.red(a)-Color.red(e))<25 && kotlin.math.abs(Color.green(a)-Color.green(e))<25 && kotlin.math.abs(Color.blue(a)-Color.blue(e))<25)
                }
            }
            assertEquals(0L,resources.liveBitmapBytes)
        }
    }
    @Test fun jpegScanTruncationAndTransportFooterCompatibilityAreSeparate() = runBlocking {
        val bytes=fixture(Bitmap.CompressFormat.JPEG,1)
        val withFooter=bytes+ByteArray(24) { (it*7+3).toByte() }
        val footerResources=RasterResources(OcrWorkBudget())
        AndroidImportImageDecoder(context).withDecoded(ByteArrayInputStream(withFooter),footerResources) { decoded ->
            assertEquals(90,decoded.page.bitmap.width);assertEquals(60,decoded.page.bitmap.height)
        }
        assertEquals(0L,footerResources.liveBitmapBytes)
        val sos=(0 until bytes.size-3).first { (bytes[it].toInt() and 255)==255 && (bytes[it+1].toInt() and 255)==218 }
        val length=(bytes[sos+2].toInt() and 255 shl 8) or (bytes[sos+3].toInt() and 255)
        val entropy=sos+2+length
        // A scan without any entropy data stays rejected; the decoder never sees a fake raster.
        val headerOnly=bytes.copyOf(entropy)
        val headerResources=RasterResources(OcrWorkBudget())
        try {
            AndroidImportImageDecoder(context).withDecoded(ByteArrayInputStream(headerOnly),headerResources) { fail("must reject empty scan") }
            fail("must reject empty scan")
        } catch (e:RasterException) { assertEquals(RasterFailure.INVALID_IMAGE,e.code) }
        assertEquals(0L,headerResources.workBudget.consumedPixels);assertEquals(0L,headerResources.liveBitmapBytes)
        // A chat/transport copy that lost only the end-of-image marker still carries the declared
        // raster; the platform decodes the remaining scan data and bounds stay exact.
        val withoutEoi=bytes.copyOf(bytes.size-2)
        val bounds=android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds=true }
        android.graphics.BitmapFactory.decodeByteArray(withoutEoi,0,withoutEoi.size,bounds)
        assertEquals(90,bounds.outWidth);assertEquals(60,bounds.outHeight)
        val resources=RasterResources(OcrWorkBudget())
        var released=0L
        AndroidImportImageDecoder(context).withDecoded(ByteArrayInputStream(withoutEoi),resources) { decoded ->
            assertEquals(90,decoded.page.bitmap.width);assertEquals(60,decoded.page.bitmap.height)
            assertTrue(resources.liveBitmapBytes>0)
            released=resources.liveBitmapBytes
        }
        assertTrue(released>0);assertEquals(0L,resources.liveBitmapBytes)
        assertTrue(resources.workBudget.consumedPixels>0)
    }
    private fun pdf(rotations: List<Int>, encrypted: Boolean=false, pages: Int=7): ByteArray {
        PDFBoxResourceLoader.init(context)
        return PDDocument().use { doc ->
            repeat(pages) { index ->
                val page=PDPage(PDRectangle(160f,260f)); page.cropBox=PDRectangle(10.25f,20.5f,100.5f,200.75f)
                page.rotation=rotations[index % rotations.size]; doc.addPage(page)
                PDPageContentStream(doc,page).use { stream ->
                    if (index+1 !in setOf(2,7)) {
                        stream.beginText();stream.setFont(PDType1Font.HELVETICA,12f);stream.newLineAtOffset(20f,150f)
                        stream.showText("Synthetic reliable page content for extraction");stream.endText()
                    }
                    stream.setNonStrokingColor(255,0,0);stream.addRect(10.25f,201.25f,20f,20f);stream.fill()
                    stream.setNonStrokingColor(0,255,0);stream.addRect(90.75f,201.25f,20f,20f);stream.fill()
                    stream.setNonStrokingColor(0,0,255);stream.addRect(10.25f,20.5f,20f,20f);stream.fill()
                    stream.setNonStrokingColor(255,255,0);stream.addRect(90.75f,20.5f,20f,20f);stream.fill()
                }
            }
            if(encrypted) doc.protect(StandardProtectionPolicy("synthetic-owner","",AccessPermission().also { it.setCanExtractContent(false) }).also { it.encryptionKeyLength=128 })
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }
    @Test fun selectedRealPdfPagesAndFractionalCropRotationCornersUseExactPointFrame() = runBlocking {
        val expected=listOf(listOf(Color.RED,Color.GREEN,Color.BLUE,Color.YELLOW),listOf(Color.BLUE,Color.RED,Color.YELLOW,Color.GREEN),listOf(Color.YELLOW,Color.BLUE,Color.GREEN,Color.RED),listOf(Color.GREEN,Color.YELLOW,Color.RED,Color.BLUE))
        for ((rotationIndex,rotation) in listOf(0,90,180,270).withIndex()) {
            val numbers=mutableListOf<Int>(); val resources=RasterResources(OcrWorkBudget())
            val assessment=AndroidPdfOcrRenderer(context).withRenderedPages(ByteArrayInputStream(pdf(listOf(rotation))),listOf(2,7),resources) { page ->
                numbers+=page.pageNumber
                val b=page.bitmap; val swapped=rotation%180!=0
                assertEquals(if(swapped) 200.75f else 100.5f,page.originalWidth)
                assertEquals(if(swapped) 100.5f else 200.75f,page.originalHeight)
                val corners=listOf(b.getPixel(10,10),b.getPixel(b.width-11,10),b.getPixel(10,b.height-11),b.getPixel(b.width-11,b.height-11))
                assertEquals(expected[rotationIndex],corners)
                val frame=page.rasterToPage.mapBox(RasterBox(0,0,b.width,b.height),page.originalWidth,page.originalHeight)!!
                assertEquals(page.originalWidth,frame.right,0.001f);assertEquals(page.originalHeight,frame.bottom,0.001f)
                assertTrue(resources.liveBitmapBytes>0)
            }
            assertEquals(listOf(2,7),numbers);assertEquals(setOf(2,7),assessment.pagesNeedingOcr)
            assertEquals(7,assessment.document!!.pages.size);assertEquals(0L,resources.liveBitmapBytes)
        }
    }
    @Test fun restrictedCorruptPageLimitAndInvalidSelectionNeverDeliverRaster() = runBlocking {
        val renderer=AndroidPdfOcrRenderer(context)
        for ((bytes,selection) in listOf(pdf(listOf(0),true) to listOf(2),"broken".toByteArray() to listOf(1),pdf(listOf(0),pages=31) to listOf(2),pdf(listOf(0)) to listOf(1),pdf(listOf(0)) to listOf(2,2),pdf(listOf(0)) to listOf(8))) {
            try { renderer.withRenderedPages(ByteArrayInputStream(bytes),selection,RasterResources(OcrWorkBudget())) { fail("must reject") };fail("must reject") }
            catch(e:RasterException) { assertTrue(e.code in setOf(RasterFailure.PDF_REJECTED,RasterFailure.LIMIT_EXCEEDED)) }
        }
    }
    @Test fun cancelledConsumerReleasesRasterAndPrivateStagingAfterActualReturn() = runBlocking {
        val bytes=fixture(Bitmap.CompressFormat.PNG,1);val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var owned:Bitmap?=null
        val resources=RasterResources(OcrWorkBudget())
        val job=launch {
            AndroidImportImageDecoder(context).withDecoded(ByteArrayInputStream(bytes),resources) { decoded ->
                owned=decoded.page.bitmap;entered.complete(Unit)
                withContext(NonCancellable) { release.await();assertFalse(owned!!.isRecycled) }
            }
        }
        entered.await();job.cancel();assertFalse(owned!!.isRecycled);release.complete(Unit);job.join()
        assertTrue(owned!!.isRecycled);assertEquals(0L,resources.liveBitmapBytes)
    }
    @Test fun staticWebpVariantsDecodeWithinBudgetWhileAnimatedAndMalformedReject() = runBlocking {
        fun encode(format: String, alpha: Boolean): ByteArray {
            val bitmap=Bitmap.createBitmap(90,60,Bitmap.Config.ARGB_8888)
            try {
                val canvas=Canvas(bitmap);val paint=android.graphics.Paint()
                if(alpha) { bitmap.eraseColor(Color.TRANSPARENT);paint.color=Color.RED;canvas.drawRect(0f,0f,45f,60f,paint) }
                else { canvas.drawColor(Color.WHITE);paint.color=Color.RED;canvas.drawRect(0f,0f,45f,60f,paint) }
                val out=ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.valueOf(format),100,out))
                return out.toByteArray()
            } finally { bitmap.recycle() }
        }
        for(format in listOf("WEBP","WEBP_LOSSLESS")) {
            val resources=RasterResources(OcrWorkBudget())
            AndroidImportImageDecoder(context).withDecoded(ByteArrayInputStream(encode(format,false)),resources) { decoded ->
                val b=decoded.page.bitmap
                assertEquals(90,b.width);assertEquals(60,b.height)
                assertTrue("format=$format left=${Integer.toHexString(b.getPixel(10,10))} right=${Integer.toHexString(b.getPixel(80,10))}",
                    kotlin.math.abs(Color.red(b.getPixel(10,10))-Color.red(Color.RED))<40 && Color.green(b.getPixel(10,10))<60 && Color.blue(b.getPixel(10,10))<60)
                assertTrue("format=$format right=${Integer.toHexString(b.getPixel(80,10))}",
                    kotlin.math.abs(Color.red(b.getPixel(80,10))-Color.red(Color.WHITE))<15 && kotlin.math.abs(Color.green(b.getPixel(80,10))-Color.green(Color.WHITE))<15)
                assertEquals(PageAffineTransform.IDENTITY,decoded.page.rasterToPage)
            }
            assertEquals(0L,resources.liveBitmapBytes)
        }
        // Transparent static WebP gets the same white background as P/PNG.
        val alphaResources=RasterResources(OcrWorkBudget())
        AndroidImportImageDecoder(context).withDecoded(ByteArrayInputStream(encode("WEBP",true)),alphaResources) { decoded ->
            assertTrue(RasterPixelChange.WHITE_BACKGROUND in decoded.pixelChanges)
            assertEquals(90,decoded.page.bitmap.width)
        }
        assertEquals(0L,alphaResources.liveBitmapBytes)
        // Animated WebP is refused before any frame is picked.
        try {
            AndroidImportImageDecoder(context).withDecoded(ByteArrayInputStream(animatedWebp()),RasterResources(OcrWorkBudget())) { fail("must reject animation") }
            fail("must reject animation")
        } catch(e:RasterException) { assertEquals(RasterFailure.UNSUPPORTED_FORMAT,e.code) }
        // Truncated/mis-sized containers are damaged content, not decodable images.
        val broken=encode("WEBP",false)
        for(bytes in listOf(broken.copyOf(broken.size/2),broken.copyOf().also { it[4]=(it[4]+9).toByte() })) {
            try {
                AndroidImportImageDecoder(context).withDecoded(ByteArrayInputStream(bytes),RasterResources(OcrWorkBudget())) { fail("must reject damaged WebP") }
                fail("must reject damaged WebP")
            } catch(e:RasterException) { assertEquals(RasterFailure.INVALID_IMAGE,e.code) }
        }
    }
    private fun le32(value:Int)=byteArrayOf(value.toByte(),(value ushr 8).toByte(),(value ushr 16).toByte(),(value ushr 24).toByte())
    private fun riffChunk(name:String,data:ByteArray):ByteArray {
        val padded=if(data.size%2==0) data else data+0
        return name.toByteArray()+le32(padded.size)+padded
    }
    private fun animatedWebp():ByteArray {
        val vp8x=ByteArray(10).also { it[0]=0x02 }
        val body=riffChunk("VP8X",vp8x)+riffChunk("ANIM",ByteArray(6))+riffChunk("VP8 ",byteArrayOf(1,2,3,4))
        return "RIFF".toByteArray()+le32(body.size+4)+"WEBP".toByteArray()+body
    }
}

