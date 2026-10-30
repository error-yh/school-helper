package com.lingion.sleepy.ui

import androidx.lifecycle.ViewModelStore
import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.domain.repository.ScheduleRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class TimetableViewModelTest {
    private val stores = mutableListOf<ViewModelStore>()
    @Before fun setUp() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun tearDown() { stores.forEach(ViewModelStore::clear); Dispatchers.resetMain() }

    @Test fun openingPageReadsSavedSnapshotWithoutInsertingAnything() = runTest {
        val saved = course(19, "已保存的合成课")
        val snapshot = TimetableSnapshot(courses = listOf(saved), revision = 7)
        val repository = FakeRepository(snapshot)
        val model = viewModel(repository)
        runCurrent()
        assertEquals(snapshot, model.uiState.value.snapshot)
        assertEquals(0, repository.saveCalls)
        assertFalse(model.uiState.value.isLoading)
    }
    @Test fun saveStaysPendingAndIgnoresRepeatClickUntilSaved() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeRepository(saveGate = gate)
        val model = viewModel(repository)
        runCurrent()
        model.saveCourse(draft); runCurrent()
        assertTrue(model.uiState.value.isSaving)
        model.saveCourse(draft); runCurrent()
        assertEquals(1, repository.saveCalls)
        gate.complete(Unit); advanceUntilIdle()
        assertFalse(model.uiState.value.isSaving)
        assertEquals(listOf(course(101)), model.uiState.value.snapshot.courses)
        assertEquals(TimetableOperation.COURSE_SAVED, model.uiState.value.completedOperation)
    }
    @Test fun failedSaveShowsFriendlyErrorAndCanRetrySameDraft() = runTest {
        val repository = FakeRepository(failSave = true)
        val model = viewModel(repository)
        runCurrent(); model.saveCourse(draft); advanceUntilIdle()
        assertFalse(model.uiState.value.isSaving)
        assertEquals("暂时无法保存课程，请重试。", model.uiState.value.errorMessage)
        assertTrue(model.uiState.value.snapshot.courses.isEmpty())
        assertEquals(null, model.uiState.value.completedOperation)
        repository.failSave = false
        model.saveCourse(draft); advanceUntilIdle()
        assertEquals(2, repository.saveCalls)
        assertEquals(null, model.uiState.value.errorMessage)
        assertEquals(listOf(course(101)), model.uiState.value.snapshot.courses)
    }
    @Test fun failedReadBlocksAllWritesUntilExplicitRetryObservesSavedRows() = runTest {
        val saved = course(19, "已保存的合成课")
        val repository = FakeRepository(TimetableSnapshot(courses = listOf(saved)), failRead = true)
        val model = viewModel(repository)
        runCurrent()
        val error = "暂时无法读取课程，请重试。"
        assertEquals(error, model.uiState.value.errorMessage)
        assertFalse(model.uiState.value.isLoading)
        model.saveCourse(draft); model.deleteCourse(19)
        model.saveSettings(SemesterSettings(), defaultPeriods()); runCurrent()
        assertEquals(0, repository.saveCalls + repository.deleteCalls + repository.settingsCalls)
        assertEquals(error, model.uiState.value.errorMessage)
        val gate = CompletableDeferred<Unit>()
        repository.failRead = false; repository.readGate = gate
        model.retryLoading(); runCurrent()
        assertTrue(model.uiState.value.isLoading)
        assertEquals(error, model.uiState.value.errorMessage)
        model.saveCourse(draft); runCurrent()
        assertEquals(0, repository.saveCalls)
        gate.complete(Unit); runCurrent()
        assertEquals(listOf(saved), model.uiState.value.snapshot.courses)
        assertFalse(model.uiState.value.isLoading)
        assertEquals(null, model.uiState.value.errorMessage)
        model.saveCourse(draft); advanceUntilIdle()
        assertEquals(1, repository.saveCalls)
        assertEquals(listOf(saved, course(101)), model.uiState.value.snapshot.courses)
    }
    @Test fun repeatedReadRetryCancelsPreviousObservation() = runTest {
        val repository = FakeRepository()
        val model = viewModel(repository)
        runCurrent()
        assertEquals(1, repository.activeObservers)
        repeat(2) { index ->
            model.retryLoading(); runCurrent()
            assertEquals(index + 2, repository.readAttempts)
            assertEquals(1, repository.activeObservers)
        }
    }
    @Test fun editUsesStableIdAndDeleteChangesObservedSnapshot() = runTest {
        val repository = FakeRepository(TimetableSnapshot(courses = listOf(course(19))))
        val model = viewModel(repository)
        runCurrent()
        model.saveCourse(draft.copy(courseName = "修改后的课", room = "B202"), 19); advanceUntilIdle()
        assertEquals(19L, repository.lastSavedId)
        assertEquals("修改后的课", model.uiState.value.snapshot.courses.single().courseName)
        assertEquals("B202", model.uiState.value.snapshot.courses.single().room)
        model.deleteCourse(19); advanceUntilIdle()
        assertTrue(model.uiState.value.snapshot.courses.isEmpty())
        assertEquals(TimetableOperation.COURSE_DELETED, model.uiState.value.completedOperation)
        assertEquals("课程已删除", model.uiState.value.message)
    }
    @Test fun settingsSavePublishesWholeSnapshotAndFriendlyFailureCanRetry() = runTest {
        val repository = FakeRepository(failSettings = true)
        val model = viewModel(repository)
        val settings = SemesterSettings(name = "合成学期", startDate = LocalDate.of(2026, 10, 19), totalWeeks = 10)
        val periods = defaultPeriods().take(3)
        runCurrent(); model.saveSettings(settings, periods); advanceUntilIdle()
        assertEquals("暂时无法保存设置，请重试。", model.uiState.value.errorMessage)
        assertEquals(SemesterSettings(), model.uiState.value.snapshot.settings)
        repository.failSettings = false
        model.saveSettings(settings, periods); advanceUntilIdle()
        assertEquals(settings, model.uiState.value.snapshot.settings)
        assertEquals(periods, model.uiState.value.snapshot.periods)
        assertEquals("设置已保存", model.uiState.value.message)
    }
    @Test fun deleteFailureKeepsCourseAndCanRetry() = runTest {
        val repository = FakeRepository(TimetableSnapshot(courses = listOf(course(19))), failDelete = true)
        val model = viewModel(repository)
        runCurrent(); model.deleteCourse(19); advanceUntilIdle()
        assertEquals("暂时无法删除课程，请重试。", model.uiState.value.errorMessage)
        assertEquals(listOf(course(19)), model.uiState.value.snapshot.courses)
        repository.failDelete = false
        model.deleteCourse(19); advanceUntilIdle()
        assertTrue(model.uiState.value.snapshot.courses.isEmpty())
    }
    @Test fun operationCursorDoesNotReplayWhenSameVmIsRecreatedButConsumesLaterSave() {
        val sameVm = TimetableOperationTracker(initialSerial = 0)
        (1L..7L).forEach { assertTrue(sameVm.consume(it)) }
        val recreatedUi = sameVm
        assertFalse(recreatedUi.consume(7))
        // Save 8 completed while the old UI was detached. The VM keeps it pending for the new UI.
        assertTrue(recreatedUi.consume(8))
        assertFalse(recreatedUi.consume(8))
    }
    @Test fun operationCursorStartsFromNewVmCounterAfterPriorProcessHandledManySaves() {
        val oldProcess = TimetableOperationTracker(initialSerial = 0)
        (1L..7L).forEach { assertTrue(oldProcess.consume(it)) }
        // The editor's raw draft is restored, but event counters belong to this new VM instance.
        val newProcess = TimetableOperationTracker(initialSerial = 0)
        assertFalse(newProcess.consume(0))
        assertTrue(newProcess.consume(1))
        assertFalse(newProcess.consume(1))
    }
    private fun viewModel(repository: ScheduleRepository) = TimetableViewModel(repository).also { model ->
        stores += ViewModelStore().apply { put("timetable-test", model) }
    }
    private class FakeRepository(
        initial: TimetableSnapshot = TimetableSnapshot(),
        private val saveGate: CompletableDeferred<Unit>? = null,
        var failSave: Boolean = false,
        var failRead: Boolean = false,
        var failSettings: Boolean = false,
        var failDelete: Boolean = false,
        var readGate: CompletableDeferred<Unit>? = null,
    ) : ScheduleRepository {
        private val snapshot = MutableStateFlow(initial)
        var saveCalls = 0; private set
        var deleteCalls = 0; private set
        var settingsCalls = 0; private set
        var lastSavedId: Long? = null; private set
        var readAttempts = 0; private set
        var activeObservers = 0; private set
        override fun observeTimetable(): Flow<TimetableSnapshot> = flow {
            readAttempts++; activeObservers++
            try {
                if (failRead) error("Synthetic read failure")
                readGate?.await(); emitAll(snapshot)
            } finally { activeObservers-- }
        }
        override suspend fun saveCourse(draft: CourseDraft, id: Long?): Long {
            saveCalls++; lastSavedId = id; saveGate?.await()
            if (failSave) error("Synthetic private implementation detail")
            val saved = ScheduledCourse(id ?: 101, draft.courseName, draft.teacher, draft.room, draft.color, draft.meetings)
            snapshot.value = snapshot.value.copy(courses = snapshot.value.courses.filterNot { it.id == saved.id } + saved)
            return saved.id
        }
        override suspend fun deleteCourse(id: Long) {
            deleteCalls++
            if (failDelete) error("Synthetic delete failure")
            snapshot.value = snapshot.value.copy(courses = snapshot.value.courses.filterNot { it.id == id })
        }
        override suspend fun updateSettings(settings: SemesterSettings, periods: List<Period>) {
            settingsCalls++
            if (failSettings) error("Synthetic settings failure")
            snapshot.value = snapshot.value.copy(settings = settings, periods = periods)
        }
    }
    private companion object {
        val draft = CourseDraft("测试录入课", "教师", "教室", meetings = listOf(CourseMeeting(day = 1, weeks = setOf(1, 3, 5), sections = setOf(1, 3))))
        fun course(id: Long, name: String = draft.courseName) = ScheduledCourse(id, name, draft.teacher, draft.room, draft.color, draft.meetings)
    }
}
