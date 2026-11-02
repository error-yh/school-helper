/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.ui

import com.lingion.sleepy.domain.imports.*
import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.domain.notice.*
import com.lingion.sleepy.domain.repository.*
import com.lingion.sleepy.importing.NoticeDayPart
import com.lingion.sleepy.importing.NoticeIntentKind
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
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
class NoticeAdjustmentViewModelTest {

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

    private open class TestImportRepository(
        private val scheduleRepo: TestScheduleRepository,
        private val bases: List<ImportLogicalBaseDisplay>,
    ) : ImportRepository {
        override suspend fun readImportTarget(semesterId: Long): ImportTargetView {
            val snap = scheduleRepo.flow.value
            return ImportTargetView(
                target = ImportTarget(snap.scopeToken, snap.settings.id, snap.revision, 5),
                snapshot = snap,
                logicalBases = bases.map { it.reference },
                logicalBaseDetails = bases,
            )
        }
        override suspend fun previewImport(reviewed: ReviewedImport, conflicts: List<ConflictReviewDecision>): ImportPreviewResult =
            throw UnsupportedOperationException()
        override suspend fun applyImport(command: ImportApplyCommand): ImportApplyResult =
            throw UnsupportedOperationException()
    }

    private class TestNoticeAdjustmentRepository : NoticeAdjustmentRepository {
        var previewCount = 0
        var applyCount = 0
        var undoCount = 0
        var lastAppliedConfirmation: NoticeAdjustmentConfirmation? = null

        var nextPreviewResult: (suspend (NoticeAdjustmentRequest) -> NoticeAdjustmentPreviewResult)? = null
        var nextApplyResult: (suspend (NoticeAdjustmentConfirmation) -> NoticeAdjustmentApplyResult)? = null
        var nextHistoryResult: (suspend (Int, Long?) -> NoticeAdjustmentHistoryResult)? = null
        var nextUndoResult: (suspend (Long, Long) -> NoticeAdjustmentUndoResult)? = null

        override suspend fun preview(request: NoticeAdjustmentRequest): NoticeAdjustmentPreviewResult {
            previewCount++
            nextPreviewResult?.let { return it(request) }

            val dummyProposal = NoticeAdjustmentProposal(
                semanticKey = "dummy-semantic",
                confirmDigest = "dummy-digest",
                target = request.target,
                calendarFingerprint = "dummy-fp",
                basis = request.intent.basis,
                rawTextDigest = "raw-digest",
                kind = request.intent.kind,
                candidate = request.selection,
                courseId = 1L,
                courseTitle = "数据可视化",
                originalDate = request.selection?.let { LocalDate.of(2026, 11, 13) },
                targetDate = LocalDate.of(2026, 11, 13),
                meetingId = 11L,
                before = NoticeOccurrenceView(
                    date = LocalDate.of(2026, 11, 13),
                    sections = setOf(3, 4),
                    ranges = emptyList(),
                    teacher = "张老师",
                    room = "A101",
                    project = null,
                    group = null,
                ),
                after = NoticeOccurrenceView(
                    date = LocalDate.of(2026, 11, 13),
                    sections = setOf(5, 6),
                    ranges = emptyList(),
                    teacher = "张老师",
                    room = "A101",
                    project = null,
                    group = null,
                ),
                keptFields = emptySet(),
                impact = NoticeImpact(NoticeImpactScope.ONE_OCCURRENCE, listOf(LocalDate.of(2026, 11, 13))),
                replacesExistingAdjustment = request.replaceExistingAdjustment,
                replacedExceptionId = null,
                conflicts = emptyList(),
            )

            val confirmation = NoticeAdjustmentConfirmation(
                target = request.target,
                requestKey = "req-key",
                semanticKey = "dummy-semantic",
                confirmDigest = "dummy-digest",
                intent = request.intent,
                selection = request.selection,
                extraCourse = request.extraCourse,
                acknowledgedConflicts = request.acknowledgedConflicts,
                replaceExistingAdjustment = request.replaceExistingAdjustment,
                sourceText = request.intent.rawText,
                sourceDigest = "digest",
            )
            return NoticeAdjustmentPreviewResult.Ready(
                NoticeAdjustmentPreview(request.target, dummyProposal, "req-key", confirmation, null)
            )
        }

