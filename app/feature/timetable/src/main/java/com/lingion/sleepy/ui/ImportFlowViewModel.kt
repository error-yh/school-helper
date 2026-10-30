/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.*
import com.lingion.sleepy.domain.repository.*
import com.lingion.sleepy.importing.WeekExpressionParser
import com.lingion.sleepy.importing.RichMeetingDraft
import com.lingion.sleepy.importing.ImportField
import com.lingion.sleepy.importing.formats.ConfirmedColumnMapping
import com.lingion.sleepy.importing.formats.ImportCancellation
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ImportTextKind { JSON, NOTICE }
internal data class ImportFlowState(
    val text: String = "", val kind: ImportTextKind = ImportTextKind.JSON,
    val mode: ImportMode = ImportMode.APPEND, val referenceDate: LocalDate = LocalDate.now(),
    val session: ImportReviewSession? = null, val evidence: Set<String> = emptySet(),
    val target: ImportTargetView? = null,
    val acceptedOptional: Set<ReviewLocator> = emptySet(), val acceptedUnscheduled: Set<ReviewLocator> = emptySet(),
    val attachments: Map<ReviewLocator, ExceptionAttachment> = emptyMap(),
    val relativeReviews: Map<Int,RelativeDateReview> = emptyMap(),
    val timeChoices: Map<ReviewLocator,TimeResolutionChoice> = emptyMap(),
    val problems: List<String> = emptyList(), val preview: ImportPreviewResult? = null,
    val conflictChoices: Map<String, ConflictReviewChoice> = emptyMap(),
    val reviewed: ReviewedImport? = null, val busy: Boolean = false, val confirming: Boolean = false,
    val completed: Boolean = false,
    val documentSelected: Boolean = false,
    val tables: LoadedImportDocument.Tables? = null,
    val identityReview: IdentityReviewDecision? = null,
    val correctionSerial: Int = 0,
    val identityAssignments: Map<ReviewLocator,String> = emptyMap(),
    val identityNames: Map<String,String> = emptyMap(),
    val identityBindings: Map<String,LogicalBaseReference.StoredLogicalBase> = emptyMap(),
    /** One explicit whole-import answer: this source is not the stored one it collides with. */
    val sourceRelation: Boolean = false,
)

