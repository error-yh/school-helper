package com.aggapp.app.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.oned.Code128Writer

/** Pure JVM part of upstream bulk rendering; never clip the encoded bar pattern. */
object BarcodeRaster {
    fun render(content: String, width: Int, height: Int): IntArray? {
        if (content.isEmpty() || width !in 1..4096 || height !in 1..2048 || width.toLong() * height > 8_000_000) return null
        return try {
            val matrix = Code128Writer().encode(content, BarcodeFormat.CODE_128, width, height,
                mapOf(EncodeHintType.MARGIN to 10))
            if (matrix.width != width || matrix.height != height) return null
            val row = IntArray(width) { x -> if (matrix[x, 0]) -16777216 else -1 }
            IntArray(width * height) { row[it % width] }
        } catch (_: IllegalArgumentException) { null }
    }
}
