package com.lingion.sleepy.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lingion.sleepy.data.local.MIGRATION_1_2
import com.lingion.sleepy.data.local.MIGRATION_2_3
import com.lingion.sleepy.data.local.MIGRATION_3_4
import com.lingion.sleepy.data.local.MIGRATION_4_5
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.DatedInterval
import com.lingion.sleepy.domain.imports.ImportCanonical
import com.lingion.sleepy.domain.imports.ImportNormalizationCodec
import com.lingion.sleepy.domain.imports.ImportOccurrencePlanner
import com.lingion.sleepy.domain.imports.LogicalOriginal
import com.lingion.sleepy.domain.imports.OccurrenceIdentity
import com.lingion.sleepy.domain.imports.OwnedFragment
import com.lingion.sleepy.domain.imports.PlannedNormalizationState
import com.lingion.sleepy.domain.imports.StoredNormalizationState
import com.lingion.sleepy.domain.imports.fList
import com.lingion.sleepy.domain.imports.fSet
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.model.defaultPeriods
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryResult
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetableMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ownedNames = mutableSetOf<String>()

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TimetableDatabase::class.java.canonicalName!!,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun cleanUpOnlySyntheticDatabases() {
        ownedNames.forEach(context::deleteDatabase)
    }

    @Test
    fun migrationPreservesEveryLegacyRowAndStableIdWithoutNameMerging() = runBlocking {
        val name = newName()
        val rows = listOf(
            LegacyRow(11, "Synthetic same name", 1, 1, 2, 1, 6, 0),
            LegacyRow(24, "Synthetic same name", 2, 3, 2, 1, 6, 1),
            LegacyRow(35, "Synthetic even", 3, 5, 1, 1, 6, 2),
            LegacyRow(46, "Synthetic type three", 4, 7, 1, 2, 4, 3),
            LegacyRow(57, "Synthetic unknown type", 5, 9, 1, 3, 5, 77),
            LegacyRow(68, "Synthetic inert odd", 6, 11, 1, 2, 2, 1),
            LegacyRow(79, "Synthetic missing period", 7, 13, 1, 21, 23, 0),
        )
        createV1(name, rows)

        helper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).use { migrated ->
            assertEquals(rows, readLegacyRows(migrated))
            assertEquals(rows.map { it.id }, longColumn(migrated, "SELECT id FROM scheduled_courses ORDER BY id"))
            assertEquals(rows.map { it.id }, longColumn(migrated, "SELECT id FROM course_meetings ORDER BY id"))
            migrated.query("SELECT name,startEpochDay,totalWeeks,revision FROM semesters WHERE id=1").use {
                assertTrue(it.moveToFirst())
                assertEquals("我的学期", it.getString(0))
                assertTrue(it.isNull(1))
                assertEquals(23, it.getInt(2))
                assertEquals(0L, it.getLong(3))
            }
            assertEquals(defaultPeriods().map { listOf(it.number, it.start.toSecondOfDay() / 60, it.end.toSecondOfDay() / 60) },
                intRows(migrated, "SELECT number,startMinute,endMinute FROM periods ORDER BY number"))
            assertEquals(listOf(1, 2, 3, 4, 5, 6), ints(migrated, "SELECT week FROM meeting_weeks WHERE meetingId=11 ORDER BY week"))
            assertEquals(listOf(1, 3, 5), ints(migrated, "SELECT week FROM meeting_weeks WHERE meetingId=24 ORDER BY week"))
            assertEquals(listOf(2, 4, 6), ints(migrated, "SELECT week FROM meeting_weeks WHERE meetingId=35 ORDER BY week"))
            assertEquals(listOf(2, 3, 4), ints(migrated, "SELECT week FROM meeting_weeks WHERE meetingId=46 ORDER BY week"))
            assertEquals(listOf(3, 4, 5), ints(migrated, "SELECT week FROM meeting_weeks WHERE meetingId=57 ORDER BY week"))
            assertTrue(ints(migrated, "SELECT week FROM meeting_weeks WHERE meetingId=68").isEmpty())
            assertEquals(listOf(13), ints(migrated, "SELECT section FROM meeting_sections WHERE meetingId=79"))
        }
        Room.databaseBuilder(context, TimetableDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().use { db ->
            assertEquals(5, db.openHelper.readableDatabase.version)
            assertEquals(0L, longColumn(db.openHelper.readableDatabase, "SELECT COUNT(*) FROM notice_adjustment_events").single())
            val repository = RoomScheduleRepository(db)
            val snapshot = withTimeout(5_000) { repository.observeTimetable().first() }
            assertEquals(rows.map { it.id }, snapshot.courses.map { it.id })
            assertTrue(snapshot.courses.single { it.id == 68L }.meetings.single().weeks.isEmpty())
            assertEquals(setOf(13), snapshot.courses.single { it.id == 79L }.meetings.single().sections)
            assertNull(snapshot.settings.startDate)
            repository.updateSettings(snapshot.settings.copy(name = "Synthetic configured migrated term"), snapshot.periods)
            repository.saveCourse(snapshot.courses.first().asDraft().copy(courseName = "Synthetic edited normalized row"), 11)
            repository.deleteCourse(24)
            assertEquals(rows, readLegacyRows(db.openHelper.readableDatabase))
        }
    }

    @Test
    fun emptyV1AndNewDatabaseHaveTheSameDefaults() = runBlocking {
        val migratedName = newName()
        createV1(migratedName, emptyList())
        helper.runMigrationsAndValidate(migratedName, 2, true, MIGRATION_1_2).close()
        val freshName = newName()
        Room.databaseBuilder(context, TimetableDatabase::class.java, migratedName).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().use { migrated ->
            Room.databaseBuilder(context, TimetableDatabase::class.java, freshName).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().use { fresh ->
                val oldSnapshot = withTimeout(5_000) { RoomScheduleRepository(migrated).observeTimetable().first() }
                val newSnapshot = withTimeout(5_000) { RoomScheduleRepository(fresh).observeTimetable().first() }
                assertEquals(oldSnapshot, newSnapshot)
                assertEquals(20, newSnapshot.settings.totalWeeks)
                assertEquals(defaultPeriods(), newSnapshot.periods)
                assertTrue(newSnapshot.courses.isEmpty())
            }
        }
    }

    @Test
    fun failureAfterActualMigrationRollsBackUserVersionArchiveAndAllNewTables() {
        val name = newName()
        val rows = listOf(LegacyRow(901, "Synthetic rollback", 7, 13, 1, 2, 6, 2))
        createV1(name, rows)
        var actualMigrationCompleted = false
        val failingMigration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_1_2.migrate(db)
                actualMigrationCompleted = true
                db.execSQL("UPDATE scheduled_courses SET courseName='must roll back'")
                throw IllegalStateException("Synthetic failure after actual v1 to v2 migration")
            }
        }
        val database = Room.databaseBuilder(context, TimetableDatabase::class.java, name)
            .addMigrations(failingMigration, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
        try {
            assertThrows(RuntimeException::class.java) { database.openHelper.writableDatabase }
            assertTrue(actualMigrationCompleted)
        } finally {
            database.close()
        }
        assertOriginalV1Survives(name, rows)
    }

    @Test
    fun malformedLegacyRowsFailClosedWithoutDestructiveFallback() {
        val invalidRows = listOf(
            LegacyRow(111, "Synthetic invalid day", 8, 1, 1, 1, 2, 0),
            LegacyRow(112, "Synthetic invalid section", 1, 0, 1, 1, 2, 0),
            LegacyRow(113, "Synthetic invalid length", 1, 1, 0, 1, 2, 0),
            LegacyRow(114, "Synthetic reversed weeks", 1, 1, 1, 5, 2, 0),
            LegacyRow(115, "Synthetic unsafe term", 1, 1, 1, 1, 1001, 0),
            LegacyRow(116, "Synthetic section overflow", 1, Int.MAX_VALUE, 2, 1, 2, 0),
            LegacyRow(117, "Synthetic unsafe section expansion", 1, 1, 10_001, 1, 2, 0),
        )
        invalidRows.forEach { invalid ->
            val name = newName()
            val rows = listOf(LegacyRow(1, "Synthetic valid before failure", 1, 1, 1, 1, 2, 0), invalid)
            createV1(name, rows)
            val database = Room.databaseBuilder(context, TimetableDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
            try {
                assertThrows(RuntimeException::class.java) { database.openHelper.writableDatabase }
            } finally {
                database.close()
            }
            assertOriginalV1Survives(name, rows)
        }
    }

    @Test
    fun maximumLegacyIdFailsClosedRatherThanExhaustingGeneratedIds() {
        val name = newName()
        val rows = listOf(
            LegacyRow(1, "Synthetic valid stable ID", 1, 1, 1, 1, 2, 0),
            LegacyRow(Long.MAX_VALUE, "Synthetic exhausted generated ID", 2, 3, 1, 1, 2, 0),
        )
        createV1(name, rows)
        val database = Room.databaseBuilder(context, TimetableDatabase::class.java, name)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
        try {
            assertThrows(RuntimeException::class.java) { database.openHelper.writableDatabase }
        } finally {
            database.close()
        }
        assertOriginalV1Survives(name, rows)
    }

    @Test
    fun openingAnUnsupportedDatabaseVersionDoesNotEraseItsRows() {
        val name = newName()
        val rows = listOf(LegacyRow(301, "Synthetic future version", 1, 1, 1, 1, 2, 0))
        createV1(name, rows)
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 6 }
        val database = Room.databaseBuilder(context, TimetableDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build()
        try {
            assertThrows(RuntimeException::class.java) { database.openHelper.writableDatabase }
        } finally {
            database.close()
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use {
            assertEquals(6, it.version)
            it.rawQuery("SELECT courseName FROM courses WHERE id=301", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Synthetic future version", cursor.getString(0))
            }
        }
    }

    @Test
    fun v4ToV5KeepsEveryRowProfileAndAValidNormalizationState() = runBlocking {
        val name = newName()
        val start = LocalDate.of(2026, 9, 7)
        val payload = syntheticImportedState(start)
        helper.createDatabase(name, 4).use { db ->
            db.execSQL("INSERT INTO semesters(id,name,startEpochDay,totalWeeks,revision) VALUES(1,'Synthetic v4',?,16,7)",
                arrayOf(start.toEpochDay()))
            defaultPeriods().forEach { period ->
                db.execSQL("INSERT INTO periods(semesterId,number,startMinute,endMinute) VALUES(1,?,?,?)",
                    arrayOf(period.number, period.start.toSecondOfDay() / 60, period.end.toSecondOfDay() / 60))
            }
            // Manual recurring course with a reschedule exception and full provenance.
            db.execSQL("INSERT INTO scheduled_courses(id,semesterId,courseName,teacher,room,color,sourceCourseCode,sourceClassCode) VALUES(10,1,'Synthetic manual','T','R',?,NULL,NULL)", arrayOf(0xFF6750A4L))
            db.execSQL("INSERT INTO course_meetings(id,courseId,day,customStartMinute,customEndMinute,teacher,room,kind,experimentProject,`group`,isExperiment) VALUES(20,10,5,NULL,NULL,NULL,NULL,'RECURRING',NULL,NULL,0)")
            for (week in 1..16) db.execSQL("INSERT INTO meeting_weeks(meetingId,week) VALUES(20,?)", arrayOf(week))
            db.execSQL("INSERT INTO meeting_sections(meetingId,section) VALUES(20,3)")
            db.execSQL("INSERT INTO meeting_sections(meetingId,section) VALUES(20,4)")
            db.execSQL("INSERT INTO meeting_exceptions(id,meetingId,kind,originalEpochDay,targetEpochDay,customStartMinute,customEndMinute,hasSectionsOverride,teacher,room,experimentProject,`group`) VALUES(100,20,'RESCHEDULE',?,?,NULL,NULL,1,NULL,NULL,NULL,NULL)",
                arrayOf(LocalDate.of(2026, 11, 13).toEpochDay(), LocalDate.of(2026, 11, 14).toEpochDay()))
            db.execSQL("INSERT INTO exception_sections(exceptionId,section) VALUES(100,5)")
            db.execSQL("INSERT INTO exception_sections(exceptionId,section) VALUES(100,6)")
            db.execSQL("INSERT INTO schedule_provenance(id,courseId,meetingId,exceptionId,position,sourceId,adapterId,page,left,top,right,bottom,rawText,sourceLocator) VALUES(1,10,NULL,NULL,0,'synthetic','csv',NULL,NULL,NULL,NULL,NULL,'synthetic course source','synthetic:course:1')")
            db.execSQL("INSERT INTO schedule_provenance(id,courseId,meetingId,exceptionId,position,sourceId,adapterId,page,left,top,right,bottom,rawText,sourceLocator) VALUES(2,NULL,20,NULL,0,'synthetic','csv',NULL,NULL,NULL,NULL,NULL,'synthetic meeting source','synthetic:meeting:20')")
            db.execSQL("INSERT INTO schedule_provenance(id,courseId,meetingId,exceptionId,position,sourceId,adapterId,page,left,top,right,bottom,rawText,sourceLocator) VALUES(3,NULL,NULL,100,0,'synthetic','csv',NULL,NULL,NULL,NULL,NULL,'synthetic exception source','synthetic:exception:100')")
            // Date-specific course with declared weeks and an inert archive.
            db.execSQL("INSERT INTO scheduled_courses(id,semesterId,courseName,teacher,room,color,sourceCourseCode,sourceClassCode) VALUES(11,1,'Synthetic dated','T2','R2',?,NULL,NULL)", arrayOf(0xFF112233L))
            db.execSQL("INSERT INTO course_meetings(id,courseId,day,customStartMinute,customEndMinute,teacher,room,kind,experimentProject,`group`,isExperiment) VALUES(21,11,0,NULL,NULL,NULL,NULL,'DATE_SPECIFIC',NULL,NULL,0)")
            db.execSQL("INSERT INTO meeting_dates(meetingId,epochDay) VALUES(21,?)", arrayOf(LocalDate.of(2026, 11, 13).toEpochDay()))
            db.execSQL("INSERT INTO meeting_declared_weeks(meetingId,week) VALUES(21,42)")
            db.execSQL("INSERT INTO meeting_sections(meetingId,section) VALUES(21,7)")
            // Unscheduled course.
            db.execSQL("INSERT INTO scheduled_courses(id,semesterId,courseName,teacher,room,color,sourceCourseCode,sourceClassCode) VALUES(12,1,'Synthetic inert','','',?,NULL,NULL)", arrayOf(0xFF445566L))
            db.execSQL("INSERT INTO course_meetings(id,courseId,day,customStartMinute,customEndMinute,teacher,room,kind,experimentProject,`group`,isExperiment) VALUES(22,12,0,NULL,NULL,NULL,NULL,'UNSCHEDULED',NULL,NULL,0)")
            // Imported course with a valid v4 normalization state.
            db.execSQL("INSERT INTO scheduled_courses(id,semesterId,courseName,teacher,room,color,sourceCourseCode,sourceClassCode) VALUES(13,1,'Synthetic imported','T','R',?,NULL,NULL)", arrayOf(0xFF6750A4L))
            db.execSQL("INSERT INTO course_meetings(id,courseId,day,customStartMinute,customEndMinute,teacher,room,kind,experimentProject,`group`,isExperiment) VALUES(23,13,5,NULL,NULL,NULL,NULL,'RECURRING',NULL,NULL,0)")
            for (week in 1..16) db.execSQL("INSERT INTO meeting_weeks(meetingId,week) VALUES(23,?)", arrayOf(week))
            db.execSQL("INSERT INTO meeting_sections(meetingId,section) VALUES(23,3)")
            db.execSQL("INSERT INTO meeting_sections(meetingId,section) VALUES(23,4)")
            db.execSQL("INSERT INTO import_normalization_state(courseId,version,payload) VALUES(13,?,?)",
                arrayOf(ImportNormalizationCodec.VERSION, ImportNormalizationCodec.encode(payload)))
        }

        helper.runMigrationsAndValidate(name, 5, true, MIGRATION_4_5).use { db ->
            assertEquals(5, db.version)
            assertEquals(listOf(10L, 11L, 12L, 13L), longColumn(db, "SELECT id FROM scheduled_courses ORDER BY id"))
            assertEquals(listOf(20L, 21L, 22L, 23L), longColumn(db, "SELECT id FROM course_meetings ORDER BY id"))
            assertEquals(16, ints(db, "SELECT week FROM meeting_weeks WHERE meetingId=20").size)
            assertEquals(listOf(7), ints(db, "SELECT section FROM meeting_sections WHERE meetingId=21"))
            db.query("SELECT kind,originalEpochDay,targetEpochDay,hasSectionsOverride FROM meeting_exceptions WHERE id=100").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("RESCHEDULE", cursor.getString(0))
                assertEquals(LocalDate.of(2026, 11, 13).toEpochDay(), cursor.getLong(1))
                assertEquals(LocalDate.of(2026, 11, 14).toEpochDay(), cursor.getLong(2))
                assertEquals(1, cursor.getInt(3))
            }
            assertEquals(listOf(5, 6), ints(db, "SELECT section FROM exception_sections WHERE exceptionId=100 ORDER BY section"))
            assertEquals(listOf(1, 2, 3), ints(db, "SELECT id FROM schedule_provenance ORDER BY id"))
            db.query("SELECT revision,startEpochDay,totalWeeks FROM semesters WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
                assertEquals(start.toEpochDay(), cursor.getLong(1))
                assertEquals(16, cursor.getInt(2))
            }
            db.query("SELECT payload FROM import_normalization_state WHERE courseId=13").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(ImportNormalizationCodec.encode(payload), cursor.getString(0))
            }
            assertEquals(0L, longColumn(db, "SELECT COUNT(*) FROM notice_adjustment_events").single())
            assertEquals(4L, longColumn(db, "SELECT COUNT(*) FROM scheduled_courses").single())
            assertEquals(0L, longColumn(db, "SELECT COUNT(*) FROM courses").single())
        }

        Room.databaseBuilder(context, TimetableDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().use { database ->
            val repository = RoomScheduleRepository(database)
            val snapshot = withTimeout(5_000) { repository.observeTimetable().first() }
            assertEquals(7L, snapshot.revision)
            assertEquals(listOf(10L, 11L, 12L, 13L), snapshot.courses.map { it.id })
            assertEquals(setOf(3, 4), snapshot.courses.single { it.id == 10L }.meetings.single().sections)
            assertEquals(1, snapshot.courses.single { it.id == 10L }.meetings.single().exceptions.size)
            val target = repository.readImportTarget(1)
            assertEquals(1, target.logicalBaseDetails.count { it.reference.courseId == 13L })
            assertEquals(5, target.target.databaseSchemaVersion)
        }
    }

    @Test
    fun freshV5DatabaseExposesAnEmptyBoundedJournal() = runBlocking {
        val name = newName()
        Room.databaseBuilder(context, TimetableDatabase::class.java, name).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().use { database ->
            assertEquals(5, database.openHelper.readableDatabase.version)
            assertEquals(0L, longColumn(database.openHelper.readableDatabase, "SELECT COUNT(*) FROM notice_adjustment_events").single())
            val history = RoomNoticeAdjustmentRepository(database).history()
            assertTrue(history is NoticeAdjustmentHistoryResult.Loaded)
            assertTrue((history as NoticeAdjustmentHistoryResult.Loaded).history.entries.isEmpty())
        }
    }

    /** The same legal state a single-original import would have hydrated, without touching production. */
    private fun syntheticImportedState(start: LocalDate): StoredNormalizationState {
        val meeting = CourseMeeting(id = 23, day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4), kind = MeetingKind.RECURRING)
        val course = ScheduledCourse(13, "Synthetic imported", "T", "R", 0xFF6750A4L, listOf(meeting))
        val snapshot = TimetableSnapshot(SemesterSettings(1, "Synthetic v4", start, 16), defaultPeriods(), listOf(course), 7, "local")
        val original = meeting.copy(id = 0, exceptions = emptyList())
        val identity = OccurrenceIdentity(course.courseName.trim(), course.sourceCourseCode, course.sourceClassCode,
            course.teacher.trim(), course.room.trim(), meeting.experimentProject, meeting.group, meeting.isExperiment)
        val intervals = TimetableRules.periodIntervals(meeting, snapshot.periods)
        val descriptor = ImportOccurrencePlanner.descriptorFingerprint(original, identity, intervals, true)
        val family = ImportCanonical.digest("synthetic-family")
        val allocated = ImportOccurrencePlanner.originalDates(original,
            snapshot.settings.startDate?.let(TimetableRules::mondayOf), snapshot.settings.totalWeeks)
            .flatMap { date -> intervals.map { TimeRange(it.start, it.end).let { range -> DatedInterval(date, range) } } }
        val graph = PlannedNormalizationState(
            fList(listOf(LogicalOriginal(family, descriptor, original, identity, fSet(setOf(meeting.id)), fList(allocated), fList(intervals), true))),
            fList(emptyList()), fList(emptyList()), fList(emptyList()), fList(emptyList()),
        )
        return StoredNormalizationState(ImportNormalizationCodec.VERSION, "local", 1, 13, graph,
            fList(listOf(OwnedFragment(ImportCanonical.digest("fragment"), family, meeting.id,
                ImportNormalizationCodec.meetingFingerprint(meeting)))),
            fList(emptyList()), fList(emptyList()))
    }

    private fun assertOriginalV1Survives(name: String, rows: List<LegacyRow>) {
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(1, raw.version)
            raw.rawQuery("SELECT * FROM courses ORDER BY id", null).use { cursor ->
                val result = buildList {
                    while (cursor.moveToNext()) add(LegacyRow(cursor.getLong(0), cursor.getString(1), cursor.getInt(2),
                        cursor.getInt(3), cursor.getInt(4), cursor.getInt(5), cursor.getInt(6), cursor.getInt(7)))
                }
                assertEquals(rows, result)
            }
            raw.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('semesters','periods','scheduled_courses','course_meetings','meeting_weeks','meeting_sections')", null).use {
                assertFalse(it.moveToFirst())
            }
        }
    }

    private fun createV1(name: String, rows: List<LegacyRow>) {
        helper.createDatabase(name, 1).use { db ->
            rows.forEach { row ->
                db.execSQL("INSERT INTO courses(id,courseName,day,startNode,step,startWeek,endWeek,type) VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf(row.id, row.name, row.day, row.startNode, row.step, row.startWeek, row.endWeek, row.type))
            }
        }
    }

    private fun newName() = "synthetic_migration_${UUID.randomUUID()}.db".also(ownedNames::add)

    private fun readLegacyRows(db: SupportSQLiteDatabase): List<LegacyRow> = db.query("SELECT * FROM courses ORDER BY id").use { cursor ->
        buildList { while (cursor.moveToNext()) add(LegacyRow(cursor.getLong(0), cursor.getString(1), cursor.getInt(2),
            cursor.getInt(3), cursor.getInt(4), cursor.getInt(5), cursor.getInt(6), cursor.getInt(7))) }
    }

    private fun ints(db: SupportSQLiteDatabase, sql: String): List<Int> = db.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
    }

    private fun longColumn(db: SupportSQLiteDatabase, sql: String): List<Long> = db.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
    }

    private fun intRows(db: SupportSQLiteDatabase, sql: String): List<List<Int>> = db.query(sql).use { cursor ->
        buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map(cursor::getInt)) }
    }

    private inline fun <T> TimetableDatabase.use(block: (TimetableDatabase) -> T): T =
        try { block(this) } finally { close() }

    private data class LegacyRow(val id: Long, val name: String, val day: Int, val startNode: Int, val step: Int,
        val startWeek: Int, val endWeek: Int, val type: Int)
}

