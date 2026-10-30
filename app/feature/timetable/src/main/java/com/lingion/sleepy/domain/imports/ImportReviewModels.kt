/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import java.time.LocalDate

/** Session identity and source/context/selection content are all bound; this is not a signature. */
data class ReviewBinding(val sourceSessionId: String, val sourceDigest: String, val requestDigest: String)
enum class ReviewRequirementKind { EVIDENCE, IDENTITY, TIME, RELATIVE_DATE, EXCEPTION_ATTACHMENT, KEEP_UNSCHEDULED, RETAIN_UNKNOWN_OPTIONAL_METADATA }
data class ReviewRequirement(val fingerprint: String, val kind: ReviewRequirementKind, val code: String, val source: ReviewLocator)
data class ImportReviewProblem(val code: String, val source: ReviewLocator = ReviewLocator())

class SourceEvidenceReview internal constructor(val binding: ReviewBinding, val requirementFingerprint: String)
enum class IdentityReviewKind { MERGE, PARTITION, INDEPENDENT, BIND_EXISTING }
data class IdentityArchive(
    val members: Set<ReviewLocator>, val identity: ReviewedCourseIdentity? = null,
    val existingArchive: LogicalBaseReference.StoredLogicalBase? = null,
)
class IdentityReviewDecision internal constructor(val binding: ReviewBinding, val kind: IdentityReviewKind, val archives: List<IdentityArchive>)
enum class TimeResolutionChoice { USE_TARGET_PERIODS, USE_SOURCE_PERIODS }
class TimeResolutionReview internal constructor(val binding: ReviewBinding, val source: ReviewLocator, val choice: TimeResolutionChoice)

sealed interface RelativeDateBasis {
    data class ReferenceDate(val date: LocalDate) : RelativeDateBasis
    data class TermMonday(val date: LocalDate) : RelativeDateBasis { init { require(date.dayOfWeek.value == 1) } }
    data class ExplicitYear(val year: Int) : RelativeDateBasis { init { require(year in 1..9999) } }
}
sealed interface RelativeDateRule {
    data class DayOffset(val days: Int) : RelativeDateRule
    data class IsoWeekDay(val nextWeek: Boolean, val day: Int) : RelativeDateRule { init { require(day in 1..7) } }
    data class MonthDay(val month: Int, val day: Int) : RelativeDateRule
    data class TermWeekDay(val week: Int, val day: Int) : RelativeDateRule { init { require(week in 1..1000 && day in 1..7) } }
}
class RelativeDateReview internal constructor(val binding: ReviewBinding, val proposalIndex: Int, val basis: RelativeDateBasis, val rule: RelativeDateRule, val resolvedDate: LocalDate)
class ExceptionAttachmentReview internal constructor(
    val binding: ReviewBinding, val source: ReviewLocator, val attachment: ExceptionAttachment,
    val originalScope: ExceptionOriginalScope, val targetTime: ReviewedTimeSpec?,
)
class RetainUnknownOptionalMetadataReview internal constructor(val binding: ReviewBinding, val source: ReviewLocator, val fields: Set<com.lingion.sleepy.importing.ImportField>, val contentFingerprint: String)

class KeepUnscheduledReview internal constructor(val binding: ReviewBinding, val source: ReviewLocator, val contentFingerprint: String)

enum class ConflictReviewChoice { KEEP, SKIP }
class ConflictReviewDecision internal constructor(val binding: ReviewBinding, val conflictFingerprint: String, val choice: ConflictReviewChoice)

/** One answer for the whole import. Position collisions cannot name a file, so the relationship
 * to an already stored source is never inferred: it is stated once by the reviewer. */
enum class SourceRelationChoice { DIFFERENT_SOURCE }
class SourceRelationReview internal constructor(val binding: ReviewBinding, val choice: SourceRelationChoice)

data class ImportReviewDecisions(
    val evidence: List<SourceEvidenceReview> = emptyList(), val identity: IdentityReviewDecision? = null,
    val times: List<TimeResolutionReview> = emptyList(), val relativeDates: List<RelativeDateReview> = emptyList(),
    val attachments: List<ExceptionAttachmentReview> = emptyList(), val conflicts: List<ConflictReviewDecision> = emptyList(),
    val unscheduled: List<KeepUnscheduledReview> = emptyList(),
    val optionalMetadata: List<RetainUnknownOptionalMetadataReview> = emptyList(),
    val sourceRelation: SourceRelationReview? = null,
)
sealed interface ImportReviewResult {
    data class Blocked(val problems: List<ImportReviewProblem>, val requirements: List<ReviewRequirement>) : ImportReviewResult
    data class Ready(val reviewed: ReviewedImport) : ImportReviewResult
}