        override suspend fun apply(confirmation: NoticeAdjustmentConfirmation): NoticeAdjustmentApplyResult {
            applyCount++
            lastAppliedConfirmation = confirmation
            nextApplyResult?.let { return it(confirmation) }
            return NoticeAdjustmentApplyResult.Applied(eventId = 101L, revision = 8L, mode = "APPEND")
        }

        override suspend fun history(limit: Int, beforeEventId: Long?): NoticeAdjustmentHistoryResult {
            nextHistoryResult?.let { return it(limit, beforeEventId) }
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
            nextUndoResult?.let { return it(eventId, expectedRevision) }
            return NoticeAdjustmentUndoResult.Undone(eventId, expectedRevision + 1)
        }
    }

    private fun createFixtures(): Triple<TestNoticeAdjustmentRepository, TestScheduleRepository, TestImportRepository> {
        val course = NoticeFixtures.course(1L, "数据可视化", teacher = "张老师", room = "A101", meetings = listOf(NoticeFixtures.meeting(id = 11L, room = "A101", teacher = "张老师")))
        val snap = NoticeFixtures.snapshot(listOf(course))
        val base = NoticeFixtures.base(course, course.meetings.first())
        val scheduleRepo = TestScheduleRepository(snap)
        val importRepo = TestImportRepository(scheduleRepo, listOf(base))
        val noticeRepo = TestNoticeAdjustmentRepository()
        return Triple(noticeRepo, scheduleRepo, importRepo)
    }

    private val noticeText = "本周五的数据可视化课程由原来的第3-4节调整为第5-6节，教室不变。"

