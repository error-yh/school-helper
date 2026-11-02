/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.ui

import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.domain.notice.*
import com.lingion.sleepy.domain.repository.*
import com.lingion.sleepy.importing.NoticeIntentKind
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoticeAdjustmentHistoryViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    private class TestScheduleRepository(
        snapshot: TimetableSnapshot,
    ) : ScheduleRepository {
        val flow = MutableStateFlow(snapshot)
        override fun observeTimetable(): Flow<TimetableSnapshot> = flow
        override suspend fun saveCourse(draft: CourseDraft, id: Long?): Long = id ?: 1L
        override suspend fun deleteCourse(id: Long) {}
        override suspend fun updateSettings(settings: SemesterSettings, periods: List<Period>) {}
    }

    private class TestNoticeAdjustmentRepository : NoticeAdjustmentRepository {
        var undoCount = 0

        override suspend fun preview(request: NoticeAdjustmentRequest): NoticeAdjustmentPreviewResult =
            throw UnsupportedOperationException()

        override suspend fun apply(confirmation: NoticeAdjustmentConfirmation): NoticeAdjustmentApplyResult =
            throw UnsupportedOperationException()

        override suspend fun history(limit: Int, beforeEventId: Long?): NoticeAdjustmentHistoryResult {
            val entry = NoticeAdjustmentHistoryEntry(
                eventId = 101L,
                kind = NoticeIntentKind.RESCHEDULE_TIME,
                courseId = 1L,
                courseTitle = "数据可视化",
                originalDate = LocalDate.of(2026, 11, 13),
                targetDate = LocalDate.of(2026, 11, 13),
                before = NoticeOccurrenceView(LocalDate.of(2026, 11, 13), setOf(3, 4), emptyList(), "张老师", "A101", null, null),
                after = NoticeOccurrenceView(LocalDate.of(2026, 11, 13), setOf(5, 6), emptyList(), "张老师", "A101", null, null),
                sourceText = "原定周五3-4节数据可视化改到5-6节",
                state = "APPLIED",
                appliedRevision = 8L,
                undoneRevision = null,
                canUndo = true,
            )
            return NoticeAdjustmentHistoryResult.Loaded(NoticeAdjustmentHistory(listOf(entry), null))
        }

        override suspend fun undo(eventId: Long, expectedRevision: Long): NoticeAdjustmentUndoResult {
            undoCount++
            return NoticeAdjustmentUndoResult.Undone(eventId, expectedRevision + 1)
        }
    }

    @Test
    fun historyPagingAndUndoLifecycle() = runTest {
        val course = ScheduledCourse(
            id = 1L,
            courseName = "数据可视化",
            teacher = "张老师",
            room = "A101",
            color = 0L,
            meetings = emptyList(),
        )
        val snapshot = TimetableSnapshot(
            scopeToken = "scope-1",
            revision = 8L,
            settings = SemesterSettings(id = 1L, name = "测试学期", startDate = LocalDate.of(2026, 9, 1), totalWeeks = 16),
            periods = emptyList(),
            courses = listOf(course),
        )
        val scheduleRepo = TestScheduleRepository(snapshot)
        val noticeRepo = TestNoticeAdjustmentRepository()
        val historyViewModel = NoticeAdjustmentHistoryViewModel(noticeRepo, scheduleRepo)
        testScheduler.advanceUntilIdle()

        val state = historyViewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(1, state.entries.size)
        val entry = state.entries.first()

        // 展开与收起原文
        assertFalse(state.expandedSourceEventIds.contains(entry.eventId))
        historyViewModel.toggleSourceExpand(entry.eventId)
        assertTrue(historyViewModel.uiState.value.expandedSourceEventIds.contains(entry.eventId))

        // 二次确认撤销
        historyViewModel.promptUndo(entry)
        assertEquals(entry, historyViewModel.uiState.value.confirmUndoEntry)

        historyViewModel.confirmUndo()
        testScheduler.advanceUntilIdle()
        assertEquals(1, noticeRepo.undoCount)
        assertNotNull(historyViewModel.uiState.value.undoSuccessMessage)
        assertNull(historyViewModel.uiState.value.confirmUndoEntry)
    }
}
