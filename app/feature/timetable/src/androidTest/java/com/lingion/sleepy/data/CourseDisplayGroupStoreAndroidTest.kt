package com.lingion.sleepy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Platform persistence check for the display-only grouping configuration. It uses an isolated
 * preferences file and never touches the app's timetable database, profile or account data.
 */
class CourseDisplayGroupStoreAndroidTest {
    private val fileName = "course_display_groups_test"
    private val key = CourseDisplayGroupKey("android-test-scope", 7L)
    private val otherKey = CourseDisplayGroupKey("android-test-scope", 8L)
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearBefore() {
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun clearAfter() {
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun savedGroupsAreReadBackByANewStoreInstance() {
        runBlocking {
            val groups = listOf(
                group("g1", "Hadoop 实验", listOf(1L, 2L, 3L)),
                group("g2", "体育", listOf(4L, 5L)),
            )
            val store = SharedPreferencesCourseDisplayGroupStore(context, fileName)
            assertEquals(CourseDisplayGroupWrite.Saved, store.write(key, groups))

            val reopened = SharedPreferencesCourseDisplayGroupStore(context, fileName)
            val read = reopened.read(key)
            assertTrue(read is CourseDisplayGroupRead.Loaded)
            assertEquals(groups, (read as CourseDisplayGroupRead.Loaded).groups)
        }
    }

    @Test
    fun scopeAndSemesterEntriesStayIsolated() {
        runBlocking {
            val store = SharedPreferencesCourseDisplayGroupStore(context, fileName)
            val groups = listOf(group("g1", "本学期", listOf(1L, 2L)))
            store.write(key, groups)

            assertEquals(CourseDisplayGroupRead.Empty, store.read(otherKey))

            store.write(otherKey, listOf(group("g2", "另一学期", listOf(3L, 4L))))
            val first = store.read(key) as CourseDisplayGroupRead.Loaded
            val second = store.read(otherKey) as CourseDisplayGroupRead.Loaded
            assertEquals(listOf("本学期"), first.groups.map { it.name })
            assertEquals(listOf("另一学期"), second.groups.map { it.name })
        }
    }

    @Test
    fun removingEveryGroupClearsOnlyThatScope() {
        runBlocking {
            val store = SharedPreferencesCourseDisplayGroupStore(context, fileName)
            store.write(key, listOf(group("g1", "组", listOf(1L, 2L))))
            store.write(otherKey, listOf(group("g2", "组", listOf(3L, 4L))))

            assertEquals(CourseDisplayGroupWrite.Saved, store.write(key, emptyList()))
            assertEquals(CourseDisplayGroupRead.Empty, store.read(key))
            assertTrue(store.read(otherKey) is CourseDisplayGroupRead.Loaded)
        }
    }

    @Test
    fun corruptValueIsReportedAndDoesNotCrashTheReader() {
        runBlocking {
            context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
                .edit().putString(key.storageKey, "{not valid json").commit()
            val store = SharedPreferencesCourseDisplayGroupStore(context, fileName)

            val read = store.read(key)
            assertTrue(read is CourseDisplayGroupRead.Failed)
            assertTrue((read as CourseDisplayGroupRead.Failed).message.isNotBlank())
        }
    }

    @Test
    fun lastWrittenValueWins() {
        runBlocking {
            val store = SharedPreferencesCourseDisplayGroupStore(context, fileName)
            store.write(key, listOf(group("g1", "旧组", listOf(1L, 2L))))
            store.write(key, listOf(group("g2", "新组", listOf(3L, 4L)), group("g3", "附加组", listOf(5L, 6L))))

            val read = store.read(key) as CourseDisplayGroupRead.Loaded
            assertEquals(listOf("新组", "附加组"), read.groups.map { it.name })
            assertEquals(listOf(3L, 4L), read.groups.first().members.map { it.courseId })
        }
    }

    private fun group(id: String, name: String, ids: List<Long>): CourseDisplayGroup =
        CourseDisplayGroup(id, name, ids.map { CourseGroupMember(it, "sig-$it") })
}