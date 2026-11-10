/* SPDX-License-Identifier: GPL-3.0-only
 * Top-level timetable backup, inspection, and safe restoration service.
 */
package com.aggapp.app.migration

import android.content.Context
import android.net.Uri
import com.lingion.sleepy.data.backup.ProfileSummary
import com.lingion.sleepy.data.backup.TIMETABLE_BACKUP_FORMAT_VERSION
import com.lingion.sleepy.data.backup.TIMETABLE_BACKUP_MAX_CONTAINER_BYTES
import com.lingion.sleepy.data.backup.TimetableBackupDigest
import com.lingion.sleepy.data.backup.TimetableBackupJsonCodec
import com.lingion.sleepy.data.backup.TimetableBackupPayloadDto
import com.lingion.sleepy.data.backup.TimetableBackupPreview
import com.lingion.sleepy.data.backup.TimetableBackupValidator
import com.lingion.sleepy.data.backup.TimetableConfirmationToken
import com.lingion.sleepy.data.backup.TimetableSnapshotReader
import com.lingion.sleepy.data.backup.TimetableStagingService
import com.lingion.sleepy.data.backup.TimetableWriteCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

sealed interface ExportResult {
    data class Success(val fileSize: Long, val profileCount: Int, val rowCount: Int, val contentDigest: String) : ExportResult
    data class Failed(val reason: String) : ExportResult
}

sealed interface ImportPreviewResult {
    data class Success(val preview: TimetableBackupPreview, val token: TimetableConfirmationToken) : ImportPreviewResult
    data class Failed(val reason: String) : ImportPreviewResult
}

sealed interface RestoreResult {
    data class Success(val profileCount: Int, val totalRows: Int, val activeProfileId: String) : RestoreResult
    data object AlreadyImported : RestoreResult
    data class Rejected(val reason: String) : RestoreResult
    data class Failed(val reason: String) : RestoreResult
}

object TimetableMigrationService {

