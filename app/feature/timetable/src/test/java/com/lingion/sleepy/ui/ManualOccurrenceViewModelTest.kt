/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.ui

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.ConflictReviewDecision
import com.lingion.sleepy.domain.imports.ImportApplyCommand
import com.lingion.sleepy.domain.imports.ImportCanonical
import com.lingion.sleepy.domain.imports.ImportLogicalBaseDisplay
import com.lingion.sleepy.domain.imports.ImportTarget
import com.lingion.sleepy.domain.imports.LogicalBaseReference
import com.lingion.sleepy.domain.imports.ReviewedImport
import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.notice.NoticeAdjustmentApplyResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentConfirmation
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPlanner
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreview
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreviewResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentProposal
import com.lingion.sleepy.domain.notice.NoticeAdjustmentRequest
import com.lingion.sleepy.domain.notice.NoticeAdjustmentUndoResult
import com.lingion.sleepy.domain.notice.NoticeCandidate
import com.lingion.sleepy.domain.notice.NoticeCandidateId
import com.lingion.sleepy.domain.notice.NoticeConflict
import com.lingion.sleepy.domain.notice.NoticeProposalResult
import com.lingion.sleepy.domain.repository.ImportApplyResult
import com.lingion.sleepy.domain.repository.ImportPreviewResult
import com.lingion.sleepy.domain.repository.ImportRepository
import com.lingion.sleepy.domain.repository.ImportTargetView
import com.lingion.sleepy.domain.repository.NoticeAdjustmentRepository
import com.lingion.sleepy.domain.repository.ScheduleRepository
import com.lingion.sleepy.importing.DraftTimeRange
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
class ManualOccurrenceViewModelTest {

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

    private class TestImportRepository(
        var target: ImportTarget,
        var bases: List<ImportLogicalBaseDisplay>,
        var snapshot: TimetableSnapshot,
        var shouldFail: Boolean = false,
    ) : ImportRepository {
        override suspend fun readImportTarget(semesterId: Long): ImportTargetView {
            if (shouldFail) throw IllegalStateException("Import target read failure")
            return ImportTargetView(target, snapshot, emptyList(), bases)
        }
        override suspend fun previewImport(reviewed: ReviewedImport, conflicts: List<ConflictReviewDecision>): ImportPreviewResult {
            throw UnsupportedOperationException()
        }
        override suspend fun applyImport(command: ImportApplyCommand): ImportApplyResult {
            throw UnsupportedOperationException()
        }
    }

    private class RealPlannerNoticeAdjustmentRepository(
        private val scheduleRepo: TestScheduleRepository,
        private val importRepo: TestImportRepository,
    ) : NoticeAdjustmentRepository {

        var interceptPreview: ((NoticeAdjustmentRequest) -> NoticeAdjustmentPreviewResult?)? = null
        var interceptApply: ((NoticeAdjustmentConfirmation) -> Unit)? = null
        var lastAppliedConfirmation: NoticeAdjustmentConfirmation? = null

        override suspend fun preview(request: NoticeAdjustmentRequest): NoticeAdjustmentPreviewResult {
            interceptPreview?.invoke(request)?.let { return it }

            val snap = scheduleRepo.flow.value
            val bases = importRepo.bases
            val target = request.target

            return when (val p = NoticeAdjustmentPlanner.propose(
                intent = request.intent,
                snapshot = snap,
                bases = bases,
                target = target,
                selection = request.selection,
                acknowledgedConflicts = request.acknowledgedConflicts,
                replaceExistingAdjustment = request.replaceExistingAdjustment,
                extraCourse = request.extraCourse,
            )) {
                is NoticeProposalResult.Ready -> {
                    val conf = NoticeAdjustmentConfirmation(
                        target = target,
                        requestKey = "req-1",
                        semanticKey = p.proposal.semanticKey,
                        confirmDigest = p.proposal.confirmDigest,
                        intent = request.intent,
                        selection = request.selection,
                        extraCourse = request.extraCourse,
                        acknowledgedConflicts = request.acknowledgedConflicts,
                        replaceExistingAdjustment = request.replaceExistingAdjustment,
                        sourceText = request.intent.rawText,
                        sourceDigest = "digest-1",
                    )
                    val preview = NoticeAdjustmentPreview(
                        target = target,
                        proposal = p.proposal,
                        requestKey = "req-1",
                        confirmation = conf,
                        alreadyAppliedEventId = null,
                    )
                    NoticeAdjustmentPreviewResult.Ready(preview)
                }
                is NoticeProposalResult.NotReady -> {
                    NoticeAdjustmentPreviewResult.ConflictPending(p.proposal, p.problems)
                }
                is NoticeProposalResult.Blocked -> {
                    NoticeAdjustmentPreviewResult.Blocked(p.problems, p.candidates)
                }
            }
        }

        override suspend fun apply(confirmation: NoticeAdjustmentConfirmation): NoticeAdjustmentApplyResult {
            interceptApply?.invoke(confirmation)
            lastAppliedConfirmation = confirmation
            return NoticeAdjustmentApplyResult.Applied(
                eventId = 2001L,
                revision = 2L,
                mode = "MANUAL_APPLY",
            )
        }

        override suspend fun undo(eventId: Long, expectedRevision: Long): NoticeAdjustmentUndoResult = throw UnsupportedOperationException()
        override suspend fun history(limit: Int, beforeEventId: Long?): NoticeAdjustmentHistoryResult = throw UnsupportedOperationException()
    }

