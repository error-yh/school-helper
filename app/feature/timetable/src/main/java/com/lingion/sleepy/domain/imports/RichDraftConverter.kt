/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.ScheduleProvenance
import com.lingion.sleepy.domain.model.SourceBounds
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*

/** Pure C1 boundary. Does not expand calendar occurrences, normalize effects or write domain rows. */
object RichDraftConverter {
    fun convert(session:ImportReviewSession, decisions:ImportReviewDecisions = ImportReviewDecisions()):ImportReviewResult = try {
        Conversion(session,freezeDecisions(decisions)).run()
    } catch(_:IllegalArgumentException) {
        ImportReviewResult.Blocked(fList(listOf(ImportReviewProblem("INVALID_SOURCE_OR_REVIEW_VALUE"))),session.requirements)
    }

    private class Conversion(val s:ImportReviewSession,val decisions:ImportReviewDecisions) {
        val problems = mutableListOf<ImportReviewProblem>()
        val courses = mutableListOf<ReviewedCourse>()
        val intents = mutableListOf<ReviewedExceptionIntent>()
        val mappings = linkedMapOf<ReviewLocator,ReviewedArchivePosition>()
        fun problem(code:String,p:ReviewLocator = ReviewLocator()) { problems += ImportReviewProblem(code,p) }
        fun run():ImportReviewResult {
            val draft = s.source.format.draft
            if(draft == null) problem("SOURCE_STRUCTURALLY_REJECTED")
            if(s.selectedMeetings.isEmpty() && s.selectedPending.isEmpty()) problem("NOTHING_SELECTED")
            validateReviews()
            validateMalformedSourceFields()
            if(draft != null) {
                draft.missingFields.filterNot { it in setOf(ImportField.SEMESTER_ID,ImportField.SEMESTER_START_DATE) }.forEach { problem("SOURCE_MISSING_${it.name}") }
                val groups = decisions.identity?.archives ?: s.selectedMeetings.groupBy { it.courseIndex!! }.values.map { IdentityArchive(it.toSet()) }
                for(group in groups) convertArchive(group)
                (s.selectedExceptions + s.selectedPending.map { ReviewLocator(pendingIndex = it) }).sortedWith(locatorOrder).forEach(::convertException)
            }
            if(problems.isNotEmpty()) return ImportReviewResult.Blocked(fList(problems.distinct()),s.requirements)
            val frozenCourses = fList(courses)
            val frozenIntents = fList(intents)
            val counts = ImportReviewCounts(s.source.ocr?.candidateCourses?.size ?: draft!!.courses.size,s.selectedMeetings.size,courses.size,intents.size)
            val semantic = ImportCanonical.digest(listOf(courses.map(::semanticCourse).map(ImportCanonical::digest).sorted(),intents.map { semanticIntent(it) }.map(ImportCanonical::digest).sorted()))
            val reviewDigest = ImportCanonical.digest(listOf(s.binding,s.context,frozenCourses,frozenIntents,mappings,decisions,counts))
            return ImportReviewResult.Ready(ReviewedImport(s.source,s.context,s.binding,frozenCourses,frozenIntents,fMap(mappings),decisions,counts,semantic,reviewDigest,s.context.termMonday == null && courses.any { c -> c.meetings.any { it.kind == MeetingKind.RECURRING } }))
        }
        /** A rejected declaration is not an absent field. Evidence review cannot correct it.
         * A future trusted edit/reparse must retain the original evidence and create new input
         * content/session; this gate never mutates the frozen producer result. */
        fun validateMalformedSourceFields() {
            if(s.selectedMeetings.isEmpty() && s.selectedPending.isEmpty()) return
            data class Owner(val source:ReviewLocator,val issues:List<ParseIssue>,val excerpts:List<SourceExcerpt>)
            val owners = buildList {
                s.source.format.draft?.courses?.forEachIndexed { c,course ->
                    add(Owner(ReviewLocator(courseIndex = c),course.warnings,course.sources))
                    course.meetings.forEachIndexed { m,meeting ->
                        add(Owner(ReviewLocator(courseIndex = c,meetingIndex = m),meeting.warnings,meeting.sources))
                        meeting.exceptions.forEachIndexed { e,exception ->
                            add(Owner(ReviewLocator(courseIndex = c,meetingIndex = m,exceptionIndex = e),exception.warnings,exception.sources))
                        }
                    }
                }
                s.source.format.pendingExceptions.forEachIndexed { i,pending ->
                    add(Owner(ReviewLocator(pendingIndex = i),pending.exception.warnings,pending.exception.sources))
                }
            }
            fun reject(issue:ParseIssue,p:ReviewLocator) = problem("SOURCE_FIELD_CORRECTION_OR_REPARSE_REQUIRED_${issue.code}",p)
            owners.filter { s.isSelected(it.source) }.forEach { owner ->
                owner.issues.filter { it.code in malformedRequiredFieldCodes && !s.source.correctionCovers(owner.source,it) }.forEach { reject(it,owner.source) }
            }
            val rootIssues = s.source.format.issues + s.source.format.draft?.issues.orEmpty() + s.source.ocr?.regions.orEmpty().flatMap { it.issues }
            rootIssues.distinct().filter { it.code in malformedRequiredFieldCodes }.forEach { issue ->
                // Tabular/OCR producers duplicate local issues at the root. Use the actual
                // local owner before considering broader shared course/page excerpts.
                val exact = owners.filter { issue in it.issues }
                val scoped = exact.ifEmpty { owners.filter { issue.source != null && issue.source in it.excerpts } }
                if(scoped.isEmpty()) reject(issue,ReviewLocator()) // No safe attribution: fail closed.
                else scoped.filter { s.isSelected(it.source) && !s.source.correctionCovers(it.source,issue) }.forEach { reject(issue,it.source) }
            }
        }
        fun validateReviews() {
            val bindings = decisions.evidence.map { it.binding } + listOfNotNull(decisions.identity?.binding) + decisions.times.map { it.binding } + decisions.relativeDates.map { it.binding } + decisions.attachments.map { it.binding } + decisions.conflicts.map { it.binding } + decisions.unscheduled.map { it.binding } + decisions.optionalMetadata.map { it.binding } + listOfNotNull(decisions.sourceRelation?.binding)
            if(bindings.any { it != s.binding }) problem("STALE_REVIEW_BINDING")
            fun duplicates(values:List<Any>,code:String) { if(values.size != values.toSet().size) problem(code) }
            duplicates(decisions.evidence.map { it.requirementFingerprint },"DUPLICATE_EVIDENCE_REVIEW")
            duplicates(decisions.times.map { it.source },"DUPLICATE_TIME_REVIEW")
            duplicates(decisions.relativeDates.map { it.proposalIndex },"DUPLICATE_RELATIVE_REVIEW")
            duplicates(decisions.attachments.map { it.source },"DUPLICATE_ATTACHMENT_REVIEW")
            duplicates(decisions.conflicts.map { it.conflictFingerprint },"DUPLICATE_CONFLICT_REVIEW")
            duplicates(decisions.unscheduled.map { it.source },"DUPLICATE_UNSCHEDULED_REVIEW")
            decisions.unscheduled.forEach { d -> checkReview("INVALID_UNSCHEDULED_REVIEW",d.source) { require(s.keepUnscheduled(d.source).contentFingerprint == d.contentFingerprint) } }
            duplicates(decisions.optionalMetadata.map { it.source },"DUPLICATE_OPTIONAL_METADATA_REVIEW")
            decisions.optionalMetadata.forEach { d -> checkReview("INVALID_OPTIONAL_METADATA_REVIEW",d.source) { require(s.retainUnknownOptionalMetadata(d.source,d.fields).contentFingerprint == d.contentFingerprint) } }
            decisions.evidence.forEach { checkReview("UNKNOWN_EVIDENCE_REVIEW") { s.reviewEvidence(it.requirementFingerprint) } }
            decisions.times.forEach { checkReview("INVALID_TIME_REVIEW",it.source) { s.reviewTime(it.source,it.choice) } }
            decisions.identity?.let { checkReview("INVALID_IDENTITY_REVIEW") { s.reviewIdentity(it.kind,it.archives) } }
            decisions.relativeDates.forEach { checkReview("INVALID_RELATIVE_REVIEW",ReviewLocator(relativeIndex = it.proposalIndex)) { s.resolveRelative(it.proposalIndex,it.basis,it.rule,it.resolvedDate) } }
            decisions.attachments.forEach { d -> checkReview("INVALID_ATTACHMENT_REVIEW",d.source) {
                val expected = s.attachException(d.source,d.attachment,d.targetTime)
                require(expected.originalScope == d.originalScope)
            } }
            s.requirements.forEach { r ->
                val satisfied = when(r.kind) {
                    ReviewRequirementKind.EVIDENCE -> decisions.evidence.any { it.binding == s.binding && it.requirementFingerprint == r.fingerprint }
                    ReviewRequirementKind.IDENTITY -> decisions.identity?.binding == s.binding
                    ReviewRequirementKind.TIME -> decisions.times.any { it.binding == s.binding && it.source == r.source }
                    ReviewRequirementKind.RELATIVE_DATE -> decisions.relativeDates.any { it.binding == s.binding && it.proposalIndex == r.source.relativeIndex }
                    ReviewRequirementKind.EXCEPTION_ATTACHMENT -> decisions.attachments.any { it.binding == s.binding && it.source == r.source }
                    ReviewRequirementKind.KEEP_UNSCHEDULED -> decisions.unscheduled.any { it.binding == s.binding && it.source == r.source }
                    ReviewRequirementKind.RETAIN_UNKNOWN_OPTIONAL_METADATA -> decisions.optionalMetadata.any { it.binding == s.binding && it.source == r.source }
                }
                if(!satisfied) problem("REVIEW_REQUIRED_${r.code}",r.source)
            }
        }
        fun checkReview(code:String,p:ReviewLocator = ReviewLocator(),block:()->Unit) {
            try { block() } catch(_:IllegalArgumentException) { problem(code,p) } catch(_:IllegalStateException) { problem(code,p) } catch(_:IndexOutOfBoundsException) { problem(code,p) } catch(_:java.time.DateTimeException) { problem(code,p) } catch(_:ArithmeticException) { problem(code,p) }
        }
        fun convertArchive(group:IdentityArchive) {
            if(group.members.isEmpty() || group.members.any { it !in s.selectedMeetings }) { problem("INVALID_IDENTITY_MEMBERS");return }
            val indices = group.members.map { it.courseIndex!! }.toSortedSet()
            val identities = indices.map { c -> s.context.courseIdentities[c] ?: s.source.format.draft!!.courses[c].let { raw ->
                val suffixCandidates = s.source.ocr?.candidateCourses.orEmpty().filter { it.path.courseIndex == c && (it.identityEvidence == com.lingion.sleepy.importing.ocr.OcrCourseIdentityEvidence.UNCONFIRMED_SUFFIX || raw.warnings.any { issue -> issue.code == "COURSE_CODE_SUFFIX_REVIEW_REQUIRED" }) }
                if(suffixCandidates.isEmpty()) ReviewedCourseIdentity(raw.name,raw.sourceCourseCode,raw.sourceClassCode)
                else {
                    val titles = suffixCandidates.map { it.completeTitle }.distinct()
                    if(titles.size != 1) problem("UNCERTAIN_COMPLETE_TITLE_REQUIRES_EXPLICIT_FIELDS",ReviewLocator(courseIndex = c))
                    ReviewedCourseIdentity(titles.firstOrNull() ?: raw.name,null,raw.sourceClassCode)
                }
            } }
            val identity = group.identity ?: identities.first()
            if(group.identity == null && identities.any { it != identity }) problem("MERGED_ARCHIVE_IDENTITY_REQUIRES_EXPLICIT_FIELDS")
            if(identity.name.isBlank()) problem("COURSE_NAME_REQUIRED",ReviewLocator(courseIndex = indices.first()))
            indices.forEach { c -> s.source.format.draft!!.courses[c].missingFields.filterNot { it in optionalMetadataFields || it == ImportField.COURSE_NAME && identity.name.isNotBlank() && (group.identity != null || s.context.courseIdentities.containsKey(c)) }.forEach { problem("COURSE_MISSING_${it.name}",ReviewLocator(courseIndex = c)) } }
            val converted = group.members.sortedWith(locatorOrder).mapNotNull(::convertMeeting)
            val coalesced = coalesceReviewedLessons(s,group.members,converted)
            val meetings = coalesced.map {it.first}
            if(meetings.isEmpty()) { problem("ARCHIVE_HAS_NO_VALID_MEETINGS");return }
            val courseIndex = courses.size
            coalesced.forEachIndexed { m,(_,sources) -> sources.forEach {mappings[it] = ReviewedArchivePosition(courseIndex,m)} }
            val provenance = indices.flatMap { c ->
                fromExcerpts(s.source.format.draft!!.courses[c].sources) + fromRefs(s.source.format.sources.filter { it.path.courseIndex == c && it.path.meetingIndex == null })
            }
            courses += ReviewedCourse(identity,fSet(indices),fList(meetings),fList(provenance),group.existingArchive,fSet(indices.flatMap { s.unknownOptionalFields(ReviewLocator(courseIndex = it)) }))
        }
        fun convertMeeting(p:ReviewLocator):ReviewedMeeting? {
            val m = s.meeting(p)
            val before = problems.size
            val keepUnscheduled = m.kind == com.lingion.sleepy.importing.MeetingKind.UNSCHEDULED && decisions.unscheduled.any { it.binding == s.binding && it.source == p && it.contentFingerprint == ImportCanonical.digest(m) }
            val maximumWeek = if(m.kind == com.lingion.sleepy.importing.MeetingKind.RECURRING) s.context.maxWeek else 1000
            if(m.weeks.any { it !in 1..maximumWeek }) problem("INVALID_DECLARED_WEEKS",p)
            if(m.sections.any { it !in 1..1000 }) problem("INVALID_SECTIONS",p)
            val kind = when(m.kind) {
                com.lingion.sleepy.importing.MeetingKind.RECURRING -> {
                    if(m.dayOfWeek !in 1..7 || m.weeks.isEmpty() || m.dates.isNotEmpty()) problem("INVALID_RECURRING_FIELDS",p)
                    MeetingKind.RECURRING
                }
                com.lingion.sleepy.importing.MeetingKind.DATE_SPECIFIC -> {
                    if(m.dates.isEmpty() || m.dayOfWeek != null && m.dayOfWeek !in 1..7) problem("INVALID_DATE_SPECIFIC_FIELDS",p)
                    if(m.dates.any { it.year !in 1..9999 || m.dayOfWeek != null && it.dayOfWeek.value != m.dayOfWeek }) problem("INVALID_DATE_OR_WEEKDAY",p)
                    MeetingKind.DATE_SPECIFIC
                }
                com.lingion.sleepy.importing.MeetingKind.UNSCHEDULED -> {
                    if(m.dates.isNotEmpty() || m.dayOfWeek != null && m.dayOfWeek !in 1..7 || m.exceptions.isNotEmpty() && s.selectedExceptions.any { it.courseIndex == p.courseIndex && it.meetingIndex == p.meetingIndex }) problem("UNSCHEDULED_HAS_DATES_OR_EXCEPTIONS",p)
                    MeetingKind.UNSCHEDULED
                }
            }
            val time = resolveTime(p,m.customTime,m.sections,kind == MeetingKind.UNSCHEDULED)
            // A PDF may declare only sections, optionally with a separate period
            // table. Only an explicit, valid source/target period decision resolves
            // that TIME marker; malformed original fields still require correction.
            val reviewedPeriodTime = time != null && decisions.times.any {it.binding==s.binding && it.source==p}
            m.missingFields.filterNot { it in optionalMetadataFields ||
                keepUnscheduled && it in setOf(ImportField.TIME,ImportField.DAY_OF_WEEK,ImportField.WEEKS) ||
                it==ImportField.TIME && reviewedPeriodTime
            }.forEach { problem("MEETING_MISSING_" + it.name,p) }
            if(before != problems.size || time == null) return null
            return ReviewedMeeting(p,kind,m.dayOfWeek ?: 0,
                fSet(if(kind == MeetingKind.RECURRING) m.weeks else emptySet()),fSet(if(kind == MeetingKind.RECURRING) emptySet() else m.weeks),fSet(m.dates),fSet(m.sections),time,
                m.teacher,m.room,m.group,m.experimentProject,m.isExperiment,
                fList(fromExcerpts(m.sources) + fromRefs(s.source.format.sources.filter { it.path.courseIndex == p.courseIndex && it.path.meetingIndex == p.meetingIndex && it.path.exceptionIndex == null })),
                retainedUnknownFields = s.unknownOptionalFields(p),
            )
        }
        fun resolveTime(p:ReviewLocator,custom:DraftTimeRange?,sections:Set<Int>,allowAbsent:Boolean = false):ReviewedTimeSpec? {
            if(sections.any { it !in 1..1000 }) { problem("INVALID_SECTIONS",p);return null }
            if(custom != null) {
                return try { ReviewedTimeSpec.Continuous(TimeRange(custom.start,custom.end)) } catch(_:IllegalArgumentException) { problem("INVALID_EXACT_TIME",p);null }
            }
            if(sections.isEmpty()) {
                if(allowAbsent) return ReviewedTimeSpec.Unscheduled
                problem("TIME_REQUIRED",p);return null
            }
            val proposals = s.source.format.draft!!.periodProposals.filter { it.section in sections }
            val review = decisions.times.singleOrNull { it.source == p && it.binding == s.binding }
            if(proposals.isNotEmpty() && review == null) { problem("SOURCE_PERIOD_REVIEW_REQUIRED",p);return null }
            if(review?.choice == TimeResolutionChoice.USE_SOURCE_PERIODS) {
                val grouped = proposals.groupBy { it.section }
                if(sections.any { section -> grouped[section].isNullOrEmpty() || grouped[section]!!.map { it.time }.distinct().size != 1 }) { problem("SOURCE_PERIOD_COVERAGE_OR_AMBIGUITY",p);return null }
                val intervals = sections.sorted().mapNotNull { section ->
                    val group = grouped.getValue(section)
                    try { SourcePeriodInterval(section,TimeRange(group.first().time.start,group.first().time.end),fList(group.flatMap { fromExcerpts(it.sources) })) }
                    catch(_:IllegalArgumentException) { problem("INVALID_SOURCE_PERIOD",p);null }
                }
                if(intervals.size != sections.size || overlaps(intervals)) { problem("SOURCE_PERIOD_OVERLAP_OR_INVALID",p);return null }
                return ReviewedTimeSpec.SourcePeriods(fSet(sections),fList(intervals),fList(proposals))
            }
            val grouped = s.context.targetPeriods.groupBy { it.number }
            if(sections.any { grouped[it]?.size != 1 }) { problem("TARGET_PERIOD_REQUIRED_OR_AMBIGUOUS",p);return null }
            val intervals = sections.sorted().map { section -> grouped.getValue(section).single().let { SourcePeriodInterval(section,TimeRange(it.start,it.end),emptyList()) } }
            if(overlaps(intervals)) { problem("TARGET_PERIOD_OVERLAP",p);return null }
            return ReviewedTimeSpec.TargetSections(fSet(sections),fList(intervals))
        }
        fun overlaps(intervals:List<SourcePeriodInterval>):Boolean = intervals.sortedBy { it.range.start }.zipWithNext().any { (a,b) -> a.range.end > b.range.start }
        fun convertException(p:ReviewLocator) {
            val raw = s.exception(p)
            val review = decisions.attachments.singleOrNull { it.source == p && it.binding == s.binding } ?: return
            val pending = p.pendingIndex?.let { s.source.format.pendingExceptions[it] }
            // An explicitly malformed role is not "no change": the old entry has no per-field
            // correction record, so it blocks instead of inheriting the current scope/time.
            val roles = pending?.roles
            if (roles != null) {
                if (roles.originalTimeInvalid) problem("ORIGINAL_TIME_INVALID_REQUIRES_CORRECTION",p)
                if (roles.originalSectionsInvalid) problem("ORIGINAL_SECTIONS_INVALID_REQUIRES_CORRECTION",p)
                if (roles.targetTimeInvalid) problem("TARGET_TIME_INVALID_REQUIRES_CORRECTION",p)
                if (roles.targetSectionsInvalid) problem("TARGET_SECTIONS_INVALID_REQUIRES_CORRECTION",p)
            }
            // Relative reviews confirm this frozen parse context. Correction/basis changes require
            // a new parse/session; they cannot fill ambiguous null dates or rewrite absolute dates.
            val originalDate = raw.originalDate
            val targetDate = raw.targetDate
            val before = problems.size
            raw.missingFields.filterNot { it in optionalMetadataFields || it == ImportField.ORIGINAL_DATE && originalDate != null || it == ImportField.TARGET_DATE && targetDate != null || it == ImportField.COURSE_NAME && review.attachment is ExceptionAttachment.Bound }.forEach { problem("EXCEPTION_MISSING_${it.name}",p) }
            val kind = when(raw.kind) {
                RichExceptionKind.CANCEL -> { if(originalDate == null || targetDate != null) problem("INVALID_CANCEL_DATES",p); ReviewedExceptionKind.CANCEL }
                RichExceptionKind.RESCHEDULE -> { if(originalDate == null || targetDate == null) problem("INVALID_RESCHEDULE_DATES",p); ReviewedExceptionKind.RESCHEDULE }
                RichExceptionKind.EXTRA -> { if(originalDate != null || targetDate == null) problem("INVALID_EXTRA_DATES",p); ReviewedExceptionKind.EXTRA }
            }
            if(listOfNotNull(originalDate,targetDate).any { it.year !in 1..9999 }) problem("INVALID_EXCEPTION_DATE",p)
            if(review.originalScope.declaredWeeks.any { it !in 1..1000 } || review.originalScope.sections?.isEmpty() == true || review.originalScope.sections?.any { it !in 1..1000 } == true || review.originalScope.dayOfWeek != null && review.originalScope.dayOfWeek !in 1..7) problem("INVALID_EXCEPTION_SCOPE",p)
            val unresolvedNoticeTime = p.pendingIndex != null && s.source.format.issues.any { it.code in setOf("AMBIGUOUS_TIME_MAPPING","INVALID_TIME","INVALID_SECTIONS","UNRESOLVED_TIME_OF_DAY") }
            if(unresolvedNoticeTime && (kind == ReviewedExceptionKind.CANCEL || review.targetTime == null)) problem("PENDING_TIME_REQUIRES_EXPLICIT_CORRECTION",p)
            val resolvedOriginalTime = if(kind == ReviewedExceptionKind.CANCEL && (raw.customTime != null || !raw.sections.isNullOrEmpty())) resolveTime(p,raw.customTime,raw.sections ?: emptySet()) else null
            val originalScope = review.originalScope.copy(resolvedTime = resolvedOriginalTime)
            val targetTime = if(kind == ReviewedExceptionKind.CANCEL) null else if(raw.customTime != null || !raw.sections.isNullOrEmpty()) {
                val expected = resolveTime(p,raw.customTime,raw.sections ?: emptySet())
                if(review.targetTime != null && ImportCanonical.digest(semanticTime(review.targetTime)) != ImportCanonical.digest(semanticTime(expected))) problem("TARGET_TIME_REQUIRES_SOURCE_CORRECTION",p)
                expected
            } else review.targetTime
            val bound = review.attachment as? ExceptionAttachment.Bound
            if(bound?.base is LogicalBaseReference.InputBase) {
                val base = bound.base
                if(base.source !in mappings) problem("INPUT_BASE_NOT_CONVERTED",p)
                if(s.meeting(base.source).kind == com.lingion.sleepy.importing.MeetingKind.UNSCHEDULED) problem("UNSCHEDULED_IS_NOT_EXCEPTION_BASE",p)
            }
            if(review.attachment is ExceptionAttachment.AsNewCourse && targetTime == null) problem("NEW_EXTRA_REQUIRES_EXACT_TIME",p)
            if(before != problems.size) return
            val provenance = fList(fromExcerpts(raw.sources) + fromRefs(pending?.sources ?: s.source.format.sources.filter { it.path.courseIndex == p.courseIndex && it.path.meetingIndex == p.meetingIndex && it.path.exceptionIndex == p.exceptionIndex }))
            val intent = ReviewedExceptionIntent(p,kind,review.attachment,originalDate,targetDate,originalScope,targetTime,raw.teacher,raw.room,raw.experimentProject,raw.group,provenance,s.unknownOptionalFields(p))
            intents += intent
            if(review.attachment is ExceptionAttachment.AsNewCourse) {
                val identity = review.attachment.identity
                val meeting = ReviewedMeeting(p,MeetingKind.DATE_SPECIFIC,0,emptySet(),fSet(pending?.declaredWeeks ?: emptySet()),fSet(setOf(targetDate!!)),fSet(raw.sections ?: emptySet()),targetTime!!,raw.teacher,raw.room,raw.group,raw.experimentProject,false,provenance,s.unknownOptionalFields(p))
                mappings[p] = ReviewedArchivePosition(courses.size,0)
                courses += ReviewedCourse(identity,emptySet(),fList(listOf(meeting)),provenance)
            }
        }
    }
    private val locatorOrder = compareBy<ReviewLocator>({ it.courseIndex ?: Int.MAX_VALUE },{ it.meetingIndex ?: Int.MAX_VALUE },{ it.exceptionIndex ?: -1 },{ it.pendingIndex ?: -1 })
    private val malformedRequiredFieldCodes = setOf("INVALID_TIME","INVALID_TIME_RANGE","INCOMPLETE_TIME","AMBIGUOUS_TIME_MAPPING","INVALID_DATE","INVALID_DAY_OF_WEEK","INVALID_WEEKS","INVALID_SECTIONS")
    private fun fromExcerpts(values:List<SourceExcerpt>):List<ScheduleProvenance> = values.map { e -> ScheduleProvenance(page = e.position.page,bounds = e.position.bounds.let { SourceBounds(it.left,it.top,it.right,it.bottom) },rawText = e.rawText) }
    private fun fromRefs(values:List<ImportSourceRef>):List<ScheduleProvenance> = values.flatMap { r ->
        buildList {
            add(ScheduleProvenance(sourceId = r.sourceId,rawText = r.rawText,sourceLocator = r.sourceLocator))
            if(r.row != null || r.column != null) add(ScheduleProvenance(sourceId = r.sourceId,rawText = r.rawText,sourceLocator = "${r.sourceLocator}:row:${r.row}:column:${r.column}"))
            r.formula?.let { add(ScheduleProvenance(sourceId = r.sourceId,rawText = it,sourceLocator = "${r.sourceLocator}:formula")) }
        }
    }
    private fun semanticTime(time:ReviewedTimeSpec?):Any? = when(time) {
        null -> null
        is ReviewedTimeSpec.Continuous -> listOf("continuous",time.range)
        is ReviewedTimeSpec.TargetSections -> listOf("sections",time.sections,time.intervals.map { listOf(it.section,it.range) })
        is ReviewedTimeSpec.SourcePeriods -> listOf("source-periods",time.sections,time.intervals.map { listOf(it.section,it.range) })
        ReviewedTimeSpec.Unscheduled -> "unscheduled"
    }
    private fun semanticMeeting(m:ReviewedMeeting):Any = listOf(m.kind,m.day,m.weeks,m.declaredWeeks,m.dates,m.originalSections,semanticTime(m.time),m.teacher,m.room,m.group,m.experimentProject,m.isExperiment,m.retainedUnknownFields)
    private fun semanticCourse(c:ReviewedCourse):Any = listOf(c.identity,c.meetings.map(::semanticMeeting).map(ImportCanonical::digest).sorted(),c.existingArchive,c.retainedUnknownFields)
    private fun Conversion.semanticIntent(e:ReviewedExceptionIntent):Any {
        val attachment = when(val a = e.attachment) {
            is ExceptionAttachment.AsNewCourse -> a.identity
            is ExceptionAttachment.Bound -> when(val base = a.base) {
                is LogicalBaseReference.StoredLogicalBase -> base
                is LogicalBaseReference.InputBase -> mappings[base.source]?.let { position -> listOf(courses[position.courseIndex].identity,semanticMeeting(courses[position.courseIndex].meetings[position.meetingIndex])) }
            }
        }
        return listOf(e.kind,attachment,e.originalDate,e.targetDate,listOf(e.originalScope.time,e.originalScope.sections,e.originalScope.declaredWeeks,e.originalScope.dayOfWeek,semanticTime(e.originalScope.resolvedTime)),semanticTime(e.targetTime),e.teacher,e.room,e.experimentProject,e.group,e.retainedUnknownFields)
    }
    private fun freezeDecisions(d:ImportReviewDecisions) = d.copy(
        evidence = fList(d.evidence),
        identity = d.identity?.let { IdentityReviewDecision(it.binding,it.kind,fList(it.archives.map { a -> a.copy(members = fSet(a.members)) })) },
        times = fList(d.times),relativeDates = fList(d.relativeDates),
        attachments = fList(d.attachments.map { ExceptionAttachmentReview(it.binding,it.source,it.attachment,it.originalScope.copy(sections = it.originalScope.sections?.let(::fSet),declaredWeeks = fSet(it.originalScope.declaredWeeks),resolvedTime = it.originalScope.resolvedTime?.let(::freezeTime)),it.targetTime?.let(::freezeTime)) }),
        conflicts = fList(d.conflicts),unscheduled = fList(d.unscheduled),
        optionalMetadata = fList(d.optionalMetadata.map { RetainUnknownOptionalMetadataReview(it.binding,it.source,fSet(it.fields),it.contentFingerprint) }),
        sourceRelation = d.sourceRelation,
    )
}
