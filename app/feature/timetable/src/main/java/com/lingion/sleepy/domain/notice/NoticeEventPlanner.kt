/* SPDX-License-Identifier: GPL-3.0-only
 * Pure helpers between the A proposal and the persisted event: request identity, the single row the
 * manual path writes, row fingerprints and the lineage-preserving state rebuild used by undo.
 */
package com.lingion.sleepy.domain.notice

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.AddEffect
import com.lingion.sleepy.domain.imports.DatedInterval
import com.lingion.sleepy.domain.imports.EffectGroup
import com.lingion.sleepy.domain.imports.ImportCanonical
import com.lingion.sleepy.domain.imports.ImportNormalizationCodec
import com.lingion.sleepy.domain.imports.LogicalOriginal
import com.lingion.sleepy.domain.imports.OccurrenceIdentity
import com.lingion.sleepy.domain.imports.OwnedAdd
import com.lingion.sleepy.domain.imports.PlannedNormalizationState
import com.lingion.sleepy.domain.imports.RemoveEffect
import com.lingion.sleepy.domain.imports.StoredNormalizationState
import com.lingion.sleepy.domain.imports.fList
import com.lingion.sleepy.domain.imports.fSet
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.ExceptionKind
import com.lingion.sleepy.domain.model.MeetingException
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.importing.NoticeIntentKind

internal object NoticeEventPlanner {
    private val absent = "ABSENT"

    /**
     * Content identity of the notice request: course/occurrence identity, kind, dates and the
     * declared old/new roles. It deliberately excludes the current before/after, revision, UUIDs and
     * the raw wording, so a repeated notice (or the same semantics in new words) collides.
     */
    fun requestKey(
        scopeToken: String,
        semesterId: Long,
        intent: NoticeIntent,
        proposal: NoticeAdjustmentProposal,
        extraCourseId: Long?,
    ): String = requestKey(scopeToken, semesterId, intent, proposal.courseId, proposal.candidate, extraCourseId)

    /**
     * Preview needs the same identity before a proposal exists (the current occurrence may already
     * be adjusted, making the proposal itself stale). Selection carries the stable identity.
     */
    fun requestKey(
        scopeToken: String,
        semesterId: Long,
        intent: NoticeIntent,
        courseId: Long?,
        candidate: NoticeCandidateId?,
        extraCourseId: Long?,
    ): String {
        val targetDate = intent.targetDate ?: intent.originalDate
        return ImportCanonical.digest(listOf(
            "notice-request", scopeToken, semesterId,
            courseId, extraCourseId,
            candidate?.logicalBaseId, candidate?.structureFingerprint,
            intent.kind, intent.originalDate, targetDate,
            intent.original.sections.sorted(), intent.original.time,
            intent.target.sections.sorted(), intent.target.time,
            intent.roomChange?.role, intent.roomChange?.from, intent.roomChange?.to,
            // Business fields that change the actual arrangement (a make-up may differ only here).
            intent.teacher?.trim(), intent.room?.trim(), intent.project?.trim(), intent.group?.trim(),
        ))
    }

    fun afterFingerprint(after: NoticeOccurrenceView?): String =
        if (after == null) absent else ImportCanonical.digest(listOf(
            "notice-after", after.date, after.sections.sorted(), after.ranges, after.teacher, after.room, after.project, after.group,
        ))

    fun exceptionFingerprint(exception: MeetingException): String = ImportCanonical.digest(exception.copy(id = 0))

    fun occurrenceViewFingerprint(view: NoticeOccurrenceView): String = ImportCanonical.digest(listOf(
        "notice-view", view.date, view.sections.sorted(), view.ranges, view.teacher, view.room, view.project, view.group,
    ))

    sealed interface ManualRow {
        data class Ready(val exception: MeetingException) : ManualRow
        data class Unsupported(val code: String) : ManualRow
    }

    /**
     * One physical row expresses the whole adjustment for a course without normalization lineage.
     * Omitted fields inherit the **original meeting**, so the comparison base is the stored base
     * arrangement, not the exception that is about to be replaced.
     */
    fun manualRow(
        proposal: NoticeAdjustmentProposal,
        periods: List<Period>,
        baseSections: Set<Int>,
        baseRanges: List<TimeRange>,
        baseTeacher: String,
        baseRoom: String,
        baseProject: String?,
        baseGroup: String?,
    ): ManualRow {
        val after = proposal.after
        return when (proposal.kind) {
            NoticeIntentKind.CANCEL -> ManualRow.Ready(
                MeetingException(kind = ExceptionKind.CANCEL, originalDate = proposal.originalDate)
            )
            NoticeIntentKind.EXTRA -> {
                val scope = scopeOf(after ?: return ManualRow.Unsupported("TARGET_SCOPE_MISSING"), periods)
                    ?: return ManualRow.Unsupported("TARGET_SCOPE_NOT_REPRESENTABLE")
                ManualRow.Ready(MeetingException(
                    kind = ExceptionKind.EXTRA, targetDate = proposal.targetDate,
                    customTime = scope.time, sections = scope.sections,
                    teacher = after.teacher, room = after.room, experimentProject = after.project, group = after.group,
                ))
            }
            else -> {
                val afterView = after ?: return ManualRow.Unsupported("TARGET_SCOPE_MISSING")
                val scope = scopeOf(afterView, periods) ?: return ManualRow.Unsupported("TARGET_SCOPE_NOT_REPRESENTABLE")
                val sameScope = afterView.sections == baseSections &&
                    afterView.ranges.sortedWith(compareBy({ it.start }, { it.end })) ==
                    baseRanges.sortedWith(compareBy({ it.start }, { it.end }))
                ManualRow.Ready(MeetingException(
                    kind = ExceptionKind.RESCHEDULE, originalDate = proposal.originalDate, targetDate = proposal.targetDate,
                    customTime = if (sameScope) null else scope.time,
                    sections = if (sameScope) null else scope.sections,
                    teacher = afterView.teacher.takeIf { it != baseTeacher },
                    room = afterView.room.takeIf { it != baseRoom },
                    experimentProject = afterView.project.takeIf { it != baseProject },
                    group = afterView.group.takeIf { it != baseGroup },
                ))
            }
        }
    }

