/* SPDX-License-Identifier: GPL-3.0-only
 * Pure before/after proposal for exactly one occurrence. It reuses the existing calendar engine
 * for effective intervals and conflicts, and returns a frozen plan: no apply port, no token,
 * no Room write and no second scheduling engine.
 */
package com.lingion.sleepy.domain.notice

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.ImportCanonical
import com.lingion.sleepy.domain.imports.ImportLogicalBaseDisplay
import com.lingion.sleepy.domain.imports.ImportTarget
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.importing.ImportField
import com.lingion.sleepy.importing.NoticeIntentKind
import com.lingion.sleepy.importing.NoticeRoomRole
import java.time.LocalDate

object NoticeAdjustmentPlanner {
    /**
     * [selection] is an explicit user choice; a single resolved candidate is still only a
     * suggestion. [extraCourse] is the explicit existing-course identity a make-up occurrence
     * belongs to. [replaceExistingAdjustment] is the explicit answer for an occurrence that
     * already carries an exception; without it the plan is blocked instead of stacking a second
     * exception on the same original date.
     */
    fun propose(
        intent: NoticeIntent,
        snapshot: TimetableSnapshot,
        bases: List<ImportLogicalBaseDisplay>,
        target: ImportTarget,
        selection: NoticeCandidateId?,
        acknowledgedConflicts: Set<String> = emptySet(),
        replaceExistingAdjustment: Boolean = false,
        extraCourse: NoticeCourseSelection? = null,
    ): NoticeProposalResult {
        if (target.scopeToken != snapshot.scopeToken || target.semesterId != snapshot.settings.id) {
            return NoticeProposalResult.Blocked(listOf(NoticeProblem(NoticeCodes.CALENDAR_MISMATCH)))
        }
        if (target.expectedRevision != snapshot.revision) {
            return NoticeProposalResult.Blocked(listOf(NoticeProblem(NoticeCodes.STALE_REVISION)))
        }
        if (intent.requiresCorrection) {
            return NoticeProposalResult.Blocked(listOf(NoticeProblem(NoticeCodes.CORRECTION_REQUIRED)))
        }
        val termMonday = snapshot.settings.startDate?.let(TimetableRules::mondayOf)
            ?: return NoticeProposalResult.Blocked(listOf(NoticeProblem(NoticeCodes.NEED_TERM_MONDAY)))
        if (intent.basis.termMonday != null && intent.basis.termMonday != termMonday) {
            return NoticeProposalResult.Blocked(listOf(NoticeProblem(NoticeCodes.CALENDAR_MISMATCH, "term basis differs")))
        }
        if (intent.basis.totalWeeks != null && intent.basis.totalWeeks != snapshot.settings.totalWeeks) {
            return NoticeProposalResult.Blocked(listOf(NoticeProblem(NoticeCodes.CALENDAR_MISMATCH, "week count differs")))
        }
        val calendarFingerprint = calendarFingerprint(snapshot)

        var candidate: NoticeCandidate? = null
        var extraCourseIdentity: Pair<Long, String>? = null
        var extraCourseValue: ScheduledCourse? = null
        if (intent.kind != NoticeIntentKind.EXTRA) {
            when (val resolved = NoticeOccurrenceResolver.resolve(intent, snapshot, bases, target)) {
                is NoticeCandidateResult.None -> return NoticeProposalResult.Blocked(resolved.problems)
                is NoticeCandidateResult.NotRequired -> return NoticeProposalResult.Blocked(
                    listOf(NoticeProblem(NoticeCodes.UNSUPPORTED_NOTICE, resolved.reason))
                )
                is NoticeCandidateResult.Found -> {
                    if (selection == null) {
                        return NoticeProposalResult.Blocked(
                            listOf(NoticeProblem(NoticeCodes.NEED_MANUAL_SELECTION)), resolved.candidates
                        )
                    }
                    candidate = resolved.candidates.singleOrNull { it.id == selection }
                        ?: return NoticeProposalResult.Blocked(
                            listOf(NoticeProblem(NoticeCodes.STALE_CANDIDATE)), resolved.candidates
                        )
                }
            }
        } else {
            // A make-up has no before occurrence, but it still belongs to one real course.
            val selected = extraCourse
                ?: return NoticeProposalResult.Blocked(listOf(NoticeProblem(NoticeCodes.NEED_COURSE_SELECTION)))
            val course = snapshot.courses.singleOrNull { it.id == selected.courseId }
                ?: return NoticeProposalResult.Blocked(listOf(NoticeProblem(NoticeCodes.STALE_CANDIDATE)))
            extraCourseIdentity = course.id to course.courseName
            extraCourseValue = course
        }
        val chosen = candidate
        if (chosen?.alreadyAdjusted == true && !replaceExistingAdjustment) {
            return NoticeProposalResult.Blocked(
                listOf(NoticeProblem(NoticeCodes.ALREADY_ADJUSTED)), listOfNotNull(chosen)
            )
        }
        val originalDate = chosen?.originalDate ?: intent.originalDate
        val targetDate = when (intent.kind) {
            NoticeIntentKind.EXTRA, NoticeIntentKind.CHANGE_DATE -> intent.targetDate
            NoticeIntentKind.CANCEL, NoticeIntentKind.ROOM_ONLY -> originalDate
            NoticeIntentKind.RESCHEDULE_TIME -> chosen?.originalDate ?: intent.targetDate ?: originalDate
        }
        if (intent.kind != NoticeIntentKind.EXTRA && originalDate == null) {
            return NoticeProposalResult.Blocked(listOf(NoticeProblem(NoticeCodes.NEED_ORIGINAL_DATE)), listOfNotNull(chosen))
        }
        if (targetDate == null) {
            return NoticeProposalResult.Blocked(listOf(NoticeProblem(NoticeCodes.NEED_TARGET_DATE)), listOfNotNull(chosen))
        }
        if (TimetableRules.teachingWeek(snapshot.settings, targetDate) == null) {
            return NoticeProposalResult.Blocked(listOf(NoticeProblem(NoticeCodes.DATE_OUTSIDE_TERM)), listOfNotNull(chosen))
        }

        val keptFields = linkedSetOf<ImportField>()
        val before = chosen?.let { occurrence ->
            NoticeOccurrenceView(occurrence.originalDate, occurrence.currentSections, occurrence.currentRanges,
                occurrence.currentTeacher, occurrence.currentRoom, occurrence.currentProject, occurrence.currentGroup)
        }
        val after: NoticeOccurrenceView?
        if (intent.kind == NoticeIntentKind.CANCEL) {
            after = null
        } else {
            // No explicit target scope means "keep the occurrence exactly as it is now": this
            // includes an effective customTime that differs from the default period envelope.
            val explicitTime = intent.target.time
            val explicitSections = intent.target.sections
            val sections: Set<Int>
            val intervals: List<TimeRange>
            when {
                explicitTime != null -> {
                    sections = explicitSections.ifEmpty { chosen?.currentSections.orEmpty() }
                    intervals = listOf(TimeRange(explicitTime.start, explicitTime.end))
                }
                explicitSections.isNotEmpty() -> {
                    sections = explicitSections
                    intervals = periodRanges(snapshot.periods, sections)
                    if (intervals.size != sections.size) {
                        return NoticeProposalResult.Blocked(
                            listOf(NoticeProblem(NoticeCodes.TARGET_PERIOD_MISSING, "缺少节次对应的时间")), listOfNotNull(chosen)
                        )
                    }
                }
                chosen != null -> {
                    sections = chosen.currentSections
                    intervals = chosen.currentRanges
                }
                else -> return NoticeProposalResult.Blocked(listOf(NoticeProblem(NoticeCodes.NEED_TIME)))
            }
            if (intervals.isEmpty()) {
                return NoticeProposalResult.Blocked(
                    listOf(NoticeProblem(NoticeCodes.TARGET_PERIOD_MISSING, "缺少节次对应的时间")), listOfNotNull(chosen)
                )
            }
            val teacher = chosen?.currentTeacher ?: intent.teacher ?: extraCourseValue?.teacher.orEmpty()
            val room = when (intent.roomChange?.role) {
                NoticeRoomRole.KEEP -> chosen?.currentRoom ?: intent.room.orEmpty()
                NoticeRoomRole.CHANGE -> intent.roomChange.to ?: chosen?.currentRoom.orEmpty()
                null -> chosen?.currentRoom ?: intent.room ?: extraCourseValue?.room.orEmpty()
            }
            val project = chosen?.currentProject ?: intent.project
            val group = chosen?.currentGroup ?: intent.group
            after = NoticeOccurrenceView(targetDate, sections, intervals, teacher, room, project, group)
            if (chosen != null) {
                if (teacher == chosen.currentTeacher) keptFields += ImportField.TEACHER
                if (room == chosen.currentRoom) keptFields += ImportField.ROOM
                if (project == chosen.currentProject) keptFields += ImportField.EXPERIMENT_PROJECT
                if (group == chosen.currentGroup) keptFields += ImportField.GROUP
            }
        }
        if (intent.kind != NoticeIntentKind.CANCEL && before != null && after != null &&
            before.date == after.date && before.sections == after.sections && before.ranges == after.ranges &&
            before.teacher == after.teacher && before.room == after.room
        ) {
            return NoticeProposalResult.Blocked(listOf(NoticeProblem(NoticeCodes.NO_EFFECTIVE_CHANGE)), listOfNotNull(chosen))
        }

        val courseId = chosen?.id?.courseId ?: extraCourseIdentity?.first
        val courseTitle = chosen?.title ?: extraCourseIdentity?.second
        val problems = mutableListOf<NoticeProblem>()
        val conflicts = if (after != null) conflicts(snapshot, chosen, after) else emptyList()
        val unacknowledged = conflicts.filter { it.fingerprint !in acknowledgedConflicts }
        if (unacknowledged.isNotEmpty()) problems += NoticeProblem(NoticeCodes.CONFLICT_PENDING, "${unacknowledged.size} conflict(s)")

        val semanticKey = ImportCanonical.digest(listOf(
            "notice-adjustment", target.scopeToken, target.semesterId,
            courseId, chosen?.id?.logicalBaseId, chosen?.id?.structureFingerprint,
            originalDate, targetDate, intent.kind,
            before?.let(::viewSignature), after?.let(::viewSignature),
            chosen?.existingExceptionId,
        ))
        val confirmDigest = ImportCanonical.digest(listOf(
            "notice-confirm", semanticKey, target.expectedRevision, calendarFingerprint,
            intent.basis, intent.rawText, courseId, chosen?.id, intent.original, intent.target,
            intent.roomChange, acknowledgedConflicts.sorted(), replaceExistingAdjustment,
        ))
        val proposal = NoticeAdjustmentProposal(
            semanticKey = semanticKey,
            confirmDigest = confirmDigest,
            target = target,
            calendarFingerprint = calendarFingerprint,
            basis = intent.basis,
            rawTextDigest = ImportCanonical.digest(intent.rawText),
            kind = intent.kind,
            candidate = chosen?.id,
            courseId = courseId,
            courseTitle = courseTitle,
            originalDate = originalDate,
            targetDate = targetDate,
            meetingId = chosen?.meetingId,
            before = before,
            after = after,
            keptFields = keptFields,
            impact = NoticeImpact(NoticeImpactScope.ONE_OCCURRENCE, listOfNotNull(originalDate, targetDate).distinct()),
            replacesExistingAdjustment = chosen?.alreadyAdjusted == true,
            replacedExceptionId = chosen?.existingExceptionId,
            conflicts = conflicts,
        )
        return if (problems.isEmpty()) NoticeProposalResult.Ready(proposal)
        else NoticeProposalResult.NotReady(proposal, problems)
    }

