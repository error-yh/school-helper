package com.lingion.sleepy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimetableSnapshot
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimetableDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val openDatabases = mutableListOf<TimetableDatabase>()
    private val testDatabaseNames = mutableSetOf<String>()

    @After
    fun cleanUpOnlyOwnedTestDatabases() {
        openDatabases.forEach(TimetableDatabase::close)
        testDatabaseNames.forEach { name -> context.deleteDatabase(name) }
    }

    @Test
    fun repositoryWritesAndReadsUsingGeneratedDaoFlow() = runBlocking {
        val database = open(newTestProfile("flow"))
        val repository = RoomScheduleRepository(database)
        val emissions = Channel<TimetableSnapshot>(capacity = 2)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.observeTimetable().take(2).collect { emissions.send(it) }
        }

        try {
            assertTrue(withTimeout(5_000) { emissions.receive() }.courses.isEmpty())

            val id = repository.saveCourse(syntheticCourse)
            val saved = withTimeout(5_000) { emissions.receive() }

            assertTrue(id > 0)
            assertSavedCourse(id, syntheticCourse, saved.courses.single())
            withTimeout(5_000) { collector.join() }
        } finally {
            collector.cancelAndJoin()
            emissions.close()
        }
    }

    @Test
    fun savedRowsSurviveClosingAndReopeningFileDatabase() = runBlocking {
        val profileId = newTestProfile("reopen")
        val database = open(profileId)
        val repository = RoomScheduleRepository(database)
        val id = repository.saveCourse(syntheticCourse)
        val beforeClose = withTimeout(5_000) { repository.observeTimetable().first() }
        val expectedFile = context.getDatabasePath("timetable_$profileId.db")
        assertTrue(expectedFile.isFile)
        database.close()
        openDatabases.remove(database)

        val reopened = open(profileId)
        val saved = withTimeout(5_000) {
            RoomScheduleRepository(reopened).observeTimetable().first()
        }

        assertSavedCourse(id, syntheticCourse, saved.courses.single())
        assertEquals(beforeClose, saved)
        assertEquals(4, reopened.openHelper.readableDatabase.version)
    }

    @Test
    fun independentProfilesReadOnlyTheirOwnSavedRows() = runBlocking {
        val profileA = newTestProfile("profileA")
        val profileB = newTestProfile("profileB")
        val repositoryA = RoomScheduleRepository(open(profileA))
        val repositoryB = RoomScheduleRepository(open(profileB))
        val courseA = syntheticCourse.copy(courseName = "Synthetic A")
        val courseB = syntheticCourse.copy(courseName = "Synthetic B", meetings = syntheticCourse.meetings.map { it.copy(day = 7) })
        val idA = repositoryA.saveCourse(courseA)
        assertTrue(withTimeout(5_000) { repositoryB.observeTimetable().first() }.courses.isEmpty())
        val idB = repositoryB.saveCourse(courseB)

        assertSavedCourse(idA, courseA, withTimeout(5_000) { repositoryA.observeTimetable().first() }.courses.single())
        assertSavedCourse(idB, courseB, withTimeout(5_000) { repositoryB.observeTimetable().first() }.courses.single())
    }

    @Test
    fun profileIdRejectsPathsBlankSpacesAndOverlongNames() {
        listOf("", " ", "../outside", "a/b", "a\\b", "a.b", "a b", "a".repeat(65))
            .forEach { profileId ->
                assertThrows(IllegalArgumentException::class.java) {
                    TimetableDatabase.open(context, profileId).also {
                        openDatabases += it
                    }
                }
            }
    }

    private fun newTestProfile(prefix: String): String =
        "test_${prefix}_${UUID.randomUUID().toString().replace("-", "")}".also { profileId ->
            testDatabaseNames += "timetable_$profileId.db"
        }

    private fun open(profileId: String): TimetableDatabase =
        TimetableDatabase.open(context, profileId).also { openDatabases += it }

    private fun assertSavedCourse(id: Long, expected: CourseDraft, actual: ScheduledCourse) {
        assertEquals(id, actual.id)
        assertTrue(actual.meetings.all { it.id > 0 })
        assertEquals(expected, actual.asDraft().copy(meetings = actual.meetings.map { it.copy(id = 0) }))
    }

    private companion object {
        val syntheticCourse = CourseDraft(
            courseName = "Synthetic persistence course",
            meetings = listOf(CourseMeeting(day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2))),
        )
    }
}

