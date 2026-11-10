/* SPDX-License-Identifier: GPL-3.0-only
 * Thorough semantic validation for Schema 5 timetable backup payloads.
 * Validates domain graphs, notice events, signature consistency, and sequence whitelist.
 */
package com.lingion.sleepy.data.backup

import com.lingion.sleepy.data.CourseDisplayGroupCodec
import com.lingion.sleepy.domain.notice.NoticeEventCodec
import com.lingion.sleepy.importing.NoticeIntentKind
import com.lingion.sleepy.ui.computeCourseDisplaySignature
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

object TimetableBackupValidator {

    private val ALLOWED_SEQUENCES = setOf(
        "courses",
        "scheduled_courses",
        "course_meetings",
        "meeting_exceptions",
        "schedule_provenance",
        "notice_adjustment_events",
    )

    private val ALLOWED_EVENT_STATES = setOf("APPLIED", "UNDONE")
    private val ALLOWED_EVENT_KINDS = NoticeIntentKind.entries.map { it.name }.toSet()

    fun validate(payload: TimetableBackupPayloadDto) {
        require(payload.formatVersion == TIMETABLE_BACKUP_FORMAT_VERSION) {
            "不支持的备份格式版本: ${payload.formatVersion}"
        }
        require(payload.roomSchemaVersion == TIMETABLE_BACKUP_ROOM_SCHEMA_VERSION) {
            "不支持的RoomSchema版本: ${payload.roomSchemaVersion}"
        }
        require(payload.schemaIdentityHash == TIMETABLE_BACKUP_SCHEMA_IDENTITY_HASH) {
            "Schema identityHash不匹配: ${payload.schemaIdentityHash}"
        }
        require(payload.profiles.isNotEmpty()) { "备份中未包含任何Profile" }
        require(payload.profiles.size <= TIMETABLE_BACKUP_MAX_PROFILES) {
            "Profile数量超过限制: ${payload.profiles.size}"
        }
        require(payload.totalRowCount() <= TIMETABLE_BACKUP_MAX_TOTAL_ROWS) {
            "总表行数超过限制: ${payload.totalRowCount()}"
        }

        val profileIds = mutableSetOf<String>()
        for (profile in payload.profiles) {
            require(profile.profileId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                "Profile ID格式非法: ${profile.profileId}"
            }
            require(profileIds.add(profile.profileId)) {
                "存在重复的Profile ID: ${profile.profileId}"
            }
            validateProfile(profile)
        }

        require(profileIds.contains(payload.activeProfileId)) {
            "活动Profile ID (${payload.activeProfileId}) 未包含在导出的Profile列表中"
        }
    }

