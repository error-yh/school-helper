/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.notice

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.ExceptionKind
import com.lingion.sleepy.domain.model.MeetingException
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.importing.ImportField
import com.lingion.sleepy.importing.NoticeIntentKind
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class NoticeAdjustmentPlanningTest {
    private val monday = NoticeFixtures.termMonday
    private val friday = NoticeFixtures.friday

    private fun campus(meetings: List<com.lingion.sleepy.domain.model.CourseMeeting> = listOf(NoticeFixtures.meeting(id = 11, room = "A101", teacher = "张老师"))): ScheduledCourse =
        NoticeFixtures.course(1, "数据可视化", teacher = "张老师", room = "A101", meetings = meetings)

    private fun singleCourse(): TimetableSnapshot = NoticeFixtures.snapshot(listOf(campus()))

    private fun selection(courseId: Long = 1, family: String = "family-$courseId-11") = NoticeCandidateId(
        courseId, family, com.lingion.sleepy.domain.imports.ImportCanonical.digest(NoticeFixtures.meeting(id = 0))
    )

    @Test fun `the accepted example changes only the tenth week friday`() {
        val snapshot = singleCourse()
        val course = snapshot.courses.single()
        val bases = listOf(NoticeFixtures.base(course, course.meetings.single()))
        val intent = NoticeFixtures.intent("本周五的数据可视化课程由原来的第3-4节调整为第5-6节，教室不变。")
        val found = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot)))
        assertEquals(1, found.size)
        assertEquals(1L, found.single().id.courseId)
        val proposal = NoticeFixtures.ready(
            NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), found.single().id)
        )
        assertEquals(friday, proposal.before!!.date)
        assertEquals(friday, proposal.after!!.date)
        assertEquals(setOf(3, 4), proposal.before!!.sections)
        assertEquals(setOf(5, 6), proposal.after!!.sections)
        assertEquals(NoticeFixtures.ranges(3, 4), proposal.before!!.ranges)
        assertEquals(NoticeFixtures.ranges(5, 6), proposal.after!!.ranges)
        assertEquals("A101", proposal.before!!.room)
        assertEquals("A101", proposal.after!!.room)
        assertTrue(ImportField.ROOM in proposal.keptFields)
        assertEquals(listOf(friday), proposal.impact.affectedDates)
        assertTrue(proposal.conflicts.isEmpty())

        val applied = NoticeFixtures.snapshotWithException(snapshot, 1, 11,
            MeetingException(kind = ExceptionKind.RESCHEDULE, originalDate = friday, targetDate = friday, sections = setOf(5, 6)))
        val before = projection(snapshot)
        val after = projection(applied)
        assertEquals("only the adjusted date may differ", before.filter { it.first != friday }, after.filter { it.first != friday })
        assertEquals(setOf(3, 4), occurrencesOn(snapshot, friday).single().second)
        assertEquals(setOf(5, 6), occurrencesOn(applied, friday).single().second)
        assertEquals("week 9 keeps its own occurrence", 1, occurrencesOn(snapshot, LocalDate.of(2026, 11, 6)).size)
        assertEquals("week 11 keeps its own occurrence", 1, occurrencesOn(snapshot, LocalDate.of(2026, 11, 20)).size)
    }

    @Test fun `same day time only proposal keeps the room and the date`() {
        val snapshot = singleCourse()
        val course = snapshot.courses.single()
        val bases = listOf(NoticeFixtures.base(course, course.meetings.single()))
        val intent = NoticeFixtures.intent("原2026-11-13数据可视化调至14:00-15:30")
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val proposal = NoticeFixtures.ready(NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), candidate.id))
        assertEquals(friday, proposal.after!!.date)
        assertEquals(listOf(TimeRange(LocalTime.of(14, 0), LocalTime.of(15, 30))), proposal.after!!.ranges)
        assertEquals("A101", proposal.after!!.room)
    }

    @Test fun `cross day proposal shows both dates and does not touch other weeks`() {
        val snapshot = singleCourse()
        val course = snapshot.courses.single()
        val bases = listOf(NoticeFixtures.base(course, course.meetings.single()))
        val intent = NoticeFixtures.intent("原2026-11-13数据可视化调至2026-11-14 10:00-11:30")
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val proposal = NoticeFixtures.ready(NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), candidate.id))
        assertEquals(friday, proposal.before!!.date)
        assertEquals(LocalDate.of(2026, 11, 14), proposal.after!!.date)
        assertEquals(listOf(friday, LocalDate.of(2026, 11, 14)), proposal.impact.affectedDates)
        assertTrue(proposal.conflicts.isEmpty())
    }

    @Test fun `cancel removes exactly one occurrence`() {
        val snapshot = singleCourse()
        val target = LocalDate.of(2026, 11, 20)
        val course = snapshot.courses.single()
        val bases = listOf(NoticeFixtures.base(course, course.meetings.single()))
        val intent = NoticeFixtures.intent("2026-11-20数据可视化停课")
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val proposal = NoticeFixtures.ready(NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), candidate.id))
        assertNull(proposal.after)
        assertEquals(setOf(3, 4), proposal.before!!.sections)
        val applied = NoticeFixtures.snapshotWithException(snapshot, 1, 11,
            MeetingException(kind = ExceptionKind.CANCEL, originalDate = target))
        assertTrue(occurrencesOn(applied, target).isEmpty())
        assertEquals(projection(snapshot).filter { it.first != target }, projection(applied).filter { it.first != target })
    }

    @Test fun `extra adds one occurrence and removes nothing`() {
        val snapshot = singleCourse()
        val target = LocalDate.of(2026, 11, 20)
        val intent = NoticeFixtures.intent("2026-11-20 16:00-17:30 数据可视化补课")
        val proposal = NoticeFixtures.ready(
            NoticeAdjustmentPlanner.propose(intent, snapshot, emptyList(), NoticeFixtures.target(snapshot), null,
                extraCourse = NoticeCourseSelection(1))
        )
        assertNull(proposal.before)
        assertEquals(target, proposal.after!!.date)
        assertEquals(1L, proposal.courseId)
        assertEquals("数据可视化", proposal.courseTitle)
        assertEquals("张老师", proposal.after!!.teacher)
        assertEquals("A101", proposal.after!!.room)
        assertEquals(listOf(TimeRange(LocalTime.of(16, 0), LocalTime.of(17, 30))), proposal.after!!.ranges)
        assertEquals(NoticeIntentKind.EXTRA, proposal.kind)
        val applied = NoticeFixtures.snapshotWithException(snapshot, 1, 11,
            MeetingException(kind = ExceptionKind.EXTRA, targetDate = target, customTime = TimeRange(LocalTime.of(16, 0), LocalTime.of(17, 30))))
        val appliedOccurrences = TimetableRules.occurrencesOn(applied, target)
        assertEquals("the base Friday occurrence is not removed", 1,
            appliedOccurrences.count { it.meeting.id == 11L && it.exception == null })
        assertEquals("the extra occurrence is added once", 1,
            appliedOccurrences.count { it.exception?.kind == ExceptionKind.EXTRA })
        assertEquals(projection(snapshot).filter { it.first != target }, projection(applied).filter { it.first != target })
    }

    @Test fun `room only proposal changes only the room`() {
        val snapshot = singleCourse()
        val course = snapshot.courses.single()
        val bases = listOf(NoticeFixtures.base(course, course.meetings.single()))
        val intent = NoticeFixtures.intent("2026-11-13数据可视化第3-4节教室改为B203")
        assertEquals(NoticeIntentKind.ROOM_ONLY, intent.kind)
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val proposal = NoticeFixtures.ready(NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), candidate.id))
        assertEquals("A101", proposal.before!!.room)
        assertEquals("B203", proposal.after!!.room)
        assertEquals(proposal.before!!.sections, proposal.after!!.sections)
        assertEquals(proposal.before!!.ranges, proposal.after!!.ranges)
        assertEquals(proposal.before!!.date, proposal.after!!.date)
        assertTrue(ImportField.TEACHER in proposal.keptFields)
    }

    @Test fun `same name different course id is never merged and needs an explicit choice`() {
        val other = NoticeFixtures.course(2, "数据可视化", teacher = "李老师", room = "B202",
            meetings = listOf(NoticeFixtures.meeting(id = 21, room = "B202", teacher = "李老师")))
        val snapshot = NoticeFixtures.snapshot(listOf(campus(), other))
        val bases = snapshot.courses.flatMap { course -> course.meetings.map { NoticeFixtures.base(course, it) } }
        val intent = NoticeFixtures.intent("本周五数据可视化调整为第5-6节")
        val found = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot)))
        assertEquals(2, found.map { it.id.courseId }.toSet().size)
        val blocked = NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), null)
        assertTrue("$blocked", blocked is NoticeProposalResult.Blocked)
        assertEquals(2, (blocked as NoticeProposalResult.Blocked).candidates.size)
        assertTrue(blocked.problems.any { it.code == NoticeCodes.NEED_MANUAL_SELECTION })
        val chosen = found.single { it.id.courseId == 2L }
        val proposal = NoticeFixtures.ready(NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), chosen.id))
        assertEquals(2L, proposal.candidate?.courseId)
        assertEquals("B202", proposal.before!!.room)
    }

    @Test fun `same course twice on one day is disambiguated by the stated old sections`() {
        val course = campus(meetings = listOf(
            NoticeFixtures.meeting(id = 11, sections = setOf(3, 4), room = "A101"),
            NoticeFixtures.meeting(id = 12, sections = setOf(7, 8), room = "A102"),
        ))
        val snapshot = NoticeFixtures.snapshot(listOf(course))
        val bases = listOf(NoticeFixtures.base(course, course.meetings[0]), NoticeFixtures.base(course, course.meetings[1]))
        val ambiguous = NoticeFixtures.intent("本周五数据可视化调整为第5-6节")
        val found = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(ambiguous, snapshot, bases, NoticeFixtures.target(snapshot)))
        assertEquals(2, found.size)
        val precise = NoticeFixtures.intent("本周五数据可视化由第7-8节调整为第5-6节")
        val exact = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(precise, snapshot, bases, NoticeFixtures.target(snapshot)))
        assertEquals(1, exact.size)
        assertEquals(12L, exact.single().meetingId)
    }

    @Test fun `a wrong old time requires an explicit correction before a proposal`() {
        val snapshot = singleCourse()
        val course = snapshot.courses.single()
        val bases = listOf(NoticeFixtures.base(course, course.meetings.single()))
        val wrong = NoticeFixtures.intent("本周五数据可视化由第1-2节调整为第5-6节")
        val none = NoticeOccurrenceResolver.resolve(wrong, snapshot, bases, NoticeFixtures.target(snapshot))
        assertTrue("$none", none is NoticeCandidateResult.None)
        assertTrue((none as NoticeCandidateResult.None).problems.any { it.code == NoticeCodes.OLD_SCOPE_MISMATCH })
        val blocked = NoticeAdjustmentPlanner.propose(wrong, snapshot, bases, NoticeFixtures.target(snapshot), selection())
        assertTrue("$blocked", blocked is NoticeProposalResult.Blocked)

        val corrected = wrong.withCorrections(NoticeCorrections(originalSections = setOf(3, 4)))
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(corrected, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val proposal = NoticeFixtures.ready(NoticeAdjustmentPlanner.propose(corrected, snapshot, bases, NoticeFixtures.target(snapshot), candidate.id))
        assertEquals(setOf(3, 4), proposal.before!!.sections)
        // A different correction changes the semantic key and therefore invalidates the old digest.
        val otherTarget = wrong.withCorrections(NoticeCorrections(originalSections = setOf(3, 4), targetSections = setOf(7, 8)))
        val otherCandidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(otherTarget, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val otherProposal = NoticeFixtures.ready(NoticeAdjustmentPlanner.propose(otherTarget, snapshot, bases, NoticeFixtures.target(snapshot), otherCandidate.id))
        assertNotEquals(proposal.semanticKey, otherProposal.semanticKey)
    }

    @Test fun `an already adjusted occurrence needs the explicit replacement answer`() {
        val exception = MeetingException(id = 5, kind = ExceptionKind.RESCHEDULE, originalDate = friday, targetDate = friday, sections = setOf(7, 8))
        val snapshot = NoticeFixtures.snapshotWithException(singleCourse(), 1, 11, exception)
        val course = snapshot.courses.single()
        val bases = listOf(NoticeFixtures.base(course, course.meetings.single()))
        val intent = NoticeFixtures.intent("本周五数据可视化由原来的第7-8节调整为第5-6节")
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        assertTrue(candidate.alreadyAdjusted)
        assertEquals(setOf(7, 8), candidate.currentSections)
        val blocked = NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), candidate.id)
        assertTrue("$blocked", blocked is NoticeProposalResult.Blocked)
        assertTrue((blocked as NoticeProposalResult.Blocked).problems.any { it.code == NoticeCodes.ALREADY_ADJUSTED })
        val proposal = NoticeFixtures.ready(
            NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), candidate.id, replaceExistingAdjustment = true)
        )
        assertTrue(proposal.replacesExistingAdjustment)
        assertEquals(5L, proposal.replacedExceptionId)
        assertEquals(setOf(7, 8), proposal.before!!.sections)
        assertEquals(setOf(5, 6), proposal.after!!.sections)
    }

    @Test fun `an unresolved conflict keeps the proposal not ready until acknowledged`() {
        val english = NoticeFixtures.course(3, "大学英语", teacher = "王老师", room = "C303",
            meetings = listOf(NoticeFixtures.meeting(id = 31, sections = setOf(5, 6), room = "C303", teacher = "王老师")))
        val snapshot = NoticeFixtures.snapshot(listOf(campus(), english))
        val bases = snapshot.courses.flatMap { course -> course.meetings.map { NoticeFixtures.base(course, it) } }
        val intent = NoticeFixtures.intent("本周五数据可视化由第3-4节调整为第5-6节")
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot))).single { it.id.courseId == 1L }
        val conflict = NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), candidate.id)
        assertTrue("$conflict", conflict is NoticeProposalResult.NotReady)
        val problems = (conflict as NoticeProposalResult.NotReady).problems
        assertTrue(problems.any { it.code == NoticeCodes.CONFLICT_PENDING })
        assertEquals(1, conflict.proposal.conflicts.size)
        val acknowledged = NoticeFixtures.ready(
            NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), candidate.id,
                acknowledgedConflicts = setOf(conflict.proposal.conflicts.single().fingerprint))
        )
        assertEquals(1, acknowledged.conflicts.size)
    }

    @Test fun `no effective change and stale revision are refused`() {
        val snapshot = singleCourse()
        val course = snapshot.courses.single()
        val bases = listOf(NoticeFixtures.base(course, course.meetings.single()))
        val noChange = NoticeFixtures.intent("本周五数据可视化由第3-4节调整为第3-4节")
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(noChange, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val blocked = NoticeAdjustmentPlanner.propose(noChange, snapshot, bases, NoticeFixtures.target(snapshot), candidate.id)
        assertTrue("$blocked", blocked is NoticeProposalResult.Blocked)
        assertTrue((blocked as NoticeProposalResult.Blocked).problems.any { it.code == NoticeCodes.NO_EFFECTIVE_CHANGE })

        val stale = NoticeAdjustmentPlanner.propose(
            NoticeFixtures.intent("本周五数据可视化由第3-4节调整为第5-6节"), snapshot, bases,
            NoticeFixtures.target(snapshot, revision = snapshot.revision + 1), candidate.id
        )
        assertTrue("$stale", stale is NoticeProposalResult.Blocked)
        assertTrue((stale as NoticeProposalResult.Blocked).problems.any { it.code == NoticeCodes.STALE_REVISION })
    }

    @Test fun `same semantics keep the key while a revision only invalidates the confirmation`() {
        val snapshot = singleCourse()
        val course = snapshot.courses.single()
        val bases = listOf(NoticeFixtures.base(course, course.meetings.single()))
        val intent = NoticeFixtures.intent("本周五数据可视化由第3-4节调整为第5-6节")
        val candidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(intent, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val first = NoticeFixtures.ready(NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), candidate.id))
        val again = NoticeFixtures.ready(NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), candidate.id))
        assertEquals(first.semanticKey, again.semanticKey)
        assertEquals(first.confirmDigest, again.confirmDigest)

        val nextRevision = snapshot.copy(revision = snapshot.revision + 1)
        val recomputed = NoticeFixtures.ready(
            NoticeAdjustmentPlanner.propose(intent, nextRevision, bases, NoticeFixtures.target(nextRevision), candidate.id)
        )
        assertEquals(first.semanticKey, recomputed.semanticKey)
        assertNotEquals(first.confirmDigest, recomputed.confirmDigest)

        val otherDate = NoticeFixtures.intent("2026-11-20数据可视化由第3-4节调整为第5-6节")
        val otherCandidate = NoticeFixtures.candidates(NoticeOccurrenceResolver.resolve(otherDate, snapshot, bases, NoticeFixtures.target(snapshot))).single()
        val otherProposal = NoticeFixtures.ready(NoticeAdjustmentPlanner.propose(otherDate, snapshot, bases, NoticeFixtures.target(snapshot), otherCandidate.id))
        assertNotEquals(first.semanticKey, otherProposal.semanticKey)
    }

    @Test fun `dates outside the confirmed term and unknown selections are refused`() {
        val snapshot = singleCourse()
        val course = snapshot.courses.single()
        val bases = listOf(NoticeFixtures.base(course, course.meetings.single()))
        val crossYear = NoticeFixtures.intent(
            "下周一数据可视化停课",
            dateBasis = com.lingion.sleepy.importing.NoticeDateBasis(
                referenceDate = LocalDate.of(2026, 12, 30),
                termMonday = monday,
                totalWeeks = 16,
            ),
        )
        val outside = NoticeOccurrenceResolver.resolve(crossYear, snapshot, bases, NoticeFixtures.target(snapshot))
        assertTrue("$outside", outside is NoticeCandidateResult.None)
        assertTrue((outside as NoticeCandidateResult.None).problems.any { it.code == NoticeCodes.DATE_OUTSIDE_TERM })

        val intent = NoticeFixtures.intent("本周五数据可视化由第3-4节调整为第5-6节")
        val stale = NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot),
            NoticeCandidateId(99, "missing", "missing"))
        assertTrue("$stale", stale is NoticeProposalResult.Blocked)
        assertTrue((stale as NoticeProposalResult.Blocked).problems.any { it.code == NoticeCodes.STALE_CANDIDATE })
    }

    @Test fun `a single candidate is still only a suggestion`() {
        val snapshot = singleCourse()
        val course = snapshot.courses.single()
        val bases = listOf(NoticeFixtures.base(course, course.meetings.single()))
        val intent = NoticeFixtures.intent("本周五数据可视化由第3-4节调整为第5-6节")
        val blocked = NoticeAdjustmentPlanner.propose(intent, snapshot, bases, NoticeFixtures.target(snapshot), null)
        assertTrue("$blocked", blocked is NoticeProposalResult.Blocked)
        assertEquals(1, (blocked as NoticeProposalResult.Blocked).candidates.size)
        assertTrue(blocked.problems.any { it.code == NoticeCodes.NEED_MANUAL_SELECTION })
    }

    private fun projection(snapshot: TimetableSnapshot): List<Triple<LocalDate, Long, List<TimeRange>>> =
        TimetableRules.occurrencesBetween(snapshot, monday, monday.plusWeeks(snapshot.settings.totalWeeks.toLong()))
            .map { Triple(it.date, it.course.id, it.timeRanges) }

    private fun occurrencesOn(snapshot: TimetableSnapshot, date: LocalDate): List<Pair<Long, Set<Int>>> =
        TimetableRules.occurrencesOn(snapshot, date).map { it.course.id to it.effectiveSections }
}