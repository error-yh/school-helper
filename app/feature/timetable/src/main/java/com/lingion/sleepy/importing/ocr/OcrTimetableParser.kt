/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import java.security.MessageDigest

/** Pure geometry/content dispatch. No engine, URI, filename, database, clock or model access. */
class OcrTimetableParser {
    fun parse(document:OcrLayoutDocument,context:OcrLayoutContext):OcrLayoutParseResult {
        val work=LayoutWork(context)
        return try {
            preflight(document,work)
            val session=OcrLayoutSession(document,context,work)
            val indexes=mutableListOf<OcrGeometryIndex>()
            for(page in document.pages.sortedBy { it.pageNumber }) {
                work.check()
                session.issues+=page.nativeIssues.map { layoutIssue(it.name) }
                for(v in page.variants) session.issues+=v.issues.map { layoutIssue(it.name) }
                if(page.variants.size>1) {
                    session.issues+=layoutIssue("OCR_ALTERNATE_REVIEW_REQUIRED")
                    session.issues+=layoutIssue("OCR_REVIEW_REQUIRED")
                    val words=page.variants.map { v -> v.spans.filter { it.level==OcrLevel.WORD }.map { it.rawText } }
                    if(words.distinct().size>1) session.issues+=layoutIssue("OCR_VARIANT_DISAGREEMENT")
                }
                val id=page.recommendedVariantId ?: page.variants.singleOrNull { it.usable && it.failure==null }?.id
                val variant=page.variants.singleOrNull { it.id==id && it.usable && it.failure==null && it.page!=null }
                if(variant==null) return session.rejected("OCR_VARIANT_UNUSABLE")
                session.select(page.pageNumber,variant.id)
                indexes+=OcrGeometryIndex(page,variant,work)
            }
            val date=DateListAdapter();val grid=SevenDayGridAdapter();val matrix=WeekMatrixAdapter()
            val actions=mutableListOf<()->Unit>()
            var ambiguous=false
            var previousGrid:GridPlan?=null
            for(index in indexes) {
                work.check()
                val dp=date.detect(index);val gp=grid.detect(index) ?: grid.continuation(index,previousGrid);val mp=matrix.detect(index)
                previousGrid=gp
                val candidates=listOfNotNull(dp?.candidate,gp?.candidate,mp?.candidate)
                session.candidates+=candidates
                when {
                    candidates.size>1 -> { ambiguous=true;session.issues+=layoutIssue("AMBIGUOUS_LAYOUT") }
                    dp!=null -> actions+= { date.parse(dp,session) }
                    gp!=null -> actions+= { grid.parse(gp,session) }
                    mp!=null -> actions+= { matrix.parse(mp,session) }
                    else -> session.issues+=layoutIssue("UNSUPPORTED_LAYOUT")
                }
            }
            // Never combine competing adapters. Keep complete raw evidence/candidate anchors.
            if(!ambiguous) actions.forEach { work.check();it() }
            session.finish(indexes)
        } catch(failure:LayoutRejected) {
            OcrLayoutParseResult(FormatParseResult(null,issues=listOf(layoutIssue(failure.code))),document)
        }
    }
    private fun preflight(document:OcrLayoutDocument,work:LayoutWork) {
        work.check();val limits=work.context.limits;val format=work.context.format.limits
        if(document.pages.size>limits.maxPages) throw LayoutRejected("LIMIT_EXCEEDED")
        val pages=hashSetOf<Int>();var spans=0L;var text=0L;var lines=0L
        fun charge(raw:String) {
            work.check();spans++;text+=raw.length
            if(spans>limits.maxRawSpans || text>minOf(limits.maxTextUnits,format.maxTextUnits) || raw.length>format.maxFieldUnits) throw LayoutRejected("LIMIT_EXCEEDED")
        }
        for(p in document.pages) {
            work.check()
            if(p.pageNumber<1 || !pages.add(p.pageNumber) || !validPageSize(p.width,p.height) || p.frame!=OcrLayoutFrame.CANONICAL_TOP_LEFT || p.variants.size !in 1..2 || p.variants.map { it.id }.distinct().size!=p.variants.size) throw LayoutRejected("INVALID_COORDINATES")
            for(v in p.variants) {
                if(v.rasterWidth<1 || v.rasterHeight<1 || v.page?.let { it.number!=p.pageNumber || it.width!=p.width || it.height!=p.height }==true) throw LayoutRejected("INVALID_COORDINATES")
                for(s in v.spans) { charge(s.rawText);s.position?.let { validatePosition(it,p) } }
            }
            p.textLayerEvidence?.let { layer ->
                if(layer.number!=p.pageNumber || layer.width!=p.width || layer.height!=p.height) throw LayoutRejected("INVALID_COORDINATES")
                for(t in layer.text) { charge(t.rawText);validatePosition(t.position,p) }
            }
            for(line in p.lines) {
                work.check();lines++
                if(lines>limits.maxRegions) throw LayoutRejected("LIMIT_EXCEEDED")
                validatePosition(SourcePosition(p.pageNumber,line.bounds),p)
            }
        }
    }
    private fun validatePosition(position:SourcePosition,page:OcrLayoutPage) {
        val b=position.bounds
        if(position.page!=page.pageNumber || listOf(b.left,b.top,b.right,b.bottom).any { !it.isFinite() } || b.left<0 || b.top<0 || b.right>page.width || b.bottom>page.height || b.left>=b.right || b.top>=b.bottom) throw LayoutRejected("INVALID_COORDINATES")
    }
}