    @Test
    fun parseWithRelativeDateRequiresExplicitReferenceDate() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)

        viewModel.updateRawText(noticeText)
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("必须显式要求 REFERENCE_DATE", state.missingBasisFields.contains(NoticeBasisField.REFERENCE_DATE))
        assertTrue(state.parseResults.isEmpty())

        // 用户显式填入参考基准日，同时复用学期周一和总周数
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        val parsedState = viewModel.uiState.value
        assertTrue("补全后缺少字段为空", parsedState.missingBasisFields.isEmpty())
        assertTrue("解析出意图", parsedState.parseResults.isNotEmpty())
        assertEquals(1, parsedState.candidates.size)
        // 关键约束：唯一候选也需明确选择，名字不是写入授权
        assertNull("默认不可自动授权候选", parsedState.selectedCandidateId)
    }

    @Test
    fun week10FridayRescheduleCompleteFlow() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)

        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        val stateAfterParse = viewModel.uiState.value
        assertEquals(1, stateAfterParse.candidates.size)
        val cand = stateAfterParse.candidates.first()

        // 显式手动选择候选
        viewModel.selectCandidate(cand.id)
        assertEquals(cand.id, viewModel.uiState.value.selectedCandidateId)

        // 生成预览
        viewModel.preview()
        testScheduler.advanceUntilIdle()
        val previewState = viewModel.uiState.value
        assertNotNull("预览应生成成功", previewState.preview)
        assertEquals(1, noticeRepo.previewCount)

        // 确认仅修改这一次
        viewModel.apply()
        testScheduler.advanceUntilIdle()
        val appliedState = viewModel.uiState.value
        assertNotNull(appliedState.appliedSuccessMessage)
        assertEquals(101L, appliedState.appliedEventId)
        assertEquals(1, noticeRepo.applyCount)
        assertNull("应用后预览应清空", appliedState.preview)
    }

    @Test
    fun singleCandidateRequiresExplicitSelectionBeforePreview() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)

        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        // 未选候选直接调用 preview
        viewModel.preview()
        testScheduler.advanceUntilIdle()
        val state = viewModel.uiState.value
        assertNull(state.preview)
        assertTrue(state.previewProblems.any { it.code == NoticeCodes.NEED_MANUAL_SELECTION })
        assertEquals(0, noticeRepo.previewCount)
    }

    @Test
    fun extraCourseRequiresExplicitCourseSelection() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)

        viewModel.updateRawText("11月13日补课数据可视化第5-6节在A101")
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        val stateAfterParse = viewModel.uiState.value
        assertTrue("补课应加载可选课程列表", stateAfterParse.availableCourses.isNotEmpty())
        assertNull("补课课程默认未选", stateAfterParse.selectedExtraCourseId)

        // 未选择补课课程时预览阻断
        viewModel.preview()
        testScheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.preview)
        assertTrue(viewModel.uiState.value.previewProblems.any { it.code == NoticeCodes.NEED_COURSE_SELECTION })

        // 显式选课后预览成功
        viewModel.selectExtraCourse(1L)
        viewModel.preview()
        testScheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.preview)
    }

    @Test
    fun conflictPendingRequiresExplicitAcknowledgment() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val conflict = NoticeConflict(
            fingerprint = "fp-conflict-1",
            date = LocalDate.of(2026, 11, 13),
            otherCourseId = 2L,
            otherMeetingId = 22L,
            otherTitle = "线性代数",
            otherRanges = emptyList(),
            overlap = NoticeFixtures.ranges(5),
        )

        noticeRepo.nextPreviewResult = { req ->
            if ("fp-conflict-1" !in req.acknowledgedConflicts) {
                val dummyProposal = NoticeAdjustmentProposal(
                    semanticKey = "dummy-semantic",
                    confirmDigest = "dummy-digest",
                    target = req.target,
                    calendarFingerprint = "dummy-fp",
                    basis = req.intent.basis,
                    rawTextDigest = "raw-digest",
                    kind = req.intent.kind,
                    candidate = req.selection,
                    courseId = 1L,
                    courseTitle = "数据可视化",
                    originalDate = LocalDate.of(2026, 11, 13),
                    targetDate = LocalDate.of(2026, 11, 13),
                    meetingId = 11L,
                    before = null,
                    after = null,
                    keptFields = emptySet(),
                    impact = NoticeImpact(NoticeImpactScope.ONE_OCCURRENCE, listOf(LocalDate.of(2026, 11, 13))),
                    replacesExistingAdjustment = false,
                    replacedExceptionId = null,
                    conflicts = listOf(conflict),
                )
                NoticeAdjustmentPreviewResult.ConflictPending(dummyProposal, listOf(NoticeProblem(NoticeCodes.CONFLICT_PENDING)))
            } else {
                val dummyProposal = NoticeAdjustmentProposal(
                    semanticKey = "dummy-semantic",
                    confirmDigest = "dummy-digest",
                    target = req.target,
                    calendarFingerprint = "dummy-fp",
                    basis = req.intent.basis,
                    rawTextDigest = "raw-digest",
                    kind = req.intent.kind,
                    candidate = req.selection,
                    courseId = 1L,
                    courseTitle = "数据可视化",
                    originalDate = LocalDate.of(2026, 11, 13),
                    targetDate = LocalDate.of(2026, 11, 13),
                    meetingId = 11L,
                    before = null,
                    after = null,
                    keptFields = emptySet(),
                    impact = NoticeImpact(NoticeImpactScope.ONE_OCCURRENCE, listOf(LocalDate.of(2026, 11, 13))),
                    replacesExistingAdjustment = false,
                    replacedExceptionId = null,
                    conflicts = listOf(conflict),
                )
                val conf = NoticeAdjustmentConfirmation(
                    target = req.target,
                    requestKey = "req-key",
                    semanticKey = "dummy-semantic",
                    confirmDigest = "dummy-digest",
                    intent = req.intent,
                    selection = req.selection,
                    extraCourse = req.extraCourse,
                    acknowledgedConflicts = req.acknowledgedConflicts,
                    replaceExistingAdjustment = false,
                    sourceText = req.intent.rawText,
                    sourceDigest = "digest",
                )
                NoticeAdjustmentPreviewResult.Ready(NoticeAdjustmentPreview(req.target, dummyProposal, "req-key", conf, null))
            }
        }

        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        val candId = viewModel.uiState.value.candidates.first().id
        viewModel.selectCandidate(candId)

        viewModel.preview()
        testScheduler.advanceUntilIdle()
        assertNull("冲突未确认时 preview 必须为 null", viewModel.uiState.value.preview)
        assertEquals(1, viewModel.uiState.value.detectedConflicts.size)

        // 显式勾选冲突
        viewModel.acknowledgeConflict("fp-conflict-1", true)
        viewModel.preview()
        testScheduler.advanceUntilIdle()
        assertNotNull("确认冲突后重新 preview 成功", viewModel.uiState.value.preview)
    }

    @Test
    fun inputModificationClearsOldPreviewAndConfirmation() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)

        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        viewModel.selectCandidate(viewModel.uiState.value.candidates.first().id)
        viewModel.preview()
        testScheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.preview)

        // 修改输入
        viewModel.updateRawText("本周五的数据可视化停课")
        testScheduler.advanceUntilIdle()
        assertNull("修改输入后旧 preview 必须立即清除", viewModel.uiState.value.preview)
        assertFalse(viewModel.uiState.value.isAlreadyApplied)
    }

    @Test
    fun rapidClicksPreventDuplicateApply() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)

        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        viewModel.selectCandidate(viewModel.uiState.value.candidates.first().id)
        viewModel.preview()
        testScheduler.advanceUntilIdle()

        // 连续调用 apply
        viewModel.apply()
        viewModel.apply()
        testScheduler.advanceUntilIdle()
        assertEquals("快速重复点击只允许调用一次 apply", 1, noticeRepo.applyCount)
    }

    @Test
    fun historyPagingAndUndoLifecycle() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
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

    @Test
    fun normalizedCourseReplaceRejectionMapsProperly() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        noticeRepo.nextApplyResult = {
            NoticeAdjustmentApplyResult.Rejected(NoticeAdjustmentCodes.REPLACE_UNSUPPORTED_FOR_NORMALIZED)
        }

        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        viewModel.selectCandidate(viewModel.uiState.value.candidates.first().id)
        viewModel.preview()
        testScheduler.advanceUntilIdle()
        viewModel.apply()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.applyErrorMessage)
        assertTrue("错误说明中应包含归一化课不支持替换说明", state.applyErrorMessage!!.contains("归一化课"))
    }

    @Test
    fun staleRevisionOnApplyGuidesUserToRepreview() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        noticeRepo.nextApplyResult = {
            NoticeAdjustmentApplyResult.Rejected(NoticeAdjustmentCodes.STALE_REVISION)
        }

        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        viewModel.selectCandidate(viewModel.uiState.value.candidates.first().id)
        viewModel.preview()
        testScheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.preview)

        viewModel.apply()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull("拒绝后旧 preview 必须清空", state.preview)
        assertNotNull(state.applyErrorMessage)
        assertTrue("错误信息引导用户重新预览", state.applyErrorMessage!!.contains("重新生成预览"))
    }

    @Test
    fun cancelFlowSetsBeforeAndNullAfter() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        noticeRepo.nextPreviewResult = { req ->
            val dummyProposal = NoticeAdjustmentProposal(
                semanticKey = "dummy-semantic",
                confirmDigest = "dummy-digest",
                target = req.target,
                calendarFingerprint = "dummy-fp",
                basis = req.intent.basis,
                rawTextDigest = "raw-digest",
                kind = NoticeIntentKind.CANCEL,
                candidate = req.selection,
                courseId = 1L,
                courseTitle = "数据可视化",
                originalDate = LocalDate.of(2026, 11, 13),
                targetDate = LocalDate.of(2026, 11, 13),
                meetingId = 11L,
                before = NoticeOccurrenceView(
                    date = LocalDate.of(2026, 11, 13),
                    sections = setOf(3, 4),
                    ranges = emptyList(),
                    teacher = "张老师",
                    room = "A101",
                    project = null,
                    group = null,
                ),
                after = null,
                keptFields = emptySet(),
                impact = NoticeImpact(NoticeImpactScope.ONE_OCCURRENCE, listOf(LocalDate.of(2026, 11, 13))),
                replacesExistingAdjustment = false,
                replacedExceptionId = null,
                conflicts = emptyList(),
            )
            val conf = NoticeAdjustmentConfirmation(
                target = req.target,
                requestKey = "req-key",
                semanticKey = "dummy-semantic",
                confirmDigest = "dummy-digest",
                intent = req.intent,
                selection = req.selection,
                extraCourse = req.extraCourse,
                acknowledgedConflicts = req.acknowledgedConflicts,
                replaceExistingAdjustment = false,
                sourceText = req.intent.rawText,
                sourceDigest = "digest",
            )
            NoticeAdjustmentPreviewResult.Ready(NoticeAdjustmentPreview(req.target, dummyProposal, "req-key", conf, null))
        }

        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        viewModel.selectCandidate(viewModel.uiState.value.candidates.first().id)
        viewModel.preview()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.preview)
        assertNotNull(state.preview!!.proposal.before)
        assertNull("停课提案的 after 为 null", state.preview!!.proposal.after)

        viewModel.apply()
        testScheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.appliedSuccessMessage)
    }

    @Test
    fun referenceDateModificationClearsDerivedParseResultsAndCandidates() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)

        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.candidates.size)
        val candId = viewModel.uiState.value.candidates.first().id
        viewModel.selectCandidate(candId)
        assertEquals(candId, viewModel.uiState.value.selectedCandidateId)

        // 改变参考日
        viewModel.updateReferenceDateInput("2026-11-16")

        // 反例断言：修改参考日后，必须清空旧的 parseResults、candidates 和选中的 candidateId！
        assertTrue("修改日期基准后必须清空 parseResults", viewModel.uiState.value.parseResults.isEmpty())
        assertTrue("修改日期基准后必须清空 candidates", viewModel.uiState.value.candidates.isEmpty())
        assertNull("修改日期基准后必须清空 selectedCandidateId", viewModel.uiState.value.selectedCandidateId)
    }

    @Test
    fun cancelledPreviewResetsIsPreviewingAllowingSubsequentPreview() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val gate = CompletableDeferred<Unit>()

        noticeRepo.nextPreviewResult = { req ->
            gate.await()
            val dummyProposal = NoticeAdjustmentProposal(
                semanticKey = "dummy-semantic",
                confirmDigest = "dummy-digest",
                target = req.target,
                calendarFingerprint = "dummy-fp",
                basis = req.intent.basis,
                rawTextDigest = "raw-digest",
                kind = req.intent.kind,
                candidate = req.selection,
                courseId = 1L,
                courseTitle = "数据可视化",
                originalDate = LocalDate.of(2026, 11, 13),
                targetDate = LocalDate.of(2026, 11, 13),
                meetingId = 11L,
                before = null,
                after = null,
                keptFields = emptySet(),
                impact = NoticeImpact(NoticeImpactScope.ONE_OCCURRENCE, listOf(LocalDate.of(2026, 11, 13))),
                replacesExistingAdjustment = false,
                replacedExceptionId = null,
                conflicts = emptyList(),
            )
            val conf = NoticeAdjustmentConfirmation(
                target = req.target,
                requestKey = "req-key",
                semanticKey = "dummy-semantic",
                confirmDigest = "dummy-digest",
                intent = req.intent,
                selection = req.selection,
                extraCourse = req.extraCourse,
                acknowledgedConflicts = req.acknowledgedConflicts,
                replaceExistingAdjustment = false,
                sourceText = req.intent.rawText,
                sourceDigest = "digest",
            )
            NoticeAdjustmentPreviewResult.Ready(NoticeAdjustmentPreview(req.target, dummyProposal, "req-key", conf, null))
        }

        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        viewModel.selectCandidate(viewModel.uiState.value.candidates.first().id)

        // 触发第一个挂起的 preview
        viewModel.preview()
        testScheduler.runCurrent()
        assertTrue("第一个 preview 正在运行", viewModel.uiState.value.isPreviewing)

        // 修改原文触发取消
        viewModel.updateRawText(noticeText)
        testScheduler.runCurrent()

        // 反例断言：取消后 isPreviewing 必须复位为 false！
        assertFalse("取消旧任务后 isPreviewing 必须复位", viewModel.uiState.value.isPreviewing)
    }

    @Test
    fun modificationsDuringApplyingAreRejected() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val applyGate = CompletableDeferred<Unit>()

        noticeRepo.nextApplyResult = { conf ->
            applyGate.await()
            NoticeAdjustmentApplyResult.Applied(101L, 9L, "APPEND")
        }

        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        val candId = viewModel.uiState.value.candidates.first().id
        viewModel.selectCandidate(candId)
        viewModel.preview()
        testScheduler.advanceUntilIdle()

        // 触发挂起的 apply
        viewModel.apply()
        testScheduler.runCurrent()
        assertTrue("正在应用中", viewModel.uiState.value.isApplying)

        // 在应用中途尝试篡改状态
        viewModel.updateRawText("恶意篡改通知")
        viewModel.updateReferenceDateInput("2026-01-01")
        testScheduler.runCurrent()

        // 反例断言：应用中途的修改必须被状态机拒绝！
        assertEquals("应用中途修改原文必须被拒绝", noticeText, viewModel.uiState.value.rawText)
        assertEquals("应用中途修改参考日必须被拒绝", "2026-11-09", viewModel.uiState.value.referenceDateInput)

        // 放行 apply
        applyGate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertFalse("应用完成后复位", viewModel.uiState.value.isApplying)
    }

    @Test
    fun sectionsRangeExpansionAndInvalidTokensRejection() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        var capturedRequest: NoticeAdjustmentRequest? = null
        noticeRepo.nextPreviewResult = { req ->
            capturedRequest = req
            val dummyProposal = NoticeAdjustmentProposal(
                semanticKey = "dummy-semantic",
                confirmDigest = "dummy-digest",
                target = req.target,
                calendarFingerprint = "dummy-fp",
                basis = req.intent.basis,
                rawTextDigest = "raw-digest",
                kind = req.intent.kind,
                candidate = req.selection,
                courseId = 1L,
                courseTitle = "数据可视化",
                originalDate = LocalDate.of(2026, 11, 13),
                targetDate = LocalDate.of(2026, 11, 13),
                meetingId = 11L,
                before = null,
                after = null,
                keptFields = emptySet(),
                impact = NoticeImpact(NoticeImpactScope.ONE_OCCURRENCE, listOf(LocalDate.of(2026, 11, 13))),
                replacesExistingAdjustment = false,
                replacedExceptionId = null,
                conflicts = emptyList(),
            )
            val conf = NoticeAdjustmentConfirmation(
                target = req.target,
                requestKey = "req-key",
                semanticKey = "dummy-semantic",
                confirmDigest = "dummy-digest",
                intent = req.intent,
                selection = req.selection,
                extraCourse = req.extraCourse,
                acknowledgedConflicts = req.acknowledgedConflicts,
                replaceExistingAdjustment = false,
                sourceText = req.intent.rawText,
                sourceDigest = "digest",
            )
            NoticeAdjustmentPreviewResult.Ready(NoticeAdjustmentPreview(req.target, dummyProposal, "req-key", conf, null))
        }

        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        val candId = viewModel.uiState.value.candidates.first().id
        viewModel.selectCandidate(candId)

        // 1. 测试节次范围展开 5-8
        viewModel.updateCorrectionTargetSections("5-8")
        viewModel.preview()
        testScheduler.advanceUntilIdle()
        assertNotNull("合法节次 5-8 preview 成功", viewModel.uiState.value.preview)
        assertEquals(setOf(5, 6, 7, 8), capturedRequest?.intent?.target?.sections)
        assertNull(viewModel.uiState.value.correctionError)

        // 2. 测试坏值 token 阻断
        viewModel.updateCorrectionTargetSections("5, 坏值")
        viewModel.preview()
        testScheduler.advanceUntilIdle()
        assertNull("包含坏值时 preview 必须被阻断为 null", viewModel.uiState.value.preview)
        assertNotNull("必须设置纠正错误信息", viewModel.uiState.value.correctionError)
        assertTrue(viewModel.uiState.value.correctionError!!.contains("无效节次"))

        // 3. 测试超限节次
        viewModel.updateCorrectionTargetSections("35")
        viewModel.preview()
        testScheduler.advanceUntilIdle()
        assertNull(viewModel.uiState.value.preview)
        assertNotNull(viewModel.uiState.value.correctionError)
    }

    @Test
    fun invalidOrReversedTimeRangeRejection() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        viewModel.selectCandidate(viewModel.uiState.value.candidates.first().id)

        // 1. 倒序时间 15:00 - 14:00
        viewModel.updateCorrectionTargetTime("15:00", "14:00")
        viewModel.preview()
        testScheduler.advanceUntilIdle()
        assertNull("倒序时间 preview 必须被阻断", viewModel.uiState.value.preview)
        assertNotNull(viewModel.uiState.value.correctionError)
        assertTrue(viewModel.uiState.value.correctionError!!.contains("必须早于结束时间"))

        // 2. 非法时间 25:00
        viewModel.updateCorrectionTargetTime("25:00", "16:00")
        viewModel.preview()
        testScheduler.advanceUntilIdle()
        assertNull("非法小时 preview 必须被阻断", viewModel.uiState.value.preview)
        assertNotNull(viewModel.uiState.value.correctionError)
        assertTrue(viewModel.uiState.value.correctionError!!.contains("时间格式无效"))
    }

    @Test
    fun originalCorrectionSectionsAndTimeArePassedToRequest() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        var capturedRequest: NoticeAdjustmentRequest? = null
        noticeRepo.nextPreviewResult = { req ->
            capturedRequest = req
            val dummyProposal = NoticeAdjustmentProposal(
                semanticKey = "dummy-semantic",
                confirmDigest = "dummy-digest",
                target = req.target,
                calendarFingerprint = "dummy-fp",
                basis = req.intent.basis,
                rawTextDigest = "raw-digest",
                kind = req.intent.kind,
                candidate = req.selection,
                courseId = 1L,
                courseTitle = "数据可视化",
                originalDate = LocalDate.of(2026, 11, 13),
                targetDate = LocalDate.of(2026, 11, 13),
                meetingId = 11L,
                before = null,
                after = null,
                keptFields = emptySet(),
                impact = NoticeImpact(NoticeImpactScope.ONE_OCCURRENCE, listOf(LocalDate.of(2026, 11, 13))),
                replacesExistingAdjustment = false,
                replacedExceptionId = null,
                conflicts = emptyList(),
            )
            val conf = NoticeAdjustmentConfirmation(
                target = req.target,
                requestKey = "req-key",
                semanticKey = "dummy-semantic",
                confirmDigest = "dummy-digest",
                intent = req.intent,
                selection = req.selection,
                extraCourse = req.extraCourse,
                acknowledgedConflicts = req.acknowledgedConflicts,
                replaceExistingAdjustment = false,
                sourceText = req.intent.rawText,
                sourceDigest = "digest",
            )
            NoticeAdjustmentPreviewResult.Ready(NoticeAdjustmentPreview(req.target, dummyProposal, "req-key", conf, null))
        }

        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        viewModel.selectCandidate(viewModel.uiState.value.candidates.first().id)

        // 纠正原课时节次与起止时间
        viewModel.updateCorrectionOriginalSections("1-2")
        viewModel.updateCorrectionOriginalTime("08:00", "09:35")
        viewModel.preview()
        testScheduler.advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.preview)
        val passedIntent = capturedRequest?.intent
        assertNotNull("纠正后的原节次已传达", passedIntent?.original?.sections)
        assertEquals(setOf(1, 2), passedIntent?.original?.sections)
        assertNotNull("纠正后的原时间已传达", passedIntent?.original?.time)
        assertEquals(LocalTime.of(8, 0), passedIntent?.original?.time?.start)
        assertEquals(LocalTime.of(9, 35), passedIntent?.original?.time?.end)
    }

    @Test
    fun loadCandidatesExceptionSetsErrorMessageAndAllowsRetry() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        // 模拟异常的 importRepo
        val failingImportRepo = object : TestImportRepository(scheduleRepo, emptyList()) {
            override suspend fun readImportTarget(semesterId: Long): ImportTargetView {
                throw IllegalStateException("模拟底层导入目标存储已破坏")
            }
        }

        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, failingImportRepo)
        viewModel.updateRawText(noticeText)
        viewModel.updateReferenceDateInput("2026-11-09")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        // 验证异常被捕获到 generalErrorMessage，而不是让协程崩溃
        val state = viewModel.uiState.value
        assertNotNull("应捕获异常至 generalErrorMessage", state.generalErrorMessage)
        assertTrue("错误包含底层异常信息", state.generalErrorMessage!!.contains("模拟底层导入目标存储已破坏"))
        assertTrue("发生异常时候选列表为空", state.candidates.isEmpty())
    }

    @Test
    fun morningFirstBigSectionMappingIdentityPreservedAndResolved() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText("2026-11-20上午第一大节数据可视化补课")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        // 第一次解析应为 NeedTime (上午第一大节)
        val initialResult = viewModel.uiState.value.parseResults.singleOrNull()
        assertTrue("应解析为 NeedTime", initialResult is NoticeIntentResult.NeedTime)
        assertEquals(NoticeDayPart.MORNING, (initialResult as NoticeIntentResult.NeedTime).bigSection?.part)
        assertEquals(1, initialResult.bigSection?.ordinal)

        // 用户输入上午第一大节映射到 3-4 节
        viewModel.updateBigSectionMapping("3", "4")
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        // 验证身份未丢失，且成功解析出对应节次
        val readyResult = viewModel.uiState.value.parseResults.singleOrNull()
        assertTrue("上午第一大节映射后应成功解析为 Ready，但实际为 $readyResult", readyResult is NoticeIntentResult.Ready)
        val readyIntent = (readyResult as NoticeIntentResult.Ready).intent
        assertEquals(setOf(3, 4), readyIntent.target.sections)
    }

    @Test
    fun afternoonSecondBigSectionMappedTo1And2WithPriorInput() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText("2026-11-20下午第二大节数据可视化补课")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        val initialResult = viewModel.uiState.value.parseResults.singleOrNull()
        assertTrue(initialResult is NoticeIntentResult.NeedTime)
        assertEquals(NoticeDayPart.AFTERNOON, (initialResult as NoticeIntentResult.NeedTime).bigSection?.part)
        assertEquals(2, initialResult.bigSection?.ordinal)

        // 用户补充前置第 1 大节映射为 3-4，当前第 2 大节映射为 1-2
        viewModel.updateBigSectionMappingForOrdinal(1, "3", "4")
        viewModel.updateBigSectionMappingForOrdinal(2, "1", "2")
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        val readyResult = viewModel.uiState.value.parseResults.singleOrNull()
        assertTrue("下午第二大节提供完整映射后应成功解析为 Ready，但实际为 $readyResult", readyResult is NoticeIntentResult.Ready)
        val readyIntent = (readyResult as NoticeIntentResult.Ready).intent
        assertEquals("下午第二大节应映射为 1-2 节", setOf(1, 2), readyIntent.target.sections)
    }

    @Test
    fun thirdBigSectionWithoutPriorInformationIsBlockedWithPrompt() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText("2026-11-20下午第三大节数据可视化补课")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        // 用户只输入了第 3 大节映射，缺少第 1、2 大节
        viewModel.updateBigSectionMapping("5", "6")
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull("缺少前置映射必须被阻断并报错", state.bigSectionMappingError)
        assertTrue("错误信息引导补充前置映射", state.bigSectionMappingError!!.contains("大节映射信息不完整"))
        assertTrue("解析结果保持清空或阻断", state.parseResults.isEmpty())
        assertNull("零 preview", state.preview)
    }

    @Test
    fun invalidOverlappingAndOutOfBoundsBigSectionMappingsAreRejected() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText("2026-11-20下午第二大节数据可视化补课")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        // 1. 坏值
        viewModel.updateBigSectionMappingForOrdinal(1, "abc", "2")
        viewModel.updateBigSectionMappingForOrdinal(2, "3", "4")
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.bigSectionMappingError)
        assertTrue(viewModel.uiState.value.bigSectionMappingError!!.contains("纯数字"))

        // 2. 重叠节次（第1大节 1-2，第2大节 1-2）
        viewModel.updateBigSectionMappingForOrdinal(1, "1", "2")
        viewModel.updateBigSectionMappingForOrdinal(2, "1", "2")
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.bigSectionMappingError)
        assertTrue("重叠节次转为说明性错误，不抛未处理异常", viewModel.uiState.value.bigSectionMappingError!!.contains("重复节次"))

        // 3. 超大范围（直接在展开前拦截，不分配巨大集合）
        viewModel.updateBigSectionMappingForOrdinal(1, "1", "99999999")
        viewModel.updateBigSectionMappingForOrdinal(2, "3", "4")
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.bigSectionMappingError)
        assertTrue("超大范围在展开前被拦截", viewModel.uiState.value.bigSectionMappingError!!.contains("超出允许范围"))

        // 4. 超出节次域（如 35 节）
        viewModel.updateBigSectionMappingForOrdinal(1, "1", "2")
        viewModel.updateBigSectionMappingForOrdinal(2, "31", "35")
        viewModel.parse()
        testScheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.bigSectionMappingError)
        assertTrue(viewModel.uiState.value.bigSectionMappingError!!.contains("超出允许范围"))
    }

    @Test
    fun textChangeOrIntentSwitchClearsStaleBigSectionMapping() = runTest {
        val (noticeRepo, scheduleRepo, importRepo) = createFixtures()
        val viewModel = NoticeAdjustmentViewModel(noticeRepo, scheduleRepo, importRepo)
        viewModel.updateRawText("2026-11-20上午第一大节数据可视化补课")
        viewModel.useCurrentSemesterBasis(NoticeFixtures.termMonday, 16)
        viewModel.parse()
        testScheduler.advanceUntilIdle()

        viewModel.updateBigSectionMapping("3", "4")
        assertEquals("3", viewModel.uiState.value.bigSectionStartInput)
        assertEquals("4", viewModel.uiState.value.bigSectionEndInput)

        // 修改原文，旧映射输入必须清空失效
        viewModel.updateRawText("2026-11-20数据可视化停课")
        assertEquals("", viewModel.uiState.value.bigSectionStartInput)
        assertEquals("", viewModel.uiState.value.bigSectionEndInput)
        assertTrue(viewModel.uiState.value.bigSectionOrdinalInputs.isEmpty())
        assertNull(viewModel.uiState.value.bigSectionMappingError)
    }
}
