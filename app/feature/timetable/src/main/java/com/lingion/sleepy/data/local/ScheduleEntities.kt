package com.lingion.sleepy.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "semesters")
data class SemesterEntity(
    @PrimaryKey val id: Long = 1,
    val name: String,
    val startEpochDay: Long?,
    val totalWeeks: Int,
    val revision: Long,
)

@Entity(
    tableName = "periods",
    primaryKeys = ["semesterId", "number"],
    foreignKeys = [ForeignKey(
        entity = SemesterEntity::class,
        parentColumns = ["id"], childColumns = ["semesterId"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["semesterId"])],
)
data class PeriodEntity(val semesterId: Long, val number: Int, val startMinute: Int, val endMinute: Int)

@Entity(
    tableName = "scheduled_courses",
    foreignKeys = [ForeignKey(
        entity = SemesterEntity::class,
        parentColumns = ["id"], childColumns = ["semesterId"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["semesterId"])],
)
data class ScheduledCourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val semesterId: Long,
    val courseName: String,
    val teacher: String,
    val room: String,
    val color: Long,
    val sourceCourseCode: String? = null,
    val sourceClassCode: String? = null,
)

@Entity(
    tableName = "course_meetings",
    foreignKeys = [ForeignKey(
        entity = ScheduledCourseEntity::class,
        parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["courseId"])],
)
data class CourseMeetingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseId: Long,
    val day: Int,
    val customStartMinute: Int?,
    val customEndMinute: Int?,
    val teacher: String?,
    val room: String?,
    @ColumnInfo(defaultValue = "'RECURRING'") val kind: String = "RECURRING",
    val experimentProject: String? = null,
    val group: String? = null,
    @ColumnInfo(defaultValue = "0") val isExperiment: Boolean = false,
)

@Entity(
    tableName = "meeting_weeks",
    primaryKeys = ["meetingId", "week"],
    foreignKeys = [ForeignKey(
        entity = CourseMeetingEntity::class,
        parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["meetingId"])],
)
data class MeetingWeekEntity(val meetingId: Long, val week: Int)

@Entity(
    tableName = "meeting_sections",
    primaryKeys = ["meetingId", "section"],
    foreignKeys = [ForeignKey(
        entity = CourseMeetingEntity::class,
        parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["meetingId"])],
)
data class MeetingSectionEntity(val meetingId: Long, val section: Int)
