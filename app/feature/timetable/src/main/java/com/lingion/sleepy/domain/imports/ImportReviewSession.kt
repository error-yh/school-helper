/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import java.time.LocalDate

/** Owns immutable request values. Methods record typed, content-bound review decisions.
 * Their existence proves consistency only; the eventual UI owns the human interaction. */
class ImportReviewSession private constructor(
    val source: FrozenImportSource, val context: ImportReviewContext,
    val selectedMeetings: Set<ReviewLocator>, val selectedPending: Set<Int>,
    val selectedExceptions: Set<ReviewLocator>,
) {
    val binding = ReviewBinding(source.sessionId,source.contentDigest,ImportCanonical.digest(listOf(context,selectedMeetings,selectedPending,selectedExceptions)))
    val requirements: List<ReviewRequirement> = fList(deriveRequirements())

    fun reviewEvidence(fingerprint:String): SourceEvidenceReview {
        require(requirements.any { it.kind == ReviewRequirementKind.EVIDENCE && it.fingerprint == fingerprint }) { "Unknown source evidence requirement" }
        return SourceEvidenceReview(binding,fingerprint)
    }
    fun reviewTime(locator:ReviewLocator, choice:TimeResolutionChoice):TimeResolutionReview {
        require(requirements.any { it.kind == ReviewRequirementKind.TIME && it.source == locator }) { "No time review at this source" }
        return TimeResolutionReview(binding,locator,choice)
    }
    fun reviewIdentity(kind:IdentityReviewKind, archives:List<IdentityArchive>):IdentityReviewDecision {
        require(source.ocr != null && selectedMeetings.isNotEmpty())
        require(archives.isNotEmpty() && archives.all { it.members.isNotEmpty() })
        val all = archives.flatMap { it.members }
        require(all.size == all.toSet().size && all.toSet() == selectedMeetings) { "Identity groups must partition exact selected members" }
        when (kind) {
            IdentityReviewKind.MERGE -> require(archives.size == 1)
            IdentityReviewKind.INDEPENDENT -> require(archives.all { a -> a.members.map { it.courseIndex }.toSet().size == 1 } && archives.map { it.members.first().courseIndex }.toSet().size == archives.size)
            IdentityReviewKind.PARTITION -> Unit
            IdentityReviewKind.BIND_EXISTING -> require(archives.any { it.existingArchive != null })
        }
        archives.mapNotNull { it.existingArchive }.forEach(::validateStoredBase)
        return IdentityReviewDecision(binding,kind,fList(archives.map { it.copy(members = fSet(it.members)) }))
    }
    fun keepUnscheduled(locator:ReviewLocator):KeepUnscheduledReview {
        require(locator in selectedMeetings && meeting(locator).kind == MeetingKind.UNSCHEDULED)
        return KeepUnscheduledReview(binding,locator,ImportCanonical.digest(meeting(locator)))
    }
    fun retainUnknownOptionalMetadata(locator:ReviewLocator, fields:Set<ImportField>):RetainUnknownOptionalMetadataReview {
        require(isSelected(locator) && fields.isNotEmpty() && fields == unknownOptionalFields(locator)) { "Review must name exactly the current unknown optional fields" }
        return RetainUnknownOptionalMetadataReview(binding,locator,fSet(fields),ImportCanonical.digest(sourceContent(locator)))
    }
    internal fun isSelected(p:ReviewLocator):Boolean = when {
        p.pendingIndex != null -> p.pendingIndex in selectedPending
        p.exceptionIndex != null -> p in selectedExceptions
        p.meetingIndex != null -> p in selectedMeetings
        p.courseIndex != null -> selectedMeetings.any { it.courseIndex == p.courseIndex }
        else -> false
    }
    internal fun sourceContent(p:ReviewLocator):Any = when {
        p.pendingIndex != null -> source.format.pendingExceptions[p.pendingIndex]
        p.exceptionIndex != null -> exception(p)
        p.meetingIndex != null -> meeting(p)
        else -> source.format.draft!!.courses[p.courseIndex!!]
    }
    internal fun missingFields(p:ReviewLocator):Set<ImportField> = when {
        p.pendingIndex != null || p.exceptionIndex != null -> exception(p).missingFields
        p.meetingIndex != null -> meeting(p).missingFields
        else -> source.format.draft!!.courses[p.courseIndex!!].missingFields
    }
    internal fun metadataValue(p:ReviewLocator, field:ImportField):String? {
        val values = when {
            p.pendingIndex != null || p.exceptionIndex != null -> exception(p).let { listOf(it.teacher,it.room,it.group,it.experimentProject) }
            p.meetingIndex != null -> meeting(p).let { listOf(it.teacher,it.room,it.group,it.experimentProject) }
            else -> listOf(null,null,null,null)
        }
        return when(field) { ImportField.TEACHER -> values[0];ImportField.ROOM -> values[1];ImportField.GROUP -> values[2];ImportField.EXPERIMENT_PROJECT -> values[3];else -> null }
    }
    internal fun unknownOptionalFields(p:ReviewLocator):Set<ImportField> = fSet(missingFields(p).filter { field ->
        field in optionalMetadataFields && metadataValue(p,field) == null && !(p.pendingIndex == null && p.exceptionIndex == null && p.courseIndex in context.courseIdentities && field in setOf(ImportField.TEACHER,ImportField.ROOM))
    })
    fun inputBase(locator:ReviewLocator):LogicalBaseReference.InputBase {
        require(locator in selectedMeetings)
        return LogicalBaseReference.InputBase(locator,ImportCanonical.digest(meeting(locator)))
    }
    fun attachException(locator:ReviewLocator, attachment:ExceptionAttachment, targetTime:ReviewedTimeSpec? = null):ExceptionAttachmentReview {
        require(locator in selectedExceptions || locator.pendingIndex in selectedPending)
        val e = exception(locator)
        when (attachment) {
            is ExceptionAttachment.Bound -> validateBase(attachment.base)
            is ExceptionAttachment.AsNewCourse -> require(e.kind == RichExceptionKind.EXTRA && attachment.identity.name.isNotBlank()) { "Only EXTRA can become a new course" }
        }
        val pending = locator.pendingIndex?.let { source.format.pendingExceptions[it] }
        val scope = ExceptionOriginalScope(
            time = if(e.kind == RichExceptionKind.CANCEL) e.customTime?.let { com.lingion.sleepy.domain.model.TimeRange(it.start,it.end) } else null,
            sections = if(e.kind == RichExceptionKind.CANCEL) e.sections?.let(::fSet) else null, declaredWeeks = fSet(pending?.declaredWeeks ?: emptySet()), dayOfWeek = pending?.dayOfWeek,
        )
        require(targetTime != ReviewedTimeSpec.Unscheduled) { "Exception target cannot be unscheduled" }
        require(e.kind != RichExceptionKind.CANCEL || targetTime == null) { "Cancellation time is original scope, not target time" }
        return ExceptionAttachmentReview(binding,locator,attachment,scope,targetTime?.let(::freezeTime))
    }
    fun resolveRelative(index:Int, basis:RelativeDateBasis, rule:RelativeDateRule, resolvedDate:LocalDate):RelativeDateReview {
        require(requirements.any { it.kind == ReviewRequirementKind.RELATIVE_DATE && it.source.relativeIndex == index })
        val proposal = source.format.relativeDates[index]
        require(proposal.unresolvedReason == null && proposal.candidate != null) { "REPARSE_REQUIRED: relative proposal has no exact resolved source context" }
        val computed = when(rule) {
            is RelativeDateRule.DayOffset -> {
                require(proposal.ruleId == "REFERENCE_DAY_OFFSET" && basis is RelativeDateBasis.ReferenceDate && basis.date == proposal.referenceDate && basis.date == proposal.basisDate) { "REPARSE_REQUIRED: reference date changed" }
                val expected = when(proposal.originalToken) { "今日","今天" -> 0; "明日","明天" -> 1; "后天" -> 2; else -> error("Unsupported relative token") }
                require(rule.days == expected)
                basis.date.plusDays(rule.days.toLong())
            }
            is RelativeDateRule.IsoWeekDay -> {
                require(proposal.ruleId == "REFERENCE_ISO_WEEK_DAY" && basis is RelativeDateBasis.ReferenceDate && basis.date == proposal.referenceDate && basis.date == proposal.basisDate) { "REPARSE_REQUIRED: reference date changed" }
                val token = proposal.originalToken
                require(token.length == 3 && token.startsWith(if(rule.nextWeek) "下周" else "本周"))
                val day = "一二三四五六日".indexOf(if(token.last() == '天') '日' else token.last()) + 1
                require(day == rule.day)
                basis.date.minusDays((basis.date.dayOfWeek.value-1).toLong()).plusWeeks(if(rule.nextWeek) 1 else 0).plusDays((rule.day-1).toLong())
            }
            is RelativeDateRule.MonthDay -> {
                require(proposal.ruleId in setOf("EXPLICIT_YEAR_MONTH_DAY","CONFIRMED_TERM_NEXT_MONTH_DAY"))
                val monthDay = Regex("([0-9]{1,2})月([0-9]{1,2})日").matchEntire(proposal.originalToken)
                require(monthDay != null && monthDay.groupValues[1].toInt() == rule.month && monthDay.groupValues[2].toInt() == rule.day)
                when(basis) {
                    is RelativeDateBasis.ExplicitYear -> { require(proposal.ruleId == "EXPLICIT_YEAR_MONTH_DAY" && proposal.confirmedYear == basis.year) { "REPARSE_REQUIRED: explicit year changed" }; LocalDate.of(basis.year,rule.month,rule.day) }
                    is RelativeDateBasis.TermMonday -> { require(proposal.ruleId == "CONFIRMED_TERM_NEXT_MONTH_DAY" && proposal.confirmedTermMonday == basis.date) { "REPARSE_REQUIRED: term basis changed" }; LocalDate.of(basis.date.year,rule.month,rule.day).let { if(it < basis.date) it.plusYears(1) else it } }
                    else -> error("Month/day requires an explicit year or term")
                }
            }
            is RelativeDateRule.TermWeekDay -> {
                require(proposal.ruleId == "CONFIRMED_TERM_WEEK_DAY" && basis is RelativeDateBasis.TermMonday && proposal.confirmedTermMonday == basis.date && proposal.basisDate == basis.date) { "REPARSE_REQUIRED: term basis changed" }
                val dayMatch = Regex("(?:星期|周)([一二三四五六日天])$").find(proposal.originalToken)
                require(dayMatch != null)
                val sourceDay = "一二三四五六日".indexOf(if(dayMatch.groupValues[1] == "天") "日" else dayMatch.groupValues[1]) + 1
                val sourceWeeks = WeekExpressionParser.parse(proposal.originalToken.substring(0,dayMatch.range.first),context.maxWeek)
                require(sourceWeeks.errors.isEmpty() && sourceWeeks.weeks == setOf(rule.week) && sourceDay == rule.day)
                require(rule.week <= context.maxWeek)
                basis.date.plusWeeks((rule.week-1).toLong()).plusDays((rule.day-1).toLong())
            }
        }
        require(computed == resolvedDate && proposal.candidate == resolvedDate && computed.year in 1..9999) { "Resolution must match the explicit basis and rule" }
        return RelativeDateReview(binding,index,basis,rule,resolvedDate)
    }
    fun reviewConflict(fingerprint:String,choice:ConflictReviewChoice):ConflictReviewDecision {
        require(fingerprint.isNotBlank())
        return ConflictReviewDecision(binding,fingerprint,choice)
    }
    /** A single whole-import answer; it never becomes per-meeting approval or a repaired source. */
    fun reviewSourceRelation(choice:SourceRelationChoice):SourceRelationReview = SourceRelationReview(binding,choice)
    internal fun meeting(p:ReviewLocator):RichMeetingDraft = source.format.draft!!.courses[p.courseIndex!!].meetings[p.meetingIndex!!]
    internal fun exception(p:ReviewLocator):RichExceptionDraft = p.pendingIndex?.let { source.format.pendingExceptions[it].exception } ?: meeting(p).exceptions[p.exceptionIndex!!]
    internal fun validateBase(base:LogicalBaseReference) {
        when(base) {
            is LogicalBaseReference.InputBase -> require(base.source in selectedMeetings && base.contentFingerprint == ImportCanonical.digest(meeting(base.source))) { "Input base membership/content changed" }
            is LogicalBaseReference.StoredLogicalBase -> validateStoredBase(base)
        }
    }
    private fun validateStoredBase(base:LogicalBaseReference.StoredLogicalBase) {
        require(base.scopeToken == context.target.scopeToken && base.semesterId == context.target.semesterId) { "Stored base is outside target" }
        require(context.mode != ImportMode.REPLACE) { "REPLACE requires an explicitly selected input base" }
    }
    internal fun relativeRelevant(p:RelativeDateProposal):Boolean {
        val path = p.source.path
        if(path.courseIndex != null) return selectedMeetings.any { it.courseIndex == path.courseIndex && (path.meetingIndex == null || it.meetingIndex == path.meetingIndex) }
        val pending = source.format.pendingExceptions.withIndex().filter { (_,e) -> e.sources.any { it.sourceId == p.source.sourceId && it.sourceLocator == p.source.sourceLocator } }
        if(pending.isNotEmpty()) return pending.any { it.index in selectedPending }
        return selectedMeetings.isNotEmpty() || selectedPending.isNotEmpty()
    }
    private fun deriveRequirements():List<ReviewRequirement> {
        if(selectedMeetings.isEmpty() && selectedPending.isEmpty()) return emptyList()
        val out = mutableListOf<ReviewRequirement>()
        fun need(kind:ReviewRequirementKind,code:String,p:ReviewLocator,payload:Any?) {
            out += ReviewRequirement(ImportCanonical.digest(listOf(binding,kind,code,p,payload)),kind,code,p)
        }
        fun issues(values:List<ParseIssue>,p:ReviewLocator) { values.forEachIndexed { i,e -> need(ReviewRequirementKind.EVIDENCE,e.code,p,listOf(i,e)) } }
        val d = source.format.draft
        sameLessonSourceGroups().forEach { group ->
            val code=if(group.map {meeting(it).room}.distinct().size>1) "SAME_LESSON_MULTIPLE_ROOMS_REVIEW_REQUIRED" else "SAME_LESSON_SOURCE_FRAGMENTS_REVIEW_REQUIRED"
            need(ReviewRequirementKind.EVIDENCE,code,group.first(),group.map {listOf(it,meeting(it))})
        }
        source.corrections.filter { isSelected(it.source) }.forEach { need(ReviewRequirementKind.EVIDENCE,"USER_CORRECTION_REVIEW_REQUIRED",it.source,it) }
        (source.format.sources + source.format.pendingExceptions.filterIndexed { i,_ -> i in selectedPending }.flatMap { it.sources }).filter { ref ->
            ref.formula != null && (ref.path.courseIndex == null || selectedMeetings.any { it.courseIndex == ref.path.courseIndex && (ref.path.meetingIndex == null || it.meetingIndex == ref.path.meetingIndex) })
        }.forEach { need(ReviewRequirementKind.EVIDENCE,"FORMULA_UNVERIFIED_SOURCE",ReviewLocator(courseIndex = it.path.courseIndex,meetingIndex = it.path.meetingIndex,exceptionIndex = it.path.exceptionIndex),it) }
        fun optionalMetadata(p:ReviewLocator) {
            val unknown = unknownOptionalFields(p)
            if(unknown.isNotEmpty()) need(ReviewRequirementKind.RETAIN_UNKNOWN_OPTIONAL_METADATA,"RETAIN_UNKNOWN_OPTIONAL_METADATA",p,listOf(unknown,sourceContent(p)))
            val markedKnown = missingFields(p).filter { it in optionalMetadataFields && metadataValue(p,it) != null }
            if(markedKnown.isNotEmpty()) need(ReviewRequirementKind.EVIDENCE,"OPTIONAL_METADATA_MARKER_WITH_VALUE",p,listOf(markedKnown,sourceContent(p)))
        }
        fun exceptionTime(p:ReviewLocator) {
            val e = exception(p)
            if(e.customTime == null && !e.sections.isNullOrEmpty() && d!!.periodProposals.any { it.section in e.sections }) need(ReviewRequirementKind.TIME,"SOURCE_EXCEPTION_PERIOD_PROPOSAL",p,listOf(e.sections,d.periodProposals.filter { it.section in e.sections },context.targetPeriods))
        }
        issues(source.format.issues,ReviewLocator())
        issues(d?.issues ?: emptyList(),ReviewLocator())
        d?.unparsed?.forEachIndexed { i,e -> need(ReviewRequirementKind.EVIDENCE,"UNPARSED_PDF",ReviewLocator(),listOf(i,e)) }
        source.format.unparsedSources.forEachIndexed { i,e -> need(ReviewRequirementKind.EVIDENCE,"UNPARSED_SOURCE",ReviewLocator(),listOf(i,e)) }
        selectedMeetings.map { it.courseIndex!! }.toSet().forEach { c -> val p = ReviewLocator(courseIndex = c); issues(d!!.courses[c].warnings,p); optionalMetadata(p) }
        selectedMeetings.forEach { p ->
            val m = meeting(p)
            issues(m.warnings,p)
            optionalMetadata(p)
            if(m.kind == MeetingKind.UNSCHEDULED) need(ReviewRequirementKind.KEEP_UNSCHEDULED,"KEEP_UNSCHEDULED",p,m)
            if(m.customTime == null && m.sections.isNotEmpty() && (ImportField.TIME in m.missingFields || d!!.periodProposals.any { it.section in m.sections })) need(ReviewRequirementKind.TIME,"SOURCE_PERIOD_PROPOSAL",p,listOf(m.sections,d!!.periodProposals.filter { it.section in m.sections },context.targetPeriods))
        }
        selectedExceptions.forEach { p -> issues(exception(p).warnings,p); optionalMetadata(p); exceptionTime(p); need(ReviewRequirementKind.EXCEPTION_ATTACHMENT,"EXCEPTION_ATTACHMENT",p,exception(p)) }
        selectedPending.forEach { index ->
            val p = ReviewLocator(pendingIndex = index)
            issues(exception(p).warnings,p)
            optionalMetadata(p)
            exceptionTime(p)
            need(ReviewRequirementKind.EXCEPTION_ATTACHMENT,"PENDING_EXCEPTION_ATTACHMENT",p,source.format.pendingExceptions[index])
        }
        source.format.relativeDates.forEachIndexed { i,p -> if(relativeRelevant(p)) need(ReviewRequirementKind.RELATIVE_DATE,"RELATIVE_DATE_BASIS",ReviewLocator(relativeIndex = i),p) }
        source.ocr?.let { o ->
            o.document.pages.forEach { page -> need(ReviewRequirementKind.EVIDENCE,"OCR_PAGE_ALL_VARIANTS",ReviewLocator(),page) }
            o.regions.filter { it.issues.isNotEmpty() || it.alternatives.isNotEmpty() }.forEach { need(ReviewRequirementKind.EVIDENCE,"OCR_REGION",ReviewLocator(),it) }
            if(o.candidateCourses.any { c ->
                c.identityEvidence != com.lingion.sleepy.importing.ocr.OcrCourseIdentityEvidence.EXPLICIT_CODES && selectedMeetings.any { p ->
                    p.courseIndex == c.path.courseIndex && (c.path.meetingIndex == null || p.meetingIndex!! in c.path.meetingIndex until c.path.meetingIndex + c.course.meetings.size)
                }
            }) need(ReviewRequirementKind.IDENTITY,"OCR_ARCHIVE_IDENTITY",ReviewLocator(),listOf(o.candidateCourses,o.identityProposals,selectedMeetings))
        }
        return out.distinctBy { it.fingerprint }
    }
    companion object {
        fun create(source:FrozenImportSource,context:ImportReviewContext,selectedMeetings:Set<ReviewLocator>? = null,selectedPending:Set<Int>? = null,selectedExceptions:Set<ReviewLocator>? = null):ImportReviewSession {
            val d = source.format.draft

            val all = d?.courses?.flatMapIndexed { c,course -> course.meetings.indices.map { m -> ReviewLocator(courseIndex = c,meetingIndex = m) } }?.toSet() ?: emptySet()
            val meetings = selectedMeetings ?: all
            require(meetings.all { it in all }) { "Unknown selected meeting" }
            val pending = selectedPending ?: source.format.pendingExceptions.indices.toSet()
            require(pending.all { it in source.format.pendingExceptions.indices })
            val allExceptions = meetings.flatMap { p -> d!!.courses[p.courseIndex!!].meetings[p.meetingIndex!!].exceptions.indices.map { e -> p.copy(exceptionIndex = e) } }.toSet()
            val exceptions = selectedExceptions ?: allExceptions
            require(exceptions.all { it in allExceptions })
            return ImportReviewSession(source,context.copy(targetPeriods = fList(context.targetPeriods),courseIdentities = fMap(context.courseIdentities)),fSet(meetings),fSet(pending),fSet(exceptions))
        }
    }
}

