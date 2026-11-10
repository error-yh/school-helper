/* SPDX-License-Identifier: GPL-3.0-only
 * Strict DTOs for lossless Room Schema 5 timetable backup, inspection and recovery.
 */
package com.lingion.sleepy.data.backup

import kotlinx.serialization.Serializable

const val TIMETABLE_BACKUP_FORMAT_VERSION = 1
const val TIMETABLE_BACKUP_ROOM_SCHEMA_VERSION = 5
const val TIMETABLE_BACKUP_SCHEMA_IDENTITY_HASH = "f206f0bcf430e0ba497f4d18127ca595"

const val TIMETABLE_BACKUP_MAX_PROFILES = 32
const val TIMETABLE_BACKUP_MAX_TOTAL_ROWS = 500000
const val TIMETABLE_BACKUP_MAX_PLAINTEXT_BYTES = 64 * 1024 * 1024 // 64 MiB
const val TIMETABLE_BACKUP_MAX_CONTAINER_BYTES = 65 * 1024 * 1024 // 65 MiB
const val TIMETABLE_BACKUP_MAX_JSON_DEPTH = 64

@Serializable
data class CourseBackupDto(
    val id: Long,
    val courseName: String,
    val day: Int,
    val startNode: Int,
    val step: Int,
    val startWeek: Int,
    val endWeek: Int,
    val type: Int,
)

@Serializable
data class SemesterBackupDto(
    val id: Long,
    val name: String,
    val startEpochDay: Long?,
    val totalWeeks: Int,
    val revision: Long,
)

@Serializable
data class PeriodBackupDto(
    val semesterId: Long,
    val number: Int,
    val startMinute: Int,
    val endMinute: Int,
)

@Serializable
data class ScheduledCourseBackupDto(
    val id: Long,
    val semesterId: Long,
    val courseName: String,
    val teacher: String,
    val room: String,
    val color: Long,
    val sourceCourseCode: String?,
    val sourceClassCode: String?,
)

@Serializable
data class CourseMeetingBackupDto(
    val id: Long,
    val courseId: Long,
    val day: Int,
    val customStartMinute: Int?,
    val customEndMinute: Int?,
    val teacher: String?,
    val room: String?,
    val kind: String,
    val experimentProject: String?,
    val group: String?,
    val isExperiment: Boolean,
)

@Serializable
data class MeetingWeekBackupDto(
    val meetingId: Long,
    val week: Int,
)

@Serializable
data class MeetingSectionBackupDto(
    val meetingId: Long,
    val section: Int,
)

@Serializable
data class MeetingDateBackupDto(
    val meetingId: Long,
    val epochDay: Long,
)

@Serializable
data class MeetingDeclaredWeekBackupDto(
    val meetingId: Long,
    val week: Int,
)

@Serializable
data class MeetingExceptionBackupDto(
    val id: Long,
    val meetingId: Long,
    val kind: String,
    val originalEpochDay: Long?,
    val targetEpochDay: Long?,
    val customStartMinute: Int?,
    val customEndMinute: Int?,
    val hasSectionsOverride: Boolean,
    val teacher: String?,
    val room: String?,
    val experimentProject: String?,
    val group: String?,
)

@Serializable
data class ExceptionSectionBackupDto(
    val exceptionId: Long,
    val section: Int,
)

@Serializable
data class ScheduleProvenanceBackupDto(
    val id: Long,
    val courseId: Long?,
    val meetingId: Long?,
    val exceptionId: Long?,
    val position: Int,
    val sourceId: String?,
    val adapterId: String?,
    val page: Int?,
    val left: Double?,
    val top: Double?,
    val right: Double?,
    val bottom: Double?,
    val rawText: String,
    val sourceLocator: String?,
)

@Serializable
data class ImportNormalizationStateBackupDto(
    val courseId: Long,
    val version: Int,
    val payload: String,
)

