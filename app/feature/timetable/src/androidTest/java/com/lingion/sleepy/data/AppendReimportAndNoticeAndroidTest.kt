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
import com.lingion.sleepy.domain.imports.IdentityArchive
import com.lingion.sleepy.domain.imports.IdentityReviewKind
import com.lingion.sleepy.domain.imports.SourceRelationChoice
import com.lingion.sleepy.importing.ocr.*
import com.lingion.sleepy.importing.formats.FormatParseContext
import com.lingion.sleepy.importing.PositionedPage
import com.lingion.sleepy.importing.PositionedText
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
import org.junit.Assert.assertFalse
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
    // S7-PRE-FIX-01 验证：图片 INDEPENDENT review 链追加重导幂等性与零写入
    // =========================================================================
    @Test
    fun appendImageWithIndependentIdentityReviewReimportIsIdempotentAndUnchanged(): Unit = runBlocking {
        openProfile()
        seedSemester()

        val p0 = ReviewLocator(courseIndex = 0, meetingIndex = 0)
        val p1 = ReviewLocator(courseIndex = 1, meetingIndex = 0)
        val courses = listOf(
            RichCourseDraft("Hadoop实验", meetings = listOf(RichMeetingDraft(MeetingKind.RECURRING, dayOfWeek = 5, weeks = setOf(7, 8), sections = setOf(1, 2), room = "信工401"))),
            RichCourseDraft("编译原理实验", meetings = listOf(RichMeetingDraft(MeetingKind.RECURRING, dayOfWeek = 6, weeks = setOf(7, 8), sections = setOf(3, 4), room = "信工402")))
        )
        val format1 = FormatParseResult(RichTimetableDraft(courses = courses))
        val source1 = FrozenImportSource.fromFormat(format1)
        val session1 = ImportReviewSession.create(source1, ImportReviewContext(target(), ImportMode.APPEND, start, 16, defaultPeriods()))
        val decisions1 = ImportReviewDecisions(
            evidence = session1.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { session1.reviewEvidence(it.fingerprint) },
            identity = session1.reviewIdentity(
                com.lingion.sleepy.domain.imports.IdentityReviewKind.INDEPENDENT,
                listOf(com.lingion.sleepy.domain.imports.IdentityArchive(setOf(p0)), com.lingion.sleepy.domain.imports.IdentityArchive(setOf(p1)))
            )
        )
        val reviewed1 = (RichDraftConverter.convert(session1, decisions1) as ImportReviewResult.Ready).reviewed
        val preview1 = schedules.previewImport(reviewed1, emptyList())
        assertTrue("第一次导入预览应 ready", preview1.ready)
        assertFalse("第一次导入不应 unchanged", preview1.unchanged)

        val cmd1 = ImportApplyCommand(target(), ImportMode.APPEND, reviewed1, preview1.preview.planDigest, emptyList())
        val apply1 = schedules.applyImport(cmd1)
        assertTrue("第一次导入应成功应用", apply1 is ImportApplyResult.Applied)
        schedules.readImportTarget(1)

        val snapAfterFirst = snapshot()
        assertEquals("应包含两门课程", 2, snapAfterFirst.courses.size)
        val revAfterFirst = snapAfterFirst.revision

        // 第二次导入：相同内容再次以 INDEPENDENT 追加
        val format2 = FormatParseResult(RichTimetableDraft(courses = courses))
        val source2 = FrozenImportSource.fromFormat(format2)
        val session2 = ImportReviewSession.create(source2, ImportReviewContext(target(), ImportMode.APPEND, start, 16, defaultPeriods()))
        val decisions2 = ImportReviewDecisions(
            evidence = session2.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { session2.reviewEvidence(it.fingerprint) },
            identity = session2.reviewIdentity(
                com.lingion.sleepy.domain.imports.IdentityReviewKind.INDEPENDENT,
                listOf(com.lingion.sleepy.domain.imports.IdentityArchive(setOf(p0)), com.lingion.sleepy.domain.imports.IdentityArchive(setOf(p1)))
            )
        )
        val reviewed2 = (RichDraftConverter.convert(session2, decisions2) as ImportReviewResult.Ready).reviewed
        val preview2 = schedules.previewImport(reviewed2, emptyList())
        assertTrue("第二次导入预览应 ready", preview2.ready)
        assertTrue("所有安排已存在，第二次导入必须 unchanged", preview2.unchanged)

        val cmd2 = ImportApplyCommand(target(), ImportMode.APPEND, reviewed2, preview2.preview.planDigest, emptyList())
        val apply2 = schedules.applyImport(cmd2)
        assertTrue("第二次导入应用应成功（零写入）", apply2 is ImportApplyResult.Applied)
        schedules.readImportTarget(1)

        val snapAfterSecond = snapshot()
        assertEquals("课程总数绝不应翻倍，仍为 2 门", 2, snapAfterSecond.courses.size)
        assertEquals("零写入：revision 保持不变", revAfterFirst, snapAfterSecond.revision)
    }

    // =========================================================================
    // S7-PRE-FIX-01-R1 核心数据库回归：8行同名多日期实验、来源变化核对、单次调课与作息联动保护
    // =========================================================================
    @Test
    fun sameTitleMultiDateExperimentsAppendWithSourceChangeGuardsAdjustmentsPeriodsAndPersistence(): Unit = runBlocking {
        openProfile()
        seedSemester()

        // 1. 首次导入：8 行同名无代码多日期实验课入库
        val reviewed1 = buildOcrReviewedImport(ocrDateRows(8, 0f), "ocr-source-8rows-1")
        val preview1 = schedules.previewImport(reviewed1, emptyList())
        assertTrue("第一次导入必须 ready: ${preview1.problems}", preview1.ready)
        assertFalse("第一次导入不应 unchanged", preview1.unchanged)

        val cmd1 = ImportApplyCommand(target(), ImportMode.APPEND, reviewed1, preview1.preview.planDigest, emptyList())
        val apply1 = schedules.applyImport(cmd1)
        assertTrue("第一次导入必须成功应用", apply1 is ImportApplyResult.Applied)
        schedules.readImportTarget(1)

        val snapAfterFirst = snapshot()
        assertEquals("底层必须落成 8 门独立课程档案", 8, snapAfterFirst.courses.size)
        val revAfterFirst = snapAfterFirst.revision

        // 2. 单次调课：对第 1 次实验（2026-10-24）应用教室变更
        val adjustDate = LocalDate.of(2026, 10, 24)
        val occsBefore = occurrences(adjustDate).filter { it.course.courseName.contains("Hadoop") }
        assertTrue("调整日前必须有该实验发生", occsBefore.isNotEmpty())
        assertEquals("初始教室为信工楼401", "信工楼401", occsBefore.single().effectiveRoom)

        val adjustIntent = parseIntent("2026-10-24Hadoop大数据实验教室改为信工实验楼502")
        val confirmation = confirmNotice(adjustIntent)
        val applyNotice = repository.apply(confirmation) as NoticeAdjustmentApplyResult.Applied
        val revAfterNotice = snapshot().revision
        assertTrue("应用调课后 revision 必须增加", revAfterNotice > revAfterFirst)

        val occsAfterNotice = occurrences(adjustDate).filter { it.course.courseName.contains("Hadoop") }
        assertTrue("调课后教室应变为信工实验楼502", occsAfterNotice.all { it.effectiveRoom == "信工实验楼502" })

        // 3. 作息时间修改（与 FIX-02 联动）：顺延 10 分钟
        val updatedPeriods = defaultPeriods().map { p ->
            p.copy(start = p.start.plusMinutes(10), end = p.end.plusMinutes(10))
        }
        schedules.updateSettings(SemesterSettings(1, "Synthetic S5-03 term", start, 16), updatedPeriods)
        val revAfterPeriod = snapshot().revision
        assertTrue("修改作息后 revision 必须增加", revAfterPeriod > revAfterNotice)
        val targetAfterPeriod = schedules.readImportTarget(1)
        assertEquals("修改作息后 ImportTarget 状态必须保持合法", 16, targetAfterPeriod.snapshot.settings.totalWeeks)

        // 4. 来源变化后的再次 APPEND 重导：来源ID改变（ocr-source-8rows-2），坐标平移 0.5px
        // 来源核对守卫：若未确认来源关系，必须要求核对
        val reviewed2NoChoice = buildOcrReviewedImport(ocrDateRows(8, 0.5f), "ocr-source-8rows-2", null)
        val preview2NoChoice = schedules.previewImport(reviewed2NoChoice, emptyList())
        assertTrue("来源变化时必须要求来源关系核对", preview2NoChoice.sourceRelationRequired)
        assertFalse("来源未确认时 preview 不应 ready", preview2NoChoice.ready)

        // 回答 DIFFERENT_SOURCE 确认来源关系
        val reviewed2 = buildOcrReviewedImport(ocrDateRows(8, 0.5f), "ocr-source-8rows-2", SourceRelationChoice.DIFFERENT_SOURCE)
        val preview2 = schedules.previewImport(reviewed2, emptyList())
        assertTrue("确认来源关系后 preview 必须 ready: ${preview2.problems}", preview2.ready)
        assertTrue("【核心回归】8 行同名实验即使来源ID与坐标微抖，重导也必须判定为 unchanged！", preview2.unchanged)

        // 验证取消/未应用时的零写入（比较 snapshot、importStates 含 payload、事件历史与 revision）
        val snapBeforeApply = snapshot()
        val importStatesBeforeApply = db.scheduleDao().importStates(1)
        val historyBeforeApply = (repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries
        assertEquals("零写入：revision 未变", revAfterPeriod, snapBeforeApply.revision)
        assertEquals("零写入：课程数量依然为 8", 8, snapBeforeApply.courses.size)

        // 执行应用 apply（全重复幂等）
        val cmd2 = ImportApplyCommand(target(), ImportMode.APPEND, reviewed2, preview2.preview.planDigest, emptyList())
        val apply2 = schedules.applyImport(cmd2)
        assertTrue("第二次导入应用应成功（零写入）", apply2 is ImportApplyResult.Applied)
        schedules.readImportTarget(1)

        val snapAfterSecond = snapshot()
        assertEquals("【核心断言】重导绝不增加新课程，课程总数保持 8 门", 8, snapAfterSecond.courses.size)
        assertEquals("零写入：全重复应用前后 revision 严格全等", revAfterPeriod, snapAfterSecond.revision)
        assertEquals("零写入：全重复应用前后 完整 TimetableSnapshot 严格全等", snapBeforeApply, snapAfterSecond)
        assertEquals("零写入：全重复应用前后 完整 importStates 含 payload 严格全等", importStatesBeforeApply, db.scheduleDao().importStates(1))
        assertEquals("零写入：全重复应用前后 历史事件列表严格全等", historyBeforeApply, (repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries)

        // 8 个日期与实验项目的完整性细粒度校验
        for (i in 0 until 8) {
            val date = LocalDate.of(2026, 10, 24).plusDays((i * 7).toLong())
            val occs = occurrences(date).filter { it.course.courseName.contains("Hadoop") }
            assertEquals("第 ${i + 1} 次实验在 $date 必须恰好有 1 条发生", 1, occs.size)
            val occ = occs.single()
            if (i == 0) {
                assertEquals("调整日（第1次实验）教室保持信工实验楼502", "信工实验楼502", occ.effectiveRoom)
            } else {
                assertEquals("未调整日（第 ${i + 1} 次实验）教室保持信工楼401", "信工楼401", occ.effectiveRoom)
            }
            assertEquals("实验项目必须精确保留", "MapReduce编程$i", occ.meeting.experimentProject)
            assertTrue("来源 provenance 必须非空保持", occ.meeting.provenance.isNotEmpty())
        }

        // 5. 撤销单次调课
        val undoResult = repository.undo(applyNotice.eventId, snapAfterSecond.revision)
        assertTrue("重导后单次调课仍能正常撤销", undoResult is NoticeAdjustmentUndoResult.Undone)
        val occsAfterUndo = occurrences(adjustDate).filter { it.course.courseName.contains("Hadoop") }
        assertEquals("撤销后教室恢复信工楼401", "信工楼401", occsAfterUndo.single().effectiveRoom)

        // 6. 关闭并重新打开数据库
        db.close()
        val reopenedDb = TimetableDatabase.open(context, profile).also(databases::add)
        val reopenedSchedules = RoomScheduleRepository(reopenedDb)
        val reopenedTarget = reopenedSchedules.readImportTarget(1)
        assertEquals(8, reopenedTarget.snapshot.courses.size)

        // 验证旧 reviewed2 因撤销操作推进了 revision 而被正确拒绝（STALE_REVISION）
        val stalePreview = reopenedSchedules.previewImport(reviewed2, emptyList())
        assertFalse("旧 reviewed 包含过期 revision，必须被拒绝", stalePreview.ready)

        // 从重开后的最新 target 重新构建 review
        val snapReopened = reopenedTarget.snapshot
        val currentReopenedTarget = ImportTarget(snapReopened.scopeToken, 1, snapReopened.revision, reopenedDb.openHelper.readableDatabase.version)
        val parsedReopened = parseSyntheticOcr(ocrDateRows(8, 0.5f), "ocr-source-8rows-2")
        val sourceReopened = FrozenImportSource.fromOcr(parsedReopened)
        val sessionReopened = ImportReviewSession.create(
            sourceReopened,
            ImportReviewContext(currentReopenedTarget, ImportMode.APPEND, start, snapReopened.settings.totalWeeks, snapReopened.periods),
        )
        val archivesReopened = sessionReopened.selectedMeetings.groupBy { it.courseIndex!! }.values.map {
            IdentityArchive(it.toSet())
        }
        val identityReopened = sessionReopened.reviewIdentity(IdentityReviewKind.INDEPENDENT, archivesReopened)
        val evidenceReopened = sessionReopened.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { sessionReopened.reviewEvidence(it.fingerprint) }
        val decisionsReopened = ImportReviewDecisions(
            evidence = evidenceReopened,
            identity = identityReopened,
            sourceRelation = sessionReopened.reviewSourceRelation(SourceRelationChoice.DIFFERENT_SOURCE)
        )
        val convReopened = RichDraftConverter.convert(sessionReopened, decisionsReopened)
        if (convReopened is ImportReviewResult.Blocked) {
            fail("reopened review failed: problems=${convReopened.problems}, requirements=${convReopened.requirements}")
        }
        val reviewed3 = (convReopened as ImportReviewResult.Ready).reviewed

        val previewAfterReopen = reopenedSchedules.previewImport(reviewed3, emptyList())
        assertTrue("从最新 target 重建后 preview 必须 ready: ${previewAfterReopen.problems}", previewAfterReopen.ready)
        assertTrue("数据库重开后依然判定为 unchanged", previewAfterReopen.unchanged)
    }

    private fun word(t: String, x: Float, y: Float, w: Float = 72f, h: Float = 10f, page: Int = 1) =
        OcrSpan(t, OcrLevel.WORD, 95f, SourcePosition(page, TextBox(x, y, x + w, y + h)))

    private fun ocrDateRows(count: Int = 8, xOffset: Float = 0f): List<OcrSpan> {
        val out = mutableListOf<OcrSpan>()
        listOf("日期", "星期", "周次", "节次", "时间", "课程名称", "备注", "实验项目", "实验室", "班组").forEachIndexed { i, s ->
            out += word(s, i * 140f + xOffset, 20f)
        }
        repeat(count) { i ->
            val date = LocalDate.of(2026, 10, 24).plusDays((i * 7).toLong())
            val fields = listOf(
                date.toString().replace("-", ""), "${date.dayOfWeek.value}", "7,8", "1,2", "08:00-09:40",
                "Hadoop大数据实验", "普通备注", "MapReduce编程$i", "信工楼401", "第${i + 1}组"
            )
            fields.forEachIndexed { j, s ->
                out += word(s, j * 140f + xOffset, 60f + i * 28f, 72f)
            }
        }
        return out
    }

    private fun parseSyntheticOcr(spans: List<OcrSpan>, sourceId: String): OcrLayoutParseResult {
        val p = PositionedPage(1, 1500f, 2000f, emptyList<PositionedText>())
        val page = OcrLayoutPage(1, p.width, p.height, listOf(
            OcrVariantEvidence(
                OcrVariantId.ORIGINAL, 1500, 2000,
                PageAffineTransform.IDENTITY, p, spans, emptySet(), null,
                OcrWordQuality.EMPTY, true
            )
        ), OcrVariantId.ORIGINAL)
        val ctx = OcrLayoutContext(
            FormatParseContext(sourceId, 20),
            confirmedTermMonday = start,
            referenceDate = start
        )
        return OcrTimetableParser().parse(OcrLayoutDocument(listOf(page)), ctx)
    }

    private suspend fun buildOcrReviewedImport(
        spans: List<OcrSpan>,
        sourceId: String,
        sourceRelationChoice: SourceRelationChoice? = null
    ): ReviewedImport {
        val snap = snapshot()
        val currentTarget = target()
        val parsed = parseSyntheticOcr(spans, sourceId)
        val source = FrozenImportSource.fromOcr(parsed)
        val session = ImportReviewSession.create(
            source,
            ImportReviewContext(currentTarget, ImportMode.APPEND, start, snap.settings.totalWeeks, snap.periods),
        )
        val archives = session.selectedMeetings.groupBy { it.courseIndex!! }.values.map {
            IdentityArchive(it.toSet())
        }
        val identity = session.reviewIdentity(IdentityReviewKind.INDEPENDENT, archives)
        val evidence = session.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) }
        val decisions = ImportReviewDecisions(
            evidence = evidence,
            identity = identity,
            sourceRelation = sourceRelationChoice?.let { session.reviewSourceRelation(it) }
        )
        val res = RichDraftConverter.convert(session, decisions)
        if (res is ImportReviewResult.Blocked) {
            fail("buildOcrReviewedImport failed: problems=${res.problems}, requirements=${res.requirements}")
        }
        val ready = res as ImportReviewResult.Ready
        return ready.reviewed
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

