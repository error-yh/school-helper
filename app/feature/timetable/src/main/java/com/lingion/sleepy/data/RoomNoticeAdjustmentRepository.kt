/* SPDX-License-Identifier: GPL-3.0-only
 * Room execution layer for one-occurrence notice adjustments: protected preview, protected apply,
 * bounded history and guarded undo. All writes (rows, normalization state, journal, revision) are
 * committed in one transaction; every failure throws out of the transaction so nothing is written.
 */
package com.lingion.sleepy.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.lingion.sleepy.data.local.CourseMeetingEntity
import com.lingion.sleepy.data.local.ExceptionSectionEntity
import com.lingion.sleepy.data.local.ImportNormalizationStateEntity
import com.lingion.sleepy.data.local.MeetingExceptionEntity
import com.lingion.sleepy.data.local.NoticeAdjustmentEventEntity
import com.lingion.sleepy.data.local.ScheduleProvenanceEntity
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.AddEffect
import com.lingion.sleepy.domain.imports.ConflictReviewChoice
import com.lingion.sleepy.domain.imports.ConflictReviewDecision
import com.lingion.sleepy.domain.imports.ExceptionAttachment
import com.lingion.sleepy.domain.imports.ImportCanonical
import com.lingion.sleepy.domain.imports.ImportMode
import com.lingion.sleepy.domain.imports.ImportNormalizationCodec
import com.lingion.sleepy.domain.imports.ImportOccurrencePlanner
import com.lingion.sleepy.domain.imports.ImportReviewContext
import com.lingion.sleepy.domain.imports.ImportReviewDecisions
import com.lingion.sleepy.domain.imports.ImportReviewResult
import com.lingion.sleepy.domain.imports.ImportReviewSession
import com.lingion.sleepy.domain.imports.ImportTarget
import com.lingion.sleepy.domain.imports.LoadedImportTarget
import com.lingion.sleepy.domain.imports.LogicalBaseReference
import com.lingion.sleepy.domain.imports.ReviewedImport
import com.lingion.sleepy.domain.imports.ReviewLocator
import com.lingion.sleepy.domain.imports.ReviewRequirementKind
import com.lingion.sleepy.domain.imports.RichDraftConverter
import com.lingion.sleepy.domain.imports.FrozenImportSource
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.ExceptionKind
import com.lingion.sleepy.domain.model.MeetingException
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.ScheduleProvenance
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.notice.NoticeAdjustmentApplyResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentCodes
import com.lingion.sleepy.domain.notice.NoticeAdjustmentConfirmation
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistory
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryEntry
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPlanner
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreview
import com.lingion.sleepy.domain.notice.NoticeAdjustmentPreviewResult
import com.lingion.sleepy.domain.notice.NoticeAdjustmentProposal
import com.lingion.sleepy.domain.notice.NoticeAdjustmentRequest
import com.lingion.sleepy.domain.notice.NoticeAdjustmentUndoResult
import com.lingion.sleepy.domain.notice.NoticeEventCodec
import com.lingion.sleepy.domain.notice.NoticeEventPayload
import com.lingion.sleepy.domain.notice.NoticeEventPlanner
import com.lingion.sleepy.importing.NoticeExceptionRoles
import com.lingion.sleepy.importing.NoticeIntentKind
import com.lingion.sleepy.domain.notice.NoticeOccurrenceView
import com.lingion.sleepy.domain.notice.NoticePreviousException
import com.lingion.sleepy.domain.notice.NoticeProblem
import com.lingion.sleepy.domain.notice.NoticeProposalResult
import com.lingion.sleepy.importing.NoticeRoomChange
import com.lingion.sleepy.importing.NoticeRoomRole
import com.lingion.sleepy.importing.NoticeScope
import com.lingion.sleepy.domain.notice.NoticeWriteMode
import com.lingion.sleepy.domain.notice.NoticeProvenanceSnapshot
import com.lingion.sleepy.domain.repository.NoticeAdjustmentRepository
import com.lingion.sleepy.importing.DraftTimeRange
import com.lingion.sleepy.importing.RichExceptionDraft
import com.lingion.sleepy.importing.RichExceptionKind
import com.lingion.sleepy.importing.RichTimetableDraft
import com.lingion.sleepy.importing.formats.FormatParseResult
import com.lingion.sleepy.importing.formats.PendingExceptionAttachment
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class RoomNoticeAdjustmentRepository(private val database: TimetableDatabase) : NoticeAdjustmentRepository {
    private val schedules = RoomScheduleRepository(database)
    private val scheduleDao = database.scheduleDao()
    private val dao = database.noticeAdjustmentDao()

    /** Synthetic failure points for transaction rollback tests; production leaves this empty. */
    internal var noticeFault: (String) -> Unit = {}

    private val semesterId: Long = 1

    override suspend fun preview(request: NoticeAdjustmentRequest): NoticeAdjustmentPreviewResult {
        currentCoroutineContext().ensureActive()
        return try {
            database.withTransaction {
                val loaded = schedules.noticeLoadTarget(request.target.semesterId)
                val current = currentTarget(loaded)
                if (request.target != current) return@withTransaction blocked(NoticeAdjustmentCodes.STALE_TARGET)
                // A repeated request is reported before planning: after the first application the
                // occurrence no longer matches the notice's old scope, so planning would be stale.
                val preKey = NoticeEventPlanner.requestKey(
                    current.scopeToken, current.semesterId, request.intent,
                    request.selection?.courseId ?: request.extraCourse?.courseId,
                    request.selection, request.extraCourse?.courseId,
                )
                dao.active(current.scopeToken, current.semesterId, preKey)?.let { active ->
                    return@withTransaction if (effectState(active, loaded) == EffectState.VALID) {
                        NoticeAdjustmentPreviewResult.AlreadyApplied(active.id, current.expectedRevision)
                    } else {
                        blocked(NoticeAdjustmentCodes.STALE_EVENT_EFFECT)
                    }
                }
                val bases = ImportOccurrencePlanner.logicalBaseDetails(loaded)
                when (val result = NoticeAdjustmentPlanner.propose(
                    request.intent, loaded.snapshot, bases, current, request.selection,
                    request.acknowledgedConflicts, request.replaceExistingAdjustment, request.extraCourse,
                )) {
                    is NoticeProposalResult.Blocked -> NoticeAdjustmentPreviewResult.Blocked(result.problems, result.candidates)
                    is NoticeProposalResult.NotReady -> NoticeAdjustmentPreviewResult.ConflictPending(result.proposal, result.problems)
                    is NoticeProposalResult.Ready -> {
                        val proposal = result.proposal
                        val key = NoticeEventPlanner.requestKey(
                            current.scopeToken, current.semesterId, request.intent, proposal, request.extraCourse?.courseId,
                        )
                        val active = dao.active(current.scopeToken, current.semesterId, key)
                        val confirmation = NoticeAdjustmentConfirmation(
                            current, key, proposal.semanticKey, proposal.confirmDigest, request.intent,
                            request.selection, request.extraCourse, request.acknowledgedConflicts,
                            request.replaceExistingAdjustment,
                            request.intent.rawText.take(NoticeEventCodec.MAX_SOURCE_TEXT),
                            ImportCanonical.digest(request.intent.rawText),
                        )
                        NoticeAdjustmentPreviewResult.Ready(NoticeAdjustmentPreview(current, proposal, key, confirmation, active?.id))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            blocked(NoticeAdjustmentCodes.PREVIEW_FAILED)
        }
    }

    override suspend fun apply(confirmation: NoticeAdjustmentConfirmation): NoticeAdjustmentApplyResult {
        currentCoroutineContext().ensureActive()
        val scope = confirmation.target.scopeToken
        val semester = confirmation.target.semesterId
        // Idempotency is checked before re-planning: a second submission of the same semantics
        // (even with the original confirmation) must not be mistaken for a stale/new request.
        val activeState = try {
            database.withTransaction {
                val existing = dao.active(scope, semester, confirmation.requestKey)
                if (existing == null) null else {
                    val loaded = schedules.noticeLoadTarget(semester)
                    Triple(existing, scheduleDao.semester(semester)?.revision ?: 0L, effectState(existing, loaded))
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (activeState != null) {
            return if (activeState.third == EffectState.VALID) {
                NoticeAdjustmentApplyResult.AlreadyApplied(activeState.first.id, activeState.second)
            } else {
                NoticeAdjustmentApplyResult.Rejected(NoticeAdjustmentCodes.STALE_EVENT_EFFECT)
            }
        }
        return try {
            database.withTransaction { applyInside(confirmation) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (abort: NoticeAbort) {
            NoticeAdjustmentApplyResult.Rejected(abort.code, abort.detail)
        } catch (_: SQLiteConstraintException) {
            // A concurrent transaction won the unique activeKey; report it as already applied.
            val existingState = try {
                database.withTransaction {
                    val existing = dao.active(scope, semester, confirmation.requestKey)
                    if (existing == null) null else existing to effectState(existing, schedules.noticeLoadTarget(semester))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (existingState != null && existingState.second == EffectState.VALID) {
                NoticeAdjustmentApplyResult.AlreadyApplied(existingState.first.id, confirmation.target.expectedRevision)
            } else if (existingState != null) {
                NoticeAdjustmentApplyResult.Rejected(NoticeAdjustmentCodes.STALE_EVENT_EFFECT)
            } else NoticeAdjustmentApplyResult.Rejected(NoticeAdjustmentCodes.WRITE_CONFLICT)
        } catch (_: Exception) {
            NoticeAdjustmentApplyResult.Rejected(NoticeAdjustmentCodes.APPLY_FAILED)
        }
    }

    override suspend fun history(limit: Int, beforeEventId: Long?): NoticeAdjustmentHistoryResult {
        if (limit !in 1..100) return NoticeAdjustmentHistoryResult.Failed(NoticeAdjustmentCodes.INVALID_LIMIT)
        currentCoroutineContext().ensureActive()
        return try {
            database.withTransaction {
                val rows = dao.history(database.scopeToken, semesterId, beforeEventId, limit + 1)
                val page = rows.take(limit)
                val entries = page.map { row ->
                    val before = row.beforeJson?.let { text -> decodeViewOrFail(text, row.id) }
                    val after = row.afterJson?.let { text -> decodeViewOrFail(text, row.id) }
                    NoticeAdjustmentHistoryEntry(
                        eventId = row.id,
                        kind = enumOrFail(row.kind, row.id),
                        courseId = row.courseId,
                        courseTitle = row.courseTitle,
                        originalDate = row.originalEpochDay?.let(LocalDate::ofEpochDay),
                        targetDate = row.targetEpochDay?.let(LocalDate::ofEpochDay),
                        before = before,
                        after = after,
                        sourceText = row.sourceText,
                        state = row.state,
                        appliedRevision = row.appliedRevision,
                        undoneRevision = row.undoneRevision,
                        canUndo = row.state == "APPLIED",
                    )
                }
                NoticeAdjustmentHistoryResult.Loaded(
                    NoticeAdjustmentHistory(entries, if (rows.size > limit) page.last().id else null)
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (abort: NoticeAbort) {
            NoticeAdjustmentHistoryResult.Failed(abort.code, abort.detail?.toLongOrNull())
        } catch (_: Exception) {
            NoticeAdjustmentHistoryResult.Failed(NoticeAdjustmentCodes.CORRUPT_EVENT)
        }
    }

    override suspend fun undo(eventId: Long, expectedRevision: Long): NoticeAdjustmentUndoResult {
        currentCoroutineContext().ensureActive()
        return try {
            database.withTransaction { undoInside(eventId, expectedRevision) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (abort: NoticeAbort) {
            NoticeAdjustmentUndoResult.Rejected(abort.code, abort.detail)
        } catch (_: Exception) {
            NoticeAdjustmentUndoResult.Rejected(NoticeAdjustmentCodes.UNDO_FAILED)
        }
    }

    // ---- apply -----------------------------------------------------------------------------

    private suspend fun applyInside(confirmation: NoticeAdjustmentConfirmation): NoticeAdjustmentApplyResult {
        val loaded = schedules.noticeLoadTarget(confirmation.target.semesterId)
        val current = currentTarget(loaded)
        // Duplicate content is reported first: a concurrent submission may have committed after
        // this confirmation was previewed, and must not be misread as a stale revision.
        dao.active(current.scopeToken, current.semesterId, confirmation.requestKey)?.let { existing ->
            return if (effectState(existing, loaded) == EffectState.VALID) {
                NoticeAdjustmentApplyResult.AlreadyApplied(existing.id, current.expectedRevision)
            } else {
                abort(NoticeAdjustmentCodes.STALE_EVENT_EFFECT)
            }
        }
        if (current != confirmation.target) abort(NoticeAdjustmentCodes.STALE_TARGET)
        if (ImportCanonical.digest(confirmation.intent.rawText) != confirmation.sourceDigest) {
            abort(NoticeAdjustmentCodes.STALE_CONFIRMATION)
        }
        val bases = ImportOccurrencePlanner.logicalBaseDetails(loaded)
        val proposal = when (val result = NoticeAdjustmentPlanner.propose(
            confirmation.intent, loaded.snapshot, bases, current, confirmation.selection,
            confirmation.acknowledgedConflicts, confirmation.replaceExistingAdjustment, confirmation.extraCourse,
        )) {
            is NoticeProposalResult.Ready -> result.proposal
            is NoticeProposalResult.NotReady -> abort(NoticeAdjustmentCodes.CONFLICT_PENDING)
            is NoticeProposalResult.Blocked -> abort(result.problems.first().code)
        }
        if (proposal.semanticKey != confirmation.semanticKey || proposal.confirmDigest != confirmation.confirmDigest) {
            abort(NoticeAdjustmentCodes.STALE_CONFIRMATION)
        }
        val key = NoticeEventPlanner.requestKey(
            current.scopeToken, current.semesterId, confirmation.intent, proposal, confirmation.extraCourse?.courseId,
        )
        if (key != confirmation.requestKey) abort(NoticeAdjustmentCodes.STALE_CONFIRMATION)
        dao.active(current.scopeToken, current.semesterId, key)?.let {
            return NoticeAdjustmentApplyResult.AlreadyApplied(it.id, current.expectedRevision)
        }
        val courseId = proposal.courseId ?: abort(NoticeAdjustmentCodes.NO_COURSE)
        val course = loaded.snapshot.courses.singleOrNull { it.id == courseId }
            ?: abort(NoticeAdjustmentCodes.COURSE_MISSING)
        val normalized = loaded.states.containsKey(courseId)
        if (proposal.replacesExistingAdjustment && normalized) {
            abort(NoticeAdjustmentCodes.REPLACE_UNSUPPORTED_FOR_NORMALIZED)
        }
        if (proposal.kind != NoticeIntentKind.EXTRA && course.meetings.none { it.id == proposal.meetingId }) {
            abort(NoticeAdjustmentCodes.MEETING_MISSING)
        }
        return if (normalized) applyNormalized(loaded, proposal, confirmation, courseId)
        else applyManual(loaded, proposal, confirmation, course, courseId)
    }

    private suspend fun applyManual(
        loaded: LoadedImportTarget,
        proposal: NoticeAdjustmentProposal,
        confirmation: NoticeAdjustmentConfirmation,
        course: com.lingion.sleepy.domain.model.ScheduledCourse,
        courseId: Long,
    ): NoticeAdjustmentApplyResult {
        val meetingId = proposal.meetingId ?: course.meetings.firstOrNull()?.id
            ?: abort(NoticeAdjustmentCodes.MEETING_MISSING)
        val meeting = course.meetings.single { it.id == meetingId }
        val row = when (val planned = NoticeEventPlanner.manualRow(
            proposal, loaded.snapshot.periods,
            baseSections = meeting.sections,
            baseRanges = TimetableRules.periodIntervals(meeting, loaded.snapshot.periods),
            baseTeacher = meeting.teacher ?: course.teacher,
            baseRoom = meeting.room ?: course.room,
            baseProject = meeting.experimentProject,
            baseGroup = meeting.group,
        )) {
            is NoticeEventPlanner.ManualRow.Ready -> planned.exception
            is NoticeEventPlanner.ManualRow.Unsupported -> abort(planned.code)
        }
        var previous: NoticePreviousException? = null
        if (proposal.replacesExistingAdjustment) {
            val oldId = proposal.replacedExceptionId ?: abort(NoticeAdjustmentCodes.REPLACED_ROW_MISSING)
            val oldRow = dao.exception(oldId, loaded.snapshot.settings.id) ?: abort(NoticeAdjustmentCodes.REPLACED_ROW_MISSING)
            if (oldRow.meetingId != meetingId) abort(NoticeAdjustmentCodes.REPLACED_ROW_MISSING)
            previous = previousFrom(oldRow, dao.exceptionSections(oldId), dao.exceptionProvenance(oldId))
            if (dao.deleteException(oldId, loaded.snapshot.settings.id) != 1) abort(NoticeAdjustmentCodes.REPLACED_ROW_MISSING)
            noticeFault("manual_after_replace_delete")
        }
        val others = meeting.exceptions.filter { it.id != proposal.replacedExceptionId }
        if (row.originalDate != null && others.any { it.originalDate == row.originalDate }) abort("EXCEPTION_DATE_CONFLICT")
        val newId = scheduleDao.insertException(row.toEntity(meetingId))
        scheduleDao.insertExceptionSections(row.sections.orEmpty().sorted().map { ExceptionSectionEntity(newId, it) })
        noticeFault("manual_after_insert")
        val after = schedules.noticeReadSnapshot(loaded.snapshot.settings.id)
        val written = after.courses.single { it.id == courseId }.meetings.single { it.id == meetingId }
            .exceptions.single { it.id == newId }
        val payload = NoticeEventPayload(
            mode = NoticeWriteMode.MANUAL, exceptionIds = listOf(newId),
            exceptionFingerprints = listOf(NoticeEventPlanner.exceptionFingerprint(written)), previous = previous,
        )
        val afterFingerprint = afterFingerprint(after, courseId, proposal, listOf(newId))
        val eventId = insertEvent(loaded, proposal, confirmation, meetingId, payload, afterFingerprint)
        noticeFault("manual_before_revision")
        schedules.noticeIncrementRevision(loaded.snapshot.settings.id)
        return NoticeAdjustmentApplyResult.Applied(eventId, loaded.snapshot.revision + 1, "MANUAL")
    }

    private suspend fun applyNormalized(
        loaded: LoadedImportTarget,
        proposal: NoticeAdjustmentProposal,
        confirmation: NoticeAdjustmentConfirmation,
        courseId: Long,
    ): NoticeAdjustmentApplyResult {
        val reviewed = buildReviewed(loaded, proposal, confirmation)
            ?: abort(NoticeAdjustmentCodes.APPLY_FAILED, "REVIEW_NOT_READY")
        // Discover the import-domain conflicts first: their fingerprints are not the preview's
        // notice fingerprints. Each one must correspond to an explicitly acknowledged notice
        // conflict; anything new blocks instead of being auto-kept.
        val probe = ImportOccurrencePlanner.plan(reviewed, loaded, emptyList())
        val blocking = probe.problems.filter {
            it.code != "CONFLICT_REVIEW_REQUIRED" && it.code != "STALE_CONFLICT_DECISION"
        }
        if (blocking.isNotEmpty()) abort(blocking.first().code)
        val decisions = probe.conflicts.map { conflict ->
            val match = proposal.conflicts.firstOrNull { notice ->
                notice.otherCourseId == conflict.other.courseId &&
                    notice.date == conflict.incoming.date &&
                    notice.overlap.any { it == conflict.overlap }
            } ?: abort(NoticeAdjustmentCodes.CONFLICT_PENDING, "NEW_IMPORT_CONFLICT")
            if (match.fingerprint !in confirmation.acknowledgedConflicts) {
                abort(NoticeAdjustmentCodes.CONFLICT_PENDING)
            }
            ConflictReviewDecision(reviewed.binding, conflict.fingerprint, ConflictReviewChoice.KEEP)
        }
        val plan = ImportOccurrencePlanner.plan(reviewed, loaded, decisions)
        if (!plan.ready) abort(plan.problems.first().code)
        if (plan.removedCourseIds.isNotEmpty()) abort(NoticeAdjustmentCodes.APPLY_FAILED, "PLAN_REMOVES_COURSES")
        if (plan.courses.size != 1) abort(NoticeAdjustmentCodes.APPLY_FAILED, "PLAN_COURSE_COUNT")
        val planned = plan.courses.single()
        if (planned.existingCourseId != courseId) abort(NoticeAdjustmentCodes.APPLY_FAILED, "PLAN_WRONG_COURSE")
        if (plan.conflicts.any { conflict -> decisions.none { it.conflictFingerprint == conflict.fingerprint } }) {
            abort(NoticeAdjustmentCodes.CONFLICT_PENDING)
        }
        val previous = loaded.snapshot.courses.single { it.id == courseId }
        val beforeFingerprints = previous.meetings.flatMap { it.exceptions }
            .map(NoticeEventPlanner::exceptionFingerprint).groupingBy { it }.eachCount()
        val write = schedules.noticeWritePlannedCourse(planned, previous, loaded.snapshot.settings.id)
        noticeFault("normalized_after_children")
        val newOnes = mutableListOf<Pair<Long, MeetingException>>()
        val remaining = beforeFingerprints.toMutableMap()
        for (meeting in write.course.meetings) {
            for (exception in meeting.exceptions) {
                val fingerprint = NoticeEventPlanner.exceptionFingerprint(exception)
                val count = remaining[fingerprint] ?: 0
                if (count > 0) remaining[fingerprint] = count - 1 else newOnes += meeting.id to exception
            }
        }
        if (newOnes.isEmpty()) abort(NoticeAdjustmentCodes.APPLY_FAILED, "NO_NEW_EXCEPTION")
        val payload = NoticeEventPayload(
            mode = NoticeWriteMode.NORMALIZED,
            exceptionIds = newOnes.map { it.second.id },
            exceptionFingerprints = newOnes.map { NoticeEventPlanner.exceptionFingerprint(it.second) },
            previous = null,
        )
        val after = schedules.noticeReadSnapshot(loaded.snapshot.settings.id)
        val afterFingerprint = afterFingerprint(after, courseId, proposal, newOnes.map { it.second.id })
        val eventId = insertEvent(loaded, proposal, confirmation, newOnes.first().first, payload, afterFingerprint)
        noticeFault("normalized_before_revision")
        schedules.noticeIncrementRevision(loaded.snapshot.settings.id)
        return NoticeAdjustmentApplyResult.Applied(eventId, loaded.snapshot.revision + 1, "NORMALIZED")
    }

    private fun buildReviewed(
        loaded: LoadedImportTarget,
        proposal: NoticeAdjustmentProposal,
        confirmation: NoticeAdjustmentConfirmation,
    ): ReviewedImport? {
        val before = proposal.before
        val after = proposal.after
        val kind = when (proposal.kind) {
            NoticeIntentKind.CANCEL -> RichExceptionKind.CANCEL
            NoticeIntentKind.EXTRA -> RichExceptionKind.EXTRA
            else -> RichExceptionKind.RESCHEDULE
        }
        val originalScope = before?.let { scopeOf(it, loaded.snapshot.periods) }
        val targetScope = after?.let { scopeOf(it, loaded.snapshot.periods) }
        if (kind == RichExceptionKind.CANCEL && originalScope == null) return null
        if (kind != RichExceptionKind.CANCEL && targetScope == null) return null
        val draftScope = if (kind == RichExceptionKind.CANCEL) originalScope else targetScope
        val draft = RichExceptionDraft(
            kind = kind,
            originalDate = proposal.originalDate,
            targetDate = if (kind == RichExceptionKind.CANCEL) null else proposal.targetDate,
            customTime = draftScope?.time,
            sections = draftScope?.sections,
            teacher = if (kind == RichExceptionKind.EXTRA) after?.teacher
                else after?.teacher?.takeIf { it != before?.teacher },
            room = if (kind == RichExceptionKind.EXTRA) after?.room
                else after?.room?.takeIf { it != before?.room },
            experimentProject = if (kind == RichExceptionKind.EXTRA) after?.project
                else after?.project?.takeIf { it != before?.project },
            group = if (kind == RichExceptionKind.EXTRA) after?.group
                else after?.group?.takeIf { it != before?.group },
            missingFields = emptySet(),
        )
        val roles = NoticeExceptionRoles(
            intent = proposal.kind,
            original = NoticeScope(originalScope?.sections ?: emptySet(), originalScope?.time),
            target = NoticeScope(targetScope?.sections ?: emptySet(), targetScope?.time),
            room = if (before != null && after != null && before.room != after.room) {
                NoticeRoomChange(NoticeRoomRole.CHANGE, before.room, after.room)
            } else null,
        )
        val pending = PendingExceptionAttachment(
            exception = draft, reason = "notice-adjustment", courseName = proposal.courseTitle, roles = roles,
        )
        val source = FrozenImportSource.fromFormat(
            FormatParseResult(draft = RichTimetableDraft(courses = emptyList()), pendingExceptions = listOf(pending))
        )
        val target = ImportTarget(
            loaded.snapshot.scopeToken, loaded.snapshot.settings.id, loaded.snapshot.revision, loaded.databaseSchemaVersion,
        )
        val context = ImportReviewContext(
            target, ImportMode.APPEND,
            loaded.snapshot.settings.startDate?.let(TimetableRules::mondayOf),
            loaded.snapshot.settings.totalWeeks, loaded.snapshot.periods,
        )
        val session = ImportReviewSession.create(source, context, selectedMeetings = emptySet(), selectedPending = setOf(0))
        val base = baseFor(loaded, proposal, confirmation) ?: return null
        val attachment = session.attachException(ReviewLocator(pendingIndex = 0), ExceptionAttachment.Bound(base))
        val evidence = session.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }
            .map { session.reviewEvidence(it.fingerprint) }
        val decisions = ImportReviewDecisions(evidence = evidence, attachments = listOf(attachment))
        val reviewed = (RichDraftConverter.convert(session, decisions) as? ImportReviewResult.Ready)?.reviewed ?: return null
        return runCatching { schedules.noticeVerifyReviewed(reviewed) }.getOrNull()
    }

    private fun baseFor(
        loaded: LoadedImportTarget,
        proposal: NoticeAdjustmentProposal,
        confirmation: NoticeAdjustmentConfirmation,
    ): LogicalBaseReference.StoredLogicalBase? {
        val courseId = proposal.courseId ?: return null
        val bases = ImportOccurrencePlanner.logicalBaseDetails(loaded)
        val candidate = proposal.candidate
        if (candidate != null) {
            return bases.singleOrNull {
                it.reference.courseId == courseId && it.reference.logicalBaseId == candidate.logicalBaseId &&
                    it.reference.structureFingerprint == candidate.structureFingerprint
            }?.reference
        }
        // A make-up has no original occurrence; it still belongs to one stable logical base of the
        // explicitly selected course, so the planner can own the Add without guessing a family.
        return bases.filter { it.reference.courseId == courseId }.minByOrNull { it.reference.logicalBaseId }?.reference
    }

    // ---- undo ------------------------------------------------------------------------------

    private suspend fun undoInside(eventId: Long, expectedRevision: Long): NoticeAdjustmentUndoResult {
        val event = dao.event(database.scopeToken, semesterId, eventId)
            ?: return NoticeAdjustmentUndoResult.Rejected(NoticeAdjustmentCodes.EVENT_NOT_FOUND)
        if (event.state != "APPLIED") return NoticeAdjustmentUndoResult.AlreadyUndone(eventId)
        val loaded = schedules.noticeLoadTarget(semesterId)
        if (loaded.snapshot.revision != expectedRevision) {
            return NoticeAdjustmentUndoResult.Rejected(NoticeAdjustmentCodes.STALE_REVISION)
        }
        if (event.courseId == null) return NoticeAdjustmentUndoResult.Rejected(NoticeAdjustmentCodes.UNDO_TARGET_MISSING)
        // Dependency is a stable logical occurrence, not "same course on the same day": two
        // independent meetings on one day must still be undoable separately.
        if (event.originalEpochDay != null && event.logicalBaseId != null && event.structureFingerprint != null &&
            dao.laterActiveOnLogicalOccurrence(
                database.scopeToken, semesterId, event.courseId,
                event.logicalBaseId, event.structureFingerprint, event.originalEpochDay, event.id,
            ) > 0
        ) {
            return NoticeAdjustmentUndoResult.Rejected(NoticeAdjustmentCodes.LATER_EVENT_DEPENDS)
        }
        val payload = try {
            NoticeEventCodec.decodePayload(event.payloadJson)
        } catch (_: Exception) {
            return NoticeAdjustmentUndoResult.Rejected(NoticeAdjustmentCodes.INVALID_EVENT_PAYLOAD)
        }
        when (effectState(event, loaded)) {
            EffectState.VALID -> Unit
            EffectState.TARGET_MISSING -> return NoticeAdjustmentUndoResult.Rejected(NoticeAdjustmentCodes.UNDO_TARGET_MISSING)
            EffectState.OWNERSHIP_CHANGED -> return NoticeAdjustmentUndoResult.Rejected(NoticeAdjustmentCodes.UNDO_OWNERSHIP_CHANGED)
            EffectState.AFTER_MISMATCH -> return NoticeAdjustmentUndoResult.Rejected(NoticeAdjustmentCodes.UNDO_AFTER_MISMATCH)
        }
        return when (payload.mode) {
            NoticeWriteMode.MANUAL -> undoManual(loaded, event, payload)
            NoticeWriteMode.NORMALIZED -> undoNormalized(loaded, event, payload)
        }
    }

    private suspend fun undoManual(
        loaded: LoadedImportTarget,
        event: NoticeAdjustmentEventEntity,
        payload: NoticeEventPayload,
    ): NoticeAdjustmentUndoResult {
        val courseId = event.courseId ?: abort(NoticeAdjustmentCodes.UNDO_TARGET_MISSING)
        val course = loaded.snapshot.courses.singleOrNull { it.id == courseId }
            ?: abort(NoticeAdjustmentCodes.UNDO_TARGET_MISSING)
        val rows = payload.exceptionIds.map { id ->
            dao.exception(id, semesterId) ?: abort(NoticeAdjustmentCodes.UNDO_TARGET_MISSING)
        }
        if (rows.any { row -> course.meetings.none { it.id == row.meetingId } }) {
            abort(NoticeAdjustmentCodes.UNDO_TARGET_MISSING)
        }
        rows.forEachIndexed { index, row ->
            val fingerprint = rowFingerprint(row, dao.exceptionSections(row.id), dao.exceptionProvenance(row.id))
            if (fingerprint != payload.exceptionFingerprints[index]) abort(NoticeAdjustmentCodes.UNDO_AFTER_MISMATCH)
        }
        rows.forEach { check(dao.deleteException(it.id, semesterId) == 1) }
        payload.previous?.let { previous -> restorePrevious(previous, rows.first().meetingId) }
        try {
            schedules.noticeReadSnapshot(semesterId)
        } catch (_: Exception) {
            abort(NoticeAdjustmentCodes.UNDO_INCONSISTENT)
        }
        val next = loaded.snapshot.revision + 1
        if (dao.markUndone(event.id, next) != 1) abort(NoticeAdjustmentCodes.UNDO_FAILED)
        schedules.noticeIncrementRevision(semesterId)
        return NoticeAdjustmentUndoResult.Undone(event.id, next)
    }

    private suspend fun undoNormalized(
        loaded: LoadedImportTarget,
        event: NoticeAdjustmentEventEntity,
        payload: NoticeEventPayload,
    ): NoticeAdjustmentUndoResult {
        if (payload.previous != null) abort(NoticeAdjustmentCodes.UNDO_INCONSISTENT)
        val courseId = event.courseId ?: abort(NoticeAdjustmentCodes.UNDO_TARGET_MISSING)
        val state = loaded.states[courseId] ?: abort(NoticeAdjustmentCodes.UNDO_STATE_MISSING)
        val rows = payload.exceptionIds.map { id ->
            dao.exception(id, semesterId) ?: abort(NoticeAdjustmentCodes.UNDO_TARGET_MISSING)
        }
        rows.forEachIndexed { index, row ->
            val fingerprint = rowFingerprint(row, dao.exceptionSections(row.id), dao.exceptionProvenance(row.id))
            if (fingerprint != payload.exceptionFingerprints[index]) abort(NoticeAdjustmentCodes.UNDO_AFTER_MISMATCH)
        }
        rows.forEach { check(dao.deleteException(it.id, semesterId) == 1) }
        val snapshot = try {
            schedules.noticeReadSnapshot(semesterId)
        } catch (_: Exception) {
            abort(NoticeAdjustmentCodes.UNDO_INCONSISTENT)
        }
        val course = snapshot.courses.singleOrNull { it.id == courseId }
            ?: abort(NoticeAdjustmentCodes.UNDO_TARGET_MISSING)
        val rebuilt = try {
            NoticeEventPlanner.rebuildNormalizedState(state, course, snapshot)
        } catch (_: Exception) {
            abort(NoticeAdjustmentCodes.UNDO_INCONSISTENT)
        }
        val encoded = try {
            ImportNormalizationCodec.encode(rebuilt)
        } catch (_: Exception) {
            abort(NoticeAdjustmentCodes.UNDO_INCONSISTENT)
        }
        scheduleDao.putImportState(ImportNormalizationStateEntity(courseId, rebuilt.version, encoded))
        noticeFault("undo_after_state")
        val next = loaded.snapshot.revision + 1
        if (dao.markUndone(event.id, next) != 1) abort(NoticeAdjustmentCodes.UNDO_FAILED)
        schedules.noticeIncrementRevision(semesterId)
        return NoticeAdjustmentUndoResult.Undone(event.id, next)
    }

    // ---- helpers ---------------------------------------------------------------------------

    private suspend fun insertEvent(
        loaded: LoadedImportTarget,
        proposal: NoticeAdjustmentProposal,
        confirmation: NoticeAdjustmentConfirmation,
        meetingId: Long?,
        payload: NoticeEventPayload,
        afterFingerprint: String,
    ): Long {
        val logical = NoticeEventPlanner.logicalBase(proposal)
        return dao.insert(
            NoticeAdjustmentEventEntity(
                requestKey = confirmation.requestKey,
                semanticKey = proposal.semanticKey,
                confirmDigest = proposal.confirmDigest,
                scopeToken = loaded.snapshot.scopeToken,
                semesterId = loaded.snapshot.settings.id,
                kind = proposal.kind.name,
                courseId = proposal.courseId,
                courseTitle = proposal.courseTitle,
                logicalBaseId = logical?.first,
                structureFingerprint = logical?.second,
                meetingId = meetingId,
                originalEpochDay = proposal.originalDate?.toEpochDay(),
                targetEpochDay = proposal.targetDate.toEpochDay(),
                beforeJson = proposal.before?.let(NoticeEventCodec::encodeView),
                afterJson = proposal.after?.let(NoticeEventCodec::encodeView),
                afterFingerprint = afterFingerprint,
                payloadJson = NoticeEventCodec.encodePayload(payload),
                sourceText = confirmation.sourceText.trim().ifBlank { null },
                sourceDigest = confirmation.sourceDigest,
                dateBasisJson = NoticeEventCodec.encodeBasis(proposal.basis),
                appliedRevision = loaded.snapshot.revision,
                state = "APPLIED",
                activeKey = confirmation.requestKey,
            )
        )
    }

    private suspend fun restorePrevious(previous: NoticePreviousException, meetingId: Long) {
        val id = scheduleDao.insertException(
            MeetingExceptionEntity(
                meetingId = meetingId, kind = previous.kind,
                originalEpochDay = previous.originalEpochDay, targetEpochDay = previous.targetEpochDay,
                customStartMinute = previous.customStartMinute, customEndMinute = previous.customEndMinute,
                hasSectionsOverride = previous.hasSections, teacher = previous.teacher, room = previous.room,
                experimentProject = previous.project, group = previous.group,
            )
        )
        scheduleDao.insertExceptionSections(previous.sections.sorted().map { ExceptionSectionEntity(id, it) })
        scheduleDao.insertProvenance(previous.provenance.mapIndexed { index, snapshot ->
            ScheduleProvenanceEntity(
                exceptionId = id, position = index, sourceId = snapshot.sourceId, adapterId = snapshot.adapterId,
                page = snapshot.page, left = snapshot.left, top = snapshot.top, right = snapshot.right, bottom = snapshot.bottom,
                rawText = snapshot.rawText, sourceLocator = snapshot.sourceLocator,
            )
        })
    }

    private fun previousFrom(
        row: MeetingExceptionEntity,
        sections: List<ExceptionSectionEntity>,
        provenance: List<ScheduleProvenanceEntity>,
    ): NoticePreviousException = NoticePreviousException(
        kind = row.kind, originalEpochDay = row.originalEpochDay, targetEpochDay = row.targetEpochDay,
        customStartMinute = row.customStartMinute, customEndMinute = row.customEndMinute,
        hasSections = row.hasSectionsOverride, sections = sections.map { it.section },
        teacher = row.teacher, room = row.room, project = row.experimentProject, group = row.group,
        provenance = provenance.map {
            NoticeProvenanceSnapshot(it.sourceId, it.adapterId, it.page, it.left, it.top, it.right, it.bottom, it.rawText, it.sourceLocator)
        },
    )

    private fun rowFingerprint(
        row: MeetingExceptionEntity,
        sections: List<ExceptionSectionEntity>,
        provenance: List<ScheduleProvenanceEntity>,
    ): String = NoticeEventPlanner.exceptionFingerprint(
        MeetingException(
            id = 0,
            kind = ExceptionKind.valueOf(row.kind),
            originalDate = row.originalEpochDay?.let(LocalDate::ofEpochDay),
            targetDate = row.targetEpochDay?.let(LocalDate::ofEpochDay),
            customTime = if (row.customStartMinute == null) null else TimeRange(
                timeOfMinute(row.customStartMinute), timeOfMinute(row.customEndMinute ?: abortFingerprint())
            ),
            sections = if (row.hasSectionsOverride) sections.map { it.section }.toSet() else null,
            teacher = row.teacher, room = row.room, experimentProject = row.experimentProject, group = row.group,
            provenance = provenance.map {
                ScheduleProvenance(it.sourceId, it.adapterId, it.page,
                    if (it.left == null) null else com.lingion.sleepy.domain.model.SourceBounds(it.left, it.top!!, it.right!!, it.bottom!!),
                    it.rawText, it.sourceLocator)
            },
        )
    )

    private fun abortFingerprint(): Nothing = throw IllegalStateException("missing custom end minute")

    private fun MeetingException.toEntity(meetingId: Long): MeetingExceptionEntity = MeetingExceptionEntity(
        meetingId = meetingId, kind = kind.name,
        originalEpochDay = originalDate?.toEpochDay(), targetEpochDay = targetDate?.toEpochDay(),
        customStartMinute = customTime?.start?.let { it.hour * 60 + it.minute },
        customEndMinute = customTime?.end?.let { it.hour * 60 + it.minute },
        hasSectionsOverride = sections != null, teacher = teacher, room = room,
        experimentProject = experimentProject, group = group,
    )

    private data class Scope(val sections: Set<Int>?, val time: DraftTimeRange?)

    /**
     * Choose a lossless representation for the import exception contract. A section range is only
     * used when its ranges really are the current period times; a continuous real clock must stay a
     * clock, otherwise a valid customTime would be turned back into the default period envelope.
     */
    private fun scopeOf(view: NoticeOccurrenceView, periods: List<Period>): Scope? {
        if (view.sections.isNotEmpty()) {
            val expected = periods.filter { it.number in view.sections }
                .map { TimeRange(it.start, it.end) }
                .sortedWith(compareBy({ it.start }, { it.end }))
            val actual = view.ranges.sortedWith(compareBy({ it.start }, { it.end }))
            if (expected.size == view.sections.size && expected == actual) return Scope(view.sections, null)
            if (view.ranges.size == 1) {
                return Scope(null, DraftTimeRange(view.ranges.single().start, view.ranges.single().end))
            }
            return null
        }
        return view.ranges.singleOrNull()?.let { Scope(null, DraftTimeRange(it.start, it.end)) }
    }

    /** The real effective occurrence(s) this event created, for the stored guard fingerprint. */
    private fun effectiveAfterView(
        snapshot: TimetableSnapshot,
        courseId: Long,
        date: LocalDate,
        exceptionIds: Collection<Long>,
    ): NoticeOccurrenceView? {
        val ids = exceptionIds.toSet()
        val occurrences = TimetableRules.occurrencesOn(snapshot, date)
            .filter { it.course.id == courseId && it.exception?.id in ids }
        if (occurrences.isEmpty()) return null
        val teacher = occurrences.map { it.effectiveTeacher }.toSet()
        val room = occurrences.map { it.effectiveRoom }.toSet()
        val project = occurrences.map { it.effectiveProject }.toSet()
        val group = occurrences.map { it.effectiveGroup }.toSet()
        if (teacher.size != 1 || room.size != 1 || project.size != 1 || group.size != 1) return null
        return NoticeOccurrenceView(
            date = date,
            sections = occurrences.flatMap { it.effectiveSections }.toSet(),
            ranges = occurrences.flatMap { it.timeRanges }.distinct().sortedWith(compareBy({ it.start }, { it.end })),
            teacher = teacher.single(),
            room = room.single(),
            project = project.single(),
            group = group.single(),
        )
    }

    private fun afterFingerprint(
        snapshot: TimetableSnapshot,
        courseId: Long,
        proposal: NoticeAdjustmentProposal,
        exceptionIds: Collection<Long>,
    ): String {
        if (proposal.after == null) return "ABSENT"
        val view = effectiveAfterView(snapshot, courseId, proposal.targetDate, exceptionIds)
            ?: abort(NoticeAdjustmentCodes.APPLY_FAILED, "NO_EFFECTIVE_AFTER")
        return NoticeEventPlanner.occurrenceViewFingerprint(view)
    }

    private enum class EffectState { VALID, TARGET_MISSING, AFTER_MISMATCH, OWNERSHIP_CHANGED }

    /** Proves the event still yields the recorded effective occurrence in the current snapshot. */
    private suspend fun effectState(event: NoticeAdjustmentEventEntity, loaded: LoadedImportTarget): EffectState {
        val payload = try {
            NoticeEventCodec.decodePayload(event.payloadJson)
        } catch (_: Exception) {
            return EffectState.TARGET_MISSING
        }
        val courseId = event.courseId ?: return EffectState.TARGET_MISSING
        val course = loaded.snapshot.courses.singleOrNull { it.id == courseId } ?: return EffectState.TARGET_MISSING
        if (event.logicalBaseId != null && event.structureFingerprint != null) {
            val bases = ImportOccurrencePlanner.logicalBaseDetails(loaded)
            val stable = bases.any {
                it.reference.courseId == courseId && it.reference.logicalBaseId == event.logicalBaseId &&
                    it.reference.structureFingerprint == event.structureFingerprint
            }
            if (!stable) return EffectState.OWNERSHIP_CHANGED
        }
        val rows = payload.exceptionIds.map { dao.exception(it, semesterId) ?: return EffectState.TARGET_MISSING }
        rows.forEachIndexed { index, row ->
            if (rowFingerprint(row, dao.exceptionSections(row.id), dao.exceptionProvenance(row.id)) !=
                payload.exceptionFingerprints[index]
            ) return EffectState.AFTER_MISMATCH
        }
        return if (event.afterFingerprint == "ABSENT") {
            // The base occurrence must still be a real, currently absent occurrence.
            val meetingIds = rows.map { it.meetingId }.toSet()
            val meeting = course.meetings.firstOrNull { it.id in meetingIds } ?: return EffectState.TARGET_MISSING
            val date = event.originalEpochDay?.let(LocalDate::ofEpochDay) ?: return EffectState.TARGET_MISSING
            if (!TimetableRules.baseOccursOn(meeting, loaded.snapshot.settings, date)) return EffectState.AFTER_MISMATCH
            if (TimetableRules.occurrencesOn(loaded.snapshot, date).any {
                    it.course.id == courseId && it.meeting.id in meetingIds &&
                        it.exception?.kind != ExceptionKind.EXTRA
                }
            ) EffectState.AFTER_MISMATCH else EffectState.VALID
        } else {
            val date = event.targetEpochDay?.let(LocalDate::ofEpochDay) ?: return EffectState.TARGET_MISSING
            val view = effectiveAfterView(loaded.snapshot, courseId, date, payload.exceptionIds)
                ?: return EffectState.AFTER_MISMATCH
            if (NoticeEventPlanner.occurrenceViewFingerprint(view) == event.afterFingerprint) {
                EffectState.VALID
            } else EffectState.AFTER_MISMATCH
        }
    }

    private fun currentTarget(loaded: LoadedImportTarget): ImportTarget = ImportTarget(
        loaded.snapshot.scopeToken, loaded.snapshot.settings.id, loaded.snapshot.revision, loaded.databaseSchemaVersion,
    )

    private fun blocked(code: String): NoticeAdjustmentPreviewResult =
        NoticeAdjustmentPreviewResult.Blocked(listOf(NoticeProblem(code)))

    private fun enumOrFail(name: String, eventId: Long): NoticeIntentKind = try {
        NoticeIntentKind.valueOf(name)
    } catch (_: Exception) {
        throw NoticeAbort(NoticeAdjustmentCodes.CORRUPT_EVENT, eventId.toString())
    }

    private fun decodeViewOrFail(text: String, eventId: Long): NoticeOccurrenceView = try {
        NoticeEventCodec.decodeView(text)
    } catch (_: Exception) {
        throw NoticeAbort(NoticeAdjustmentCodes.CORRUPT_EVENT, eventId.toString())
    }

    private fun abort(code: String, detail: String? = null): Nothing = throw NoticeAbort(code, detail)

    private class NoticeAbort(val code: String, val detail: String? = null) : Exception(code)
}