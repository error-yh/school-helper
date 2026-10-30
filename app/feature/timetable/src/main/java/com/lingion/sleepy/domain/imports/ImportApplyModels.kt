/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.ScheduleProvenance
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.importing.PeriodProposal
import java.time.LocalDate

/** All four values are compared by C3 inside its transaction. Schema is the database version. */
data class ImportTarget(val scopeToken: String, val semesterId: Long, val expectedRevision: Long, val databaseSchemaVersion: Int) {
    init { require(scopeToken.isNotBlank()); require(semesterId > 0); require(expectedRevision >= 0); require(databaseSchemaVersion > 0) }
}

/** Zero-based positions in ONE frozen source; never persisted as machine lineage in provenance. */
data class ReviewLocator(
    val sourceOrdinal: Int = 0, val courseIndex: Int? = null, val meetingIndex: Int? = null,
    val exceptionIndex: Int? = null, val pendingIndex: Int? = null, val relativeIndex: Int? = null,
    val field: String? = null,
) {
    init { require(sourceOrdinal == 0); require(listOfNotNull(courseIndex, meetingIndex, exceptionIndex, pendingIndex, relativeIndex).all { it >= 0 }); require(meetingIndex == null || courseIndex != null); require(exceptionIndex == null || meetingIndex != null) }
}

data class ReviewedCourseIdentity(
    val name: String, val sourceCourseCode: String? = null, val sourceClassCode: String? = null,
    val teacher: String = "", val room: String = "", val color: Long = 0xFF6750A4,
)

data class ImportReviewContext(
    val target: ImportTarget, val mode: ImportMode = ImportMode.APPEND,
    val termMonday: LocalDate? = null, val maxWeek: Int = 20,
    val targetPeriods: List<Period> = emptyList(),
    /** Explicit course-level corrections only. A meeting's teacher/room never becomes defaults. */
    val courseIdentities: Map<Int, ReviewedCourseIdentity> = emptyMap(),
) { init { require(maxWeek in 1..1000); require(termMonday == null || termMonday.dayOfWeek.value == 1) } }

data class SourcePeriodInterval(val section: Int, val range: TimeRange, val provenance: List<ScheduleProvenance>)
sealed interface ReviewedTimeSpec {
    data class Continuous(val range: TimeRange) : ReviewedTimeSpec
    data class TargetSections(val sections: Set<Int>, val intervals: List<SourcePeriodInterval>) : ReviewedTimeSpec
    /** Disjoint source periods stay disjoint. The source arrangement is never counted as fragments. */
    data class SourcePeriods(val sections: Set<Int>, val intervals: List<SourcePeriodInterval>, val proposals: List<PeriodProposal>) : ReviewedTimeSpec
    data object Unscheduled : ReviewedTimeSpec
}

data class ReviewedMeeting internal constructor(
    val source: ReviewLocator, val kind: MeetingKind, val day: Int,
    val weeks: Set<Int>, val declaredWeeks: Set<Int>, val dates: Set<LocalDate>,
    val originalSections: Set<Int>, val time: ReviewedTimeSpec,
    val teacher: String?, val room: String?, val group: String?, val experimentProject: String?,
    val isExperiment: Boolean, val provenance: List<ScheduleProvenance>,
    /** Retained source unknowns are explicit state, never wildcards for C2 matching. */
    val retainedUnknownFields: Set<com.lingion.sleepy.importing.ImportField> = emptySet(),
)

data class ReviewedCourse internal constructor(
    val identity: ReviewedCourseIdentity, val sourceCourses: Set<Int>, val meetings: List<ReviewedMeeting>,
    val provenance: List<ScheduleProvenance>, val existingArchive: LogicalBaseReference.StoredLogicalBase? = null,
    val retainedUnknownFields: Set<com.lingion.sleepy.importing.ImportField> = emptySet(),
)

data class ReviewedArchivePosition(val courseIndex: Int, val meetingIndex: Int)

/** A readable logical original, never a mutable normalization graph or current residual fragment. */
data class ImportLogicalBaseDisplay(val reference:LogicalBaseReference.StoredLogicalBase,val title:String,
    val original:CourseMeeting,val timeRanges:List<TimeRange>,val teacher:String,val room:String,val project:String?,val group:String?)