    private data class Scope(val sections: Set<Int>?, val time: TimeRange?)

    private fun scopeOf(view: NoticeOccurrenceView, periods: List<Period>): Scope? {
        if (view.sections.isNotEmpty()) {
            val expected = periods.filter { it.number in view.sections }.map { TimeRange(it.start, it.end) }
                .sortedWith(compareBy({ it.start }, { it.end }))
            val actual = view.ranges.sortedWith(compareBy({ it.start }, { it.end }))
            if (expected.size == view.sections.size && expected == actual) return Scope(view.sections, null)
            // A continuous new clock keeps the inherited section labels as metadata; the row must
            // carry the clock, not the default period envelope of those labels.
            if (view.ranges.size == 1) return Scope(null, view.ranges.single())
            return null
        }
        return when (view.ranges.size) {
            1 -> Scope(null, view.ranges.single())
            0 -> null
            else -> null
        }
    }

    /**
     * Rebuild a legal normalization state from the current rows, keeping the previous stable
     * original/fragment/source lineage. Used by undo after deleting only this event's rows; it never
     * restores an old snapshot. Validation is mandatory and a failure aborts the transaction.
     */
    fun rebuildNormalizedState(
        previous: StoredNormalizationState,
        course: ScheduledCourse,
        snapshot: TimetableSnapshot,
    ): StoredNormalizationState {
        require(previous.courseId == course.id) { "STATE_COURSE_MISMATCH" }
        require(previous.fragments.all { f -> course.meetings.any { it.id == f.meetingId } }) { "STATE_FRAGMENT_MISSING" }
        val removes = mutableListOf<RemoveEffect>()
        val adds = mutableListOf<AddEffect>()
        val groups = mutableListOf<EffectGroup>()
        val owned = mutableListOf<OwnedAdd>()
        // Fragment shapes include their attached exceptions, and undo removes only exceptions; the
        // stored shape must therefore be refreshed from the current rows alongside the effects.
        val fragments = previous.fragments.map { fragment ->
            val meeting = course.meetings.single { it.id == fragment.meetingId }
            fragment.copy(shapeFingerprint = ImportNormalizationCodec.meetingFingerprint(meeting))
        }
        for (fragment in fragments) {
            val meeting = course.meetings.single { it.id == fragment.meetingId }
            val intervals = TimetableRules.periodIntervals(meeting, snapshot.periods)
            for (exception in meeting.exceptions.sortedBy { it.id }) {
                val date = exception.originalDate ?: exception.targetDate ?: continue
                val removeKeys = linkedSetOf<String>()
                if (exception.kind != ExceptionKind.EXTRA) {
                    for (range in intervals) {
                        val key = ImportCanonical.digest(listOf("notice-rebuild-remove", fragment.familyId, date, range))
                        removes += RemoveEffect(key, fragment.familyId, DatedInterval(date, range), exception.provenance)
                        removeKeys += key
                    }
                }
                val addKeys = linkedSetOf<String>()
                if (exception.kind != ExceptionKind.CANCEL) {
                    val ranges = exception.customTime?.let { listOf(it) }
                        ?: exception.sections?.takeIf { it.isNotEmpty() }?.let { sections ->
                            snapshot.periods.filter { it.number in sections }.map { TimeRange(it.start, it.end) }
                        }
                        ?: intervals
                    val identity = OccurrenceIdentity(
                        course.courseName.trim(), course.sourceCourseCode, course.sourceClassCode,
                        exception.teacher?.trim() ?: meeting.teacher?.trim() ?: course.teacher.trim(),
                        exception.room?.trim() ?: meeting.room?.trim() ?: course.room.trim(),
                        exception.experimentProject ?: meeting.experimentProject, exception.group ?: meeting.group,
                        meeting.isExperiment,
                    )
                    for (range in ranges.sortedWith(compareBy({ it.start }, { it.end }))) {
                        val key = ImportCanonical.digest(listOf("notice-rebuild-add", fragment.familyId, date, range, identity))
                        adds += AddEffect(key, fragment.familyId, DatedInterval(date, range), identity, exception.provenance, fragment.key, key)
                        addKeys += key
                    }
                }
                groups += EffectGroup(ImportCanonical.digest(listOf("notice-rebuild-group", fragment.familyId, exception.id)),
                    fSet(removeKeys), fSet(addKeys))
                for (key in addKeys) owned += OwnedAdd(key, fragment.key, exception.id, exceptionFingerprint(exception))
            }
        }
        val state = StoredNormalizationState(
            previous.version, previous.scopeToken, previous.semesterId, course.id,
            PlannedNormalizationState(fList(previous.graph.originals), fList(removes), fList(adds), fList(groups), fList(previous.graph.sources)),
            fList(fragments), fList(owned), fList(previous.sourceReferences),
        )
        ImportNormalizationCodec.validate(state, snapshot.scopeToken, snapshot.settings.id, course, snapshot)
        return state
    }

    /** The stable logical original for the event's candidate. */
    fun logicalBase(proposal: NoticeAdjustmentProposal): Pair<String, String>? =
        proposal.candidate?.let { it.logicalBaseId to it.structureFingerprint }
}