package com.lingion.sleepy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.model.defaultPeriods
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomScheduleRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val openDatabases = mutableListOf<TimetableDatabase>()
    private val ownedNames = mutableSetOf<String>()

    @After
    fun cleanUpOnlyOwnedSyntheticDatabases() {
        openDatabases.forEach(TimetableDatabase::close)
        ownedNames.forEach(context::deleteDatabase)
    }

    @Test
    fun freshDatabaseInitializesDefaultsAndStableProfileScopeOnce() = runBlocking {
        val profile = newProfile("defaults")
        val repository = RoomScheduleRepository(open(profile))
        val first = snapshot(repository)
        assertEquals(SemesterSettings(), first.settings)
        assertEquals(defaultPeriods(), first.periods)
        assertEquals(0L, first.revision)
        assertEquals(profile, first.scopeToken)
        assertTrue(first.courses.isEmpty())
        assertEquals(first, snapshot(repository))
    }

    @Test
    fun exactSparseMeetingsCustomMinutesOverridesColorAndSettingsSurviveReopen() = runBlocking {
        val profile = newProfile("reopen")
        val db = open(profile)
        val repository = RoomScheduleRepository(db)
        val settings = SemesterSettings(name = " Synthetic term ", startDate = LocalDate.parse("2026-08-31"), totalWeeks = 24)
        val periods = defaultPeriods() + Period(13, time("22:40"), time("23:25"))
        repository.updateSettings(settings, periods)
        val draft = complexDraft()
        val id = repository.saveCourse(draft)
        val saved = snapshot(repository)
        val savedCourse = saved.courses.single()
        assertEquals("Synthetic term", saved.settings.name)
        assertEquals(id, savedCourse.id)
        assertEquals("Synthetic exact course", savedCourse.courseName)
        assertEquals("Teacher", savedCourse.teacher)
        assertEquals("Room", savedCourse.room)
        assertEquals(0xF0123456L, savedCourse.color)
        assertEquals(setOf(1, 3, 8, 20), savedCourse.meetings[0].weeks)
        assertEquals(setOf(1, 3, 13), savedCourse.meetings[0].sections)
        assertEquals("Override teacher", savedCourse.meetings[0].teacher)
        assertEquals("Override room", savedCourse.meetings[0].room)
        assertEquals(TimeRange(time("12:07"), time("12:49")), savedCourse.meetings[1].customTime)
        assertEquals(setOf(99), savedCourse.meetings[1].sections)
        assertEquals("", savedCourse.meetings[1].teacher)
        assertEquals(null, savedCourse.meetings[1].room)
        assertEquals(2, savedCourse.meetings.map { it.id }.distinct().size)
        assertTrue(savedCourse.meetings.all { it.id > 0 })
        assertEquals(2L, saved.revision)
        db.close()
        openDatabases.remove(db)

        val reopened = open(profile)
        assertEquals(saved, snapshot(RoomScheduleRepository(reopened)))
        assertEquals(4, reopened.openHelper.readableDatabase.version)
        assertEquals(0, count(reopened, "courses"))
    }

    @Test
    fun sameNameRowsStayIndependentAndEditDeleteUseOnlyTheExactId() = runBlocking {
        val db = open(newProfile("crud"))
        val repository = RoomScheduleRepository(db)
        val draft = simpleDraft()
        val firstId = repository.saveCourse(draft)
        val secondId = repository.saveCourse(draft)
        assertNotEquals(firstId, secondId)
        assertEquals(2, snapshot(repository).courses.size)
        val replacement = draft.copy(courseName = "Synthetic edited", meetings = listOf(
            CourseMeeting(day = 7, weeks = setOf(2, 4), sections = setOf(5, 7)),
        ))
        assertEquals(firstId, repository.saveCourse(replacement, firstId))
        val afterEdit = snapshot(repository)
        assertEquals(setOf(firstId, secondId), afterEdit.courses.map { it.id }.toSet())
        assertEquals("Synthetic edited", afterEdit.courses.single { it.id == firstId }.courseName)
        assertEquals(draft.courseName, afterEdit.courses.single { it.id == secondId }.courseName)
        assertEquals(setOf(2, 4), afterEdit.courses.single { it.id == firstId }.meetings.single().weeks)
        assertEquals(2, count(db, "course_meetings"))
        repository.deleteCourse(firstId)
        val afterDelete = snapshot(repository)
        assertEquals(listOf(secondId), afterDelete.courses.map { it.id })
        assertEquals(4L, afterDelete.revision)
        assertEquals(1, count(db, "course_meetings"))
        assertEquals(3, count(db, "meeting_weeks"))
        assertEquals(2, count(db, "meeting_sections"))
    }

    @Test
    fun flowEmitsOnlyCompleteSnapshotsAfterCourseSettingsEditAndDeleteTransactions() = runBlocking {
        val repository = RoomScheduleRepository(open(newProfile("flow")))
        val emissions = Channel<TimetableSnapshot>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.observeTimetable().collect(emissions::send)
        }
        suspend fun next() = withTimeout(5_000) { emissions.receive() }
        try {
            assertEquals(0L, next().revision)
            val id = repository.saveCourse(simpleDraft())
            val afterInsert = next()
            assertEquals(1L, afterInsert.revision)
            assertEquals(setOf(1, 3, 8), afterInsert.courses.single().meetings.single().weeks)
            assertEquals(setOf(1, 3), afterInsert.courses.single().meetings.single().sections)
            repository.updateSettings(SemesterSettings(name = "Synthetic changed", totalWeeks = 30), defaultPeriods())
            val afterSettings = next()
            assertEquals(2L, afterSettings.revision)
            assertEquals(30, afterSettings.settings.totalWeeks)
            assertEquals(afterInsert.courses, afterSettings.courses)
            repository.saveCourse(simpleDraft().copy(meetings = listOf(
                CourseMeeting(day = 2, weeks = setOf(2, 9), sections = setOf(2, 4)),
                CourseMeeting(day = 4, weeks = setOf(4), sections = emptySet(), customTime = TimeRange(time("12:01"), time("12:31"))),
            )), id)
            val afterEdit = next()
            assertEquals(3L, afterEdit.revision)
            assertEquals(2, afterEdit.courses.single().meetings.size)
            assertEquals(setOf(2, 9), afterEdit.courses.single().meetings.first().weeks)
            repository.deleteCourse(id)
            val afterDelete = next()
            assertEquals(4L, afterDelete.revision)
            assertTrue(afterDelete.courses.isEmpty())
            assertTrue(emissions.tryReceive().isFailure)
        } finally {
            collector.cancelAndJoin()
            emissions.close()
        }
    }

    @Test
    fun invalidSettingsCannotShrinkAwayWeeksOrRemoveUsedOrdinarySections() = runBlocking {
        val repository = RoomScheduleRepository(open(newProfile("settings")))
        repository.saveCourse(simpleDraft())
        val before = snapshot(repository)
        expectInvalid { repository.updateSettings(before.settings.copy(totalWeeks = 7), before.periods) }
        assertEquals(before, snapshot(repository))
        expectInvalid { repository.updateSettings(before.settings, before.periods.filter { it.number != 3 }) }
        assertEquals(before, snapshot(repository))
        expectInvalid { repository.updateSettings(before.settings.copy(id = 2), before.periods) }
        assertEquals(before, snapshot(repository))
        expectInvalid { repository.updateSettings(before.settings.copy(name = " "), before.periods) }
        assertEquals(before, snapshot(repository))
        expectInvalid { repository.updateSettings(before.settings, before.periods + before.periods.first()) }
        assertEquals(before, snapshot(repository))
        val overlap = before.periods.toMutableList().apply { this[1] = Period(2, time("08:30"), time("09:30")) }
        expectInvalid { repository.updateSettings(before.settings, overlap) }
        assertEquals(before, snapshot(repository))
        repository.updateSettings(before.settings.copy(totalWeeks = 24), before.periods.map {
            if (it.number == 1) Period(1, time("07:50"), time("08:35")) else it
        })
        val after = snapshot(repository)
        assertEquals(before.courses, after.courses)
        assertEquals(before.revision + 1, after.revision)
    }

    @Test
    fun invalidSemesterBoundaryDateLeavesSettingsPeriodsCoursesAndRevisionUntouched() = runBlocking {
        val profile = newProfile("datebounds")
        val db = open(profile)
        val repository = RoomScheduleRepository(db)
        repository.saveCourse(simpleDraft())
        val before = snapshot(repository)
        val changedPeriods = before.periods.map {
            if (it.number == 1) Period(1, time("07:50"), time("08:35")) else it
        }

        expectInvalid {
            repository.updateSettings(before.settings.copy(name = "Must roll back", startDate = LocalDate.MAX), changedPeriods)
        }

        assertEquals(before, snapshot(repository))
        db.close()
        openDatabases.remove(db)
        assertEquals(before, snapshot(RoomScheduleRepository(open(profile))))
    }

    @Test
    fun customTimeReferenceSectionsDoNotPreventPeriodRemoval() = runBlocking {
        val repository = RoomScheduleRepository(open(newProfile("custom")))
        repository.saveCourse(simpleDraft().copy(meetings = listOf(CourseMeeting(
            day = 2, weeks = setOf(1), sections = setOf(3), customTime = TimeRange(time("12:10"), time("12:40")),
        ))))
        val before = snapshot(repository)
        repository.updateSettings(before.settings, before.periods.filter { it.number != 3 })
        assertEquals(before.courses, snapshot(repository).courses)
    }

    @Test
    fun failedSettingsSqlRollsBackSettingsPeriodsRevisionAndExistingCourses() = runBlocking {
        val db = open(newProfile("rollback"))
        val repository = RoomScheduleRepository(db)
        repository.saveCourse(simpleDraft())
        val before = snapshot(repository)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_period_failure BEFORE INSERT ON periods WHEN NEW.number=2 BEGIN SELECT RAISE(ABORT, 'Synthetic period write failure'); END")
        try {
            expectFailure { repository.updateSettings(before.settings.copy(name = "Must roll back"), before.periods) }
            assertEquals(before, snapshot(repository))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_period_failure")
        }
    }

    @Test
    fun failedChildWriteRollsBackCourseEditChildrenAndRevision() = runBlocking {
        val db = open(newProfile("childrollback"))
        val repository = RoomScheduleRepository(db)
        val id = repository.saveCourse(simpleDraft())
        val before = snapshot(repository)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_section_failure BEFORE INSERT ON meeting_sections WHEN NEW.section=4 BEGIN SELECT RAISE(ABORT, 'Synthetic child write failure'); END")
        try {
            expectFailure { repository.saveCourse(simpleDraft().copy(courseName = "Must roll back", meetings = listOf(
                CourseMeeting(day = 5, weeks = setOf(2, 7), sections = setOf(2, 4)),
            )), id) }
            assertEquals(before, snapshot(repository))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_section_failure")
        }
    }

    @Test
    fun unknownEditAndDeleteIdsAndInvalidDraftsLeaveTheSnapshotUntouched() = runBlocking {
        val repository = RoomScheduleRepository(open(newProfile("invalid")))
        repository.saveCourse(simpleDraft())
        val before = snapshot(repository)
        expectInvalid { repository.saveCourse(simpleDraft(), 999_999) }
        assertEquals(before, snapshot(repository))
        expectInvalid { repository.deleteCourse(999_999) }
        assertEquals(before, snapshot(repository))
        for (invalid in listOf(simpleDraft().copy(courseName = " "), simpleDraft().copy(meetings = emptyList()),
            simpleDraft().copy(color = -1), simpleDraft().copy(meetings = listOf(CourseMeeting(day = 1, weeks = emptySet(), sections = setOf(1)))),
            simpleDraft().copy(meetings = listOf(CourseMeeting(day = 1, weeks = setOf(21), sections = setOf(1)))),
            simpleDraft().copy(meetings = listOf(CourseMeeting(day = 1, weeks = setOf(1), sections = setOf(13)))))) {
            expectInvalid { repository.saveCourse(invalid, before.courses.single().id) }
            assertEquals(before, snapshot(repository))
        }
    }

    @Test
    fun twoProfilesKeepDataSettingsAndSnapshotScopeSeparate() = runBlocking {
        val profileA = newProfile("A")
        val profileB = newProfile("B")
        val repositoryA = RoomScheduleRepository(open(profileA))
        val repositoryB = RoomScheduleRepository(open(profileB))
        val idA = repositoryA.saveCourse(simpleDraft())
        assertTrue(snapshot(repositoryB).courses.isEmpty())
        repositoryB.updateSettings(SemesterSettings(name = "Synthetic B", totalWeeks = 24), defaultPeriods())
        val idB = repositoryB.saveCourse(simpleDraft().copy(courseName = "Synthetic B course"))
        val a = snapshot(repositoryA)
        val b = snapshot(repositoryB)
        assertEquals(listOf(idA), a.courses.map { it.id })
        assertEquals(listOf(idB), b.courses.map { it.id })
        assertEquals("Synthetic same name", a.courses.single().courseName)
        assertEquals("Synthetic B course", b.courses.single().courseName)
        assertEquals(20, a.settings.totalWeeks)
        assertEquals(24, b.settings.totalWeeks)
        assertEquals(profileA, a.scopeToken)
        assertEquals(profileB, b.scopeToken)
        assertNotEquals(a.scopeToken, b.scopeToken)
    }

    private fun newProfile(prefix: String) = "test_s2_${prefix}_${UUID.randomUUID().toString().replace("-", "")}".also {
        ownedNames += "timetable_$it.db"
    }

    private fun open(profile: String) = TimetableDatabase.open(context, profile).also(openDatabases::add)
    private suspend fun snapshot(repository: RoomScheduleRepository) = withTimeout(5_000) { repository.observeTimetable().first() }
    private fun count(db: TimetableDatabase, table: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    private suspend fun expectInvalid(block: suspend () -> Unit) {
        var failure: Throwable? = null
        try { block() } catch (error: IllegalArgumentException) { failure = error }
        assertTrue("Expected invalid input to be rejected", failure is IllegalArgumentException)
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        var failure: Throwable? = null
        try { block() } catch (error: Exception) { failure = error }
        assertTrue("Expected the synthetic SQLite failure", failure != null)
    }

    private fun time(value: String) = LocalTime.parse(value)
    private fun simpleDraft() = CourseDraft("Synthetic same name", meetings = listOf(
        CourseMeeting(day = 1, weeks = setOf(1, 3, 8), sections = setOf(1, 3)),
    ))
    private fun complexDraft() = CourseDraft(" Synthetic exact course ", " Teacher ", " Room ", 0xF0123456L, listOf(
        CourseMeeting(day = 1, weeks = setOf(1, 3, 8, 20), sections = setOf(1, 3, 13), teacher = " Override teacher ", room = " Override room "),
        CourseMeeting(day = 5, weeks = setOf(2, 6, 22), sections = setOf(99), customTime = TimeRange(time("12:07"), time("12:49")), teacher = "", room = null),
    ))
}

