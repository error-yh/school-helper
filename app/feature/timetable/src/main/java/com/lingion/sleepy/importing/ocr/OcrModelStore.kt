/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class OcrModelSpec(val filename: String, val bytes: Long, val sha256: String) {
    init {
        require(filename.matches(Regex("(?:[a-z_]+\\.traineddata|[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*\\.onnx)")))
        require(bytes > 0 && bytes <= 20_000_000L)
        require(sha256.matches(Regex("[0-9a-fA-F]{64}")))
    }
}
class OcrModelException : RuntimeException("OCR_MODEL_UNAVAILABLE")

/** Only pinned public model files are stored here; root must be app-private, never a user input directory. */
class OcrModelStore(private val root: File, private val models: List<OcrModelSpec> = BUNDLED_MODELS, private val openAsset: (String) -> InputStream) {
    init { require(models.isNotEmpty() && models.map { it.filename }.distinct().size == models.size) }
    @Synchronized fun prepare(checkCancellation: () -> Unit = {}): File {
        try {
            checkCancellation()
            val tessdata = File(root, "tessdata")
            if (!tessdata.isDirectory && !tessdata.mkdirs()) throw OcrModelException()
            for (model in models) {
                checkCancellation()
                val destination = File(tessdata, model.filename)
                if (verified(destination, model, checkCancellation)) continue
                val temporary = File.createTempFile("ocr-model-", ".tmp", tessdata)
                try {
                    openAsset(model.filename).use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                checkCancellation()
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > model.bytes) throw OcrModelException()
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    if (!verified(temporary, model, checkCancellation)) throw OcrModelException()
                    checkCancellation()
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally { temporary.delete() }
            }
            return root
        } catch (_: IOException) { throw OcrModelException() } catch (_: SecurityException) { throw OcrModelException() }
    }
    private fun verified(file: File, model: OcrModelSpec, checkCancellation: () -> Unit): Boolean {
        if (!file.isFile || file.length() != model.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancellation()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.equals(model.sha256, ignoreCase = true)
    }
    companion object {
        val BUNDLED_MODELS = listOf(
            OcrModelSpec("chi_sim.traineddata", 2_469_156, "a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730"),
            OcrModelSpec("eng.traineddata", 4_113_088, "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"),
        )
    }
}