    private fun createStandardFixture(
        meetings: List<CourseMeeting>,
        periods: List<Period> = listOf(
            Period(1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
            Period(2, LocalTime.of(8, 55), LocalTime.of(9, 40)),
            Period(3, LocalTime.of(10, 0), LocalTime.of(10, 45)),
            Period(4, LocalTime.of(10, 55), LocalTime.of(11, 40)),
            Period(5, LocalTime.of(14, 0), LocalTime.of(14, 45)),
            Period(6, LocalTime.of(14, 55), LocalTime.of(15, 40)),
        ),
        courseId: Long = 101L,
        courseName: String = "高等数学",
    ): Triple<TimetableDependencies, TestScheduleRepository, RealPlannerNoticeAdjustmentRepository> {
        val startDate = LocalDate.of(2026, 9, 7) // 周一
        val settings = SemesterSettings(id = 1L, name = "2026秋季", startDate = startDate, totalWeeks = 16)
        val course = ScheduledCourse(
            id = courseId,
            courseName = courseName,
            teacher = "张教授",
            room = "教1-101",
            color = 0xFF6750A4,
            meetings = meetings,
        )
        val snapshot = TimetableSnapshot(
            settings = settings,
            periods = periods,
            courses = listOf(course),
            revision = 1L,
            scopeToken = "scope-1",
        )
        val target = ImportTarget("scope-1", 1L, 1L, 5)
        val logicalBases = meetings.map { meeting ->
            val intervals = meeting.customTime?.let { listOf(it) }
                ?: periods.filter { it.number in meeting.sections }.map { TimeRange(it.start, it.end) }
            val original = meeting.copy(id = 0, exceptions = emptyList())
            ImportLogicalBaseDisplay(
                reference = LogicalBaseReference.StoredLogicalBase(
                    "scope-1", 1L, courseId, "base-${meeting.id}", ImportCanonical.digest(original)
                ),
                title = courseName,
                original = original,
                timeRanges = intervals,
                teacher = meeting.teacher ?: course.teacher,
                room = meeting.room ?: course.room,
                project = meeting.experimentProject,
                group = meeting.group,
            )
        }

        val scheduleRepo = TestScheduleRepository(snapshot)
        val importRepo = TestImportRepository(target, logicalBases, snapshot)
        val noticeRepo = RealPlannerNoticeAdjustmentRepository(scheduleRepo, importRepo)

        val deps = TimetableDependencies(
            repository = scheduleRepo,
            imports = importRepo,
            noticeAdjustments = noticeRepo,
        )
        return Triple(deps, scheduleRepo, noticeRepo)
    }

    @Test
    fun testMultiRangeCoursePreservesAllIntervalsAndBreaksOnLocationOrDateChange() = runTest {
        // 反例1验证：第3/4节为 10:00–10:45、10:55–11:40（中间有10分钟课间）。
        // 仅修改地点或跨日，提案 after.intervals 必须严格保留两段独立的作息，绝不能合并为 10:00-11:40 连续区间！
        val originalMeeting = CourseMeeting(
            id = 1,
            day = 1, // 周一
            weeks = setOf(1, 2, 3),
            sections = setOf(3, 4),
            kind = MeetingKind.RECURRING,
        )
        val (deps, _, noticeRepo) = createStandardFixture(listOf(originalMeeting))

        val vm = ManualOccurrenceViewModel(deps, 101L, ManualOccurrenceMode.ADJUST)
        advanceUntilIdle()

        // 验证初始化未静默默认选中日期
        assertNull(vm.uiState.value.originalDate)
        assertNull(vm.uiState.value.selectedCandidateId)

        // 用户明确选择原日期：第1周周一（2026-09-07）
        val dateWeek1Monday = LocalDate.of(2026, 9, 7)
        vm.setOriginalDate(dateWeek1Monday)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.candidates.size)
        val cand = vm.uiState.value.candidates.single()
        vm.selectCandidate(cand.id)
        advanceUntilIdle()

        // 验证 selectCandidate 绝不把首尾包络填入自定义时钟输入框！
        assertFalse(vm.uiState.value.useCustomTime)
        assertEquals("", vm.uiState.value.customTimeStartInput)
        assertEquals("", vm.uiState.value.customTimeEndInput)

        // 操作：仅修改教室（从 教1-101 改为 实验楼-302）
        vm.setTargetRoomInput("实验楼-302")
        vm.preview()
        advanceUntilIdle()

        val preview = vm.uiState.value.preview
        assertNotNull("预览应成功生成", preview)
        val after = preview!!.proposal.after
        assertNotNull(after)
        assertEquals("实验楼-302", after!!.room)
        assertEquals(setOf(3, 4), after.sections)

        // 核心反例断言：两个区间严格独立，课间休息完全保留！
        assertEquals(2, after.ranges.size)
        assertEquals(LocalTime.of(10, 0), after.ranges[0].start)
        assertEquals(LocalTime.of(10, 45), after.ranges[0].end)
        assertEquals(LocalTime.of(10, 55), after.ranges[1].start)
        assertEquals(LocalTime.of(11, 40), after.ranges[1].end)
    }

