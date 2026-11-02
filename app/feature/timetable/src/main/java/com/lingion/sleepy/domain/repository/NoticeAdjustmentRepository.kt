/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.repository

import com.lingion.sleepy.domain.notice.NoticeAdjustmentApplyResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentConfirmation
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreviewResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentRequest
import com.lingion.sleepy.domain.notice.NoticeAdjustmentUndoResult

/**
 * Execution layer for one-occurrence adjustments. Preview is read-only; apply and undo reload the
 * snapshot, re-run the proposal and write rows/state/journal/revision in one Room transaction.
 * A confirmation is not a signed permit: every field is revalidated against the current database.
 */
interface NoticeAdjustmentRepository {
    suspend fun preview(request: NoticeAdjustmentRequest): NoticeAdjustmentPreviewResult
    suspend fun apply(confirmation: NoticeAdjustmentConfirmation): NoticeAdjustmentApplyResult
    suspend fun history(limit: Int = 50, beforeEventId: Long? = null): NoticeAdjustmentHistoryResult
    suspend fun undo(eventId: Long, expectedRevision: Long): NoticeAdjustmentUndoResult
}