    suspend fun export(context: Context, destinationUri: Uri, passphrase: CharArray): ExportResult = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "export_temp_${UUID.randomUUID()}.agt")
        try {
            require(passphrase.isNotEmpty()) { "密码不能为空" }

            // 1. Capture snapshot under write coordinator lease
            val payload = TimetableSnapshotReader.captureSnapshot(context)

            // 2. Encode to strict canonical JSON
            val jsonString = TimetableBackupJsonCodec.encode(payload)

            // 3. Encrypt into binary container
            val containerBytes = TimetableEncryption.encrypt(jsonString.toByteArray(Charsets.UTF_8), passphrase)

            // 4. Safely write to temporary private file first
            tempFile.outputStream().use { it.write(containerBytes) }

            // 5. Verification on temporary file (self-decrypt and digest check) before writing to destination
            val decryptedBytes = TimetableEncryption.decrypt(tempFile.readBytes(), passphrase)
            val decodedPayload = TimetableBackupJsonCodec.decode(String(decryptedBytes, Charsets.UTF_8))
            val recalculatedDigest = TimetableBackupDigest.calculateDigest(decodedPayload)
            check(recalculatedDigest == payload.contentDigest) {
                "导出自检失败: 生成的加密备份自解密摘要不一致 (原: ${payload.contentDigest}, 自检: $recalculatedDigest)"
            }

            // 6. Stream from temp file to SAF destinationUri
            context.contentResolver.openOutputStream(destinationUri)?.use { outStream ->
                FileInputStream(tempFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
            } ?: throw IllegalStateException("无法打开目标文件输出流")

            // 7. Re-open destinationUri for read-back verification (Magic/bytes and digest check)
            val readBackBytes = context.contentResolver.openInputStream(destinationUri)?.use { stream ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16384)
                var total = 0
                var read: Int
                while (stream.read(chunk).also { read = it } != -1) {
                    total += read
                    require(total <= TIMETABLE_BACKUP_MAX_CONTAINER_BYTES) { "导出的文件体积超过限制: $total" }
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            } ?: throw IllegalStateException("导出后无法重新打开目标文件进行读回验证: $destinationUri")

            check(readBackBytes.size == containerBytes.size) {
                "导出读回体积不一致: 期望 ${containerBytes.size} 字节, 实际 ${readBackBytes.size} 字节"
            }
            check(readBackBytes.contentEquals(containerBytes)) {
                "导出读回内容字节与写入内容不一致"
            }

            val readBackDecrypted = TimetableEncryption.decrypt(readBackBytes, passphrase)
            val readBackPayload = TimetableBackupJsonCodec.decode(String(readBackDecrypted, Charsets.UTF_8))
            val readBackDigest = TimetableBackupDigest.calculateDigest(readBackPayload)
            check(readBackDigest == payload.contentDigest) {
                "导出读回解密摘要校验失败 (期望: ${payload.contentDigest}, 读回: $readBackDigest)"
            }

            ExportResult.Success(
                fileSize = containerBytes.size.toLong(),
                profileCount = payload.profiles.size,
                rowCount = payload.totalRowCount(),
                contentDigest = payload.contentDigest,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ExportResult.Failed(e.message ?: "导出失败")
        } finally {
            passphrase.fill('\u0000')
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    suspend fun inspect(context: Context, sourceUri: Uri, passphrase: CharArray): ImportPreviewResult = withContext(Dispatchers.IO) {
        try {
            val bytes = readUriBytesWithLimit(context, sourceUri, TIMETABLE_BACKUP_MAX_CONTAINER_BYTES)
            val plaintext = TimetableEncryption.decrypt(bytes, passphrase)
            val jsonString = String(plaintext, Charsets.UTF_8)
            val payload = TimetableBackupJsonCodec.decode(jsonString)

            TimetableBackupValidator.validate(payload)

            val calculatedDigest = TimetableBackupDigest.calculateDigest(payload)
            require(calculatedDigest == payload.contentDigest) {
                "备份内容摘要校验失败: 声明=${payload.contentDigest}, 计算=$calculatedDigest"
            }

            val warnings = mutableListOf<String>()

            // Check if already imported
            val receiptRes = TimetableMigrationJournal.readReceipt(context)
            if (receiptRes is ReceiptReadResult.Present && receiptRes.receipt.contentDigest == payload.contentDigest) {
                warnings.add("该备份已于此前成功导入 (Digest: ${receiptRes.receipt.contentDigest})")
            }

            // Check if target has existing non-empty user schedules or colliding databases
            if (hasExistingUserData(context)) {
                warnings.add("目标环境已存在课表数据；按安全契约，本次导入将被拒绝，以防止覆盖既有数据。")
            } else {
                for (p in payload.profiles) {
                    val dbFile = context.getDatabasePath("timetable_${p.profileId}.db")
                    if (dbFile.exists()) {
                        warnings.add("目标环境已存在同名数据库文件 (timetable_${p.profileId}.db)，恢复将被拒绝。")
                        break
                    }
                }
            }

            val profileSummaries = payload.profiles.map { p ->
                ProfileSummary(
                    profileId = p.profileId,
                    semesterCount = p.semesters.size,
                    courseCount = p.scheduledCourses.size,
                    meetingCount = p.courseMeetings.size,
                    exceptionCount = p.meetingExceptions.size,
                    eventCount = p.noticeAdjustmentEvents.size,
                    displayGroupCount = p.displayGroups.size,
                )
            }

            val totalCourses = payload.profiles.sumOf { it.scheduledCourses.size }
            val totalMeetings = payload.profiles.sumOf { it.courseMeetings.size }
            val totalExceptions = payload.profiles.sumOf { it.meetingExceptions.size }
            val totalEvents = payload.profiles.sumOf { it.noticeAdjustmentEvents.size }

            val preview = TimetableBackupPreview(
                formatVersion = payload.formatVersion,
                sourceAppId = payload.sourceAppId,
                sourceVersionCode = payload.sourceVersionCode,
                createdAt = payload.createdAt,
                activeProfileId = payload.activeProfileId,
                contentDigest = payload.contentDigest,
                profileSummaries = profileSummaries,
                totalCourses = totalCourses,
                totalMeetings = totalMeetings,
                totalExceptions = totalExceptions,
                totalAdjustmentEvents = totalEvents,
                warnings = warnings,
            )

            val token = TimetableConfirmationToken(
                contentDigest = payload.contentDigest,
                targetStateHash = computeTargetStateHash(context),
                formatVersion = payload.formatVersion,
            )

            ImportPreviewResult.Success(preview, token)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ImportPreviewResult.Failed(e.message ?: "备份文件预检失败")
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun restore(
        context: Context,
        sourceUri: Uri,
        passphrase: CharArray,
        token: TimetableConfirmationToken,
    ): RestoreResult = withContext(Dispatchers.IO) {
        try {
            // Entire restore process executes under snapshot lease to prevent concurrency rollback hazard
            TimetableWriteCoordinator.withSnapshotLease {
                // 1. Run recovery barrier inside snapshot lease
                val initialRecovery = TimetableRecoveryBarrier.recoverBeforeOpen(context)
                if (initialRecovery is RecoveryResult.Blocked) {
                    return@withSnapshotLease RestoreResult.Rejected("目标环境存在未解决的崩溃残留: ${initialRecovery.error}")
                }

                // 2. Read and decrypt payload
                val bytes = readUriBytesWithLimit(context, sourceUri, TIMETABLE_BACKUP_MAX_CONTAINER_BYTES)
                val plaintext = TimetableEncryption.decrypt(bytes, passphrase)
                val jsonString = String(plaintext, Charsets.UTF_8)
                val payload = TimetableBackupJsonCodec.decode(jsonString)

                TimetableBackupValidator.validate(payload)
                val calculatedDigest = TimetableBackupDigest.calculateDigest(payload)
                require(calculatedDigest == payload.contentDigest) { "备份内容摘要不匹配" }
                require(payload.contentDigest == token.contentDigest) { "确认Token与备份内容不匹配" }

                // 3. Idempotency check: ALREADY_IMPORTED
                val existingReceiptRes = TimetableMigrationJournal.readReceipt(context)
                if (existingReceiptRes is ReceiptReadResult.Present && existingReceiptRes.receipt.contentDigest == payload.contentDigest) {
                    return@withSnapshotLease RestoreResult.AlreadyImported
                }

                // 4. Precondition check inside lock
                require(token.formatVersion == TIMETABLE_BACKUP_FORMAT_VERSION) { "备份格式版本已过期或不支持" }
                val currentTargetHash = computeTargetStateHash(context)
                require(currentTargetHash == token.targetStateHash) { "目标状态在确认期间已发生变化，请重新预检" }
                if (hasExistingUserData(context)) {
                    return@withSnapshotLease RestoreResult.Rejected("目标环境已存在课程数据，禁止覆盖")
                }
                for (p in payload.profiles) {
                    val destFile = context.getDatabasePath("timetable_${p.profileId}.db")
                    if (destFile.exists()) {
                        return@withSnapshotLease RestoreResult.Rejected("目标数据库文件已存在，拒绝覆盖: ${destFile.name}")
                    }
                }

                // 5. Execute staging, publishing and committing with rollback protection
                executeStagedRestoreUnderLease(context, payload)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            RestoreResult.Failed(e.message ?: "课表数据恢复失败")
        } finally {
            passphrase.fill('\u0000')
        }
    }

    private suspend fun executeStagedRestoreUnderLease(
        context: Context,
        payload: TimetableBackupPayloadDto,
    ): RestoreResult {
        val profilePrefs = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
        val originalActiveId = profilePrefs.getString("id", null)

        val stagingDir = File(context.filesDir, "staging_${UUID.randomUUID()}")
        val sessionToken = UUID.randomUUID().toString()

        // Backup existing course_display_groups keys before mutation
        val groupPrefs = context.getSharedPreferences("course_display_groups", Context.MODE_PRIVATE)
        val groupBackups = mutableMapOf<String, String?>()
        for (p in payload.profiles) {
            for (g in p.displayGroups) {
                groupBackups[g.storageKey] = groupPrefs.getString(g.storageKey, null)
            }
        }

        // Stage 1: PREPARING (createdDatabaseFiles is strictly empty at start to prevent deleting unowned files)
        var journal = MigrationJournalState(
            stage = MigrationStage.PREPARING.name,
            contentDigest = payload.contentDigest,
            targetActiveProfileId = payload.activeProfileId,
            originalActiveProfileId = originalActiveId,
            createdDatabaseFiles = emptyList(),
            createdStagingDir = stagingDir.absolutePath,
            groupBackups = groupBackups,
            sessionToken = sessionToken,
        )
        TimetableMigrationJournal.writeJournal(context, journal)



        try {
            // Stage 2: Build, validate staging databases and verify Room entity readback
            val createdStagingFiles = TimetableStagingService.createStagingDatabases(context, stagingDir, payload)
            journal = journal.copy(stage = MigrationStage.VALIDATED.name)
            TimetableMigrationJournal.writeJournal(context, journal)

            TimetableMigrationTestHooks.beforePublishHook?.invoke()

            // Stage 3: PUBLISHING (Copy staging databases to destination with dynamic ownership proof)
            journal = journal.copy(stage = MigrationStage.PUBLISHING.name)
            TimetableMigrationJournal.writeJournal(context, journal)

            val currentlyCreatedFiles = mutableListOf<String>()
            for (stagedFile in createdStagingFiles) {
                val destFile = context.getDatabasePath(stagedFile.name)
                val destDir = destFile.parentFile ?: File(context.filesDir, "../databases").canonicalFile
                destDir.mkdirs()

                if (destFile.exists()) {
                    throw IllegalStateException("目标位置已被占用，发布中止: ${destFile.name}")
                }

                MigrationPublication.publish(stagedFile, destFile, sessionToken)
                currentlyCreatedFiles.add(stagedFile.name)
                journal = journal.copy(createdDatabaseFiles = currentlyCreatedFiles.toList())
                TimetableMigrationJournal.writeJournal(context, journal)
            }

            // Publish course display groups
            val groupEditor = groupPrefs.edit()
            for (p in payload.profiles) {
                for (g in p.displayGroups) {
                    groupEditor.putString(g.storageKey, g.rawJson)
                }
            }
            check(groupEditor.commit()) { "无法持久化展示分组配置" }

            // Switch active profile
            check(profilePrefs.edit().putString("id", payload.activeProfileId).commit()) {
                "无法持久化活动Profile ID"
            }

            // Read-back verification
            val verifiedActiveId = profilePrefs.getString("id", null)
            check(verifiedActiveId == payload.activeProfileId) { "活动Profile读回核对失败" }

            TimetableMigrationTestHooks.afterPublishBeforeCommitHook?.invoke()

            // Stage 4: COMMITTED (Unified commit point: write journal COMMITTED FIRST)
            val committed = journal.copy(stage = MigrationStage.COMMITTED.name)
            TimetableMigrationJournal.writeJournal(context, committed)
            check((TimetableMigrationJournal.readJournal(context) as? JournalReadResult.Present)?.state == committed) {
                "无法确认迁移提交日志已持久化"
            }
            journal = committed
        } catch (t: Throwable) {
            // Stage is uncommitted (PREPARING, VALIDATED, or PUBLISHING)
            // Roll back uncommitted session completely under NonCancellable before releasing write lease
            val rollbackRes = withContext(NonCancellable) {
                TimetableRecoveryBarrier.recoverBeforeOpen(context)
            }
            if (t is CancellationException) {
                throw t
            }
            if (rollbackRes is RecoveryResult.Blocked) {
                return RestoreResult.Failed("恢复中断且安全回滚阻断: ${rollbackRes.error}")
            }
            if (rollbackRes is RecoveryResult.RestoredCommitted && rollbackRes.receipt.contentDigest == payload.contentDigest) {
                return RestoreResult.Success(payload.profiles.size, payload.totalRowCount(), payload.activeProfileId)
            }
            return RestoreResult.Failed(t.message ?: "课表数据发布恢复失败")
        }

        // Post-commit stage: Data is fully committed and irreversible.
        // Failures in receipt writing or cleanup MUST NOT rollback committed user data.
        try {
            TimetableMigrationTestHooks.afterCommitBeforeReceiptHook?.invoke()
            TimetableMigrationTestHooks.writeReceiptHook?.invoke()

            val receipt = MigrationReceipt(
                status = "COMMITTED",
                contentDigest = payload.contentDigest,
                restoredProfileIds = payload.profiles.map { it.profileId },
                restoredActiveProfileId = payload.activeProfileId,
                committedAt = System.currentTimeMillis(),
            )
            TimetableMigrationJournal.writeReceipt(context, receipt)
            check((TimetableMigrationJournal.readReceipt(context) as? ReceiptReadResult.Present)?.receipt == receipt) {
                "无法确认迁移收据已持久化"
            }
            TimetableMigrationTestHooks.cleanupHook?.invoke()
            check(TimetableRecoveryBarrier.finishCommittedSession(context, journal)) {
                "迁移已提交，临时文件将在下次启动时重试清理"
            }
        } catch (e: Exception) {
            android.util.Log.w("TimetableMigration", "提交后清理临时文件异常: ${e.message}")
        }

        return RestoreResult.Success(
            profileCount = payload.profiles.size,
            totalRows = payload.totalRowCount(),
            activeProfileId = payload.activeProfileId,
        )
    }

    private fun readUriBytesWithLimit(context: Context, uri: Uri, limit: Int): ByteArray {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16384)
            var total = 0
            var read: Int
            while (stream.read(chunk).also { read = it } != -1) {
                total += read
                require(total <= limit) { "输入文件体积超过限制: $total > $limit 字节" }
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        } ?: throw IllegalArgumentException("无法打开输入文件: $uri")
    }

    private fun hasExistingUserData(context: Context): Boolean {
        if (context.getSharedPreferences("course_display_groups", Context.MODE_PRIVATE).all.isNotEmpty()) return true
        val defaultSemester = com.lingion.sleepy.domain.model.SemesterSettings()
        val defaultPeriods = com.lingion.sleepy.domain.model.defaultPeriods()
        for (name in context.databaseList().orEmpty()) {
            if (!name.startsWith("timetable_") || !name.endsWith(".db")) continue
            val file = context.getDatabasePath(name)
            if (!file.isFile || file.length() == 0L) return true
            android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                val dataTables = listOf(
                    "courses", "scheduled_courses", "course_meetings", "meeting_weeks",
                    "meeting_sections", "meeting_dates", "meeting_declared_weeks", "meeting_exceptions",
                    "exception_sections", "schedule_provenance", "import_normalization_state",
                    "notice_adjustment_events", "sqlite_sequence",
                )
                for (table in dataTables) {
                    db.rawQuery("SELECT 1 FROM $table LIMIT 1", null).use {
                        if (it.moveToFirst()) return true
                    }
                }
                val hasDefaultSemester = db.rawQuery(
                    "SELECT id, name, startEpochDay, totalWeeks, revision FROM semesters", null,
                ).use { cursor ->
                    if (!cursor.moveToFirst()) false
                    else {
                        if (cursor.getLong(0) != defaultSemester.id || cursor.getString(1) != defaultSemester.name ||
                            !cursor.isNull(2) || cursor.getInt(3) != defaultSemester.totalWeeks ||
                            cursor.getLong(4) != 0L || cursor.moveToNext()
                        ) return true
                        true
                    }
                }
                db.rawQuery(
                    "SELECT semesterId, number, startMinute, endMinute FROM periods ORDER BY semesterId, number", null,
                ).use { cursor ->
                    val expected = if (hasDefaultSemester) defaultPeriods else emptyList()
                    for (period in expected) {
                        if (!cursor.moveToNext() || cursor.getLong(0) != defaultSemester.id ||
                            cursor.getInt(1) != period.number ||
                            cursor.getInt(2) != period.start.toSecondOfDay() / 60 ||
                            cursor.getInt(3) != period.end.toSecondOfDay() / 60
                        ) return true
                    }
                    if (cursor.moveToNext()) return true
                }
            }
        }
        return false
    }

    private fun computeTargetStateHash(context: Context): String {
        val md = MessageDigest.getInstance("SHA-256")
        fun updateStr(s: String) {
            md.update(s.toByteArray(Charsets.UTF_8))
            md.update(0.toByte())
        }

        // 1. Active profile ID
        val profilePrefs = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
        val activeId = profilePrefs.getString("id", "none") ?: "none"
        updateStr("activeProfile:$activeId")

        // 2. Display groups preferences (all keys and values sorted)
        val groupPrefs = context.getSharedPreferences("course_display_groups", Context.MODE_PRIVATE)
        val groupEntries = groupPrefs.all.entries.sortedBy { it.key }
        for ((k, v) in groupEntries) {
            updateStr("group:$k=${v?.toString() ?: "null"}")
        }

        // 3. Database files metadata and content
        val allDbs = (context.databaseList() ?: emptyArray()).sorted()
        for (dbName in allDbs) {
            if (dbName.startsWith("timetable_") && dbName.endsWith(".db")) {
                val dbFile = context.getDatabasePath(dbName)
                updateStr("dbFile:$dbName|length=${dbFile.length()}|modified=${dbFile.lastModified()}")
                if (dbFile.exists() && dbFile.length() > 0) {
                    try {
                        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                            dbFile.absolutePath,
                            null,
                            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                        )
                        db.use {
                            // PRAGMA data_version and user_version
                            it.rawQuery("PRAGMA data_version;", null).use { c ->
                                if (c.moveToFirst()) updateStr("data_version:${c.getLong(0)}")
                            }
                            it.rawQuery("PRAGMA user_version;", null).use { c ->
                                if (c.moveToFirst()) updateStr("user_version:${c.getLong(0)}")
                            }
                            // Check periods rows if exists
                            it.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='periods'", null).use { c ->
                                if (c.moveToFirst() && c.getInt(0) > 0) {
                                    it.rawQuery("SELECT semesterId, number, startMinute, endMinute FROM periods ORDER BY semesterId, number", null).use { cp ->
                                        while (cp.moveToNext()) {
                                            updateStr("period:${cp.getLong(0)}|${cp.getInt(1)}|${cp.getInt(2)}|${cp.getInt(3)}")
                                        }
                                    }
                                }
                            }
                            // Check scheduled_courses count
                            it.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='scheduled_courses'", null).use { c ->
                                if (c.moveToFirst() && c.getInt(0) > 0) {
                                    it.rawQuery("SELECT count(*) FROM scheduled_courses", null).use { csc ->
                                        if (csc.moveToFirst()) updateStr("scCount:${csc.getLong(0)}")
                                    }
                                }
                            }
                            // Check notice_adjustment_events count
                            it.rawQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='notice_adjustment_events'", null).use { c ->
                                if (c.moveToFirst() && c.getInt(0) > 0) {
                                    it.rawQuery("SELECT count(*) FROM notice_adjustment_events", null).use { cna ->
                                        if (cna.moveToFirst()) updateStr("naCount:${cna.getLong(0)}")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        updateStr("dbError:$dbName|${e.message}")
                    }
                }
            }
        }

        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
