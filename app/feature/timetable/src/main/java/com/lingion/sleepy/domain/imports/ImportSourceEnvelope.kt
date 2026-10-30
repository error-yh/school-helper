/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import com.lingion.sleepy.importing.ocr.*
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.util.Collections
import java.util.UUID

/** A parse-session snapshot, not evidence of human consent or persistence permission.
 * Only factories accept parser DTOs. OCR must enter through the OUTER result. */
class FrozenImportSource private constructor(
    val format: FormatParseResult,
    val ocr: OcrLayoutParseResult?,
    val sessionId: String,
    val originalSource: FrozenImportSource? = null,
    val corrections: List<ImportSourceCorrection> = emptyList(),
) {
    val contentDigest: String = ImportCanonical.digest(if(originalSource==null) listOf(format,ocr) else listOf(format,ocr,originalSource.contentDigest,corrections))
    /** Provider metadata, never OCR text or a parser/school hint. Existing locations stay intact. */
    fun withDocumentDisplayName(displayName:String):FrozenImportSource {
        require(displayName.isNotBlank() && displayName.length<=256 && displayName.none {it.isISOControl()}) { "来源文件显示名称无效。" }
        fun reference(path:DraftPath)=ImportSourceRef("document-metadata","document:display-name",displayName,path)
        fun List<ImportSourceRef>.withoutDocumentLabel()=filterNot {it.sourceId=="document-metadata" && it.sourceLocator=="document:display-name"}
        val names=buildList {
            format.draft?.courses?.forEachIndexed {c,course->
                add(reference(DraftPath(c)))
                course.meetings.forEachIndexed {m,meeting->
                    add(reference(DraftPath(c,m)))
                    meeting.exceptions.indices.forEach {e->add(reference(DraftPath(c,m,e)))}
                }
            }
        }
        val enriched=format.copy(sources=format.sources.withoutDocumentLabel()+names,
            pendingExceptions=format.pendingExceptions.map {it.copy(sources=it.sources.withoutDocumentLabel()+reference(DraftPath()))})
        val frozen=if(ocr==null) fromFormat(enriched) else fromOcr(ocr.copy(formatResult=enriched))
        return FrozenImportSource(frozen.format,frozen.ocr,frozen.sessionId,originalSource,corrections)
    }
    /** Typed editing keeps producer warnings and original evidence; ordinary acknowledgments cannot call this gate. */
    fun correctMeeting(locator:ReviewLocator,replacement:RichMeetingDraft,correctedFields:Set<ImportField>,maxWeek:Int):FrozenImportSource {
        require(corrections.size<10_000) { "修正次数超过容量限制。" }
        val edit=correctMeetingSource(format,locator,replacement,correctedFields,maxWeek)
        val frozen=if(ocr==null) fromFormat(edit.first) else fromOcr(ocr.copy(formatResult=edit.first))
        return FrozenImportSource(frozen.format,frozen.ocr,UUID.randomUUID().toString(),originalSource ?: this,fList(corrections+edit.second))
    }
    internal fun correctionCovers(locator:ReviewLocator,issue:ParseIssue):Boolean =
        corrections.any { it.source==locator && ImportCanonical.digest(issue) in it.issueDigests }
    companion object {
        fun fromFormat(result: FormatParseResult): FrozenImportSource {
            // Accidental projection protection, not authentication of arbitrary internal callers.
            // Trusted source loaders route OCR through fromOcr; producer DTOs remain unchanged.
            val draft = result.draft
            val issues = result.issues + (draft?.issues ?: emptyList()) + (draft?.courses?.flatMap { c -> c.warnings + c.meetings.flatMap { m -> m.warnings + m.exceptions.flatMap { it.warnings } } } ?: emptyList())
            require(issues.none { it.code.startsWith("OCR_") || it.code == "UNPARSED_OCR_SOURCE" || it.code == "ARCHIVE_IDENTITY_REVIEW_REQUIRED" } &&
                draft?.adapterIds.orEmpty().none { it in setOf("date-list","seven-day-grid","week-matrix") } &&
                (result.sources + result.unparsedSources + result.pendingExceptions.flatMap { it.sources }).none { it.sourceLocator.startsWith("ocr:") }) { "OCR layout results must enter through fromOcr with all outer evidence" }
            return FrozenImportSource(freezeFormat(result), null, UUID.randomUUID().toString())
        }
        fun fromPdf(result: RichTimetableDraft): FrozenImportSource = fromFormat(FormatParseResult(result))
        fun fromOcr(result: OcrLayoutParseResult): FrozenImportSource {
            val format = freezeFormat(result.formatResult)
            return FrozenImportSource(format, result.copy(
                formatResult = format,
                document = OcrLayoutDocument(fList(result.document.pages.map { page -> page.copy(
                    variants = fList(page.variants.map { v -> v.copy(page = v.page?.let(::freezePage), spans = fList(v.spans), issues = fSet(v.issues)) }),
                    lines = fList(page.lines), textLayerEvidence = page.textLayerEvidence?.let(::freezePage), nativeIssues = fSet(page.nativeIssues),
                ) })),
                layoutCandidates = fList(result.layoutCandidates.map { it.copy(evidence = fSet(it.evidence), anchorSpanIds = fList(it.anchorSpanIds)) }),
                regions = fList(result.regions.map { it.copy(spanIds = fList(it.spanIds), issues = fList(it.issues), alternatives = fList(it.alternatives)) }),
                coverage = fList(result.coverage.map { it.copy(regionIds = fSet(it.regionIds), paths = fSet(it.paths)) }),
                candidateCourses = fList(result.candidateCourses.map { it.copy(course = freezeCourse(it.course), regionIds = fList(it.regionIds), spanIds = fList(it.spanIds)) }),
                identityProposals = fList(result.identityProposals.map { it.copy(memberIds = fList(it.memberIds), spanIds = fList(it.spanIds)) }),
            ), UUID.randomUUID().toString())
        }
    }
}

