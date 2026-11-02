/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.ui

import android.content.Context
import java.io.File
import org.json.JSONObject

/** Facts supplied by the screen/ViewModel for one read; never includes user text or names. */
internal data class ImportReadContext(val hasDocumentRequest: Boolean = true, val textKind: String? = null)

/** Bounded, private diagnosis of one loader read. It stays in the app's no-backup directory,
 * is capped and overwritten, and is never written to logcat or returned to the UI.
 * Provider authority, MIME, byte count, private digest and magic bytes only: no path,
 * file name, recognized text or account/device identifier is recorded.
 */
internal object ImportReadDiagnostics {
    private const val MAX_BYTES = 8_192
    private const val DIRECTORY = "import-read-diagnostics"
    private const val FILE = "last-read.json"

    fun record(context: Context, read: ImportReadFacts) {
        runCatching {
            val directory = File(context.noBackupFilesDir, DIRECTORY)
            if (!directory.isDirectory && !directory.mkdirs()) return
            val json = JSONObject()
                .put("requestedKind", read.requestedKind)
                .put("documentRequest", read.hasDocumentRequest)
                .put("textKind", read.textKind)
                .put("uriScheme", read.uriScheme)
                .put("provider", read.provider)
                .put("providerMime", read.mime)
                .put("bytes", read.bytes)
                .put("sha256", read.sha256)
                .put("magic", read.magic)
                .put("content", read.content)
                .put("branch", read.branch)
                .put("stage", read.stage)
            val text = json.toString()
            if (text.length > MAX_BYTES) return
            val target = File(directory, FILE)
            val temporary = File(directory, "$FILE.tmp")
            temporary.writeText(text)
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) temporary.delete()
            }
        }
    }
}

/** One bounded read's non-identifying facts; provider authority is a provider category, not a path. */
internal data class ImportReadFacts(
    val requestedKind: String,
    val hasDocumentRequest: Boolean,
    val textKind: String?,
    val uriScheme: String?,
    val provider: String?,
    val mime: String?,
    val bytes: Long,
    val sha256: String?,
    val magic: String?,
    val content: String?,
    val branch: String?,
    val stage: String,
) {
    companion object {
        const val STAGE_EMPTY = "EMPTY"
        const val STAGE_SNIFF = "SNIFF"
        const val STAGE_IMAGE = "IMAGE"
        const val STAGE_PDF = "PDF"
        const val STAGE_TABLE = "TABLE"
        const val STAGE_TEXT = "TEXT"
        const val STAGE_DECODE = "DECODE"
        const val STAGE_REJECTED = "REJECTED"
        const val STAGE_OK = "OK"
    }
}