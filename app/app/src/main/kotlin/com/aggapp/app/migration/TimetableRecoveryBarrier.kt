/* SPDX-License-Identifier: GPL-3.0-only
 * Startup recovery barrier executed before TimetableDatabase and prefs are accessed.
 */
package com.aggapp.app.migration

import android.content.Context
import java.io.File

sealed interface RecoveryResult {
    data object Clean : RecoveryResult
    data class RestoredCommitted(val receipt: MigrationReceipt) : RecoveryResult
    data class RolledBack(val reason: String) : RecoveryResult
    data class Blocked(val error: String) : RecoveryResult
}

object TimetableRecoveryBarrier {

    fun recoverBeforeOpen(context: Context): RecoveryResult {
        return when (val journalRes = TimetableMigrationJournal.readJournal(context)) {
            is JournalReadResult.Corrupt -> {
                // Fail closed: corrupted journal could mean torn uncommitted migration
                RecoveryResult.Blocked("迁移日志损坏，阻止打开数据库以保护数据安全: ${journalRes.error}")
            }
            is JournalReadResult.Absent -> {
                handleNoJournal(context)
            }
            is JournalReadResult.Present -> {
                val journalState = journalRes.state
                if (journalState.stage == MigrationStage.COMMITTED.name) {
                    handleCommittedJournal(context, journalState)
                } else {
                    handleUncommittedCrash(context, journalState)
                }
            }
        }
    }

    private fun handleNoJournal(context: Context): RecoveryResult {
        return when (val receiptRes = TimetableMigrationJournal.readReceipt(context)) {
            is ReceiptReadResult.Present -> {
                val receipt = receiptRes.receipt
                val integrityError = verifyReceiptIntegrity(context, receipt)
                if (integrityError != null) {
                    RecoveryResult.Blocked("迁移收据与实际数据库物理文件核对失败: $integrityError")
                } else {
                    RecoveryResult.RestoredCommitted(receipt)
                }
            }
            is ReceiptReadResult.Corrupt -> RecoveryResult.Blocked("迁移收据损坏: ${receiptRes.error}")
            is ReceiptReadResult.Absent -> RecoveryResult.Clean
        }
    }

    private fun handleCommittedJournal(context: Context, state: MigrationJournalState): RecoveryResult {
        return when (val receiptRes = TimetableMigrationJournal.readReceipt(context)) {
            is ReceiptReadResult.Present -> {
                val receipt = receiptRes.receipt
                if (receipt.contentDigest == state.contentDigest) {
                    val integrityError = verifyReceiptIntegrity(context, receipt)
                    if (integrityError != null) {
                        RecoveryResult.Blocked("COMMITTED迁移收据与实际数据库物理文件核对失败: $integrityError")
                    } else {
                        // Safe idempotency: clean residual committed journal and ownership markers
                        finishCommittedSession(context, state)
                        RecoveryResult.RestoredCommitted(receipt)
                    }
                } else {
                    RecoveryResult.Blocked("迁移收据摘要与日志不匹配 (Receipt: ${receipt.contentDigest}, Journal: ${state.contentDigest})")
                }
            }
            is ReceiptReadResult.Corrupt -> RecoveryResult.Blocked("迁移收据已损坏: ${receiptRes.error}")
            is ReceiptReadResult.Absent -> {
                // Receipt was not written before crash, but journal is COMMITTED.
                // Reconstruct receipt from journal to prevent destructive rollback of committed state.
                val receipt = MigrationReceipt(
                    status = "COMMITTED",
                    contentDigest = state.contentDigest,
                    restoredProfileIds = state.createdDatabaseFiles.map { it.removePrefix("timetable_").removeSuffix(".db") },
                    restoredActiveProfileId = state.targetActiveProfileId,
                    committedAt = state.timestamp,
                )
                val integrityError = verifyReceiptIntegrity(context, receipt)
                if (integrityError != null) {
                    RecoveryResult.Blocked("补建收据与实际数据库物理文件核对失败: $integrityError")
                } else {
                    TimetableMigrationJournal.writeReceipt(context, receipt)
                    if ((TimetableMigrationJournal.readReceipt(context) as? ReceiptReadResult.Present)?.receipt == receipt) {
                        finishCommittedSession(context, state)
                    } else {
                        return RecoveryResult.Blocked("无法确认补建收据已持久化")
                    }
                    RecoveryResult.RestoredCommitted(receipt)
                }
            }
        }
    }

