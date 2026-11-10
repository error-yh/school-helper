/* SPDX-License-Identifier: GPL-3.0-only
 * Safely discovers all valid profiles, acquires snapshot lease, and reads full Schema 5 tables.
 */
package com.lingion.sleepy.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

object TimetableSnapshotReader {

    private val PROFILE_DB_REGEX = Regex("^timetable_([A-Za-z0-9_-]{1,64})\\.db$")

    suspend fun captureSnapshot(context: Context): TimetableBackupPayloadDto {
        return TimetableWriteCoordinator.withSnapshotLease {
            readSnapshotUnderLease(context)
        }
    }

    private fun readSnapshotUnderLease(context: Context): TimetableBackupPayloadDto {
        // 1. Read active profile id
        val profilePrefs = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
        val activeProfileId = profilePrefs.getString("id", null)
            ?: throw IllegalStateException("未找到活动的本地课表Profile (timetable_local_profile.id 为空)")

        // 2. Discover all profiles from databaseList()
        val allDbs = context.databaseList() ?: emptyArray()
        val discoveredProfileIds = mutableSetOf<String>()
        for (dbName in allDbs) {
            val match = PROFILE_DB_REGEX.matchEntire(dbName)
            if (match != null) {
                discoveredProfileIds.add(match.groupValues[1])
            }
        }

        if (!discoveredProfileIds.contains(activeProfileId)) {
            val activeDbFile = context.getDatabasePath("timetable_$activeProfileId.db")
            if (activeDbFile.exists()) {
                discoveredProfileIds.add(activeProfileId)
            } else {
                throw IllegalStateException("活动Profile数据库文件不存在: timetable_$activeProfileId.db")
            }
        }

        // 3. Read and check display groups
        val groupPrefs = context.getSharedPreferences("course_display_groups", Context.MODE_PRIVATE)
        val allGroupEntries = groupPrefs.all
        val groupsByProfile = mutableMapOf<String, MutableList<CourseDisplayGroupBackupDto>>()

        for ((key, value) in allGroupEntries) {
            if (value !is String) {
                throw IllegalStateException("course_display_groups 存在非法非字符串配置项: key=$key, value=$value")
            }
            val parts = key.split("|")
            if (parts.size == 3 && parts[0] == "v1" && parts[1].startsWith("scope=")) {
                val scope = parts[1].removePrefix("scope=")
                if (!discoveredProfileIds.contains(scope)) {
                    throw IllegalStateException("发现孤立的展示分组配置 (所属Profile $scope 不存在)，已中止备份")
                }
                groupsByProfile.getOrPut(scope) { mutableListOf() }
                    .add(CourseDisplayGroupBackupDto(key, value))
            }
        }

        // 4. Read each profile under snapshot lease
        val sortedProfiles = discoveredProfileIds.sorted()
        val profileDtos = mutableListOf<ProfileBackupDto>()

        for (profileId in sortedProfiles) {
            val dbFile = context.getDatabasePath("timetable_$profileId.db")
            if (!dbFile.exists()) {
                throw IllegalStateException("Profile数据库文件不存在: ${dbFile.name}")
            }
            val dto = readSingleProfileDatabase(dbFile, profileId, groupsByProfile[profileId] ?: emptyList())
            profileDtos.add(dto)
        }

        val packageInfo = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }
        val sourceAppId = context.packageName ?: "com.aggapp.app"
        val sourceVersionCode = if (packageInfo != null) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } else {
            2L
        }

        val payload = TimetableBackupPayloadDto(
            formatVersion = TIMETABLE_BACKUP_FORMAT_VERSION,
            roomSchemaVersion = TIMETABLE_BACKUP_ROOM_SCHEMA_VERSION,
            schemaIdentityHash = TIMETABLE_BACKUP_SCHEMA_IDENTITY_HASH,
            sourceAppId = sourceAppId,
            sourceVersionCode = sourceVersionCode,
            createdAt = System.currentTimeMillis(),
            activeProfileId = activeProfileId,
            profiles = profileDtos,
            contentDigest = "",
        )

        // Validate structure and foreign keys
        TimetableBackupValidator.validate(payload)

        // Compute canonical content digest
        val digest = TimetableBackupDigest.calculateDigest(payload)
        return payload.copy(contentDigest = digest)
    }

    internal fun readSingleProfileDatabase(
        dbFile: File,
        profileId: String,
        displayGroups: List<CourseDisplayGroupBackupDto>,
    ): ProfileBackupDto {
        val db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.beginTransactionNonExclusive()
            try {
                // Check identity hash from room_master_table if present
                var identityHash = ""
                try {
                    db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42 LIMIT 1", null).use { cursor ->
                        if (cursor.moveToFirst()) {
                            identityHash = cursor.getString(0) ?: ""
                        }
                    }
                } catch (_: Exception) {}
                if (identityHash.isNotEmpty() && identityHash != TIMETABLE_BACKUP_SCHEMA_IDENTITY_HASH) {
                    throw IllegalStateException("Profile $profileId 数据库 identityHash 不匹配: $identityHash != $TIMETABLE_BACKUP_SCHEMA_IDENTITY_HASH")
                }

            // 1. courses
            val courses = mutableListOf<CourseBackupDto>()
            db.rawQuery("SELECT id, courseName, day, startNode, step, startWeek, endWeek, type FROM courses ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    courses.add(
                        CourseBackupDto(
                            id = c.getLong(0),
                            courseName = c.getString(1),
                            day = c.getInt(2),
                            startNode = c.getInt(3),
                            step = c.getInt(4),
                            startWeek = c.getInt(5),
                            endWeek = c.getInt(6),
                            type = c.getInt(7),
                        )
                    )
                }
            }

            // 2. semesters
            val semesters = mutableListOf<SemesterBackupDto>()
            db.rawQuery("SELECT id, name, startEpochDay, totalWeeks, revision FROM semesters ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    semesters.add(
                        SemesterBackupDto(
                            id = c.getLong(0),
                            name = c.getString(1),
                            startEpochDay = if (c.isNull(2)) null else c.getLong(2),
                            totalWeeks = c.getInt(3),
                            revision = c.getLong(4),
                        )
                    )
                }
            }

            // 3. periods
            val periods = mutableListOf<PeriodBackupDto>()
            db.rawQuery("SELECT semesterId, number, startMinute, endMinute FROM periods ORDER BY semesterId, number", null).use { c ->
                while (c.moveToNext()) {
                    periods.add(
                        PeriodBackupDto(
                            semesterId = c.getLong(0),
                            number = c.getInt(1),
                            startMinute = c.getInt(2),
                            endMinute = c.getInt(3),
                        )
                    )
                }
            }

            // 4. scheduled_courses
            val scheduledCourses = mutableListOf<ScheduledCourseBackupDto>()
            db.rawQuery("SELECT id, semesterId, courseName, teacher, room, color, sourceCourseCode, sourceClassCode FROM scheduled_courses ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    scheduledCourses.add(
                        ScheduledCourseBackupDto(
                            id = c.getLong(0),
                            semesterId = c.getLong(1),
                            courseName = c.getString(2),
                            teacher = c.getString(3),
                            room = c.getString(4),
                            color = c.getLong(5),
                            sourceCourseCode = if (c.isNull(6)) null else c.getString(6),
                            sourceClassCode = if (c.isNull(7)) null else c.getString(7),
                        )
                    )
                }
            }

            // 5. course_meetings
            val courseMeetings = mutableListOf<CourseMeetingBackupDto>()
            db.rawQuery("SELECT id, courseId, day, customStartMinute, customEndMinute, teacher, room, kind, experimentProject, `group`, isExperiment FROM course_meetings ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    courseMeetings.add(
                        CourseMeetingBackupDto(
                            id = c.getLong(0),
                            courseId = c.getLong(1),
                            day = c.getInt(2),
                            customStartMinute = if (c.isNull(3)) null else c.getInt(3),
                            customEndMinute = if (c.isNull(4)) null else c.getInt(4),
                            teacher = if (c.isNull(5)) null else c.getString(5),
                            room = if (c.isNull(6)) null else c.getString(6),
                            kind = c.getString(7),
                            experimentProject = if (c.isNull(8)) null else c.getString(8),
                            group = if (c.isNull(9)) null else c.getString(9),
                            isExperiment = c.getInt(10) != 0,
                        )
                    )
                }
            }

            // 6. meeting_weeks
            val meetingWeeks = mutableListOf<MeetingWeekBackupDto>()
            db.rawQuery("SELECT meetingId, week FROM meeting_weeks ORDER BY meetingId, week", null).use { c ->
                while (c.moveToNext()) {
                    meetingWeeks.add(
                        MeetingWeekBackupDto(
                            meetingId = c.getLong(0),
                            week = c.getInt(1),
                        )
                    )
                }
            }

            // 7. meeting_sections
            val meetingSections = mutableListOf<MeetingSectionBackupDto>()
            db.rawQuery("SELECT meetingId, section FROM meeting_sections ORDER BY meetingId, section", null).use { c ->
                while (c.moveToNext()) {
                    meetingSections.add(
                        MeetingSectionBackupDto(
                            meetingId = c.getLong(0),
                            section = c.getInt(1),
                        )
                    )
                }
            }

            // 8. meeting_dates
            val meetingDates = mutableListOf<MeetingDateBackupDto>()
            db.rawQuery("SELECT meetingId, epochDay FROM meeting_dates ORDER BY meetingId, epochDay", null).use { c ->
                while (c.moveToNext()) {
                    meetingDates.add(
                        MeetingDateBackupDto(
                            meetingId = c.getLong(0),
                            epochDay = c.getLong(1),
                        )
                    )
                }
            }

            // 9. meeting_declared_weeks
            val meetingDeclaredWeeks = mutableListOf<MeetingDeclaredWeekBackupDto>()
            db.rawQuery("SELECT meetingId, week FROM meeting_declared_weeks ORDER BY meetingId, week", null).use { c ->
                while (c.moveToNext()) {
                    meetingDeclaredWeeks.add(
                        MeetingDeclaredWeekBackupDto(
                            meetingId = c.getLong(0),
                            week = c.getInt(1),
                        )
                    )
                }
            }

            // 10. meeting_exceptions
            val meetingExceptions = mutableListOf<MeetingExceptionBackupDto>()
            db.rawQuery("SELECT id, meetingId, kind, originalEpochDay, targetEpochDay, customStartMinute, customEndMinute, hasSectionsOverride, teacher, room, experimentProject, `group` FROM meeting_exceptions ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    meetingExceptions.add(
                        MeetingExceptionBackupDto(
                            id = c.getLong(0),
                            meetingId = c.getLong(1),
                            kind = c.getString(2),
                            originalEpochDay = if (c.isNull(3)) null else c.getLong(3),
                            targetEpochDay = if (c.isNull(4)) null else c.getLong(4),
                            customStartMinute = if (c.isNull(5)) null else c.getInt(5),
                            customEndMinute = if (c.isNull(6)) null else c.getInt(6),
                            hasSectionsOverride = c.getInt(7) != 0,
                            teacher = if (c.isNull(8)) null else c.getString(8),
                            room = if (c.isNull(9)) null else c.getString(9),
                            experimentProject = if (c.isNull(10)) null else c.getString(10),
                            group = if (c.isNull(11)) null else c.getString(11),
                        )
                    )
                }
            }

            // 11. exception_sections
            val exceptionSections = mutableListOf<ExceptionSectionBackupDto>()
            db.rawQuery("SELECT exceptionId, section FROM exception_sections ORDER BY exceptionId, section", null).use { c ->
                while (c.moveToNext()) {
                    exceptionSections.add(
                        ExceptionSectionBackupDto(
                            exceptionId = c.getLong(0),
                            section = c.getInt(1),
                        )
                    )
                }
            }

            // 12. schedule_provenance
            val provenance = mutableListOf<ScheduleProvenanceBackupDto>()
            db.rawQuery("SELECT id, courseId, meetingId, exceptionId, position, sourceId, adapterId, page, left, top, right, bottom, rawText, sourceLocator FROM schedule_provenance ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    provenance.add(
                        ScheduleProvenanceBackupDto(
                            id = c.getLong(0),
                            courseId = if (c.isNull(1)) null else c.getLong(1),
                            meetingId = if (c.isNull(2)) null else c.getLong(2),
                            exceptionId = if (c.isNull(3)) null else c.getLong(3),
                            position = c.getInt(4),
                            sourceId = if (c.isNull(5)) null else c.getString(5),
                            adapterId = if (c.isNull(6)) null else c.getString(6),
                            page = if (c.isNull(7)) null else c.getInt(7),
                            left = if (c.isNull(8)) null else c.getDouble(8),
                            top = if (c.isNull(9)) null else c.getDouble(9),
                            right = if (c.isNull(10)) null else c.getDouble(10),
                            bottom = if (c.isNull(11)) null else c.getDouble(11),
                            rawText = c.getString(12),
                            sourceLocator = if (c.isNull(13)) null else c.getString(13),
                        )
                    )
                }
            }

            // 13. import_normalization_state
            val normalizationStates = mutableListOf<ImportNormalizationStateBackupDto>()
            db.rawQuery("SELECT courseId, version, payload FROM import_normalization_state ORDER BY courseId", null).use { c ->
                while (c.moveToNext()) {
                    normalizationStates.add(
                        ImportNormalizationStateBackupDto(
                            courseId = c.getLong(0),
                            version = c.getInt(1),
                            payload = c.getString(2),
                        )
                    )
                }
            }

            // 14. notice_adjustment_events
            val noticeEvents = mutableListOf<NoticeAdjustmentEventBackupDto>()
            db.rawQuery("SELECT id, requestKey, semanticKey, confirmDigest, scopeToken, semesterId, kind, courseId, courseTitle, logicalBaseId, structureFingerprint, meetingId, originalEpochDay, targetEpochDay, beforeJson, afterJson, afterFingerprint, payloadJson, sourceText, sourceDigest, dateBasisJson, appliedRevision, undoneRevision, state, activeKey FROM notice_adjustment_events ORDER BY id", null).use { c ->
                while (c.moveToNext()) {
                    noticeEvents.add(
                        NoticeAdjustmentEventBackupDto(
                            id = c.getLong(0),
                            requestKey = c.getString(1),
                            semanticKey = c.getString(2),
                            confirmDigest = c.getString(3),
                            scopeToken = c.getString(4),
                            semesterId = c.getLong(5),
                            kind = c.getString(6),
                            courseId = if (c.isNull(7)) null else c.getLong(7),
                            courseTitle = if (c.isNull(8)) null else c.getString(8),
                            logicalBaseId = if (c.isNull(9)) null else c.getString(9),
                            structureFingerprint = if (c.isNull(10)) null else c.getString(10),
                            meetingId = if (c.isNull(11)) null else c.getLong(11),
                            originalEpochDay = if (c.isNull(12)) null else c.getLong(12),
                            targetEpochDay = if (c.isNull(13)) null else c.getLong(13),
                            beforeJson = if (c.isNull(14)) null else c.getString(14),
                            afterJson = if (c.isNull(15)) null else c.getString(15),
                            afterFingerprint = c.getString(16),
                            payloadJson = c.getString(17),
                            sourceText = if (c.isNull(18)) null else c.getString(18),
                            sourceDigest = c.getString(19),
                            dateBasisJson = if (c.isNull(20)) null else c.getString(20),
                            appliedRevision = c.getLong(21),
                            undoneRevision = if (c.isNull(22)) null else c.getLong(22),
                            state = c.getString(23),
                            activeKey = if (c.isNull(24)) null else c.getString(24),
                        )
                    )
                }
            }

            // 15. sqlite_sequence
            val sequences = mutableListOf<SqliteSequenceBackupDto>()
            val hasSequenceTable = db.rawQuery(
                "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='sqlite_sequence'",
                null
            ).use { it.moveToFirst() && it.getInt(0) > 0 }

            if (hasSequenceTable) {
                db.rawQuery("SELECT name, seq FROM sqlite_sequence ORDER BY name", null).use { c ->
                    while (c.moveToNext()) {
                        sequences.add(
                            SqliteSequenceBackupDto(
                                name = c.getString(0),
                                seq = c.getLong(1),
                            )
                        )
                    }
                }
            }

            val profileDto = ProfileBackupDto(
                profileId = profileId,
                courses = courses,
                semesters = semesters,
                periods = periods,
                scheduledCourses = scheduledCourses,
                courseMeetings = courseMeetings,
                meetingWeeks = meetingWeeks,
                meetingSections = meetingSections,
                meetingDates = meetingDates,
                meetingDeclaredWeeks = meetingDeclaredWeeks,
                meetingExceptions = meetingExceptions,
                exceptionSections = exceptionSections,
                scheduleProvenance = provenance,
                importNormalizationState = normalizationStates,
                noticeAdjustmentEvents = noticeEvents,
                sequences = sequences,
                displayGroups = displayGroups,
            )
            db.setTransactionSuccessful()
            return profileDto
        } finally {
            db.endTransaction()
        }
    } finally {
        db.close()
    }
}
}
