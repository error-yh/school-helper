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
        write(byteArrayOf(-1,-38,0,2,-1,-39))
    }.toByteArray()
    private fun chunk(name: String,data: ByteArray): ByteArray = ByteBuffer.allocate(data.size+12).apply {
        val type=name.toByteArray();putInt(data.size);put(type);put(data)
        putInt(CRC32().also { it.update(type);it.update(data) }.value.toInt())
    }.array()
    private fun png(vararg payload: ByteArray): ByteArray = byteArrayOf(-119,80,78,71,13,10,26,10)+chunk("IHDR",ByteArray(13))+payload.fold(byteArrayOf()) { a,b -> a+chunk("eXIf",b) }+chunk("IEND",byteArrayOf())
    private fun read(bytes: ByteArray): Int = ImageContainerOrientation.read(ByteArrayInputStream(bytes),bytes.size.toLong())
    @Test fun rejectsMissingJpegEoiTailWithoutTreatingItAsCompleteEntropyValidation() {
        val complete=jpeg(tiff(6))
        assertEquals(6,read(complete))
        assertThrows(RasterException::class.java) { read(complete.copyOf(complete.size-1)) }
        assertThrows(RasterException::class.java) { read(complete.copyOf(complete.size-2)) }
        // Provider/transport footers may follow EOI; the platform still owns pixel decoding.
        assertEquals(6,read(complete+byteArrayOf(0)))
        // This narrow boundary rule deliberately does not decode/validate entropy itself.
        val spoofed=complete.copyOfRange(0,complete.size-2)+byteArrayOf(1,2,3,-1,-39)
        assertEquals(6,read(spoofed))
    }
    @Test fun acceptsTransportFooterAfterJpegEoiWithoutChangingOrientationOrInput() {
        val complete=jpeg(tiff(8))
        val footer=ByteArray(24) { (it*7+3).toByte() }
        val bytes=complete+footer
        val before=bytes.copyOf()
        assertEquals(8,read(bytes))
        assertArrayEquals(before,bytes)
        // A footer cannot replace the required end-of-image marker.
        assertThrows(RasterException::class.java) { read(complete.copyOf(complete.size-2)+footer) }
    }
    @Test fun readsAllEightDirectionsBothContainersAndByteOrdersWithoutMutatingBytes() {
        for (value in 1..8) for (little in listOf(false,true)) for (bytes in listOf(jpeg(tiff(value,little)),png(tiff(value,little)))) {
            val before=bytes.copyOf();assertEquals(value,read(bytes));assertArrayEquals(before,bytes)
        }
        assertEquals(1,read(jpeg()));assertEquals(1,read(png()))
    }
    @Test fun rejectsInvalidOrientationTypeCountValueAndDuplicateTagOrPayload() {
        for (payload in listOf(tiff(0),tiff(9),tiff(type=4),tiff(count=2),tiff(count=Int.MAX_VALUE))) {
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