internal class ImportFlowViewModel(private val repository: ImportRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ImportFlowState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private data class DocumentRequest(val loader: AndroidImportDocumentLoader, val uri: Uri, val kind: ImportDocumentKind)
    private var documentRequest: DocumentRequest? = null
    private fun invalidate(change: (ImportFlowState) -> ImportFlowState) {
        generation++
        mutable.update { change(it).copy(session=null,target=null,tables=null,identityReview=null,identityAssignments=emptyMap(),identityNames=emptyMap(),identityBindings=emptyMap(),evidence=emptySet(),acceptedOptional=emptySet(),acceptedUnscheduled=emptySet(),attachments=emptyMap(),relativeReviews=emptyMap(),timeChoices=emptyMap(),conflictChoices=emptyMap(),problems=emptyList(),preview=null,reviewed=null,confirming=false,completed=false,sourceRelation=false) }
    }
    fun text(value: String) { if (value.length <= ImportSourceLoader.MAX_TEXT_CHARS && !mutable.value.busy) { documentRequest=null; invalidate { it.copy(text=value,documentSelected=false) } } }
    fun kind(value: ImportTextKind) { if (!mutable.value.busy) { documentRequest=null; invalidate { it.copy(kind=value,documentSelected=false) } } }
    fun mode(value: ImportMode) {
        val s=mutable.value;if(s.busy || s.mode==value) return
        if(s.session!=null) {
            mutable.update { it.copy(mode=value,identityBindings=emptyMap()) }
            resetReview(ImportReviewSession.create(s.session.source,s.session.context.copy(mode=value),s.session.selectedMeetings,s.session.selectedPending,s.session.selectedExceptions))
        } else if(s.tables!=null) mutable.update { it.copy(mode=value) }
        else invalidate { it.copy(mode=value) }
    }
    fun dismissPreview() { if(!mutable.value.busy) mutable.update { it.copy(preview=null,reviewed=null,confirming=false) } }
    fun referenceDate(value: LocalDate) { if (!mutable.value.busy) invalidate { it.copy(referenceDate=value) } }
    fun invalidReferenceDate() { if (!mutable.value.busy) invalidate { it } }
    private fun resetReview(session:ImportReviewSession,correction:Boolean=false) {
        generation++
        mutable.update { it.copy(session=session,identityReview=null,evidence=emptySet(),acceptedOptional=emptySet(),acceptedUnscheduled=emptySet(),attachments=emptyMap(),relativeReviews=emptyMap(),timeChoices=emptyMap(),conflictChoices=emptyMap(),problems=emptyList(),preview=null,reviewed=null,confirming=false,completed=false,correctionSerial=it.correctionSerial+if(correction) 1 else 0,sourceRelation=false) }
    }
    fun clearIdentityReview() { if(!mutable.value.busy) mutable.update { it.copy(identityReview=null,preview=null,reviewed=null,confirming=false) } }
    fun identityGroup(source:ReviewLocator,value:String) { if(!mutable.value.busy && value.length<=64) { clearIdentityReview();mutable.update { it.copy(identityAssignments=it.identityAssignments+(source to value)) } } }
    fun identityName(group:String,value:String) { if(!mutable.value.busy && value.length<=2048) { clearIdentityReview();mutable.update { it.copy(identityNames=it.identityNames+(group to value)) } } }
    fun identityBinding(group:String,value:LogicalBaseReference.StoredLogicalBase?) { if(!mutable.value.busy) { clearIdentityReview();mutable.update { it.copy(identityBindings=if(value==null) it.identityBindings-group else it.identityBindings+(group to value)) } } }
    fun confirmIdentityDraft() {
        val s=mutable.value;val session=s.session ?: return
        val assignments=session.selectedMeetings.associateWith { s.identityAssignments[it] ?: (it.courseIndex!!+1).toString() }
        confirmIdentity(assignments,s.identityNames.filterKeys { it in assignments.values },s.identityBindings.filterKeys { it in assignments.values })
    }
    fun confirmIdentity(assignments:Map<ReviewLocator,String>,names:Map<String,String>,bindings:Map<String,LogicalBaseReference.StoredLogicalBase>) {
        val s=mutable.value;val session=s.session ?: return;if(s.busy) return
        try {
            require(assignments.keys==session.selectedMeetings && assignments.values.all { it.isNotBlank() && it.length<=64 })
            val archives=assignments.entries.groupBy { it.value }.map { (group,entries)->
                val members=entries.map { it.key }.toSet()
                val first=session.source.format.draft!!.courses[members.first().courseIndex!!]
                val name=names[group]?.trim()?.takeIf { it.isNotEmpty() }
                require(name==null || name.length<=2048)
                IdentityArchive(members,name?.let { ReviewedCourseIdentity(it,first.sourceCourseCode,first.sourceClassCode) },bindings[group])
            }
            val kind=when {
                bindings.isNotEmpty()->IdentityReviewKind.BIND_EXISTING
                archives.size==1->IdentityReviewKind.MERGE
                archives.all { it.members.map { p->p.courseIndex }.distinct().size==1 } && archives.map { it.members.first().courseIndex }.distinct().size==archives.size->IdentityReviewKind.INDEPENDENT
                else->IdentityReviewKind.PARTITION
            }
            val review=session.reviewIdentity(kind,archives)
            mutable.update { it.copy(identityReview=review,preview=null,reviewed=null,confirming=false,problems=emptyList()) }
        } catch(_:IllegalArgumentException) { mutable.update { it.copy(problems=listOf("请为每条已选安排指定归属组，并核对课程名称与已有课程关联。")) } }
    }
    fun renameCourse(courseIndex:Int,name:String) {
        val s=mutable.value;val session=s.session ?: return;if(s.busy) return
        val raw=session.source.format.draft?.courses?.getOrNull(courseIndex) ?: return
        if(name.isBlank() || name.length>2048) return
        val identity=(session.context.courseIdentities[courseIndex] ?: ReviewedCourseIdentity(raw.name,raw.sourceCourseCode,raw.sourceClassCode)).copy(name=name.trim())
        resetReview(ImportReviewSession.create(session.source,session.context.copy(courseIdentities=session.context.courseIdentities+(courseIndex to identity)),session.selectedMeetings,session.selectedPending,session.selectedExceptions))
    }
    fun correctMeeting(locator:ReviewLocator,replacement:RichMeetingDraft,fields:Set<ImportField>) {
        val s=mutable.value;val session=s.session ?: return;if(s.busy) return
        try {
            val source=session.source.correctMeeting(locator,replacement,fields,session.context.maxWeek)
            resetReview(ImportReviewSession.create(source,session.context,session.selectedMeetings,session.selectedPending,session.selectedExceptions),true)
        } catch(failure:IllegalArgumentException) { mutable.update { it.copy(problems=listOf(failure.message ?: "修正内容无效，请检查后重试。")) } }
    }
    fun selectAll(selected:Boolean) {
        val s=mutable.value;val session=s.session ?: return;if(s.busy) return
        resetReview(ImportReviewSession.create(session.source,session.context,if(selected) null else emptySet(),if(selected) null else emptySet()))
    }
    fun documentKind(value: ImportDocumentKind) {
        if(mutable.value.busy) return
        documentRequest=documentRequest?.copy(kind=value)
        invalidate { it }
    }

    fun loadDocument(loader: AndroidImportDocumentLoader, uri: Uri, kind: ImportDocumentKind) {
        if (mutable.value.busy) return
        documentRequest=DocumentRequest(loader,uri,kind)
        val input=mutable.value
        invalidate { it.copy(busy=true,documentSelected=true) }
        val token=generation
        viewModelScope.launch {
            try {
                val target=repository.readImportTarget(1)
                val monday=target.snapshot.settings.startDate?.let(TimetableRules::mondayOf)
                val loaded=loader.load(uri,kind,target.snapshot.settings.totalWeeks,monday,input.referenceDate)
                if(token!=generation) return@launch
                when(loaded) {
                    is LoadedImportDocument.Rejected -> mutable.update { it.copy(busy=false,problems=listOf(loaded.message)) }
                    is LoadedImportDocument.Tables -> mutable.update { it.copy(busy=false,target=target,tables=loaded) }
                    is LoadedImportDocument.Parsed -> installSource(loaded.source,target,input.mode)
                }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(token==generation) mutable.update { it.copy(busy=false,problems=listOf("无法读取导入文件，请重试。")) } }
        }
    }
    private fun installSource(source: FrozenImportSource, target: ImportTargetView, mode: ImportMode) {
        val context=ImportReviewContext(target.target,mode,target.snapshot.settings.startDate?.let(TimetableRules::mondayOf),target.snapshot.settings.totalWeeks,target.snapshot.periods)
        mutable.update { it.copy(busy=false,target=target,tables=null,session=ImportReviewSession.create(source,context)) }
    }
    fun mapDocument(sheetNumber: Int, mapping: ConfirmedColumnMapping) {
        val input=mutable.value;val tables=input.tables ?: return;val target=input.target ?: return
        val loader=documentRequest?.loader ?: return
        if(input.busy) return
        val token=++generation
        mutable.update { it.copy(busy=true,problems=emptyList()) }
        viewModelScope.launch {
            try {
                val loaded=withContext(Dispatchers.Default) {
                    val request=currentCoroutineContext()
                    loader.mapTable(tables.copy(context=tables.context.copy(cancellation=ImportCancellation { request.ensureActive() })),sheetNumber,mapping)
                }
                if(token!=generation) return@launch
                when(loaded) {
                    is LoadedImportDocument.Parsed -> installSource(loaded.source,target,input.mode)
                    is LoadedImportDocument.Rejected -> mutable.update { it.copy(busy=false,problems=listOf(loaded.message)) }
                    else -> mutable.update { it.copy(busy=false,problems=listOf("请选择有效工作表与字段映射。")) }
                }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(token==generation) mutable.update { it.copy(busy=false,problems=listOf("映射无法应用，请检查列号。")) } }
        }
    }
    fun evidence(fingerprint: String, checked: Boolean) {
        if (mutable.value.busy || mutable.value.session?.requirements?.none { it.kind==ReviewRequirementKind.EVIDENCE && it.fingerprint==fingerprint } != false) return
        mutable.update { it.copy(evidence=if(checked) it.evidence+fingerprint else it.evidence-fingerprint,preview=null,reviewed=null,confirming=false) }
    }
    fun acceptRequirement(requirement: ReviewRequirement, checked: Boolean) {
        val s=mutable.value; if(s.busy || s.session?.requirements?.contains(requirement)!=true) return
        mutable.update {
            when(requirement.kind) {
                ReviewRequirementKind.RETAIN_UNKNOWN_OPTIONAL_METADATA -> it.copy(acceptedOptional=if(checked) it.acceptedOptional+requirement.source else it.acceptedOptional-requirement.source)
                ReviewRequirementKind.KEEP_UNSCHEDULED -> it.copy(acceptedUnscheduled=if(checked) it.acceptedUnscheduled+requirement.source else it.acceptedUnscheduled-requirement.source)
                else -> it
            }.copy(preview=null,reviewed=null,confirming=false)
        }
    }
    fun selectMeeting(locator: ReviewLocator, selected: Boolean) {
        val current=mutable.value;val session=current.session ?: return
        if(current.busy) return
        val meetings=if(selected) session.selectedMeetings+locator else session.selectedMeetings-locator
        val next=ImportReviewSession.create(session.source,session.context,meetings,session.selectedPending)
        generation++
        mutable.update { it.copy(session=next,identityReview=null,evidence=emptySet(),acceptedOptional=emptySet(),acceptedUnscheduled=emptySet(),attachments=emptyMap(),relativeReviews=emptyMap(),timeChoices=emptyMap(),conflictChoices=emptyMap(),preview=null,reviewed=null,confirming=false,problems=emptyList(),sourceRelation=false) }
    }
    fun selectPending(index:Int,selected:Boolean) {
        val s=mutable.value;val session=s.session ?: return;if(s.busy) return
        val pending=if(selected) session.selectedPending+index else session.selectedPending-index
        val next=ImportReviewSession.create(session.source,session.context,session.selectedMeetings,pending,session.selectedExceptions)
        generation++
        mutable.update { it.copy(session=next,identityReview=null,evidence=emptySet(),acceptedOptional=emptySet(),acceptedUnscheduled=emptySet(),attachments=emptyMap(),relativeReviews=emptyMap(),timeChoices=emptyMap(),conflictChoices=emptyMap(),preview=null,reviewed=null,confirming=false,problems=emptyList(),sourceRelation=false) }
    }
    fun attach(locator:ReviewLocator,attachment:ExceptionAttachment) {
        val s=mutable.value;val session=s.session ?: return;if(s.busy) return
        try { session.attachException(locator,attachment) } catch(_:IllegalArgumentException) { return }
        mutable.update { it.copy(attachments=it.attachments+(locator to attachment),preview=null,reviewed=null,confirming=false) }
    }
    fun chooseTime(locator:ReviewLocator,choice:TimeResolutionChoice) {
        val s=mutable.value;val session=s.session ?: return;if(s.busy) return
        try { session.reviewTime(locator,choice) } catch(_:IllegalArgumentException) { return }
        mutable.update { it.copy(timeChoices=it.timeChoices+(locator to choice),preview=null,reviewed=null,confirming=false) }
    }
    fun confirmRelative(index:Int) {
        val s=mutable.value;val session=s.session ?: return;if(s.busy) return
        val proposal=session.source.format.relativeDates.getOrNull(index) ?: return
        val candidate=proposal.candidate ?: return
        try {
            val pair:Pair<RelativeDateBasis,RelativeDateRule> = when(proposal.ruleId) {
                "REFERENCE_DAY_OFFSET" -> RelativeDateBasis.ReferenceDate(proposal.referenceDate) to RelativeDateRule.DayOffset(when(proposal.originalToken) { "今日","今天"->0;"明日","明天"->1;"后天"->2;else->return })
                "REFERENCE_ISO_WEEK_DAY" -> RelativeDateBasis.ReferenceDate(proposal.referenceDate) to RelativeDateRule.IsoWeekDay(proposal.originalToken.startsWith("下周"),candidate.dayOfWeek.value)
                "EXPLICIT_YEAR_MONTH_DAY" -> RelativeDateBasis.ExplicitYear(proposal.confirmedYear ?: return) to RelativeDateRule.MonthDay(candidate.monthValue,candidate.dayOfMonth)
                "CONFIRMED_TERM_NEXT_MONTH_DAY" -> RelativeDateBasis.TermMonday(proposal.confirmedTermMonday ?: return) to RelativeDateRule.MonthDay(candidate.monthValue,candidate.dayOfMonth)
                "CONFIRMED_TERM_WEEK_DAY" -> {
                    val match=Regex("(?:星期|周)([一二三四五六日天])$").find(proposal.originalToken) ?: return
                    val weeks=WeekExpressionParser.parse(proposal.originalToken.substring(0,match.range.first),session.context.maxWeek).weeks
                    RelativeDateBasis.TermMonday(proposal.confirmedTermMonday ?: return) to RelativeDateRule.TermWeekDay(weeks.single(),candidate.dayOfWeek.value)
                }
                else -> return
            }
            val review=session.resolveRelative(index,pair.first,pair.second,candidate)
            mutable.update { it.copy(relativeReviews=it.relativeReviews+(index to review),preview=null,reviewed=null,confirming=false) }
        } catch(_:Exception) { mutable.update { it.copy(problems=listOf("日期依据无效，请修改来源后重新解析。")) } }
    }
    fun parse() {
        if (mutable.value.busy) return
        documentRequest?.let { loadDocument(it.loader,it.uri,it.kind);return }
        val input=mutable.value; val token=++generation
        mutable.update { it.copy(busy=true,session=null,target=null,evidence=emptySet(),acceptedOptional=emptySet(),acceptedUnscheduled=emptySet(),attachments=emptyMap(),relativeReviews=emptyMap(),timeChoices=emptyMap(),conflictChoices=emptyMap(),preview=null,reviewed=null,problems=emptyList(),completed=false) }
        viewModelScope.launch {
            try {
                val target=repository.readImportTarget(1)
                val monday=target.snapshot.settings.startDate?.let(TimetableRules::mondayOf)
                val result=withContext(Dispatchers.Default) {
                    when(input.kind) {
                        ImportTextKind.JSON -> ImportSourceLoader.json(input.text,target.snapshot.settings.totalWeeks)
                        ImportTextKind.NOTICE -> ImportSourceLoader.notice(input.text,target.snapshot.settings.totalWeeks,input.referenceDate,monday)
                    }
                }
                if (token != generation) return@launch
                if (result.draft == null) { mutable.update { it.copy(busy=false,problems=result.issues.map { issue -> issue.message }.ifEmpty { listOf("来源无法读取") }) }; return@launch }
                val source=FrozenImportSource.fromFormat(result)
                val context=ImportReviewContext(target.target,input.mode,monday,target.snapshot.settings.totalWeeks,target.snapshot.periods)
                mutable.update { it.copy(busy=false,target=target,session=ImportReviewSession.create(source,context)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if(token==generation) mutable.update { it.copy(busy=false,problems=listOf("无法读取导入来源，请检查后重试。")) } }
        }
    }
    fun check() {
        val input=mutable.value;val session=input.session ?: return
        if(input.busy) return
        // One user action reviews this frozen import as a whole. Keep the typed,
        // content-bound decisions and all converter/transaction validation.
        if(session.requirements.any {it.kind==ReviewRequirementKind.IDENTITY} && input.identityReview==null) confirmIdentityDraft()
        session.requirements.filter {it.kind==ReviewRequirementKind.RELATIVE_DATE}.forEach {requirement ->
            requirement.source.relativeIndex?.takeIf {it !in mutable.value.relativeReviews}?.let(::confirmRelative)
        }
        val current=mutable.value
        val defaultClock=if(session.source.format.draft?.periodProposals.isNullOrEmpty()) TimeResolutionChoice.USE_TARGET_PERIODS else TimeResolutionChoice.USE_SOURCE_PERIODS
        val evidence=session.requirements.filter {it.kind==ReviewRequirementKind.EVIDENCE}.map {it.fingerprint}.toSet()
        val optional=session.requirements.filter {it.kind==ReviewRequirementKind.RETAIN_UNKNOWN_OPTIONAL_METADATA}.map {it.source}.toSet()
        val unscheduled=session.requirements.filter {it.kind==ReviewRequirementKind.KEEP_UNSCHEDULED}.map {it.source}.toSet()
        val times=session.requirements.filter {it.kind==ReviewRequirementKind.TIME}.associate {it.source to defaultClock}+current.timeChoices
        mutable.update {it.copy(evidence=evidence,acceptedOptional=optional,acceptedUnscheduled=unscheduled,timeChoices=times)}
        val decisions=ImportReviewDecisions(evidence=evidence.map(session::reviewEvidence),identity=current.identityReview,
            optionalMetadata=optional.map { session.retainUnknownOptionalMetadata(it,session.unknownOptionalFields(it)) },
            unscheduled=unscheduled.map(session::keepUnscheduled),
            attachments=current.attachments.map { (source,attachment)->session.attachException(source,attachment) },relativeDates=current.relativeReviews.values.toList(),
            times=times.map { (source,choice)->session.reviewTime(source,choice) },
            sourceRelation=if(input.sourceRelation) session.reviewSourceRelation(SourceRelationChoice.DIFFERENT_SOURCE) else null)
        val converted=RichDraftConverter.convert(session,decisions)
        if(converted is ImportReviewResult.Blocked) { mutable.update { it.copy(problems=converted.problems.map {p ->
            val course=p.source.courseIndex?.let {index ->session.source.format.draft?.courses?.getOrNull(index)?.name}
            val location=listOfNotNull(course,p.source.meetingIndex?.let {index ->"安排 ${index+1}"},p.source.pendingIndex?.let {index ->"通知 ${index+1}"}).joinToString(" · ")
            if(location.isBlank()) importReviewMessage(p.code) else "$location：${importReviewMessage(p.code)}"
        }.distinct(),preview=null,reviewed=null) };return }
        val reviewed=(converted as ImportReviewResult.Ready).reviewed;val token=generation
        mutable.update { it.copy(busy=true,conflictChoices=emptyMap(),problems=emptyList(),preview=null,reviewed=null) }
        viewModelScope.launch {
            try {
                val preview=repository.previewImport(reviewed)
                if(token==generation) mutable.update { it.copy(busy=false,reviewed=reviewed,preview=preview,problems=preview.problems.map { p -> p.code }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if(token==generation) mutable.update { it.copy(busy=false,problems=listOf("预览失败，请重新检查。")) } }
        }
    }
    fun resolveConflict(fingerprint:String,choice:ConflictReviewChoice) {
        val s=mutable.value
        if(s.busy || s.preview?.conflicts?.none {it.fingerprint==fingerprint}!=false) return
        resolveConflicts(s.conflictChoices+(fingerprint to choice))
    }
    fun resolveAllConflicts(choice:ConflictReviewChoice) {
        val s=mutable.value
        if(s.busy || s.preview?.conflicts.isNullOrEmpty()) return
        resolveConflicts(s.conflictChoices+checkNotNull(s.preview).conflicts.associate {it.fingerprint to choice})
    }
    private fun resolveConflicts(choices:Map<String,ConflictReviewChoice>) {
        val s=mutable.value;val session=s.session ?: return;val reviewed=s.reviewed ?: return
        if(s.busy) return
        val token=generation
        val decisions=choices.map { (key,value)->session.reviewConflict(key,value) }
        mutable.update { it.copy(busy=true,confirming=false) }
        viewModelScope.launch {
            try {
                val preview=repository.previewImport(reviewed,decisions)
                if(token==generation) mutable.update { it.copy(busy=false,conflictChoices=choices,preview=preview,problems=preview.problems.map { p->p.code }) }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { if(token==generation) mutable.update { it.copy(busy=false,problems=listOf("无法重新检查冲突。")) } }
        }
    }
    /** One explicit whole-import answer: stored positions alone never prove the source relationship. */
    fun confirmDifferentSource() {
        val s=mutable.value
        if(s.busy || s.preview?.sourceRelationRequired!=true || s.sourceRelation) return
        mutable.update { it.copy(sourceRelation=true) }
        check()
    }
    fun confirm() { val s=mutable.value;if(!s.busy && s.preview?.ready==true && s.reviewed!=null) mutable.update { it.copy(confirming=true) } }
    fun cancelConfirmation() { mutable.update { it.copy(confirming=false) } }
    fun apply() {
        val s=mutable.value;val reviewed=s.reviewed ?: return;val preview=s.preview ?: return
        if(s.busy || !s.confirming || !preview.ready) return
        mutable.update { it.copy(busy=true,confirming=false) }
        viewModelScope.launch {
            try {
                val session=checkNotNull(s.session)
                val decisions=s.conflictChoices.map { (key,value)->session.reviewConflict(key,value) }
                val result=repository.applyImport(ImportApplyCommand(reviewed.context.target,reviewed.context.mode,reviewed,preview.preview.planDigest,decisions))
                mutable.update { when(result) {
                    is ImportApplyResult.Applied -> it.copy(busy=false,completed=true,preview=null,reviewed=null,problems=emptyList())
                    is ImportApplyResult.Rejected -> it.copy(busy=false,preview=null,reviewed=null,problems=listOf("保存未完成，请重新预览：${result.code}"))
                } }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(busy=false,preview=null,reviewed=null,problems=listOf("保存未完成，请重新预览。")) } }
        }
    }
    class Factory(private val repository: ImportRepository): ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T: ViewModel> create(modelClass: Class<T>): T = ImportFlowViewModel(repository) as T
    }
}
