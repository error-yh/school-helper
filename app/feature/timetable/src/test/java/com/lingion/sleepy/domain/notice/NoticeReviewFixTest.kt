/* SPDX-License-Identifier: GPL-3.0-only
 * Regressions for the first risk review: custom clocks must survive a non-time change, malformed
 * roles must block instead of becoming "no change", occurrence identity must be proven and the
 * replaceable occurrence must be exact, and a make-up must carry an explicit course identity.
 */
package com.lingion.sleepy.domain.notice

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.ExceptionKind
import com.lingion.sleepy.domain.model.MeetingException
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.importing.DraftTimeRange
import com.lingion.sleepy.importing.NoticeIntentKind
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class NoticeReviewFixTest {
    private val friday = NoticeFixtures.friday
    private val custom = TimeRange(LocalTime.of(9, 10), LocalTime.of(10, 20))

    private fun course(meeting: CourseMeeting, teacher: String = "张老师", room: String = "A101", id: Long = 1, name: String = "数据可视化"): ScheduledCourse =
        NoticeFixtures.course(id, name, teacher = teacher, room = room, meetings = listOf(meeting))

    private fun customMeeting(id: Long = 11, sections: Set<Int> = setOf(3, 4)): CourseMeeting =
        NoticeFixtures.meeting(id = id, sections = sections, customTime = custom, room = "A101", teacher = "张老师")

    private fun propose(
        intent: NoticeIntent,
        snapshot: TimetableSnapshot,
        bases: List<com.lingion.sleepy.domain.imports.ImportLogicalBaseDisplay>,
        selection: NoticeCandidateId?,
        extraCourse: NoticeCourseSelection? = null,
        replace: Boolean = false,
        acknowledged: Set<String> = emptySet(),
    ): NoticeProposalResult = NoticeAdjustmentPlanner.propose(
        intent, snapshot, bases, NoticeFixtures.target(snapshot), selection,
        acknowledgedConflicts = acknowledged, replaceExistingAdjustment = replace, extraCourse = extraCourse,
    )

    private fun single(snapshot: TimetableSnapshot): Pair<ScheduledCourse, List<com.lingion.sleepy.domain.imports.ImportLogicalBaseDisplay>> {
        val course = snapshot.courses.single()
        return course to listOf(NoticeFixtures.base(course, course.meetings.single()))
    }

    @Test fun `room only keeps a custom clock instead of the default period envelope`() {
        val snapshot = NoticeFixtures.snapshot(listOf(course(customMeeting())))
        val (_, bases) = single(snapshot)
        val intent = NoticeFixtures.intent("2026-11-13数据可视化教室改为B203")
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val proposal = NoticeFixtures.ready(propose(intent, snapshot, bases, candidate.id))
        assertEquals(listOf(custom), proposal.after!!.ranges)
        assertEquals(setOf(3, 4), proposal.after!!.sections)
        assertEquals("B203", proposal.after!!.room)
        assertEquals(friday, proposal.after!!.date)
    }

    @Test fun `date only change keeps the custom clock and only moves the date`() {
        val snapshot = NoticeFixtures.snapshot(listOf(course(customMeeting())))
        val (_, bases) = single(snapshot)
        val intent = NoticeFixtures.intent("原2026-11-13数据可视化调至2026-11-14")
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val proposal = NoticeFixtures.ready(propose(intent, snapshot, bases, candidate.id))
        assertEquals(LocalDate.of(2026, 11, 14), proposal.after!!.date)
        assertEquals(listOf(custom), proposal.after!!.ranges)
        assertEquals(setOf(3, 4), proposal.after!!.sections)
        assertEquals("A101", proposal.after!!.room)
    }

    @Test fun `an already adjusted occurrence inherits its effective custom clock`() {
        val baseMeeting = NoticeFixtures.meeting(id = 11, sections = setOf(3, 4),
            customTime = TimeRange(LocalTime.of(8, 0), LocalTime.of(9, 40)), room = "A101", teacher = "张老师")
        val exception = MeetingException(id = 5, kind = ExceptionKind.RESCHEDULE, originalDate = friday,
            targetDate = friday, customTime = custom)
        val snapshot = NoticeFixtures.snapshotWithException(
            NoticeFixtures.snapshot(listOf(course(baseMeeting))), 1, 11, exception
        )
        val (_, bases) = single(snapshot)
        val intent = NoticeFixtures.intent("2026-11-13数据可视化教室改为B203")
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        assertTrue(candidate.alreadyAdjusted)
        val proposal = NoticeFixtures.ready(propose(intent, snapshot, bases, candidate.id, replace = true))
        assertEquals(listOf(custom), proposal.after!!.ranges)
        assertEquals("B203", proposal.after!!.room)
    }

    @Test fun `an invalid target clock is a correction not an inherited no-op`() {
        val snapshot = NoticeFixtures.snapshot(listOf(course(NoticeFixtures.meeting(id = 11, room = "A101"))))
        val (_, bases) = single(snapshot)
        val parsed = NoticeIntentParser.parse("原2026-11-13数据可视化调至2026-11-14 25:00-26:00", NoticeBasis(NoticeFixtures.basis)).single()
        assertTrue("$parsed", parsed is NoticeIntentResult.CorrectionRequired)
        val correction = parsed as NoticeIntentResult.CorrectionRequired
        assertTrue(correction.problems.any { it.code == NoticeCodes.INVALID_TARGET_TIME })
        assertTrue(correction.intent.invalidTargetTime)
        assertTrue(correction.intent.requiresCorrection)

        val blocked = propose(correction.intent, snapshot, bases, null)
        assertTrue("$blocked", blocked is NoticeProposalResult.Blocked)
        assertTrue((blocked as NoticeProposalResult.Blocked).problems.any { it.code == NoticeCodes.CORRECTION_REQUIRED })

        val corrected = correction.intent.withCorrections(
            NoticeCorrections(targetTime = DraftTimeRange(LocalTime.of(10, 0), LocalTime.of(11, 0)))
        )
        assertFalse(corrected.requiresCorrection)
        // Correcting an unrelated field must not clear the target blocker.
        assertTrue(correction.intent.withCorrections(NoticeCorrections(originalSections = setOf(3, 4))).requiresCorrection)
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(corrected, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val proposal = NoticeFixtures.ready(propose(corrected, snapshot, bases, candidate.id))
        assertEquals(LocalDate.of(2026, 11, 14), proposal.after!!.date)
        assertEquals(listOf(TimeRange(LocalTime.of(10, 0), LocalTime.of(11, 0))), proposal.after!!.ranges)
        // The old clock remains the stated match condition, so the original date is still 11-13.
        assertEquals(friday, proposal.before!!.date)
    }

    @Test fun `an invalid original section range blocks until corrected`() {
        val snapshot = NoticeFixtures.snapshot(listOf(course(NoticeFixtures.meeting(id = 11, room = "A101"))))
        val (_, bases) = single(snapshot)
        val parsed = NoticeIntentParser.parse("本周五数据可视化由第0-2节调整为第5-6节", NoticeBasis(NoticeFixtures.basis)).single()
        assertTrue("$parsed", parsed is NoticeIntentResult.CorrectionRequired)
        val correction = parsed as NoticeIntentResult.CorrectionRequired
        assertTrue(correction.problems.any { it.code == NoticeCodes.INVALID_ORIGINAL_SECTIONS })
        assertTrue(correction.intent.invalidOriginalSections)

        val corrected = correction.intent.withCorrections(NoticeCorrections(originalSections = setOf(3, 4)))
        assertFalse(corrected.requiresCorrection)
        // Correcting the target field must not clear the original blocker either.
        assertTrue(correction.intent.withCorrections(NoticeCorrections(targetSections = setOf(5, 6))).requiresCorrection)
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(corrected, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val proposal = NoticeFixtures.ready(propose(corrected, snapshot, bases, candidate.id))
        assertEquals(setOf(3, 4), proposal.before!!.sections)
        assertEquals(setOf(5, 6), proposal.after!!.sections)
    }

    @Test fun `same shape meetings with different rooms stay distinct candidates`() {
        val meetings = listOf(
            NoticeFixtures.meeting(id = 11, sections = setOf(3, 4), room = "A101", teacher = "张老师"),
            NoticeFixtures.meeting(id = 12, sections = setOf(3, 4), room = "A102", teacher = "张老师"),
        )
        val snapshot = NoticeFixtures.snapshot(listOf(NoticeFixtures.course(1, "数据可视化", meetings = meetings)))
        val course = snapshot.courses.single()
        val bases = meetings.map { NoticeFixtures.base(course, it) }
        val intent = NoticeFixtures.intent("本周五数据可视化调整为第5-6节")
        val candidates = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot)))
        assertEquals(2, candidates.size)
        assertEquals(setOf(11L, 12L), candidates.map { it.meetingId }.toSet())
        assertEquals(setOf("A101", "A102"), candidates.map { it.currentRoom }.toSet())
        assertEquals(2, candidates.map { it.id }.toSet().size)
    }

    @Test fun `identical identities are ambiguous instead of first guessed`() {
        val meetings = listOf(
            NoticeFixtures.meeting(id = 11, sections = setOf(3, 4), room = "A101", teacher = "张老师"),
            NoticeFixtures.meeting(id = 12, sections = setOf(3, 4), room = "A101", teacher = "张老师"),
        )
        val snapshot = NoticeFixtures.snapshot(listOf(NoticeFixtures.course(1, "数据可视化", meetings = meetings)))
        val course = snapshot.courses.single()
        val bases = meetings.map { NoticeFixtures.base(course, it) }
        val intent = NoticeFixtures.intent("本周五数据可视化调整为第5-6节")
        val result = NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot))
        assertTrue("$result", result is NoticeCandidateResult.None)
        assertTrue((result as NoticeCandidateResult.None).problems.any { it.code == NoticeCodes.AMBIGUOUS_OCCURRENCE })
    }

    @Test fun `a same meeting extra is a conflict and not excluded with the replaced base`() {
        val meeting = NoticeFixtures.meeting(id = 11, sections = setOf(3, 4), room = "A101", teacher = "张老师")
        val extra = MeetingException(kind = ExceptionKind.EXTRA, targetDate = friday,
            customTime = TimeRange(LocalTime.of(14, 0), LocalTime.of(15, 40)))
        val snapshot = NoticeFixtures.snapshotWithException(
            NoticeFixtures.snapshot(listOf(course(meeting))), 1, 11, extra
        )
        val (_, bases) = single(snapshot)
        val intent = NoticeFixtures.intent("本周五数据可视化由第3-4节调整为第5-6节")
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val result = propose(intent, snapshot, bases, candidate.id)
        assertTrue("$result", result is NoticeProposalResult.NotReady)
        val notReady = result as NoticeProposalResult.NotReady
        assertTrue(notReady.problems.any { it.code == NoticeCodes.CONFLICT_PENDING })
        assertTrue(notReady.proposal.conflicts.any { it.otherMeetingId == 11L && it.otherRanges == listOf(TimeRange(LocalTime.of(14, 0), LocalTime.of(15, 40))) })
        val acknowledged = NoticeFixtures.ready(
            propose(intent, snapshot, bases, candidate.id, acknowledged = setOf(notReady.proposal.conflicts.single().fingerprint))
        )
        assertEquals(1, acknowledged.conflicts.size)
    }

    @Test fun `a make-up requires an explicit existing course`() {
        val snapshot = NoticeFixtures.snapshot(listOf(course(NoticeFixtures.meeting(id = 11))))
        val intent = NoticeFixtures.intent("2026-11-20 16:00-17:30 数据可视化补课")
        val blocked = propose(intent, snapshot, emptyList(), null)
        assertTrue("$blocked", blocked is NoticeProposalResult.Blocked)
        assertTrue((blocked as NoticeProposalResult.Blocked).problems.any { it.code == NoticeCodes.NEED_COURSE_SELECTION })
    }

    @Test fun `make-ups in the same slot but different courses get different keys`() {
        // Same title slot, same teacher/room: only the real course identity separates the keys.
        val first = NoticeFixtures.course(1, "数据可视化", teacher = "张老师", room = "A101",
            meetings = listOf(NoticeFixtures.meeting(id = 11, room = "A101", teacher = "张老师")))
        val second = NoticeFixtures.course(2, "数据可视化", teacher = "张老师", room = "A101",
            meetings = listOf(NoticeFixtures.meeting(id = 21, room = "A101", teacher = "张老师")))
        val snapshot = NoticeFixtures.snapshot(listOf(first, second))
        val intent = NoticeFixtures.intent("2026-11-20 16:00-17:30 补课")
        val firstProposal = NoticeFixtures.ready(
            propose(intent, snapshot, emptyList(), null, extraCourse = NoticeCourseSelection(1))
        )
        val secondProposal = NoticeFixtures.ready(
            propose(intent, snapshot, emptyList(), null, extraCourse = NoticeCourseSelection(2))
        )
        assertEquals(1L, firstProposal.courseId)
        assertEquals(2L, secondProposal.courseId)
        assertNotEquals(firstProposal.semanticKey, secondProposal.semanticKey)
        val again = NoticeFixtures.ready(
            propose(intent, snapshot, emptyList(), null, extraCourse = NoticeCourseSelection(1))
        )
        assertEquals(firstProposal.semanticKey, again.semanticKey)
    }

    @Test fun `a make-up inherits the selected course teacher and room`() {
        val first = NoticeFixtures.course(1, "数据可视化", teacher = "张老师", room = "A101",
            meetings = listOf(NoticeFixtures.meeting(id = 11, room = "A101", teacher = "张老师")))
        val second = NoticeFixtures.course(2, "大学英语", teacher = "李老师", room = "B202",
            meetings = listOf(NoticeFixtures.meeting(id = 21, room = "B202", teacher = "李老师")))
        val snapshot = NoticeFixtures.snapshot(listOf(first, second))
        val intent = NoticeFixtures.intent("2026-11-20 16:00-17:30 补课")
        val proposal = NoticeFixtures.ready(
            propose(intent, snapshot, emptyList(), null, extraCourse = NoticeCourseSelection(2))
        )
        assertEquals(2L, proposal.courseId)
        assertEquals("大学英语", proposal.courseTitle)
        assertEquals("李老师", proposal.after!!.teacher)
        assertEquals("B202", proposal.after!!.room)
    }

    @Test fun `a cancelled occurrence with a carried old clock still maps to one date`() {
        // Sanity: the invalid-role guard does not swallow a valid single cancellation.
        val snapshot = NoticeFixtures.snapshot(listOf(course(NoticeFixtures.meeting(id = 11, room = "A101"))))
        val intent = NoticeFixtures.intent("2026-11-20数据可视化第3-4节停课", NoticeFixtures.emptyBasis)
        assertEquals(NoticeIntentKind.CANCEL, intent.kind)
        assertEquals(setOf(3, 4), intent.original.sections)
        assertEquals(LocalDate.of(2026, 11, 20), intent.originalDate)
        assertFalse(intent.requiresCorrection)
        assertEquals(1, TimetableRules.occurrencesOn(snapshot, LocalDate.of(2026, 11, 20)).size)
    }

    @Test fun `occurrence resolver matches when physical meeting has empty sections and normalized zero day but base retains imported sections and day under custom clock`() {
        // 物理落地行：按 planner.reconstruct 规范化，kind=DATE_SPECIFIC 时 day=0，明确时钟下 sections 为空
        val physicalMeeting = NoticeFixtures.meeting(id = 11, day = 0, sections = emptySet(), customTime = custom, room = "信工楼401", teacher = "张老师", kind = com.lingion.sleepy.domain.model.MeetingKind.DATE_SPECIFIC, dates = setOf(friday), weeks = emptySet())
        val course = course(physicalMeeting, teacher = "张老师", room = "信工楼401", name = "Hadoop大数据实验")
        val snapshot = NoticeFixtures.snapshot(listOf(course))

        // 逻辑原始 base：保留了 OCR/导入解析的原始星期 day=5、节次 sections=setOf(1, 2) 以及 customTime
        val importedOriginal = physicalMeeting.copy(day = 5, sections = setOf(1, 2))
        val base = NoticeFixtures.base(course, importedOriginal)

        val intent = NoticeFixtures.intent("2026-11-13Hadoop大数据实验教室改为信工实验楼502")
        val resolved = NoticeOccurrenceResolver.resolve(intent, snapshot, listOf(base), NoticeFixtures.target(snapshot))
        val candidate = NoticeFixtures.candidates(resolved).single()

        assertEquals(course.id, candidate.id.courseId)
        assertEquals("信工楼401", candidate.currentRoom)
        assertEquals(listOf(custom), candidate.currentRanges)
    }
}
