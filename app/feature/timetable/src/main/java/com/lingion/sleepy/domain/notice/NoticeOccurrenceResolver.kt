/* SPDX-License-Identifier: GPL-3.0-only
 * Candidate resolution by real course identity and the stored logical base, never by display
 * group id, OCR coordinates or a fragment index. Read-only: no occurrence is changed here.
 */
package com.lingion.sleepy.domain.notice

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.ImportLogicalBaseDisplay
import com.lingion.sleepy.domain.imports.ImportOccurrencePlanner
import com.lingion.sleepy.domain.imports.ImportTarget
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.CourseOccurrence
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import java.time.LocalDate

object NoticeOccurrenceResolver {
    private const val MAX_CANDIDATES = 50

    fun resolve(
        intent: NoticeIntent,
        snapshot: TimetableSnapshot,
        bases: List<ImportLogicalBaseDisplay>,
        target: ImportTarget,
    ): NoticeCandidateResult {
        if (intent.kind == com.lingion.sleepy.importing.NoticeIntentKind.EXTRA) {
            return NoticeCandidateResult.NotRequired("EXTRA_HAS_NO_ORIGINAL_OCCURRENCE")
        }
        val originalDate = intent.originalDate
            ?: return NoticeCandidateResult.None(listOf(NoticeProblem(NoticeCodes.NEED_ORIGINAL_DATE)))
        if (target.scopeToken != snapshot.scopeToken || target.semesterId != snapshot.settings.id) {
            return NoticeCandidateResult.None(listOf(NoticeProblem(NoticeCodes.CALENDAR_MISMATCH, "target/snapshot mismatch")))
        }
        val termMonday = snapshot.settings.startDate?.let(TimetableRules::mondayOf)
            ?: return NoticeCandidateResult.None(listOf(NoticeProblem(NoticeCodes.NEED_TERM_MONDAY)))
        if (TimetableRules.teachingWeek(snapshot.settings, originalDate) == null) {
            return NoticeCandidateResult.None(listOf(NoticeProblem(NoticeCodes.DATE_OUTSIDE_TERM)))
        }
        val occurrences = TimetableRules.occurrencesOn(snapshot, originalDate)
        val problems = mutableListOf<NoticeProblem>()
        val candidates = mutableListOf<NoticeCandidate>()
        for (base in bases) {
            val reference = base.reference
            if (reference.scopeToken != snapshot.scopeToken || reference.semesterId != snapshot.settings.id) continue
            val course = snapshot.courses.singleOrNull { it.id == reference.courseId } ?: continue
            val dates = try {
                ImportOccurrencePlanner.originalDates(base.original, termMonday, snapshot.settings.totalWeeks)
            } catch (_: IllegalArgumentException) {
                problems += NoticeProblem(NoticeCodes.OCCURRENCE_NOT_FOUND, "invalid logical base weeks")
                continue
            }
            if (originalDate !in dates) continue
            val matching = occurrences.filter { occurrence ->
                occurrence.course.id == reference.courseId && sameShape(occurrence.meeting, base.original) &&
                    // Ownership is proven by the meeting/base fields; an exception's effective
                    // override changes this occurrence, not which logical base it belongs to.
                    identityMatches(base, occurrence.meeting.teacher ?: course.teacher,
                        occurrence.meeting.room ?: course.room,
                        occurrence.meeting.experimentProject, occurrence.meeting.group)
            }
            val baseOccurrences = matching.filter { it.exception == null }
            val adjustedOccurrences = matching.filter {
                it.exception != null && it.exception.kind != com.lingion.sleepy.domain.model.ExceptionKind.EXTRA
            }
            // Shape alone only narrows; the actual occurrence must be proven unique by identity.
            val occurrence = when {
                baseOccurrences.size + adjustedOccurrences.size == 1 -> (baseOccurrences + adjustedOccurrences).single()
                baseOccurrences.isEmpty() && adjustedOccurrences.isEmpty() -> null
                else -> {
                    problems += NoticeProblem(NoticeCodes.AMBIGUOUS_OCCURRENCE, "same shape and identity on the date")
                    continue
                }
            }
            if (occurrence == null) {
                val cancelled = course.meetings.any { meeting ->
                    sameShape(meeting, base.original) &&
                        identityMatches(base, meeting.teacher ?: course.teacher, meeting.room ?: course.room,
                            meeting.experimentProject, meeting.group) &&
                        meeting.exceptions.any { it.kind == com.lingion.sleepy.domain.model.ExceptionKind.CANCEL && it.originalDate == originalDate }
                }
                problems += NoticeProblem(if (cancelled) NoticeCodes.OCCURRENCE_ALREADY_CANCELLED else NoticeCodes.OCCURRENCE_NOT_FOUND)
                continue
            }
            val currentSections = occurrence.effectiveSections
            val currentTime = occurrence.effectiveCustomTime
            val baseMatches = scopeMatches(intent.original, base.original.sections, base.original.customTime, base.timeRanges)
            val currentMatches = scopeMatches(intent.original, currentSections, currentTime, occurrence.timeRanges)
            if (intent.original.specified && !baseMatches && !currentMatches) {
                problems += NoticeProblem(NoticeCodes.OLD_SCOPE_MISMATCH)
                continue
            }
            val reasons = mutableListOf<String>()
            when {
                !intent.original.specified -> reasons += "DATE_ONLY"
                baseMatches -> reasons += "ORIGINAL_SCOPE"
                else -> reasons += "CURRENT_SCOPE"
            }
            reasons += "IDENTITY_MATCH"
            if (base.original.kind == com.lingion.sleepy.domain.model.MeetingKind.DATE_SPECIFIC) reasons += "DATE_SPECIFIC"
            if (base.original.kind == com.lingion.sleepy.domain.model.MeetingKind.RECURRING) reasons += "RECURRING"
            val warnings = mutableListOf<NoticeProblem>()
            val title = base.title
            val hint = intent.courseNameHint?.let(::normalizeName)
            if (!hint.isNullOrBlank()) {
                val normalizedTitle = normalizeName(title)
                if (!normalizedTitle.contains(hint) && !hint.contains(normalizedTitle)) {
                    warnings += NoticeProblem(NoticeCodes.NAME_HINT_MISMATCH)
                } else reasons += "NAME_HINT"
            }
            if (!intent.teacher.isNullOrBlank() && intent.teacher == occurrence.effectiveTeacher) reasons += "TEACHER_HINT"
            if (!intent.room.isNullOrBlank() && intent.room == occurrence.effectiveRoom) reasons += "ROOM_HINT"
            val adjusted = occurrence.exception != null
            candidates += NoticeCandidate(
                id = NoticeCandidateId(reference.courseId, reference.logicalBaseId, reference.structureFingerprint),
                title = title,
                meetingId = occurrence.meeting.id,
                originalDate = originalDate,
                currentDate = occurrence.date,
                baseSections = base.original.sections,
                baseTime = base.original.customTime,
                currentSections = currentSections,
                currentRanges = occurrence.timeRanges,
                currentTeacher = occurrence.effectiveTeacher,
                currentRoom = occurrence.effectiveRoom,
                currentProject = occurrence.effectiveProject,
                currentGroup = occurrence.effectiveGroup,
                alreadyAdjusted = adjusted,
                existingExceptionId = occurrence.exception?.id?.takeIf { it > 0 },
                existingExceptionKind = occurrence.exception?.kind?.name,
                reasons = reasons,
                warnings = warnings,
            )
        }
        if (candidates.isEmpty()) {
            val distinct = problems.distinctBy { it.code }.ifEmpty {
                listOf(NoticeProblem(NoticeCodes.OCCURRENCE_NOT_FOUND))
            }
            return NoticeCandidateResult.None(distinct)
        }
        return NoticeCandidateResult.Found(
            candidates.distinctBy { it.id }.sortedWith(
                compareByDescending<NoticeCandidate> { rank(it) }.thenBy { it.id.courseId }.thenBy { it.id.logicalBaseId }
            ).take(MAX_CANDIDATES),
            problems.distinctBy { it.code },
        )
    }

