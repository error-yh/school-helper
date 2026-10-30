package com.lingion.sleepy.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "meeting_dates", primaryKeys = ["meetingId", "epochDay"],
    foreignKeys = [ForeignKey(entity = CourseMeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["meetingId"])])
data class MeetingDateEntity(val meetingId: Long, val epochDay: Long)

@Entity(tableName = "meeting_declared_weeks", primaryKeys = ["meetingId", "week"],
    foreignKeys = [ForeignKey(entity = CourseMeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["meetingId"])])
data class MeetingDeclaredWeekEntity(val meetingId: Long, val week: Int)

@Entity(tableName = "meeting_exceptions",
    foreignKeys = [ForeignKey(entity = CourseMeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["meetingId"])])
data class MeetingExceptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val meetingId: Long, val kind: String, val originalEpochDay: Long?, val targetEpochDay: Long?,
    val customStartMinute: Int?, val customEndMinute: Int?, val hasSectionsOverride: Boolean,
    val teacher: String?, val room: String?, val experimentProject: String?, val group: String?,
)

@Entity(tableName = "exception_sections", primaryKeys = ["exceptionId", "section"],
    foreignKeys = [ForeignKey(entity = MeetingExceptionEntity::class, parentColumns = ["id"], childColumns = ["exceptionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["exceptionId"])])
data class ExceptionSectionEntity(val exceptionId: Long, val section: Int)

@Entity(tableName = "schedule_provenance", foreignKeys = [
    ForeignKey(entity = ScheduledCourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = CourseMeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = MeetingExceptionEntity::class, parentColumns = ["id"], childColumns = ["exceptionId"], onDelete = ForeignKey.CASCADE),
], indices = [Index(value = ["courseId", "position"], unique = true), Index(value = ["meetingId", "position"], unique = true), Index(value = ["exceptionId", "position"], unique = true)])
data class ScheduleProvenanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long? = null, val meetingId: Long? = null, val exceptionId: Long? = null,
    val position: Int, val sourceId: String?, val adapterId: String?, val page: Int?,
    val left: Float?, val top: Float?, val right: Float?, val bottom: Float?, val rawText: String,
    val sourceLocator: String? = null,
)
