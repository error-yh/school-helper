package com.lingion.sleepy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.model.defaultPeriods
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
import com.lingion.sleepy.domain.repository.ImportApplyResult
import com.lingion.sleepy.data.local.ImportNormalizationStateEntity
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.ImportNormalizationCodec
import com.lingion.sleepy.domain.notice.NoticeAdjustmentApplyResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentCodes
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreviewResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentRequest
import com.lingion.sleepy.domain.notice.NoticeAdjustmentUndoResult
import com.lingion.sleepy.domain.notice.NoticeBasis
import com.lingion.sleepy.domain.notice.NoticeIntentParser
import com.lingion.sleepy.domain.notice.NoticeIntentResult
import com.lingion.sleepy.importing.NoticeDateBasis
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import com.lingion.sleepy.domain.model.ScheduleProvenance
import com.lingion.sleepy.domain.imports.LogicalOriginal
import com.lingion.sleepy.domain.imports.OccurrenceIdentity
import com.lingion.sleepy.domain.imports.DatedInterval
import com.lingion.sleepy.domain.imports.OwnedFragment
import com.lingion.sleepy.domain.imports.StoredNormalizationState
import com.lingion.sleepy.domain.imports.PlannedNormalizationState
import com.lingion.sleepy.domain.imports.StoredSourceReference
import com.lingion.sleepy.domain.imports.ImportCanonical
import com.lingion.sleepy.domain.imports.ImportOccurrencePlanner
import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomScheduleRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val openDatabases = mutableListOf<TimetableDatabase>()
    private val ownedNames = mutableSetOf<String>()

    @After
    fun cleanUpOnlyOwnedSyntheticDatabases() {
        openDatabases.forEach(TimetableDatabase::close)
        ownedNames.forEach(context::deleteDatabase)
    }

    @Test
    fun freshDatabaseInitializesDefaultsAndStableProfileScopeOnce() = runBlocking {
        val profile = newProfile("defaults")
        val repository = RoomScheduleRepository(open(profile))
        val first = snapshot(repository)
        assertEquals(SemesterSettings(), first.settings)
        assertEquals(defaultPeriods(), first.periods)
        assertEquals(0L, first.revision)
        assertEquals(profile, first.scopeToken)
        assertTrue(first.courses.isEmpty())
        assertEquals(first, snapshot(repository))
    }

    @Test
    fun exactSparseMeetingsCustomMinutesOverridesColorAndSettingsSurviveReopen() = runBlocking {
        val profile = newProfile("reopen")
        val db = open(profile)
        val repository = RoomScheduleRepository(db)
        val settings = SemesterSettings(name = " Synthetic term ", startDate = LocalDate.parse("2026-08-31"), totalWeeks = 24)
        val periods = defaultPeriods() + Period(13, time("22:40"), time("23:25"))
        repository.updateSettings(settings, periods)
        val draft = complexDraft()
        val id = repository.saveCourse(draft)
        val saved = snapshot(repository)
        val savedCourse = saved.courses.single()
        assertEquals("Synthetic term", saved.settings.name)
        assertEquals(id, savedCourse.id)
        assertEquals("Synthetic exact course", savedCourse.courseName)
        assertEquals("Teacher", savedCourse.teacher)
        assertEquals("Room", savedCourse.room)
        assertEquals(0xF0123456L, savedCourse.color)
        assertEquals(setOf(1, 3, 8, 20), savedCourse.meetings[0].weeks)
        assertEquals(setOf(1, 3, 13), savedCourse.meetings[0].sections)
        assertEquals("Override teacher", savedCourse.meetings[0].teacher)
        assertEquals("Override room", savedCourse.meetings[0].room)
        assertEquals(TimeRange(time("12:07"), time("12:49")), savedCourse.meetings[1].customTime)
        assertEquals(setOf(99), savedCourse.meetings[1].sections)
        assertEquals("", savedCourse.meetings[1].teacher)
        assertEquals(null, savedCourse.meetings[1].room)
        assertEquals(2, savedCourse.meetings.map { it.id }.distinct().size)
        assertTrue(savedCourse.meetings.all { it.id > 0 })
        assertEquals(2L, saved.revision)
        db.close()
        openDatabases.remove(db)

        val reopened = open(profile)
        assertEquals(saved, snapshot(RoomScheduleRepository(reopened)))
        assertEquals(4, reopened.openHelper.readableDatabase.version)
        assertEquals(0, count(reopened, "courses"))
    }

    @Test
    fun sameNameRowsStayIndependentAndEditDeleteUseOnlyTheExactId() = runBlocking {
        val db = open(newProfile("crud"))
        val repository = RoomScheduleRepository(db)
        val draft = simpleDraft()
        val firstId = repository.saveCourse(draft)
        val secondId = repository.saveCourse(draft)
        assertNotEquals(firstId, secondId)
        assertEquals(2, snapshot(repository).courses.size)
        val replacement = draft.copy(courseName = "Synthetic edited", meetings = listOf(
            CourseMeeting(day = 7, weeks = setOf(2, 4), sections = setOf(5, 7)),
        ))
        assertEquals(firstId, repository.saveCourse(replacement, firstId))
        val afterEdit = snapshot(repository)
        assertEquals(setOf(firstId, secondId), afterEdit.courses.map { it.id }.toSet())
        assertEquals("Synthetic edited", afterEdit.courses.single { it.id == firstId }.courseName)
        assertEquals(draft.courseName, afterEdit.courses.single { it.id == secondId }.courseName)
        assertEquals(setOf(2, 4), afterEdit.courses.single { it.id == firstId }.meetings.single().weeks)
        assertEquals(2, count(db, "course_meetings"))
        repository.deleteCourse(firstId)
        val afterDelete = snapshot(repository)
        assertEquals(listOf(secondId), afterDelete.courses.map { it.id })
        assertEquals(4L, afterDelete.revision)
        assertEquals(1, count(db, "course_meetings"))
        assertEquals(3, count(db, "meeting_weeks"))
        assertEquals(2, count(db, "meeting_sections"))
    }

    @Test
    fun flowEmitsOnlyCompleteSnapshotsAfterCourseSettingsEditAndDeleteTransactions() = runBlocking {
        val repository = RoomScheduleRepository(open(newProfile("flow")))
        val emissions = Channel<TimetableSnapshot>(Channel.UNLIMITED)
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.observeTimetable().collect(emissions::send)
        }
        suspend fun next() = withTimeout(5_000) { emissions.receive() }
        try {
            assertEquals(0L, next().revision)
            val id = repository.saveCourse(simpleDraft())
            val afterInsert = next()
            assertEquals(1L, afterInsert.revision)
            assertEquals(setOf(1, 3, 8), afterInsert.courses.single().meetings.single().weeks)
            assertEquals(setOf(1, 3), afterInsert.courses.single().meetings.single().sections)
            repository.updateSettings(SemesterSettings(name = "Synthetic changed", totalWeeks = 30), defaultPeriods())
            val afterSettings = next()
            assertEquals(2L, afterSettings.revision)
            assertEquals(30, afterSettings.settings.totalWeeks)
            assertEquals(afterInsert.courses, afterSettings.courses)
            repository.saveCourse(simpleDraft().copy(meetings = listOf(
                CourseMeeting(day = 2, weeks = setOf(2, 9), sections = setOf(2, 4)),
                CourseMeeting(day = 4, weeks = setOf(4), sections = emptySet(), customTime = TimeRange(time("12:01"), time("12:31"))),
            )), id)
            val afterEdit = next()
            assertEquals(3L, afterEdit.revision)
            assertEquals(2, afterEdit.courses.single().meetings.size)
            assertEquals(setOf(2, 9), afterEdit.courses.single().meetings.first().weeks)
            repository.deleteCourse(id)
            val afterDelete = next()
            assertEquals(4L, afterDelete.revision)
            assertTrue(afterDelete.courses.isEmpty())
            assertTrue(emissions.tryReceive().isFailure)
        } finally {
            collector.cancelAndJoin()
            emissions.close()
        }
    }

    @Test
    fun invalidSettingsCannotShrinkAwayWeeksOrRemoveUsedOrdinarySections() = runBlocking {
        val repository = RoomScheduleRepository(open(newProfile("settings")))
        repository.saveCourse(simpleDraft())
        val before = snapshot(repository)
        expectInvalid { repository.updateSettings(before.settings.copy(totalWeeks = 7), before.periods) }
        assertEquals(before, snapshot(repository))
        expectInvalid { repository.updateSettings(before.settings, before.periods.filter { it.number != 3 }) }
        assertEquals(before, snapshot(repository))
        expectInvalid { repository.updateSettings(before.settings.copy(id = 2), before.periods) }
        assertEquals(before, snapshot(repository))
        expectInvalid { repository.updateSettings(before.settings.copy(name = " "), before.periods) }
        assertEquals(before, snapshot(repository))
        expectInvalid { repository.updateSettings(before.settings, before.periods + before.periods.first()) }
        assertEquals(before, snapshot(repository))
        val overlap = before.periods.toMutableList().apply { this[1] = Period(2, time("08:30"), time("09:30")) }
        expectInvalid { repository.updateSettings(before.settings, overlap) }
        assertEquals(before, snapshot(repository))
        repository.updateSettings(before.settings.copy(totalWeeks = 24), before.periods.map {
            if (it.number == 1) Period(1, time("07:50"), time("08:35")) else it
        })
        val after = snapshot(repository)
        assertEquals(before.courses, after.courses)
        assertEquals(before.revision + 1, after.revision)
    }

    @Test
    fun invalidSemesterBoundaryDateLeavesSettingsPeriodsCoursesAndRevisionUntouched() = runBlocking {
        val profile = newProfile("datebounds")
        val db = open(profile)
        val repository = RoomScheduleRepository(db)
        repository.saveCourse(simpleDraft())
        val before = snapshot(repository)
        val changedPeriods = before.periods.map {
            if (it.number == 1) Period(1, time("07:50"), time("08:35")) else it
        }

        expectInvalid {
            repository.updateSettings(before.settings.copy(name = "Must roll back", startDate = LocalDate.MAX), changedPeriods)
        }

        assertEquals(before, snapshot(repository))
        db.close()
        openDatabases.remove(db)
        assertEquals(before, snapshot(RoomScheduleRepository(open(profile))))
    }

    @Test
    fun customTimeReferenceSectionsDoNotPreventPeriodRemoval() = runBlocking {
        val repository = RoomScheduleRepository(open(newProfile("custom")))
        repository.saveCourse(simpleDraft().copy(meetings = listOf(CourseMeeting(
            day = 2, weeks = setOf(1), sections = setOf(3), customTime = TimeRange(time("12:10"), time("12:40")),
        ))))
        val before = snapshot(repository)
        repository.updateSettings(before.settings, before.periods.filter { it.number != 3 })
        assertEquals(before.courses, snapshot(repository).courses)
    }

    @Test
    fun failedSettingsSqlRollsBackSettingsPeriodsRevisionAndExistingCourses() = runBlocking {
        val db = open(newProfile("rollback"))
        val repository = RoomScheduleRepository(db)
        repository.saveCourse(simpleDraft())
        val before = snapshot(repository)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_period_failure BEFORE INSERT ON periods WHEN NEW.number=2 BEGIN SELECT RAISE(ABORT, 'Synthetic period write failure'); END")
        try {
            expectFailure { repository.updateSettings(before.settings.copy(name = "Must roll back"), before.periods) }
            assertEquals(before, snapshot(repository))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_period_failure")
        }
    }

    @Test
    fun failedChildWriteRollsBackCourseEditChildrenAndRevision() = runBlocking {
        val db = open(newProfile("childrollback"))
        val repository = RoomScheduleRepository(db)
        val id = repository.saveCourse(simpleDraft())
        val before = snapshot(repository)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_section_failure BEFORE INSERT ON meeting_sections WHEN NEW.section=4 BEGIN SELECT RAISE(ABORT, 'Synthetic child write failure'); END")
        try {
            expectFailure { repository.saveCourse(simpleDraft().copy(courseName = "Must roll back", meetings = listOf(
                CourseMeeting(day = 5, weeks = setOf(2, 7), sections = setOf(2, 4)),
            )), id) }
            assertEquals(before, snapshot(repository))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER synthetic_section_failure")
        }
    }

    @Test
    fun unknownEditAndDeleteIdsAndInvalidDraftsLeaveTheSnapshotUntouched() = runBlocking {
        val repository = RoomScheduleRepository(open(newProfile("invalid")))
        repository.saveCourse(simpleDraft())
        val before = snapshot(repository)
        expectInvalid { repository.saveCourse(simpleDraft(), 999_999) }
        assertEquals(before, snapshot(repository))
        expectInvalid { repository.deleteCourse(999_999) }
        assertEquals(before, snapshot(repository))
        for (invalid in listOf(simpleDraft().copy(courseName = " "), simpleDraft().copy(meetings = emptyList()),
            simpleDraft().copy(color = -1), simpleDraft().copy(meetings = listOf(CourseMeeting(day = 1, weeks = emptySet(), sections = setOf(1)))),
            simpleDraft().copy(meetings = listOf(CourseMeeting(day = 1, weeks = setOf(21), sections = setOf(1)))),
            simpleDraft().copy(meetings = listOf(CourseMeeting(day = 1, weeks = setOf(1), sections = setOf(13)))))) {
            expectInvalid { repository.saveCourse(invalid, before.courses.single().id) }
            assertEquals(before, snapshot(repository))
        }
    }

    @Test
    fun twoProfilesKeepDataSettingsAndSnapshotScopeSeparate() = runBlocking {
        val profileA = newProfile("A")
        val profileB = newProfile("B")
        val repositoryA = RoomScheduleRepository(open(profileA))
        val repositoryB = RoomScheduleRepository(open(profileB))
        val idA = repositoryA.saveCourse(simpleDraft())
        assertTrue(snapshot(repositoryB).courses.isEmpty())
        repositoryB.updateSettings(SemesterSettings(name = "Synthetic B", totalWeeks = 24), defaultPeriods())
        val idB = repositoryB.saveCourse(simpleDraft().copy(courseName = "Synthetic B course"))
        val a = snapshot(repositoryA)
        val b = snapshot(repositoryB)
        assertEquals(listOf(idA), a.courses.map { it.id })
        assertEquals(listOf(idB), b.courses.map { it.id })
        assertEquals("Synthetic same name", a.courses.single().courseName)
        assertEquals("Synthetic B course", b.courses.single().courseName)
        assertEquals(20, a.settings.totalWeeks)
        assertEquals(24, b.settings.totalWeeks)
        assertEquals(profileA, a.scopeToken)
        assertEquals(profileB, b.scopeToken)
        assertNotEquals(a.scopeToken, b.scopeToken)
    }

    private fun newProfile(prefix: String) = "test_s2_${prefix}_${UUID.randomUUID().toString().replace("-", "")}".also {
        ownedNames += "timetable_$it.db"
    }

    private fun open(profile: String) = TimetableDatabase.open(context, profile).also(openDatabases::add)
    private suspend fun snapshot(repository: RoomScheduleRepository) = withTimeout(5_000) { repository.observeTimetable().first() }
    private fun count(db: TimetableDatabase, table: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    private suspend fun expectInvalid(block: suspend () -> Unit) {
        var failure: Throwable? = null
        try { block() } catch (error: IllegalArgumentException) { failure = error }
        assertTrue("Expected invalid input to be rejected", failure is IllegalArgumentException)
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        var failure: Throwable? = null
        try { block() } catch (error: Exception) { failure = error }
        assertTrue("Expected the synthetic SQLite failure", failure != null)
    }

    private fun time(value: String) = LocalTime.parse(value)
    private fun simpleDraft() = CourseDraft("Synthetic same name", meetings = listOf(
        CourseMeeting(day = 1, weeks = setOf(1, 3, 8), sections = setOf(1, 3)),
    ))
    @Test
    fun updateSettingsAdaptsImportedNormalizationStateAndPersistsAcrossReopen() = runBlocking {
        val profile = newProfile("update_settings_import")
        val db = open(profile)
        val repository = RoomScheduleRepository(db)
        val settings = SemesterSettings(name = "Synthetic import term", startDate = LocalDate.parse("2026-09-07"), totalWeeks = 16)
        repository.updateSettings(settings, defaultPeriods())

        // 1. 真实导入一门节次课程
        val excerpt = listOf(SourceExcerpt(SourcePosition(1, TextBox(1f, 2f, 3f, 4f)), "synthetic source"))
        val richDraft = RichTimetableDraft(
            courses = listOf(
                RichCourseDraft(
                    name = "导入高等数学",
                    meetings = listOf(
                        RichMeetingDraft(
                            kind = com.lingion.sleepy.importing.MeetingKind.RECURRING,
                            dayOfWeek = 1,
                            weeks = setOf(1, 2, 3),
                            sections = setOf(1, 2),
                            teacher = "高数老师",
                            room = "101",
                            sources = excerpt
                        )
                    )
                )
            )
        )
        val snap0 = snapshot(repository)
        val currentTarget = ImportTarget(snap0.scopeToken, 1, snap0.revision, db.openHelper.readableDatabase.version)
        val session = ImportReviewSession.create(
            FrozenImportSource.fromFormat(FormatParseResult(richDraft)),
            ImportReviewContext(currentTarget, ImportMode.REPLACE, LocalDate.parse("2026-09-07"), 16, defaultPeriods()),
            selectedMeetings = setOf(ReviewLocator(courseIndex = 0, meetingIndex = 0))
        )
        val evidence = session.requirements
            .filter { it.kind == ReviewRequirementKind.EVIDENCE }
            .map { session.reviewEvidence(it.fingerprint) }
        val readyResult = RichDraftConverter.convert(session, ImportReviewDecisions(evidence = evidence))
        assertTrue("convert ready: $readyResult", readyResult is ImportReviewResult.Ready)
        val reviewed = (readyResult as ImportReviewResult.Ready).reviewed
        val preview = repository.previewImport(reviewed, emptyList())
        assertTrue("preview ready: ${preview.problems}", preview.ready)
        val applied = repository.applyImport(ImportApplyCommand(currentTarget, ImportMode.REPLACE, reviewed, preview.preview.planDigest, emptyList()))
        assertTrue("applied import", applied is ImportApplyResult.Applied)

        // 2. 验证已成功存储 importState
        val targetViewBefore = repository.readImportTarget(1)
        assertEquals(1, targetViewBefore.snapshot.courses.size)
        val revisionBefore = targetViewBefore.snapshot.revision
        val courseBefore = targetViewBefore.snapshot.courses.single()
        val meetingBefore = courseBefore.meetings.single()

        // 3. 执行新作息变更：第1节 08:10-08:55，第2节 09:05-09:50，第3节 10:00-10:45（其余节次保持默认，完全合法无重叠）
        val newPeriods = defaultPeriods().map { period ->
            when (period.number) {
                1 -> Period(1, time("08:10"), time("08:55"))
                2 -> Period(2, time("09:05"), time("09:50"))
                else -> period
            }
        }
        assertEquals("newPeriods 必须无重叠完全合法", emptyList<String>(), TimetableRules.validatePeriods(newPeriods))
        repository.updateSettings(settings, newPeriods)

        // 4. 验证作息已更新且 revision 递增
        val snapshotAfter = snapshot(repository)
        assertEquals(revisionBefore + 1, snapshotAfter.revision)
        assertEquals(newPeriods, snapshotAfter.periods)

        // 验证课程时钟、稳定ID与来源
        val courseAfter = snapshotAfter.courses.single()
        val meetingAfter = courseAfter.meetings.single()
        assertEquals(courseBefore.id, courseAfter.id)
        assertEquals(meetingBefore.id, meetingAfter.id)
        assertEquals(1, meetingAfter.day)
        assertEquals(setOf(1, 2), meetingAfter.sections)
        assertEquals(excerpt.size, meetingAfter.provenance.size)
        assertEquals("导入高等数学", courseAfter.courseName)
        val intervalsAfter = TimetableRules.periodIntervals(meetingAfter, snapshotAfter.periods)
        assertEquals(listOf(TimeRange(time("08:10"), time("08:55")), TimeRange(time("09:05"), time("09:50"))), intervalsAfter)

        // 5. 验证 readImportTarget 成功，归一化状态在新作息下完全合法
        val targetViewAfter = repository.readImportTarget(1)
        assertEquals(snapshotAfter.revision, targetViewAfter.target.expectedRevision)
        assertEquals(1, targetViewAfter.logicalBases.size)
        val rawStateAfter = db.scheduleDao().importStates(1).single { it.courseId == courseAfter.id }
        val decodedStateAfter = ImportNormalizationCodec.decode(rawStateAfter.payload, snapshotAfter.scopeToken, snapshotAfter.settings.id, courseAfter, snapshotAfter)
        val logicalAfter = decodedStateAfter.graph.originals.single()
        assertEquals(intervalsAfter, logicalAfter.intervals)
        assertEquals(6, logicalAfter.allocated.size)
        assertEquals(intervalsAfter.toSet(), logicalAfter.allocated.map { it.range }.toSet())

        // 6. 验证非法操作事务回滚（重叠时间 & 删除已使用节次）
        val overlappingPeriods = listOf(
            Period(1, time("08:00"), time("08:45")),
            Period(2, time("08:30"), time("09:15")), // 重叠
        )
        expectInvalid { repository.updateSettings(settings, overlappingPeriods) }
        assertEquals(snapshotAfter.revision, snapshot(repository).revision) // revision 不变，零写入

        val deletedSectionPeriods = newPeriods.filter { it.number != 1 } // 删除正在使用的第1节
        expectInvalid { repository.updateSettings(settings, deletedSectionPeriods) }
        assertEquals(snapshotAfter.revision, snapshot(repository).revision) // revision 不变，零写入

        // 7. 关闭并重新打开数据库，验证 SQLite 持久化完全自洽
        db.close()
        openDatabases.remove(db)
        val reopened = open(profile)
        val reopenedRepo = RoomScheduleRepository(reopened)
        val reopenedTargetView = reopenedRepo.readImportTarget(1)
        assertEquals(snapshotAfter.revision, reopenedTargetView.target.expectedRevision)
        assertEquals(newPeriods, reopenedTargetView.snapshot.periods)
        assertEquals(1, reopenedTargetView.snapshot.courses.size)
        val reopenedCourse = reopenedTargetView.snapshot.courses.single()
        assertEquals(courseBefore.id, reopenedCourse.id)
        assertEquals(meetingBefore.id, reopenedCourse.meetings.single().id)
        assertEquals(excerpt.size, reopenedCourse.meetings.single().provenance.size)
        val reopenedRawState = reopened.scheduleDao().importStates(1).single { it.courseId == reopenedCourse.id }
        val reopenedDecodedState = ImportNormalizationCodec.decode(reopenedRawState.payload, reopenedTargetView.snapshot.scopeToken, reopenedTargetView.snapshot.settings.id, reopenedCourse, reopenedTargetView.snapshot)
        assertEquals(intervalsAfter, reopenedDecodedState.graph.originals.single().intervals)
    }

    @Test
    fun updateSettingsPreservesNoticeAdjustmentsAndGuardsUndo() = runBlocking {
        val profile = newProfile("update_settings_notice")
        val db = open(profile)
        val repository = RoomScheduleRepository(db)
        val noticeRepo = RoomNoticeAdjustmentRepository(db)
        val startMonday = LocalDate.parse("2026-09-07")
        val settings = SemesterSettings(name = "Synthetic term", startDate = startMonday, totalWeeks = 16)
        repository.updateSettings(settings, defaultPeriods())

        // 1. 导入周一第1-2节课程（第10周周一为 2026-11-09）
        val richDraft = RichTimetableDraft(
            courses = listOf(
                RichCourseDraft(
                    name = "高等数学",
                    meetings = listOf(
                        RichMeetingDraft(
                            kind = com.lingion.sleepy.importing.MeetingKind.RECURRING,
                            dayOfWeek = 1,
                            weeks = (1..16).toSet(),
                            sections = setOf(1, 2),
                            teacher = "高数老师",
                            room = "101"
                        )
                    )
                )
            )
        )
        val snap0 = snapshot(repository)
        val currentTarget = ImportTarget(snap0.scopeToken, 1, snap0.revision, db.openHelper.readableDatabase.version)
        val session = ImportReviewSession.create(
            FrozenImportSource.fromFormat(FormatParseResult(richDraft)),
            ImportReviewContext(currentTarget, ImportMode.REPLACE, startMonday, 16, defaultPeriods()),
            selectedMeetings = setOf(ReviewLocator(courseIndex = 0, meetingIndex = 0))
        )
        val evidence = session.requirements
            .filter { it.kind == ReviewRequirementKind.EVIDENCE }
            .map { session.reviewEvidence(it.fingerprint) }
        val readyResult = RichDraftConverter.convert(session, ImportReviewDecisions(evidence = evidence))
        assertTrue("convert ready: $readyResult", readyResult is ImportReviewResult.Ready)
        val reviewed = (readyResult as ImportReviewResult.Ready).reviewed
        val preview = repository.previewImport(reviewed, emptyList())
        assertTrue("preview ready: ${preview.problems}", preview.ready)
        val applied = repository.applyImport(ImportApplyCommand(currentTarget, ImportMode.REPLACE, reviewed, preview.preview.planDigest, emptyList()))
        assertTrue("applied import", applied is ImportApplyResult.Applied)
        val courseBefore = snapshot(repository).courses.single()

        // 2. 应用单次调课：将第10周周一（2026-11-09）的课调整为第3-4节
        val basis = NoticeDateBasis(referenceDate = LocalDate.parse("2026-11-09"), termMonday = startMonday, totalWeeks = 16)
        val parsedIntent = (NoticeIntentParser.parse("本周一高等数学由第1-2节调整为第3-4节", NoticeBasis(basis)).single() as NoticeIntentResult.Ready).intent
        val snapNotice = snapshot(repository)
        val currentTargetNotice = ImportTarget(snapNotice.scopeToken, snapNotice.settings.id, snapNotice.revision, db.openHelper.readableDatabase.version)
        val confirmation1 = confirmNotice(noticeRepo, currentTargetNotice, parsedIntent)
        val appliedNotice = noticeRepo.apply(confirmation1) as NoticeAdjustmentApplyResult.Applied
        val noticeRev = snapshot(repository).revision

        // 断言调整日（第10周周一）为两个独立EXTRA物理时段（10:00-10:45、10:55-11:40），非调整日（第9周周一）为原第1-2节
        val mondayWeek10 = LocalDate.parse("2026-11-09")
        val mondayWeek9 = LocalDate.parse("2026-11-02")
        val occ10List = TimetableRules.occurrencesOn(snapshot(repository), mondayWeek10)
            .filter { it.course.id == courseBefore.id }
        assertEquals(2, occ10List.size)
        val expectedExtraRanges = listOf(TimeRange(time("10:00"), time("10:45")), TimeRange(time("10:55"), time("11:40")))
        assertEquals(expectedExtraRanges, occ10List.flatMap { it.timeRanges }.distinct().sortedBy { it.start })
        assertTrue(occ10List.all { it.effectiveSections.isEmpty() })

        val occ9 = TimetableRules.occurrencesOn(snapshot(repository), mondayWeek9)
            .filter { it.course.id == courseBefore.id }.single()
        assertEquals(setOf(1, 2), occ9.effectiveSections)
        val expectedMorningRanges = listOf(TimeRange(time("08:00"), time("08:45")), TimeRange(time("08:55"), time("09:40")))
        assertEquals(expectedMorningRanges, occ9.timeRanges.sortedBy { it.start })

        // 3. 情形 A：修改上午第1-2节作息（原相关作息变化）
        val morningAdjustedPeriods = defaultPeriods().map { period ->
            when (period.number) {
                1 -> Period(1, time("08:10"), time("08:55"))
                2 -> Period(2, time("09:05"), time("09:50"))
                else -> period
            }
        }
        assertEquals(emptyList<String>(), TimetableRules.validatePeriods(morningAdjustedPeriods))
        repository.updateSettings(settings, morningAdjustedPeriods)
        val revAfterMorningPeriods = snapshot(repository).revision
        assertEquals(noticeRev + 1, revAfterMorningPeriods)

        // 尝试撤销旧事件：由于修改的是原第1-2节，归一化原始descriptor随之改变，触发逻辑归属守卫UNDO_OWNERSHIP_CHANGED
        val snapshotBeforeGuardedUndo = snapshot(repository)
        val statesBeforeGuardedUndo = db.scheduleDao().importStates(1)
        val historyBeforeGuardedUndo = (noticeRepo.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries

        val guardedUndo = noticeRepo.undo(appliedNotice.eventId, revAfterMorningPeriods)
        assertTrue("undo must be guarded and rejected when ownership changed: $guardedUndo",
            guardedUndo is NoticeAdjustmentUndoResult.Rejected)
        assertEquals(NoticeAdjustmentCodes.UNDO_OWNERSHIP_CHANGED, (guardedUndo as NoticeAdjustmentUndoResult.Rejected).code)

        // 严格全等比较断言零写入：完整快照、所有importStates与事件表均全等
        val snapshotAfterGuardedUndo = snapshot(repository)
        val statesAfterGuardedUndo = db.scheduleDao().importStates(1)
        val historyAfterGuardedUndo = (noticeRepo.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries

        assertEquals(snapshotBeforeGuardedUndo, snapshotAfterGuardedUndo)
        assertEquals(statesBeforeGuardedUndo, statesAfterGuardedUndo)
        assertEquals(historyBeforeGuardedUndo, historyAfterGuardedUndo)

        // 4. 情形 B：恢复上午作息，改为仅修改无关晚课（第11-12节）
        val eveningAdjustedPeriods = defaultPeriods().map { period ->
            when (period.number) {
                11 -> Period(11, time("21:00"), time("21:45"))
                12 -> Period(12, time("21:55"), time("22:40"))
                else -> period
            }
        }
        assertEquals(emptyList<String>(), TimetableRules.validatePeriods(eveningAdjustedPeriods))
        repository.updateSettings(settings, eveningAdjustedPeriods)
        val revAfterEveningPeriods = snapshot(repository).revision
        assertEquals(revAfterMorningPeriods + 1, revAfterEveningPeriods)

        // 调课日和正常日的有效发生依然保持
        val eveningOcc10 = TimetableRules.occurrencesOn(snapshot(repository), mondayWeek10)
            .filter { it.course.id == courseBefore.id }
        assertEquals(2, eveningOcc10.size)
        assertEquals(expectedExtraRanges, eveningOcc10.flatMap { it.timeRanges }.distinct().sortedBy { it.start })
        assertTrue(eveningOcc10.all { it.effectiveSections.isEmpty() })

        val eveningOcc9 = TimetableRules.occurrencesOn(snapshot(repository), mondayWeek9)
            .filter { it.course.id == courseBefore.id }.single()
        assertEquals(setOf(1, 2), eveningOcc9.effectiveSections)

        // 撤销该调课事件：由于上午物理时钟完全未变（仅无关晚自习调整），撤销必须成功
        val undone = noticeRepo.undo(appliedNotice.eventId, revAfterEveningPeriods)
        assertTrue("undone must succeed when relevant periods unchanged: $undone", undone is NoticeAdjustmentUndoResult.Undone)
        // 撤销后恢复为第1-2节
        val undoneOcc10 = TimetableRules.occurrencesOn(snapshot(repository), mondayWeek10)
            .filter { it.course.id == courseBefore.id }.single()
        assertEquals(setOf(1, 2), undoneOcc10.effectiveSections)
        assertEquals(expectedMorningRanges, undoneOcc10.timeRanges.sortedBy { it.start })

        assertEquals(snapshotBeforeGuardedUndo, snapshotAfterGuardedUndo)
        assertEquals(statesBeforeGuardedUndo, statesAfterGuardedUndo)
        assertEquals(historyBeforeGuardedUndo, historyAfterGuardedUndo)
    }

    @Test
    fun updateSettingsRejectsLossyPartialClockFragmentAndRollsBackCompletely() = runBlocking {
        val profile = newProfile("partial_lossy")
        val db = open(profile)
        val repository = RoomScheduleRepository(db)
        val settings = SemesterSettings(startDate = LocalDate.parse("2026-09-07"), totalWeeks = 16)
        repository.updateSettings(settings, defaultPeriods())

        // 1. 构造一个在旧作息下合法自洽但包含 allocated 部分残片（08:00-08:30）的课程
        val monday = LocalDate.parse("2026-09-07")
        val originalMeetingDef = CourseMeeting(
            id = 0,
            day = 1,
            weeks = setOf(1),
            sections = setOf(1),
            customTime = null,
            teacher = "Teacher P",
            room = "Room P",
            provenance = listOf(ScheduleProvenance(rawText = "partial", sourceLocator = "row:1"))
        )
        val partialMeeting = originalMeetingDef.copy(
            sections = emptySet(),
            customTime = TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 30))
        )
        val draftCourse = CourseDraft(
            courseName = "PartialFragmentCourse",
            teacher = "Teacher P",
            room = "Room P",
            color = 3,
            meetings = listOf(partialMeeting),
            provenance = emptyList()
        )
        val courseId = repository.saveCourse(draftCourse)
        val courseWithId = snapshot(repository).courses.single { it.id == courseId }
        val meetingWithId = courseWithId.meetings.single()

        val original = LogicalOriginal(
            familyId = "1".repeat(64),
            descriptorFingerprint = ImportOccurrencePlanner.descriptorFingerprint(
                originalMeetingDef,
                OccurrenceIdentity("PartialFragmentCourse", null, null, "Teacher P", "Room P", null, null, false),
                listOf(TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45))),
                true
            ),
            original = originalMeetingDef,
            identity = OccurrenceIdentity("PartialFragmentCourse", null, null, "Teacher P", "Room P", null, null, false),
            oldMeetingAliases = emptySet(),
            allocated = listOf(DatedInterval(monday, TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 30)))),
            intervals = listOf(TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45))),
            sectionsUseTarget = true
        )
        val fragment = OwnedFragment(
            key = "2".repeat(64),
            familyId = original.familyId,
            meetingId = meetingWithId.id,
            shapeFingerprint = ImportNormalizationCodec.meetingFingerprint(meetingWithId)
        )
        val snap = snapshot(repository)
        val state = StoredNormalizationState(
            version = 1,
            scopeToken = snap.scopeToken,
            semesterId = snap.settings.id,
            courseId = courseId,
            graph = PlannedNormalizationState(listOf(original), emptyList(), emptyList(), emptyList(), emptyList()),
            fragments = listOf(fragment),
            ownedAdds = emptyList(),
            sourceReferences = listOf(StoredSourceReference(courseId, meetingWithId.id, null, 0, ImportCanonical.digest(partialMeeting.provenance.single())))
        )
        ImportNormalizationCodec.validate(state, snap.scopeToken, snap.settings.id, courseWithId, snap)
        val encoded = ImportNormalizationCodec.encode(state)
        db.scheduleDao().putImportState(ImportNormalizationStateEntity(courseId, state.version, encoded))

        // 2. 证明旧状态在旧作息下完全自洽且能被成功读取
        val targetBefore = repository.readImportTarget(1)
        assertEquals(snap.revision, targetBefore.target.expectedRevision)
        val statesBefore = db.scheduleDao().importStates(1)
        assertEquals(1, statesBefore.size)
        assertEquals(courseId, statesBefore.single().courseId)

        // 记录修改前的完整快照、所有states及事件列表
        val snapBefore = snapshot(repository)
        val noticeRepo = RoomNoticeAdjustmentRepository(db)
        val historyBefore = (noticeRepo.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries

        // 3. 执行新作息变更：第1节推迟（08:10-08:55）
        val newPeriods = defaultPeriods().map { period ->
            when (period.number) {
                1 -> Period(1, time("08:10"), time("08:55"))
                2 -> Period(2, time("09:05"), time("09:50"))
                else -> period
            }
        }
        var thrown: IllegalArgumentException? = null
        try {
            repository.updateSettings(settings, newPeriods)
        } catch (e: IllegalArgumentException) {
            thrown = e
        }
        assertNotNull("预期抛出 IllegalArgumentException", thrown)
        val err = thrown!!
        assertTrue("必须包含明确中文拒绝提示: ${err.message}", err.message?.contains("作息时间调整与已有导入课程状态冲突") == true)

        // 4. 验证严格零写入与全等回滚
        val snapAfter = snapshot(repository)
        val statesAfter = db.scheduleDao().importStates(1)
        val historyAfter = (noticeRepo.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries

        assertEquals(snapBefore, snapAfter)
        assertEquals(statesBefore, statesAfter)
        assertEquals(historyBefore, historyAfter)
    }

    private suspend fun confirmNotice(
        noticeRepo: RoomNoticeAdjustmentRepository,
        target: ImportTarget,
        intent: com.lingion.sleepy.domain.notice.NoticeIntent
    ): com.lingion.sleepy.domain.notice.NoticeAdjustmentConfirmation {
        val first = noticeRepo.preview(NoticeAdjustmentRequest(target, intent, null))
        if (first is NoticeAdjustmentPreviewResult.Ready) return first.preview.confirmation
        val blocked = first as? NoticeAdjustmentPreviewResult.Blocked ?: error("预览意外结果: $first")
        if (blocked.candidates.isEmpty()) error("无候选: problems=${blocked.problems}, target=$target, intentOrig=${intent.originalDate}, hint=${intent.courseNameHint}")
        val candidate = blocked.candidates.singleOrNull() ?: error("无唯一候选: ${blocked.candidates}")
        val second = noticeRepo.preview(NoticeAdjustmentRequest(target, intent, candidate.id))
        val ready = second as? NoticeAdjustmentPreviewResult.Ready ?: error("第二次预览意外结果: $second")
        return ready.preview.confirmation
    }

    @Test
    fun updateSettingsFailureInNormalizationPathRollsBackCompletelyWithoutPartialWrite() = runBlocking {
        val profile = newProfile("update_settings_zero_write")
        val db = open(profile)
        val repository = RoomScheduleRepository(db)
        val startMonday = LocalDate.parse("2026-09-07")
        val settings = SemesterSettings(name = "Zero write test term", startDate = startMonday, totalWeeks = 16)
        repository.updateSettings(settings, defaultPeriods())

        // 导入两门课程：Course A (第1-2节) 和 Course B (第3-4节)
        val richDraft = RichTimetableDraft(
            courses = listOf(
                RichCourseDraft(
                    name = "课程A",
                    meetings = listOf(
                        RichMeetingDraft(
                            kind = com.lingion.sleepy.importing.MeetingKind.RECURRING,
                            dayOfWeek = 1,
                            weeks = setOf(1, 2),
                            sections = setOf(1, 2),
                            room = "101"
                        )
                    )
                ),
                RichCourseDraft(
                    name = "课程B",
                    meetings = listOf(
                        RichMeetingDraft(
                            kind = com.lingion.sleepy.importing.MeetingKind.RECURRING,
                            dayOfWeek = 2,
                            weeks = setOf(1, 2),
                            sections = setOf(3, 4),
                            room = "102"
                        )
                    )
                )
            )
        )
        val snap0 = snapshot(repository)
        val currentTarget = ImportTarget(snap0.scopeToken, 1, snap0.revision, db.openHelper.readableDatabase.version)
        val session = ImportReviewSession.create(
            FrozenImportSource.fromFormat(FormatParseResult(richDraft)),
            ImportReviewContext(currentTarget, ImportMode.REPLACE, startMonday, 16, defaultPeriods()),
            selectedMeetings = setOf(ReviewLocator(courseIndex = 0, meetingIndex = 0), ReviewLocator(courseIndex = 1, meetingIndex = 0))
        )
        val evidence = session.requirements
            .filter { it.kind == ReviewRequirementKind.EVIDENCE }
            .map { session.reviewEvidence(it.fingerprint) }
        val readyResult = RichDraftConverter.convert(session, ImportReviewDecisions(evidence = evidence))
        assertTrue("convert ready: $readyResult", readyResult is ImportReviewResult.Ready)
        val reviewed = (readyResult as ImportReviewResult.Ready).reviewed
        val preview = repository.previewImport(reviewed, emptyList())
        assertTrue("preview ready: ${preview.problems}", preview.ready)
        val applied = repository.applyImport(ImportApplyCommand(currentTarget, ImportMode.REPLACE, reviewed, preview.preview.planDigest, emptyList()))
        assertTrue("applied import", applied is ImportApplyResult.Applied)

        val targetViewBefore = repository.readImportTarget(1)
        assertEquals(2, targetViewBefore.snapshot.courses.size)
        val courseA = targetViewBefore.snapshot.courses.single { it.courseName == "课程A" }
        val courseB = targetViewBefore.snapshot.courses.single { it.courseName == "课程B" }
        val snapshotBefore = snapshot(repository)
        val revisionBefore = snapshotBefore.revision

        // 读取 Course A 在损坏前的 raw state 实体
        val rawStateABefore = db.scheduleDao().importStates(1).single { it.courseId == courseA.id }

        // 人为将 Course B 的归一化状态在数据库中损坏（写入非法 JSON payload）
        db.scheduleDao().putImportState(ImportNormalizationStateEntity(courseB.id, 1, "{\"corrupted\":true}"))

        // 构造 100% 合法的新作息时间（第1节 08:10-08:55，第2节 09:05-09:50，第3-12节保持）
        val legalNewPeriods = defaultPeriods().map { period ->
            when (period.number) {
                1 -> Period(1, time("08:10"), time("08:55"))
                2 -> Period(2, time("09:05"), time("09:50"))
                else -> period
            }
        }
        assertEquals("legalNewPeriods 必须完全合法", emptyList<String>(), TimetableRules.validatePeriods(legalNewPeriods))

        // 调用 updateSettings：由于 Course B 的归一化状态损坏，必须在适配路径抛出明确中文异常
        val exception = try {
            repository.updateSettings(settings, legalNewPeriods)
            null
        } catch (e: IllegalArgumentException) {
            e
        }
        assertNotNull("必须抛出 IllegalArgumentException", exception)
        assertEquals("已有课程导入状态损坏，无法更新作息时间。", exception!!.message)

        // 逐字段严格比较零写入：
        val snapshotAfterFailure = snapshot(repository)
        assertEquals("revision 必须保持不变", revisionBefore, snapshotAfterFailure.revision)
        assertEquals("periods 必须保持旧作息不变", snapshotBefore.periods, snapshotAfterFailure.periods)
        assertEquals("courses 数量保持不变", snapshotBefore.courses.size, snapshotAfterFailure.courses.size)
        assertEquals("courses 内容保持不变", snapshotBefore.courses, snapshotAfterFailure.courses)

        // 验证 Course A 的 state 绝对没有被部分落盘（payload 与 version 与之前完全一致）
        val rawStateAAfter = db.scheduleDao().importStates(1).single { it.courseId == courseA.id }
        val rawStateBAfter = db.scheduleDao().importStates(1).single { it.courseId == courseB.id }
        assertEquals(rawStateABefore.version, rawStateAAfter.version)
        assertEquals(rawStateABefore.payload, rawStateAAfter.payload)
        // Course B 的坏状态也未被写成新状态
        assertEquals("{\"corrupted\":true}", rawStateBAfter.payload)
    }

    private fun complexDraft() = CourseDraft(" Synthetic exact course ", " Teacher ", " Room ", 0xF0123456L, listOf(
        CourseMeeting(day = 1, weeks = setOf(1, 3, 8, 20), sections = setOf(1, 3, 13), teacher = " Override teacher ", room = " Override room "),
        CourseMeeting(day = 5, weeks = setOf(2, 6, 22), sections = setOf(99), customTime = TimeRange(time("12:07"), time("12:49")), teacher = "", room = null),
    ))
}

