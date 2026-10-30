/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot

/** Parser output is independent of persistence and never mutates a timetable. */
data class ParsedTimetableImport(
    val courses: List<CourseDraft> = emptyList(),
    val errors: List<String> = emptyList(),
)

enum class ImportMode { APPEND, REPLACE }

/**
 * A future application boundary must compare targetScopeToken, targetSemesterId and expectedRevision atomically.
 * Optional metadata belongs to the same all-or-nothing plan. This DTO performs no application.
 */
data class TimetableImportCommand(
    val courses: List<CourseDraft>,
    val mode: ImportMode,
    val expectedRevision: Long,
    val targetSemesterId: Long,
    val targetScopeToken: String = "local",
    val settings: SemesterSettings? = null,
    val periods: List<Period>? = null,
)

data class CanonicalOccurrenceKey(
    val scopeId: Long,
    val courseName: String,
    val teacher: String,
    val room: String,
    val day: Int,
    val weeks: Set<Int>,
    val sections: Set<Int>,
    val customTime: TimeRange?,
    val scopeToken: String = "local",
)

data class ImportConflict(val day: Int, val weeks: Set<Int>)

data class TimetableImportEntry(
    val course: CourseDraft,
    val duplicate: Boolean = false,
    val conflicts: List<ImportConflict> = emptyList(),
    val errors: List<String> = emptyList(),
    val effectiveCourse: CourseDraft = course,
    val duplicateMeetingCount: Int = 0,
)

data class TimetableImportPreview(
    val targetSemesterId: Long,
    val entries: List<TimetableImportEntry> = emptyList(),
    val errors: List<String> = emptyList(),
    val expectedRevision: Long = 0,
    val mode: ImportMode = ImportMode.APPEND,
    val targetScopeToken: String = "local",
) {
    val totalCount: Int get() = entries.size
    val duplicateCount: Int get() = entries.count { it.duplicate }
    val duplicateMeetingCount: Int get() = entries.sumOf { it.duplicateMeetingCount }
    val conflictCount: Int get() = entries.count { it.conflicts.isNotEmpty() }
    val errorCount: Int get() = errors.size + entries.sumOf { it.errors.size }
    /** Conflicts are reviewable warnings; malformed rows and parser errors block application. */
    val canApply: Boolean get() = errors.isEmpty() && entries.all { it.errors.isEmpty() } &&
        entries.any { !it.duplicate && it.effectiveCourse.meetings.isNotEmpty() }
}

object TimetableImportRules {
    fun canonicalKey(
        scopeId: Long,
        course: CourseDraft,
        meeting: CourseMeeting,
        scopeToken: String = "local",
    ): CanonicalOccurrenceKey = CanonicalOccurrenceKey(
        scopeId = scopeId,
        courseName = course.courseName,
        teacher = meeting.teacher ?: course.teacher,
        room = meeting.room ?: course.room,
        day = meeting.day,
        weeks = meeting.weeks.toSet(),
        sections = meeting.sections.toSet(),
        customTime = meeting.customTime,
        scopeToken = scopeToken,
    )

    fun conflicts(
        first: CourseMeeting,
        second: CourseMeeting,
        periods: List<Period>,
    ): Boolean {
        if (first.day != second.day || first.weeks.none { it in second.weeks }) return false
        val firstRanges = TimetableRules.periodIntervals(first, periods)
        val secondRanges = TimetableRules.periodIntervals(second, periods)
        return firstRanges.any { left ->
            secondRanges.any { right -> left.start < right.end && right.start < left.end }
        }
    }

    fun preview(
        snapshot: TimetableSnapshot,
        parsed: ParsedTimetableImport,
        mode: ImportMode = ImportMode.APPEND,
    ): TimetableImportPreview {
        val knownKeys = mutableSetOf<CanonicalOccurrenceKey>()
        val comparisonMeetings = mutableListOf<CourseMeeting>()
        if (mode == ImportMode.APPEND) {
            snapshot.courses.forEach { course ->
                val draft = course.asDraft()
                course.meetings.forEach { meeting ->
                    knownKeys += canonicalKey(snapshot.settings.id, draft, meeting, snapshot.scopeToken)
                    comparisonMeetings += meeting
                }
            }
        }
        val entries = parsed.courses.map { draft ->
            val effectiveMeetings = mutableListOf<CourseMeeting>()
            val foundConflicts = mutableListOf<ImportConflict>()
            var duplicates = 0
            draft.meetings.forEach { meeting ->
                val key = canonicalKey(snapshot.settings.id, draft, meeting, snapshot.scopeToken)
                if (!knownKeys.add(key)) {
                    duplicates++
                } else {
                    comparisonMeetings.filter { conflicts(meeting, it, snapshot.periods) }.forEach { other ->
                        foundConflicts += ImportConflict(meeting.day, meeting.weeks.intersect(other.weeks))
                    }
                    effectiveMeetings += meeting
                    comparisonMeetings += meeting
                }
            }
            TimetableImportEntry(
                course = draft,
                duplicate = draft.meetings.isNotEmpty() && effectiveMeetings.isEmpty(),
                conflicts = foundConflicts.distinct(),
                errors = TimetableRules.validateCourse(draft, snapshot.settings, snapshot.periods),
                effectiveCourse = draft.copy(meetings = effectiveMeetings.toList()),
                duplicateMeetingCount = duplicates,
            )
        }
        return TimetableImportPreview(
            targetSemesterId = snapshot.settings.id,
            entries = entries,
            errors = parsed.errors + TimetableRules.validateSettings(snapshot.settings) +
                TimetableRules.validatePeriods(snapshot.periods),
            expectedRevision = snapshot.revision,
            mode = mode,
            targetScopeToken = snapshot.scopeToken,
        )
    }
}