internal fun <T> fList(values: Collection<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
internal fun <T> fSet(values: Collection<T>): Set<T> = Collections.unmodifiableSet(LinkedHashSet(values))
internal fun <K, V> fMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(LinkedHashMap(values))
private fun freezePage(page: PositionedPage) = page.copy(text = fList(page.text))
internal fun freezeException(e: RichExceptionDraft) = e.copy(sections = e.sections?.let(::fSet), sources = fList(e.sources), warnings = fList(e.warnings), missingFields = fSet(e.missingFields))
private fun freezeMeeting(m: RichMeetingDraft) = m.copy(weeks = fSet(m.weeks), sections = fSet(m.sections), dates = fSet(m.dates), sources = fList(m.sources), warnings = fList(m.warnings), missingFields = fSet(m.missingFields), exceptions = fList(m.exceptions.map(::freezeException)))
private fun freezeCourse(c: RichCourseDraft) = c.copy(meetings = fList(c.meetings.map(::freezeMeeting)), sources = fList(c.sources), warnings = fList(c.warnings), missingFields = fSet(c.missingFields))
private fun freezeFormat(r: FormatParseResult) = r.copy(
    draft = r.draft?.let { d -> d.copy(courses = fList(d.courses.map(::freezeCourse)), periodProposals = fList(d.periodProposals.map { it.copy(sources = fList(it.sources)) }), issues = fList(d.issues), unparsed = fList(d.unparsed), missingFields = fSet(d.missingFields), adapterIds = fSet(d.adapterIds)) },
    sources = fList(r.sources), issues = fList(r.issues), courseKeys = fMap(r.courseKeys), meetingKeys = fMap(r.meetingKeys),
    pendingExceptions = fList(r.pendingExceptions.map { it.copy(exception = freezeException(it.exception), sources = fList(it.sources), declaredWeeks = fSet(it.declaredWeeks), candidatePaths = fList(it.candidatePaths)) }),
    relativeDates = fList(r.relativeDates), unparsedSources = fList(r.unparsedSources),
)

/** Length-delimited canonical values: lists preserve position, sets/maps ignore insertion order.
 * Reflection is limited to our closed DTO packages; no parser, URI, database or clock is run.
 * This is a consistency digest, NOT a signature or proof that anyone read the evidence. */
internal object ImportCanonical {
    fun digest(value: Any?): String = MessageDigest.getInstance("SHA-256").digest(encode(value).toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2,'0') }
    private fun atom(tag: String, value: String) = "$tag${value.length}:$value"
    private fun encode(value: Any?): String = when (value) {
        null -> "n"
        is String -> atom("s", value)
        is Enum<*> -> atom("e", value.javaClass.name + ":" + value.name)
        is LocalDate -> atom("d", value.toString())
        is LocalTime -> atom("t", value.toString())
        is Number -> atom(value.javaClass.name, value.toString())
        is Boolean -> if (value) "b1" else "b0"
        is Set<*> -> atom("set", value.map(::encode).sorted().joinToString(""))
        is Collection<*> -> atom("list", value.joinToString("") { encode(it) })
        is Map<*, *> -> atom("map", value.entries.map { encode(it.key) + encode(it.value) }.sorted().joinToString(""))
        else -> {
            require(value.javaClass.name.startsWith("com.lingion.sleepy.")) { "Unsupported canonical value" }
            atom(value.javaClass.name, value.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.sortedBy { it.name }.joinToString("") { field ->
                field.isAccessible = true
                atom("field", field.name) + encode(field.get(value))
            })
        }
    }
}
