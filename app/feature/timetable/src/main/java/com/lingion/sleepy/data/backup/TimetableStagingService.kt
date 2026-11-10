/* SPDX-License-Identifier: GPL-3.0-only
 * Staging service that constructs isolated SQLite databases, restores sequences, and runs integrity checks.
 */
package com.lingion.sleepy.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.lingion.sleepy.data.local.CALENDAR_CALLBACK
import com.lingion.sleepy.data.local.MIGRATION_1_2
import com.lingion.sleepy.data.local.MIGRATION_2_3
import com.lingion.sleepy.data.local.MIGRATION_3_4
import com.lingion.sleepy.data.local.MIGRATION_4_5
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.domain.notice.NoticeEventCodec
import java.io.File
import kotlin.math.max

object TimetableStagingService {

    /**
     * Builds and populates all profile databases in an isolated staging directory.
     * Returns the list of created database files.
     */
    suspend fun createStagingDatabases(
        context: Context,
        stagingDir: File,
        payload: TimetableBackupPayloadDto,
    ): List<File> {
        if (!stagingDir.exists()) {
            check(stagingDir.mkdirs()) { "无法创建staging目录: ${stagingDir.absolutePath}" }
        }

        val createdFiles = mutableListOf<File>()

        for (profile in payload.profiles) {
            val dbFile = File(stagingDir, "timetable_${profile.profileId}.db")
            if (dbFile.exists()) {
                dbFile.delete()
            }
            // 1. Initialize schema using Room builder
            val roomDb = Room.databaseBuilder(
                context.applicationContext,
                TimetableDatabase::class.java,
                dbFile.absolutePath,
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(CALENDAR_CALLBACK)
                .build()

            // Trigger schema creation
            roomDb.openHelper.writableDatabase
            roomDb.close()

            // 2. Strongly typed parameterized insertion
            populateProfileDatabase(dbFile, profile)

            // 3. Verify integrity, foreign keys & autoincrement sequence behavior
            verifyDatabaseIntegrity(dbFile, profile)

            // 4. Verify full-value read-back through normal Room database and DAOs
            verifyRoomEntities(context, dbFile, profile)

            createdFiles.add(dbFile)
        }

        return createdFiles
    }

    private fun populateProfileDatabase(dbFile: File, profile: ProfileBackupDto) {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.beginTransaction()
            try {
                // 1. courses
                val insertCourses = db.compileStatement("INSERT INTO courses (id, courseName, day, startNode, step, startWeek, endWeek, type) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                for (c in profile.courses) {
                    insertCourses.bindLong(1, c.id)
                    insertCourses.bindString(2, c.courseName)
                    insertCourses.bindLong(3, c.day.toLong())
                    insertCourses.bindLong(4, c.startNode.toLong())
                    insertCourses.bindLong(5, c.step.toLong())
                    insertCourses.bindLong(6, c.startWeek.toLong())
                    insertCourses.bindLong(7, c.endWeek.toLong())
                    insertCourses.bindLong(8, c.type.toLong())
                    insertCourses.executeInsert()
                }

                // 2. semesters
                val insertSemesters = db.compileStatement("INSERT INTO semesters (id, name, startEpochDay, totalWeeks, revision) VALUES (?, ?, ?, ?, ?)")
                for (s in profile.semesters) {
                    insertSemesters.bindLong(1, s.id)
                    insertSemesters.bindString(2, s.name)
                    if (s.startEpochDay != null) insertSemesters.bindLong(3, s.startEpochDay) else insertSemesters.bindNull(3)
                    insertSemesters.bindLong(4, s.totalWeeks.toLong())
                    insertSemesters.bindLong(5, s.revision)
                    insertSemesters.executeInsert()
                }

                // 3. periods
                val insertPeriods = db.compileStatement("INSERT INTO periods (semesterId, number, startMinute, endMinute) VALUES (?, ?, ?, ?)")
                for (p in profile.periods) {
                    insertPeriods.bindLong(1, p.semesterId)
                    insertPeriods.bindLong(2, p.number.toLong())
                    insertPeriods.bindLong(3, p.startMinute.toLong())
                    insertPeriods.bindLong(4, p.endMinute.toLong())
                    insertPeriods.executeInsert()
                }

                // 4. scheduled_courses
                val insertScheduledCourses = db.compileStatement("INSERT INTO scheduled_courses (id, semesterId, courseName, teacher, room, color, sourceCourseCode, sourceClassCode) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                for (sc in profile.scheduledCourses) {
                    insertScheduledCourses.bindLong(1, sc.id)
                    insertScheduledCourses.bindLong(2, sc.semesterId)
                    insertScheduledCourses.bindString(3, sc.courseName)
                    insertScheduledCourses.bindString(4, sc.teacher)
                    insertScheduledCourses.bindString(5, sc.room)
                    insertScheduledCourses.bindLong(6, sc.color)
                    if (sc.sourceCourseCode != null) insertScheduledCourses.bindString(7, sc.sourceCourseCode) else insertScheduledCourses.bindNull(7)
                    if (sc.sourceClassCode != null) insertScheduledCourses.bindString(8, sc.sourceClassCode) else insertScheduledCourses.bindNull(8)
                    insertScheduledCourses.executeInsert()
                }

                // 5. course_meetings
                val insertCourseMeetings = db.compileStatement("INSERT INTO course_meetings (id, courseId, day, customStartMinute, customEndMinute, teacher, room, kind, experimentProject, `group`, isExperiment) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                for (cm in profile.courseMeetings) {
                    insertCourseMeetings.bindLong(1, cm.id)
                    insertCourseMeetings.bindLong(2, cm.courseId)
                    insertCourseMeetings.bindLong(3, cm.day.toLong())
                    if (cm.customStartMinute != null) insertCourseMeetings.bindLong(4, cm.customStartMinute.toLong()) else insertCourseMeetings.bindNull(4)
                    if (cm.customEndMinute != null) insertCourseMeetings.bindLong(5, cm.customEndMinute.toLong()) else insertCourseMeetings.bindNull(5)
                    if (cm.teacher != null) insertCourseMeetings.bindString(6, cm.teacher) else insertCourseMeetings.bindNull(6)
                    if (cm.room != null) insertCourseMeetings.bindString(7, cm.room) else insertCourseMeetings.bindNull(7)
                    insertCourseMeetings.bindString(8, cm.kind)
                    if (cm.experimentProject != null) insertCourseMeetings.bindString(9, cm.experimentProject) else insertCourseMeetings.bindNull(9)
                    if (cm.group != null) insertCourseMeetings.bindString(10, cm.group) else insertCourseMeetings.bindNull(10)
                    insertCourseMeetings.bindLong(11, if (cm.isExperiment) 1L else 0L)
                    insertCourseMeetings.executeInsert()
                }

                // 6. meeting_weeks
                val insertMeetingWeeks = db.compileStatement("INSERT INTO meeting_weeks (meetingId, week) VALUES (?, ?)")
                for (mw in profile.meetingWeeks) {
                    insertMeetingWeeks.bindLong(1, mw.meetingId)
                    insertMeetingWeeks.bindLong(2, mw.week.toLong())
                    insertMeetingWeeks.executeInsert()
                }

                // 7. meeting_sections
                val insertMeetingSections = db.compileStatement("INSERT INTO meeting_sections (meetingId, section) VALUES (?, ?)")
                for (ms in profile.meetingSections) {
                    insertMeetingSections.bindLong(1, ms.meetingId)
                    insertMeetingSections.bindLong(2, ms.section.toLong())
                    insertMeetingSections.executeInsert()
                }

                // 8. meeting_dates
                val insertMeetingDates = db.compileStatement("INSERT INTO meeting_dates (meetingId, epochDay) VALUES (?, ?)")
                for (md in profile.meetingDates) {
                    insertMeetingDates.bindLong(1, md.meetingId)
                    insertMeetingDates.bindLong(2, md.epochDay)
                    insertMeetingDates.executeInsert()
                }

                // 9. meeting_declared_weeks
                val insertMeetingDeclaredWeeks = db.compileStatement("INSERT INTO meeting_declared_weeks (meetingId, week) VALUES (?, ?)")
                for (mdw in profile.meetingDeclaredWeeks) {
                    insertMeetingDeclaredWeeks.bindLong(1, mdw.meetingId)
                    insertMeetingDeclaredWeeks.bindLong(2, mdw.week.toLong())
                    insertMeetingDeclaredWeeks.executeInsert()
                }

                // 10. meeting_exceptions
                val insertMeetingExceptions = db.compileStatement("INSERT INTO meeting_exceptions (id, meetingId, kind, originalEpochDay, targetEpochDay, customStartMinute, customEndMinute, hasSectionsOverride, teacher, room, experimentProject, `group`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                for (me in profile.meetingExceptions) {
                    insertMeetingExceptions.bindLong(1, me.id)
                    insertMeetingExceptions.bindLong(2, me.meetingId)
                    insertMeetingExceptions.bindString(3, me.kind)
                    if (me.originalEpochDay != null) insertMeetingExceptions.bindLong(4, me.originalEpochDay) else insertMeetingExceptions.bindNull(4)
                    if (me.targetEpochDay != null) insertMeetingExceptions.bindLong(5, me.targetEpochDay) else insertMeetingExceptions.bindNull(5)
                    if (me.customStartMinute != null) insertMeetingExceptions.bindLong(6, me.customStartMinute.toLong()) else insertMeetingExceptions.bindNull(6)
                    if (me.customEndMinute != null) insertMeetingExceptions.bindLong(7, me.customEndMinute.toLong()) else insertMeetingExceptions.bindNull(7)
                    insertMeetingExceptions.bindLong(8, if (me.hasSectionsOverride) 1L else 0L)
                    if (me.teacher != null) insertMeetingExceptions.bindString(9, me.teacher) else insertMeetingExceptions.bindNull(9)
                    if (me.room != null) insertMeetingExceptions.bindString(10, me.room) else insertMeetingExceptions.bindNull(10)
                    if (me.experimentProject != null) insertMeetingExceptions.bindString(11, me.experimentProject) else insertMeetingExceptions.bindNull(11)
                    if (me.group != null) insertMeetingExceptions.bindString(12, me.group) else insertMeetingExceptions.bindNull(12)
                    insertMeetingExceptions.executeInsert()
                }

                // 11. exception_sections
                val insertExceptionSections = db.compileStatement("INSERT INTO exception_sections (exceptionId, section) VALUES (?, ?)")
                for (es in profile.exceptionSections) {
                    insertExceptionSections.bindLong(1, es.exceptionId)
                    insertExceptionSections.bindLong(2, es.section.toLong())
                    insertExceptionSections.executeInsert()
                }

                // 12. schedule_provenance
                val insertProvenance = db.compileStatement("INSERT INTO schedule_provenance (id, courseId, meetingId, exceptionId, position, sourceId, adapterId, page, left, top, right, bottom, rawText, sourceLocator) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                for (sp in profile.scheduleProvenance) {
                    insertProvenance.bindLong(1, sp.id)
                    if (sp.courseId != null) insertProvenance.bindLong(2, sp.courseId) else insertProvenance.bindNull(2)
                    if (sp.meetingId != null) insertProvenance.bindLong(3, sp.meetingId) else insertProvenance.bindNull(3)
                    if (sp.exceptionId != null) insertProvenance.bindLong(4, sp.exceptionId) else insertProvenance.bindNull(4)
                    insertProvenance.bindLong(5, sp.position.toLong())
                    if (sp.sourceId != null) insertProvenance.bindString(6, sp.sourceId) else insertProvenance.bindNull(6)
                    if (sp.adapterId != null) insertProvenance.bindString(7, sp.adapterId) else insertProvenance.bindNull(7)
                    if (sp.page != null) insertProvenance.bindLong(8, sp.page.toLong()) else insertProvenance.bindNull(8)
                    if (sp.left != null) insertProvenance.bindDouble(9, sp.left) else insertProvenance.bindNull(9)
                    if (sp.top != null) insertProvenance.bindDouble(10, sp.top) else insertProvenance.bindNull(10)
                    if (sp.right != null) insertProvenance.bindDouble(11, sp.right) else insertProvenance.bindNull(11)
                    if (sp.bottom != null) insertProvenance.bindDouble(12, sp.bottom) else insertProvenance.bindNull(12)
                    insertProvenance.bindString(13, sp.rawText)
                    if (sp.sourceLocator != null) insertProvenance.bindString(14, sp.sourceLocator) else insertProvenance.bindNull(14)
                    insertProvenance.executeInsert()
                }

                // 13. import_normalization_state
                val insertNormalizationState = db.compileStatement("INSERT INTO import_normalization_state (courseId, version, payload) VALUES (?, ?, ?)")
                for (ins in profile.importNormalizationState) {
                    insertNormalizationState.bindLong(1, ins.courseId)
                    insertNormalizationState.bindLong(2, ins.version.toLong())
                    insertNormalizationState.bindString(3, ins.payload)
                    insertNormalizationState.executeInsert()
                }

                // 14. notice_adjustment_events
                val insertNoticeAdjustmentEvents = db.compileStatement("INSERT INTO notice_adjustment_events (id, requestKey, semanticKey, confirmDigest, scopeToken, semesterId, kind, courseId, courseTitle, logicalBaseId, structureFingerprint, meetingId, originalEpochDay, targetEpochDay, beforeJson, afterJson, afterFingerprint, payloadJson, sourceText, sourceDigest, dateBasisJson, appliedRevision, undoneRevision, state, activeKey) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                for (nae in profile.noticeAdjustmentEvents) {
                    insertNoticeAdjustmentEvents.bindLong(1, nae.id)
                    insertNoticeAdjustmentEvents.bindString(2, nae.requestKey)
                    insertNoticeAdjustmentEvents.bindString(3, nae.semanticKey)
                    insertNoticeAdjustmentEvents.bindString(4, nae.confirmDigest)
                    insertNoticeAdjustmentEvents.bindString(5, nae.scopeToken)
                    insertNoticeAdjustmentEvents.bindLong(6, nae.semesterId)
                    insertNoticeAdjustmentEvents.bindString(7, nae.kind)
                    if (nae.courseId != null) insertNoticeAdjustmentEvents.bindLong(8, nae.courseId) else insertNoticeAdjustmentEvents.bindNull(8)
                    if (nae.courseTitle != null) insertNoticeAdjustmentEvents.bindString(9, nae.courseTitle) else insertNoticeAdjustmentEvents.bindNull(9)
                    if (nae.logicalBaseId != null) insertNoticeAdjustmentEvents.bindString(10, nae.logicalBaseId) else insertNoticeAdjustmentEvents.bindNull(10)
                    if (nae.structureFingerprint != null) insertNoticeAdjustmentEvents.bindString(11, nae.structureFingerprint) else insertNoticeAdjustmentEvents.bindNull(11)
                    if (nae.meetingId != null) insertNoticeAdjustmentEvents.bindLong(12, nae.meetingId) else insertNoticeAdjustmentEvents.bindNull(12)
                    if (nae.originalEpochDay != null) insertNoticeAdjustmentEvents.bindLong(13, nae.originalEpochDay) else insertNoticeAdjustmentEvents.bindNull(13)
                    if (nae.targetEpochDay != null) insertNoticeAdjustmentEvents.bindLong(14, nae.targetEpochDay) else insertNoticeAdjustmentEvents.bindNull(14)
                    if (nae.beforeJson != null) insertNoticeAdjustmentEvents.bindString(15, nae.beforeJson) else insertNoticeAdjustmentEvents.bindNull(15)
                    if (nae.afterJson != null) insertNoticeAdjustmentEvents.bindString(16, nae.afterJson) else insertNoticeAdjustmentEvents.bindNull(16)
                    insertNoticeAdjustmentEvents.bindString(17, nae.afterFingerprint)
                    insertNoticeAdjustmentEvents.bindString(18, nae.payloadJson)
                    if (nae.sourceText != null) insertNoticeAdjustmentEvents.bindString(19, nae.sourceText) else insertNoticeAdjustmentEvents.bindNull(19)
                    insertNoticeAdjustmentEvents.bindString(20, nae.sourceDigest)
                    if (nae.dateBasisJson != null) insertNoticeAdjustmentEvents.bindString(21, nae.dateBasisJson) else insertNoticeAdjustmentEvents.bindNull(21)
                    insertNoticeAdjustmentEvents.bindLong(22, nae.appliedRevision)
                    if (nae.undoneRevision != null) insertNoticeAdjustmentEvents.bindLong(23, nae.undoneRevision) else insertNoticeAdjustmentEvents.bindNull(23)
                    insertNoticeAdjustmentEvents.bindString(24, nae.state)
                    if (nae.activeKey != null) insertNoticeAdjustmentEvents.bindString(25, nae.activeKey) else insertNoticeAdjustmentEvents.bindNull(25)
                    insertNoticeAdjustmentEvents.executeInsert()
                }

                // 15. sqlite_sequence restoration (max of provided sequence and max id)
                // Note: SQLite's sqlite_sequence table has NO UNIQUE or PRIMARY KEY constraint.
                // An 'INSERT OR REPLACE' would simply insert duplicate rows instead of replacing.
                // We must explicitly DELETE any preexisting row before INSERT to guarantee uniqueness.
                val autoincrementMaxIds = mapOf(
                    "courses" to (profile.courses.maxOfOrNull { it.id } ?: 0L),
                    "scheduled_courses" to (profile.scheduledCourses.maxOfOrNull { it.id } ?: 0L),
                    "course_meetings" to (profile.courseMeetings.maxOfOrNull { it.id } ?: 0L),
                    "meeting_exceptions" to (profile.meetingExceptions.maxOfOrNull { it.id } ?: 0L),
                    "schedule_provenance" to (profile.scheduleProvenance.maxOfOrNull { it.id } ?: 0L),
                    "notice_adjustment_events" to (profile.noticeAdjustmentEvents.maxOfOrNull { it.id } ?: 0L),
                )
                val delSeqStmt = db.compileStatement("DELETE FROM sqlite_sequence WHERE name = ?")
                val insertSeqStmt = db.compileStatement("INSERT INTO sqlite_sequence (name, seq) VALUES (?, ?)")
                for ((tableName, tableMaxId) in autoincrementMaxIds) {
                    val backupSeq = profile.sequences.firstOrNull { it.name == tableName }?.seq ?: 0L
                    val finalSeq = max(backupSeq, tableMaxId)
                    if (finalSeq > 0L) {
                        delSeqStmt.bindString(1, tableName)
                        delSeqStmt.executeUpdateDelete()
                        insertSeqStmt.bindString(1, tableName)
                        insertSeqStmt.bindLong(2, finalSeq)
                        insertSeqStmt.executeInsert()
                    }
                }

                // Verify sqlite_sequence has no duplicate entries
                db.rawQuery("SELECT name, COUNT(*) FROM sqlite_sequence GROUP BY name HAVING COUNT(*) > 1", null).use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dupName = cursor.getString(0)
                        throw IllegalStateException("sqlite_sequence 存在重复表名记录: $dupName")
                    }
                }

                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } finally {
            db.close()
        }
    }

    private fun verifyDatabaseIntegrity(dbFile: File, profile: ProfileBackupDto) {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            // Foreign key check
            db.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val table = cursor.getString(0)
                    val rowId = cursor.getLong(1)
                    val parent = cursor.getString(2)
                    throw IllegalStateException("Staging数据库外键校验失败: table=$table, rowId=$rowId, parent=$parent")
                }
            }

            // Quick integrity check
            db.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                if (cursor.moveToFirst()) {
                    val result = cursor.getString(0)
                    if (result != "ok") {
                        throw IllegalStateException("Staging数据库完整性检查失败: $result")
                    }
                } else {
                    throw IllegalStateException("Staging数据库完整性检查无返回")
                }
            }

            // Verify sqlite_sequence values match expectation
            val autoincrementMaxIds = mapOf(
                "courses" to (profile.courses.maxOfOrNull { it.id } ?: 0L),
                "scheduled_courses" to (profile.scheduledCourses.maxOfOrNull { it.id } ?: 0L),
                "course_meetings" to (profile.courseMeetings.maxOfOrNull { it.id } ?: 0L),
                "meeting_exceptions" to (profile.meetingExceptions.maxOfOrNull { it.id } ?: 0L),
                "schedule_provenance" to (profile.scheduleProvenance.maxOfOrNull { it.id } ?: 0L),
                "notice_adjustment_events" to (profile.noticeAdjustmentEvents.maxOfOrNull { it.id } ?: 0L),
            )
            for ((tableName, tableMaxId) in autoincrementMaxIds) {
                val backupSeq = profile.sequences.firstOrNull { it.name == tableName }?.seq ?: 0L
                val finalSeq = max(backupSeq, tableMaxId)
                if (finalSeq > 0L) {
                    db.rawQuery("SELECT seq FROM sqlite_sequence WHERE name = ?", arrayOf(tableName)).use { seqCursor ->
                        if (seqCursor.moveToFirst()) {
                            val actualSeq = seqCursor.getLong(0)
                            check(actualSeq == finalSeq) {
                                "sqlite_sequence 值不匹配: table=$tableName, expected=$finalSeq, actual=$actualSeq"
                            }
                        } else {
                            throw IllegalStateException("sqlite_sequence 缺少预期表项: table=$tableName")
                        }
                    }
                }
            }
        } finally {
            db.close()
        }

        // Verify autoincrement behavior on an isolated probe copy so that the staging DB is untouched
        verifyAutoincrementOnProbe(dbFile, profile)
    }

    private fun verifyAutoincrementOnProbe(dbFile: File, profile: ProfileBackupDto) {
        val probeFile = File(dbFile.parentFile, "${dbFile.name}.probe")
        try {
            dbFile.copyTo(probeFile, overwrite = true)
            val db = SQLiteDatabase.openDatabase(probeFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            try {
                // If courses has sequence, test next inserted id
                val coursesMaxId = profile.courses.maxOfOrNull { it.id } ?: 0L
                val coursesBackupSeq = profile.sequences.firstOrNull { it.name == "courses" }?.seq ?: 0L
                val coursesFinalSeq = max(coursesBackupSeq, coursesMaxId)
                if (coursesFinalSeq > 0L) {
                    val stmt = db.compileStatement("INSERT INTO courses (courseName, day, startNode, step, startWeek, endWeek, type) VALUES ('__probe__', 1, 1, 1, 1, 1, 1)")
                    val insertedId = stmt.executeInsert()
                    check(insertedId == coursesFinalSeq + 1L) {
                        "Staging探针 courses 自增分配断言失败: expected=${coursesFinalSeq + 1L}, actual=$insertedId"
                    }
                }

                // If scheduled_courses has sequence and valid semester, test next inserted id
                val scMaxId = profile.scheduledCourses.maxOfOrNull { it.id } ?: 0L
                val scBackupSeq = profile.sequences.firstOrNull { it.name == "scheduled_courses" }?.seq ?: 0L
                val scFinalSeq = max(scBackupSeq, scMaxId)
                val semId = profile.semesters.firstOrNull()?.id
                if (scFinalSeq > 0L && semId != null) {
                    val stmtSc = db.compileStatement("INSERT INTO scheduled_courses (semesterId, courseName, teacher, room, color) VALUES (?, '__sc_probe__', 'T', 'R', 0)")
                    stmtSc.bindLong(1, semId)
                    val insertedScId = stmtSc.executeInsert()
                    check(insertedScId == scFinalSeq + 1L) {
                        "Staging探针 scheduled_courses 自增分配断言失败: expected=${scFinalSeq + 1L}, actual=$insertedScId"
                    }
                }
            } finally {
                db.close()
            }
        } finally {
            probeFile.delete()
            File(probeFile.parentFile, "${probeFile.name}-wal").delete()
            File(probeFile.parentFile, "${probeFile.name}-shm").delete()
            File(probeFile.parentFile, "${probeFile.name}-journal").delete()
        }
    }

    private suspend fun verifyRoomEntities(context: Context, dbFile: File, profile: ProfileBackupDto) {
        val roomDb = Room.databaseBuilder(
            context.applicationContext,
            TimetableDatabase::class.java,
            dbFile.absolutePath,
        ).build()
        try {
            val scheduleDao = roomDb.scheduleDao()
            val noticeDao = roomDb.noticeAdjustmentDao()

            for (s in profile.semesters) {
                val semEntity = scheduleDao.semester(s.id)
                checkNotNull(semEntity) { "Room全值核对失败: 学期缺失 id=${s.id}" }
                check(
                    semEntity.name == s.name &&
                    semEntity.totalWeeks == s.totalWeeks &&
                    semEntity.revision == s.revision &&
                    semEntity.startEpochDay == s.startEpochDay
                ) {
                    "Room全值核对失败: 学期数据不匹配 id=${s.id}"
                }

                // 1. Periods full values
                val loadedPeriods = scheduleDao.periods(s.id)
                val expectedPeriods = profile.periods.filter { it.semesterId == s.id }
                check(loadedPeriods.size == expectedPeriods.size) {
                    "Room全值核对失败: periods数量不匹配 (期望 ${expectedPeriods.size}, 实际 ${loadedPeriods.size})"
                }
                for (ep in expectedPeriods) {
                    val lp = loadedPeriods.firstOrNull { it.number == ep.number }
                    checkNotNull(lp) { "Room全值核对失败: periods缺少节次 number=${ep.number}" }
                    check(lp.startMinute == ep.startMinute && lp.endMinute == ep.endMinute) {
                        "Room全值核对失败: periods节次时间不匹配 number=${ep.number}"
                    }
                }

                // 2. Scheduled courses full values including sourceCourseCode and sourceClassCode
                val loadedCourses = scheduleDao.courses(s.id)
                val expectedCourses = profile.scheduledCourses.filter { it.semesterId == s.id }
                check(loadedCourses.size == expectedCourses.size) {
                    "Room全值核对失败: scheduled_courses数量不匹配 (期望 ${expectedCourses.size}, 实际 ${loadedCourses.size})"
                }
                for (ec in expectedCourses) {
                    val lc = loadedCourses.firstOrNull { it.id == ec.id }
                    checkNotNull(lc) { "Room全值核对失败: scheduled_courses缺失课程 id=${ec.id}" }
                    check(
                        lc.courseName == ec.courseName &&
                        lc.teacher == ec.teacher &&
                        lc.room == ec.room &&
                        lc.color == ec.color &&
                        lc.sourceCourseCode == ec.sourceCourseCode &&
                        lc.sourceClassCode == ec.sourceClassCode
                    ) {
                        "Room全值核对失败: scheduled_courses字段不匹配 id=${ec.id}"
                    }
                }

                // 3. Course meetings full values
                val loadedMeetings = scheduleDao.meetings(s.id)
                val expectedMeetings = profile.courseMeetings.filter { cm ->
                    profile.scheduledCourses.any { sc -> sc.id == cm.courseId && sc.semesterId == s.id }
                }
                check(loadedMeetings.size == expectedMeetings.size) {
                    "Room全值核对失败: course_meetings数量不匹配 (期望 ${expectedMeetings.size}, 实际 ${loadedMeetings.size})"
                }
                for (em in expectedMeetings) {
                    val lm = loadedMeetings.firstOrNull { it.id == em.id }
                    checkNotNull(lm) { "Room全值核对失败: course_meetings缺失安排 id=${em.id}" }
                    check(
                        lm.courseId == em.courseId &&
                        lm.day == em.day &&
                        lm.customStartMinute == em.customStartMinute &&
                        lm.customEndMinute == em.customEndMinute &&
                        lm.teacher == em.teacher &&
                        lm.room == em.room &&
                        lm.kind == em.kind &&
                        lm.experimentProject == em.experimentProject &&
                        lm.group == em.group &&
                        lm.isExperiment == em.isExperiment
                    ) {
                        "Room全值核对失败: course_meetings字段不匹配 id=${em.id}"
                    }
                }

                // 4. Meeting exceptions full values
                val loadedExceptions = scheduleDao.exceptions(s.id)
                val expectedExceptions = profile.meetingExceptions.filter { me ->
                    profile.courseMeetings.any { cm ->
                        cm.id == me.meetingId && profile.scheduledCourses.any { sc -> sc.id == cm.courseId && sc.semesterId == s.id }
                    }
                }
                check(loadedExceptions.size == expectedExceptions.size) {
                    "Room全值核对失败: meeting_exceptions数量不匹配 (期望 ${expectedExceptions.size}, 实际 ${loadedExceptions.size})"
                }
                for (ee in expectedExceptions) {
                    val le = loadedExceptions.firstOrNull { it.id == ee.id }
                    checkNotNull(le) { "Room全值核对失败: meeting_exceptions缺失例外 id=${ee.id}" }
                    check(
                        le.meetingId == ee.meetingId &&
                        le.kind == ee.kind &&
                        le.originalEpochDay == ee.originalEpochDay &&
                        le.targetEpochDay == ee.targetEpochDay &&
                        le.customStartMinute == ee.customStartMinute &&
                        le.customEndMinute == ee.customEndMinute &&
                        le.hasSectionsOverride == ee.hasSectionsOverride &&
                        le.teacher == ee.teacher &&
                        le.room == ee.room &&
                        le.experimentProject == ee.experimentProject &&
                        le.group == ee.group
                    ) {
                        "Room全值核对失败: meeting_exceptions字段不匹配 id=${ee.id}"
                    }
                }

                // 5. Import normalization state full values
                val loadedImportStates = scheduleDao.importStates(s.id)
                val expectedImportStates = profile.importNormalizationState.filter { ins ->
                    profile.scheduledCourses.any { sc -> sc.id == ins.courseId && sc.semesterId == s.id }
                }
                check(loadedImportStates.size == expectedImportStates.size) {
                    "Room全值核对失败: import_normalization_state数量不匹配"
                }
                for (es in expectedImportStates) {
                    val ls = loadedImportStates.firstOrNull { it.courseId == es.courseId }
                    checkNotNull(ls) { "Room全值核对失败: import_normalization_state缺失课程 id=${es.courseId}" }
                    check(
                        ls.version == es.version &&
                        ls.payload == es.payload
                    ) {
                        "Room全值核对失败: import_normalization_state字段不匹配 id=${es.courseId}"
                    }
                }
            }

            // 6. Notice adjustment events: all 25 fields + domain payload validation
            for (ne in profile.noticeAdjustmentEvents) {
                val le = noticeDao.eventById(ne.id)
                checkNotNull(le) { "Room全值核对失败: notice_adjustment_events缺失 id=${ne.id}" }
                check(
                    le.requestKey == ne.requestKey &&
                    le.semanticKey == ne.semanticKey &&
                    le.confirmDigest == ne.confirmDigest &&
                    le.scopeToken == ne.scopeToken &&
                    le.semesterId == ne.semesterId &&
                    le.kind == ne.kind &&
                    le.courseId == ne.courseId &&
                    le.courseTitle == ne.courseTitle &&
                    le.logicalBaseId == ne.logicalBaseId &&
                    le.structureFingerprint == ne.structureFingerprint &&
                    le.meetingId == ne.meetingId &&
                    le.originalEpochDay == ne.originalEpochDay &&
                    le.targetEpochDay == ne.targetEpochDay &&
                    le.beforeJson == ne.beforeJson &&
                    le.afterJson == ne.afterJson &&
                    le.afterFingerprint == ne.afterFingerprint &&
                    le.payloadJson == ne.payloadJson &&
                    le.sourceText == ne.sourceText &&
                    le.sourceDigest == ne.sourceDigest &&
                    le.dateBasisJson == ne.dateBasisJson &&
                    le.appliedRevision == ne.appliedRevision &&
                    le.undoneRevision == ne.undoneRevision &&
                    le.state == ne.state &&
                    le.activeKey == ne.activeKey
                ) {
                    "Room全值核对失败: notice_adjustment_events字段不匹配 id=${ne.id}"
                }

                // Strict domain payload codec verification
                val decoded = NoticeEventCodec.decodePayload(le.payloadJson)
                check(decoded.mode.name.isNotBlank()) { "调课事件 payload 领域解码 mode 无效" }
            }

            // 7. Verify SQLite sequences consistency
            val rawDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                rawDb.rawQuery("SELECT name, seq FROM sqlite_sequence", null).use { cursor ->
                    val seqMap = mutableMapOf<String, Long>()
                    while (cursor.moveToNext()) {
                        seqMap[cursor.getString(0)] = cursor.getLong(1)
                    }
                    for (seqDto in profile.sequences) {
                        val actualSeq = seqMap[seqDto.name]
                        if (actualSeq != null) {
                            check(actualSeq >= seqDto.seq) {
                                "sqlite_sequence 比对失败: 表 ${seqDto.name} 期望 >= ${seqDto.seq}, 实际 $actualSeq"
                            }
                        }
                    }
                }
            } finally {
                rawDb.close()
            }
        } finally {
            roomDb.close()
        }
    }
}
