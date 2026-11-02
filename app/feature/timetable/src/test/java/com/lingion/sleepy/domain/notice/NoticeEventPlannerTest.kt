/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.notice

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.ImportCanonical
import com.lingion.sleepy.domain.imports.ImportNormalizationCodec
import com.lingion.sleepy.domain.imports.ImportOccurrencePlanner
import com.lingion.sleepy.domain.imports.LogicalOriginal
import com.lingion.sleepy.domain.imports.OccurrenceIdentity
import com.lingion.sleepy.domain.imports.OwnedFragment
import com.lingion.sleepy.domain.imports.PlannedNormalizationState
import com.lingion.sleepy.domain.imports.StoredNormalizationState
import com.lingion.sleepy.domain.imports.fList
import com.lingion.sleepy.domain.imports.fSet
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.ExceptionKind
import com.lingion.sleepy.domain.model.MeetingException
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.importing.DraftTimeRange
import com.lingion.sleepy.importing.NoticeIntentKind
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class NoticeEventPlannerTest {
    private val friday = NoticeFixtures.friday

    private fun proposalFor(text: String, meeting: CourseMeeting = NoticeFixtures.meeting(id = 11, room = "A101", teacher = "张老师")): NoticeAdjustmentProposal {
        val course = NoticeFixtures.course(1, "数据可视化", teacher = "张老师", room = "A101", meetings = listOf(meeting))
        val snapshot = NoticeFixtures.snapshot(listOf(course))
        val bases = listOf(NoticeFixtures.base(course, course.meetings.single()))
        val target = NoticeFixtures.target(snapshot)
        val intent = NoticeFixtures.intent(text)
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, target)).single()
        return NoticeFixtures.ready(NoticeAdjustmentPlanner.propose(intent, snapshot, bases, target, candidate.id))
    }

    private fun extraProposal(): NoticeAdjustmentProposal {
        val course = NoticeFixtures.course(1, "数据可视化", teacher = "张老师", room = "A101",
            meetings = listOf(NoticeFixtures.meeting(id = 11, room = "A101", teacher = "张老师")))
        val snapshot = NoticeFixtures.snapshot(listOf(course))
        val intent = NoticeFixtures.intent("2026-11-20 16:00-17:30 数据可视化补课")
        return NoticeFixtures.ready(NoticeAdjustmentPlanner.propose(
            intent, snapshot, emptyList(), NoticeFixtures.target(snapshot), null, extraCourse = NoticeCourseSelection(1),
        ))
    }

    @Test fun `request identity is content based, wording independent and neutral to revision`() {
        val first = proposalFor("本周五数据可视化由第3-4节调整为第5-6节")
        val repeated = proposalFor("本周五数据可视化由第3-4节调整至第5-6节")
        val key = NoticeEventPlanner.requestKey("local", 1, first.let { NoticeFixtures.intent("本周五数据可视化由第3-4节调整为第5-6节") }, first, null)
        val repeatedKey = NoticeEventPlanner.requestKey("local", 1,
            NoticeFixtures.intent("本周五数据可视化由第3-4节调整至第5-6节"), repeated, null)
        assertEquals(key, repeatedKey)
        val other = proposalFor("本周五数据可视化由第3-4节调整为第7-8节")
        val otherKey = NoticeEventPlanner.requestKey("local", 1,
            NoticeFixtures.intent("本周五数据可视化由第3-4节调整为第7-8节"), other, null)
        assertNotEquals(key, otherKey)
        assertNotEquals(key, NoticeEventPlanner.requestKey("other", 1,
            NoticeFixtures.intent("本周五数据可视化由第3-4节调整为第5-6节"), first, null))
    }

    @Test fun `after fingerprint treats cancellation as explicit absence`() {
        val cancelled = proposalFor("2026-11-20数据可视化停课")
        assertEquals("ABSENT", NoticeEventPlanner.afterFingerprint(cancelled.after))
        val moved = proposalFor("本周五数据可视化由第3-4节调整为第5-6节")
        assertNotEquals("ABSENT", NoticeEventPlanner.afterFingerprint(moved.after))
        assertNotEquals(NoticeEventPlanner.afterFingerprint(moved.after), NoticeEventPlanner.afterFingerprint(null))
    }

    @Test fun `manual row keeps unchanged metadata inherited and writes only the target scope`() {
        val moved = proposalFor("本周五数据可视化由第3-4节调整为第5-6节")
        val row = manualRow(moved)
        assertEquals(ExceptionKind.RESCHEDULE, row.kind)
        assertEquals(friday, row.originalDate)
        assertEquals(friday, row.targetDate)
        assertEquals(setOf(5, 6), row.sections)
        assertNull(row.customTime)
        assertNull(row.teacher)
        assertNull(row.room)
    }

    @Test fun `room only keeps the declared scope inherited`() {
        val room = proposalFor("2026-11-13数据可视化教室改为B203")
        assertEquals(NoticeIntentKind.ROOM_ONLY, room.kind)
        val row = manualRow(room)
        assertNull(row.sections)
        assertNull(row.customTime)
        assertEquals("B203", row.room)
        assertEquals(friday, row.originalDate)
        assertEquals(friday, row.targetDate)
    }

    @Test fun `cross day move keeps the base time inherited and changes only the date`() {
        val moved = proposalFor("原2026-11-13数据可视化调至2026-11-14")
        val row = manualRow(moved)
        assertEquals(LocalDate.of(2026, 11, 14), row.targetDate)
        assertNull(row.sections)
        assertNull(row.customTime)
    }

    @Test fun `a custom clock change is stored as a continuous range, never as guessed periods`() {
        val meeting = NoticeFixtures.meeting(id = 11, sections = setOf(3, 4),
            customTime = TimeRange(LocalTime.of(9, 10), LocalTime.of(10, 20)), room = "A101", teacher = "张老师")
        val moved = proposalFor("原2026-11-13数据可视化调至14:00-15:30", meeting)
        val row = manualRow(moved, meeting)
        assertEquals(TimeRange(LocalTime.of(14, 0), LocalTime.of(15, 30)), row.customTime)
        assertNull(row.sections)
    }

    @Test fun `cancel and make up rows carry the right presence semantics`() {
        val cancelled = proposalFor("2026-11-20数据可视化停课")
        val cancelRow = manualRow(cancelled)
        assertEquals(ExceptionKind.CANCEL, cancelRow.kind)
        assertEquals(LocalDate.of(2026, 11, 20), cancelRow.originalDate)
        assertNull(cancelRow.targetDate)

        val extra = extraProposal()
        val extraRow = manualRow(extra)
        assertEquals(ExceptionKind.EXTRA, extraRow.kind)
        assertNull(extraRow.originalDate)
        assertEquals(LocalDate.of(2026, 11, 20), extraRow.targetDate)
        assertEquals(TimeRange(LocalTime.of(16, 0), LocalTime.of(17, 30)), extraRow.customTime)
        assertEquals("张老师", extraRow.teacher)
        assertEquals("A101", extraRow.room)
    }

    private fun manualRow(
        proposal: NoticeAdjustmentProposal,
        meeting: CourseMeeting = NoticeFixtures.meeting(id = 11, room = "A101", teacher = "张老师"),
    ): MeetingException {
        val periods = NoticeFixtures.periods
        val result = NoticeEventPlanner.manualRow(
            proposal, periods, meeting.sections, TimetableRules.periodIntervals(meeting, periods),
            meeting.teacher ?: "张老师", meeting.room ?: "A101", meeting.experimentProject, meeting.group,
        )
        return (result as NoticeEventPlanner.ManualRow.Ready).exception
    }

    @Test fun `request identity includes make-up business fields`() {
        val intent = NoticeFixtures.intent("2026-11-20 16:00-17:30 数据可视化补课")
        val base = NoticeEventPlanner.requestKey("local", 1, intent, 1L, null, 1L)
        val otherProject = NoticeEventPlanner.requestKey("local", 1, intent.copy(project = "实验乙"), 1L, null, 1L)
        val otherGroup = NoticeEventPlanner.requestKey("local", 1, intent.copy(group = "B组"), 1L, null, 1L)
        val otherRoom = NoticeEventPlanner.requestKey("local", 1, intent.copy(room = "B203"), 1L, null, 1L)
        val otherTeacher = NoticeEventPlanner.requestKey("local", 1, intent.copy(teacher = "李老师"), 1L, null, 1L)
        assertNotEquals(base, otherProject)
        assertNotEquals(base, otherGroup)
        assertNotEquals(base, otherRoom)
        assertNotEquals(base, otherTeacher)
        // Same semantics in different words stays the same identity.
        val reworded = intent.copy(rawText = intent.rawText + " ")
        assertEquals(base, NoticeEventPlanner.requestKey("local", 1, reworded, 1L, null, 1L))
    }

    @Test fun `rebuild derives a legal lineage state from the current rows`() {
        val meeting = NoticeFixtures.meeting(id = 11, sections = setOf(3, 4), room = "A101", teacher = "张老师")
        val course = NoticeFixtures.course(1, "数据可视化", teacher = "张老师", room = "A101", meetings = listOf(meeting))
        val snapshot = NoticeFixtures.snapshot(listOf(course))
        val state = syntheticState(course, snapshot)
        val rebuilt = NoticeEventPlanner.rebuildNormalizedState(state, course, snapshot)
        assertTrue(rebuilt.graph.removes.isEmpty())
        assertTrue(rebuilt.graph.adds.isEmpty())

        val cancelled = course.copy(meetings = listOf(meeting.copy(exceptions = listOf(
            MeetingException(kind = ExceptionKind.CANCEL, originalDate = friday)))))
        val cancelSnapshot = snapshot.copy(courses = listOf(cancelled))
        val cancelState = NoticeEventPlanner.rebuildNormalizedState(state, cancelled, cancelSnapshot)
        assertEquals(2, cancelState.graph.removes.size)
        assertTrue(cancelState.ownedAdds.isEmpty())

        val extra = MeetingException(id = 900, kind = ExceptionKind.EXTRA, targetDate = LocalDate.of(2026, 11, 20),
            customTime = TimeRange(LocalTime.of(16, 0), LocalTime.of(17, 30)), sections = emptySet(),
            teacher = "张老师", room = "A101")
        val withExtra = course.copy(meetings = listOf(meeting.copy(exceptions = listOf(extra))))
        val extraSnapshot = snapshot.copy(courses = listOf(withExtra))
        val extraState = NoticeEventPlanner.rebuildNormalizedState(state, withExtra, extraSnapshot)
        assertEquals(1, extraState.ownedAdds.size)
        assertEquals(1, extraState.graph.adds.size)
    }

    /** Mirrors what an import would have hydrated for this single recurring meeting. */
    private fun syntheticState(course: com.lingion.sleepy.domain.model.ScheduledCourse,
                               snapshot: com.lingion.sleepy.domain.model.TimetableSnapshot): StoredNormalizationState {
        val meeting = course.meetings.single()
        val original = meeting.copy(id = 0, exceptions = emptyList())
        val identity = OccurrenceIdentity(course.courseName.trim(), course.sourceCourseCode, course.sourceClassCode,
            course.teacher.trim(), course.room.trim(), meeting.experimentProject, meeting.group, meeting.isExperiment)
        val intervals = TimetableRules.periodIntervals(meeting, snapshot.periods)
        val descriptor = ImportOccurrencePlanner.descriptorFingerprint(original, identity, intervals, true)
        val family = ImportCanonical.digest("synthetic-family")
        val allocated = ImportOccurrencePlanner.originalDates(original,
            snapshot.settings.startDate?.let(TimetableRules::mondayOf), snapshot.settings.totalWeeks)
            .flatMap { date -> intervals.map { TimeRange(it.start, it.end).let { range -> com.lingion.sleepy.domain.imports.DatedInterval(date, range) } } }
        val graph = PlannedNormalizationState(
            fList(listOf(LogicalOriginal(family, descriptor, original, identity, fSet(setOf(meeting.id)), fList(allocated), fList(intervals), true))),
            fList(emptyList()), fList(emptyList()), fList(emptyList()), fList(emptyList()),
        )
        return StoredNormalizationState(ImportNormalizationCodec.VERSION, snapshot.scopeToken, snapshot.settings.id, course.id,
            graph, fList(listOf(OwnedFragment(ImportCanonical.digest("fragment"), family, meeting.id,
                ImportNormalizationCodec.meetingFingerprint(meeting)))),
            fList(emptyList()), fList(emptyList()))
    }
}