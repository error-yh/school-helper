/* SPDX-License-Identifier: GPL-3.0-only
 * S5-03 工作包二：真正的跨进程持久化验证（三独立进程生命周期）
 *
 * 设计原则：
 *   - 三阶段分别运行在三个独立的 instrumentation 进程中：
 *       1. crossProcessSeed: 导入A/B、应用通知、写入 checkpoint (phase=SEED_DONE) -> 进程退出
 *       2. crossProcessVerifyAndUndo: 全新进程1，校验 seedPid!=currentPid，验证调整后持久化，执行撤销，更新 checkpoint (phase=UNDO_DONE) -> 进程退出
 *       3. crossProcessVerifyUndone: 全新进程2，校验 undoPid!=currentPid 且 seedPid!=currentPid，验证撤销后冷启动持久化，精准清理 -> 进程退出
 *   - 严格通过 instrumentation arguments (-e s503_runId <runId>) 显式 opt-in。
 *   - 普通运行默认跳过（org.junit.Assume.assumeTrue），不阻塞 CI，不创建测试文件。
 *   - checkpoint 写入应用专属 files 目录，文件名含固定 runId，跨进程可读取。
 *   - 仅操作本次命名的独立测试数据库 "timetable_s503_persist_<runId>.db"，绝对不访问日常宿主数据库。
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
import com.lingion.sleepy.domain.notice.NoticeCandidateId
import com.lingion.sleepy.domain.notice.NoticeAdjustmentApplyResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreviewResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentRequest
import com.lingion.sleepy.domain.notice.NoticeAdjustmentUndoResult
import com.lingion.sleepy.domain.notice.NoticeBasis
import com.lingion.sleepy.domain.notice.NoticeIntentParser
import com.lingion.sleepy.domain.notice.NoticeIntentResult
import com.lingion.sleepy.domain.repository.ImportApplyResult
import com.lingion.sleepy.importing.MeetingKind
import com.lingion.sleepy.importing.NoticeDateBasis
import com.lingion.sleepy.importing.RichCourseDraft
import com.lingion.sleepy.importing.RichMeetingDraft
import com.lingion.sleepy.importing.RichTimetableDraft
import com.lingion.sleepy.importing.SourceExcerpt
import com.lingion.sleepy.importing.SourcePosition
import com.lingion.sleepy.importing.TextBox
import com.lingion.sleepy.importing.formats.FormatParseResult
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CrossProcessPersistenceAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val args: Bundle = InstrumentationRegistry.getArguments()

    /** 学期起始：2026-09-07 */
    private val start = LocalDate.of(2026, 9, 7)

    /** 断言目标日：第10周周五 = 2026-11-13 */
    private val friday = LocalDate.of(2026, 11, 13)

    // 默认节次时间范围
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

    // =========================================================================
    // 阶段一：Seed — 导入课程、应用通知、写 checkpoint (SEED_DONE) -> 退出
    // =========================================================================
    @Test
    fun crossProcessSeed(): Unit = runBlocking {
        val runId = args.getString("s503_runId")
        assumeTrue("crossProcessSeed: 跳过，未传入 -e s503_runId", runId != null)
        requireNotNull(runId)

        val profileId = profileIdFor(runId)
        val checkpointFile = checkpointFile(runId)
        val dbFile = context.getDatabasePath("timetable_$profileId.db")

        // 严格拒绝残留：checkpoint 与数据库必须均不存在
        check(!checkpointFile.exists()) {
            "Seed 发现已有 checkpoint 文件 ${checkpointFile.path}，请清理残留后重试"
        }
        check(!dbFile.exists()) {
            "Seed 发现已有测试数据库 ${dbFile.path}，请清理残留后重试"
        }

        val db = TimetableDatabase.open(context, profileId)
        try {
            val schedules = RoomScheduleRepository(db)
            val repository = RoomNoticeAdjustmentRepository(db)

            // 初始化学期
            withTimeout(5_000) { schedules.observeTimetable().first() }
            schedules.updateSettings(
                SemesterSettings(1, "S5-03 CrossProcess Term", start, 16),
                defaultPeriods(),
            )

            // APPEND 导入：课程A（周五第3-4节）+ 无关课程B（周一第1-2节）
            val reviewed = buildReviewedImport(
                schedules, db,
                ImportMode.APPEND,
                "持久化测试课A" to CourseMeeting(id = 0, day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4)),
                "持久化测试课B" to CourseMeeting(id = 0, day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2)),
            )
            val target0 = currentTarget(schedules, db)
            val preview = schedules.previewImport(reviewed, emptyList())
            assertTrue("Seed: APPEND preview 应 ready", preview.ready)
            val applyResult = schedules.applyImport(
                ImportApplyCommand(target0, ImportMode.APPEND, reviewed, preview.preview.planDigest, emptyList())
            )
            assertTrue("Seed: APPEND apply 应成功", applyResult is ImportApplyResult.Applied)
            schedules.readImportTarget(1)

            val snapAfterImport = snapshotOf(schedules)
            val courseAId = snapAfterImport.courses.single { it.courseName == "持久化测试课A" }.id
            val courseBId = snapAfterImport.courses.single { it.courseName == "持久化测试课B" }.id

            // 应用通知：第10周周五课程A由第3-4节调整为第5-6节
            val noticeText = "本周五持久化测试课A由第3-4节调整为第5-6节"
            val intent = parseIntent(noticeText)
            val confirmation = confirmNotice(repository, schedules, db, intent)
            val applied = repository.apply(confirmation) as NoticeAdjustmentApplyResult.Applied
            assertEquals("Seed: 通知应走 NORMALIZED 路径", "NORMALIZED", applied.mode)

            val snapAfterNotice = snapshotOf(schedules)
            val revAfterNotice = snapAfterNotice.revision
            val eventId = applied.eventId

            // 细粒度校验通知应用效果
            assertScheduleState(snapAfterNotice, courseAId, courseBId, isAdjusted = true)

            // 写 checkpoint
            val selection = confirmation.selection
            val currentPid = android.os.Process.myPid()
            val content = buildString {
                appendLine("runId=$runId")
                appendLine("profileId=$profileId")
                appendLine("courseAId=$courseAId")
                appendLine("courseBId=$courseBId")
                appendLine("eventId=$eventId")
                appendLine("revisionAfterNotice=$revAfterNotice")
                appendLine("fridayEpochDay=${friday.toEpochDay()}")
                appendLine("logicalBaseId=${selection?.logicalBaseId.orEmpty()}")
                appendLine("structureFingerprint=${selection?.structureFingerprint.orEmpty()}")
                appendLine("seedPid=$currentPid")
                appendLine("phase=SEED_DONE")
            }
            checkpointFile.parentFile?.mkdirs()
            checkpointFile.writeText(content, Charsets.UTF_8)

            android.util.Log.i(TAG, "S5-03 Seed 完成: runId=$runId, eventId=$eventId, rev=$revAfterNotice, seedPid=$currentPid")
        } finally {
            db.close()
        }
    }

    // =========================================================================
    // 阶段二：VerifyAndUndo — 全新进程1验证调整持久化，执行撤销，写 checkpoint (UNDO_DONE) -> 退出
    // =========================================================================
    @Test
    fun crossProcessVerifyAndUndo(): Unit = runBlocking {
        val runId = args.getString("s503_runId")
        assumeTrue("crossProcessVerifyAndUndo: 跳过，未传入 -e s503_runId", runId != null)
        requireNotNull(runId)

        val checkpointFile = checkpointFile(runId)
        assertTrue("crossProcessVerifyAndUndo: checkpoint 不存在，必须先运行 seed 阶段", checkpointFile.exists())

        val cp = parseCheckpoint(checkpointFile)
        assertEquals("runId 不匹配", runId, cp["runId"])
        assertEquals("阶段标记不正确，期望 SEED_DONE", "SEED_DONE", cp["phase"])

        val seedPid = cp["seedPid"]?.toIntOrNull() ?: error("checkpoint 缺少 seedPid")
        val currentPid = android.os.Process.myPid()
        check(currentPid != seedPid) {
            "VerifyAndUndo 进程 pid=$currentPid 与 Seed pid=$seedPid 相同，未实现跨进程！"
        }
        android.util.Log.i(TAG, "S5-03 VerifyAndUndo: seedPid=$seedPid, currentPid=$currentPid (跨进程已确认)")

        val profileId = cp["profileId"] ?: error("checkpoint 缺少 profileId")
        val courseAId = cp["courseAId"]?.toLong() ?: error("checkpoint 缺少 courseAId")
        val courseBId = cp["courseBId"]?.toLong() ?: error("checkpoint 缺少 courseBId")
        val eventId = cp["eventId"]?.toLong() ?: error("checkpoint 缺少 eventId")
        val revisionAfterNotice = cp["revisionAfterNotice"]?.toLong() ?: error("checkpoint 缺少 revisionAfterNotice")

        val db = TimetableDatabase.open(context, profileId)
        try {
            val schedules = RoomScheduleRepository(db)
            val repository = RoomNoticeAdjustmentRepository(db)

            // 全新冷启动进程读取：全16周细粒度验证 Seed 阶段的调整持久化
            val snap = snapshotOf(schedules)
            assertEquals("VerifyAndUndo: revision 应与 Seed 结束时一致", revisionAfterNotice, snap.revision)
            assertScheduleState(snap, courseAId, courseBId, isAdjusted = true)

            // 历史事件持久化验证
            val history = repository.history() as NoticeAdjustmentHistoryResult.Loaded
            assertEquals("VerifyAndUndo: 历史应有1条事件", 1, history.history.entries.size)
            assertEquals("VerifyAndUndo: 事件状态应为 APPLIED", "APPLIED",
                history.history.entries.single { it.eventId == eventId }.state)

            // 幂等验证：重放同一通知应返回 AlreadyApplied
            val intent = parseIntent("本周五持久化测试课A由第3-4节调整为第5-6节")
            val t = currentTarget(schedules, db)
            val logicalBaseId = cp["logicalBaseId"].orEmpty()
            val structureFingerprint = cp["structureFingerprint"].orEmpty()
            val selection = NoticeCandidateId(courseAId, logicalBaseId, structureFingerprint)
            val previewResult = repository.preview(NoticeAdjustmentRequest(t, intent, selection))
            assertTrue("VerifyAndUndo: 幂等 preview 应为 AlreadyApplied，实际: $previewResult",
                previewResult is NoticeAdjustmentPreviewResult.AlreadyApplied)
            assertEquals("VerifyAndUndo: 幂等返回的 eventId 应一致",
                eventId, (previewResult as NoticeAdjustmentPreviewResult.AlreadyApplied).eventId)

            // === 在此独立进程中执行守卫撤销 ===
            val undoResult = repository.undo(eventId, snap.revision)
            assertTrue("VerifyAndUndo: 守卫撤销必须返回 Undone，实际: $undoResult",
                undoResult is NoticeAdjustmentUndoResult.Undone)
            val undone = undoResult as NoticeAdjustmentUndoResult.Undone

            // 验证在当前进程中状态已恢复
            val snapAfterUndo = snapshotOf(schedules)
            assertEquals("VerifyAndUndo: 撤销后 revision 匹配", undone.revision, snapAfterUndo.revision)
            assertScheduleState(snapAfterUndo, courseAId, courseBId, isAdjusted = false)

            // 更新 checkpoint：写入 UNDO_DONE 与 undoPid，准备进入第三进程冷启动读回
            val content = buildString {
                checkpointFile.readLines(Charsets.UTF_8).forEach { line ->
                    if (!line.startsWith("phase=") && !line.startsWith("undoPid=") && !line.startsWith("undoneRevision=")) {
                        appendLine(line)
                    }
                }
                appendLine("undoPid=$currentPid")
                appendLine("undoneRevision=${undone.revision}")
                appendLine("phase=UNDO_DONE")
            }
            checkpointFile.writeText(content, Charsets.UTF_8)

            android.util.Log.i(TAG, "S5-03 VerifyAndUndo 完成: runId=$runId, undoPid=$currentPid, undoneRev=${undone.revision}")
        } finally {
            db.close()
        }
    }

    // =========================================================================
    // 阶段三：VerifyUndone — 全新进程2验证撤销结果冷启动持久化，精准清理
    // =========================================================================
    @Test
    fun crossProcessVerifyUndone(): Unit = runBlocking {
        val runId = args.getString("s503_runId")
        assumeTrue("crossProcessVerifyUndone: 跳过，未传入 -e s503_runId", runId != null)
        requireNotNull(runId)

        val checkpointFile = checkpointFile(runId)
        assertTrue("crossProcessVerifyUndone: checkpoint 不存在，请先运行 seed 与 verifyAndUndo 阶段",
            checkpointFile.exists())

        val cp = parseCheckpoint(checkpointFile)
        assertEquals("runId 不匹配", runId, cp["runId"])
        assertEquals("阶段标记不正确，期望 UNDO_DONE", "UNDO_DONE", cp["phase"])

        val seedPid = cp["seedPid"]?.toIntOrNull() ?: error("checkpoint 缺少 seedPid")
        val undoPid = cp["undoPid"]?.toIntOrNull() ?: error("checkpoint 缺少 undoPid")
        val undoneRevision = cp["undoneRevision"]?.toLongOrNull() ?: error("checkpoint 缺少 undoneRevision")
        val currentPid = android.os.Process.myPid()

        // 严格断言：第三阶段进程 PID 必须不同于 Phase 1 和 Phase 2！
        check(currentPid != undoPid && currentPid != seedPid) {
            "VerifyUndone 进程 pid=$currentPid 与 Undo pid=$undoPid 或 Seed pid=$seedPid 相同，未实现跨进程！"
        }
        android.util.Log.i(TAG, "S5-03 VerifyUndone: seedPid=$seedPid, undoPid=$undoPid, currentPid=$currentPid (第三次跨进程已确认)")

        val profileId = cp["profileId"] ?: error("checkpoint 缺少 profileId")
        val courseAId = cp["courseAId"]?.toLong() ?: error("checkpoint 缺少 courseAId")
        val courseBId = cp["courseBId"]?.toLong() ?: error("checkpoint 缺少 courseBId")
        val eventId = cp["eventId"]?.toLong() ?: error("checkpoint 缺少 eventId")

        val db = TimetableDatabase.open(context, profileId)
        try {
            val schedules = RoomScheduleRepository(db)
            val repository = RoomNoticeAdjustmentRepository(db)

            // 全新冷启动进程读取：验证撤销结果在完全退出并重新打开后持久存在！
            val snap = snapshotOf(schedules)
            assertEquals("VerifyUndone: revision 应与 Undo 阶段一致", undoneRevision, snap.revision)

            // 细粒度断言：全16周已恢复未调整状态（第10周恢复为3-4节及10:00-11:40，B依然为1-2节，总发生32）
            assertScheduleState(snap, courseAId, courseBId, isAdjusted = false)

            // 历史事件持久化：状态为 UNDONE，undoneRevision 匹配
            val history = repository.history() as NoticeAdjustmentHistoryResult.Loaded
            assertEquals("VerifyUndone: 历史事件数仍为1", 1, history.history.entries.size)
            val eventEntry = history.history.entries.single { it.eventId == eventId }
            assertEquals("VerifyUndone: 事件状态必须为 UNDONE", "UNDONE", eventEntry.state)
            assertEquals("VerifyUndone: undoneRevision 必须一致", undoneRevision, eventEntry.undoneRevision)

            // 来源持久化验证
            schedules.readImportTarget(1)
            assertTrue("VerifyUndone: 课程A必须保留来源",
                snap.courses.single { it.id == courseAId }.meetings.any { it.provenance.isNotEmpty() })
            assertTrue("VerifyUndone: 课程B必须保留来源",
                snap.courses.single { it.id == courseBId }.meetings.any { it.provenance.isNotEmpty() })

            android.util.Log.i(TAG, "S5-03 VerifyUndone 验证全部通过，开始精准清理")
        } finally {
            db.close()
        }

        // 精确清理本次独立测试数据库与 checkpoint 文件（绝不触碰日常库）
        val deleted = context.deleteDatabase("timetable_$profileId.db")
        assertTrue("VerifyUndone: 测试数据库应删除成功", deleted)
        val cpDeleted = checkpointFile.delete()
        assertTrue("VerifyUndone: checkpoint 文件应删除成功", cpDeleted)

        // 再次确认文件已不存在
        assertFalse("测试数据库文件已彻底删除", context.getDatabasePath("timetable_$profileId.db").exists())
        assertFalse("checkpoint 文件已彻底删除", checkpointFile.exists())

        // 结构化输出本次真实 PID 与 runId（直接注入 instrumentation stream）
        val resultBundle = Bundle().apply {
            putInt("currentPid", currentPid)
            putString("runId", runId)
            putString("phase", "VERIFY_UNDONE_SUCCESS")
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, resultBundle)

        android.util.Log.i(TAG, "S5-03 VerifyUndone 清理完成: runId=$runId, currentPid=$currentPid")
    }

    // =========================================================================
    // 细粒度全课表精确校验逻辑
    // =========================================================================

    private fun assertScheduleState(
        snap: TimetableSnapshot,
        courseAId: Long,
        courseBId: Long,
        isAdjusted: Boolean,
    ) {
        assertEquals("总课程数必须为2", 2, snap.courses.size)
        assertEquals("课程集合匹配", setOf(courseAId, courseBId), snap.courses.map { it.id }.toSet())

        var occurrencesCount = 0
        for (week in 1..16) {
            val monday = snap.settings.startDate?.let { TimetableRules.mondayOf(it) } ?: start
            val weekMonday = monday.plusWeeks((week - 1).toLong())

            for (dayOffset in 0..6) {
                val date = weekMonday.plusDays(dayOffset.toLong())
                val dayOfWeek = dayOffset + 1
                val occs = TimetableRules.occurrencesOn(snap, date)
                occurrencesCount += occs.size

                when (dayOfWeek) {
                    1 -> {
                        assertEquals("第 $week 周周一必须有且仅有 1 次发生", 1, occs.size)
                        val occ = occs.single()
                        assertEquals(courseBId, occ.course.id)
                        assertEquals(setOf(1, 2), occ.effectiveSections)
                        assertEquals(period1And2Ranges, occ.timeRanges)
                    }
                    5 -> {
                        assertTrue("第 $week 周周五必须有发生", occs.isNotEmpty())
                        assertTrue("第 $week 周周五所有发生必须是课程 A", occs.all { it.course.id == courseAId })
                        if (week == 10 && isAdjusted) {
                            val combinedRanges = occs.flatMap { it.timeRanges }
                            assertEquals("第10周已调课时钟必须为 5-6 节", period5And6Ranges, combinedRanges)
                            assertEquals("第10周已调课按节拆分为 2 条 EXTRA 发生", 2, occs.size)
                        } else {
                            assertEquals("未调课周五必须恰好 1 次发生", 1, occs.size)
                            val occ = occs.single()
                            assertEquals("未调课必须为 3-4 节", setOf(3, 4), occ.effectiveSections)
                            assertEquals("未调课时钟必须为 3-4 节", period3And4Ranges, occ.timeRanges)
                        }
                    }
                    else -> {
                        assertEquals("其余工作日或周末无发生", 0, occs.size)
                    }
                }
            }
        }
        val expectedOccurrences = if (isAdjusted) 33 else 32
        assertEquals("全课表发生总数严格守恒", expectedOccurrences, occurrencesCount)
    }

    // =========================================================================
    // 辅助方法
    // =========================================================================

    private fun profileIdFor(runId: String): String {
        require(runId.matches(Regex("^[A-Za-z0-9_-]{1,40}$"))) {
            "runId 格式非法，必须匹配 ^[A-Za-z0-9_-]{1,40}$: $runId"
        }
        return "s503_persist_$runId"
    }

    private fun checkpointFile(runId: String): File {
        require(runId.matches(Regex("^[A-Za-z0-9_-]{1,40}$"))) {
            "runId 格式非法，必须匹配 ^[A-Za-z0-9_-]{1,40}$: $runId"
        }
        return File(context.filesDir, "s503_checkpoint_$runId.txt")
    }

    private fun parseCheckpoint(file: File): Map<String, String> =
        file.readLines(Charsets.UTF_8)
            .filter { it.contains('=') }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }

    private suspend fun snapshotOf(schedules: RoomScheduleRepository): TimetableSnapshot =
        withTimeout(5_000) { schedules.observeTimetable().first() }

    private suspend fun currentTarget(
        schedules: RoomScheduleRepository,
        db: TimetableDatabase,
    ): ImportTarget {
        val snap = snapshotOf(schedules)
        return ImportTarget(snap.scopeToken, 1, snap.revision, db.openHelper.readableDatabase.version)
    }

    private fun parseIntent(text: String): com.lingion.sleepy.domain.notice.NoticeIntent {
        val basis = NoticeDateBasis(referenceDate = LocalDate.of(2026, 11, 9), termMonday = start, totalWeeks = 16)
        val parsed = NoticeIntentParser.parse(text, NoticeBasis(basis)).single()
        assertTrue("意图解析应 Ready：$parsed", parsed is NoticeIntentResult.Ready)
        return (parsed as NoticeIntentResult.Ready).intent
    }

    private suspend fun confirmNotice(
        repository: RoomNoticeAdjustmentRepository,
        schedules: RoomScheduleRepository,
        db: TimetableDatabase,
        intent: com.lingion.sleepy.domain.notice.NoticeIntent,
    ): com.lingion.sleepy.domain.notice.NoticeAdjustmentConfirmation {
        val t = currentTarget(schedules, db)
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

    private suspend fun buildReviewedImport(
        schedules: RoomScheduleRepository,
        db: TimetableDatabase,
        mode: ImportMode,
        vararg courses: Pair<String, CourseMeeting>,
    ): ReviewedImport {
        val drafts = courses.map { (name, meeting) ->
            val excerpts = listOf(
                SourceExcerpt(SourcePosition(1, TextBox(1f, 2f, 3f, 4f)), "synthetic s503 persist $name")
            )
            RichCourseDraft(
                name,
                meetings = listOf(
                    RichMeetingDraft(
                        kind = MeetingKind.RECURRING,
                        dayOfWeek = meeting.day,
                        weeks = meeting.weeks,
                        sections = meeting.sections,
                        customTime = meeting.customTime?.let { DraftTimeRange(it.start, it.end) },
                        room = meeting.room ?: "P101",
                        sources = excerpts,
                    )
                ),
            )
        }
        val source = FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses = drafts)))
        val target = currentTarget(schedules, db)
        val session = ImportReviewSession.create(
            source,
            ImportReviewContext(target, mode, start, 16, defaultPeriods()),
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

    companion object {
        private const val TAG = "S5-03-CrossProcess"
    }
}