    private fun verifyReceiptIntegrity(context: Context, receipt: MigrationReceipt): String? {
        if (receipt.restoredActiveProfileId.isBlank()) {
            return "收据活动Profile为空"
        }
        val activeDbFile = context.getDatabasePath("timetable_${receipt.restoredActiveProfileId}.db")
        if (!activeDbFile.exists() || activeDbFile.length() == 0L) {
            return "收据指定的活动数据库文件缺失或为空: ${activeDbFile.name}"
        }
        for (pid in receipt.restoredProfileIds) {
            val dbFile = context.getDatabasePath("timetable_$pid.db")
            if (!dbFile.exists() || dbFile.length() == 0L) {
                return "收据指定的Profile数据库文件缺失或为空: ${dbFile.name}"
            }
            try {
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                )
                db.use {
                    it.rawQuery("PRAGMA quick_check;", null).use { cursor ->
                        if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                            return "收据数据库完整性校验失败: ${dbFile.name}"
                        }
                    }
                }
            } catch (e: Exception) {
                return "收据数据库无法打开: ${dbFile.name} (${e.message})"
            }
        }
        return null
    }

    private fun handleUncommittedCrash(context: Context, state: MigrationJournalState): RecoveryResult {
        // Crucial safety guard: If a valid committed receipt already exists for this exact digest,
        // the session actually completed. DO NOT destroy user data.
        val receiptRes = TimetableMigrationJournal.readReceipt(context)
        if (receiptRes is ReceiptReadResult.Present && receiptRes.receipt.contentDigest == state.contentDigest) {
            return handleCommittedJournal(context, state)
        }

        var rollbackSuccess = true
        val errors = mutableListOf<String>()

        // 1. Rollback active profile id
        try {
            val profilePrefs = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
            val editor = profilePrefs.edit()
            if (state.originalActiveProfileId != null) {
                editor.putString("id", state.originalActiveProfileId)
            } else {
                editor.remove("id")
            }
            if (!editor.commit()) {
                rollbackSuccess = false
                errors.add("无法还原 timetable_local_profile")
            }
        } catch (e: Exception) {
            rollbackSuccess = false
            errors.add("还原 timetable_local_profile 异常: ${e.message}")
        }

        // 2. Rollback display group keys using saved previous values (groupBackups)
        try {
            if (state.groupBackups.isNotEmpty()) {
                val groupPrefs = context.getSharedPreferences("course_display_groups", Context.MODE_PRIVATE)
                val editor = groupPrefs.edit()
                for ((key, previousValue) in state.groupBackups) {
                    if (previousValue != null) {
                        editor.putString(key, previousValue)
                    } else {
                        editor.remove(key)
                    }
                }
                if (!editor.commit()) {
                    rollbackSuccess = false
                    errors.add("无法还原 course_display_groups")
                }
            }
        } catch (e: Exception) {
            rollbackSuccess = false
            errors.add("还原 course_display_groups 异常: ${e.message}")
        }

        // The retained hard link proves which file this session published.
        try {
            val dir = context.getDatabasePath("probe.db").parentFile ?: error("缺少数据库目录")
            MigrationPublication.rollback(dir, state.sessionToken, state.createdDatabaseFiles)
            check(cleanStaging(context, state)) { "无法清理迁移暂存目录" }
        } catch (e: Exception) {
            rollbackSuccess = false
            errors.add("迁移文件清理失败: ${e.message}")
        }

        // Delete the journal only after all rollback operations succeed.
        return if (rollbackSuccess) {
            TimetableMigrationJournal.deleteJournal(context)
            RecoveryResult.RolledBack("已成功回滚未完成的迁移会话 (${state.stage})")
        } else {
            val errorMsg = "迁移未提交且自动回滚清理失败: ${errors.joinToString("; ")}"
            RecoveryResult.Blocked(errorMsg)
        }
    }

    internal fun finishCommittedSession(context: Context, state: MigrationJournalState): Boolean = runCatching {
        val dir = context.getDatabasePath("probe.db").parentFile ?: error("缺少数据库目录")
        MigrationPublication.finish(dir, state.sessionToken)
        check(cleanStaging(context, state))
        TimetableMigrationJournal.deleteJournal(context)
        TimetableMigrationJournal.readJournal(context) is JournalReadResult.Absent
    }.getOrDefault(false)

    private fun cleanStaging(context: Context, state: MigrationJournalState): Boolean {
        val path = state.createdStagingDir ?: return true
        val directory = File(path).canonicalFile
        val parent = directory.parentFile
        if (parent != context.filesDir.canonicalFile && parent != context.cacheDir.canonicalFile) return false
        if (!directory.name.startsWith("staging_")) return false
        return !directory.exists() || directory.deleteRecursively()
    }
}
