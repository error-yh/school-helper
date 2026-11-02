/* SPDX-License-Identifier: GPL-3.0-only
 * Pure single-occurrence notice contract. Nothing here writes Room, opens the UI or owns a clock:
 * a caller must supply confirmed basis values, and every output is a read-only proposal.
 */
package com.lingion.sleepy.domain.notice

import com.lingion.sleepy.domain.imports.ImportTarget
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.importing.*
import java.time.LocalDate

enum class NoticeBasisField { REFERENCE_DATE, TERM_MONDAY, TOTAL_WEEKS, YEAR }

data class NoticeProblem(val code: String, val detail: String? = null) {
    init { require(code.isNotBlank()) }
}

/** Source evidence for one clause, kept so a later UI can show the exact original wording. */
data class NoticeClause(val sourceId: String?, val sourceLocator: String?, val rawText: String)

/** One user-confirmed date basis shared by the text's clauses. */
data class NoticeBasis(
    val dates: NoticeDateBasis,
    val bigSections: NoticeBigSectionMapping? = null,
)

/**
 * Role-complete notice intent. Old fields are match conditions; target fields are overrides.
 * [courseNameHint] is only a lookup hint and never authorizes a candidate.
 */
data class NoticeIntent(
    val kind: NoticeIntentKind,
    val rawText: String,
    val clauses: List<NoticeClause>,
    val basis: NoticeDateBasis,
    val courseNameHint: String?,
    val teacher: String?,
    val room: String?,
    val group: String?,
    val project: String?,
    val originalDate: LocalDate?,
    val targetDate: LocalDate?,
    val original: NoticeScope,
    val target: NoticeScope,
    val roomChange: NoticeRoomChange?,
    val bigSection: NoticeBigSection?,
    val declaredWeeks: Set<Int>,
    val declaredDayOfWeek: Int?,
    val dateRule: String?,
    val sameDayAdjustment: Boolean,
    /** Explicit but malformed declarations stay blocking until the named field is corrected. */
    val invalidOriginalTime: Boolean = false,
    val invalidOriginalSections: Boolean = false,
    val invalidTargetTime: Boolean = false,
    val invalidTargetSections: Boolean = false,
    val warnings: List<NoticeProblem> = emptyList(),
) {
    val requiresCorrection: Boolean
        get() = invalidOriginalTime || invalidOriginalSections || invalidTargetTime || invalidTargetSections

    /** Explicit user corrections replace the stated role values before re-resolution. */
    fun withCorrections(corrections: NoticeCorrections): NoticeIntent = copy(
        original = NoticeScope(corrections.originalSections ?: original.sections, corrections.originalTime ?: original.time),
        target = NoticeScope(corrections.targetSections ?: target.sections, corrections.targetTime ?: target.time),
        targetDate = corrections.targetDate ?: targetDate,
        roomChange = when {
            corrections.room != null -> NoticeRoomChange(NoticeRoomRole.CHANGE, roomChange?.from ?: room, corrections.room)
            corrections.roomKeep -> NoticeRoomChange(NoticeRoomRole.KEEP)
            else -> roomChange
        },
        // Only the corrected field clears its own blocker; unrelated problems stay.
        invalidOriginalTime = invalidOriginalTime && corrections.originalTime == null,
        invalidOriginalSections = invalidOriginalSections && corrections.originalSections == null,
        invalidTargetTime = invalidTargetTime && corrections.targetTime == null,
        invalidTargetSections = invalidTargetSections && corrections.targetSections == null,
    )
}

/** Null means "not corrected"; an empty set explicitly clears a wrong role value. */
data class NoticeCorrections(
    val originalSections: Set<Int>? = null,
    val originalTime: DraftTimeRange? = null,
    val targetSections: Set<Int>? = null,
    val targetTime: DraftTimeRange? = null,
    val targetDate: LocalDate? = null,
    val room: String? = null,
    val roomKeep: Boolean = false,
)