@Serializable
data class NoticeAdjustmentEventBackupDto(
    val id: Long,
    val requestKey: String,
    val semanticKey: String,
    val confirmDigest: String,
    val scopeToken: String,
    val semesterId: Long,
    val kind: String,
    val courseId: Long?,
    val courseTitle: String?,
    val logicalBaseId: String?,
    val structureFingerprint: String?,
    val meetingId: Long?,
    val originalEpochDay: Long?,
    val targetEpochDay: Long?,
    val beforeJson: String?,
    val afterJson: String?,
    val afterFingerprint: String,
    val payloadJson: String,
    val sourceText: String?,
    val sourceDigest: String,
    val dateBasisJson: String?,
    val appliedRevision: Long,
    val undoneRevision: Long?,
    val state: String,
    val activeKey: String?,
)

@Serializable
data class SqliteSequenceBackupDto(
    val name: String,
    val seq: Long,
)

@Serializable
data class CourseDisplayGroupBackupDto(
    val storageKey: String,
    val rawJson: String,
)

@Serializable
data class ProfileBackupDto(
    val profileId: String,
    val courses: List<CourseBackupDto> = emptyList(),
    val semesters: List<SemesterBackupDto> = emptyList(),
    val periods: List<PeriodBackupDto> = emptyList(),
    val scheduledCourses: List<ScheduledCourseBackupDto> = emptyList(),
    val courseMeetings: List<CourseMeetingBackupDto> = emptyList(),
    val meetingWeeks: List<MeetingWeekBackupDto> = emptyList(),
    val meetingSections: List<MeetingSectionBackupDto> = emptyList(),
    val meetingDates: List<MeetingDateBackupDto> = emptyList(),
    val meetingDeclaredWeeks: List<MeetingDeclaredWeekBackupDto> = emptyList(),
    val meetingExceptions: List<MeetingExceptionBackupDto> = emptyList(),
    val exceptionSections: List<ExceptionSectionBackupDto> = emptyList(),
    val scheduleProvenance: List<ScheduleProvenanceBackupDto> = emptyList(),
    val importNormalizationState: List<ImportNormalizationStateBackupDto> = emptyList(),
    val noticeAdjustmentEvents: List<NoticeAdjustmentEventBackupDto> = emptyList(),
    val sequences: List<SqliteSequenceBackupDto> = emptyList(),
    val displayGroups: List<CourseDisplayGroupBackupDto> = emptyList(),
) {
    fun totalRowCount(): Int =
        courses.size + semesters.size + periods.size + scheduledCourses.size +
            courseMeetings.size + meetingWeeks.size + meetingSections.size +
            meetingDates.size + meetingDeclaredWeeks.size + meetingExceptions.size +
            exceptionSections.size + scheduleProvenance.size +
            importNormalizationState.size + noticeAdjustmentEvents.size
}

@Serializable
data class TimetableBackupPayloadDto(
    val formatVersion: Int = TIMETABLE_BACKUP_FORMAT_VERSION,
    val roomSchemaVersion: Int = TIMETABLE_BACKUP_ROOM_SCHEMA_VERSION,
    val schemaIdentityHash: String = TIMETABLE_BACKUP_SCHEMA_IDENTITY_HASH,
    val sourceAppId: String,
    val sourceVersionCode: Long,
    val createdAt: Long,
    val activeProfileId: String,
    val profiles: List<ProfileBackupDto>,
    val contentDigest: String = "",
) {
    fun totalRowCount(): Int = profiles.sumOf { it.totalRowCount() }
}

/** Preview summary model returned by inspect before restoration. */
data class TimetableBackupPreview(
    val formatVersion: Int,
    val sourceAppId: String,
    val sourceVersionCode: Long,
    val createdAt: Long,
    val activeProfileId: String,
    val contentDigest: String,
    val profileSummaries: List<ProfileSummary>,
    val totalCourses: Int,
    val totalMeetings: Int,
    val totalExceptions: Int,
    val totalAdjustmentEvents: Int,
    val warnings: List<String> = emptyList(),
)

data class ProfileSummary(
    val profileId: String,
    val semesterCount: Int,
    val courseCount: Int,
    val meetingCount: Int,
    val exceptionCount: Int,
    val eventCount: Int,
    val displayGroupCount: Int,
)

/** Token binding the inspected digest and target state for two-phase confirmation. */
data class TimetableConfirmationToken(
    val contentDigest: String,
    val targetStateHash: String,
    val formatVersion: Int,
    val tokenCreatedAt: Long = System.currentTimeMillis(),
)
