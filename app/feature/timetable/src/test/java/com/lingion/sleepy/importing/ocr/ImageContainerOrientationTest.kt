/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ImageContainerOrientationTest {
    private fun tiff(value: Int=1, little: Boolean=true, type: Int=3, count: Int=1): ByteArray = ByteBuffer.allocate(26).order(if(little) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN).apply {
        put(if(little) 'I'.code.toByte() else 'M'.code.toByte());put(if(little) 'I'.code.toByte() else 'M'.code.toByte())
        putShort(42);putInt(8);putShort(1);putShort(274);putShort(type.toShort());putInt(count);putShort(value.toShort());putShort(0);putInt(0)
    }.array()
    private fun jpeg(vararg payload: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(-1,-40))
        payload.forEach { val bytes="Exif\u0000\u0000".toByteArray()+it;write(byteArrayOf(-1,-31,(bytes.size+2 ushr 8).toByte(),(bytes.size+2).toByte()));write(bytes) }
        write(byteArrayOf(-1,-38,0,2))
        write(ByteArray(16) { 0x5a })
        write(byteArrayOf(-1,-39))
    }.toByteArray()
    private fun chunk(name: String,data: ByteArray): ByteArray = ByteBuffer.allocate(data.size+12).apply {
        val type=name.toByteArray();putInt(data.size);put(type);put(data)
        putInt(CRC32().also { it.update(type);it.update(data) }.value.toInt())
    }.array()
    private fun png(vararg payload: ByteArray): ByteArray = byteArrayOf(-119,80,78,71,13,10,26,10)+chunk("IHDR",ByteArray(13))+payload.fold(byteArrayOf()) { a,b -> a+chunk("eXIf",b) }+chunk("IEND",byteArrayOf())
    private fun read(bytes: ByteArray): Int = ImageContainerOrientation.read(ByteArrayInputStream(bytes),bytes.size.toLong())
    private fun le32(value: Int) = byteArrayOf(value.toByte(),(value ushr 8).toByte(),(value ushr 16).toByte(),(value ushr 24).toByte())
    private fun riff(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        chunks.forEach { (name, data) ->
            body.write(name.toByteArray()); body.write(le32(data.size)); body.write(data)
            if (data.size % 2 == 1) body.write(0)
        }
        val payload = body.toByteArray()
        return "RIFF".toByteArray() + le32(payload.size + 4) + "WEBP".toByteArray() + payload
    }
    private fun vp8x(flags: Int, width: Int = 100, height: Int = 60): ByteArray = ByteArray(10).also {
        it[0] = flags.toByte()
        val w = width - 1; val h = height - 1
        it[4] = w.toByte(); it[5] = (w ushr 8).toByte(); it[6] = (w ushr 16).toByte()
        it[7] = h.toByte(); it[8] = (h ushr 8).toByte(); it[9] = (h ushr 16).toByte()
    }
    @Test fun acceptsStaticWebpVariantsAndReadsOnlyBoundedExifOrientation() {
        val lossy = riff("VP8 " to byteArrayOf(1,2,3))
        val original = lossy.copyOf()
        assertEquals(1, read(lossy))
        assertArrayEquals(original, lossy)
        assertEquals(6, read(riff("VP8 " to byteArrayOf(1,2,3),"EXIF" to ("Exif\u0000\u0000".toByteArray()+tiff(6)))))
        assertEquals(8, read(riff("VP8X" to vp8x(0x08),"EXIF" to tiff(8),"VP8L" to byteArrayOf(0x2f,1,2))))
        assertEquals(1, read(riff("VP8X" to vp8x(0x10),"ALPH" to byteArrayOf(0),"VP8 " to byteArrayOf(1,2))))
        // A single trailing pad byte after the declared RIFF payload is tolerated.
        assertEquals(1, read(lossy + byteArrayOf(0)))
        // An undefined EXIF orientation stays identity, exactly like JPEG/PNG.
        assertEquals(1, read(riff("VP8 " to byteArrayOf(1),"EXIF" to tiff(0))))
    }
    @Test fun rejectsAnimatedAndMalformedWebpInsteadOfDecodingAnArbitraryFrame() {
        val animatedFlag = riff("VP8X" to vp8x(0x02),"VP8 " to byteArrayOf(1,2))
        val animChunk = riff("VP8X" to vp8x(0x00),"ANIM" to ByteArray(6),"VP8 " to byteArrayOf(1,2))
        val duplicateFrame = riff("VP8 " to byteArrayOf(1,2),"VP8 " to byteArrayOf(3,4))
        val shortVp8x = riff("VP8X" to ByteArray(9))
        val unknownRiff = "RIFF".toByteArray() + le32(8) + "WAVE".toByteArray()
        val truncated = riff("VP8 " to byteArrayOf(1,2)).copyOf(10)
        val wrongDeclaredSize = riff("VP8 " to byteArrayOf(1,2)).also { it[4] = (it[4] + 7).toByte() }
        for (bytes in listOf(animatedFlag,animChunk,duplicateFrame,shortVp8x,unknownRiff,truncated,wrongDeclaredSize)) {
            assertThrows(RasterException::class.java) { read(bytes) }
        }
        assertEquals(RasterFailure.UNSUPPORTED_FORMAT, (runCatching { read(animatedFlag) }.exceptionOrNull() as RasterException).code)
    }
    @Test fun acceptsChatSavedJpegWithoutEoiWhileStructuralGarbageStillRejects() {
        val complete=jpeg(tiff(6))
        assertEquals(6,read(complete))
        // Chat/transport copies routinely drop the two-byte end-of-image marker; the platform still
        // decodes the declared raster, and this reader never claimed to validate entropy.
        assertEquals(6,read(complete.copyOf(complete.size-1)))
        assertEquals(6,read(complete.copyOf(complete.size-2)))
        // Provider/transport footers may follow EOI; the platform still owns pixel decoding.
        assertEquals(6,read(complete+byteArrayOf(0)))
        val spoofed=complete.copyOfRange(0,complete.size-2)+byteArrayOf(1,2,3,-1,-39)
        assertEquals(6,read(spoofed))
        // A header without any scan, and a segment length beyond the file, stay rejected.
        val headerOnly=complete.copyOfRange(0,complete.size-2)
        assertThrows(RasterException::class.java) { read(headerOnly.copyOf(2)) }
        assertThrows(RasterException::class.java) { read(byteArrayOf(-1,-40,-1,-31,127,-1)) }
    }
    @Test fun acceptsTransportFooterAfterJpegEoiWithoutChangingOrientationOrInput() {
        val complete=jpeg(tiff(8))
        val footer=ByteArray(24) { (it*7+3).toByte() }
        val bytes=complete+footer
        val before=bytes.copyOf()
        assertEquals(8,read(bytes))
        assertArrayEquals(before,bytes)
        // Dropping EOI keeps the same bounded IFD result; a footer is never parsed as metadata.
        assertEquals(8,read(complete.copyOf(complete.size-2)+footer))
    }
    @Test fun readsAllEightDirectionsBothContainersAndByteOrdersWithoutMutatingBytes() {
        for (value in 1..8) for (little in listOf(false,true)) for (bytes in listOf(jpeg(tiff(value,little)),png(tiff(value,little)))) {
            val before=bytes.copyOf();assertEquals(value,read(bytes));assertArrayEquals(before,bytes)
        }
        assertEquals(1,read(jpeg()));assertEquals(1,read(png()))
    }
    @Test fun readsUndefinedOrientationAsIdentityAndStillRejectsMalformedIfdEntries() {
        // EXIF defines only 1..8. Encoders that write 0 (or any other value) mean "undefined";
        // identity preserves the real pixels instead of rejecting a decodable image.
        for (payload in listOf(tiff(0),tiff(9),tiff(65535))) assertEquals(1,read(jpeg(payload)))
        for (payload in listOf(tiff(type=4),tiff(count=2),tiff(count=Int.MAX_VALUE))) {
            assertThrows(RasterException::class.java) { read(jpeg(payload)) }
        }
        assertThrows(RasterException::class.java) { read(jpeg(tiff(1),tiff(2))) }
        assertThrows(RasterException::class.java) { read(png(tiff(1),tiff(1))) }
        val duplicate=ByteBuffer.allocate(38).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(tiff().copyOfRange(0,10));putShort(8,2);position(10)
            repeat(2) { putShort(274);putShort(3);putInt(1);putShort(1);putShort(0) };putInt(0)
        }.array()
        assertThrows(RasterException::class.java) { read(png(duplicate)) }
    }
    @Test fun rejectsTruncatedHeadersOffsetTableAndHugeContainerBeforeDeclaredAllocation() {
        for (payload in listOf(byteArrayOf(),tiff().copyOf(7),tiff().copyOf(25),tiff().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4,Int.MAX_VALUE) },tiff().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(8,1025) })) {
            assertThrows(RasterException::class.java) { read(png(payload)) }
        }
        val huge=byteArrayOf(-119,80,78,71,13,10,26,10)+chunk("IHDR",ByteArray(13))+byteArrayOf(127,-1,-1,-1,101,88,73,102)
        assertThrows(RasterException::class.java) { read(huge) }
        assertThrows(RasterException::class.java) { read(png(ByteArray(262_145))) }
        assertThrows(RasterException::class.java) { read(byteArrayOf(-1,-40,-1,-31,127,-1)) }
    }
    @Test fun ignoresUnrelatedComponentCountsAndIndirectPointersWithoutTraversingOrAllocating() {
        for (tag in listOf(271,273,279,513,514,330,34665,34853,40965)) {
            val payload=tiff().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(10,tag.toShort()).putInt(14,Int.MAX_VALUE).putInt(18,Int.MAX_VALUE).putInt(22,Int.MAX_VALUE) }
            assertEquals(1,read(png(payload)))
        }
    }
    @Test fun rejectsBadExifCrcBoundsContainerWorkAndPropagatesCancellation() {
        val corrupt=png(tiff()).also { it[it.size-13]=(it[it.size-13].toInt() xor 1).toByte() }
        assertThrows(RasterException::class.java) { read(corrupt) }
        val many=byteArrayOf(-119,80,78,71,13,10,26,10)+chunk("IHDR",ByteArray(13))+ByteArrayOutputStream().apply { repeat(4096) { write(chunk("tEXt",byteArrayOf())) };write(chunk("IEND",byteArrayOf())) }.toByteArray()
        assertThrows(RasterException::class.java) { read(many) }
        assertThrows(CancellationException::class.java) { ImageContainerOrientation.read(ByteArrayInputStream(png()),png().size.toLong()) { throw CancellationException() } }
    }
}
