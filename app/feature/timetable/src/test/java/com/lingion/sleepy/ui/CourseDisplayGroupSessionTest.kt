package com.lingion.sleepy.ui

import com.lingion.sleepy.data.CourseDisplayGroup
import com.lingion.sleepy.data.CourseDisplayGroupKey
import com.lingion.sleepy.data.CourseDisplayGroupRead
import com.lingion.sleepy.data.CourseDisplayGroupStore
import com.lingion.sleepy.data.CourseDisplayGroupWrite
import com.lingion.sleepy.data.CourseGroupMember
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.ScheduledCourse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseDisplayGroupSessionTest {
    private val keyA = CourseDisplayGroupKey("local", 1)
    private val keyB = CourseDisplayGroupKey("local", 2)

    @Test
    fun activatesAndLoadsOncePerScope() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val session = CourseDisplayGroupSession(store, backgroundScope, UnconfinedTestDispatcher(testScheduler), { "g-1" }, keyA)

        session.activate(keyA)
        session.activate(keyA)

        assertEquals(1, store.reads)
        assertTrue(session.state.value.loaded)
        assertEquals(keyA, session.state.value.loadedKey)
        assertEquals(emptyList<CourseDisplayGroup>(), session.state.value.groups)
        assertEquals(0, store.writes)
    }

    @Test
    fun confirmedGroupIsPersistedAndReadByANewSession() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val courses = listOf(course(1, "Hadoop实验"), course(2, "Hadoop实验"), course(3, "线性代数"))
        val session = CourseDisplayGroupSession(store, backgroundScope, dispatcher, { "g-1" }, keyA)
        session.activate(keyA)

        val result = session.saveGroup(
            editingGroupId = null,
            name = "Hadoop 实验",
            courseIds = listOf(1, 2),
            courses = courses,
            allowMove = false,
            expected = session.currentWriteScope,
        )
        assertTrue(result is GroupSaveResult.Saved)

        assertEquals(1, session.state.value.groups.size)
        assertEquals("Hadoop 实验", session.state.value.groups.single().name)
        assertEquals(1L, session.state.value.savedSerial)
        assertNull(session.state.value.error)

        // A fresh session (process/Activity restart) reads the same configuration back.
        val restarted = CourseDisplayGroupSession(store, backgroundScope, dispatcher, { "g-2" }, keyA)
        restarted.activate(keyA)
        assertEquals(session.state.value.groups, restarted.state.value.groups)
    }

    @Test
    fun closingWithoutSavingWritesNothing() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val session = CourseDisplayGroupSession(store, backgroundScope, UnconfinedTestDispatcher(testScheduler), { "g-1" }, keyA)
        session.activate(keyA)

        // The editor validates the selection before it ever calls the session.
        assertEquals(0, store.writes)
        assertEquals(0L, session.state.value.savedSerial)
    }

    @Test
    fun failedWriteKeepsTheOldGroupsAndReportsIt() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val courses = listOf(course(1, "A"), course(2, "B"))
        val session = CourseDisplayGroupSession(store, backgroundScope, UnconfinedTestDispatcher(testScheduler), { "g-1" }, keyA)
        session.activate(keyA)
        store.failingWrites = true

        session.saveGroup(null, "组", listOf(1, 2), courses, allowMove = false, expected = session.currentWriteScope)
        advanceUntilIdle()

        assertEquals("展示分组未保存成功，请重试。", session.state.value.error)
        assertFalse(session.state.value.saving)
        assertEquals(emptyList<CourseDisplayGroup>(), session.state.value.groups)
        assertEquals(0L, session.state.value.savedSerial)
    }

    @Test
    fun corruptConfigurationFallsBackToAutomaticAndIsNotReportedAsLoaded() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        store.readResult = CourseDisplayGroupRead.Failed("展示分组配置已损坏，已按自动分组显示。可重新整理后保存。")
        val session = CourseDisplayGroupSession(store, backgroundScope, UnconfinedTestDispatcher(testScheduler), { "g-1" }, keyA)

        session.activate(keyA)

        assertFalse(session.state.value.loaded)
        assertNull(session.state.value.loadedKey)
        assertEquals(emptyList<CourseDisplayGroup>(), session.state.value.groups)
        assertEquals("展示分组配置已损坏，已按自动分组显示。可重新整理后保存。", session.state.value.error)
        assertEquals(0, store.writes)
        // A failed read still allows an explicit re-organize to replace the unreadable value.
        assertTrue(session.currentWriteScope != null)
    }

    @Test
    fun lateResultOfThePreviousScopeIsIgnored() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        store.data[keyB] = listOf(group("g-b", "B 组", listOf(3L, 4L)))
        val readA = CompletableDeferred<Unit>()
        val readB = CompletableDeferred<Unit>()
        store.readGates[keyA] = readA
        store.readGates[keyB] = readB
        val session = CourseDisplayGroupSession(store, backgroundScope, dispatcher, { "g-1" }, keyA)

        session.activate(keyA)
        session.activate(keyB)
        readB.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("B 组"), session.state.value.groups.map { it.name })

        readA.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("B 组"), session.state.value.groups.map { it.name })
        assertEquals(keyB, session.activeKey)
        assertEquals(keyB, session.state.value.loadedKey)
    }

    @Test
    fun lateWriteAfterScopeSwitchStaysInItsOwnScope() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val writeGate = CompletableDeferred<Unit>()
        store.writeGates[keyA] = writeGate
        val courses = listOf(course(1, "A"), course(2, "B"))
        val session = CourseDisplayGroupSession(store, backgroundScope, dispatcher, { "g-1" }, keyA)
        session.activate(keyA)

        session.saveGroup(null, "A 组", listOf(1, 2), courses, allowMove = false, expected = session.currentWriteScope)
        session.activate(keyB)
        writeGate.complete(Unit)
        advanceUntilIdle()

        // The write still belongs to the old scope's entry, and the new scope's state is untouched.
        assertEquals(keyA, store.lastWriteKey)
        assertEquals(emptyList<CourseDisplayGroup>(), session.state.value.groups)
        assertEquals(1, store.data[keyA]?.size)
        assertNull(store.data[keyB])
    }

    /**
     * P1 regression: a cleanup result computed for scope A must never be written after scope B became
     * active, even when B's read is still in flight when the stale result is submitted.
     */
    @Test
    fun staleCleanupComputedForThePreviousScopeIsRejected() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val first = course(1, "Hadoop实验")
        val second = course(2, "Hadoop实验")
        val third = course(3, "Hadoop实验")
        store.data[keyA] = listOf(
            CourseDisplayGroup(
                "g1", "Hadoop 实验",
                listOf(first, second, third).map { CourseGroupMember(it.id, courseDisplaySignature(it)) },
            ),
        )
        val readB = CompletableDeferred<Unit>()
        store.readGates[keyB] = readB
        val session = CourseDisplayGroupSession(store, backgroundScope, dispatcher, { "g-1" }, keyA)

        session.activate(keyA)
        val staleScope = session.currentWriteScope!!
        // The third record is gone from this snapshot, so this cleanup would drop its binding.
        val staleCleanup = pruneInvalidMembers(session.state.value.groups, listOf(first, second))!!
        assertEquals(2, staleCleanup.single().members.size)

        // Switch to B while its read is blocked, then let B finish before submitting the stale cleanup.
        session.activate(keyB)
        readB.complete(Unit)
        advanceUntilIdle()
        assertEquals(keyB, session.state.value.loadedKey)
        val writesBefore = store.writes

        assertFalse(session.persistIfCurrent(staleCleanup, staleScope))

        assertEquals(writesBefore, store.writes)
        assertNull(store.data[keyB])
        assertEquals(emptyList<CourseDisplayGroup>(), session.state.value.groups)
        assertEquals(keyB, session.state.value.loadedKey)
        // The stale submission did not overwrite A's stored configuration either.
        assertEquals(3, store.data[keyA]?.single()?.members?.size)
    }

    /**
     * Same scope, but the user saved in between: the generation moved on and an older computed write
     * must be rejected instead of overwriting the newer edit.
     */
    @Test
    fun staleWriteAfterANewerEditIsRejected() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val courses = listOf(course(1, "A"), course(2, "B"))
        val session = CourseDisplayGroupSession(store, backgroundScope, UnconfinedTestDispatcher(testScheduler), { "g-1" }, keyA)
        session.activate(keyA)
        val staleScope = session.currentWriteScope!!

        session.saveGroup(null, "第一组", listOf(1, 2), courses, allowMove = false, expected = staleScope)
        advanceUntilIdle()
        assertEquals(1, store.writes)
        val savedGroups = session.state.value.groups
        val writesBefore = store.writes

        val result = session.saveGroup(null, "第二组", listOf(1, 2), courses, allowMove = false, expected = staleScope)

        assertEquals(GroupSaveResult.Stale, result)
        assertEquals(writesBefore, store.writes)
        assertEquals(savedGroups, session.state.value.groups)
        assertTrue(session.state.value.error!!.contains("过期"))
    }

    /**
     * P2 regression: a failed maintenance write must not be retried by the same input state; only an
     * explicit retry (a new generation) may attempt it again.
     */
    @Test
    fun failedWriteIsNotRetriedAutomatically() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val courses = listOf(course(1, "A"), course(2, "B"))
        val session = CourseDisplayGroupSession(store, backgroundScope, UnconfinedTestDispatcher(testScheduler), { "g-1" }, keyA)
        session.activate(keyA)
        store.failingWrites = true

        session.saveGroup(null, "组", listOf(1, 2), courses, allowMove = false, expected = session.currentWriteScope)
        advanceUntilIdle()
        assertEquals(1, store.writes)
        assertTrue(session.state.value.error != null)

        // Advancing with no new input performs no further write attempts.
        advanceUntilIdle()
        assertEquals(1, store.writes)

        // An explicit retry (new attempt with the current scope) is allowed once.
        store.failingWrites = false
        val retried = session.saveGroup(null, "组", listOf(1, 2), courses, allowMove = false, expected = session.currentWriteScope)
        advanceUntilIdle()
        assertTrue(retried is GroupSaveResult.Saved)
        assertEquals(2, store.writes)
        assertNull(session.state.value.error)
        assertEquals(1, session.state.value.groups.size)
    }

    @Test
    fun deletedOrReplacedRecordsAreHiddenButTheStoredGroupIsKept() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val original = course(1, "Hadoop实验")
        val second = course(2, "Hadoop实验")
        val session = CourseDisplayGroupSession(store, backgroundScope, dispatcher, { "g-1" }, keyA)
        session.activate(keyA)
        session.saveGroup(null, "Hadoop 实验", listOf(1, 2), listOf(original, second), allowMove = false, expected = session.currentWriteScope)

        // The record with id 1 was replaced by an unrelated course (id reuse after a clear/re-import).
        val replaced = course(1, "线性代数")
        val reconcile = reconcileManualGroups(session.state.value.groups, listOf(replaced, second))

        assertTrue(reconcile.effective.isEmpty())
        assertEquals(1, reconcile.invalidMemberCount)
        assertEquals(listOf(1L, 2L), session.state.value.groups.single().members.map { it.courseId })
    }

    @Test
    fun savedSerialOnlyAdvancesOnAnEditorSave() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val courses = listOf(course(1, "A"), course(2, "B"))
        val session = CourseDisplayGroupSession(store, backgroundScope, UnconfinedTestDispatcher(testScheduler), { "g-1" }, keyA)
        session.activate(keyA)

        assertTrue(session.persistIfCurrent(listOf(group("g-1", "手动组", listOf(1L, 2L))), session.currentWriteScope!!))
        advanceUntilIdle()
        assertEquals(0L, session.state.value.savedSerial)

        session.saveGroup("g-1", "手动组", listOf(1, 2), courses, allowMove = false, expected = session.currentWriteScope)
        advanceUntilIdle()
        assertEquals(1L, session.state.value.savedSerial)
    }

    @Test
    fun rejectedSelectionIsExplainedAndWritesNothing() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val session = CourseDisplayGroupSession(store, backgroundScope, UnconfinedTestDispatcher(testScheduler), { "g-1" }, keyA)
        session.activate(keyA)

        val result = session.saveGroup(null, "组", listOf(1), listOf(course(1, "A")), allowMove = false, expected = session.currentWriteScope)

        assertEquals(GroupSaveResult.Invalid, result)
        assertTrue(session.state.value.error?.contains("两条现有课程记录") == true)
        assertEquals(0, store.writes)
    }

    @Test
    fun staleConfirmationIsExplainedAndWritesNothing() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        store.data[keyA] = listOf(group("g1", "已有组", listOf(1L, 2L)))
        val courses = listOf(course(1, "A"), course(2, "B"), course(3, "C"))
        val session = CourseDisplayGroupSession(store, backgroundScope, UnconfinedTestDispatcher(testScheduler), { "g-2" }, keyA)
        session.activate(keyA)
        val writesBefore = store.writes

        val result = session.saveGroup(null, "新组", listOf(2, 3), courses, allowMove = false, expected = session.currentWriteScope)

        assertTrue(result is GroupSaveResult.Rejected)
        assertEquals(writesBefore, store.writes)
        assertTrue(session.state.value.error?.contains("更改") == true)
    }

    /** P2 regression: legitimate but very long course fields must not throw on save. */
    @Test
    fun longCourseIdentityIsDigestedAndStillSaves() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val longName = "Hadoop实验".repeat(500)
        val longCode = "C".repeat(2048)
        val longClass = "D".repeat(2048)
        val first = course(1, longName, code = longCode, classCode = longClass)
        val second = course(2, longName, code = longCode, classCode = longClass)
        val signature = courseDisplaySignature(first)
        assertTrue(signature.startsWith("d1:"))
        assertTrue(signature.length <= 400)

        val session = CourseDisplayGroupSession(store, backgroundScope, UnconfinedTestDispatcher(testScheduler), { "g-1" }, keyA)
        session.activate(keyA)
        val result = session.saveGroup(null, "长字段组", listOf(1, 2), listOf(first, second), allowMove = false, expected = session.currentWriteScope)
        advanceUntilIdle()

        assertTrue(result is GroupSaveResult.Saved)
        assertEquals(listOf(signature, signature), session.state.value.groups.single().members.map { it.signature })
        assertEquals(1, reconcileManualGroups(session.state.value.groups, listOf(first, second)).effective.size)
        assertNull(session.state.value.error)
    }

    @Test
    fun hundredMembersAreAcceptedAndOneHundredAndOneAreRejected() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val session = CourseDisplayGroupSession(store, backgroundScope, dispatcher, { "g-1" }, keyA)
        session.activate(keyA)
        val hundred = (1L..100L).map { course(it, "课程$it") }

        val saved = session.saveGroup(null, "百条", hundred.map { it.id }, hundred, allowMove = false, expected = session.currentWriteScope)
        advanceUntilIdle()
        assertTrue(saved is GroupSaveResult.Saved)
        assertEquals(100, session.state.value.groups.single().members.size)

        val writesBefore = store.writes
        val overLimit = hundred + course(101, "课程101")
        val rejected = session.saveGroup(null, "超出", overLimit.map { it.id }, overLimit, allowMove = false, expected = session.currentWriteScope)

        assertEquals(GroupSaveResult.Invalid, rejected)
        assertEquals(writesBefore, store.writes)
        assertTrue(session.state.value.error!!.contains("100"))
        assertEquals(100, session.state.value.groups.single().members.size)
    }

    /**
     * P1 regression: leaving and re-entering the same scope must not make an old write scope valid
     * again. A→B→A with a completed read must keep the new A configuration, and a result computed on
     * the previous visit of A must be rejected without touching the store.
     */
    @Test
    fun oldWriteFromAPreviousVisitOfTheSameScopeIsRejectedAfterARoundTrip() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val first = course(1, "A")
        val second = course(2, "B")
        val session = CourseDisplayGroupSession(store, backgroundScope, dispatcher, { "g-new" }, keyA)

        // 1. Enter A and capture the binding of this visit plus a result computed then.
        session.activate(keyA)
        val oldScope = session.currentWriteScope!!
        val oldResult = session.state.value.groups
        assertEquals(emptyList<CourseDisplayGroup>(), oldResult)

        // 2. The user saves a newer A group; the disk now holds it.
        session.saveGroup(null, "新组", listOf(1, 2), listOf(first, second), allowMove = false, expected = oldScope)
        advanceUntilIdle()
        val savedGroups = session.state.value.groups
        assertEquals(1, savedGroups.size)
        val writesAfterSave = store.writes

        // 3. A -> B -> A, each read completing normally.
        session.activate(keyB)
        advanceUntilIdle()
        session.activate(keyA)
        advanceUntilIdle()
        assertEquals(keyA, session.state.value.loadedKey)
        assertEquals(savedGroups, session.state.value.groups)

        // 4. Submit the result of the previous A visit; it must not overwrite the fresh configuration.
        assertFalse(session.persistIfCurrent(oldResult, oldScope))
        assertEquals(writesAfterSave, store.writes)
        assertEquals(savedGroups, session.state.value.groups)
        assertEquals(listOf("新组"), store.data[keyA]?.map { it.name })
    }

    @Test
    fun generationNeverRepeatsAcrossScopeRoundTrips() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        val session = CourseDisplayGroupSession(store, backgroundScope, UnconfinedTestDispatcher(testScheduler), { "g-1" }, keyA)

        session.activate(keyA)
        val firstVisit = session.state.value.generation
        session.activate(keyB)
        val secondVisit = session.state.value.generation
        session.activate(keyA)
        val thirdVisit = session.state.value.generation

        assertTrue(firstVisit < secondVisit)
        assertTrue(secondVisit < thirdVisit)
    }

    private fun group(id: String, name: String, ids: List<Long>): CourseDisplayGroup =
        CourseDisplayGroup(id, name, ids.map { CourseGroupMember(it, "sig-${it}") })

    private fun course(id: Long, name: String, code: String? = null, classCode: String? = null): ScheduledCourse = ScheduledCourse(
        id = id,
        courseName = name,
        teacher = "",
        room = "",
        color = 0xFF6750A4,
        meetings = listOf(
            CourseMeeting(id = 0, day = 1, weeks = setOf(1), sections = setOf(1), kind = MeetingKind.RECURRING),
        ),
        sourceCourseCode = code,
        sourceClassCode = classCode,
    )

    private class FakeStore : CourseDisplayGroupStore {
        val data = linkedMapOf<CourseDisplayGroupKey, List<CourseDisplayGroup>>()
        val readGates = mutableMapOf<CourseDisplayGroupKey, CompletableDeferred<Unit>>()
        val writeGates = mutableMapOf<CourseDisplayGroupKey, CompletableDeferred<Unit>>()
        var readResult: CourseDisplayGroupRead? = null
        var failingWrites = false
        var reads = 0
            private set
        var writes = 0
            private set
        var lastWriteKey: CourseDisplayGroupKey? = null
            private set

        override suspend fun read(key: CourseDisplayGroupKey): CourseDisplayGroupRead {
            reads++
            readGates[key]?.await()
            readResult?.let { return it }
            return data[key]?.let { CourseDisplayGroupRead.Loaded(it) } ?: CourseDisplayGroupRead.Empty
        }

        override suspend fun write(key: CourseDisplayGroupKey, groups: List<CourseDisplayGroup>): CourseDisplayGroupWrite {
            writes++
            writeGates[key]?.await()
            if (failingWrites) return CourseDisplayGroupWrite.Failed("展示分组未保存成功，请重试。")
            lastWriteKey = key
            if (groups.isEmpty()) data.remove(key) else data[key] = groups
            return CourseDisplayGroupWrite.Saved
        }
    }
}