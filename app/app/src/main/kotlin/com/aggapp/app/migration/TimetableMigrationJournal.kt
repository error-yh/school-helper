/* SPDX-License-Identifier: GPL-3.0-only
 * Persistent two-phase migration journal and AtomicFile committed receipt.
 * Serialized manually using standard kotlinx.serialization.json without compiler plugin.
 */
package com.aggapp.app.migration

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.serialization.json.*
import java.io.File

enum class MigrationStage {
    PREPARING,
    VALIDATED,
    PUBLISHING,
    COMMITTED
}

data class MigrationJournalState(
    val stage: String,
    val contentDigest: String,
    val targetActiveProfileId: String,
    val originalActiveProfileId: String?,
    val createdDatabaseFiles: List<String>,
    val createdStagingDir: String?,
    val groupBackups: Map<String, String?>,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionToken: String = "",
)

data class MigrationReceipt(
    val status: String = "COMMITTED",
    val contentDigest: String,
    val restoredProfileIds: List<String>,
    val restoredActiveProfileId: String,
    val committedAt: Long = System.currentTimeMillis(),
)

sealed interface JournalReadResult {
    data object Absent : JournalReadResult
    data class Present(val state: MigrationJournalState) : JournalReadResult
    data class Corrupt(val error: String) : JournalReadResult
}

sealed interface ReceiptReadResult {
    data object Absent : ReceiptReadResult
    data class Present(val receipt: MigrationReceipt) : ReceiptReadResult
    data class Corrupt(val error: String) : ReceiptReadResult
}

object TimetableMigrationJournal {

    private const val JOURNAL_FILE_NAME = "migration_journal.json"
    private const val RECEIPT_FILE_NAME = "timetable_migration_receipt.json"

    fun journalFile(context: Context): File = File(context.filesDir, JOURNAL_FILE_NAME)
    fun receiptFile(context: Context): File = File(context.filesDir, RECEIPT_FILE_NAME)

    fun encodeJournal(state: MigrationJournalState): String {
        return buildJsonObject {
            put("stage", state.stage)
            put("contentDigest", state.contentDigest)
            put("targetActiveProfileId", state.targetActiveProfileId)
            if (state.originalActiveProfileId != null) put("originalActiveProfileId", state.originalActiveProfileId) else put("originalActiveProfileId", JsonNull)
            putJsonArray("createdDatabaseFiles") {
                state.createdDatabaseFiles.forEach { add(it) }
            }
            if (state.createdStagingDir != null) put("createdStagingDir", state.createdStagingDir) else put("createdStagingDir", JsonNull)
            putJsonObject("groupBackups") {
                state.groupBackups.forEach { (k, v) ->
                    if (v != null) put(k, v) else put(k, JsonNull)
                }
            }
            put("timestamp", state.timestamp)
            put("sessionToken", state.sessionToken)
        }.toString()
    }

    fun decodeJournal(jsonStr: String): MigrationJournalState {
        val obj = Json.parseToJsonElement(jsonStr).jsonObject
        val stageStr = obj.getValue("stage").jsonPrimitive.content
        require(MigrationStage.entries.any { it.name == stageStr }) { "未知的迁移阶段: $stageStr" }

        val groupBackupsMap = (obj["groupBackups"] as? JsonObject)?.mapValues { (_, v) ->
            if (v is JsonNull) null else v.jsonPrimitive.contentOrNull
        } ?: emptyMap()

        return MigrationJournalState(
            stage = stageStr,
            contentDigest = obj.getValue("contentDigest").jsonPrimitive.content,
            targetActiveProfileId = obj.getValue("targetActiveProfileId").jsonPrimitive.content,
            originalActiveProfileId = obj["originalActiveProfileId"]?.jsonPrimitive?.contentOrNull,
            createdDatabaseFiles = (obj["createdDatabaseFiles"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            createdStagingDir = obj["createdStagingDir"]?.jsonPrimitive?.contentOrNull,
            groupBackups = groupBackupsMap,
            timestamp = obj["timestamp"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis(),
            sessionToken = obj["sessionToken"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    fun encodeReceipt(receipt: MigrationReceipt): String {
        return buildJsonObject {
            put("status", receipt.status)
            put("contentDigest", receipt.contentDigest)
            putJsonArray("restoredProfileIds") {
                receipt.restoredProfileIds.forEach { add(it) }
            }
            put("restoredActiveProfileId", receipt.restoredActiveProfileId)
            put("committedAt", receipt.committedAt)
        }.toString()
    }

    fun decodeReceipt(jsonStr: String): MigrationReceipt {
        val obj = Json.parseToJsonElement(jsonStr).jsonObject
        val statusStr = obj.getValue("status").jsonPrimitive.content
        require(statusStr == "COMMITTED") { "收据状态必须为COMMITTED: $statusStr" }

        return MigrationReceipt(
            status = statusStr,
            contentDigest = obj.getValue("contentDigest").jsonPrimitive.content,
            restoredProfileIds = (obj["restoredProfileIds"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList(),
            restoredActiveProfileId = obj.getValue("restoredActiveProfileId").jsonPrimitive.content,
            committedAt = obj.getValue("committedAt").jsonPrimitive.long,
        )
    }

    fun readJournal(context: Context): JournalReadResult {
        val file = journalFile(context)
        if (!file.exists() && !File(file.path + ".bak").exists()) return JournalReadResult.Absent
        val atomicFile = AtomicFile(file)
        return try {
            atomicFile.openRead().use { stream ->
                val text = stream.bufferedReader(Charsets.UTF_8).readText()
                JournalReadResult.Present(decodeJournal(text))
            }
        } catch (e: Exception) {
            JournalReadResult.Corrupt("读取迁移日志失败: ${e.message}")
        }
    }

    fun writeJournal(context: Context, state: MigrationJournalState) {
        writeAtomic(journalFile(context), encodeJournal(state))
    }

    fun deleteJournal(context: Context) {
        val file = journalFile(context)
        AtomicFile(file).delete()
        MigrationPublication.syncDirectory(context.filesDir)
    }

    fun readReceipt(context: Context): ReceiptReadResult {
        val file = receiptFile(context)
        if (!file.exists() && !File(file.path + ".bak").exists()) return ReceiptReadResult.Absent
        val atomicFile = AtomicFile(file)
        return try {
            atomicFile.openRead().use { stream ->
                val text = stream.bufferedReader(Charsets.UTF_8).readText()
                ReceiptReadResult.Present(decodeReceipt(text))
            }
        } catch (e: Exception) {
            ReceiptReadResult.Corrupt("读取迁移收据失败: ${e.message}")
        }
    }

    fun writeReceipt(context: Context, receipt: MigrationReceipt) {
        writeAtomic(receiptFile(context), encodeReceipt(receipt))
    }

    private fun writeAtomic(file: File, text: String) {
        val atomicFile = AtomicFile(file)
        val stream = atomicFile.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        } catch (e: Exception) {
            atomicFile.failWrite(stream)
            throw e
        }
        atomicFile.finishWrite(stream)
        MigrationPublication.syncDirectory(checkNotNull(file.parentFile))
    }
}