    private fun rank(candidate: NoticeCandidate): Int {
        var score = 0
        if ("ORIGINAL_SCOPE" in candidate.reasons) score += 100
        if ("NAME_HINT" in candidate.reasons) score += 20
        if ("TEACHER_HINT" in candidate.reasons) score += 10
        if ("ROOM_HINT" in candidate.reasons) score += 5
        if (candidate.alreadyAdjusted) score -= 30
        return score
    }

    /** Old sections/time are match conditions; a current effective scope may also match when
     * the occurrence was already adjusted, which the proposal then has to reconcile explicitly. */
    private fun scopeMatches(
        scope: com.lingion.sleepy.importing.NoticeScope,
        sections: Set<Int>,
        customTime: TimeRange?,
        ranges: List<TimeRange>,
    ): Boolean {
        if (scope.sections.isNotEmpty() && scope.sections != sections) return false
        val time = scope.time ?: return true
        val expected = TimeRange(time.start, time.end)
        if (customTime == expected) return true
        if (ranges.contains(expected)) return true
        if (sections.isEmpty()) return false
        val envelope = ranges.minOfOrNull { it.start }?.let { start ->
            ranges.maxOfOrNull { it.end }?.let { end -> TimeRange(start, end) }
        }
        return envelope == expected
    }

    private fun sameShape(meeting: CourseMeeting, base: CourseMeeting): Boolean =
        meeting.kind == base.kind && meeting.day == base.day && meeting.weeks == base.weeks &&
            meeting.dates == base.dates && meeting.sections == base.sections && meeting.customTime == base.customTime

    /** Shape is only a filter; the logical identity's teacher/room/project/group must also agree. */
    private fun identityMatches(
        base: ImportLogicalBaseDisplay,
        teacher: String?,
        room: String?,
        project: String?,
        group: String?,
    ): Boolean = sameText(base.teacher, teacher) && sameText(base.room, room) &&
        sameText(base.project, project) && sameText(base.group, group)

    private fun sameText(first: String?, second: String?): Boolean = (first ?: "").trim() == (second ?: "").trim()

    private fun normalizeName(value: String): String =
        value.lowercase().replace(Regex("[\\s·•()（）\\[\\]【】,，.。:：;；/\\\\-]"), "")
}