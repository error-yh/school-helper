package com.lingion.sleepy.data.local

import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive only: all seven existing tables, rows, sequences and identity-bearing IDs survive. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE scheduled_courses ADD COLUMN sourceCourseCode TEXT")
        db.execSQL("ALTER TABLE scheduled_courses ADD COLUMN sourceClassCode TEXT")
        db.execSQL("ALTER TABLE course_meetings ADD COLUMN kind TEXT NOT NULL DEFAULT 'RECURRING'")
        db.execSQL("ALTER TABLE course_meetings ADD COLUMN experimentProject TEXT")
        db.execSQL("ALTER TABLE course_meetings ADD COLUMN `group` TEXT")
        db.execSQL("ALTER TABLE course_meetings ADD COLUMN isExperiment INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE TABLE meeting_dates (meetingId INTEGER NOT NULL, epochDay INTEGER NOT NULL, PRIMARY KEY(meetingId,epochDay), FOREIGN KEY(meetingId) REFERENCES course_meetings(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_meeting_dates_meetingId ON meeting_dates(meetingId)")
        db.execSQL("CREATE TABLE meeting_declared_weeks (meetingId INTEGER NOT NULL, week INTEGER NOT NULL, PRIMARY KEY(meetingId,week), FOREIGN KEY(meetingId) REFERENCES course_meetings(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_meeting_declared_weeks_meetingId ON meeting_declared_weeks(meetingId)")
        db.execSQL("CREATE TABLE meeting_exceptions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, meetingId INTEGER NOT NULL, kind TEXT NOT NULL, originalEpochDay INTEGER, targetEpochDay INTEGER, customStartMinute INTEGER, customEndMinute INTEGER, hasSectionsOverride INTEGER NOT NULL, teacher TEXT, room TEXT, experimentProject TEXT, `group` TEXT, FOREIGN KEY(meetingId) REFERENCES course_meetings(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_meeting_exceptions_meetingId ON meeting_exceptions(meetingId)")
        db.execSQL("CREATE TABLE exception_sections (exceptionId INTEGER NOT NULL, section INTEGER NOT NULL, PRIMARY KEY(exceptionId,section), FOREIGN KEY(exceptionId) REFERENCES meeting_exceptions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_exception_sections_exceptionId ON exception_sections(exceptionId)")
        db.execSQL("CREATE TABLE schedule_provenance (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, courseId INTEGER, meetingId INTEGER, exceptionId INTEGER, position INTEGER NOT NULL, sourceId TEXT, adapterId TEXT, page INTEGER, `left` REAL, `top` REAL, `right` REAL, bottom REAL, rawText TEXT NOT NULL, sourceLocator TEXT, FOREIGN KEY(courseId) REFERENCES scheduled_courses(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(meetingId) REFERENCES course_meetings(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(exceptionId) REFERENCES meeting_exceptions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        for (owner in listOf("courseId", "meetingId", "exceptionId")) db.execSQL("CREATE UNIQUE INDEX index_schedule_provenance_${owner}_position ON schedule_provenance($owner,position)")
        installCalendarConstraints(db)
    }
}

internal val CALENDAR_CALLBACK = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) { installCalendarConstraints(db) }
}

/** Identical checks on fresh and migrated databases; legacy inert rows are never rewritten. */
private fun installCalendarConstraints(db: SupportSQLiteDatabase) {
    val validDate = { column: String -> "(NEW.$column IS NULL OR NEW.$column BETWEEN -365243219162 AND 365241780471)" }
    val checks = mapOf(
        "course_meetings" to "NEW.kind IN ('RECURRING','DATE_SPECIFIC','UNSCHEDULED') AND NEW.day BETWEEN CASE WHEN NEW.kind='RECURRING' THEN 1 ELSE 0 END AND 7 AND NEW.isExperiment IN (0,1)",
        "meeting_dates" to "NEW.epochDay BETWEEN -365243219162 AND 365241780471 AND (SELECT kind FROM course_meetings WHERE id=NEW.meetingId)='DATE_SPECIFIC'",
        "meeting_declared_weeks" to "NEW.week BETWEEN 1 AND 1000",
        "meeting_exceptions" to """NEW.kind IN ('CANCEL','RESCHEDULE','EXTRA') AND NEW.hasSectionsOverride IN (0,1)
            AND (SELECT kind FROM course_meetings WHERE id=NEW.meetingId)!='UNSCHEDULED'
            AND ${validDate("originalEpochDay")} AND ${validDate("targetEpochDay")}
            AND ((NEW.customStartMinute IS NULL AND NEW.customEndMinute IS NULL) OR
                (NEW.customStartMinute BETWEEN 0 AND 1439 AND NEW.customEndMinute BETWEEN 0 AND 1439 AND NEW.customStartMinute<NEW.customEndMinute))
            AND ((NEW.kind='CANCEL' AND NEW.originalEpochDay IS NOT NULL AND NEW.targetEpochDay IS NULL AND NEW.customStartMinute IS NULL AND NEW.customEndMinute IS NULL AND NEW.hasSectionsOverride=0 AND NEW.teacher IS NULL AND NEW.room IS NULL AND NEW.experimentProject IS NULL AND NEW.`group` IS NULL)
              OR (NEW.kind='RESCHEDULE' AND NEW.originalEpochDay IS NOT NULL AND NEW.targetEpochDay IS NOT NULL)
              OR (NEW.kind='EXTRA' AND NEW.originalEpochDay IS NULL AND NEW.targetEpochDay IS NOT NULL))
            AND (NEW.originalEpochDay IS NULL OR NOT EXISTS(SELECT 1 FROM meeting_exceptions e WHERE e.meetingId=NEW.meetingId AND e.originalEpochDay=NEW.originalEpochDay AND e.id!=NEW.id))""",
        "exception_sections" to "NEW.section>0 AND (SELECT hasSectionsOverride FROM meeting_exceptions WHERE id=NEW.exceptionId)=1",
        "schedule_provenance" to """((NEW.courseId IS NOT NULL)+(NEW.meetingId IS NOT NULL)+(NEW.exceptionId IS NOT NULL))=1
            AND NEW.position>=0 AND (NEW.page IS NULL OR NEW.page BETWEEN 1 AND 100000)
            AND length(COALESCE(NEW.sourceId,''))<=2048 AND length(COALESCE(NEW.adapterId,''))<=256 AND length(NEW.rawText)<=500000 AND length(COALESCE(NEW.sourceLocator,''))<=4096
            AND ((NEW.`left` IS NULL AND NEW.`top` IS NULL AND NEW.`right` IS NULL AND NEW.bottom IS NULL) OR
              (NEW.`left` IS NOT NULL AND NEW.`top` IS NOT NULL AND NEW.`right` IS NOT NULL AND NEW.bottom IS NOT NULL
               AND NEW.`left` BETWEEN 0 AND 10000000 AND NEW.`top` BETWEEN 0 AND 10000000 AND NEW.`right` BETWEEN NEW.`left` AND 10000000 AND NEW.bottom BETWEEN NEW.`top` AND 10000000))""",
    )
    for ((table, condition) in checks) for (operation in listOf("INSERT", "UPDATE")) {
        // COALESCE prevents SQLite NULL truth values from bypassing a partial-time check.
        db.execSQL("CREATE TRIGGER IF NOT EXISTS calendar_${table}_${operation.lowercase()} BEFORE $operation ON `$table` WHEN NOT COALESCE(($condition),0) BEGIN SELECT RAISE(ABORT,'Invalid calendar data'); END")
    }
}
