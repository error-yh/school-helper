/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import com.lingion.sleepy.importing.ocr.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ImportDuplicateAppendReproTest {
    private val monday = LocalDate.of(2026, 9, 7)
    private val periods = defaultPeriods()

    private fun word(t: String, x: Float, y: Float, w: Float = 72f, h: Float = 10f, page: Int = 1) =
        OcrSpan(t, OcrLevel.WORD, 95f, SourcePosition(page, TextBox(x, y, x + w, y + h)))

    private fun dateRows(count: Int = 2, xOffset: Float = 0f, includeExtraRow: Boolean = false): List<OcrSpan> {
        val out = mutableListOf<OcrSpan>()
        listOf("日期", "星期", "周次", "节次", "时间", "课程名称", "备注", "实验项目", "实验室", "班组").forEachIndexed { i, s ->
            out += word(s, i * 140f + xOffset, 20f)
        }
        val totalCount = if (includeExtraRow) count + 1 else count
        repeat(totalCount) { i ->
            val date = LocalDate.of(2026, 10, 24).plusDays((i * 7).toLong())
            val fields = listOf(
                date.toString().replace("-", ""), "${date.dayOfWeek.value}", "7,8", "1,2", "08:00-09:40",
                "Hadoop大数据实验", "普通备注", "MapReduce编程$i", "信工楼401", "第${i + 1}组"
            )
            fields.forEachIndexed { j, s -> out += word(s, j * 140f + xOffset, 60f + i * 28f, 110f) }
        }
        return out
    }

    private fun parseOcr(spans: List<OcrSpan>, sourceId: String): OcrLayoutParseResult {
        val p = PositionedPage(1, 1500f, 2000f, emptyList())
        val page = OcrLayoutPage(1, p.width, p.height, listOf(OcrVariantEvidence(OcrVariantId.ORIGINAL, 1500, 2000, PageAffineTransform.IDENTITY, p, spans, emptySet(), null, OcrWordQuality.EMPTY, true)), OcrVariantId.ORIGINAL)
        val ctx = OcrLayoutContext(FormatParseContext(sourceId, 20), confirmedTermMonday = monday, referenceDate = monday)
        return OcrTimetableParser().parse(OcrLayoutDocument(listOf(page)), ctx)
    }

    /**
     * Codex R1-01 反例闭环验证：
     * 同名、无代码的多行实验在 OCR 形成独立课程条目入库后，
     * 再次以来源ID改变（uuid-second）且坐标微抖（0.5px）追加导入，
     * 必须判定为 unchanged 且零写入，彻底阻止同名实验重复追加。
     */
    @Test
    fun reproDuplicateImageAppendWithSlightJitterOrDifferentSourceId() {
        val snap0 = TimetableSnapshot(SemesterSettings(1, "Term", monday, 20), periods, emptyList(), 1, "token")
        val target0 = ImportTarget("token", 1, 1, 3)

        // 第一次导入：sourceId = "uuid-first", 无抖动
        val parsed1 = parseOcr(dateRows(2, 0f), "uuid-first")
        assertNotNull(parsed1.formatResult.draft)
        assertEquals("Must preserve separate same-title OCR rows without explicit code", 2, parsed1.formatResult.draft!!.courses.size)
        val source1 = FrozenImportSource.fromOcr(parsed1)
        val session1 = ImportReviewSession.create(source1, ImportReviewContext(target0, ImportMode.APPEND, monday, 20, periods))

        val selectedMeetings1 = session1.selectedMeetings
        val archives1 = selectedMeetings1.groupBy { it.courseIndex!! }.values.map { IdentityArchive(it.toSet()) }
        val identityReview1 = session1.reviewIdentity(
            if (archives1.size == 1) IdentityReviewKind.MERGE else IdentityReviewKind.INDEPENDENT,
            archives1
        )
        val decisions1 = ImportReviewDecisions(
            evidence = session1.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { session1.reviewEvidence(it.fingerprint) },
            identity = identityReview1
        )
        val reviewed1 = (RichDraftConverter.convert(session1, decisions1) as ImportReviewResult.Ready).reviewed
        val plan1 = ImportOccurrencePlanner.plan(reviewed1, LoadedImportTarget(snap0, 3))
        assertTrue("plan1 must be ready: ${plan1.problems}", plan1.ready)
        assertFalse("plan1 is first import, not unchanged", plan1.unchanged)

        // 入库
        var nextMeetingId = 1L
        val savedCourses = plan1.courses.mapIndexed { index, p ->
            val cid = (index + 1).toLong()
            val meetings = p.draft.meetings.map { it.copy(id = nextMeetingId++) }
            ScheduledCourse(cid, p.draft.courseName, p.draft.teacher, p.draft.room, p.draft.color, meetings, p.draft.sourceCourseCode, p.draft.sourceClassCode)
        }
        val snap1 = snap0.copy(courses = savedCourses, revision = 2)
        val courseIds = plan1.courses.mapIndexed { i, p -> p.key to savedCourses[i].id }.toMap()
        val states = plan1.courses.mapIndexed { index, p ->
            val course = savedCourses[index]
            val fragmentMap = p.fragments.mapIndexed { fi, f -> f.key to course.meetings[fi].id }.toMap()
            course.id to ImportNormalizationCodec.hydrate(p, course, snap1.scopeToken, snap1.settings.id, snap1, fragmentMap, emptyMap(), courseIds)
        }.toMap()

        val loadedTarget = LoadedImportTarget(snap1, 3, states)

        // 第二次导入：同一图片再次追加（重新扫描有随机 sourceId = "uuid-second"，且 OCR 坐标有 0.5f 微小抖动）
        val parsed2 = parseOcr(dateRows(2, 0.5f), "uuid-second")
        val source2 = FrozenImportSource.fromOcr(parsed2)
        val target1 = ImportTarget(snap1.scopeToken, 1, 2, 3)
        val session2 = ImportReviewSession.create(source2, ImportReviewContext(target1, ImportMode.APPEND, monday, 20, periods))
        val selectedMeetings2 = session2.selectedMeetings
        val archives2 = selectedMeetings2.groupBy { it.courseIndex!! }.values.map { IdentityArchive(it.toSet()) }
        val identityReview2 = session2.reviewIdentity(
            if (archives2.size == 1) IdentityReviewKind.MERGE else IdentityReviewKind.INDEPENDENT,
            archives2
        )
        val decisions2 = ImportReviewDecisions(
            evidence = session2.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { session2.reviewEvidence(it.fingerprint) },
            identity = identityReview2,
            sourceRelation = session2.reviewSourceRelation(SourceRelationChoice.DIFFERENT_SOURCE)
        )
        val reviewed2 = (RichDraftConverter.convert(session2, decisions2) as ImportReviewResult.Ready).reviewed
        val plan2 = ImportOccurrencePlanner.plan(reviewed2, loadedTarget)

        assertTrue("plan2 must be ready: ${plan2.problems}", plan2.ready)
        assertTrue("plan2 must be unchanged when reimporting existing arrangements: ${plan2.courses}", plan2.unchanged)
        assertTrue("plan2 must not write new courses", plan2.courses.isEmpty())
    }

    /**
     * 8 行真实多日期同名实验课验证：
     * 1. 8行全量重复导入时，判定为 unchanged 且零写入，全部 8 次不同日期/实验项目均不重复；
     * 2. 混合批次（8行旧安排 + 1行新日期实验安排）追加时，前8行精确去重，仅新增第9行安排。
     */
    @Test
    fun eightRowsSameTitleExperimentsAppendIdempotentAndMixedBatchOnlyAddsNew() {
        val snap0 = TimetableSnapshot(SemesterSettings(1, "Term", monday, 20), periods, emptyList(), 1, "token")
        val target0 = ImportTarget("token", 1, 1, 3)

        // 第一次导入：8行同名无代码多日期实验
        val parsed1 = parseOcr(dateRows(8, 0f), "uuid-8rows-first")
        assertEquals(8, parsed1.formatResult.draft!!.courses.size)
        val source1 = FrozenImportSource.fromOcr(parsed1)
        val session1 = ImportReviewSession.create(source1, ImportReviewContext(target0, ImportMode.APPEND, monday, 20, periods))
        val selected1 = session1.selectedMeetings
        val archives1 = selected1.groupBy { it.courseIndex!! }.values.map { IdentityArchive(it.toSet()) }
        val identityReview1 = session1.reviewIdentity(IdentityReviewKind.INDEPENDENT, archives1)
        val decisions1 = ImportReviewDecisions(
            evidence = session1.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { session1.reviewEvidence(it.fingerprint) },
            identity = identityReview1
        )
        val reviewed1 = (RichDraftConverter.convert(session1, decisions1) as ImportReviewResult.Ready).reviewed
        val plan1 = ImportOccurrencePlanner.plan(reviewed1, LoadedImportTarget(snap0, 3))
        assertTrue(plan1.ready)
        assertEquals(8, plan1.courses.size)

        // 入库保存
        var nextMeetingId = 100L
        val savedCourses = plan1.courses.mapIndexed { index, p ->
            val cid = (index + 1).toLong()
            val meetings = p.draft.meetings.map { it.copy(id = nextMeetingId++) }
            ScheduledCourse(cid, p.draft.courseName, p.draft.teacher, p.draft.room, p.draft.color, meetings, p.draft.sourceCourseCode, p.draft.sourceClassCode)
        }
        val snap1 = snap0.copy(courses = savedCourses, revision = 2)
        val courseIds = plan1.courses.mapIndexed { i, p -> p.key to savedCourses[i].id }.toMap()
        val states = plan1.courses.mapIndexed { index, p ->
            val course = savedCourses[index]
            val fragmentMap = p.fragments.mapIndexed { fi, f -> f.key to course.meetings[fi].id }.toMap()
            course.id to ImportNormalizationCodec.hydrate(p, course, snap1.scopeToken, snap1.settings.id, snap1, fragmentMap, emptyMap(), courseIds)
        }.toMap()

        val loadedTarget = LoadedImportTarget(snap1, 3, states)

        // 验证 1：8行全量重复重导（来源改变 + 坐标 0.5f 抖动）
        val parsedRepeat = parseOcr(dateRows(8, 0.5f), "uuid-8rows-second")
        val sourceRepeat = FrozenImportSource.fromOcr(parsedRepeat)
        val target1 = ImportTarget(snap1.scopeToken, 1, 2, 3)
        val sessionRepeat = ImportReviewSession.create(sourceRepeat, ImportReviewContext(target1, ImportMode.APPEND, monday, 20, periods))
        val selectedRepeat = sessionRepeat.selectedMeetings
        val archivesRepeat = selectedRepeat.groupBy { it.courseIndex!! }.values.map { IdentityArchive(it.toSet()) }
        val identityRepeat = sessionRepeat.reviewIdentity(IdentityReviewKind.INDEPENDENT, archivesRepeat)
        val decisionsRepeat = ImportReviewDecisions(
            evidence = sessionRepeat.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { sessionRepeat.reviewEvidence(it.fingerprint) },
            identity = identityRepeat,
            sourceRelation = sessionRepeat.reviewSourceRelation(SourceRelationChoice.DIFFERENT_SOURCE)
        )
        val reviewedRepeat = (RichDraftConverter.convert(sessionRepeat, decisionsRepeat) as ImportReviewResult.Ready).reviewed
        val planRepeat = ImportOccurrencePlanner.plan(reviewedRepeat, loadedTarget)

        assertTrue("全量8行重复必须 ready: ${planRepeat.problems}", planRepeat.ready)
        assertTrue("全量8行重复必须 unchanged: ${planRepeat.courses}", planRepeat.unchanged)
        assertTrue("全量8行重复绝不增加任何课程", planRepeat.courses.isEmpty())

        // 验证 2：混合批次（前8行相同，新增第9行新日期新项目）
        val parsedMixed = parseOcr(dateRows(8, 0f, includeExtraRow = true), "uuid-8rows-mixed")
        assertEquals(9, parsedMixed.formatResult.draft!!.courses.size)
        val sourceMixed = FrozenImportSource.fromOcr(parsedMixed)
        val sessionMixed = ImportReviewSession.create(sourceMixed, ImportReviewContext(target1, ImportMode.APPEND, monday, 20, periods))
        val selectedMixed = sessionMixed.selectedMeetings
        val archivesMixed = selectedMixed.groupBy { it.courseIndex!! }.values.map { IdentityArchive(it.toSet()) }
        val identityMixed = sessionMixed.reviewIdentity(IdentityReviewKind.INDEPENDENT, archivesMixed)
        val decisionsMixed = ImportReviewDecisions(
            evidence = sessionMixed.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { sessionMixed.reviewEvidence(it.fingerprint) },
            identity = identityMixed,
            sourceRelation = sessionMixed.reviewSourceRelation(SourceRelationChoice.DIFFERENT_SOURCE)
        )
        val reviewedMixed = (RichDraftConverter.convert(sessionMixed, decisionsMixed) as ImportReviewResult.Ready).reviewed
        val planMixed = ImportOccurrencePlanner.plan(reviewedMixed, loadedTarget)

        assertTrue("混合批次必须 ready: ${planMixed.problems}", planMixed.ready)
        assertFalse("包含新日期安排，所以不是 unchanged", planMixed.unchanged)
        // 关键断言：仅新增了第9行新安排，前8行旧安排被精确去重！
        assertEquals("仅有1门新课程被计划写入", 1, planMixed.courses.size)
        val plannedNew = planMixed.courses.single()
        val plannedProject = plannedNew.draft.meetings.single().experimentProject
        assertEquals("新增的安排必须是第9项实验", "MapReduce编程8", plannedProject)
    }

    /**
     * Codex R2-01 / P1 反例闭环验证：
     * 当多个独立归属（Course 1 与 Course 2）都具有同一逻辑原始安排，
     * 但无唯一谱系或显式绑定时，规划器绝不能任选列表第一项去修改/扩展已有档案。
     */
    @Test
    fun independentInputMustNotRestoreAnArbitraryAmbiguousStoredOwner() {
        val full = TimeRange(LocalTime.parse("08:00"), LocalTime.parse("08:45"))
        val half = TimeRange(LocalTime.parse("08:00"), LocalTime.parse("08:30"))
        val original = CourseMeeting(day = 1, weeks = setOf(1), sections = setOf(1), teacher = "T", room = "R")
        val identity = OccurrenceIdentity("Same title", null, null, "T", "R", null, null, false)
        val courses = (1L..2L).map { id -> ScheduledCourse(id, "Same title", "T", "R", 9, listOf(original.copy(id = 200 + id, sections = emptySet(), customTime = half))) }
        val snapshot = TimetableSnapshot(SemesterSettings(startDate = monday), defaultPeriods(), courses, 7, "local")
        val states = courses.associate { c ->
            val family = c.id.toString().repeat(64)
            val o = LogicalOriginal(family, ImportOccurrencePlanner.descriptorFingerprint(original, identity, listOf(full), true), original, identity, emptySet(), listOf(DatedInterval(monday, half)), listOf(full))
            val fragment = OwnedFragment((c.id + 2).toString().repeat(64), family, c.meetings.single().id, ImportNormalizationCodec.meetingFingerprint(c.meetings.single()))
            val state = StoredNormalizationState(1, "local", 1, c.id, PlannedNormalizationState(listOf(o), emptyList(), emptyList(), emptyList(), emptyList()), listOf(fragment), emptyList(), emptyList())
            ImportNormalizationCodec.validate(state, "local", 1, c, snapshot)
            c.id to state
        }
        val draft = RichCourseDraft("Same title", meetings = listOf(RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING, dayOfWeek = 1, weeks = setOf(1), sections = setOf(1), teacher = "T", room = "R")))
        val session = ImportReviewSession.create(
            FrozenImportSource.fromOcr(OcrLayoutParseResult(FormatParseResult(RichTimetableDraft(courses = listOf(draft))), OcrLayoutDocument(emptyList()))),
            ImportReviewContext(ImportTarget("local", 1, 7, 5), termMonday = monday, targetPeriods = snapshot.periods)
        )
        val decision = ImportReviewDecisions(
            evidence = session.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },
            identity = session.reviewIdentity(IdentityReviewKind.INDEPENDENT, listOf(IdentityArchive(setOf(ReviewLocator(courseIndex = 0, meetingIndex = 0)))))
        )
        val conversion = RichDraftConverter.convert(session, decision)
        assertTrue(conversion.toString(), conversion is ImportReviewResult.Ready)
        val plan = ImportOccurrencePlanner.plan((conversion as ImportReviewResult.Ready).reviewed, LoadedImportTarget(snapshot, 5, states))
        assertTrue("No lineage or explicit binding authorizes restoring either ambiguous stored owner", plan.courses.none { it.existingCourseId != null })
    }

    /**
     * Codex R3-01 / P1 反例闭环验证：
     * 当已有单一已存课程且部分覆盖（08:00-08:30），第三方独立输入要求完整时间（08:00-08:45）时，
     * 在无谱系/无显式绑定授权下，规划器绝不能静默丢弃未覆盖的 08:30-08:45，
     * 也不能擅改已有档案，未被覆盖的部分必须保留为待核对的新增残余（plan.unchanged = false）。
     */
    @Test
    fun unownedUniquePartialMustNotSilentlyDiscardUncoveredIncomingTime() {
        val full = TimeRange(LocalTime.parse("08:00"), LocalTime.parse("08:45"))
        val half = TimeRange(LocalTime.parse("08:00"), LocalTime.parse("08:30"))
        val original = CourseMeeting(day = 1, weeks = setOf(1), sections = setOf(1), teacher = "T", room = "R")
        val identity = OccurrenceIdentity("Same title", null, null, "T", "R", null, null, false)
        val courses = (1L..1L).map { id -> ScheduledCourse(id, "Same title", "T", "R", 9, listOf(original.copy(id = 200 + id, sections = emptySet(), customTime = half))) }
        val snapshot = TimetableSnapshot(SemesterSettings(startDate = monday), defaultPeriods(), courses, 7, "local")
        val states = courses.associate { c ->
            val family = c.id.toString().repeat(64)
            val o = LogicalOriginal(family, ImportOccurrencePlanner.descriptorFingerprint(original, identity, listOf(full), true), original, identity, emptySet(), listOf(DatedInterval(monday, half)), listOf(full))
            val fragment = OwnedFragment((c.id + 2).toString().repeat(64), family, c.meetings.single().id, ImportNormalizationCodec.meetingFingerprint(c.meetings.single()))
            val state = StoredNormalizationState(1, "local", 1, c.id, PlannedNormalizationState(listOf(o), emptyList(), emptyList(), emptyList(), emptyList()), listOf(fragment), emptyList(), emptyList())
            ImportNormalizationCodec.validate(state, "local", 1, c, snapshot)
            c.id to state
        }
        val draft = RichCourseDraft("Same title", meetings = listOf(RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING, dayOfWeek = 1, weeks = setOf(1), sections = setOf(1), teacher = "T", room = "R")))
        val session = ImportReviewSession.create(
            FrozenImportSource.fromOcr(OcrLayoutParseResult(FormatParseResult(RichTimetableDraft(courses = listOf(draft))), OcrLayoutDocument(emptyList()))),
            ImportReviewContext(ImportTarget("local", 1, 7, 5), termMonday = monday, targetPeriods = snapshot.periods)
        )
        val decision = ImportReviewDecisions(
            evidence = session.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },
            identity = session.reviewIdentity(IdentityReviewKind.INDEPENDENT, listOf(IdentityArchive(setOf(ReviewLocator(courseIndex = 0, meetingIndex = 0)))))
        )
        val conversion = RichDraftConverter.convert(session, decision)
        assertTrue(conversion.toString(), conversion is ImportReviewResult.Ready)
        val plan = ImportOccurrencePlanner.plan((conversion as ImportReviewResult.Ready).reviewed, LoadedImportTarget(snapshot, 5, states))
        assertTrue("No lineage authorizes rewriting existing owner", plan.courses.none { it.existingCourseId != null })
        assertFalse("Incoming uncovered 08:30-08:45 must not be silently discarded", plan.unchanged)
    }
}
