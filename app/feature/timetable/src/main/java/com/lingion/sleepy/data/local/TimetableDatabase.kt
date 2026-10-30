package com.lingion.sleepy.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * An independent database for each host-selected profile, with lossless v1 to v2 to v3 migrations.
 * It does not open, migrate, or claim compatibility with Sleepy's version 12 database.
 * The host owns each returned instance and its lifetime.
 */
@Database(
    entities = [CourseEntity::class, SemesterEntity::class, PeriodEntity::class,
        ScheduledCourseEntity::class, CourseMeetingEntity::class, MeetingWeekEntity::class, MeetingSectionEntity::class,
        MeetingDateEntity::class, MeetingDeclaredWeekEntity::class, MeetingExceptionEntity::class,
        ExceptionSectionEntity::class, ScheduleProvenanceEntity::class, ImportNormalizationStateEntity::class],
    version = 4,
    exportSchema = true,
)
abstract class TimetableDatabase : RoomDatabase() {
    /** Fixed for the instance lifetime; direct builders use the local test/default scope. */
    var scopeToken: String = "local"
        private set

    abstract fun scheduleDao(): ScheduleDao

    companion object {
        /** Profile IDs are 1 to 64 ASCII letters, digits, underscores, or hyphens. */
        fun open(context: Context, profileId: String): TimetableDatabase {
            require(profileId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                "profileId must contain 1 to 64 ASCII letters, digits, underscores, or hyphens"
            }
            return Room.databaseBuilder(
                context.applicationContext,
                TimetableDatabase::class.java,
                "timetable_$profileId.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).addCallback(CALENDAR_CALLBACK).build().also { it.scopeToken = profileId }
        }
    }
}