    @Test
    fun testChangeSectionsUsesNewScheduleAndClearsAutomaticClock() = runTest {
        // 反例1衍生：将第3/4节改为第5/6节，目标安排必须使用第5/6节对应新作息（14:00-14:45, 14:55-15:40），不能按旧10:00执行！
        val originalMeeting = CourseMeeting(
            id = 1,
            day = 1,
            weeks = setOf(1, 2),
            sections = setOf(3, 4),
            kind = MeetingKind.RECURRING,
        )
        val (deps, _, _) = createStandardFixture(listOf(originalMeeting))

        val vm = ManualOccurrenceViewModel(deps, 101L, ManualOccurrenceMode.ADJUST)
        advanceUntilIdle()

        vm.setOriginalDate(LocalDate.of(2026, 9, 7))
        advanceUntilIdle()
        vm.selectCandidate(vm.uiState.value.candidates.single().id)
        advanceUntilIdle()

        // 用户明确修改节次为 5,6 节
        vm.setTargetSectionsInput("5,6")
        vm.preview()
        advanceUntilIdle()

        val preview = vm.uiState.value.preview
        assertNotNull(preview)
        val after = preview!!.proposal.after
        assertNotNull(after)
        assertEquals(setOf(5, 6), after!!.sections)

        // 验证采用新作息，完全不沿用旧时间！
        assertEquals(2, after.ranges.size)
        assertEquals(LocalTime.of(14, 0), after.ranges[0].start)
        assertEquals(LocalTime.of(14, 45), after.ranges[0].end)
        assertEquals(LocalTime.of(14, 55), after.ranges[1].start)
        assertEquals(LocalTime.of(15, 40), after.ranges[1].end)
    }