internal class OcrLayoutSession(val document:OcrLayoutDocument,val context:OcrLayoutContext,val work:LayoutWork) {
    val issues=mutableListOf(layoutIssue("OCR_SOURCE_REVIEW_REQUIRED"))
    val candidates=mutableListOf<OcrLayoutCandidate>()
    private val selected=mutableMapOf<Int,OcrVariantId>()
    private val regions=mutableListOf<RegionEvidence>()
    private data class Record(val region:RegionEvidence,val course:RichCourseDraft,val assignments:Map<OcrSpanId,Set<String>>,val meetingSpans:List<Set<OcrSpanId>>,val completeTitle:String)
    private val records=mutableListOf<Record>()
    private var meetingCount=0
    private val ordinals=mutableMapOf<String,Int>()
    private val tokenRegions=mutableMapOf<OcrSpanId,MutableSet<String>>()
    private val tokenStatuses=mutableMapOf<OcrSpanId,SpanCoverageStatus>()
    private val periods=mutableListOf<PeriodProposal>()
    private val pendingExceptions=mutableListOf<PendingExceptionAttachment>()
    private val relativeDates=mutableListOf<RelativeDateProposal>()
    private val annotationPaths=mutableMapOf<OcrSpanId,MutableSet<DraftPath>>()
    fun period(token:GeometryToken) {
        work.check()
        val pair=individualAxisClock(token.text) ?: return
        period(PeriodProposal(pair.first,pair.second,listOf(token.excerpt())))
    }
    fun period(proposal:PeriodProposal) {
        work.check()
        require(proposal.section in 1..100 && proposal.time.end>proposal.time.start)
        if(periods.size>=context.limits.maxRegions) throw LayoutRejected("LIMIT_EXCEEDED")
        periods+=proposal
    }
    fun select(page:Int,variant:OcrVariantId) { selected[page]=variant }
    /** Only a colon-labelled cancellation is an operation. Ordinary holiday notes
     * remain annotations; a title containing a cancellation word is not a marker. */
    fun matrixAnnotation(index:OcrGeometryIndex,tokens:List<GeometryToken>) {
        index.checkCellText(tokens)
        region(index,tokens,OcrRegionKind.ANNOTATION)
        val token=tokens.first()
        val annotationText=tokens.joinToString(""){it.text}
        val marker=Regex("^(停课|取消)[:：]").find(annotationText) ?: return
        val path=DraftPath(field="NOTICE_ANNOTATION")
        val sources=tokens.map { t ->
            annotationPaths.getOrPut(t.id){linkedSetOf()}+=path
            ImportSourceRef(context.format.sourceId,"ocr:${t.id.pageNumber}:${t.id.variantId}:${t.id.index}",t.span.rawText,path)
        }
        fun unresolved(code:String) {
            tokens.forEach { tokenStatuses[it.id]=SpanCoverageStatus.UNPARSED }
            issues+=layoutIssue(code).copy(source=token.excerpt())
        }
        val explicitDates=Regex("(?<![0-9-])[0-9]{4}-[0-9]{2}-[0-9]{2}(?![0-9-])").findAll(annotationText).map { exactDate(it.value) }.toList()
        val explicitDate=explicitDates.singleOrNull()
        val hasRelative=annotationText.contains(Regex("今日|今天|明日|明天|后天|本周|下周|周末|近期|下次|[0-9]+月[0-9]+日"))
        val needsReference=annotationText.contains(Regex("今日|今天|明日|明天|后天|本周|下周|周末|近期|下次"))
        // An absolute declaration can supply the notice parser's otherwise unused
        // reference parameter. It never becomes the basis for a relative expression.
        val basis=context.referenceDate ?: if(needsReference) null else context.confirmedTermMonday ?: explicitDate?.takeUnless { hasRelative }
        if(basis==null) { unresolved("NOTICE_DATE_BASIS_REQUIRED");return }
        val text=marker.groupValues[1]+" "+annotationText.substring(marker.range.last+1)
        val parsed=NoticeTimetableParser().parse(text,NoticeParseContext(context.format,basis,context.confirmedTermMonday,context.confirmedYear))
        if(parsed.draft==null) throw LayoutRejected(parsed.issues.firstOrNull()?.code ?: "UNPARSED_NOTICE")
        issues+=parsed.issues.map { it.copy(source=token.excerpt()) }
        if(parsed.relativeDates.size>context.format.limits.maxExceptions-relativeDates.size) throw LayoutRejected("LIMIT_EXCEEDED")
        relativeDates+=parsed.relativeDates.map { p -> p.copy(source=sources.firstOrNull { it.rawText.contains(p.originalToken) } ?: sources.first()) }
        val pending=parsed.pendingExceptions.singleOrNull()
        val name=pending?.courseName
        val explicitScope=explicitDate!=null && pending?.exception?.originalDate==explicitDate && pending.exception.kind==RichExceptionKind.CANCEL &&
            pending.exception.customTime!=null && !name.isNullOrBlank() && !name.contains(Regex("[/、;；|]|全部|所有|全校|各班|全体")) &&
            pending.exception.missingFields.isEmpty() && parsed.relativeDates.isEmpty() && parsed.unparsedSources.isEmpty() &&
            parsed.issues.all { it.code=="PENDING_EXCEPTION_ATTACHMENT" }
        if(explicitScope) {
            if(pendingExceptions.size>=context.format.limits.maxExceptions) throw LayoutRejected("LIMIT_EXCEEDED")
            pendingExceptions+=pending!!.copy(sources=sources,exception=pending.exception.copy(sources=tokens.map { it.excerpt() },warnings=pending.exception.warnings.map { it.copy(source=token.excerpt()) }))
        } else unresolved("ANNOTATION_SCOPE_REVIEW_REQUIRED")
    }
    fun region(index:OcrGeometryIndex,tokens:List<GeometryToken>,kind:OcrRegionKind,box:TextBox?=boundsOf(tokens),issues:List<ParseIssue> = emptyList(),alternatives:List<String> = emptyList()):RegionEvidence {
        work.check()
        if(regions.size>=context.limits.maxRegions) throw LayoutRejected("LIMIT_EXCEEDED")
        val coords=box?.let { "${it.left.toBits()}:${it.top.toBits()}:${it.right.toBits()}:${it.bottom.toBits()}" } ?: "unpositioned"
        val base="p${index.page.pageNumber}:${index.variant.id}:$kind:$coords"
        val ordinal=ordinals[base] ?: 0;ordinals[base]=ordinal+1
        val id=base+":$ordinal"
        val region=RegionEvidence(id,index.page.pageNumber,index.variant.id,box,tokens.map { it.id }.distinct(),kind,issues,alternatives)
        regions+=region;this.issues+=issues
        for(t in tokens) {
            work.check();tokenRegions.getOrPut(t.id){linkedSetOf()}+=id
            val status=when(kind) { OcrRegionKind.RECORD -> SpanCoverageStatus.PARSED;OcrRegionKind.STRUCTURAL_ANCHOR -> SpanCoverageStatus.STRUCTURAL_ANCHOR;OcrRegionKind.ANNOTATION -> SpanCoverageStatus.ANNOTATION;else -> SpanCoverageStatus.UNPARSED }
            val old=tokenStatuses[t.id]
            // Sharing an axis with a parsed record retains both region references.
            if(old==null || status==SpanCoverageStatus.PARSED || status==SpanCoverageStatus.UNPARSED) tokenStatuses[t.id]=status
        }
        return region
    }
    fun record(index:OcrGeometryIndex,tokens:List<GeometryToken>,course:RichCourseDraft,alternatives:List<String> = emptyList(),unknown:Boolean=false,fields:Set<String> = setOf("record"),assignments:Map<OcrSpanId,Set<String>> = emptyMap(),supportTokens:List<GeometryToken> = emptyList(),completeTitle:String=course.name) {
        work.check()
        if(records.size>=minOf(context.limits.maxCandidates,context.format.limits.maxCourses)) throw LayoutRejected("LIMIT_EXCEEDED")
        if(course.meetings.size>context.format.limits.maxMeetings-meetingCount) throw LayoutRejected("LIMIT_EXCEEDED")
        meetingCount+=course.meetings.size
        val allIssues=course.warnings+course.meetings.flatMap { it.warnings }
        issues+=allIssues
        val all=(tokens+supportTokens).distinctBy { it.id }
        val source=all.map { it.excerpt() }
        val supports=supportTokens.map { it.excerpt() }
        val withSource=course.copy(sources=source,meetings=course.meetings.map { it.copy(sources=(it.sources+supports).distinct()) })
        val region=region(index,all,OcrRegionKind.RECORD,box=boundsOf(tokens),issues=emptyList(),alternatives=alternatives)
        val destinations=if(assignments.isEmpty()) tokens.associate { it.id to fields } else assignments
        val sourceIds=all.groupBy { it.excerpt() }
        val meetingSpans=withSource.meetings.map { meeting ->
            meeting.sources.flatMap { excerpt -> work.compare();sourceIds[excerpt].orEmpty().map { it.id } }.toSet()
        }
        records+=Record(region,withSource,destinations+supportTokens.associate { it.id to setOf("GEOMETRIC_AXIS") },meetingSpans,completeTitle)
        if(unknown) { issues+=layoutIssue("UNPARSED_RECORD_CONTENT");tokens.forEach { tokenStatuses[it.id]=SpanCoverageStatus.UNPARSED } }
    }
    fun rejected(code:String):OcrLayoutParseResult {
        val coverage=document.pages.flatMap { p->p.variants.flatMap { v->v.spans.mapIndexed { i,s->work.check();OcrSpanCoverage(OcrSpanId(p.pageNumber,v.id,i),s,SpanCoverageStatus.ALTERNATE_ONLY) } } }
        return OcrLayoutParseResult(FormatParseResult(null,issues=listOf(layoutIssue(code))),document,coverage=coverage)
    }
    fun finish(indexes:List<OcrGeometryIndex>):OcrLayoutParseResult {
        work.check()
        val courses=mutableListOf<RichCourseDraft>();val explicit=mutableMapOf<List<String>,Int>()
        val candidateCourses=mutableListOf<OcrCandidateCourse>();val paths=annotationPaths.mapValues { it.value.toMutableSet() }.toMutableMap()
        val sorted=records.sortedWith { a,b -> work.compare();compareValuesBy(a,b,{it.region.pageNumber},{it.region.bounds?.top},{it.region.bounds?.left},{it.region.id}) }
        for(record in sorted) {
            work.check();val c=record.course
            val suffixOnly=c.warnings.any { it.code=="COURSE_CODE_SUFFIX_REVIEW_REQUIRED" }
            val explicitCode=c.sourceCourseCode?.takeUnless { suffixOnly }
            val key=if(explicitCode!=null || c.sourceClassCode!=null) listOf(explicitCode.orEmpty(),c.sourceClassCode.orEmpty()) else null
            val existing=key?.let { explicit[it] }?.takeIf { courses[it].name==c.name }
            val ci=existing ?: courses.size;val mi=if(existing==null) 0 else courses[ci].meetings.size
            if(existing==null) {
                if(courses.size>=context.format.limits.maxCourses) throw LayoutRejected("LIMIT_EXCEEDED")
                courses+=c;if(key!=null) explicit[key]=ci
            } else courses[ci]=courses[ci].copy(meetings=courses[ci].meetings+c.meetings,sources=courses[ci].sources+c.sources)
            val id=CandidateCourseId("ocr-"+MessageDigest.getInstance("SHA-256").digest(record.region.id.toByteArray(Charsets.UTF_8)).take(16).joinToString(""){"%02x".format(it)})
            val identityEvidence=when { key!=null -> OcrCourseIdentityEvidence.EXPLICIT_CODES;suffixOnly -> OcrCourseIdentityEvidence.UNCONFIRMED_SUFFIX;c.meetings.size>1 -> OcrCourseIdentityEvidence.TITLE_CONTAINER;else -> OcrCourseIdentityEvidence.NONE }
            candidateCourses+=OcrCandidateCourse(id,c,listOf(record.region.id),record.region.spanIds,DraftPath(ci,mi),record.completeTitle,identityEvidence)
            for((span,fields) in record.assignments) for((offset,sourceIds) in record.meetingSpans.withIndex()) {
                work.compare()
                if(span in sourceIds) for(field in fields) { work.compare();paths.getOrPut(span){linkedSetOf()}+=DraftPath(ci,mi+offset,field=field) }
            }
        }
        val unkeyed=candidateCourses.filter { it.identityEvidence!=OcrCourseIdentityEvidence.EXPLICIT_CODES }
        val proposals=unkeyed.groupBy { layoutMatch(it.completeTitle) }.values.filter { it.size>1 }.map { group ->
            work.check();ArchiveIdentityProposal(group.map { it.id },ArchiveIdentityEvidence.SAME_TITLE,group.flatMap { it.spanIds }.distinct())
        }
        if(unkeyed.isNotEmpty()) issues+=layoutIssue("ARCHIVE_IDENTITY_REVIEW_REQUIRED")
        // SYMBOL is evidence only. Link it to already classified WORD geometry without
        // ever decoding the same glyph layer as a second course.
        for(index in indexes) for((i,s) in index.variant.spans.withIndex()) if(s.level==OcrLevel.SYMBOL && s.position!=null) {
            work.check();val id=OcrSpanId(index.page.pageNumber,index.variant.id,i)
            val words=index.nearbyWords(s.position.bounds).filter { t -> work.compare();val b=t.box;val q=s.position.bounds;b.left<=q.left && b.right>=q.right && b.top<=q.top && b.bottom>=q.bottom }
            val classified=words.firstOrNull { tokenStatuses[it.id]!=null }
            if(classified!=null) {
                tokenStatuses[id]=tokenStatuses[classified.id]!!
                tokenRegions[id]=tokenRegions[classified.id].orEmpty().toMutableSet()
                paths[classified.id]?.let { paths[id]=it.toMutableSet() }
            }
        }
        val coverage=mutableListOf<OcrSpanCoverage>()
        val refs=mutableListOf<ImportSourceRef>();val unparsed=mutableListOf<ImportSourceRef>()
        for(page in document.pages.sortedBy { it.pageNumber }) for(variant in page.variants) for((i,s) in variant.spans.withIndex()) {
            work.check();val id=OcrSpanId(page.pageNumber,variant.id,i)
            val status=if(selected[page.pageNumber]!=variant.id) SpanCoverageStatus.ALTERNATE_ONLY else tokenStatuses[id] ?: SpanCoverageStatus.UNPARSED
            val spanPaths=paths[id].orEmpty().toSet()
            coverage+=OcrSpanCoverage(id,s,status,tokenRegions[id].orEmpty().toSet(),spanPaths)
            if(s.confidence==null || !s.confidence.isFinite() || s.confidence<70f || s.confidence>100f) issues+=layoutIssue("OCR_REVIEW_REQUIRED")
            val sourcePaths=spanPaths.ifEmpty { setOf(DraftPath(field=status.name)) }
            for(path in sourcePaths) {
                val ref=ImportSourceRef(context.format.sourceId,"ocr:${page.pageNumber}:${variant.id}:$i",s.rawText,path)
                refs+=ref
                if(status==SpanCoverageStatus.UNPARSED) unparsed+=ref
            }
        }
        if(coverage.any { it.status==SpanCoverageStatus.UNPARSED }) issues+=layoutIssue("UNPARSED_OCR_SOURCE")
        val finalIssues=issues.distinctBy { it.code }
        val rawUnparsed=coverage.filter { it.status==SpanCoverageStatus.UNPARSED }.mapNotNull { c->c.span.position?.let { SourceExcerpt(it,c.span.rawText) } }
        val clocks=periods.groupBy { it.section to it.time }.values.map { group -> work.check();group.first().copy(sources=group.flatMap { it.sources }) }
        val clockIssues=if(clocks.groupBy { it.section }.values.any { it.size>1 }) listOf(layoutIssue("CONFLICTING_PERIOD_CLOCKS")) else emptyList()
        val draft=RichTimetableDraft(courses=courses.toList(),periodProposals=clocks,issues=finalIssues+clockIssues,unparsed=rawUnparsed,adapterIds=candidates.map { it.adapterId }.toSet())
        return OcrLayoutParseResult(FormatParseResult(draft,refs,finalIssues+clockIssues,pendingExceptions=pendingExceptions.toList(),relativeDates=relativeDates.toList(),unparsedSources=unparsed),document,candidates.toList(),regions.toList(),coverage.toList(),candidateCourses.toList(),proposals)
    }
}