    /**
     * Half-open overlap against the calendar's actual occurrences on the target date. Only the
     * exact occurrence this plan replaces is excluded: a same-meeting EXTRA on that date is a
     * different occurrence and must still be reported as a conflict.
     */
    private fun conflicts(
        snapshot: TimetableSnapshot,
        chosen: NoticeCandidate?,
        after: NoticeOccurrenceView,
    ): List<NoticeConflict> {
        val occurrences = TimetableRules.occurrencesOn(snapshot, after.date)
        return occurrences.mapNotNull { other ->
            if (chosen != null && replacesPlanTarget(chosen, other)) return@mapNotNull null
            val overlap = after.ranges.flatMap { left ->
                other.timeRanges.mapNotNull { right ->
                    val start = maxOf(left.start, right.start)
                    val end = minOf(left.end, right.end)
                    if (start < end) TimeRange(start, end) else null
                }
            }
            if (overlap.isEmpty()) return@mapNotNull null
            val fingerprint = ImportCanonical.digest(listOf(
                "notice-conflict", after.date, other.course.id, other.meeting.id, other.exception?.id,
                other.timeRanges, after.ranges
            ))
            NoticeConflict(fingerprint, after.date, other.course.id, other.meeting.id, other.course.courseName,
                other.timeRanges, overlap.distinct())
        }.distinctBy { it.fingerprint }
    }

    private fun replacesPlanTarget(chosen: NoticeCandidate, other: com.lingion.sleepy.domain.model.CourseOccurrence): Boolean =
        other.course.id == chosen.id.courseId && other.meeting.id == chosen.meetingId &&
            other.date == chosen.originalDate &&
            (other.exception == null || (chosen.existingExceptionId != null && other.exception?.id == chosen.existingExceptionId))

    private fun periodRanges(periods: List<Period>, sections: Set<Int>): List<TimeRange> =
        periods.filter { it.number in sections }.map { TimeRange(it.start, it.end) }
            .sortedWith(compareBy({ it.start }, { it.end }))

    private fun calendarFingerprint(snapshot: TimetableSnapshot): String = ImportCanonical.digest(listOf(
        "notice-calendar", snapshot.settings.startDate, snapshot.settings.totalWeeks,
        snapshot.periods.sortedBy { it.number }.map { listOf(it.number, it.start, it.end) },
    ))

    private fun viewSignature(view: NoticeOccurrenceView): List<Any?> = listOf(
        view.date, view.sections.sorted(), view.ranges, view.teacher, view.room, view.project, view.group
    )
}