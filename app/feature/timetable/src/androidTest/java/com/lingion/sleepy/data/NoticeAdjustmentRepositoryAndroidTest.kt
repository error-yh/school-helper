/* SPDX-License-Identifier: GPL-3.0-only
 * Real Room/SQLite tests for the protected notice execution layer: migration is covered separately.
 * Every fixture is synthetic, every database has a unique profile and is removed afterwards.
 */
package com.lingion.sleepy.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lingion.sleepy.data.local.ImportNormalizationStateEntity
import com.lingion.sleepy.data.local.NoticeAdjustmentEventEntity
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.DatedInterval
import com.lingion.sleepy.domain.imports.ImportCanonical
import com.lingion.sleepy.domain.imports.ImportNormalizationCodec
import com.lingion.sleepy.domain.imports.ImportOccurrencePlanner
import com.lingion.sleepy.domain.imports.ImportTarget
import com.lingion.sleepy.domain.imports.LogicalOriginal
import com.lingion.sleepy.domain.imports.OccurrenceIdentity
import com.lingion.sleepy.domain.imports.OwnedFragment
import com.lingion.sleepy.domain.imports.PlannedNormalizationState
import com.lingion.sleepy.domain.imports.StoredNormalizationState
import com.lingion.sleepy.domain.imports.fList
import com.lingion.sleepy.domain.imports.fSet
import com.lingion.sleepy.domain.imports.ImportApplyCommand
import com.lingion.sleepy.domain.repository.ImportApplyResult
import com.lingion.sleepy.domain.imports.ImportMode
import com.lingion.sleepy.domain.imports.ImportReviewContext
import com.lingion.sleepy.domain.imports.ImportReviewDecisions
import com.lingion.sleepy.domain.imports.ImportReviewResult
import com.lingion.sleepy.domain.imports.ImportReviewSession
import com.lingion.sleepy.domain.imports.ReviewedImport
import com.lingion.sleepy.domain.imports.ReviewLocator
import com.lingion.sleepy.domain.imports.ReviewRequirementKind
import com.lingion.sleepy.domain.imports.RichDraftConverter
import com.lingion.sleepy.domain.imports.FrozenImportSource
import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.model.defaultPeriods
import com.lingion.sleepy.domain.notice.NoticeAdjustmentApplyResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentCodes
import com.lingion.sleepy.domain.notice.NoticeAdjustmentConfirmation
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreviewResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentRequest
import com.lingion.sleepy.domain.notice.NoticeAdjustmentUndoResult
import com.lingion.sleepy.domain.notice.NoticeCandidateId
import com.lingion.sleepy.domain.notice.NoticeCourseSelection
import com.lingion.sleepy.importing.NoticeDateBasis
import com.lingion.sleepy.domain.notice.NoticeEventPlanner
import com.lingion.sleepy.domain.notice.NoticeIntent
import com.lingion.sleepy.domain.notice.NoticeIntentParser
import com.lingion.sleepy.domain.notice.NoticeIntentResult
import com.lingion.sleepy.domain.notice.NoticeBasis
import com.lingion.sleepy.importing.DraftTimeRange
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoticeAdjustmentRepositoryAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val profiles = mutableSetOf<String>()
    private val databases = mutableListOf<TimetableDatabase>()
    private lateinit var profile: String
    private lateinit var db: TimetableDatabase
    private lateinit var schedules: RoomScheduleRepository
    private lateinit var repository: RoomNoticeAdjustmentRepository

    private val start = LocalDate.of(2026, 9, 7)
    private val friday = LocalDate.of(2026, 11, 13)

    @After
    fun cleanUpOnlySyntheticDatabases() {
        databases.forEach { runCatching { it.close() } }
        databases.clear()
        profiles.forEach { context.deleteDatabase("timetable_$it.db") }
        profiles.clear()
    }

    @Test
    fun manualRescheduleAppliesOnlyOneWeekAndSurvivesReopenBeforeUndo() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        val confirmation = confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))
        val applied = repository.apply(confirmation) as NoticeAdjustmentApplyResult.Applied
        assertEquals("MANUAL", applied.mode)
        assertSections(friday, courseId, setOf(5, 6))
        assertSections(LocalDate.of(2026, 11, 6), courseId, setOf(3, 4))
        assertSections(LocalDate.of(2026, 11, 20), courseId, setOf(3, 4))

        val history = repository.history() as NoticeAdjustmentHistoryResult.Loaded
        assertEquals(1, history.history.entries.size)
        assertEquals("APPLIED", history.history.entries.single().state)

        // Close and reopen the same profile: history and undo must come from SQLite, not memory.
        db.close()
        databases.remove(db)
        val reopened = TimetableDatabase.open(context, profile)
        databases += reopened
        val reopenedSchedules = RoomScheduleRepository(reopened)
        val reopenedRepository = RoomNoticeAdjustmentRepository(reopened)
        val persisted = reopenedRepository.history() as NoticeAdjustmentHistoryResult.Loaded
        assertEquals(1, persisted.history.entries.size)
        val current = withTimeout(5_000) { reopenedSchedules.observeTimetable().first() }
        val undone = reopenedRepository.undo(applied.eventId, current.revision) as NoticeAdjustmentUndoResult.Undone
        assertEquals(current.revision + 1, undone.revision)
        assertSectionsOn(reopenedSchedules, friday, courseId, setOf(3, 4))
        val afterUndo = reopenedRepository.history() as NoticeAdjustmentHistoryResult.Loaded
        assertEquals("UNDONE", afterUndo.history.entries.single().state)
        assertNotNull(afterUndo.history.entries.single().undoneRevision)
    }

    @Test
    fun customClockSurvivesRoomOnlyChangeAndUndo() = runBlocking {
        open()
        seed()
        val custom = TimeRange(LocalTime.of(9, 10), LocalTime.of(10, 20))
        val courseId = addCourse(custom = custom)
        val confirmation = confirm(intent("2026-11-13数据可视化教室改为B203"))
        repository.apply(confirmation) as NoticeAdjustmentApplyResult.Applied
        val occurrence = occurrences(friday).single { it.course.id == courseId }
        assertEquals(listOf(custom), occurrence.timeRanges)
        assertEquals("B203", occurrence.effectiveRoom)
        repository.undo(eventId(), revision()) as NoticeAdjustmentUndoResult.Undone
        val restored = occurrences(friday).single { it.course.id == courseId }
        assertEquals("A101", restored.effectiveRoom)
        assertEquals(listOf(custom), restored.timeRanges)
    }

    @Test
    fun cancelAndMakeUpWriteOnlyTheirOwnOccurrence() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        val cancel = confirm(intent("2026-11-20数据可视化停课"))
        repository.apply(cancel) as NoticeAdjustmentApplyResult.Applied
        assertTrue(occurrences(LocalDate.of(2026, 11, 20)).none { it.course.id == courseId })
        repository.undo(eventId(), revision()) as NoticeAdjustmentUndoResult.Undone
        assertEquals(1, occurrences(LocalDate.of(2026, 11, 20)).count { it.course.id == courseId })

        val extraIntent = intent("2026-11-20 16:00-17:30 数据可视化补课")
        val extra = confirm(extraIntent, extra = NoticeCourseSelection(courseId))
        repository.apply(extra) as NoticeAdjustmentApplyResult.Applied
        val day = occurrences(LocalDate.of(2026, 11, 20)).filter { it.course.id == courseId }
        assertEquals(2, day.size)
        assertTrue(day.any { it.timeRanges == listOf(TimeRange(LocalTime.of(16, 0), LocalTime.of(17, 30))) })
        repository.undo(eventId(), revision()) as NoticeAdjustmentUndoResult.Undone
        assertEquals(1, occurrences(LocalDate.of(2026, 11, 20)).count { it.course.id == courseId })
    }

    @Test
    fun staleTargetAndStaleConfirmationNeverWrite() = runBlocking {
        open()
        seed()
        addCourse()
        val confirmation = confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))
        addCourse(name = "Other synthetic course")
        val rejected = repository.apply(confirmation) as NoticeAdjustmentApplyResult.Rejected
        assertEquals(NoticeAdjustmentCodes.STALE_TARGET, rejected.code)
        assertTrue((repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.isEmpty())
        assertSections(friday, 1L, setOf(3, 4))
    }

    @Test
    fun repeatedSemanticsAreAlreadyAppliedWithoutASecondWrite() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        val notice = intent("本周五数据可视化由第3-4节调整为第5-6节")
        val confirmation = confirm(notice)
        val first = repository.apply(confirmation) as NoticeAdjustmentApplyResult.Applied
        val again = repository.apply(confirmation) as NoticeAdjustmentApplyResult.AlreadyApplied
        assertEquals(first.eventId, again.eventId)
        assertEquals(1, (repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.size)

        val current = target()
        val selection = confirmation.selection!!
        val preview = repository.preview(NoticeAdjustmentRequest(current, notice, selection))
        assertTrue("$preview", preview is NoticeAdjustmentPreviewResult.AlreadyApplied)
        assertEquals(first.eventId, (preview as NoticeAdjustmentPreviewResult.AlreadyApplied).eventId)

        val reworded = intent("本周五数据可视化由第3-4节调整至第5-6节")
        val preview2 = repository.preview(NoticeAdjustmentRequest(current, reworded, selection))
        assertTrue("$preview2", preview2 is NoticeAdjustmentPreviewResult.AlreadyApplied)

        // After undo the same semantics can be applied again as a new event.
        repository.undo(first.eventId, revision()) as NoticeAdjustmentUndoResult.Undone
        val confirmation2 = confirm(notice, selectionFrom = selection)
        val second = repository.apply(confirmation2) as NoticeAdjustmentApplyResult.Applied
        assertTrue(second.eventId > first.eventId)
        assertEquals(2, (repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.size)
        assertEquals(setOf(5, 6), occurrences(friday).single { it.course.id == courseId }.effectiveSections)
    }

    @Test
    fun conflictNeedsExplicitAcknowledgmentAndWritesNothingBeforeIt() = runBlocking {
        open()
        seed()
        val first = addCourse(name = "数据可视化")
        addCourse(name = "大学英语", sections = setOf(5, 6))
        val notice = intent("本周五数据可视化由第3-4节调整为第5-6节")
        val target = target()
        val blocked = repository.preview(NoticeAdjustmentRequest(target, notice, null))
        val selected = (blocked as NoticeAdjustmentPreviewResult.Blocked).candidates.single { it.id.courseId == first }.id
        val pending = repository.preview(NoticeAdjustmentRequest(target, notice, selected))
        assertTrue("$pending", pending is NoticeAdjustmentPreviewResult.ConflictPending)
        val conflict = (pending as NoticeAdjustmentPreviewResult.ConflictPending).proposal.conflicts.single().fingerprint
        assertTrue((repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.isEmpty())
        val ready = repository.preview(NoticeAdjustmentRequest(target, notice, selected, acknowledgedConflicts = setOf(conflict)))
        val confirmation = (ready as NoticeAdjustmentPreviewResult.Ready).preview.confirmation
        assertTrue(repository.apply(confirmation) is NoticeAdjustmentApplyResult.Applied)
    }

    @Test
    fun transactionFaultRollsBackRowsStateJournalAndRevision() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        val revisionBefore = revision()
        val confirmation = confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))
        repository.noticeFault = { point -> if (point == "manual_after_insert") throw IllegalStateException("synthetic fault") }
        val failed = repository.apply(confirmation) as NoticeAdjustmentApplyResult.Rejected
        repository.noticeFault = {}
        assertEquals(NoticeAdjustmentCodes.APPLY_FAILED, failed.code)
        assertEquals(revisionBefore, revision())
        assertEquals(1, occurrences(friday).count { it.course.id == courseId })
        assertTrue((repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.isEmpty())
        val retried = confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))
        assertTrue(repository.apply(retried) is NoticeAdjustmentApplyResult.Applied)
    }

    @Test
    fun importedCourseAppliesStateJournalAndUndoThroughLineage() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        markImported(courseId)
        val confirmation = confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))
        val applied = repository.apply(confirmation) as NoticeAdjustmentApplyResult.Applied
        assertEquals("NORMALIZED", applied.mode)
        // The import representation owns one EXTRA per continuous period interval.
        val moved = occurrences(friday).filter { it.course.id == courseId }
        assertEquals(2, moved.size)
        assertEquals(setOf(TimeRange(LocalTime.of(14, 0), LocalTime.of(14, 45)),
            TimeRange(LocalTime.of(14, 55), LocalTime.of(15, 40))), moved.flatMap { it.timeRanges }.toSet())
        assertTrue(moved.all { it.exception?.kind == com.lingion.sleepy.domain.model.ExceptionKind.EXTRA })
        assertTrue(moved.none { it.timeRanges.any { range -> range.end <= LocalTime.of(12, 0) } })
        schedules.readImportTarget(1) // decode + validate must still succeed
        assertEquals(1, (repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.size)

        val undone = repository.undo(applied.eventId, revision()) as NoticeAdjustmentUndoResult.Undone
        assertEquals(setOf(3, 4), occurrences(friday).single { it.course.id == courseId }.effectiveSections)
        assertEquals(undone.revision, revision())
        assertNotNull(schedules.readImportTarget(1))
    }

    @Test
    fun independentLaterAdjustmentSurvivesEarlierUndo() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        val first = repository.apply(confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))) as NoticeAdjustmentApplyResult.Applied
        val second = repository.apply(confirm(intent("2026-11-20数据可视化由第3-4节调整为第7-8节"))) as NoticeAdjustmentApplyResult.Applied
        repository.undo(first.eventId, revision()) as NoticeAdjustmentUndoResult.Undone
        assertSections(friday, courseId, setOf(3, 4))
        assertSections(LocalDate.of(2026, 11, 20), courseId, setOf(7, 8))
        val entries = (repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries
        assertEquals(2, entries.size)
        assertEquals("UNDONE", entries.single { it.eventId == first.eventId }.state)
        assertEquals("APPLIED", entries.single { it.eventId == second.eventId }.state)
    }

    @Test
    fun replacingASameDayAdjustmentBlocksTheEarlierUndo() = runBlocking {
        open()
        seed()
        addCourse()
        val first = repository.apply(confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))) as NoticeAdjustmentApplyResult.Applied
        val replaceIntent = intent("本周五数据可视化由原来的第5-6节调整为第7-8节")
        val replaceConfirmation = confirm(replaceIntent, replace = true)
        val second = repository.apply(replaceConfirmation) as NoticeAdjustmentApplyResult.Applied
        val rejected = repository.undo(first.eventId, revision()) as NoticeAdjustmentUndoResult.Rejected
        assertEquals(NoticeAdjustmentCodes.LATER_EVENT_DEPENDS, rejected.code)
        assertEquals(setOf(7, 8), occurrences(friday).single().effectiveSections)
        repository.undo(second.eventId, revision()) as NoticeAdjustmentUndoResult.Undone
        assertEquals(setOf(5, 6), occurrences(friday).single().effectiveSections)
        assertEquals(2, (repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.size)
    }

    @Test
    fun deletingTheCourseKeepsHistoryAndRefusesUndo() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        val applied = repository.apply(confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))) as NoticeAdjustmentApplyResult.Applied
        schedules.deleteCourse(courseId)
        val entry = (repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.single()
        assertNull(entry.courseId)
        val rejected = repository.undo(applied.eventId, revision()) as NoticeAdjustmentUndoResult.Rejected
        assertEquals(NoticeAdjustmentCodes.UNDO_TARGET_MISSING, rejected.code)
        assertEquals(1, (repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.size)
    }

    @Test
    fun corruptJournalAndForeignScopeAreReportedWithoutCrashing() = runBlocking {
        open()
        seed()
        val dao = db.noticeAdjustmentDao()
        dao.insert(event(scope = "other", requestKey = "other-key", beforeJson = "{}"))
        assertTrue((repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.isEmpty())
        dao.insert(event(scope = db.scopeToken, requestKey = "corrupt-key", beforeJson = "not-json"))
        val failed = repository.history()
        assertTrue("$failed", failed is NoticeAdjustmentHistoryResult.Failed)
        assertEquals(NoticeAdjustmentCodes.CORRUPT_EVENT, (failed as NoticeAdjustmentHistoryResult.Failed).code)
    }

    @Test
    fun concurrentDoubleSubmitAppliesExactlyOnce() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        val confirmation = confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))
        val results = coroutineScope {
            val first = async { repository.apply(confirmation) }
            val second = async { repository.apply(confirmation) }
            listOf(first.await(), second.await())
        }
        assertEquals("results=$results", 1, results.count { it is NoticeAdjustmentApplyResult.Applied })
        assertEquals(1, results.count { it is NoticeAdjustmentApplyResult.AlreadyApplied })
        assertEquals(1, (repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.size)
        assertEquals(setOf(5, 6), occurrences(friday).single { it.course.id == courseId }.effectiveSections)
    }

    @Test
    fun normalizedFaultRollsBackStateRowsJournalAndRevision() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        markImported(courseId)
        val revisionBefore = revision()
        val confirmation = confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))
        repository.noticeFault = { point -> if (point == "normalized_after_children") throw IllegalStateException("synthetic fault") }
        val failed = repository.apply(confirmation) as NoticeAdjustmentApplyResult.Rejected
        repository.noticeFault = {}
        assertEquals(NoticeAdjustmentCodes.APPLY_FAILED, failed.code)
        assertEquals(revisionBefore, revision())
        assertSections(friday, courseId, setOf(3, 4))
        assertNotNull(schedules.readImportTarget(1))
        assertTrue((repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.isEmpty())
    }

    @Test
    fun historyIsBoundedAndPagesBackwards() = runBlocking {
        open()
        seed()
        addCourse()
        val first = repository.apply(confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))) as NoticeAdjustmentApplyResult.Applied
        val second = repository.apply(confirm(intent("2026-11-20数据可视化由第3-4节调整为第7-8节"))) as NoticeAdjustmentApplyResult.Applied
        val newest = repository.history(limit = 1) as NoticeAdjustmentHistoryResult.Loaded
        assertEquals(second.eventId, newest.history.entries.single().eventId)
        assertEquals(second.eventId, newest.history.nextBeforeEventId)
        val older = repository.history(limit = 1, beforeEventId = newest.history.nextBeforeEventId) as NoticeAdjustmentHistoryResult.Loaded
        assertEquals(first.eventId, older.history.entries.single().eventId)
        assertNull(older.history.nextBeforeEventId)
        repository.undo(first.eventId, revision()) as NoticeAdjustmentUndoResult.Undone
        assertEquals(
            NoticeAdjustmentUndoResult.AlreadyUndone(first.eventId),
            repository.undo(first.eventId, revision()) as NoticeAdjustmentUndoResult.AlreadyUndone,
        )
    }

    @Test
    fun normalizedCustomClockSurvivesRoomOnlyChange() = runBlocking {
        open()
        seed()
        val custom = TimeRange(LocalTime.of(9, 10), LocalTime.of(10, 20))
        val courseId = addCourse(custom = custom)
        markImported(courseId)
        applyText("2026-11-13数据可视化教室改为B203")
        assertRanges(friday, courseId, listOf(custom))
        assertRoom(friday, courseId, "B203")
        assertRanges(LocalDate.of(2026, 11, 6), courseId, listOf(custom))
        undoLast()
        assertRanges(friday, courseId, listOf(custom))
        assertRoom(friday, courseId, "A101")
    }

    @Test
    fun normalizedCustomClockSurvivesCrossDayMove() = runBlocking {
        open()
        seed()
        val custom = TimeRange(LocalTime.of(9, 10), LocalTime.of(10, 20))
        val courseId = addCourse(custom = custom)
        markImported(courseId)
        applyText("原2026-11-13数据可视化调至2026-11-14")
        assertTrue(occurrences(friday).none { it.course.id == courseId })
        assertRanges(LocalDate.of(2026, 11, 14), courseId, listOf(custom))
        undoLast()
        assertRanges(friday, courseId, listOf(custom))
    }

    @Test
    fun normalizedCustomClockSurvivesExplicitNewTime() = runBlocking {
        open()
        seed()
        val custom = TimeRange(LocalTime.of(9, 10), LocalTime.of(10, 20))
        val courseId = addCourse(custom = custom)
        markImported(courseId)
        applyText("原2026-11-13数据可视化调至14:00-15:30")
        assertRanges(friday, courseId, listOf(TimeRange(LocalTime.of(14, 0), LocalTime.of(15, 30))))
        undoLast()
        assertRanges(friday, courseId, listOf(custom))
    }

    @Test
    fun replacingAdjustmentExpressesEffectiveAfterRelativeToOriginal() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        repository.apply(confirm(intent("本周五数据可视化由第3-4节调整为第5-6节，教室改为B203"))) as NoticeAdjustmentApplyResult.Applied
        assertSections(friday, courseId, setOf(5, 6))
        assertRoom(friday, courseId, "B203")

        val second = repository.apply(
            confirm(intent("本周五数据可视化由原来的第5-6节调整为第7-8节，教室不变"), replace = true)
        ) as NoticeAdjustmentApplyResult.Applied
        assertSections(friday, courseId, setOf(7, 8))
        assertRoom(friday, courseId, "B203")

        repository.undo(second.eventId, revision()) as NoticeAdjustmentUndoResult.Undone
        assertSections(friday, courseId, setOf(5, 6))
        assertRoom(friday, courseId, "B203")
    }

    @Test
    fun replacingWithRoomOnlyKeepsTheAdjustedTime() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        repository.apply(confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))) as NoticeAdjustmentApplyResult.Applied
        assertSections(friday, courseId, setOf(5, 6))
        repository.apply(
            confirm(intent("本周五数据可视化由原来的第5-6节调整为第5-6节，教室改为B203"), replace = true)
        ) as NoticeAdjustmentApplyResult.Applied
        assertSections(friday, courseId, setOf(5, 6))
        assertRoom(friday, courseId, "B203")
    }

    @Test
    fun undoRefusesWhenEffectiveAfterChangedByCalendar() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        val applied = repository.apply(confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))) as NoticeAdjustmentApplyResult.Applied
        val snapshot = snapshot()
        val changed = snapshot.periods.map { period ->
            if (period.number == 5) Period(5, LocalTime.of(13, 0), LocalTime.of(13, 45)) else period
        }
        schedules.updateSettings(snapshot.settings, changed)
        val rejected = repository.undo(applied.eventId, revision()) as NoticeAdjustmentUndoResult.Rejected
        assertEquals(NoticeAdjustmentCodes.UNDO_AFTER_MISMATCH, rejected.code)
        assertEquals(TimeRange(LocalTime.of(13, 0), LocalTime.of(13, 45)),
            occurrences(friday).single { it.course.id == courseId }.timeRanges.first())
        assertEquals("APPLIED", (repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.single().state)
    }

    @Test
    fun sameDayIndependentMeetingsDoNotBlockUndo() = runBlocking {
        open()
        seed()
        val courseId = addCourseWith(listOf(
            CourseMeeting(id = 0, day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4)),
            CourseMeeting(id = 0, day = 5, weeks = (1..16).toSet(), sections = setOf(7, 8)),
        ))
        val first = repository.apply(confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))) as NoticeAdjustmentApplyResult.Applied
        repository.apply(confirm(intent("本周五数据可视化由第7-8节调整为第1-2节"))) as NoticeAdjustmentApplyResult.Applied
        repository.undo(first.eventId, revision()) as NoticeAdjustmentUndoResult.Undone
        val occurrences = occurrences(friday).filter { it.course.id == courseId }
        assertTrue(occurrences.any { it.effectiveSections == setOf(3, 4) })
        assertTrue(occurrences.any { it.effectiveSections == setOf(1, 2) })
    }

    @Test
    fun normalizedConflictUsesPreviewAcknowledgement() = runBlocking {
        open()
        seed()
        val imported = addCourse()
        markImported(imported)
        addCourse(name = "大学英语", sections = setOf(5, 6))
        val notice = intent("本周五数据可视化由第3-4节调整为第5-6节")
        val target = target()
        val blocked = repository.preview(NoticeAdjustmentRequest(target, notice, null)) as NoticeAdjustmentPreviewResult.Blocked
        val selected = blocked.candidates.single { it.id.courseId == imported }.id
        val pending = repository.preview(NoticeAdjustmentRequest(target, notice, selected)) as NoticeAdjustmentPreviewResult.ConflictPending
        val fingerprint = pending.proposal.conflicts.single().fingerprint
        val ready = repository.preview(
            NoticeAdjustmentRequest(target, notice, selected, acknowledgedConflicts = setOf(fingerprint))
        ) as NoticeAdjustmentPreviewResult.Ready
        val applied = repository.apply(ready.preview.confirmation) as NoticeAdjustmentApplyResult.Applied
        assertEquals("NORMALIZED", applied.mode)
        val moved = occurrences(friday).filter { it.course.id == imported }
        assertTrue(moved.isNotEmpty())
        assertTrue(moved.all { it.timeRanges.all { range -> range.start >= LocalTime.of(14, 0) } })
    }

    @Test
    fun makeUpBusinessFieldsArePartOfRequestIdentity() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        val base = intent("2026-11-20 16:00-17:30 数据可视化补课")
        val firstIntent = base.copy(project = "实验甲", group = "A组")
        val first = repository.apply(confirm(firstIntent, extra = NoticeCourseSelection(courseId))) as NoticeAdjustmentApplyResult.Applied

        val secondIntent = base.copy(project = "实验乙", group = "B组")
        val secondPreview = repository.preview(
            NoticeAdjustmentRequest(target(), secondIntent, null, NoticeCourseSelection(courseId))
        )
        assertTrue("$secondPreview", secondPreview !is NoticeAdjustmentPreviewResult.AlreadyApplied)
        val firstKey = NoticeEventPlanner.requestKey(
            snapshot().scopeToken, 1, firstIntent, courseId, null, courseId,
        )
        val secondKey = NoticeEventPlanner.requestKey(
            snapshot().scopeToken, 1, secondIntent, courseId, null, courseId,
        )
        assertNotEquals(firstKey, secondKey)

        val pending = secondPreview as NoticeAdjustmentPreviewResult.ConflictPending
        val fingerprint = pending.proposal.conflicts.single().fingerprint
        val ready = repository.preview(
            NoticeAdjustmentRequest(target(), secondIntent, null, NoticeCourseSelection(courseId),
                acknowledgedConflicts = setOf(fingerprint))
        ) as NoticeAdjustmentPreviewResult.Ready
        repository.apply(ready.preview.confirmation) as NoticeAdjustmentApplyResult.Applied
        assertEquals(3, occurrences(LocalDate.of(2026, 11, 20)).count { it.course.id == courseId })

        val sameFieldsReworded = firstIntent.copy(rawText = firstIntent.rawText + " ")
        val repeated = repository.preview(
            NoticeAdjustmentRequest(target(), sameFieldsReworded, null, NoticeCourseSelection(courseId))
        )
        assertTrue("$repeated", repeated is NoticeAdjustmentPreviewResult.AlreadyApplied)
        assertEquals(first.eventId, (repeated as NoticeAdjustmentPreviewResult.AlreadyApplied).eventId)
    }

    @Test
    fun realImportNoticeApplyReimportAndGuardedUndo() = runBlocking {
        open()
        seed()
        importReviewed(ImportMode.APPEND,
            "合成导入课" to CourseMeeting(id = 0, day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4)),
            "合成其他课" to CourseMeeting(id = 0, day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2)),
        )
        schedules.readImportTarget(1)
        val importedId = snapshot().courses.single { it.courseName == "合成导入课" }.id
        assertTrue(snapshot().courses.single { it.id == importedId }.meetings.single().provenance.isNotEmpty())

        val noticeConfirmation = confirm(intent("本周五合成导入课由第3-4节调整为第5-6节"))
        val notice = repository.apply(noticeConfirmation) as NoticeAdjustmentApplyResult.Applied
        assertEquals("NORMALIZED", notice.mode)
        assertTrue(occurrences(friday).filter { it.course.id == importedId }
            .all { occurrence -> occurrence.timeRanges.all { it.end > LocalTime.of(13, 0) } })
        schedules.readImportTarget(1)

        // Re-import replaces the original archive: the old adjusted occurrence no longer exists.
        importReviewed(ImportMode.REPLACE,
            "合成导入课" to CourseMeeting(id = 0, day = 5, weeks = (1..16).toSet(), sections = setOf(3, 4), room = "R2"),
            "合成其他课" to CourseMeeting(id = 0, day = 1, weeks = (1..16).toSet(), sections = setOf(1, 2)),
        )
        schedules.readImportTarget(1)
        assertTrue("the other course must survive re-import", snapshot().courses.any { it.courseName == "合成其他课" })
        assertTrue("sources must survive re-import", snapshot().courses.all { course -> course.meetings.any { it.provenance.isNotEmpty() } })

        val again = intent("本周五合成导入课由第3-4节调整为第5-6节")
        val afterReimport = repository.preview(NoticeAdjustmentRequest(target(), again, noticeConfirmation.selection))
        assertTrue("$afterReimport", afterReimport !is NoticeAdjustmentPreviewResult.AlreadyApplied)
        assertTrue("$afterReimport", afterReimport is NoticeAdjustmentPreviewResult.Blocked)
        assertTrue((afterReimport as NoticeAdjustmentPreviewResult.Blocked).problems.any {
            it.code == NoticeAdjustmentCodes.STALE_EVENT_EFFECT
        })

        val revisionBeforeUndo = revision()
        val undo = repository.undo(notice.eventId, revisionBeforeUndo) as NoticeAdjustmentUndoResult.Rejected
        assertTrue(undo.code in setOf(
            NoticeAdjustmentCodes.UNDO_TARGET_MISSING,
            NoticeAdjustmentCodes.UNDO_AFTER_MISMATCH,
            NoticeAdjustmentCodes.UNDO_OWNERSHIP_CHANGED,
        ))
        assertEquals(revisionBeforeUndo, revision())
        assertEquals("APPLIED", (repository.history() as NoticeAdjustmentHistoryResult.Loaded)
            .history.entries.single { it.eventId == notice.eventId }.state)
    }

    @Test
    fun cancellationFaultPropagatesAndRollsBack() = runBlocking {
        open()
        seed()
        val courseId = addCourse()
        val revisionBefore = revision()
        val confirmation = confirm(intent("本周五数据可视化由第3-4节调整为第5-6节"))
        repository.noticeFault = { throw java.util.concurrent.CancellationException("synthetic cancellation") }
        try {
            repository.apply(confirmation)
            org.junit.Assert.fail("cancellation must propagate")
        } catch (_: java.util.concurrent.CancellationException) {
        }
        repository.noticeFault = {}
        assertEquals(revisionBefore, revision())
        assertEquals(1, occurrences(friday).count { it.course.id == courseId })
        assertTrue((repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.isEmpty())
    }

    // ---- harness ---------------------------------------------------------------------------

    private suspend fun open() {
        profile = "synthetic_notice_" + UUID.randomUUID().toString().replace("-", "")
        profiles += profile
        db = TimetableDatabase.open(context, profile).also(databases::add)
        schedules = RoomScheduleRepository(db)
        repository = RoomNoticeAdjustmentRepository(db)
    }

    private suspend fun seed() {
        withTimeout(5_000) { schedules.observeTimetable().first() }
        schedules.updateSettings(SemesterSettings(1, "Synthetic term", start, 16), defaultPeriods())
    }

    private suspend fun addCourse(
        name: String = "数据可视化",
        teacher: String = "张老师",
        room: String = "A101",
        sections: Set<Int> = setOf(3, 4),
        custom: TimeRange? = null,
    ): Long = schedules.saveCourse(
        CourseDraft(name, teacher, room, 0xFF6750A4,
            listOf(CourseMeeting(id = 0, day = 5, weeks = (1..16).toSet(), sections = sections, customTime = custom))),
        null,
    )

    private suspend fun addCourseWith(meetings: List<CourseMeeting>): Long = schedules.saveCourse(
        CourseDraft("数据可视化", "张老师", "A101", 0xFF6750A4, meetings), null,
    )

    private suspend fun applyText(text: String): NoticeAdjustmentApplyResult.Applied =
        repository.apply(confirm(intent(text))) as NoticeAdjustmentApplyResult.Applied

    private suspend fun undoLast(): NoticeAdjustmentUndoResult.Undone {
        val eventId = (repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.first().eventId
        return repository.undo(eventId, revision()) as NoticeAdjustmentUndoResult.Undone
    }

    private suspend fun assertRanges(date: LocalDate, courseId: Long, ranges: List<TimeRange>) {
        val matches = occurrences(date).filter { it.course.id == courseId }
        assertTrue("no occurrence on $date", matches.isNotEmpty())
        assertEquals(ranges.toSet(), matches.flatMap { it.timeRanges }.toSet())
    }

    private suspend fun assertRoom(date: LocalDate, courseId: Long, room: String) {
        val matches = occurrences(date).filter { it.course.id == courseId }
        assertTrue("no occurrence on $date", matches.isNotEmpty())
        assertTrue(matches.all { it.effectiveRoom == room })
    }

    private suspend fun importReviewed(mode: ImportMode, vararg courses: Pair<String, CourseMeeting>) {
        val reviewed = reviewedImport(mode, *courses)
        val preview = schedules.previewImport(reviewed, emptyList())
        assertTrue("$preview", preview.ready)
        val applied = schedules.applyImport(
            ImportApplyCommand(target(), mode, reviewed, preview.preview.planDigest, emptyList())
        )
        assertTrue("$applied", applied is ImportApplyResult.Applied)
    }

    private suspend fun reviewedImport(mode: ImportMode, vararg courses: Pair<String, CourseMeeting>): ReviewedImport {
        val drafts = courses.map { (name, meeting) ->
            val excerpts = listOf(SourceExcerpt(SourcePosition(1, TextBox(1f, 2f, 3f, 4f)), "synthetic $name source"))
            RichCourseDraft(name, meetings = listOf(RichMeetingDraft(
                kind = com.lingion.sleepy.importing.MeetingKind.RECURRING,
                dayOfWeek = meeting.day,
                weeks = meeting.weeks,
                sections = meeting.sections,
                customTime = meeting.customTime?.let { DraftTimeRange(it.start, it.end) },
                teacher = meeting.teacher,
                room = meeting.room,
                sources = excerpts,
            )))
        }
        val source = FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses = drafts)))
        val session = ImportReviewSession.create(
            source,
            ImportReviewContext(target(), mode, start, 16, defaultPeriods()),
            selectedMeetings = drafts.indices.map { ReviewLocator(courseIndex = it, meetingIndex = 0) }.toSet(),
        )
        val evidence = session.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }
            .map { session.reviewEvidence(it.fingerprint) }
        val result = RichDraftConverter.convert(session, ImportReviewDecisions(evidence = evidence))
        assertTrue("$result", result is ImportReviewResult.Ready)
        return (result as ImportReviewResult.Ready).reviewed
    }

    private suspend fun target(): ImportTarget {
        val snapshot = snapshot()
        return ImportTarget(snapshot.scopeToken, 1, snapshot.revision, db.openHelper.readableDatabase.version)
    }

    private suspend fun snapshot(): TimetableSnapshot = withTimeout(5_000) { schedules.observeTimetable().first() }
    private suspend fun revision(): Long = snapshot().revision
    private suspend fun occurrences(date: LocalDate) = TimetableRules.occurrencesOn(snapshot(), date)

    private fun intent(text: String, basis: NoticeDateBasis = NoticeDateBasis(
        referenceDate = LocalDate.of(2026, 11, 9), termMonday = start, totalWeeks = 16,
    )): NoticeIntent {
        val parsed = NoticeIntentParser.parse(text, NoticeBasis(basis)).single()
        assertTrue("$parsed", parsed is NoticeIntentResult.Ready)
        return (parsed as NoticeIntentResult.Ready).intent
    }

    private suspend fun confirm(
        intent: NoticeIntent,
        selectionFrom: NoticeCandidateId? = null,
        extra: NoticeCourseSelection? = null,
        acknowledged: Set<String> = emptySet(),
        replace: Boolean = false,
    ): NoticeAdjustmentConfirmation {
        val target = target()
        val first = repository.preview(NoticeAdjustmentRequest(target, intent, selectionFrom, extra, acknowledged, replace))
        if (first is NoticeAdjustmentPreviewResult.Ready) return first.preview.confirmation
        val blocked = first as? NoticeAdjustmentPreviewResult.Blocked ?: error("unexpected preview $first")
        if (blocked.candidates.isEmpty()) error("no candidates: ${blocked.problems}")
        val candidate = blocked.candidates.single()
        val second = repository.preview(NoticeAdjustmentRequest(target, intent, candidate.id, extra, acknowledged, replace))
        val ready = second as? NoticeAdjustmentPreviewResult.Ready ?: error("unexpected preview $second")
        return ready.preview.confirmation
    }

    private suspend fun assertSections(date: LocalDate, courseId: Long, sections: Set<Int>) =
        assertSectionsOn(schedules, date, courseId, sections)

    private suspend fun assertSectionsOn(repo: RoomScheduleRepository, date: LocalDate, courseId: Long, sections: Set<Int>) {
        val snapshot = withTimeout(5_000) { repo.observeTimetable().first() }
        val matches = TimetableRules.occurrencesOn(snapshot, date).filter { it.course.id == courseId }
        assertEquals(
            "occurrences on $date: " + matches.map { "meeting=${it.meeting.id} sections=${it.effectiveSections} ranges=${it.timeRanges} exception=${it.exception?.id}/${it.exception?.kind}" },
            1, matches.size,
        )
        assertEquals(sections, matches.single().effectiveSections)
    }

    private suspend fun markImported(courseId: Long) {
        val snapshot = snapshot()
        val course = snapshot.courses.single { it.id == courseId }
        val state = syntheticState(course, snapshot)
        db.scheduleDao().putImportState(
            ImportNormalizationStateEntity(courseId, ImportNormalizationCodec.VERSION, ImportNormalizationCodec.encode(state))
        )
        schedules.readImportTarget(1)
    }

    private fun syntheticState(course: ScheduledCourse, snapshot: TimetableSnapshot): StoredNormalizationState {
        val meeting = course.meetings.single()
        require(meeting.exceptions.isEmpty() && meeting.provenance.isEmpty() && course.provenance.isEmpty()) { "synthetic state needs a clean course" }
        val original = meeting.copy(id = 0, exceptions = emptyList())
        val identity = OccurrenceIdentity(course.courseName.trim(), course.sourceCourseCode, course.sourceClassCode,
            course.teacher.trim(), course.room.trim(), meeting.experimentProject, meeting.group, meeting.isExperiment)
        val intervals = TimetableRules.periodIntervals(meeting, snapshot.periods)
        val descriptor = ImportOccurrencePlanner.descriptorFingerprint(original, identity, intervals, true)
        val family = ImportCanonical.digest("synthetic-family")
        val allocated = ImportOccurrencePlanner.originalDates(original,
            snapshot.settings.startDate?.let(TimetableRules::mondayOf), snapshot.settings.totalWeeks)
            .flatMap { date -> intervals.map { DatedInterval(date, it) } }
        val graph = PlannedNormalizationState(
            fList(listOf(LogicalOriginal(family, descriptor, original, identity, fSet(setOf(meeting.id)), fList(allocated), fList(intervals), true))),
            fList(emptyList()), fList(emptyList()), fList(emptyList()), fList(emptyList()),
        )
        return StoredNormalizationState(ImportNormalizationCodec.VERSION, snapshot.scopeToken, 1, course.id, graph,
            fList(listOf(OwnedFragment(ImportCanonical.digest("fragment"), family, meeting.id,
                ImportNormalizationCodec.meetingFingerprint(meeting)))),
            fList(emptyList()), fList(emptyList()))
    }

    private fun event(scope: String, requestKey: String, beforeJson: String?): NoticeAdjustmentEventEntity =
        NoticeAdjustmentEventEntity(
            requestKey = requestKey, semanticKey = ImportCanonical.digest("semantic-$requestKey"),
            confirmDigest = ImportCanonical.digest("confirm-$requestKey"), scopeToken = scope, semesterId = 1,
            kind = "CANCEL", courseId = null, courseTitle = null, logicalBaseId = null, structureFingerprint = null,
            meetingId = null, originalEpochDay = friday.toEpochDay(), targetEpochDay = friday.toEpochDay(),
            beforeJson = beforeJson, afterJson = null, afterFingerprint = "ABSENT",
            payloadJson = "{\"version\":1,\"mode\":\"MANUAL\",\"exceptions\":[],\"fingerprints\":[],\"previous\":null}",
            sourceText = null, sourceDigest = ImportCanonical.digest("source"), dateBasisJson = null,
            appliedRevision = 1, state = "APPLIED", activeKey = requestKey,
        )

    private suspend fun eventId(): Long =
        (repository.history() as NoticeAdjustmentHistoryResult.Loaded).history.entries.first().eventId
}