    @Test
    fun testNonContiguousSectionsKept() = runTest {
        // 反例1衍生：非连续节次 {1, 3}，调整时节次严格保持 {1, 3}，区间为两段独立的第1节和第3节作息
        val originalMeeting = CourseMeeting(
            id = 1,
            day = 2, // 周二
            weeks = setOf(1),
            sections = setOf(1, 3),
            kind = MeetingKind.RECURRING,
        )
        val (deps, _, _) = createStandardFixture(listOf(originalMeeting))

        val vm = ManualOccurrenceViewModel(deps, 101L, ManualOccurrenceMode.ADJUST)
        advanceUntilIdle()

        val date = LocalDate.of(2026, 9, 8)
        vm.setOriginalDate(date)
        advanceUntilIdle()
        vm.selectCandidate(vm.uiState.value.candidates.single().id)
        advanceUntilIdle()

        vm.setTargetRoomInput("新教室A")
        vm.preview()
        advanceUntilIdle()

        val after = vm.uiState.value.preview!!.proposal.after!!
        assertEquals(setOf(1, 3), after.sections)
        assertEquals(2, after.ranges.size)
        assertEquals(LocalTime.of(8, 0), after.ranges[0].start)
        assertEquals(LocalTime.of(8, 45), after.ranges[0].end)
        assertEquals(LocalTime.of(10, 0), after.ranges[1].start)
        assertEquals(LocalTime.of(10, 45), after.ranges[1].end)
    }

