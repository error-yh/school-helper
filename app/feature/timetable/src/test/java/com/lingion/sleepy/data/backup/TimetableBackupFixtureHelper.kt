/* SPDX-License-Identifier: GPL-3.0-only
 * Synthetic Schema 5 fixture helper covering all 14 tables, multi-profile, sequences, and display groups.
 */
package com.lingion.sleepy.data.backup

import com.lingion.sleepy.data.CourseDisplayGroup
import com.lingion.sleepy.data.CourseDisplayGroupCodec
import com.lingion.sleepy.data.CourseGroupMember
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.notice.NoticeEventCodec
import com.lingion.sleepy.domain.notice.NoticeEventPayload
import com.lingion.sleepy.domain.notice.NoticeOccurrenceView
import com.lingion.sleepy.domain.notice.NoticeWriteMode
import com.lingion.sleepy.importing.NoticeDateBasis
import java.time.LocalDate
import java.time.LocalTime

object TimetableBackupFixtureHelper {

    fun createValidPayload(): TimetableBackupPayloadDto {
        val p1 = createProfile("profile_alpha", 1L)
        val p2 = createProfile("profile_beta", 2L)
        val payload = TimetableBackupPayloadDto(
            formatVersion = TIMETABLE_BACKUP_FORMAT_VERSION,
            roomSchemaVersion = TIMETABLE_BACKUP_ROOM_SCHEMA_VERSION,
            schemaIdentityHash = TIMETABLE_BACKUP_SCHEMA_IDENTITY_HASH,
            sourceAppId = "io.github.schoolhelper.app.debug",
            sourceVersionCode = 2L,
            createdAt = 1730700000000L,
            activeProfileId = "profile_alpha",
            profiles = listOf(p1, p2),
            contentDigest = "",
        )
        val digest = TimetableBackupDigest.calculateDigest(payload)
        return payload.copy(contentDigest = digest)
    }

