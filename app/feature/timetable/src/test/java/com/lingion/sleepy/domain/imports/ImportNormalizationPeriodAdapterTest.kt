/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ImportNormalizationPeriodAdapterTest {
    private val monday = LocalDate.parse("2026-09-07")
    private val originalMeeting = CourseMeeting(
        id = 10,
        day = 1,
        weeks = setOf(1, 2),
        sections = setOf(1, 2, 3),
        provenance = listOf(ScheduleProvenance(rawText = "original", sourceLocator = "row:2"))
    )

    private fun initialSnapshot(): TimetableSnapshot = TimetableSnapshot(
        SemesterSettings(startDate = monday),
        defaultPeriods(),
        listOf(ScheduledCourse(1, "Course", "Teacher", "Room", 9, listOf(originalMeeting))),
        7,
        "local"
    )

    private fun reviewed(
        s: TimetableSnapshot,
        ref: LogicalBaseReference.StoredLogicalBase,
        kind: RichExceptionKind = RichExceptionKind.CANCEL,
        sections: Set<Int>? = setOf(2),
        target: LocalDate? = null,
        targetTime: DraftTimeRange? = null
    ): ReviewedImport {
        val raw = RichExceptionDraft(
            kind = kind,
            originalDate = if (kind == RichExceptionKind.EXTRA) null else monday,
            targetDate = target,
            customTime = targetTime,
            sections = sections
        )
        val source = FrozenImportSource.fromFormat(
            FormatParseResult(RichTimetableDraft(), pendingExceptions = listOf(PendingExceptionAttachment(exception = raw, reason = "test")))
        )
        val session = ImportReviewSession.create(
            source,
            ImportReviewContext(ImportTarget(s.scopeToken, s.settings.id, s.revision, 3), termMonday = monday, targetPeriods = s.periods)
        )
        val decisions = ImportReviewDecisions(
            evidence = session.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },
            attachments = listOf(session.attachException(ReviewLocator(pendingIndex = 0), ExceptionAttachment.Bound(ref)))
        )
        val ready = RichDraftConverter.convert(session, decisions)
        assertTrue(ready.toString(), ready is ImportReviewResult.Ready)
        return (ready as ImportReviewResult.Ready).reviewed
    }

    private data class Realized(val snapshot: TimetableSnapshot, val state: StoredNormalizationState, val plan: PlannedCourse)

    private fun realize(
        s: TimetableSnapshot,
        p: PlannedCourse,
        newId: Long = 1,
        plannedCourseIds: Map<String, Long> = emptyMap()
    ): Realized {
        val fragmentIds = p.fragments.mapIndexed { i, f -> f.key to i + 100L }.toMap()
        var exceptionId = 500L
        val addIds = mutableMapOf<String, Long>()
        val meetings = p.fragments.map { f ->
            f.meeting.copy(
                id = fragmentIds.getValue(f.key),
                exceptions = f.meeting.exceptions.map { e ->
                    val id = exceptionId++
                    if (e.kind == ExceptionKind.EXTRA) {
                        val add = p.state.adds.single {
                            it.ownerFragmentKey == f.key && it.scope.date == e.targetDate &&
                                it.scope.range == e.customTime && it.identity.teacher == e.teacher &&
                                it.identity.room == e.room && it.identity.group == e.group &&
                                it.identity.project == e.experimentProject
                        }
                        addIds[add.exceptionKey] = id
                    }
                    e.copy(id = id)
                }
            )
        }
        val c = ScheduledCourse(
            p.existingCourseId ?: newId,
            p.draft.courseName,
            p.draft.teacher,
            p.draft.room,
            p.draft.color,
            meetings,
            p.draft.sourceCourseCode,
            p.draft.sourceClassCode,
            p.draft.provenance
        )
        val snapshot = s.copy(courses = s.courses.filter { it.id != c.id } + c)
        return Realized(snapshot, ImportNormalizationCodec.hydrate(p, c, s.scopeToken, s.settings.id, snapshot, fragmentIds, addIds, plannedCourseIds), p)
    }

    private fun partialWithCancel(): Realized {
        val s = initialSnapshot()
        val target = LoadedImportTarget(s, 3)
        val ref = ImportOccurrencePlanner.logicalBases(target).single()
        val p = ImportOccurrencePlanner.plan(reviewed(s, ref), target)
        assertTrue(p.problems.toString(), p.ready)
        return realize(s, p.courses.single())
    }

    @Test
    fun oldStateIsValidUnderOldPeriodsButFailsClosedUnderNewPeriods() {
        val realized = partialWithCancel()
        val course = realized.snapshot.courses.single()

        // 1. 证明旧状态在旧作息下完全合法，绝非旧状态损坏
        ImportNormalizationCodec.validate(
            realized.state,
            realized.snapshot.scopeToken,
            realized.snapshot.settings.id,
            course,
            realized.snapshot
        )

        // 2. 模拟合法新作息变更：第1-3节整体后移5分钟
        val newPeriods = realized.snapshot.periods.map { period ->
            when (period.number) {
                1 -> Period(1, LocalTime.of(8, 5), LocalTime.of(8, 50))
                2 -> Period(2, LocalTime.of(9, 0), LocalTime.of(9, 45))
                3 -> Period(3, LocalTime.of(10, 5), LocalTime.of(10, 50))
                else -> period
            }
        }
        val candidate = realized.snapshot.copy(periods = newPeriods)

        // 3. 证明红测：如果不做协调直接用 candidate 校验旧 state，必抛出 InvalidNormalizationState
        val error = assertThrows(InvalidNormalizationState::class.java) {
            ImportNormalizationCodec.validate(
                realized.state,
                candidate.scopeToken,
                candidate.settings.id,
                course,
                candidate
            )
        }
        assertEquals("INVALID_NORMALIZATION_STATE", error.message)
    }

    @Test
    fun adapterSuccessfullyReconcilesUniform45MinutePeriods() {
        val realized = partialWithCancel()
        val course = realized.snapshot.courses.single()
        val oldPeriods = realized.snapshot.periods

        // 统一45分钟新时间表（例如上午推迟半小时上课）
        val newPeriods = oldPeriods.map { period ->
            when (period.number) {
                1 -> Period(1, LocalTime.of(8, 30), LocalTime.of(9, 15))
                2 -> Period(2, LocalTime.of(9, 25), LocalTime.of(10, 10))
                3 -> Period(3, LocalTime.of(10, 30), LocalTime.of(11, 15))
                4 -> Period(4, LocalTime.of(11, 25), LocalTime.of(12, 10))
                else -> period
            }
        }
        val candidate = realized.snapshot.copy(periods = newPeriods)

        val adapted = ImportNormalizationPeriodAdapter.adapt(
            realized.state,
            oldPeriods,
            newPeriods,
            course,
            candidate
        )

        // 验证结构与内容
        assertEquals(realized.state.version, adapted.version)
        assertEquals(realized.state.scopeToken, adapted.scopeToken)
        assertEquals(realized.state.courseId, adapted.courseId)
        val adaptedOriginal = adapted.graph.originals.single()
        assertEquals(
            listOf(
                TimeRange(LocalTime.of(8, 30), LocalTime.of(9, 15)),
                TimeRange(LocalTime.of(9, 25), LocalTime.of(10, 10)),
                TimeRange(LocalTime.of(10, 30), LocalTime.of(11, 15)),
            ),
            adaptedOriginal.intervals
        )
        // 编码为 JSON 后再解码必须完全一致，证明保存重开自洽
        val encoded = ImportNormalizationCodec.encode(adapted)
        val decoded = ImportNormalizationCodec.decode(encoded, candidate.scopeToken, candidate.settings.id, course, candidate)
        assertEquals(adapted, decoded)
    }

    @Test
    fun adapterSuccessfullyReconcilesIndependentDifferentDurations() {
        val realized = partialWithCancel()
        val course = realized.snapshot.courses.single()
        val oldPeriods = realized.snapshot.periods

        // 独立不同课时时长：第1节40分钟，第2节45分钟，第3节50分钟
        val newPeriods = oldPeriods.map { period ->
            when (period.number) {
                1 -> Period(1, LocalTime.of(8, 0), LocalTime.of(8, 40))
                2 -> Period(2, LocalTime.of(8, 50), LocalTime.of(9, 35))
                3 -> Period(3, LocalTime.of(9, 45), LocalTime.of(10, 35))
                else -> period
            }
        }
        val candidate = realized.snapshot.copy(periods = newPeriods)

        val adapted = ImportNormalizationPeriodAdapter.adapt(
            realized.state,
            oldPeriods,
            newPeriods,
            course,
            candidate
        )

        val adaptedOriginal = adapted.graph.originals.single()
        assertEquals(
            listOf(
                TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 40)),
                TimeRange(LocalTime.of(8, 50), LocalTime.of(9, 35)),
                TimeRange(LocalTime.of(9, 45), LocalTime.of(10, 35)),
            ),
            adaptedOriginal.intervals
        )
        val encoded = ImportNormalizationCodec.encode(adapted)
        val decoded = ImportNormalizationCodec.decode(encoded, candidate.scopeToken, candidate.settings.id, course, candidate)
        assertEquals(adapted, decoded)
    }

    @Test
    fun adapterSupportsContiguousPeriodsWithoutBreak() {
        val realized = partialWithCancel()
        val course = realized.snapshot.courses.single()
        val oldPeriods = realized.snapshot.periods

        // 相邻节次无缝衔接（如前一节 10:55 结束，后一节 10:55 开始，课间 0 分钟）
        val newPeriods = oldPeriods.map { period ->
            when (period.number) {
                1 -> Period(1, LocalTime.of(8, 0), LocalTime.of(8, 45))
                2 -> Period(2, LocalTime.of(8, 45), LocalTime.of(9, 30))
                3 -> Period(3, LocalTime.of(9, 30), LocalTime.of(10, 15))
                else -> period
            }
        }
        // 先确保 TimetableRules 确认该候选作息合法
        assertTrue(TimetableRules.validatePeriods(newPeriods).isEmpty())
        val candidate = realized.snapshot.copy(periods = newPeriods)

        val adapted = ImportNormalizationPeriodAdapter.adapt(
            realized.state,
            oldPeriods,
            newPeriods,
            course,
            candidate
        )

        val encoded = ImportNormalizationCodec.encode(adapted)
        val decoded = ImportNormalizationCodec.decode(encoded, candidate.scopeToken, candidate.settings.id, course, candidate)
        assertEquals(adapted, decoded)
    }

    @Test
    fun adapterSupportsAddingAndRemovingUnreferencedPeriods() {
        val realized = partialWithCancel()
        val course = realized.snapshot.courses.single()
        val oldPeriods = realized.snapshot.periods

        // 增加第13节（晚自习第三节 22:40-23:25），删除未被课程引用的第12节
        val newPeriods = oldPeriods.filter { it.number != 12 } + listOf(
            Period(13, LocalTime.of(22, 40), LocalTime.of(23, 25))
        )
        assertTrue(TimetableRules.validatePeriods(newPeriods).isEmpty())
        val candidate = realized.snapshot.copy(periods = newPeriods)

        val adapted = ImportNormalizationPeriodAdapter.adapt(
            realized.state,
            oldPeriods,
            newPeriods,
            course,
            candidate
        )

        val encoded = ImportNormalizationCodec.encode(adapted)
        val decoded = ImportNormalizationCodec.decode(encoded, candidate.scopeToken, candidate.settings.id, course, candidate)
        assertEquals(adapted, decoded)
    }

    @Test
    fun adapterPreservesCustomTimeUnmodified() {
        // 创建带有明确 customTime 的导入课程（sectionsUseTarget == true, customTime != null, sections == emptySet）
        val customMeeting = CourseMeeting(
            id = 20,
            day = 2,
            weeks = setOf(1, 2),
            sections = emptySet(),
            customTime = TimeRange(LocalTime.of(19, 0), LocalTime.of(20, 30)),
            provenance = listOf(ScheduleProvenance(rawText = "custom meeting", sourceLocator = "row:3"))
        )
        val snapshot = TimetableSnapshot(
            SemesterSettings(startDate = monday),
            defaultPeriods(),
            listOf(ScheduledCourse(2, "CustomCourse", "Teacher", "Room", 9, listOf(customMeeting))),
            7,
            "local"
        )
        val originalMeetingDef = customMeeting.copy(id = 0)
        val original = LogicalOriginal(
            familyId = "0".repeat(64),
            descriptorFingerprint = ImportOccurrencePlanner.descriptorFingerprint(
                originalMeetingDef,
                OccurrenceIdentity("CustomCourse", null, null, "Teacher", "Room", null, null, false),
                listOf(customMeeting.customTime!!),
                sectionsUseTarget = true
            ),
            original = originalMeetingDef,
            identity = OccurrenceIdentity("CustomCourse", null, null, "Teacher", "Room", null, null, false),
            oldMeetingAliases = emptySet(),
            allocated = listOf(
                DatedInterval(monday.plusDays(1), customMeeting.customTime!!),
                DatedInterval(monday.plusWeeks(1).plusDays(1), customMeeting.customTime!!)
            ),
            intervals = listOf(customMeeting.customTime!!),
            sectionsUseTarget = true
        )
        val fragment = OwnedFragment(
            key = "1".repeat(64),
            familyId = original.familyId,
            meetingId = customMeeting.id,
            shapeFingerprint = ImportNormalizationCodec.meetingFingerprint(customMeeting)
        )
        val state = StoredNormalizationState(
            version = 1,
            scopeToken = snapshot.scopeToken,
            semesterId = snapshot.settings.id,
            courseId = 2,
            graph = PlannedNormalizationState(
                originals = listOf(original),
                removes = emptyList(),
                adds = emptyList(),
                groups = emptyList(),
                sources = emptyList()
            ),
            fragments = listOf(fragment),
            ownedAdds = emptyList(),
            sourceReferences = listOf(
                StoredSourceReference(2, customMeeting.id, null, 0, ImportCanonical.digest(customMeeting.provenance.single()))
            )
        )
        ImportNormalizationCodec.validate(state, snapshot.scopeToken, snapshot.settings.id, snapshot.courses.single(), snapshot)

        // 修改作息时间表
        val newPeriods = snapshot.periods.map { Period(it.number, it.start.plusMinutes(15), it.end.plusMinutes(15)) }
        val candidate = snapshot.copy(periods = newPeriods)

        val adapted = ImportNormalizationPeriodAdapter.adapt(
            state,
            snapshot.periods,
            newPeriods,
            snapshot.courses.single(),
            candidate
        )

        // 验证 customTime 保持原样未变
        val adaptedOriginal = adapted.graph.originals.single()
        assertTrue(adaptedOriginal.sectionsUseTarget)
        assertEquals(listOf(TimeRange(LocalTime.of(19, 0), LocalTime.of(20, 30))), adaptedOriginal.intervals)
        assertEquals(original.descriptorFingerprint, adaptedOriginal.descriptorFingerprint)

        val encoded = ImportNormalizationCodec.encode(adapted)
        val decoded = ImportNormalizationCodec.decode(encoded, candidate.scopeToken, candidate.settings.id, snapshot.courses.single(), candidate)
        assertEquals(adapted, decoded)
    }

    @Test
    fun overlappingPeriodsAreRejectedByTimetableRules() {
        val periods = listOf(
            Period(1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
            Period(2, LocalTime.of(8, 30), LocalTime.of(9, 15)) // 重叠
        )
        val errors = TimetableRules.validatePeriods(periods)
        assertEquals(listOf("节次时间不能重叠"), errors)
    }

    @Test
    fun samePeriodsIsNoOpAndPreservesState() {
        val realized = partialWithCancel()
        val course = realized.snapshot.courses.single()
        val adapted = ImportNormalizationPeriodAdapter.adapt(
            realized.state,
            realized.snapshot.periods,
            realized.snapshot.periods,
            course,
            realized.snapshot
        )
        assertSame(realized.state, adapted)
    }

    @Test
    fun adapterPreservesExplicitClockMakeupMeeting() {
        // 构造包含真实 AddEffect / EXTRA 例外的补课/调课事件
        val s = initialSnapshot()
        val target = LoadedImportTarget(s, 3)
        val ref = ImportOccurrencePlanner.logicalBases(target).single()
        val reviewedMakeup = reviewed(
            s,
            ref,
            kind = RichExceptionKind.RESCHEDULE,
            sections = setOf(2),
            target = monday.plusDays(4),
            targetTime = DraftTimeRange(LocalTime.of(16, 0), LocalTime.of(17, 30))
        )
        val p = ImportOccurrencePlanner.plan(reviewedMakeup, target)
        assertTrue(p.problems.toString(), p.ready)
        val realized = realize(s, p.courses.single())
        val course = realized.snapshot.courses.single()
        val oldPeriods = realized.snapshot.periods

        // 验证真实存在 AddEffect 和 ownedAdd，绝非普通无例外课程
        assertTrue("必须具有真实 AddEffect", realized.state.graph.adds.isNotEmpty())
        assertTrue("必须具有真实 ownedAdds", realized.state.ownedAdds.isNotEmpty())
        val initialAdd = realized.state.graph.adds.single()
        assertEquals(TimeRange(LocalTime.of(16, 0), LocalTime.of(17, 30)), initialAdd.scope.range)

        // 证明旧状态在旧作息下完全自洽合法
        ImportNormalizationCodec.validate(
            realized.state,
            realized.snapshot.scopeToken,
            realized.snapshot.settings.id,
            course,
            realized.snapshot
        )

        // 变更作息（全部顺延 10 分钟）
        val newPeriods = oldPeriods.map { Period(it.number, it.start.plusMinutes(10), it.end.plusMinutes(10)) }
        val candidate = realized.snapshot.copy(periods = newPeriods)

        val adapted = ImportNormalizationPeriodAdapter.adapt(
            realized.state,
            oldPeriods,
            newPeriods,
            course,
            candidate
        )

        // 明确时钟保持：AddEffect 与 ownedAdds 的物理时钟不变
        val adaptedAdd = adapted.graph.adds.single()
        assertEquals(TimeRange(LocalTime.of(16, 0), LocalTime.of(17, 30)), adaptedAdd.scope.range)
        assertEquals(realized.state.ownedAdds.size, adapted.ownedAdds.size)
        // 编码解码验证自洽
        val encoded = ImportNormalizationCodec.encode(adapted)
        val decoded = ImportNormalizationCodec.decode(
            encoded, candidate.scopeToken, candidate.settings.id, course, candidate
        )
        assertEquals(adapted, decoded)
    }

    @Test
    fun adapterRejectsPartialClockFragmentWithoutLoss() {
        // 1. 构造在旧作息下合法自洽的 allocated 部分残片（非完整节次时钟）
        val originalDef = CourseMeeting(
            id = 0,
            day = 1,
            weeks = setOf(1),
            sections = setOf(1),
            customTime = null,
            teacher = "Teacher",
            room = "Room",
            provenance = listOf(ScheduleProvenance(rawText = "partial test", sourceLocator = "row:1"))
        )
        val partialMeeting = originalDef.copy(
            id = 201,
            sections = emptySet(),
            customTime = TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 30))
        )
        val partialCourse = ScheduledCourse(2, "PartialAllocatedCourse", "Teacher", "Room", 9, listOf(partialMeeting))
        val snapshot1 = TimetableSnapshot(SemesterSettings(startDate = monday), defaultPeriods(), listOf(partialCourse), 7, "local")
        val original1 = LogicalOriginal(
            familyId = "1".repeat(64),
            descriptorFingerprint = ImportOccurrencePlanner.descriptorFingerprint(
                originalDef, OccurrenceIdentity("PartialAllocatedCourse", null, null, "Teacher", "Room", null, null, false),
                listOf(TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45))), true
            ),
            original = originalDef,
            identity = OccurrenceIdentity("PartialAllocatedCourse", null, null, "Teacher", "Room", null, null, false),
            oldMeetingAliases = emptySet(),
            allocated = listOf(DatedInterval(monday, TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 30)))),
            intervals = listOf(TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45))),
            sectionsUseTarget = true
        )
        val fragment1 = OwnedFragment(
            key = "2".repeat(64),
            familyId = original1.familyId,
            meetingId = 201,
            shapeFingerprint = ImportNormalizationCodec.meetingFingerprint(partialMeeting)
        )
        val state1 = StoredNormalizationState(
            version = 1,
            scopeToken = snapshot1.scopeToken,
            semesterId = snapshot1.settings.id,
            courseId = 2,
            graph = PlannedNormalizationState(listOf(original1), emptyList(), emptyList(), emptyList(), emptyList()),
            fragments = listOf(fragment1),
            ownedAdds = emptyList(),
            sourceReferences = listOf(StoredSourceReference(2, 201, null, 0, ImportCanonical.digest(partialMeeting.provenance.single())))
        )

        // 明确验证旧状态在旧作息下完全合法自洽通过
        ImportNormalizationCodec.validate(state1, snapshot1.scopeToken, snapshot1.settings.id, partialCourse, snapshot1)

        // 变更作息（顺延10分钟）
        val newPeriods1 = snapshot1.periods.map { Period(it.number, it.start.plusMinutes(10), it.end.plusMinutes(10)) }
        val candidate1 = snapshot1.copy(periods = newPeriods1)

        // 验证因部分残片无法无损映射到完整新作息节次，适配保守拒绝
        val err1 = assertThrows(InvalidNormalizationState::class.java) {
            ImportNormalizationPeriodAdapter.adapt(state1, snapshot1.periods, newPeriods1, partialCourse, candidate1)
        }
        assertEquals("ALLOCATION_INTERVAL_MISMATCH", err1.message)

        // 2. 构造在旧作息下合法自洽的 RemoveEffect 部分残片
        val residualMeeting = originalDef.copy(
            id = 202,
            sections = emptySet(),
            customTime = TimeRange(LocalTime.of(8, 20), LocalTime.of(8, 45)),
            provenance = listOf(ScheduleProvenance(rawText = "residual", sourceLocator = "row:2"))
        )
        val cancelledMeeting = originalDef.copy(
            id = 203,
            sections = emptySet(),
            customTime = TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 20)),
            exceptions = listOf(MeetingException(id = 501, kind = ExceptionKind.CANCEL, originalDate = monday)),
            provenance = listOf(ScheduleProvenance(rawText = "cancelled", sourceLocator = "row:3"))
        )
        val removeCourse = ScheduledCourse(3, "PartialRemoveCourse", "Teacher", "Room", 9, listOf(residualMeeting, cancelledMeeting))
        val snapshot2 = TimetableSnapshot(SemesterSettings(startDate = monday), defaultPeriods(), listOf(removeCourse), 7, "local")
        val original2 = LogicalOriginal(
            familyId = "3".repeat(64),
            descriptorFingerprint = ImportOccurrencePlanner.descriptorFingerprint(
                originalDef, OccurrenceIdentity("PartialRemoveCourse", null, null, "Teacher", "Room", null, null, false),
                listOf(TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45))), true
            ),
            original = originalDef,
            identity = OccurrenceIdentity("PartialRemoveCourse", null, null, "Teacher", "Room", null, null, false),
            oldMeetingAliases = emptySet(),
            allocated = listOf(DatedInterval(monday, TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45)))),
            intervals = listOf(TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45))),
            sectionsUseTarget = true
        )
        val remove2 = RemoveEffect(
            key = "5".repeat(64),
            familyId = original2.familyId,
            scope = DatedInterval(monday, TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 20))),
            provenance = listOf(ScheduleProvenance(rawText = "remove", sourceLocator = "row:2"))
        )
        val fragment2A = OwnedFragment(
            key = "4".repeat(64),
            familyId = original2.familyId,
            meetingId = 202,
            shapeFingerprint = ImportNormalizationCodec.meetingFingerprint(residualMeeting)
        )
        val fragment2B = OwnedFragment(
            key = "6".repeat(64),
            familyId = original2.familyId,
            meetingId = 203,
            shapeFingerprint = ImportNormalizationCodec.meetingFingerprint(cancelledMeeting)
        )
        val group2 = EffectGroup(
            key = "7".repeat(64),
            removeKeys = setOf(remove2.key),
            addKeys = emptySet(),
            fulfilledRemoveKeys = emptySet(),
            witnesses = emptyList()
        )
        val state2 = StoredNormalizationState(
            version = 1,
            scopeToken = snapshot2.scopeToken,
            semesterId = snapshot2.settings.id,
            courseId = 3,
            graph = PlannedNormalizationState(listOf(original2), listOf(remove2), emptyList(), listOf(group2), emptyList()),
            fragments = listOf(fragment2A, fragment2B),
            ownedAdds = emptyList(),
            sourceReferences = listOf(
                StoredSourceReference(3, 202, null, 0, ImportCanonical.digest(residualMeeting.provenance.single())),
                StoredSourceReference(3, 203, null, 0, ImportCanonical.digest(cancelledMeeting.provenance.single()))
            )
        )

        // 明确验证旧状态在旧作息下完全合法自洽通过
        ImportNormalizationCodec.validate(state2, snapshot2.scopeToken, snapshot2.settings.id, removeCourse, snapshot2)

        // 变更作息（顺延10分钟）
        val newPeriods2 = snapshot2.periods.map { Period(it.number, it.start.plusMinutes(10), it.end.plusMinutes(10)) }
        val candidate2 = snapshot2.copy(periods = newPeriods2)

        // 验证因部分残片无法无损映射到完整新作息节次，适配保守拒绝
        val err2 = assertThrows(InvalidNormalizationState::class.java) {
            ImportNormalizationPeriodAdapter.adapt(state2, snapshot2.periods, newPeriods2, removeCourse, candidate2)
        }
        assertEquals("REMOVE_RANGE_MISMATCH", err2.message)
    }
}
