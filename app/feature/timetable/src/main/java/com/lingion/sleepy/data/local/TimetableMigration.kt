package com.lingion.sleepy.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lingion.sleepy.domain.model.defaultPeriods

/**
 * The old table is an immutable migration archive. New application writes use the normalized tables.
 * Room invokes this inside its upgrade transaction; every failure preserves the v1 logical data/version.
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val legacyRows = db.query("SELECT * FROM courses ORDER BY id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    fun legacyInt(column: String): Int {
                        val value = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                            "Legacy $column is outside the supported integer range; original data retained"
                        }
                        return value.toInt()
                    }
                    add(LegacyCourse(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("courseName")),
                        day = legacyInt("day"),
                        startNode = legacyInt("startNode"),
                        step = legacyInt("step"),
                        startWeek = legacyInt("startWeek"),
                        endWeek = legacyInt("endWeek"),
                        type = legacyInt("type"),
                    ))
                }
            }
        }
        legacyRows.forEach(LegacyCourse::validate)
        createScheduleTables(db)
        val totalWeeks = maxOf(20, legacyRows.maxOfOrNull { it.endWeek } ?: 20)
        db.execSQL("INSERT INTO semesters(id,name,startEpochDay,totalWeeks,revision) VALUES(1,?,NULL,?,0)",
            arrayOf("我的学期", totalWeeks))
        defaultPeriods().forEach { period ->
            db.execSQL("INSERT INTO periods(semesterId,number,startMinute,endMinute) VALUES(1,?,?,?)",
                arrayOf(period.number, period.start.toSecondOfDay() / 60, period.end.toSecondOfDay() / 60))
        }
        legacyRows.forEach { row ->
            db.execSQL("INSERT INTO scheduled_courses(id,semesterId,courseName,teacher,room,color) VALUES(?,1,?,'','',?)",
                arrayOf(row.id, row.name, 0xFF6750A4L))
            db.execSQL("INSERT INTO course_meetings(id,courseId,day,customStartMinute,customEndMinute,teacher,room) VALUES(?,?,?,NULL,NULL,NULL,NULL)",
                arrayOf(row.id, row.id, row.day))
            for (week in row.startWeek..row.endWeek) {
                val active = when (row.type) {
                    1 -> week % 2 == 1
                    2 -> week % 2 == 0
                    else -> true
                }
                if (active) db.execSQL("INSERT INTO meeting_weeks(meetingId,week) VALUES(?,?)", arrayOf(row.id, week))
            }
            val endSection = row.startNode.toLong() + row.step.toLong() - 1
            for (section in row.startNode.toLong()..endSection) {
                db.execSQL("INSERT INTO meeting_sections(meetingId,section) VALUES(?,?)", arrayOf(row.id, section))
            }
        }
    }
}

private data class LegacyCourse(val id: Long, val name: String, val day: Int, val startNode: Int,
    val step: Int, val startWeek: Int, val endWeek: Int, val type: Int) {
    fun validate() {
        // Inserting Long.MAX_VALUE would permanently exhaust both AUTOINCREMENT sequences.
        require(id > 0 && id < Long.MAX_VALUE) {
            "Legacy course $id has an invalid or exhausted stable ID; original data retained"
        }
        require(day in 1..7) { "Legacy course $id has an invalid day; original data retained" }
        require(startWeek in 1..1000 && endWeek in startWeek..1000) {
            "Legacy course $id has an unsafe week range; original data retained"
        }
        require(startNode > 0 && step in 1..10_000 && startNode.toLong() + step.toLong() - 1 <= Int.MAX_VALUE) {
            "Legacy course $id has an invalid section range or exceeds the safe 10000-section expansion limit; original data retained"
        }
    }
}

private fun createScheduleTables(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS `semesters` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `startEpochDay` INTEGER, `totalWeeks` INTEGER NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    db.execSQL("CREATE TABLE IF NOT EXISTS `periods` (`semesterId` INTEGER NOT NULL, `number` INTEGER NOT NULL, `startMinute` INTEGER NOT NULL, `endMinute` INTEGER NOT NULL, PRIMARY KEY(`semesterId`, `number`), FOREIGN KEY(`semesterId`) REFERENCES `semesters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_periods_semesterId` ON `periods` (`semesterId`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `scheduled_courses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `semesterId` INTEGER NOT NULL, `courseName` TEXT NOT NULL, `teacher` TEXT NOT NULL, `room` TEXT NOT NULL, `color` INTEGER NOT NULL, FOREIGN KEY(`semesterId`) REFERENCES `semesters`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_courses_semesterId` ON `scheduled_courses` (`semesterId`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `course_meetings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `courseId` INTEGER NOT NULL, `day` INTEGER NOT NULL, `customStartMinute` INTEGER, `customEndMinute` INTEGER, `teacher` TEXT, `room` TEXT, FOREIGN KEY(`courseId`) REFERENCES `scheduled_courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_course_meetings_courseId` ON `course_meetings` (`courseId`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `meeting_weeks` (`meetingId` INTEGER NOT NULL, `week` INTEGER NOT NULL, PRIMARY KEY(`meetingId`, `week`), FOREIGN KEY(`meetingId`) REFERENCES `course_meetings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_meeting_weeks_meetingId` ON `meeting_weeks` (`meetingId`)")
    db.execSQL("CREATE TABLE IF NOT EXISTS `meeting_sections` (`meetingId` INTEGER NOT NULL, `section` INTEGER NOT NULL, PRIMARY KEY(`meetingId`, `section`), FOREIGN KEY(`meetingId`) REFERENCES `course_meetings`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_meeting_sections_meetingId` ON `meeting_sections` (`meetingId`)")
}