    @Test
    fun testCustomTimeOnlyCoursePreviewAndApply() = runTest {
        // 反例1末尾：无节次课程（sections为空，只有customTime），启用自定义时钟，可成功预览和应用
        val originalMeeting = CourseMeeting(
            id = 1,
            day = 0,
            weeks = emptySet(),
            sections = emptySet(),
            customTime = TimeRange(LocalTime.of(19, 0), LocalTime.of(21, 0)),
            kind = MeetingKind.DATE_SPECIFIC,
            dates = setOf(LocalDate.of(2026, 9, 10)),
        )
        val (deps, _, noticeRepo) = createStandardFixture(listOf(originalMeeting))

        val vm = ManualOccurrenceViewModel(deps, 101L, ManualOccurrenceMode.ADJUST)
        advanceUntilIdle()

        vm.setOriginalDate(LocalDate.of(2026, 9, 10))
        advanceUntilIdle()

        val cand = vm.uiState.value.candidates.single()
        vm.selectCandidate(cand.id)
        advanceUntilIdle()

        // 验证无节次课程自动启用 useCustomTime 并展示原时间
        assertTrue(vm.uiState.value.useCustomTime)
        assertEquals("19:00", vm.uiState.value.customTimeStartInput)
        assertEquals("21:00", vm.uiState.value.customTimeEndInput)

        // 修改时间为 19:30 - 21:30
        vm.setCustomTimeStartInput("19:30")
        vm.setCustomTimeEndInput("21:30")
        vm.preview()
        advanceUntilIdle()

        val after = vm.uiState.value.preview!!.proposal.after!!
        assertTrue(after.sections.isEmpty())
        assertEquals(1, after.ranges.size)
        assertEquals(LocalTime.of(19, 30), after.ranges[0].start)
        assertEquals(LocalTime.of(21, 30), after.ranges[0].end)

        // 应用提交
        vm.apply()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.applySuccessMessage)
        assertNotNull(noticeRepo.lastAppliedConfirmation)
    }

    @Test
    fun testViewModelCourseIdentityAndSessionIsolation() = runTest {
        // 反例2：在同一个 ViewModelStore 下，不同 courseId 的 VM 彻底隔离，同课不同入口模式分立
        val meeting1 = CourseMeeting(id = 1, day = 1, weeks = setOf(1), sections = setOf(1), kind = MeetingKind.RECURRING)
        val meeting2 = CourseMeeting(id = 2, day = 2, weeks = setOf(1), sections = setOf(2), kind = MeetingKind.RECURRING)
        val (deps, schedRepo, _) = createStandardFixture(listOf(meeting1), courseId = 101L, courseName = "课程A")

        // 扩充快照增加课程B
        val courseB = ScheduledCourse(id = 202L, courseName = "课程B", teacher = "李老师", room = "教2-202", color = 0xFF6750A4, meetings = listOf(meeting2))
        val currentSnap = schedRepo.flow.value
        schedRepo.flow.value = currentSnap.copy(courses = currentSnap.courses + courseB)

        val store = ViewModelStore()

        // 打开 101 ADJUST
        val factoryA = ManualOccurrenceViewModel.Factory(deps, 101L, ManualOccurrenceMode.ADJUST)
        val providerA = ViewModelProvider(store, factoryA)
        val vmA = providerA["manual_occurrence_101_ADJUST", ManualOccurrenceViewModel::class.java]
        advanceUntilIdle()
        assertEquals(101L, vmA.uiState.value.courseId)
        assertEquals("课程A", vmA.uiState.value.courseName)
        assertEquals(ManualOccurrenceMode.ADJUST, vmA.uiState.value.mode)

        // 打开 202 CANCEL
        val factoryB = ManualOccurrenceViewModel.Factory(deps, 202L, ManualOccurrenceMode.CANCEL)
        val providerB = ViewModelProvider(store, factoryB)
        val vmB = providerB["manual_occurrence_202_CANCEL", ManualOccurrenceViewModel::class.java]
        advanceUntilIdle()
        assertEquals(202L, vmB.uiState.value.courseId)
        assertEquals("课程B", vmB.uiState.value.courseName)
        assertEquals(ManualOccurrenceMode.CANCEL, vmB.uiState.value.mode)

        // 同门课 101 从 EXTRA 打开
        val factoryAExtra = ManualOccurrenceViewModel.Factory(deps, 101L, ManualOccurrenceMode.EXTRA)
        val providerAExtra = ViewModelProvider(store, factoryAExtra)
        val vmAExtra = providerAExtra["manual_occurrence_101_EXTRA", ManualOccurrenceViewModel::class.java]
        advanceUntilIdle()
        assertEquals(101L, vmAExtra.uiState.value.courseId)
        assertEquals(ManualOccurrenceMode.EXTRA, vmAExtra.uiState.value.mode)

        store.clear()
    }

    @Test
    fun testStalePreviewResultDiscardedOnInputChanged() = runTest {
        // 反例4：挂起 repo.preview(A)，修改输入后放行 A，旧 confirmation 绝不回填到新页面
        val meeting = CourseMeeting(id = 1, day = 1, weeks = setOf(1), sections = setOf(1, 2), kind = MeetingKind.RECURRING)
        val (deps, _, noticeRepo) = createStandardFixture(listOf(meeting))

        val deferredA = CompletableDeferred<Unit>()
        var previewCallCount = 0

        noticeRepo.interceptPreview = { req ->
            previewCallCount++
            if (previewCallCount == 1) {
                // 挂起第一个预览
                kotlinx.coroutines.runBlocking { deferredA.await() }
            }
            null // 继续由真实 planner 处理
        }

        val vm = ManualOccurrenceViewModel(deps, 101L, ManualOccurrenceMode.ADJUST)
        advanceUntilIdle()

        vm.setOriginalDate(LocalDate.of(2026, 9, 7))
        advanceUntilIdle()
        vm.selectCandidate(vm.uiState.value.candidates.single().id)
        advanceUntilIdle()

        // 发起第一次预览 A（挂起中）
        vm.preview()

        // 用户在等待期间修改了输入（切换为停课模式）
        vm.setMode(ManualOccurrenceMode.CANCEL)

        // 放行第一次预览 A
        deferredA.complete(Unit)
        advanceUntilIdle()

        // 验证：旧预览 A 的 confirmation 决不能回填！当前 preview 必须仍为 null
        assertNull("旧预览结果必须被代次门闩丢弃", vm.uiState.value.preview)
    }

    @Test
    fun testInputModificationRejectedDuringApplying() = runTest {
        // 反例4：提交期间输入门禁，isApplying 为 true 时拒绝修改输入与语义
        val meeting = CourseMeeting(id = 1, day = 1, weeks = setOf(1), sections = setOf(1, 2), kind = MeetingKind.RECURRING)
        val (deps, _, noticeRepo) = createStandardFixture(listOf(meeting))

        val deferredApply = CompletableDeferred<Unit>()
        val vm = ManualOccurrenceViewModel(deps, 101L, ManualOccurrenceMode.CANCEL)
        advanceUntilIdle()

        vm.setOriginalDate(LocalDate.of(2026, 9, 7))
        advanceUntilIdle()
        vm.selectCandidate(vm.uiState.value.candidates.single().id)
        advanceUntilIdle()

        vm.preview()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.preview)

        // 拦截 apply 使其处于提交挂起中
        val applyDeferred = CompletableDeferred<Unit>()
        noticeRepo.interceptApply = {
            kotlinx.coroutines.runBlocking { applyDeferred.await() }
        }

        // 验证在 apply 启动后修改被忽略
        vm.apply()
        assertTrue(vm.uiState.value.isApplying)

        // 尝试在提交期间篡改输入
        vm.setMode(ManualOccurrenceMode.EXTRA)
        vm.setOriginalDate(LocalDate.of(2026, 9, 14))

        // 验证输入未被篡改
        assertEquals(ManualOccurrenceMode.CANCEL, vm.uiState.value.mode)
        assertEquals(LocalDate.of(2026, 9, 7), vm.uiState.value.originalDate)

        // 放行提交
        applyDeferred.complete(Unit)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isApplying)
        assertNotNull(vm.uiState.value.applySuccessMessage)
    }

    @Test
    fun testDateSpecificCourseDatesEnumeration() = runTest {
        // 反例3：calculateCourseDates 正确遍历 DATE_SPECIFIC 的 dates
        val date1 = LocalDate.of(2026, 10, 15)
        val date2 = LocalDate.of(2026, 11, 20)
        val dateSpecificMeeting = CourseMeeting(
            id = 1,
            day = 0,
            weeks = emptySet(),
            sections = setOf(1, 2),
            kind = MeetingKind.DATE_SPECIFIC,
            dates = setOf(date1, date2),
        )
        val (deps, _, _) = createStandardFixture(listOf(dateSpecificMeeting))

        val vm = ManualOccurrenceViewModel(deps, 101L, ManualOccurrenceMode.ADJUST)
        advanceUntilIdle()

        assertEquals(listOf(date1, date2), vm.uiState.value.courseOccurrenceDates)
    }

    @Test
    fun testMetadataFailureDoesNotFabricateLogicalBases() = runTest {
        // 反例3：readImportTarget 失败时进入错误状态，坚决不手造 fallbackBases
        val meeting = CourseMeeting(id = 1, day = 1, weeks = setOf(1), sections = setOf(1), kind = MeetingKind.RECURRING)
        val (deps, _, _) = createStandardFixture(listOf(meeting))
        (deps.imports as TestImportRepository).shouldFail = true

        val vm = ManualOccurrenceViewModel(deps, 101L, ManualOccurrenceMode.ADJUST)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.metadataError)
        assertTrue(vm.uiState.value.metadataError!!.contains("无法读取导入目标"))

        // 恢复后可重试
        (deps.imports as TestImportRepository).shouldFail = false
        vm.retryLoadMetadata()
        advanceUntilIdle()
        assertNull(vm.uiState.value.metadataError)
        assertEquals("高等数学", vm.uiState.value.courseName)
    }
}