sealed interface NoticeIntentResult {
    data class Ready(val intent: NoticeIntent) : NoticeIntentResult
    data class NeedBasis(val missing: Set<NoticeBasisField>) : NoticeIntentResult
    /** [code] is NEED_TIME for a bare 上午/下午 and NEED_PERIOD_MAPPING for 第N大节. */
    data class NeedTime(val bigSection: NoticeBigSection?, val code: String) : NoticeIntentResult
    /** Malformed but lexically explicit roles: correct the named field, then propose again. */
    data class CorrectionRequired(val intent: NoticeIntent, val problems: List<NoticeProblem>) : NoticeIntentResult
    data class Blocked(val problems: List<NoticeProblem>) : NoticeIntentResult
}

/** An explicit existing-course selection for a make-up (EXTRA) occurrence. */
data class NoticeCourseSelection(val courseId: Long) {
    init { require(courseId > 0) { "补课必须绑定已有课程" } }
}

/** Stable logical identity of a stored occurrence; display groups and fragment indexes are not ids. */
data class NoticeCandidateId(
    val courseId: Long,
    val logicalBaseId: String,
    val structureFingerprint: String,
) {
    init {
        require(courseId > 0 && logicalBaseId.isNotBlank() && structureFingerprint.isNotBlank())
    }
}

data class NoticeCandidate(
    val id: NoticeCandidateId,
    val title: String,
    val meetingId: Long,
    val originalDate: LocalDate,
    val currentDate: LocalDate,
    val baseSections: Set<Int>,
    val baseTime: TimeRange?,
    val currentSections: Set<Int>,
    val currentRanges: List<TimeRange>,
    val currentTeacher: String,
    val currentRoom: String,
    val currentProject: String?,
    val currentGroup: String?,
    val alreadyAdjusted: Boolean,
    val existingExceptionId: Long?,
    val existingExceptionKind: String?,
    val reasons: List<String>,
    val warnings: List<NoticeProblem> = emptyList(),
) {
    init { require(baseSections.all { it > 0 } && currentSections.all { it > 0 }) }
}

sealed interface NoticeCandidateResult {
    /** EXTRA has no original occurrence to choose. */
    data class NotRequired(val reason: String) : NoticeCandidateResult
    data class Found(val candidates: List<NoticeCandidate>, val problems: List<NoticeProblem> = emptyList()) : NoticeCandidateResult
    data class None(val problems: List<NoticeProblem>) : NoticeCandidateResult
}

/** One actual occurrence as the calendar sees it now. Before and after never mix dates silently. */
data class NoticeOccurrenceView(
    val date: LocalDate,
    val sections: Set<Int>,
    val ranges: List<TimeRange>,
    val teacher: String,
    val room: String,
    val project: String?,
    val group: String?,
)

enum class NoticeImpactScope { ONE_OCCURRENCE }

data class NoticeImpact(val scope: NoticeImpactScope, val affectedDates: List<LocalDate>) {
    init { require(affectedDates.isNotEmpty() && affectedDates.size <= 2) { "单次调整只能影响原发生/补课发生所在的日期" } }
}

data class NoticeConflict(
    val fingerprint: String,
    val date: LocalDate,
    val otherCourseId: Long,
    val otherMeetingId: Long,
    val otherTitle: String,
    val otherRanges: List<TimeRange>,
    val overlap: List<TimeRange>,
) {
    init { require(fingerprint.isNotBlank() && overlap.isNotEmpty()) }
}

class NoticeAdjustmentProposal internal constructor(
    /** Stable across recomputation/revision; binds scope, identity, date and before/after. */
    val semanticKey: String,
    /** Binds the exact input, identity, basis, corrections, revision and decisions. */
    val confirmDigest: String,
    val target: ImportTarget,
    val calendarFingerprint: String,
    val basis: NoticeDateBasis,
    val rawTextDigest: String,
    val kind: NoticeIntentKind,
    val candidate: NoticeCandidateId?,
    /** Real stored course the occurrence (or the make-up) belongs to; never a display group. */
    val courseId: Long?,
    val courseTitle: String?,
    val originalDate: LocalDate?,
    val targetDate: LocalDate,
    /** Physical meeting that currently owns the occurrence; auxiliary for the manual write path. */
    val meetingId: Long?,
    val before: NoticeOccurrenceView?,
    /** Null means the occurrence is cancelled; an EXTRA starts from no before view. */
    val after: NoticeOccurrenceView?,
    val keptFields: Set<ImportField>,
    val impact: NoticeImpact,
    val replacesExistingAdjustment: Boolean,
    val replacedExceptionId: Long?,
    val conflicts: List<NoticeConflict>,
)