    private fun validateProfile(profile: ProfileBackupDto) {
        // 1. Primary keys uniqueness
        val courseIds = mutableSetOf<Long>()
        profile.courses.forEach { require(courseIds.add(it.id)) { "courses主键重复: ${it.id}" } }

        val semesterIds = mutableSetOf<Long>()
        profile.semesters.forEach { require(semesterIds.add(it.id)) { "semesters主键重复: ${it.id}" } }

        val periodKeys = mutableSetOf<Pair<Long, Int>>()
        profile.periods.forEach { require(periodKeys.add(it.semesterId to it.number)) { "periods主键重复: (${it.semesterId}, ${it.number})" } }

        val scheduledCourseIds = mutableSetOf<Long>()
        val scheduledCourseMap = mutableMapOf<Long, ScheduledCourseBackupDto>()
        profile.scheduledCourses.forEach {
            require(scheduledCourseIds.add(it.id)) { "scheduled_courses主键重复: ${it.id}" }
            scheduledCourseMap[it.id] = it
        }

        val meetingIds = mutableSetOf<Long>()
        profile.courseMeetings.forEach { require(meetingIds.add(it.id)) { "course_meetings主键重复: ${it.id}" } }

        val meetingWeekKeys = mutableSetOf<Pair<Long, Int>>()
        profile.meetingWeeks.forEach { require(meetingWeekKeys.add(it.meetingId to it.week)) { "meeting_weeks主键重复: (${it.meetingId}, ${it.week})" } }

        val meetingSectionKeys = mutableSetOf<Pair<Long, Int>>()
        profile.meetingSections.forEach { require(meetingSectionKeys.add(it.meetingId to it.section)) { "meeting_sections主键重复: (${it.meetingId}, ${it.section})" } }

        val meetingDateKeys = mutableSetOf<Pair<Long, Long>>()
        profile.meetingDates.forEach { require(meetingDateKeys.add(it.meetingId to it.epochDay)) { "meeting_dates主键重复: (${it.meetingId}, ${it.epochDay})" } }

        val meetingDeclaredWeekKeys = mutableSetOf<Pair<Long, Int>>()
        profile.meetingDeclaredWeeks.forEach { require(meetingDeclaredWeekKeys.add(it.meetingId to it.week)) { "meeting_declared_weeks主键重复: (${it.meetingId}, ${it.week})" } }

        val exceptionIds = mutableSetOf<Long>()
        profile.meetingExceptions.forEach { require(exceptionIds.add(it.id)) { "meeting_exceptions主键重复: ${it.id}" } }

        val exceptionSectionKeys = mutableSetOf<Pair<Long, Int>>()
        profile.exceptionSections.forEach { require(exceptionSectionKeys.add(it.exceptionId to it.section)) { "exception_sections主键重复: (${it.exceptionId}, ${it.section})" } }

        val provenanceIds = mutableSetOf<Long>()
        profile.scheduleProvenance.forEach { require(provenanceIds.add(it.id)) { "schedule_provenance主键重复: ${it.id}" } }

        val normalizationStateIds = mutableSetOf<Long>()
        profile.importNormalizationState.forEach { require(normalizationStateIds.add(it.courseId)) { "import_normalization_state主键重复: ${it.courseId}" } }

        val eventIds = mutableSetOf<Long>()
        profile.noticeAdjustmentEvents.forEach { require(eventIds.add(it.id)) { "notice_adjustment_events主键重复: ${it.id}" } }

        // 2. Foreign keys validation
        profile.periods.forEach {
            require(semesterIds.contains(it.semesterId)) { "periods外键悬空: semesterId=${it.semesterId}" }
        }
        profile.scheduledCourses.forEach {
            require(semesterIds.contains(it.semesterId)) { "scheduled_courses外键悬空: semesterId=${it.semesterId}" }
        }
        profile.courseMeetings.forEach {
            require(scheduledCourseIds.contains(it.courseId)) { "course_meetings外键悬空: courseId=${it.courseId}" }
        }
        profile.meetingWeeks.forEach {
            require(meetingIds.contains(it.meetingId)) { "meeting_weeks外键悬空: meetingId=${it.meetingId}" }
        }
        profile.meetingSections.forEach {
            require(meetingIds.contains(it.meetingId)) { "meeting_sections外键悬空: meetingId=${it.meetingId}" }
        }
        profile.meetingDates.forEach {
            require(meetingIds.contains(it.meetingId)) { "meeting_dates外键悬空: meetingId=${it.meetingId}" }
        }
        profile.meetingDeclaredWeeks.forEach {
            require(meetingIds.contains(it.meetingId)) { "meeting_declared_weeks外键悬空: meetingId=${it.meetingId}" }
        }
        profile.meetingExceptions.forEach {
            require(meetingIds.contains(it.meetingId)) { "meeting_exceptions外键悬空: meetingId=${it.meetingId}" }
        }
        profile.exceptionSections.forEach {
            require(exceptionIds.contains(it.exceptionId)) { "exception_sections外键悬空: exceptionId=${it.exceptionId}" }
        }
        profile.scheduleProvenance.forEach {
            if (it.courseId != null) require(scheduledCourseIds.contains(it.courseId)) { "schedule_provenance外键悬空: courseId=${it.courseId}" }
            if (it.meetingId != null) require(meetingIds.contains(it.meetingId)) { "schedule_provenance外键悬空: meetingId=${it.meetingId}" }
            if (it.exceptionId != null) require(exceptionIds.contains(it.exceptionId)) { "schedule_provenance外键悬空: exceptionId=${it.exceptionId}" }
        }

        // 3. import_normalization_state deep validation
        profile.importNormalizationState.forEach {
            require(scheduledCourseIds.contains(it.courseId)) { "import_normalization_state外键悬空: courseId=${it.courseId}" }
            require(it.payload.isNotBlank()) { "import_normalization_state payload为空" }
            val jsonRoot = try {
                Json.parseToJsonElement(it.payload) as? JsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("import_normalization_state payload 不是有效的JSON: ${e.message}", e)
            } ?: throw IllegalArgumentException("import_normalization_state payload 根节点必须为JSON对象")

            require(jsonRoot.containsKey("version") && jsonRoot.containsKey("graph")) {
                "import_normalization_state payload 缺少 version 或 graph 根节点"
            }
        }

        // 4. notice_adjustment_events deep validation
        val activeKeys = mutableSetOf<String>()
        profile.noticeAdjustmentEvents.forEach {
            require(it.scopeToken == profile.profileId) {
                "notice_adjustment_events scopeToken (${it.scopeToken}) 与 Profile ID (${profile.profileId}) 不一致"
            }
            require(semesterIds.contains(it.semesterId)) { "notice_adjustment_events外键悬空: semesterId=${it.semesterId}" }
            require(ALLOWED_EVENT_STATES.contains(it.state)) {
                "notice_adjustment_events state 无效: ${it.state}"
            }
            require(ALLOWED_EVENT_KINDS.contains(it.kind)) {
                "notice_adjustment_events kind 无效: ${it.kind}"
            }
            require(it.afterFingerprint.isNotBlank()) { "notice_adjustment_events afterFingerprint 为空" }
            require(it.sourceDigest.isNotBlank()) { "notice_adjustment_events sourceDigest 为空" }

            // Validate against existing production domain codec
            try {
                NoticeEventCodec.decodePayload(it.payloadJson)
            } catch (e: Exception) {
                throw IllegalArgumentException("notice_adjustment_events payloadJson 领域反序列化失败: ${e.message}", e)
            }

            if (it.beforeJson != null) {
                try {
                    NoticeEventCodec.decodeView(it.beforeJson)
                } catch (e: Exception) {
                    throw IllegalArgumentException("notice_adjustment_events beforeJson 领域反序列化失败: ${e.message}", e)
                }
            }

            if (it.afterJson != null) {
                try {
                    NoticeEventCodec.decodeView(it.afterJson)
                } catch (e: Exception) {
                    throw IllegalArgumentException("notice_adjustment_events afterJson 领域反序列化失败: ${e.message}", e)
                }
            }

            if (it.dateBasisJson != null) {
                try {
                    NoticeEventCodec.decodeBasis(it.dateBasisJson)
                } catch (e: Exception) {
                    throw IllegalArgumentException("notice_adjustment_events dateBasisJson 领域反序列化失败: ${e.message}", e)
                }
            }

            if (it.state == "APPLIED") {
                val key = it.activeKey ?: throw IllegalArgumentException("APPLIED 状态的事件必须包含 activeKey")
                require(activeKeys.add(key)) { "重复的活动事件 activeKey: $key" }
            }

            if (it.courseId != null) {
                require(scheduledCourseIds.contains(it.courseId)) { "notice_adjustment_events外键悬空: courseId=${it.courseId}" }
            }
            if (it.meetingId != null) {
                require(meetingIds.contains(it.meetingId)) { "notice_adjustment_events外键悬空: meetingId=${it.meetingId}" }
            }
        }

        // 5. sqlite_sequence checks
        val maxIds = mapOf(
            "courses" to (profile.courses.maxOfOrNull { it.id } ?: 0L),
            "scheduled_courses" to (profile.scheduledCourses.maxOfOrNull { it.id } ?: 0L),
            "course_meetings" to (profile.courseMeetings.maxOfOrNull { it.id } ?: 0L),
            "meeting_exceptions" to (profile.meetingExceptions.maxOfOrNull { it.id } ?: 0L),
            "schedule_provenance" to (profile.scheduleProvenance.maxOfOrNull { it.id } ?: 0L),
            "notice_adjustment_events" to (profile.noticeAdjustmentEvents.maxOfOrNull { it.id } ?: 0L),
        )

        val seenSequences = mutableSetOf<String>()
        for (seq in profile.sequences) {
            require(ALLOWED_SEQUENCES.contains(seq.name)) {
                "发现未知或非白名单的sqlite_sequence表名: ${seq.name}"
            }
            require(seenSequences.add(seq.name)) {
                "存在重复的sqlite_sequence项: ${seq.name}"
            }
            val maxId = maxIds[seq.name] ?: 0L
            require(seq.seq >= maxId) {
                "表 ${seq.name} 的自增序号无效: seq(${seq.seq}) < maxId($maxId)"
            }
        }

        // 6. course_display_groups validation
        val seenStorageKeys = mutableSetOf<String>()
        val seenGroupIds = mutableSetOf<String>()

        for (groupEntry in profile.displayGroups) {
            val keyParts = groupEntry.storageKey.split("|")
            require(keyParts.size == 3 && keyParts[0] == "v1" && keyParts[1].startsWith("scope=") && keyParts[2].startsWith("semester=")) {
                "展示分组storageKey格式非法: ${groupEntry.storageKey}"
            }
            require(seenStorageKeys.add(groupEntry.storageKey)) {
                "存在重复的展示分组storageKey: ${groupEntry.storageKey}"
            }
            val groupScope = keyParts[1].removePrefix("scope=")
            require(groupScope == profile.profileId) {
                "展示分组scope (${groupScope}) 与 Profile ID (${profile.profileId}) 不一致"
            }
            val groupSemesterId = keyParts[2].removePrefix("semester=").toLongOrNull()
                ?: throw IllegalArgumentException("展示分组semesterId格式非法: ${groupEntry.storageKey}")

            require(semesterIds.contains(groupSemesterId)) {
                "展示分组引用的学期不存在: semesterId=$groupSemesterId"
            }

            val groups = CourseDisplayGroupCodec.decode(groupEntry.rawJson)
            for (group in groups) {
                require(seenGroupIds.add(group.id)) {
                    "存在重复的展示分组ID: ${group.id}"
                }
                for (member in group.members) {
                    val course = scheduledCourseMap[member.courseId]
                        ?: throw IllegalArgumentException("展示分组引用了不存在的课程ID: ${member.courseId}")

                    require(course.semesterId == groupSemesterId) {
                        "展示分组课程 (${member.courseId}) 所属学期 (${course.semesterId}) 与分组学期 ($groupSemesterId) 不一致"
                    }

                    // Strict signature check against actual course identity
                    val expectedSignature = computeCourseSignature(course)
                    require(member.signature == expectedSignature) {
                        "展示分组课程 (${member.courseId}) 签名不匹配: expected=$expectedSignature, actual=${member.signature}"
                    }
                }
            }
        }
    }

    fun computeCourseSignature(c: ScheduledCourseBackupDto): String {
        return computeCourseDisplaySignature(c.courseName, c.sourceCourseCode, c.sourceClassCode)
    }
}
