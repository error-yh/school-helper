/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.notice

import com.lingion.sleepy.domain.imports.ImportCanonical
import com.lingion.sleepy.domain.imports.ImportLogicalBaseDisplay
import com.lingion.sleepy.domain.imports.ImportTarget
import com.lingion.sleepy.domain.imports.LogicalBaseReference
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.MeetingException
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.importing.NoticeBigSectionMapping
import com.lingion.sleepy.importing.NoticeDateBasis
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertTrue

/** Synthetic term and courses only; no real notice, course or personal data is used. */
internal object NoticeFixtures {
    val termMonday: LocalDate = LocalDate.of(2026, 9, 7)
    val referenceMonday: LocalDate = LocalDate.of(2026, 11, 9) // week 10
    val friday: LocalDate = LocalDate.of(2026, 11, 13)

    val basis: NoticeDateBasis = NoticeDateBasis(referenceDate = referenceMonday, termMonday = termMonday, totalWeeks = 16)
    val emptyBasis: NoticeDateBasis = NoticeDateBasis()

    val periods: List<Period> = listOf(
        Period(1, LocalTime.of(8, 0), LocalTime.of(8, 45)),
        Period(2, LocalTime.of(8, 55), LocalTime.of(9, 40)),
        Period(3, LocalTime.of(10, 0), LocalTime.of(10, 45)),
        Period(4, LocalTime.of(10, 55), LocalTime.of(11, 40)),
        Period(5, LocalTime.of(14, 0), LocalTime.of(14, 45)),
        Period(6, LocalTime.of(14, 55), LocalTime.of(15, 40)),
        Period(7, LocalTime.of(16, 0), LocalTime.of(16, 45)),
        Period(8, LocalTime.of(16, 55), LocalTime.of(17, 40)),
    )

    fun ranges(vararg sections: Int): List<TimeRange> = ranges(sections.toSet())
    fun ranges(sections: Set<Int>): List<TimeRange> =
        periods.filter { it.number in sections }.map { TimeRange(it.start, it.end) }

    fun meeting(
        id: Long = 11,
        day: Int = 5,
        weeks: Set<Int> = (1..16).toSet(),
        sections: Set<Int> = setOf(3, 4),
        customTime: TimeRange? = null,
        room: String? = null,
        teacher: String? = null,
        kind: MeetingKind = MeetingKind.RECURRING,
        dates: Set<LocalDate> = emptySet(),
        project: String? = null,
        group: String? = null,
        exceptions: List<MeetingException> = emptyList(),
    ): CourseMeeting = CourseMeeting(
        id = id, day = day, weeks = weeks, sections = sections, customTime = customTime,
        teacher = teacher, room = room, kind = kind, dates = dates,
        experimentProject = project, group = group, exceptions = exceptions,
    )

    fun course(
        id: Long,
        name: String,
        teacher: String = "张老师",
        room: String = "A101",
        meetings: List<CourseMeeting>,
    ): ScheduledCourse = ScheduledCourse(id, name, teacher, room, 0xFF6750A4, meetings)

    fun snapshot(
        courses: List<ScheduledCourse>,
        revision: Long = 7,
        settings: SemesterSettings = SemesterSettings(1, "合成学期", termMonday, 16),
    ): TimetableSnapshot = TimetableSnapshot(settings, periods, courses, revision, "local")

    fun base(course: ScheduledCourse, meeting: CourseMeeting, family: String = "family-${course.id}-${meeting.id}"): ImportLogicalBaseDisplay {
        val original = meeting.copy(id = 0, exceptions = emptyList())
        val intervals = original.customTime?.let { listOf(it) } ?: ranges(original.sections)
        return ImportLogicalBaseDisplay(
            LogicalBaseReference.StoredLogicalBase("local", 1, course.id, family, ImportCanonical.digest(original)),
            course.courseName, original, intervals,
            meeting.teacher ?: course.teacher, meeting.room ?: course.room,
            meeting.experimentProject, meeting.group,
        )
    }

    fun target(snapshot: TimetableSnapshot, revision: Long = snapshot.revision): ImportTarget =
        ImportTarget("local", 1, revision, 4)

    fun intent(
        text: String,
        dateBasis: NoticeDateBasis = basis,
        mapping: NoticeBigSectionMapping? = null,
    ): NoticeIntent {
        val results = NoticeIntentParser.parse(text, NoticeBasis(dateBasis, mapping))
        val result = results.single()
        assertTrue("expected Ready but was $result", result is NoticeIntentResult.Ready)
        return (result as NoticeIntentResult.Ready).intent
    }

    fun candidates(result: NoticeCandidateResult): List<NoticeCandidate> {
        assertTrue("expected Found but was $result", result is NoticeCandidateResult.Found)
        return (result as NoticeCandidateResult.Found).candidates
    }

    fun ready(result: NoticeProposalResult): NoticeAdjustmentProposal {
        assertTrue("expected Ready but was $result", result is NoticeProposalResult.Ready)
        return (result as NoticeProposalResult.Ready).proposal
    }

    fun snapshotWithException(
        snapshot: TimetableSnapshot,
        courseId: Long,
        meetingId: Long,
        exception: MeetingException,
    ): TimetableSnapshot = snapshot.copy(courses = snapshot.courses.map { course ->
        if (course.id != courseId) course else course.copy(meetings = course.meetings.map { meeting ->
            if (meeting.id != meetingId) meeting else meeting.copy(exceptions = meeting.exceptions + exception)
        })
    })
}