sealed interface NoticeProposalResult {
    data class Ready(val proposal: NoticeAdjustmentProposal) : NoticeProposalResult
    /** The proposal is displayable but a conflict/decision still blocks execution. */
    data class NotReady(val proposal: NoticeAdjustmentProposal, val problems: List<NoticeProblem>) : NoticeProposalResult
    data class Blocked(val problems: List<NoticeProblem>, val candidates: List<NoticeCandidate> = emptyList()) : NoticeProposalResult
}

/** The only execution-adjacent shape A delivers: a frozen plan, no apply port and no token. */
data class NoticeAdjustmentPlan(val proposal: NoticeAdjustmentProposal)

internal object NoticeCodes {
    const val NEED_REFERENCE_DATE = "NEED_REFERENCE_DATE"
    const val NEED_TERM_MONDAY = "NEED_TERM_MONDAY"
    const val NEED_TOTAL_WEEKS = "NEED_TOTAL_WEEKS"
    const val NEED_YEAR = "NEED_YEAR"
    const val NEED_TIME = "NEED_TIME"
    const val NEED_PERIOD_MAPPING = "NEED_PERIOD_MAPPING"
    const val NEED_ORIGINAL_DATE = "NEED_ORIGINAL_DATE"
    const val NEED_TARGET_DATE = "NEED_TARGET_DATE"
    const val UNSUPPORTED_NOTICE = "UNSUPPORTED_NOTICE"
    const val AMBIGUOUS_NOTICE = "AMBIGUOUS_NOTICE"
    const val LIMIT_EXCEEDED = "LIMIT_EXCEEDED"
    const val DATE_OUTSIDE_TERM = "DATE_OUTSIDE_TERM"
    const val OCCURRENCE_NOT_FOUND = "OCCURRENCE_NOT_FOUND"
    const val OCCURRENCE_ALREADY_CANCELLED = "OCCURRENCE_ALREADY_CANCELLED"
    const val OLD_SCOPE_MISMATCH = "OLD_SCOPE_MISMATCH"
    const val NAME_HINT_MISMATCH = "NAME_HINT_MISMATCH"
    const val NEED_MANUAL_SELECTION = "NEED_MANUAL_SELECTION"
    const val NEED_COURSE_SELECTION = "NEED_COURSE_SELECTION"
    const val STALE_CANDIDATE = "STALE_CANDIDATE"
    const val CORRECTION_REQUIRED = "CORRECTION_REQUIRED"
    const val INVALID_ORIGINAL_TIME = "INVALID_ORIGINAL_TIME"
    const val INVALID_ORIGINAL_SECTIONS = "INVALID_ORIGINAL_SECTIONS"
    const val INVALID_TARGET_TIME = "INVALID_TARGET_TIME"
    const val INVALID_TARGET_SECTIONS = "INVALID_TARGET_SECTIONS"
    const val AMBIGUOUS_OCCURRENCE = "AMBIGUOUS_OCCURRENCE"
    const val ALREADY_ADJUSTED = "ALREADY_ADJUSTED_REQUIRES_RECONCILE"
    const val STALE_REVISION = "STALE_REVISION"
    const val CALENDAR_MISMATCH = "CALENDAR_MISMATCH"
    const val TARGET_PERIOD_MISSING = "TARGET_PERIOD_MISSING"
    const val NO_EFFECTIVE_CHANGE = "NO_EFFECTIVE_CHANGE"
    const val CONFLICT_PENDING = "CONFLICT_PENDING"
}