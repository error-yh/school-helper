/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import java.io.File
import java.io.InputStream

/** Construction is restricted to counted copies; no public arbitrary-file authorization. */
internal class BoundedPrivateRasterSource private constructor(private val file: File, val byteCount: Long) : AutoCloseable {
    private var closed = false
    fun openRead(): InputStream { check(!closed); return file.inputStream() }
    fun openDescriptor(): android.os.ParcelFileDescriptor {
        check(!closed)
        return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun close() { if (!closed) { closed = true; file.delete() } }
    companion object {
        fun copyTo(directory: File, input: InputStream, maxBytes: Int, checkCancellation: () -> Unit = {}): BoundedPrivateRasterSource {
            require(maxBytes in 1..12_000_000)
            var owned: File? = null
            try {
                checkCancellation()
                if (!directory.isDirectory && !directory.mkdirs()) throw RasterException(RasterFailure.RASTER_UNREADABLE)
                val file = File.createTempFile("raster-", ".private", directory); owned = file
                var total = 0L
                file.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        checkCancellation()
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - total + 1).toInt())
                        if (n < 0) break
                        if (n == 0) {
                            val single = input.read(); if (single < 0) break
                            if (total == maxBytes.toLong()) throw RasterException(RasterFailure.LIMIT_EXCEEDED)
                            out.write(single); total++; continue
                        }
                        if (n > maxBytes - total) throw RasterException(RasterFailure.LIMIT_EXCEEDED)
                        out.write(buffer,0,n); total += n
                    }
                }
                checkCancellation()
                if (!file.setReadOnly()) throw RasterException(RasterFailure.RASTER_UNREADABLE)
                return BoundedPrivateRasterSource(file,total).also { owned = null }
            } catch (e: RasterException) { throw e
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: java.io.IOException) { throw RasterException(RasterFailure.RASTER_UNREADABLE)
            } catch (_: RuntimeException) { throw RasterException(RasterFailure.RASTER_UNREADABLE)
            } finally { owned?.delete() }
        }
    }
}