/** References logical originals, not whichever current fragment happens to be visible. */
sealed interface LogicalBaseReference {
    data class InputBase(val source: ReviewLocator, val contentFingerprint: String) : LogicalBaseReference
    data class StoredLogicalBase(
        val scopeToken: String, val semesterId: Long, val courseId: Long,
        val logicalBaseId: String, val structureFingerprint: String,
    ) : LogicalBaseReference {
        init { require(scopeToken.isNotBlank() && semesterId > 0 && courseId > 0 && logicalBaseId.isNotBlank() && structureFingerprint.isNotBlank()) }
    }
}

enum class ReviewedExceptionKind { CANCEL, RESCHEDULE, EXTRA }
data class ExceptionOriginalScope(
    val time: TimeRange? = null, val sections: Set<Int>? = null,
    val declaredWeeks: Set<Int> = emptySet(), val dayOfWeek: Int? = null,
    /** Exact original intervals resolved by C1, distinct from any target time. */
    val resolvedTime: ReviewedTimeSpec? = null,
)
sealed interface ExceptionAttachment {
    data class Bound(val base: LogicalBaseReference) : ExceptionAttachment
    /** Only EXTRA can take this explicitly reviewed path. C1 materializes one DATE archive;
     * its retained intent is lineage, never a second Add for C2. */
    data class AsNewCourse(val identity: ReviewedCourseIdentity) : ExceptionAttachment
}

data class ReviewedExceptionIntent internal constructor(
    val source: ReviewLocator, val kind: ReviewedExceptionKind, val attachment: ExceptionAttachment,
    val originalDate: LocalDate?, val targetDate: LocalDate?,
    val originalScope: ExceptionOriginalScope, val targetTime: ReviewedTimeSpec?,
    val teacher: String?, val room: String?, val experimentProject: String?, val group: String?,
    val provenance: List<ScheduleProvenance>,
    val retainedUnknownFields: Set<com.lingion.sleepy.importing.ImportField> = emptySet(),
)

data class ImportReviewCounts(val candidateCourses: Int, val selectedSourceMeetings: Int, val confirmedArchives: Int, val selectedExceptionIntents: Int)

/** Constructor is owned by the review kernel. This is reviewed INPUT to C2, not an apply permit. */
class ReviewedImport internal constructor(
    val source: FrozenImportSource, val context: ImportReviewContext, val binding: ReviewBinding,
    val courses: List<ReviewedCourse>, val exceptions: List<ReviewedExceptionIntent>,
    val sourceMappings: Map<ReviewLocator, ReviewedArchivePosition>, val decisions: ImportReviewDecisions,
    val counts: ImportReviewCounts,
    /** Canonical reviewed input without transport/session IDs. C2 still computes actual effects. */
    val semanticFingerprint: String, val reviewDigest: String,
    /** C2 cannot expand recurring dates without a confirmed calendar. */
    val requiresCalendarForOccurrencePlanning: Boolean,
)

/** C2 fills the actual plan/digest. There is deliberately no caller-controlled canApply flag. */
class ImportPlanPreview(
    val target: ImportTarget, val reviewDigest: String, val planDigest: String,
    val semanticFingerprint: String, val counts: ImportReviewCounts,
    conflictFingerprints: Set<String>,
) {
    val conflictFingerprints: Set<String> = fSet(conflictFingerprints)
    init { require(reviewDigest.isNotBlank() && planDigest.isNotBlank() && semanticFingerprint.isNotBlank()); require(conflictFingerprints.none { it.isBlank() }) }
}
class ImportApplyCommand(
    val target: ImportTarget, val mode: ImportMode, val reviewed: ReviewedImport,
    val expectedPlanDigest: String, conflictDecisions: List<ConflictReviewDecision>,
) {
    val conflictDecisions: List<ConflictReviewDecision> = fList(conflictDecisions)
    init { require(target == reviewed.context.target && mode == reviewed.context.mode); require(expectedPlanDigest.isNotBlank()); require(conflictDecisions.all { it.binding == reviewed.binding }) }
}
