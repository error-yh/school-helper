/* SPDX-License-Identifier: GPL-3.0-only
 * Public contract between the execution layer (B) and the future confirmation UI (C). Nothing here
 * writes or grants permission: preview is read-only and apply revalidates everything inside Room.
 */
package com.lingion.sleepy.domain.notice

import com.lingion.sleepy.domain.imports.ImportTarget
import com.lingion.sleepy.importing.NoticeIntentKind

/** Caller input for a preview. The intent and selection are re-planned inside the transaction. */
data class NoticeAdjustmentRequest(
    val target: ImportTarget,
    val intent: NoticeIntent,
    val selection: NoticeCandidateId?,
    val extraCourse: NoticeCourseSelection? = null,
    val acknowledgedConflicts: Set<String> = emptySet(),
    val replaceExistingAdjustment: Boolean = false,
)

/** Opaque result of a preview. Apply recomputes the proposal and compares these digests. */
class NoticeAdjustmentConfirmation internal constructor(
    val target: ImportTarget,
    val requestKey: String,
    val semanticKey: String,
    val confirmDigest: String,
    val intent: NoticeIntent,
    val selection: NoticeCandidateId?,
    val extraCourse: NoticeCourseSelection?,
    val acknowledgedConflicts: Set<String>,
    val replaceExistingAdjustment: Boolean,
    val sourceText: String,
    val sourceDigest: String,
)

class NoticeAdjustmentPreview internal constructor(
    val target: ImportTarget,
    val proposal: NoticeAdjustmentProposal,
    val requestKey: String,
    val confirmation: NoticeAdjustmentConfirmation,
    /** Non-null when the same request is already applied; apply returns AlreadyApplied. */
    val alreadyAppliedEventId: Long?,
)

sealed interface NoticeAdjustmentPreviewResult {
    data class Ready(val preview: NoticeAdjustmentPreview) : NoticeAdjustmentPreviewResult
    /** The same request content is already applied; apply would report AlreadyApplied as well. */
    data class AlreadyApplied(val eventId: Long, val revision: Long) : NoticeAdjustmentPreviewResult
    data class ConflictPending(val proposal: NoticeAdjustmentProposal, val problems: List<NoticeProblem>) : NoticeAdjustmentPreviewResult
    data class Blocked(val problems: List<NoticeProblem>, val candidates: List<NoticeCandidate> = emptyList()) : NoticeAdjustmentPreviewResult
}

sealed interface NoticeAdjustmentApplyResult {
    data class Applied(val eventId: Long, val revision: Long, val mode: String) : NoticeAdjustmentApplyResult
    data class AlreadyApplied(val eventId: Long, val revision: Long) : NoticeAdjustmentApplyResult
    data class Rejected(val code: String, val detail: String? = null) : NoticeAdjustmentApplyResult
}

data class NoticeAdjustmentHistoryEntry(
    val eventId: Long,
    val kind: NoticeIntentKind,
    val courseId: Long?,
    val courseTitle: String?,
    val originalDate: java.time.LocalDate?,
    val targetDate: java.time.LocalDate?,
    val before: NoticeOccurrenceView?,
    val after: NoticeOccurrenceView?,
    val sourceText: String?,
    val state: String,
    val appliedRevision: Long,
    val undoneRevision: Long?,
    val canUndo: Boolean,
)

data class NoticeAdjustmentHistory(
    /** Newest first; [nextBeforeEventId] is null when this page reaches the oldest event. */
    val entries: List<NoticeAdjustmentHistoryEntry>,
    val nextBeforeEventId: Long?,
)

sealed interface NoticeAdjustmentHistoryResult {
    data class Loaded(val history: NoticeAdjustmentHistory) : NoticeAdjustmentHistoryResult
    data class Failed(val code: String, val eventId: Long? = null) : NoticeAdjustmentHistoryResult
}

sealed interface NoticeAdjustmentUndoResult {
    data class Undone(val eventId: Long, val revision: Long) : NoticeAdjustmentUndoResult
    data class AlreadyUndone(val eventId: Long) : NoticeAdjustmentUndoResult
    data class Rejected(val code: String, val detail: String? = null) : NoticeAdjustmentUndoResult
}

/** Structured codes C can map to text; no code implies success. */
object NoticeAdjustmentCodes {
    const val STALE_TARGET = "STALE_TARGET"
    const val STALE_CONFIRMATION = "STALE_CONFIRMATION_REQUIRES_PREVIEW"
    const val STALE_REVISION = "STALE_REVISION"
    const val CONFLICT_PENDING = "CONFLICT_PENDING"
    const val ALREADY_APPLIED = "ALREADY_APPLIED"
    /** The recorded request is active but its effective occurrence no longer holds (re-import etc.). */
    const val STALE_EVENT_EFFECT = "STALE_EVENT_EFFECT_REQUIRES_RECONCILE"
    const val NO_COURSE = "NO_COURSE_SELECTED"
    const val COURSE_MISSING = "COURSE_MISSING"
    const val MEETING_MISSING = "MEETING_MISSING"
    const val REPLACE_UNSUPPORTED_FOR_NORMALIZED = "REPLACE_UNSUPPORTED_FOR_NORMALIZED_COURSE"
    const val REPLACED_ROW_MISSING = "REPLACED_ROW_MISSING"
    const val PREVIEW_FAILED = "PREVIEW_FAILED"
    const val APPLY_FAILED = "APPLY_FAILED"
    const val WRITE_CONFLICT = "WRITE_CONFLICT"
    const val INVALID_EVENT_PAYLOAD = "INVALID_EVENT_PAYLOAD"
    const val EVENT_NOT_FOUND = "EVENT_NOT_FOUND"
    const val EVENT_NOT_APPLIED = "EVENT_NOT_APPLIED"
    const val UNDO_TARGET_MISSING = "UNDO_TARGET_MISSING"
    const val UNDO_AFTER_MISMATCH = "UNDO_AFTER_MISMATCH"
    const val UNDO_OWNERSHIP_CHANGED = "UNDO_OWNERSHIP_CHANGED"
    const val UNDO_STATE_MISSING = "UNDO_STATE_MISSING"
    const val UNDO_INCONSISTENT = "UNDO_INCONSISTENT"
    const val LATER_EVENT_DEPENDS = "LATER_EVENT_DEPENDS"
    const val UNDO_FAILED = "UNDO_FAILED"
    const val CORRUPT_EVENT = "CORRUPT_EVENT"
    const val INVALID_LIMIT = "INVALID_HISTORY_LIMIT"
}