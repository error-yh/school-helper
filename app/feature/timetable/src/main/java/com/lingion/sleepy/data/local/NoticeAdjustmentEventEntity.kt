/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One applied single-occurrence adjustment. Physical meeting/exception columns are auxiliary:
 * rows are rewritten on import/apply, so undo relies on the stable request/logical identity and on
 * the recorded payload fingerprints, not on these ids. Deleting a course/meeting keeps the event
 * (SET NULL) so history is never cascaded away.
 */
@Entity(
    tableName = "notice_adjustment_events",
    foreignKeys = [
        ForeignKey(entity = ScheduledCourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = CourseMeetingEntity::class, parentColumns = ["id"], childColumns = ["meetingId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index(value = ["scopeToken", "semesterId", "activeKey"], unique = true),
        Index(value = ["scopeToken", "semesterId", "courseId"]),
        Index(value = ["semesterId", "appliedRevision"]),
    ],
)
data class NoticeAdjustmentEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Content identity of the request, independent of the current before state/revision. */
    val requestKey: String,
    val semanticKey: String,
    val confirmDigest: String,
    val scopeToken: String,
    val semesterId: Long,
    val kind: String,
    val courseId: Long?,
    val courseTitle: String?,
    val logicalBaseId: String?,
    val structureFingerprint: String?,
    val meetingId: Long?,
    val originalEpochDay: Long?,
    val targetEpochDay: Long?,
    val beforeJson: String?,
    val afterJson: String?,
    val afterFingerprint: String,
    /** Bounded JSON: write mode, created exception ids/fingerprints, replaced-row payload. */
    val payloadJson: String,
    /** Bounded user-facing source text; never written to logs. */
    val sourceText: String?,
    val sourceDigest: String,
    val dateBasisJson: String?,
    val appliedRevision: Long,
    val undoneRevision: Long? = null,
    /** APPLIED or UNDONE. */
    val state: String,
    /** requestKey while APPLIED; null when undone so a new application can create a new event. */
    val activeKey: String?,
)