    fun createProfile(profileId: String, idOffset: Long): ProfileBackupDto {
        val c1Id = idOffset * 100 + 1
        val c2Id = idOffset * 100 + 2

        val sem1Id = idOffset * 10 + 1
        val sem2Id = idOffset * 10 + 2

        // 1. courses (v1 archive)
        val courses = listOf(
            CourseBackupDto(id = c1Id, courseName = "高等数学v1", day = 1, startNode = 1, step = 2, startWeek = 1, endWeek = 16, type = 0),
            CourseBackupDto(id = c2Id, courseName = "大学英语v1", day = 2, startNode = 3, step = 2, startWeek = 1, endWeek = 16, type = 0),
        )

        // 2. semesters
        val semesters = listOf(
            SemesterBackupDto(id = sem1Id, name = "2026年秋季学期", startEpochDay = 20300L, totalWeeks = 20, revision = 5L),
            SemesterBackupDto(id = sem2Id, name = "2027年春季学期", startEpochDay = 20450L, totalWeeks = 18, revision = 1L),
        )

        // 3. periods
        val periods = listOf(
            PeriodBackupDto(semesterId = sem1Id, number = 1, startMinute = 480, endMinute = 525),
            PeriodBackupDto(semesterId = sem1Id, number = 2, startMinute = 535, endMinute = 580),
            PeriodBackupDto(semesterId = sem2Id, number = 1, startMinute = 480, endMinute = 525),
        )

        // 4. scheduled_courses
        val sc1Id = idOffset * 1000 + 10
        val sc2Id = idOffset * 1000 + 20
        val scheduledCourses = listOf(
            ScheduledCourseBackupDto(
                id = sc1Id,
                semesterId = sem1Id,
                courseName = "量子力学导论",
                teacher = "张教授",
                room = "理学楼101",
                color = 0xFF336699L,
                sourceCourseCode = "PHY301",
                sourceClassCode = "CLASS_01",
            ),
            ScheduledCourseBackupDto(
                id = sc2Id,
                semesterId = sem1Id,
                courseName = "量子计算实验",
                teacher = "李教授",
                room = "实验楼202",
                color = 0xFF88CC00L,
                sourceCourseCode = "PHY302",
                sourceClassCode = "CLASS_02",
            ),
        )

        // 5. course_meetings
        val cm1Id = idOffset * 10000 + 100
        val cm2Id = idOffset * 10000 + 200
        val courseMeetings = listOf(
            CourseMeetingBackupDto(
                id = cm1Id,
                courseId = sc1Id,
                day = 1,
                customStartMinute = null,
                customEndMinute = null,
                teacher = "张教授",
                room = "理学楼101",
                kind = "COMPACT",
                experimentProject = null,
                group = null,
                isExperiment = false,
            ),
            CourseMeetingBackupDto(
                id = cm2Id,
                courseId = sc2Id,
                day = 3,
                customStartMinute = 840,
                customEndMinute = 960,
                teacher = "李教授",
                room = "实验楼202",
                kind = "CUSTOM_TIME",
                experimentProject = "Shor算法模拟",
                group = "第一组",
                isExperiment = true,
            ),
        )

        // 6. meeting_weeks
        val meetingWeeks = listOf(
            MeetingWeekBackupDto(meetingId = cm1Id, week = 1),
            MeetingWeekBackupDto(meetingId = cm1Id, week = 2),
            MeetingWeekBackupDto(meetingId = cm2Id, week = 3),
        )

        // 7. meeting_sections
        val meetingSections = listOf(
            MeetingSectionBackupDto(meetingId = cm1Id, section = 1),
            MeetingSectionBackupDto(meetingId = cm1Id, section = 2),
            MeetingSectionBackupDto(meetingId = cm2Id, section = 7),
            MeetingSectionBackupDto(meetingId = cm2Id, section = 8),
        )

        // 8. meeting_dates
        val meetingDates = listOf(
            MeetingDateBackupDto(meetingId = cm1Id, epochDay = 20300L),
            MeetingDateBackupDto(meetingId = cm2Id, epochDay = 20314L),
        )

        // 9. meeting_declared_weeks
        val meetingDeclaredWeeks = listOf(
            MeetingDeclaredWeekBackupDto(meetingId = cm1Id, week = 1),
            MeetingDeclaredWeekBackupDto(meetingId = cm2Id, week = 3),
        )

        // 10. meeting_exceptions
        val me1Id = idOffset * 100000 + 1000
        val meetingExceptions = listOf(
            MeetingExceptionBackupDto(
                id = me1Id,
                meetingId = cm1Id,
                kind = "RESCHEDULE",
                originalEpochDay = 20300L,
                targetEpochDay = 20301L,
                customStartMinute = null,
                customEndMinute = null,
                hasSectionsOverride = true,
                teacher = "张教授",
                room = "理学楼102",
                experimentProject = null,
                group = null,
            )
        )

        // 11. exception_sections
        val exceptionSections = listOf(
            ExceptionSectionBackupDto(exceptionId = me1Id, section = 3),
            ExceptionSectionBackupDto(exceptionId = me1Id, section = 4),
        )

        // 12. schedule_provenance
        val sp1Id = idOffset * 1000000 + 10000
        val sp2Id = idOffset * 1000000 + 20000
        val scheduleProvenance = listOf(
            ScheduleProvenanceBackupDto(
                id = sp1Id,
                courseId = sc1Id,
                meetingId = cm1Id,
                exceptionId = null,
                position = 1,
                sourceId = "IMAGE_IMPORT_001",
                adapterId = "MATRIX_GRID",
                page = 1,
                left = 0.1,
                top = 0.2,
                right = 0.4,
                bottom = 0.5,
                rawText = "量子力学导论 周一1-2节",
                sourceLocator = "page1_row2_col1",
            ),
            ScheduleProvenanceBackupDto(
                id = sp2Id,
                courseId = null,
                meetingId = null,
                exceptionId = me1Id,
                position = 2,
                sourceId = "MANUAL_ADJUSTMENT",
                adapterId = "USER_INPUT",
                page = null,
                left = null,
                top = null,
                right = null,
                bottom = null,
                rawText = "调课申请至周二3-4节",
                sourceLocator = null,
            ),
        )

        // 13. import_normalization_state
        val importNormalizationState = listOf(
            ImportNormalizationStateBackupDto(
                courseId = sc1Id,
                version = 1,
                payload = """{"version":1,"scope":"$profileId","semester":$sem1Id,"course":$sc1Id,"graph":{"originals":[],"removes":[],"adds":[],"groups":[],"sources":[]},"fragments":[],"ownedAdds":[],"sources":[]}""",
            )
        )

        // 14. notice_adjustment_events using production NoticeIntentKind and valid NoticeEventCodec payloads
        val nae1Id = idOffset * 10000000 + 100000
        val nae2Id = idOffset * 10000000 + 200000

        val view1 = NoticeOccurrenceView(
            date = LocalDate.ofEpochDay(20300L),
            sections = setOf(1, 2),
            ranges = listOf(TimeRange(LocalTime.of(8, 0), LocalTime.of(9, 35))),
            teacher = "张教授",
            room = "理学楼101",
            project = null,
            group = null,
        )
        val beforeJson1 = NoticeEventCodec.encodeView(view1)
        val afterJson1 = NoticeEventCodec.encodeView(view1.copy(room = "理学楼102"))

        val payload1 = NoticeEventPayload(
            version = 1,
            mode = NoticeWriteMode.MANUAL,
            exceptionIds = listOf(me1Id),
            exceptionFingerprints = listOf("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
            previous = null,
        )
        val payloadJson1 = NoticeEventCodec.encodePayload(payload1)

        val basis1 = NoticeDateBasis(
            referenceDate = LocalDate.of(2026, 9, 7),
            termMonday = LocalDate.of(2026, 9, 7),
            totalWeeks = 20,
            confirmedYear = 2026,
        )
        val dateBasisJson1 = NoticeEventCodec.encodeBasis(basis1)

        val payload2 = NoticeEventPayload(
            version = 1,
            mode = NoticeWriteMode.MANUAL,
            exceptionIds = emptyList(),
            exceptionFingerprints = emptyList(),
            previous = null,
        )
        val payloadJson2 = NoticeEventCodec.encodePayload(payload2)

        val noticeAdjustmentEvents = listOf(
            NoticeAdjustmentEventBackupDto(
                id = nae1Id,
                requestKey = "req_applied_$profileId",
                semanticKey = "sem_applied_$profileId",
                confirmDigest = "digest_applied",
                scopeToken = profileId,
                semesterId = sem1Id,
                kind = "RESCHEDULE_TIME", // Valid NoticeIntentKind
                courseId = sc1Id,
                courseTitle = "量子力学导论",
                logicalBaseId = "base_1",
                structureFingerprint = "fp_struct_1",
                meetingId = cm1Id,
                originalEpochDay = 20300L,
                targetEpochDay = 20301L,
                beforeJson = beforeJson1,
                afterJson = afterJson1,
                afterFingerprint = "fp_after_1",
                payloadJson = payloadJson1,
                sourceText = "通知：原周一课程调至周二理学楼102",
                sourceDigest = "src_digest_1",
                dateBasisJson = dateBasisJson1,
                appliedRevision = 5L,
                undoneRevision = null,
                state = "APPLIED",
                activeKey = "req_applied_$profileId",
            ),
            NoticeAdjustmentEventBackupDto(
                id = nae2Id,
                requestKey = "req_undone_$profileId",
                semanticKey = "sem_undone_$profileId",
                confirmDigest = "digest_undone",
                scopeToken = profileId,
                semesterId = sem1Id,
                kind = "CANCEL", // Valid NoticeIntentKind
                courseId = null,
                courseTitle = "已废弃课程",
                logicalBaseId = null,
                structureFingerprint = null,
                meetingId = null,
                originalEpochDay = 20305L,
                targetEpochDay = null,
                beforeJson = null,
                afterJson = null,
                afterFingerprint = "fp_after_undone",
                payloadJson = payloadJson2,
                sourceText = null,
                sourceDigest = "src_digest_2",
                dateBasisJson = null,
                appliedRevision = 4L,
                undoneRevision = 5L,
                state = "UNDONE",
                activeKey = null,
            ),
        )

        // 15. sequences: set seq > maxId (e.g. max courses is c2Id, seq is c2Id + 50)
        val sequences = listOf(
            SqliteSequenceBackupDto(name = "courses", seq = c2Id + 50L),
            SqliteSequenceBackupDto(name = "scheduled_courses", seq = sc2Id + 100L),
            SqliteSequenceBackupDto(name = "course_meetings", seq = cm2Id + 100L),
            SqliteSequenceBackupDto(name = "meeting_exceptions", seq = me1Id + 100L),
            SqliteSequenceBackupDto(name = "schedule_provenance", seq = sp2Id + 100L),
            SqliteSequenceBackupDto(name = "notice_adjustment_events", seq = nae2Id + 100L),
        )

        // 16. displayGroups: strict and valid signatures against scheduledCourses
        val signature1 = TimetableBackupValidator.computeCourseSignature(scheduledCourses[0])
        val signature2 = TimetableBackupValidator.computeCourseSignature(scheduledCourses[1])
        val displayGroup = CourseDisplayGroup(
            id = "group_quantum_$profileId",
            name = "量子物理课程组",
            members = listOf(
                CourseGroupMember(courseId = sc1Id, signature = signature1),
                CourseGroupMember(courseId = sc2Id, signature = signature2),
            ),
        )
        val groupRawJson = CourseDisplayGroupCodec.encode(listOf(displayGroup))
        val displayGroups = listOf(
            CourseDisplayGroupBackupDto(
                storageKey = "v1|scope=$profileId|semester=$sem1Id",
                rawJson = groupRawJson,
            )
        )

        return ProfileBackupDto(
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
            scheduleProvenance = scheduleProvenance,
            importNormalizationState = importNormalizationState,
            noticeAdjustmentEvents = noticeAdjustmentEvents,
            sequences = sequences,
            displayGroups = displayGroups,
        )
    }
}
