/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import java.io.InputStream
import java.util.zip.CRC32

/** Bounded IFD0 orientation only. This deliberately does not validate/decode other EXIF tags. */
internal object ImageContainerOrientation {
    private val pngSignature = byteArrayOf(-119,80,78,71,13,10,26,10)
    private const val MAX_METADATA = 262_144
    fun read(input: InputStream, byteCount: Long, checkCancellation: () -> Unit = {}): Int {
        if (byteCount > 12_000_000) throw RasterException(RasterFailure.LIMIT_EXCEEDED)
        if (byteCount < 1) throw RasterException(RasterFailure.INVALID_IMAGE)
        val reader = Counted(input,byteCount,checkCancellation)
        val first = reader.byte(); val second = reader.byte()
        return when {
            first == 255 && second == 216 -> jpeg(reader)
            first == 137 && second == 80 -> {
                for (index in 2..7) if (reader.byte() != (pngSignature[index].toInt() and 255)) invalid(RasterFailure.UNSUPPORTED_FORMAT)
                png(reader)
            }
            else -> invalid(RasterFailure.UNSUPPORTED_FORMAT)
        }
    }
    private fun jpeg(reader: Counted): Int {
        var exifSeen = false; var orientation = 1
        repeat(4096) {
            if (reader.byte() != 255) invalid()
            var marker = reader.byte()
            // Marker padding is actual counted input, also capped as work.
            var padding = 0
            while (marker == 255) { if (++padding > 4096) limit(); marker = reader.byte() }
            if (marker == 0 || marker == 216) invalid()
            if (marker == 217) return orientation
            if (marker == 1 || marker in 208..215) return@repeat
            val length = (reader.byte() shl 8 or reader.byte()) - 2
            if (length < 0 || length.toLong() > reader.remaining) invalid()
            if (marker == 218) {
                reader.skip(length.toLong())
                // EOI need not be the final file bytes: downloaded JPEGs can carry a
                // transport footer. Inspect only a bounded suffix for the marker; this
                // is not entropy validation. BitmapFactory still decodes the pixels.
                if (reader.remaining < 2) invalid()
                val suffixLength = minOf(reader.remaining, MAX_METADATA.toLong()).toInt()
                reader.skip(reader.remaining-suffixLength)
                val suffix = reader.bytes(suffixLength)
                if ((0 until suffix.lastIndex).none { suffix[it] == (-1).toByte() && suffix[it+1] == (-39).toByte() }) invalid()
                return orientation
            }
            if (marker == 225 && length >= 6) {
                val prefix = reader.bytes(6)
                if (prefix.contentEquals(byteArrayOf(69,120,105,102,0,0))) {
                    if (exifSeen) invalid(); exifSeen = true
                    orientation = ifd0(reader.bytes(length-6))
                } else reader.skip((length-6).toLong())
            } else reader.skip(length.toLong())
        }
        limit()
    }
    private fun png(reader: Counted): Int {
        var exifSeen = false; var orientation = 1
        repeat(4096) { index ->
            val length = reader.u32()
            val type = reader.bytes(4)
            if (length > reader.remaining - 4) invalid()
            val name = type.toString(Charsets.US_ASCII)
            if (index == 0 && (name != "IHDR" || length != 13L) || index > 0 && name == "IHDR") invalid()
            when (name) {
                "eXIf" -> {
                    if (exifSeen) invalid(); exifSeen = true
                    if (length > MAX_METADATA) limit()
                    val data = reader.bytes(length.toInt())
                    val crc = CRC32().also { it.update(type); it.update(data) }.value
                    if (reader.u32() != crc) invalid()
                    orientation = ifd0(data)
                }
                "IEND" -> {
                    if (length != 0L) invalid()
                    reader.skip(4)
                    if (reader.remaining != 0L) invalid()
                    return orientation
                }
                else -> { reader.skip(length); reader.skip(4) }
            }
        }
        limit()
    }
    /** No IFD traversal, unrelated value decode, thumbnail reading, or component-sized allocation.
     * Only fixed table ranges and the one inline SHORT are inspected, in the bounded payload.
     */
    private fun ifd0(data: ByteArray): Int {
        if (data.size < 8) invalid()
        val little = when {
            data[0] == 73.toByte() && data[1] == 73.toByte() -> true
            data[0] == 77.toByte() && data[1] == 77.toByte() -> false
            else -> invalid()
        }
        fun u16(offset: Int): Int {
            val a = data[offset].toInt() and 255; val b = data[offset+1].toInt() and 255
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(offset: Int): Long = if (little) u16(offset).toLong() or (u16(offset+2).toLong() shl 16)
            else (u16(offset).toLong() shl 16) or u16(offset+2).toLong()
        if (u16(2) != 42) invalid()
        val offset = u32(4)
        if (offset < 8 || offset > data.size.toLong()-2) invalid()
        val count = u16(offset.toInt())
        if (count > 1024) limit()
        val end = offset + 2L + count.toLong()*12 + 4
        if (end > data.size) invalid()
        var found = false; var orientation = 1
        for (entry in 0 until count) {
            val position = (offset + 2 + entry*12L).toInt()
            if (u16(position) != 274) continue
            if (found || u16(position+2) != 3 || u32(position+4) != 1L) invalid()
            found = true; orientation = u16(position+8)
            if (orientation !in 1..8) invalid()
        }
        return orientation
    }
    private class Counted(val input: InputStream, var remaining: Long, val probe: () -> Unit) {
        private val skipBuffer = ByteArray(8192)
        fun byte(): Int {
            probe(); if (remaining < 1) invalid()
            val value = input.read(); if (value < 0) invalid(); remaining--; return value
        }
        fun u32(): Long = (byte().toLong() shl 24) or (byte().toLong() shl 16) or (byte().toLong() shl 8) or byte().toLong()
        fun bytes(length: Int): ByteArray {
            probe(); if (length < 0 || length > MAX_METADATA || length.toLong() > remaining) invalid()
            val bytes = ByteArray(length); var position = 0
            while (position < length) {
                probe()
                val count = input.read(bytes,position,minOf(8192,length-position))
                if (count < 0) invalid()
                if (count == 0) { bytes[position++] = byte().toByte() } else { position += count; remaining -= count }
            }
            return bytes
        }
        fun skip(length: Long) {
            if (length < 0 || length > remaining) invalid()
            var left = length
            while (left > 0) {
                probe(); val count = input.read(skipBuffer,0,minOf(left,skipBuffer.size.toLong()).toInt())
                if (count < 0) invalid()
                if (count == 0) { byte(); left-- } else { left -= count; remaining -= count }
            }
        }
    }
    private fun invalid(code: RasterFailure = RasterFailure.INVALID_IMAGE): Nothing = throw RasterException(code)
    private fun limit(): Nothing = throw RasterException(RasterFailure.LIMIT_EXCEEDED)
}
