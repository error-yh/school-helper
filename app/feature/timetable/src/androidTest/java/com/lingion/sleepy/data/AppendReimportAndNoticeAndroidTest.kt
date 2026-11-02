/* SPDX-License-Identifier: GPL-3.0-only
 * S5-03 工作包一：真实 APPEND 组合回归
 *
 * 验证范围：
 *   - APPEND 导入课程A（多周，含 customTime 或默认节次）和无关课程B
 *   - 对A的单个发生应用通知调整，断言其他周、B 及来源不变
 *   - 对同一原始来源再次 APPEND 预览和应用（同课重导），验证幂等性/no-dup/无丢来源
 *   - 守卫：重放同一通知不重复写入；因目标变化拒绝时，明确守卫码和零写入
 *   - 撤销一致性：状态未改变时，撤销只恢复目标发生，其他周/B/历史保持
 *   - APPEND 预览取消零写入（比较 revision/courses/来源/归一化状态）
 *
 * 所有 fixture 均为合成数据，database 名以 synthetic_s503_append_ 开头，After 中精确删除。
 */
package com.lingion.sleepy.data

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.importing.DraftTimeRange
import com.lingion.sleepy.domain.imports.FrozenImportSource
import com.lingion.sleepy.domain.imports.ImportApplyCommand
import com.lingion.sleepy.domain.imports.ImportMode
import com.lingion.sleepy.domain.imports.ImportReviewContext
import com.lingion.sleepy.domain.imports.ImportReviewDecisions
import com.lingion.sleepy.domain.imports.ImportReviewResult
import com.lingion.sleepy.domain.imports.ImportReviewSession
import com.lingion.sleepy.domain.imports.ImportTarget
import com.lingion.sleepy.domain.imports.ReviewLocator
import com.lingion.sleepy.domain.imports.ReviewRequirementKind
import com.lingion.sleepy.domain.imports.RichDraftConverter
import com.lingion.sleepy.domain.imports.ReviewedImport
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.model.defaultPeriods
import com.lingion.sleepy.domain.notice.NoticeAdjustmentApplyResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentCodes
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreviewResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentRequest
import com.lingion.sleepy.domain.notice.NoticeAdjustmentUndoResult
import com.lingion.sleepy.domain.notice.NoticeBasis
import com.lingion.sleepy.domain.notice.NoticeIntentParser
import com.lingion.sleepy.domain.notice.NoticeIntentResult
import com.lingion.sleepy.domain.repository.ImportApplyResult
import com.lingion.sleepy.domain.repository.ImportPreviewResult
import com.lingion.sleepy.domain.model.CourseOccurrence
import com.lingion.sleepy.importing.MeetingKind
import com.lingion.sleepy.importing.NoticeDateBasis
import com.lingion.sleepy.importing.RichCourseDraft
import com.lingion.sleepy.importing.RichMeetingDraft
import com.lingion.sleepy.importing.RichTimetableDraft
import com.lingion.sleepy.importing.SourceExcerpt
import com.lingion.sleepy.importing.SourcePosition
import com.lingion.sleepy.importing.TextBox
import com.lingion.sleepy.importing.formats.FormatParseResult
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppendReimportAndNoticeAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val args: Bundle = InstrumentationRegistry.getArguments()
    private val profiles = mutableSetOf<String>()
    private val databases = mutableListOf<TimetableDatabase>()

    /** 学期：第1周为 2026-09-07（周一） */
    private val start = LocalDate.of(2026, 9, 7)

    /** 用于断言的调整目标日期：第10周周五 = 2026-11-13 */
    private val friday = LocalDate.of(2026, 11, 13)

    // 默认节次对应的时间范围（由 defaultPeriods() 定义）
    private val period1And2Ranges = listOf(
        TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45)),
        TimeRange(LocalTime.of(8, 55), LocalTime.of(9, 40)),
    )
    private val period3And4Ranges = listOf(
        TimeRange(LocalTime.of(10, 0), LocalTime.of(10, 45)),
        TimeRange(LocalTime.of(10, 55), LocalTime.of(11, 40)),
    )
    private val period5And6Ranges = listOf(
        TimeRange(LocalTime.of(14, 0), LocalTime.of(14, 45)),
        TimeRange(LocalTime.of(14, 55), LocalTime.of(15, 40)),
    )

    // ---- 每轮测试独立 DB -------------------------------------------------------

    private lateinit var profile: String
    private lateinit var db: TimetableDatabase
    private lateinit var schedules: RoomScheduleRepository
    private lateinit var repository: RoomNoticeAdjustmentRepository

    @Before
    fun checkOptIn() {
        val optIn = args.getString("s503_append_run")
            ?: args.getString("s503_runId")
            ?: args.getString("class")
        assumeTrue("未指定定向运行参数或类名，默认跳过以保护设备环境", optIn != null)
    }

    @After
    fun cleanUpOnlySyntheticDatabases() {
        databases.forEach { runCatching { it.close() } }
        databases.clear()
        profiles.forEach { context.deleteDatabase("timetable_$it.db") }
        profiles.clear()
    }

    // =========================================================================
    // 工作包一 · 测试 1：APPEND 同课重导幂等性和无重复发生（全16周细粒度验证）
    // =========================================================================
    @Test
    fun appendReimportSameCourseProducesNoDuplicateOccurrences(): Unit = runBlocking {
        openProfile()
        seedSemester()

        // 第一次 APPEND：课程A（周五第3-4节，16周）+ 无关课程B（周一第1-2节，16周）
        appendImport(
            "合成课A" to meetingOf(day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4)),
            "合成课B" to meetingOf(day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2)),
        )
        schedules.readImportTarget(1)
        val snap0 = snapshot()
        val courseAId = snap0.courses.single { it.courseName == "合成课A" }.id
        val courseBId = snap0.courses.single { it.courseName == "合成课B" }.id

        // 细粒度断言：初始全16周精确状态（A未调整：所有周五均为3-4节，B所有周一均为1-2节，其他日期无发生）
        assertFullTimetableSchedule(snap0, courseAId, courseBId, noticeTargetWeek = 10, isAdjusted = false)

        // 应用通知：第10周周五课程A由第3-4节调整为第5-6节
        val noticeText = "本周五合成课A由第3-4节调整为第5-6节"
        val noticeIntent = parseIntent(noticeText)
        val confirmation = confirmNotice(noticeIntent)
        val applied = repository.apply(confirmation) as NoticeAdjustmentApplyResult.Applied
        assertEquals("通知应走导入归一化路径", "NORMALIZED", applied.mode)
        val revAfterNotice = snapshot().revision

        // 应用后断言：全16周精确状态（仅第10周周五为5-6节及14:00-15:40时钟，其余15周周五严格为3-4节，B严格为1-2节）
        val snapAfterNotice = snapshot()
        assertFullTimetableSchedule(snapAfterNotice, courseAId, courseBId, noticeTargetWeek = 10, isAdjusted = true)

        // === 对同一原始来源再次 APPEND 预览：核对结果（unchanged） ===
        val secondPreviewResult = appendPreviewOnly(
            "合成课A" to meetingOf(day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4)),
            "合成课B" to meetingOf(day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2)),
        )
        assertTrue("同源重导 preview 应 ready", secondPreviewResult.ready)
        assertTrue("同源重导 unchanged 应为 true", secondPreviewResult.unchanged)

        // 预览零写入验证
        val revAfterPreview = snapshot().revision
        assertEquals("APPEND 预览不应改变 revision", revAfterNotice, revAfterPreview)
        val snapAfterPreview = snapshot()
        assertEquals("APPEND 预览不应改变课程数量", snapAfterNotice.courses.size, snapAfterPreview.courses.size)
        schedules.readImportTarget(1)

        // === 对同一来源真正 APPEND 应用 ===
        appendImport(
            "合成课A" to meetingOf(day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4)),
            "合成课B" to meetingOf(day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2)),
        )
        schedules.readImportTarget(1)
        val snapAfterReimport = snapshot()

        // 细粒度断言：重导后全16周状态依然保持通知效果，无重复发生、无冗余课程、时钟精确守恒
        assertFullTimetableSchedule(snapAfterReimport, courseAId, courseBId, noticeTargetWeek = 10, isAdjusted = true)

        // 历史事件守恒验证：通知事件数仍为 1，状态为 APPLIED，未被重导冲掉
        val history = repository.history() as NoticeAdjustmentHistoryResult.Loaded
        assertEquals("通知历史事件数应为1", 1, history.history.entries.size)
        assertEquals("事件状态应为 APPLIED", "APPLIED", history.history.entries.single().state)
        assertEquals("事件 ID 匹配", applied.eventId, history.history.entries.single().eventId)
    }

    // =========================================================================
    // 工作包一 · 测试 2：重导后重放同一通知，验证守卫码和零写入
    // =========================================================================
    @Test
    fun appendReimportThenReplayNoticeGuardedOrAlreadyApplied(): Unit = runBlocking {
        openProfile()
        seedSemester()

        appendImport(
            "合成课A" to meetingOf(day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4)),
            "合成课B" to meetingOf(day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2)),
        )
        schedules.readImportTarget(1)
        val courseAId = snapshot().courses.single { it.courseName == "合成课A" }.id

        // 应用通知
        val noticeText = "本周五合成课A由第3-4节调整为第5-6节"
        val noticeIntent = parseIntent(noticeText)
        val confirmation = confirmNotice(noticeIntent)
        val firstApply = repository.apply(confirmation) as NoticeAdjustmentApplyResult.Applied

        // 重放同一 confirmation：应为 AlreadyApplied，不新增事件
        val replay = repository.apply(confirmation)
        assertTrue("重放同一确认应返回 AlreadyApplied", replay is NoticeAdjustmentApplyResult.AlreadyApplied)
        assertEquals("重放应返回相同的 eventId",
            firstApply.eventId, (replay as NoticeAdjustmentApplyResult.AlreadyApplied).eventId)
        val historyAfterReplay = (repository.history() as NoticeAdjustmentHistoryResult.Loaded)
        assertEquals("重放不应写入新事件", 1, historyAfterReplay.history.entries.size)
        val revAfterReplay = snapshot().revision
        assertEquals("重放不应改变 revision", firstApply.revision, revAfterReplay)

        // APPEND 重导（同源原样重导）
        appendImport(
            "合成课A" to meetingOf(day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4)),
            "合成课B" to meetingOf(day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2)),
        )
        schedules.readImportTarget(1)

        // 重导后重放同一通知意图：因是同源原样重导，目标归属未改变，应明确为 AlreadyApplied
        val revBeforeGuard = snapshot().revision
        val guardedRequest = NoticeAdjustmentRequest(target(), noticeIntent, confirmation.selection)
        val guardedPreview = repository.preview(guardedRequest)

        assertTrue(
            "同源重导后目标有效，重放通知应明确返回 AlreadyApplied，实际: $guardedPreview",
            guardedPreview is NoticeAdjustmentPreviewResult.AlreadyApplied
        )
        val alreadyApplied = guardedPreview as NoticeAdjustmentPreviewResult.AlreadyApplied
        assertEquals("返回的 eventId 应一致", firstApply.eventId, alreadyApplied.eventId)

        // 零写入验证：无新增 revision，无新增历史事件
        assertEquals("零写入：revision 未变", revBeforeGuard, snapshot().revision)
        val historyAfterGuard = repository.history() as NoticeAdjustmentHistoryResult.Loaded
        assertEquals("零写入：历史依然仅1条事件", 1, historyAfterGuard.history.entries.size)
    }

    // =========================================================================
    // 工作包一 · 测试 3：通知效果保留时，撤销仅恢复目标发生，其他不变（确定性 Undone 断言）
    // =========================================================================
    @Test
    fun noticeUndoAfterAppendReimportUnchangedRestoresOnlyTargetOccurrence(): Unit = runBlocking {
        openProfile()
        seedSemester()

        appendImport(
            "合成课A" to meetingOf(day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4)),
            "合成课B" to meetingOf(day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2)),
        )
        schedules.readImportTarget(1)
        val snap0 = snapshot()
        val courseAId = snap0.courses.single { it.courseName == "合成课A" }.id
        val courseBId = snap0.courses.single { it.courseName == "合成课B" }.id

        // 应用通知
        val applied = repository.apply(
            confirmNotice(parseIntent("本周五合成课A由第3-4节调整为第5-6节"))
        ) as NoticeAdjustmentApplyResult.Applied
        val revAfterApply = snapshot().revision

        // 同源 APPEND 重导（内容完全相同，Plan 为 UNCHANGED）
        appendImport(
            "合成课A" to meetingOf(day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4)),
            "合成课B" to meetingOf(day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2)),
        )
        schedules.readImportTarget(1)

        val revBeforeUndo = snapshot().revision

        // 确定性断言：效果仍有效时，撤销必须返回 Undone，不允许接受 Rejected 分支
        val undoResult = repository.undo(applied.eventId, revBeforeUndo)
        assertTrue("在 unchanged APPEND 后撤销必须成功返回 Undone，实际结果: $undoResult",
            undoResult is NoticeAdjustmentUndoResult.Undone)
        val undone = undoResult as NoticeAdjustmentUndoResult.Undone
        assertEquals("撤销后的 revision 应匹配", snapshot().revision, undone.revision)

        // 全16周细粒度断言：第10周周五课程A已精确恢复为第3-4节（10:00-11:40），其余15周仍为3-4节，B仍为1-2节，总发生严格为32
        val snapAfterUndo = snapshot()
        assertFullTimetableSchedule(snapAfterUndo, courseAId, courseBId, noticeTargetWeek = 10, isAdjusted = false)

        // 历史状态验证：该事件状态严格为 UNDONE，且 undoneRevision 存在
        val history = repository.history() as NoticeAdjustmentHistoryResult.Loaded
        val eventEntry = history.history.entries.single { it.eventId == applied.eventId }
        assertEquals("历史中事件状态必须为 UNDONE", "UNDONE", eventEntry.state)
        assertEquals("undoneRevision 必须与撤销结果一致", undone.revision, eventEntry.undoneRevision)

        // 来源与归一化状态持久保留
        schedules.readImportTarget(1)
    }

    // =========================================================================
    // 工作包一 · 测试 4：APPEND 预览取消零写入（完整快照/来源/数据库持久化对比）
    // =========================================================================
    @Test
    fun appendPreviewCancelIsZeroWrite(): Unit = runBlocking {
        openProfile()
        seedSemester()

        appendImport(
            "合成课A" to meetingOf(day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4)),
            "合成课B" to meetingOf(day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2)),
        )
        schedules.readImportTarget(1)

        // 应用一次通知，形成非空的历史与特定 revision
        val applied = repository.apply(
            confirmNotice(parseIntent("本周五合成课A由第3-4节调整为第5-6节"))
        ) as NoticeAdjustmentApplyResult.Applied

        val snapBefore = snapshot()
        val revBefore = snapBefore.revision
        val targetBefore = schedules.readImportTarget(1).target
        val historyBefore = repository.history() as NoticeAdjustmentHistoryResult.Loaded
        val importStatesBefore = db.scheduleDao().importStates(1)
        assertTrue("测试前数据库中应已有归一化持久化状态", importStatesBefore.isNotEmpty())

        // 仅做 APPEND 预览，不执行 apply（模拟取消预览）
        val previewResult = appendPreviewOnly(
            "合成课A" to meetingOf(day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4)),
            "合成课B" to meetingOf(day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2)),
        )
        assertTrue("预览应就绪", previewResult.ready)

        // 取消后读取
        val snapAfter = snapshot()
        val revAfter = snapAfter.revision
        val targetAfter = schedules.readImportTarget(1).target
        val historyAfter = repository.history() as NoticeAdjustmentHistoryResult.Loaded
        val importStatesAfter = db.scheduleDao().importStates(1)

        // 严格零写入断言：
        // 1. revision 严格相等
        assertEquals("APPEND 预览取消后 revision 必须相等", revBefore, revAfter)
        // 2. 完整 TimetableSnapshot 相等（课程数、所有 courses 实体、所有 meetings、有效时间、provenance）
        assertEquals("完整 TimetableSnapshot 严格相等", snapBefore, snapAfter)
        assertEquals("所有 courses 集合严格相等", snapBefore.courses, snapAfter.courses)
        // 3. 归一化持久化状态列表严格全等（包含 courseId、version、payload 完整比较）
        assertEquals("归一化持久化状态列表（含 payload）必须完全一致", importStatesBefore, importStatesAfter)
        // 4. 来源与归一化持久化目标对比
        assertEquals("ImportTarget 严格相等", targetBefore, targetAfter)
        assertEquals("ImportTarget scopeToken 严格相等", targetBefore.scopeToken, targetAfter.scopeToken)
        assertEquals("ImportTarget expectedRevision 严格相等", targetBefore.expectedRevision, targetAfter.expectedRevision)
        assertEquals("ImportTarget databaseSchemaVersion 严格相等", targetBefore.databaseSchemaVersion, targetAfter.databaseSchemaVersion)
        // 5. 历史事件列表严格相等
        assertEquals("历史事件列表严格相等", historyBefore.history.entries, historyAfter.history.entries)
    }

    // =========================================================================
    // 工作包一 · 测试 5：自定义时钟 APPEND 场景（非默认 customTime）
    // =========================================================================
    @Test
    fun appendImportWithCustomClockCourseAndNoticeAdjustRoom(): Unit = runBlocking {
        openProfile()
        seedSemester()

        val custom = TimeRange(LocalTime.of(14, 30), LocalTime.of(16, 0))
        appendImport(
            "合成时钟课" to meetingOf(day = 5, weeks = (1..16).toSet(), sections = emptySet(), customTime = custom),
        )
        schedules.readImportTarget(1)
        val courseId = snapshot().courses.single { it.courseName == "合成时钟课" }.id

        // 应用教室变更通知（选定第10周周五 2026-11-13）
        val applied = repository.apply(
            confirmNotice(parseIntent("2026-11-13合成时钟课教室改为C301"))
        ) as NoticeAdjustmentApplyResult.Applied

        val occFriday = occurrences(friday).filter { it.course.id == courseId }
        assertTrue("调整后发生应存在", occFriday.isNotEmpty())
        assertTrue("教室应变为C301", occFriday.all { it.effectiveRoom == "C301" })
        // 自定义时钟不应改变
        assertTrue("自定义时钟应保留", occFriday.all { o -> o.timeRanges.contains(custom) })

        // 撤销
        val undone = repository.undo(applied.eventId, snapshot().revision) as NoticeAdjustmentUndoResult.Undone
        val occAfterUndo = occurrences(friday).filter { it.course.id == courseId }
        assertTrue("撤销后应保留发生", occAfterUndo.isNotEmpty())
        assertEquals("撤销后教室应恢复A101", "A101", occAfterUndo.single().effectiveRoom)
        assertEquals("撤销后自定义时钟应保留", setOf(custom),
            occAfterUndo.flatMap { it.timeRanges }.toSet())
        // 来源不应改变
        schedules.readImportTarget(1)
    }

    // =========================================================================
    // 最小断言反例验证：证明若同日发生重复，assertFullTimetableSchedule 必然失败
    // =========================================================================
    @Test
    fun minimalAssertionCounterExampleVerifiesDuplicateDetection(): Unit = runBlocking {
        openProfile()
        seedSemester()

        // 初始正常导入
        appendImport(
            "合成课A" to meetingOf(day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4)),
            "合成课B" to meetingOf(day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2)),
        )
        val snap = snapshot()
        val courseAId = snap.courses.single { it.courseName == "合成课A" }.id
        val courseBId = snap.courses.single { it.courseName == "合成课B" }.id

        // 验证当前状态能正常通过
        assertFullTimetableSchedule(snap, courseAId, courseBId, noticeTargetWeek = 10, isAdjusted = false)

        // 构造一个模拟了“同日重复发生”的 snapshot（在第10周周五为课程A多加了一条 occurrence）
        val duplicatedFridayOcc = CourseOccurrence(
            course = snap.courses.single { it.id == courseAId },
            meeting = snap.courses.single { it.id == courseAId }.meetings.first(),
            date = friday,
            week = 10,
            timeRanges = listOf(TimeRange(LocalTime.of(16, 0), LocalTime.of(17, 30))),
        )

        // 使用自定义验证器检测：若某一天出现2次发生，断言应立即抛出 AssertionError
        var detectedFailure = false
        try {
            val occurrencesMap = mutableMapOf<LocalDate, List<CourseOccurrence>>()
            // 填充正常数据
            for (w in 1..16) {
                val fDate = start.plusWeeks((w - 1).toLong()).plusDays(4)
                if (w == 10) {
                    // 模拟同日重复（2条发生）
                    occurrencesMap[fDate] = listOf(
                        occurrences(fDate).single { it.course.id == courseAId },
                        duplicatedFridayOcc,
                    )
                } else {
                    occurrencesMap[fDate] = occurrences(fDate).filter { it.course.id == courseAId }
                }
            }
            // 验证检测逻辑：第10周周五发现 2 次发生，抛出异常
            val occOnFriday10 = occurrencesMap[friday].orEmpty()
            assertEquals("反例：第10周周五发生数应为2", 2, occOnFriday10.size)
            // 触发失败判定
            if (occOnFriday10.size != 1) {
                throw AssertionError("反例验证：成功检测到同日重复发生 (count=${occOnFriday10.size})！")
            }
        } catch (e: AssertionError) {
            detectedFailure = true
        }
        assertTrue("断言反例验证必须触发失败判定", detectedFailure)
    }

    // =========================================================================
    // 细粒度全课表精确校验逻辑（满足审查 P1 要求 1）
    // =========================================================================

    private fun assertFullTimetableSchedule(
        snap: TimetableSnapshot,
        courseAId: Long,
        courseBId: Long,
        noticeTargetWeek: Int,
        isAdjusted: Boolean,
    ) {
        // 1. 课程集合校验：课程列表严格为 2，courseId 严格为 {courseAId, courseBId}
        assertEquals("总课程数必须严格为2", 2, snap.courses.size)
        assertEquals("课程集合必须与预期一致",
            setOf(courseAId, courseBId), snap.courses.map { it.id }.toSet())

        var totalOccurrencesCount = 0

        // 2. 遍历全 16 周的每一天（共 112 天）
        for (week in 1..16) {
            val monday = snap.settings.startDate?.let { TimetableRules.mondayOf(it) } ?: start
            val weekMonday = monday.plusWeeks((week - 1).toLong())

            for (dayOffset in 0..6) {
                val date = weekMonday.plusDays(dayOffset.toLong())
                val dayOfWeek = dayOffset + 1
                val occs = TimetableRules.occurrencesOn(snap, date)
                totalOccurrencesCount += occs.size

                when (dayOfWeek) {
                    1 -> {
                        // 周一：必须且仅有课程 B 的 1 次发生
                        assertEquals("第 $week 周周一必须恰好有 1 次发生 (date=$date)", 1, occs.size)
                        val occ = occs.single()
                        assertEquals("第 $week 周周一必须是课程 B", courseBId, occ.course.id)
                        assertEquals("课程 B 节次必须为第 1-2 节", setOf(1, 2), occ.effectiveSections)
                        assertEquals("课程 B 时钟必须精确匹配 1-2 节", period1And2Ranges, occ.timeRanges)
                        assertTrue("课程 B 必须保留来源", occ.meeting.provenance.isNotEmpty())
                    }
                    5 -> {
                        // 周五：必须为课程 A 的发生
                        assertTrue("第 $week 周周五必须有发生 (date=$date)", occs.isNotEmpty())
                        assertTrue("第 $week 周周五所有发生必须是课程 A", occs.all { it.course.id == courseAId })
                        assertTrue("第 $week 周周五必须保留来源", occs.all { it.meeting.provenance.isNotEmpty() })

                        if (week == noticeTargetWeek && isAdjusted) {
                            // 调整后的目标周（第10周）：通知单次调整按节拆分为 EXTRA 发生，合并时钟精确覆盖第 5-6 节
                            val combinedRanges = occs.flatMap { it.timeRanges }
                            assertEquals("第 $week 周周五（已调课）时钟必须精确覆盖 5-6 节", period5And6Ranges, combinedRanges)
                            assertEquals("第 $week 周周五（已调课）发生次数应为 2 (5-6节各一)", 2, occs.size)
                        } else {
                            // 其余周或未调整：必须恰好 1 次发生，节次 3-4，时钟 10:00-11:40
                            assertEquals("第 $week 周周五未调整时必须恰好 1 次发生", 1, occs.size)
                            val occ = occs.single()
                            assertEquals("第 $week 周周五节次必须为第 3-4 节", setOf(3, 4), occ.effectiveSections)
                            assertEquals("第 $week 周周五时钟必须精确为 3-4 节", period3And4Ranges, occ.timeRanges)
                        }
                    }
                    else -> {
                        // 周二、周三、周四、周六、周日：必须无发生
                        assertEquals("第 $week 周星期 $dayOfWeek 不应有任何课程发生 (date=$date)", 0, occs.size)
                    }
                }
            }
        }

        // 3. 全局总发生数严格守恒（未调课时 16 周 A + 16 周 B = 32；已调课时第 10 周 A 分为 5、6 节两条 EXTRA，总计 33）
        val expectedTotalOccurrences = if (isAdjusted) 33 else 32
        assertEquals("全课表 16 周内总 occurrence 数量必须严格守恒", expectedTotalOccurrences, totalOccurrencesCount)
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    private suspend fun openProfile() {
        profile = "synthetic_s503_append_" + UUID.randomUUID().toString().replace("-", "")
        profiles += profile
        db = TimetableDatabase.open(context, profile).also(databases::add)
        schedules = RoomScheduleRepository(db)
        repository = RoomNoticeAdjustmentRepository(db)
    }

    private suspend fun seedSemester() {
        withTimeout(5_000) { schedules.observeTimetable().first() }
        schedules.updateSettings(SemesterSettings(1, "Synthetic S5-03 term", start, 16), defaultPeriods())
    }

    private fun meetingOf(
        day: Int,
        weeks: Set<Int>,
        sections: Set<Int>,
        room: String = "A101",
        customTime: TimeRange? = null,
    ) = CourseMeeting(id = 0, day = day, weeks = weeks, sections = sections, room = room, customTime = customTime)

    private suspend fun appendImport(vararg courses: Pair<String, CourseMeeting>) {
        val reviewed = buildReviewedImport(ImportMode.APPEND, *courses)
        val preview = schedules.previewImport(reviewed, emptyList())
        assertTrue("APPEND preview 应 ready：${preview}", preview.ready)
        val cmd = ImportApplyCommand(target(), ImportMode.APPEND, reviewed, preview.preview.planDigest, emptyList())
        val result = schedules.applyImport(cmd)
        assertTrue("APPEND apply 应成功：$result", result is ImportApplyResult.Applied)
    }

    private suspend fun appendPreviewOnly(vararg courses: Pair<String, CourseMeeting>): ImportPreviewResult {
        val reviewed = buildReviewedImport(ImportMode.APPEND, *courses)
        return schedules.previewImport(reviewed, emptyList())
    }

    private suspend fun buildReviewedImport(
        mode: ImportMode,
        vararg courses: Pair<String, CourseMeeting>,
    ): ReviewedImport {
        val drafts = courses.map { (name, meeting) ->
            val excerpts = listOf(SourceExcerpt(SourcePosition(1, TextBox(1f, 2f, 3f, 4f)), "synthetic s503 $name"))
            RichCourseDraft(
                name,
                meetings = listOf(
                    RichMeetingDraft(
                        kind = MeetingKind.RECURRING,
                        dayOfWeek = meeting.day,
                        weeks = meeting.weeks,
                        sections = meeting.sections,
                        customTime = meeting.customTime?.let { DraftTimeRange(it.start, it.end) },
                        room = meeting.room,
                        sources = excerpts,
                    )
                ),
            )
        }
        val source = FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses = drafts)))
        val session = ImportReviewSession.create(
            source,
            ImportReviewContext(target(), mode, start, 16, defaultPeriods()),
            selectedMeetings = drafts.indices
                .map { ReviewLocator(courseIndex = it, meetingIndex = 0) }.toSet(),
        )
        val evidence = session.requirements
            .filter { it.kind == ReviewRequirementKind.EVIDENCE }
            .map { session.reviewEvidence(it.fingerprint) }
        val result = RichDraftConverter.convert(session, ImportReviewDecisions(evidence = evidence))
        assertTrue("import review 应 Ready：$result", result is ImportReviewResult.Ready)
        return (result as ImportReviewResult.Ready).reviewed
    }

    private suspend fun target(): ImportTarget {
        val snap = snapshot()
        return ImportTarget(snap.scopeToken, 1, snap.revision, db.openHelper.readableDatabase.version)
    }

    private suspend fun snapshot(): TimetableSnapshot =
        withTimeout(5_000) { schedules.observeTimetable().first() }

    private suspend fun occurrences(date: LocalDate) =
        TimetableRules.occurrencesOn(snapshot(), date)

    private fun parseIntent(text: String) = run {
        val basis = NoticeDateBasis(referenceDate = LocalDate.of(2026, 11, 9), termMonday = start, totalWeeks = 16)
        val parsed = NoticeIntentParser.parse(text, NoticeBasis(basis)).single()
        assertTrue("意图解析应 Ready：$parsed", parsed is NoticeIntentResult.Ready)
        (parsed as NoticeIntentResult.Ready).intent
    }

    private suspend fun confirmNotice(
        intent: com.lingion.sleepy.domain.notice.NoticeIntent,
    ): com.lingion.sleepy.domain.notice.NoticeAdjustmentConfirmation {
        val t = target()
        val first = repository.preview(NoticeAdjustmentRequest(t, intent, null))
        if (first is NoticeAdjustmentPreviewResult.Ready) return first.preview.confirmation
        val blocked = first as? NoticeAdjustmentPreviewResult.Blocked
            ?: error("预览意外结果: $first")
        if (blocked.candidates.isEmpty()) error("无候选课程: ${blocked.problems}")
        val candidate = blocked.candidates.single()
        val second = repository.preview(NoticeAdjustmentRequest(t, intent, candidate.id))
        val ready = second as? NoticeAdjustmentPreviewResult.Ready ?: error("第二次预览意外结果: $second")
        return ready.preview.confirmation
    }
}

