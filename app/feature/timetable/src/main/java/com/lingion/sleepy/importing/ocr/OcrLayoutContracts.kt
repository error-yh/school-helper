/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import java.time.LocalDate

/** Input spans and line bounds are ALREADY inverse-mapped into this canonical frame.
 * rasterToPage is retained as evidence only. This layer must never apply it again. */
enum class OcrLayoutFrame { CANONICAL_TOP_LEFT }
data class OcrLayoutPage(
    val pageNumber:Int, val width:Float, val height:Float,
    val variants:List<OcrVariantEvidence>, val recommendedVariantId:OcrVariantId?,
    val lines:List<TableLineEvidence> = emptyList(),
    val textLayerEvidence:PositionedPage? = null,
    val frame:OcrLayoutFrame = OcrLayoutFrame.CANONICAL_TOP_LEFT,
    /** Copy OcrPageResult.issues here: coordinator-wide flags are not written into
     * each OcrVariantEvidence.issues. These remain review signals, never authorization. */
    val nativeIssues:Set<OcrIssue> = emptySet(),
)
data class OcrLayoutDocument(val pages:List<OcrLayoutPage>)
data class OcrLayoutLimits(
    val maxPages:Int=30, val maxTextUnits:Int=500_000, val maxRawSpans:Int=100_000,
    val maxRegions:Int=10_000, val maxComparisons:Long=1_000_000,
    val maxCandidates:Int=2_000,
) { init {
    require(maxPages in 1..30 && maxTextUnits in 1..500_000 && maxRawSpans in 1..100_000)
    require(maxRegions in 1..10_000 && maxComparisons in 1..1_000_000 && maxCandidates in 1..2_000)
} }
data class OcrLayoutContext(
    val format:FormatParseContext, val limits:OcrLayoutLimits=OcrLayoutLimits(),
    val confirmedTermMonday:LocalDate?=null, val confirmedYear:Int?=null, val referenceDate:LocalDate?=null,
) { init { require(confirmedYear==null || confirmedYear in 1..9999);require(confirmedTermMonday==null || confirmedTermMonday.dayOfWeek.value==1) } }

data class OcrSpanId(val pageNumber:Int,val variantId:OcrVariantId,val index:Int)
enum class SpanCoverageStatus { PARSED, STRUCTURAL_ANCHOR, ANNOTATION, UNPARSED, ALTERNATE_ONLY }
data class OcrSpanCoverage(
    val id:OcrSpanId, val span:OcrSpan, val status:SpanCoverageStatus,
    val regionIds:Set<String> = emptySet(), val paths:Set<DraftPath> = emptySet(),
)
enum class OcrRegionKind { RECORD, STRUCTURAL_ANCHOR, ANNOTATION, UNPARSED, EMPTY_BODY }
data class RegionEvidence(
    val id:String, val pageNumber:Int, val variantId:OcrVariantId, val bounds:TextBox?,
    val spanIds:List<OcrSpanId>, val kind:OcrRegionKind,
    val issues:List<ParseIssue> = emptyList(), val alternatives:List<String> = emptyList(),
)
data class OcrLayoutCandidate(val adapterId:String,val pageNumber:Int,val evidence:Set<String>,val anchorSpanIds:List<OcrSpanId>)
data class CandidateCourseId(val value:String)
enum class OcrCourseIdentityEvidence { NONE, EXPLICIT_CODES, TITLE_CONTAINER, UNCONFIRMED_SUFFIX }
data class OcrCandidateCourse(
    val id:CandidateCourseId,val course:RichCourseDraft,val regionIds:List<String>,val spanIds:List<OcrSpanId>,val path:DraftPath,
    val completeTitle:String=course.name,val identityEvidence:OcrCourseIdentityEvidence=OcrCourseIdentityEvidence.NONE,
)
enum class ArchiveIdentityEvidence { SAME_TITLE }
/** A proposal is never user authorization. No constructor/copy parameter can turn this off. */
data class ArchiveIdentityProposal(
    val memberIds:List<CandidateCourseId>, val evidence:ArchiveIdentityEvidence,
    val spanIds:List<OcrSpanId>,
) { val requiresConfirmation:Boolean get()=true }
data class OcrLayoutCounts(val courseRecordCandidates:Int=0,val sameTitleProposalGroups:Int=0,val meetings:Int=0,val confirmedArchives:Int=0)
data class OcrLayoutParseResult(
    val formatResult:FormatParseResult, val document:OcrLayoutDocument,
    val layoutCandidates:List<OcrLayoutCandidate> = emptyList(),
    val regions:List<RegionEvidence> = emptyList(), val coverage:List<OcrSpanCoverage> = emptyList(),
    val candidateCourses:List<OcrCandidateCourse> = emptyList(),
    val identityProposals:List<ArchiveIdentityProposal> = emptyList(),
) { val counts:OcrLayoutCounts get()=OcrLayoutCounts(candidateCourses.size,identityProposals.size,formatResult.draft?.courses?.sumOf { it.meetings.size } ?: 0,0) }