internal val optionalMetadataFields = setOf(ImportField.TEACHER,ImportField.ROOM,ImportField.GROUP,ImportField.EXPERIMENT_PROJECT)

internal fun freezeTime(time:ReviewedTimeSpec):ReviewedTimeSpec {
    fun checkIntervals(sections:Set<Int>, intervals:List<SourcePeriodInterval>) {
        require(sections.isNotEmpty() && sections.all { it in 1..1000 })
        require(intervals.size == sections.size && intervals.map { it.section }.toSet() == sections)
        require(intervals.sortedBy { it.range.start }.zipWithNext().none { (a,b) -> a.range.end > b.range.start })
    }
    return when(time) {
        is ReviewedTimeSpec.Continuous -> time
        is ReviewedTimeSpec.TargetSections -> {
            checkIntervals(time.sections,time.intervals)
            time.copy(sections = fSet(time.sections),intervals = fList(time.intervals.map { it.copy(provenance = fList(it.provenance)) }))
        }
        is ReviewedTimeSpec.SourcePeriods -> {
            checkIntervals(time.sections,time.intervals)
            require(time.proposals.map { it.section }.toSet() == time.sections)
            require(time.intervals.all { interval -> time.proposals.filter { it.section == interval.section }.all { it.time.start == interval.range.start && it.time.end == interval.range.end } })
            time.copy(sections = fSet(time.sections),intervals = fList(time.intervals.map { it.copy(provenance = fList(it.provenance)) }),proposals = fList(time.proposals.map { it.copy(sources = fList(it.sources)) }))
        }
        ReviewedTimeSpec.Unscheduled -> time
    }
}
