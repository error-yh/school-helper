package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.model.defaultPeriods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class TimetableImportRulesTest {
    private val settings = SemesterSettings(startDate = LocalDate.parse("2026-08-31"), totalWeeks = 10)

    @Test
    fun sameTimeInDifferentWeeksOrOnDifferentDaysDoesNotConflict() {
        val first = meeting(weeks = setOf(1, 3, 5))
        assertFalse(TimetableImportRules.conflicts(first, meeting(weeks = setOf(2, 4, 6)), defaultPeriods()))
        assertFalse(TimetableImportRules.conflicts(first, first.copy(day = 2), defaultPeriods()))
        assertTrue(TimetableImportRules.conflicts(first, first.copy(weeks = setOf(5, 8)), defaultPeriods()))
    }

    @Test
    fun noncontiguousSectionsDoNotCreateABoundingBoxConflict() {
        val sparse = meeting(sections = setOf(1, 3))
        assertFalse(TimetableImportRules.conflicts(sparse, meeting(sections = setOf(2)), defaultPeriods()))
        assertTrue(TimetableImportRules.conflicts(sparse, meeting(sections = setOf(3)), defaultPeriods()))
    }

    @Test
    fun aCourseInARealPeriodBreakDoesNotConflict() {
        val consecutive = meeting(sections = setOf(1, 2))
        assertFalse(TimetableImportRules.conflicts(consecutive, custom("08:45", "08:55"), defaultPeriods()))
        assertTrue(TimetableImportRules.conflicts(consecutive, custom("08:44", "08:56"), defaultPeriods()))
    }

    @Test
    fun customTimeConflictUsesHalfOpenIntervals() {
        val first = custom("08:00", "08:45")
        assertFalse(TimetableImportRules.conflicts(first, custom("08:45", "09:00"), emptyList()))
        assertTrue(TimetableImportRules.conflicts(first, custom("08:44", "09:00"), emptyList()))
        assertTrue(TimetableImportRules.conflicts(first, custom("08:10", "08:20"), emptyList()))
    }

    @Test
    fun canonicalKeyPreservesSparseWeeksSectionsCustomTimeTeacherRoomAndScope() {
        val draft = draft(meeting(weeks = setOf(1, 3, 5, 8, 10), sections = setOf(1, 3)))
        val meeting = draft.meetings.single()
        val key = TimetableImportRules.canonicalKey(1, draft, meeting)
        assertEquals(setOf(1, 3, 5, 8, 10), key.weeks)
        assertEquals(setOf(1, 3), key.sections)
        assertNotEquals(key, TimetableImportRules.canonicalKey(2, draft, meeting))
        assertNotEquals(key, TimetableImportRules.canonicalKey(1, draft.copy(teacher = "另一教师"), meeting))
        assertNotEquals(key, TimetableImportRules.canonicalKey(1, draft.copy(room = "另一教室"), meeting))
        assertNotEquals(key, TimetableImportRules.canonicalKey(1, draft, meeting.copy(weeks = (1..10).toSet())))
        assertNotEquals(key, TimetableImportRules.canonicalKey(1, draft, meeting.copy(sections = setOf(1, 2, 3))))
        assertNotEquals(key, TimetableImportRules.canonicalKey(1, draft, meeting.copy(customTime = range("07:00", "07:30"))))
    }

    @Test
    fun canonicalKeyUsesEffectiveMeetingTeacherAndRoomRatherThanDefaultValues() {
        val draft = draft(meeting().copy(teacher = "实验教师", room = "实验室"))
        val meeting = draft.meetings.single()
        val key = TimetableImportRules.canonicalKey(1, draft, meeting)
        assertEquals("实验教师", key.teacher)
        assertEquals("实验室", key.room)
        assertEquals(key, TimetableImportRules.canonicalKey(1, draft.copy(teacher = "无效默认", room = "无效默认"), meeting))
        assertNotEquals(key, TimetableImportRules.canonicalKey(1, draft, meeting.copy(teacher = "", room = "")))
    }

    @Test
    fun exactKeyIgnoresRowIdAndSetOrder() {
        val first = meeting(weeks = linkedSetOf(1, 3, 5), sections = linkedSetOf(1, 3))
        val second = first.copy(id = 999, weeks = linkedSetOf(5, 3, 1), sections = linkedSetOf(3, 1))
        assertEquals(TimetableImportRules.canonicalKey(1, draft(first), first),
            TimetableImportRules.canonicalKey(1, draft(second), second))
    }

    @Test
    fun canonicalKeySeparatesProfilesEvenWhenSemesterAndCourseValuesMatch() {
        val meeting = meeting()
        val draft = draft(meeting)
        assertNotEquals(TimetableImportRules.canonicalKey(1, draft, meeting, scopeToken = "profile-A"),
            TimetableImportRules.canonicalKey(1, draft, meeting, scopeToken = "profile-B"))
    }

    @Test
    fun partiallyDuplicateCourseKeepsOnlyNewMeetingsInItsEffectivePlan() {
        val existing = draft(meeting())
        val newMeeting = meeting().copy(day = 3)
        val incoming = existing.copy(meetings = listOf(existing.meetings.single(), newMeeting))
        val preview = TimetableImportRules.preview(snapshot(listOf(scheduled(1, existing))), ParsedTimetableImport(listOf(incoming)))
        val entry = preview.entries.single()
        assertFalse(entry.duplicate)
        assertEquals(1, entry.duplicateMeetingCount)
        assertEquals(1, preview.duplicateMeetingCount)
        assertEquals(listOf(newMeeting), entry.effectiveCourse.meetings)
        assertTrue(preview.canApply)
    }

    @Test
    fun appendPreviewMarksExactDuplicatesAndCountsTrueConflicts() {
        val existing = draft(meeting())
        val other = existing.copy(courseName = "另一课程", teacher = "另一教师")
        val snapshot = snapshot(listOf(scheduled(11, existing)), revision = 7).copy(scopeToken = "profile-A")
        val preview = TimetableImportRules.preview(snapshot, ParsedTimetableImport(listOf(existing, other)))
        assertEquals(2, preview.totalCount)
        assertEquals(1, preview.duplicateCount)
        assertEquals(1, preview.conflictCount)
        assertTrue(preview.entries.first().duplicate)
        assertTrue(preview.entries.first().conflicts.isEmpty())
        assertEquals(setOf(1), preview.entries.last().conflicts.single().weeks)
        assertEquals(1, preview.entries.last().conflicts.single().day)
        assertEquals(7L, preview.expectedRevision)
        assertEquals("profile-A", preview.targetScopeToken)
        assertTrue(preview.canApply)
        assertEquals(listOf(11L), snapshot.courses.map { it.id })
    }

    @Test
    fun repeatedImportedRowsAreCountedAsDuplicatesWithoutNameGrouping() {
        val first = draft(meeting())
        val differentRoom = first.copy(room = "另一个教室")
        val preview = TimetableImportRules.preview(snapshot(), ParsedTimetableImport(listOf(first, first, differentRoom)))
        assertEquals(3, preview.totalCount)
        assertEquals(1, preview.duplicateCount)
        assertFalse(preview.entries.last().duplicate)
        assertTrue(preview.entries.last().conflicts.isNotEmpty())
    }

    @Test
    fun differentWeekImportRowsAreNeitherDuplicatesNorConflicts() {
        val first = draft(meeting(weeks = setOf(1)))
        val second = first.copy(meetings = listOf(meeting(weeks = setOf(2))))
        val preview = TimetableImportRules.preview(snapshot(listOf(scheduled(1, first))), ParsedTimetableImport(listOf(second)))
        assertEquals(0, preview.duplicateCount)
        assertEquals(0, preview.conflictCount)
        assertTrue(preview.canApply)
    }

    @Test
    fun replacePreviewChecksIncomingRowsButDoesNotCompareWithCoursesBeingReplaced() {
        val existing = draft(meeting())
        val snapshot = snapshot(listOf(scheduled(1, existing)), revision = 6)
        val preview = TimetableImportRules.preview(snapshot, ParsedTimetableImport(listOf(existing)), ImportMode.REPLACE)
        assertEquals(0, preview.duplicateCount)
        assertEquals(0, preview.conflictCount)
        assertEquals(ImportMode.REPLACE, preview.mode)
        assertEquals(6L, preview.expectedRevision)
        assertTrue(preview.canApply)
        assertEquals(1, snapshot.courses.size)
    }

    @Test
    fun invalidRowsAndParserErrorsRemainVisibleAndBlockFutureApplication() {
        val invalid = draft(meeting(weeks = setOf(11), sections = setOf(99))).copy(courseName = " ")
        val preview = TimetableImportRules.preview(snapshot(), ParsedTimetableImport(listOf(invalid), listOf("第2行无法解析")))
        assertEquals(1, preview.totalCount)
        assertEquals(listOf("第2行无法解析"), preview.errors)
        assertTrue(preview.entries.single().errors.isNotEmpty())
        assertTrue(preview.errorCount > 1)
        assertFalse(preview.canApply)
    }

    @Test
    fun emptyAndDuplicateOnlyPreviewsDoNotOfferAnAppendCommand() {
        val empty = TimetableImportRules.preview(snapshot(), ParsedTimetableImport())
        assertEquals(0, empty.totalCount)
        assertEquals(0, empty.errorCount)
        assertFalse(empty.canApply)
        val existing = draft(meeting())
        val duplicate = TimetableImportRules.preview(snapshot(listOf(scheduled(1, existing))), ParsedTimetableImport(listOf(existing)))
        assertFalse(duplicate.canApply)
    }

    @Test
    fun commandCarriesExplicitModeAndExpectedRevisionWithoutApplyingAnything() {
        val courses = listOf(draft(meeting()))
        val append = TimetableImportCommand(courses, ImportMode.APPEND, expectedRevision = 18, targetSemesterId = settings.id, targetScopeToken = "profile-A")
        val replace = TimetableImportCommand(courses, ImportMode.REPLACE, expectedRevision = 18, targetSemesterId = settings.id, targetScopeToken = "profile-A", settings = settings, periods = defaultPeriods())
        assertEquals(courses, append.courses)
        assertEquals(18L, replace.expectedRevision)
        assertEquals("profile-A", replace.targetScopeToken)
        assertEquals(settings, replace.settings)
        assertEquals(defaultPeriods(), replace.periods)
        assertNotEquals(append, replace)
    }

    @Test
    fun importPlanTargetsTheSnapshotSemesterAsWellAsProfileAndRevision() {
        val snapshot = snapshot(revision = 8).copy(settings = settings.copy(id = 42), scopeToken = "profile-A")
        val preview = TimetableImportRules.preview(snapshot, ParsedTimetableImport(listOf(draft(meeting()))))
        assertEquals(42L, preview.targetSemesterId)
        val command = TimetableImportCommand(
            courses = preview.entries.map { it.effectiveCourse },
            mode = preview.mode,
            expectedRevision = preview.expectedRevision,
            targetSemesterId = preview.targetSemesterId,
            targetScopeToken = preview.targetScopeToken,
        )
        assertEquals(42L, command.targetSemesterId)
        assertEquals("profile-A", preview.targetScopeToken)
        assertEquals(8L, preview.expectedRevision)
    }

    private fun snapshot(courses: List<ScheduledCourse> = emptyList(), revision: Long = 0) =
        TimetableSnapshot(settings, defaultPeriods(), courses, revision)
    private fun scheduled(id: Long, draft: CourseDraft) =
        ScheduledCourse(id, draft.courseName, draft.teacher, draft.room, draft.color, draft.meetings)
    private fun draft(meeting: CourseMeeting) = CourseDraft("课程", "默认教师", "默认教室", meetings = listOf(meeting))
    private fun meeting(weeks: Set<Int> = setOf(1), sections: Set<Int> = setOf(1)) =
        CourseMeeting(day = 1, weeks = weeks, sections = sections)
    private fun custom(start: String, end: String) =
        CourseMeeting(day = 1, weeks = setOf(1), sections = emptySet(), customTime = range(start, end))
    private fun range(start: String, end: String) = TimeRange(LocalTime.parse(start), LocalTime.parse(end))
}
