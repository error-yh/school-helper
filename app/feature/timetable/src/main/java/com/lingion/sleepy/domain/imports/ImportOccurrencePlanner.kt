/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.*
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Pure planning over a transaction-loaded snapshot. Neither a database check nor an apply permit. */
internal object ImportOccurrencePlanner {
    fun plan(reviewed:ReviewedImport,target:LoadedImportTarget,
        conflictDecisions:List<ConflictReviewDecision> = reviewed.decisions.conflicts):ImportOccurrencePlan {
        val contextProblems=checkContext(reviewed.context,target)
        if(reviewed.requiresCalendarForOccurrencePlanning && reviewed.context.termMonday==null) return result(reviewed,emptyList(),emptySet(),emptyList(),listOf(OccurrencePlanProblem("CALENDAR_REQUIRED")),false,conflictDecisions)
        if(contextProblems.isNotEmpty()) return result(reviewed,emptyList(),emptySet(),emptyList(),contextProblems,false,conflictDecisions)
        return try {
            val budget=OccurrenceBudget()
            val decisions=conflictDecisions.associateBy { it.conflictFingerprint }
            val skipped=linkedSetOf<ReviewLocator>();val encountered=linkedMapOf<String,ActualImportConflict>()
            var effective:EngineResult
            do {
                // Monotonic skips, one shared invocation budget, and ORIGINAL reviewed input each pass.
                effective=Engine(reviewed,target,skipped,budget).run()
                effective.conflicts.forEach { encountered[it.fingerprint]=it }
                val newSkips=effective.conflicts.filter { decisions[it.fingerprint]?.choice==ConflictReviewChoice.SKIP && decisions[it.fingerprint]?.binding==reviewed.binding }.mapNotNull { it.incoming.source }.filter { it !in skipped }
                skipped.addAll(newSkips)
            } while(newSkips.isNotEmpty())
            val conflicts=encountered.values.toList()
            val problems=effective.problems.toMutableList()
            if(decisions.size!=conflictDecisions.size || conflictDecisions.any { it.binding!=reviewed.binding || it.conflictFingerprint !in encountered }) problems+=OccurrencePlanProblem("STALE_CONFLICT_DECISION")
            effective.conflicts.filter { decisions[it.fingerprint]?.choice!=ConflictReviewChoice.KEEP }.forEach { problems+=OccurrencePlanProblem("CONFLICT_REVIEW_REQUIRED",it.incoming.source) }
            result(reviewed,effective.courses,effective.removed,conflicts,problems.distinct(),effective.unchanged,conflictDecisions,effective.sourceRelationRequired)
        } catch(_:ImportPlanningLimit) {
            result(reviewed,emptyList(),emptySet(),emptyList(),listOf(OccurrencePlanProblem("OCCURRENCE_LIMIT")),false,conflictDecisions)
        } catch(_:InvalidNormalizationState) {
            result(reviewed,emptyList(),emptySet(),emptyList(),listOf(OccurrencePlanProblem("INVALID_NORMALIZATION_STATE")),false,conflictDecisions)
        }
    }

    fun logicalBases(target:LoadedImportTarget):List<LogicalBaseReference.StoredLogicalBase> {
        return fList(logicalBaseDetails(target).map { it.reference })
    }
    fun logicalBaseDetails(target:LoadedImportTarget):List<ImportLogicalBaseDisplay> {
        val budget=OccurrenceBudget()
        return fList(loadCourses(target,budget).flatMap { c->c.originals.map { o->
            ImportLogicalBaseDisplay(LogicalBaseReference.StoredLogicalBase(target.snapshot.scopeToken,target.snapshot.settings.id,c.id!!,o.familyId,o.descriptorFingerprint),
                c.metadata.courseName,o.original,fList(o.intervals),o.identity.teacher,o.identity.room,o.identity.project,o.identity.group)
        } })
    }

    private fun checkContext(context:ImportReviewContext,loaded:LoadedImportTarget):List<OccurrencePlanProblem> {
        val s=loaded.snapshot;val t=context.target
        return buildList {
            if(t.scopeToken!=s.scopeToken) add(OccurrencePlanProblem("WRONG_SCOPE"))
            if(t.semesterId!=s.settings.id) add(OccurrencePlanProblem("WRONG_SEMESTER"))
            if(t.expectedRevision!=s.revision) add(OccurrencePlanProblem("STALE_REVISION"))
            if(t.databaseSchemaVersion!=loaded.databaseSchemaVersion) add(OccurrencePlanProblem("WRONG_SCHEMA"))
            if(context.termMonday!=s.settings.startDate?.let(TimetableRules::mondayOf) || context.maxWeek!=s.settings.totalWeeks || context.targetPeriods!=s.periods) add(OccurrencePlanProblem("STALE_CALENDAR_OR_PERIODS"))
            if(TimetableRules.validateSettings(s.settings).isNotEmpty() || TimetableRules.validatePeriods(s.periods).isNotEmpty()) add(OccurrencePlanProblem("INVALID_LOADED_CALENDAR"))
        }
    }

    private fun result(r:ReviewedImport,courses:List<PlannedCourse>,removed:Set<Long>,conflicts:List<ActualImportConflict>,problems:List<OccurrencePlanProblem>,unchanged:Boolean,decisions:List<ConflictReviewDecision>,sourceRelationRequired:Boolean = false):ImportOccurrencePlan {
        val semantic=ImportCanonical.digest(listOf(courses.map { c->
            val originals=c.state.originals.associateBy { it.familyId }
            listOf(c.existingCourseId,c.draft.courseName,c.draft.teacher,c.draft.room,c.draft.sourceCourseCode,c.draft.sourceClassCode,
                c.state.originals.map { listOf(it.descriptorFingerprint,it.allocated) }.map(ImportCanonical::digest).sorted(),
                c.state.removes.map { listOf(originals.getValue(it.familyId).descriptorFingerprint,it.scope) }.map(ImportCanonical::digest).sorted(),
                c.state.adds.map { listOf(originals.getValue(it.familyId).descriptorFingerprint,it.scope,it.identity) }.map(ImportCanonical::digest).sorted())
        },removed))
        val digest=ImportCanonical.digest(listOf(r.reviewDigest,r.context,semantic,courses,removed,conflicts,problems,decisions))
        return ImportOccurrencePlan(ImportPlanPreview(r.context.target,r.reviewDigest,digest,semantic,r.counts,conflicts.map { it.fingerprint }.toSet()),fList(courses),fSet(removed),fList(conflicts),fList(problems),unchanged,sourceRelationRequired)
    }

    private data class WorkCourse(val key:String,val id:Long?,val metadata:CourseDraft,
        val originals:MutableList<LogicalOriginal> = mutableListOf(),val removes:MutableList<RemoveEffect> = mutableListOf(),
        val adds:MutableList<AddEffect> = mutableListOf(),val groups:MutableList<EffectGroup> = mutableListOf(),val sources:MutableList<SourceContentReference> = mutableListOf())
    private data class Atom(val course:WorkCourse,val original:LogicalOriginal,val scope:DatedInterval,val identity:OccurrenceIdentity,
        val source:ReviewLocator?,val incoming:Boolean)
    private data class EngineResult(val courses:List<PlannedCourse>,val removed:Set<Long>,val conflicts:List<ActualImportConflict>,val problems:List<OccurrencePlanProblem>,val unchanged:Boolean,val sourceRelationRequired:Boolean = false)
    private data class BoundIntent(val course:WorkCourse,val original:LogicalOriginal,val intent:ReviewedExceptionIntent,val removeKeys:Set<String>,val fulfilled:Set<String>,val key:String)

    private class Engine(val reviewed:ReviewedImport,val loaded:LoadedImportTarget,val skipped:Set<ReviewLocator>,val budget:OccurrenceBudget) {
        val problems=mutableListOf<OccurrencePlanProblem>();val conflicts=mutableListOf<ActualImportConflict>()
        /** True when an unresolved whole-batch source relation answer would clear these problems. */
        var sourceRelationRequired=false
        val loadedCourses=loadCourses(loaded,budget)
        val baseline=loadedCourses.associate { c->c.id to effectSignature(c) }
        val courses=if(reviewed.context.mode==ImportMode.APPEND) loadedCourses.toMutableList() else mutableListOf()
        val inputBindings=mutableMapOf<ReviewLocator,Pair<WorkCourse,LogicalOriginal>?>()
        val incomingFamilies=hashSetOf<String>();val addSources=mutableMapOf<String,ReviewLocator>()
        val restoredSources=mutableMapOf<Pair<String,DatedInterval>,ReviewLocator>()

        fun run():EngineResult {
            checkArchiveMembership()
            if(problems.isNotEmpty()) return EngineResult(emptyList(),emptySet(),emptyList(),fList(problems),false,sourceRelationRequired)
            addInputs()
            // Bind and form every causal group before any target Add is subtracted.
            val complete=reviewed.exceptions.filter { it.source !in skipped && it.attachment is ExceptionAttachment.Bound }.mapNotNull(::bindAndRemove)
            val bound=complete.filterNot { neutralize(it,complete) }
            var coverage=activeAtoms(courses,budget,incomingFamilies,addSources,restoredSources).toMutableList()
            for(item in bound) addTargets(item,coverage)
            coverage=activeAtoms(courses,budget,incomingFamilies,addSources,restoredSources).toMutableList()
            detectConflicts(coverage)
            val writes=mutableListOf<PlannedCourse>()
            for(c in courses) {
                val previous=c.id?.let { id->loaded.snapshot.courses.firstOrNull { it.id==id } }
                val newSignature=effectSignature(c)
                val oldSignature=if(previous==null) null else baseline[c.id]
                if(previous!=null && newSignature==oldSignature) continue
                if(c.originals.isEmpty()) continue
                val planned=reconstruct(c,loaded.snapshot,budget)
                val errors=TimetableRules.validateCourse(planned.draft,loaded.snapshot.settings,loaded.snapshot.periods)
                if(errors.isNotEmpty()) { problems+=OccurrencePlanProblem("INVALID_RESIDUAL_DOMAIN");continue }
                val reexpanded=actualSignature(ScheduledCourse(1,planned.draft.courseName,planned.draft.teacher,planned.draft.room,planned.draft.color,planned.draft.meetings,planned.draft.sourceCourseCode,planned.draft.sourceClassCode,planned.draft.provenance),loaded.snapshot,budget)
                check((semanticAtoms(coverage.filter { it.course===c },true) to inertSignature(c,true))==reexpanded) { "Residual positive-effect invariant" }
                writes+=planned
            }
            val removed=if(reviewed.context.mode==ImportMode.REPLACE) loaded.snapshot.courses.map { it.id }.toSet() else emptySet()
            return EngineResult(fList(writes),fSet(removed),fList(conflicts),fList(problems),writes.isEmpty()&&removed.isEmpty(),sourceRelationRequired)
        }

        private fun checkArchiveMembership() {
            if(reviewed.context.mode!=ImportMode.APPEND || reviewed.decisions.identity?.kind !in setOf(IdentityReviewKind.INDEPENDENT,IdentityReviewKind.PARTITION,IdentityReviewKind.MERGE)) return
            // ReviewLocator positions are zero-based inside ONE frozen source, so an unrelated file can
            // report the same position. Content decides membership: a position whose stored content
            // matches reuses that archive, and one whose stored content differs is either a changed
            // member of the SAME source or a course of a DIFFERENT source. Nothing in the stored
            // evidence names the file, so that question is never guessed here; it stays unresolved
            // until the reviewer answers once for the whole import.
            val differentSource=reviewed.decisions.sourceRelation?.choice==SourceRelationChoice.DIFFERENT_SOURCE
            val ownerGroup=mutableMapOf<Long,Int>()
            for((group,input) in reviewed.courses.withIndex()) {
                val members=input.meetings.filter { it.source !in skipped }
                val owners=mutableSetOf<Long>();var unmatched=false
                for(m in members) {
                    val historical=loadedCourses.flatMap { c->c.sources.filter { budget.compare();it.position==m.source }.map { c to it } }
                    if(historical.isEmpty()) { unmatched=true;continue }
                    val matching=historical.filter { it.second.contentFingerprint==aliasFingerprint(m) }
                    if(matching.isEmpty()) {
                        // A different source owns this position instead; it neither joins nor shadows
                        // the stored archive, so it must not count as an unmatched archive member.
                        if(differentSource) continue
                        sourceRelationRequired=true;unmatched=true
                        problems+=OccurrencePlanProblem("ARCHIVE_MEMBERSHIP_REVIEW_REQUIRED",m.source)
                        continue
                    }
                    if(matching.size>1) { problems+=OccurrencePlanProblem("ARCHIVE_MEMBERSHIP_REVIEW_REQUIRED",m.source);continue }
                    owners+=matching.single().first.id!!
                }
                if(owners.size>1 || owners.isNotEmpty() && unmatched) members.forEach { problems+=OccurrencePlanProblem("ARCHIVE_MEMBERSHIP_REVIEW_REQUIRED",it.source) }
                for(owner in owners) {
                    val prior=ownerGroup.putIfAbsent(owner,group)
                    if(prior!=null && prior!=group) members.forEach { problems+=OccurrencePlanProblem("ARCHIVE_MEMBERSHIP_REVIEW_REQUIRED",it.source) }
                }
            }
        }

        private fun addInputs() {
            for((ci,input) in reviewed.courses.withIndex()) {
                val stored=input.existingArchive?.let { ref->findStored(ref,ReviewLocator(courseIndex=ci))?.first }
                val meta=stored?.metadata ?: CourseDraft(input.identity.name.trim(),input.identity.teacher.trim(),input.identity.room.trim(),input.identity.color,emptyList(),input.identity.sourceCourseCode,input.identity.sourceClassCode,fList(input.provenance))
                val key=stored?.key ?: "input:$ci"
                val work=stored ?: WorkCourse(key,null,meta)
                if(stored==null) courses+=work
                for(m in input.meetings) {
                    if(m.source in skipped) { inputBindings[m.source]=null;continue }
                    val original=reviewedOriginal(input,m,work,budget,reviewed.context)
                    val rawFingerprint=aliasFingerprint(m)
                    val separate=reviewed.decisions.identity?.kind in setOf(IdentityReviewKind.INDEPENDENT,IdentityReviewKind.PARTITION)
                    fun eligible(c:WorkCourse,o:LogicalOriginal):Boolean = !separate || c===work || c.id!=null && c.sources.any { it.familyId==o.familyId && it.position==m.source && it.contentFingerprint==rawFingerprint }
                    val exact=courses.flatMap { c->c.originals.map { c to it } }.firstOrNull { (c,o)->eligible(c,o) && o.descriptorFingerprint==original.descriptorFingerprint }
                    if(exact!=null) {
                        val ownedAlias=exact.first.sources.any { it.familyId==exact.second.familyId && it.position==m.source && it.contentFingerprint==rawFingerprint }
                        // Historical input aliases are explicit lineage. Ordinary absorption is NOT binding.
                        inputBindings[m.source]=if(ownedAlias || exact.first.id==null) exact else null
                        if(original.original.kind!=MeetingKind.UNSCHEDULED) {
                            val active=activeAtoms(courses,budget,incomingFamilies,addSources,restoredSources)
                            val restored=original.allocated.flatMap { atom->
                                val cuts=active.filter { other->budget.compare();eligible(other.course,other.original) && other.scope.date==atom.date && other.identity==original.identity }.map { it.scope.range } +
                                    exact.first.removes.filter { it.familyId==exact.second.familyId && it.scope.date==atom.date }.map { it.scope.range }
                                ImportResidualBuilder.subtract(atom.range,cuts).map { range->budget.atom(atom.date);DatedInterval(atom.date,range) }
                            }
                            if(restored.isNotEmpty()) {
                                restored.forEach { restoredSources[exact.second.familyId to it]=m.source }
                                val index=exact.first.originals.indexOf(exact.second)
                                val updated=exact.second.copy(allocated=fList(exact.second.allocated+restored))
                                exact.first.originals[index]=updated
                                if(inputBindings[m.source]!=null) inputBindings[m.source]=exact.first to updated
                            }
                        }
                        continue
                    }
                    val active=activeAtoms(courses,budget,incomingFamilies,addSources,restoredSources)
                    val allocated=mutableListOf<DatedInterval>()
                    for(atom in original.allocated) {
                        val cuts=active.filter { other->budget.compare();eligible(other.course,other.original) && other.scope.date==atom.date && other.identity==original.identity }.map { it.scope.range }
                        ImportResidualBuilder.subtract(atom.range,cuts).forEach { range->budget.atom(atom.date);allocated+=DatedInterval(atom.date,range) }
                    }
                    if(original.original.kind==MeetingKind.UNSCHEDULED || allocated.isNotEmpty()) {
                        val accepted=original.copy(allocated=fList(allocated))
                        work.originals+=accepted;incomingFamilies+=accepted.familyId
                        work.sources+=SourceContentReference(accepted.familyId,m.source,aliasFingerprint(m))
                        inputBindings[m.source]=work to accepted
                    } else inputBindings[m.source]=null
                }
            }
        }

        private fun inputFingerprint(source:ReviewLocator):String {
            if(source.courseIndex!=null && source.meetingIndex!=null) return ImportCanonical.digest(reviewed.source.format.draft!!.courses[source.courseIndex].meetings[source.meetingIndex])
            return ImportCanonical.digest(listOf(source,reviewed.semanticFingerprint))
        }

        private fun aliasFingerprint(m:ReviewedMeeting):String = ImportCanonical.digest(listOf(inputFingerprint(m.source),
            m.provenance.map { listOf(it.rawText.trim(),it.sourceLocator) }.map(ImportCanonical::digest).distinct().sorted()))

        private fun findStored(ref:LogicalBaseReference.StoredLogicalBase,source:ReviewLocator):Pair<WorkCourse,LogicalOriginal>? {
            if(ref.scopeToken!=loaded.snapshot.scopeToken || ref.semesterId!=loaded.snapshot.settings.id) { problems+=OccurrencePlanProblem("FOREIGN_LOGICAL_BASE",source);return null }
            if(reviewed.context.mode==ImportMode.REPLACE) { problems+=OccurrencePlanProblem("STORED_BASE_ABSENT_IN_REPLACE",source);return null }
            val course=courses.firstOrNull { it.id==ref.courseId }
            val original=course?.originals?.firstOrNull { it.familyId==ref.logicalBaseId && it.descriptorFingerprint==ref.structureFingerprint }
            if(original==null) { problems+=OccurrencePlanProblem("STALE_LOGICAL_BASE",source);return null }
            return course to original
        }

        private fun bindAndRemove(e:ReviewedExceptionIntent):BoundIntent? {
            val ref=(e.attachment as ExceptionAttachment.Bound).base
            val bound=when(ref) {
                is LogicalBaseReference.StoredLogicalBase -> findStored(ref,e.source)
                is LogicalBaseReference.InputBase -> {
                    val input=inputBindings[ref.source]
                    if(ref.source !in reviewed.sourceMappings || ref.source in skipped) { problems+=OccurrencePlanProblem("INPUT_BASE_NOT_SELECTED",e.source);null }
                    else if(inputFingerprint(ref.source)!=ref.contentFingerprint) { problems+=OccurrencePlanProblem("STALE_INPUT_BASE",e.source);null }
                    else if(input==null) { problems+=OccurrencePlanProblem("INPUT_BASE_ABSORBED_REQUIRES_STORED_BINDING",e.source);null } else input
                }
            } ?: return null
            val(c,o)=bound
            val sourceContent=if(e.source.pendingIndex!=null) reviewed.source.format.pendingExceptions[e.source.pendingIndex].exception else reviewed.source.format.draft!!.courses[e.source.courseIndex!!].meetings[e.source.meetingIndex!!].exceptions[e.source.exceptionIndex!!]
            val sourceAlias=SourceContentReference(o.familyId,e.source,ImportCanonical.digest(sourceContent))
            if(sourceAlias !in c.sources) c.sources+=sourceAlias
            if(o.original.kind==MeetingKind.UNSCHEDULED) { problems+=OccurrencePlanProblem("UNSCHEDULED_EXCEPTION_BASE",e.source);return null }
            val date=e.originalDate
            if(e.kind!=ReviewedExceptionKind.EXTRA && (date==null || !originalDates(o.original,reviewed.context.termMonday,reviewed.context.maxWeek).contains(date))) {
                problems+=OccurrencePlanProblem("ORIGINAL_DATE_OUTSIDE_LOGICAL_BASE",e.source);return null
            }
            val key=ImportCanonical.digest(listOf(o.familyId,e.kind,date,e.targetDate,e.originalScope,e.targetTime,e.teacher,e.room,e.experimentProject,e.group,e.retainedUnknownFields))
            val removeKeys=mutableSetOf<String>();val fulfilled=mutableSetOf<String>()
            if(date!=null) {
                val ranges=e.originalScope.resolvedTime?.let(::timeRanges) ?: e.originalScope.time?.let(::listOf)
                    ?: e.originalScope.sections?.let { sections->loaded.snapshot.periods.filter { it.number in sections }.map { TimeRange(it.start,it.end) } } ?: o.intervals
                val valid=o.allocated.filter { it.date==date }.flatMap { allocated->ranges.mapNotNull { ImportResidualBuilder.intersection(allocated.range,it) } }
                if(valid.isEmpty()) { problems+=OccurrencePlanProblem("ORIGINAL_SCOPE_NOT_OWNED",e.source);return null }
                for(range in ImportResidualBuilder.union(valid)) {
                    val existing=c.removes.filter { it.familyId==o.familyId && it.scope.date==date }
                    val missing=ImportResidualBuilder.subtract(range,existing.map { it.scope.range })
                    existing.filter { ImportResidualBuilder.intersection(it.scope.range,range)!=null }.forEach { fulfilled+=it.key }
                    for(part in missing) {
                        val removeKey=ImportCanonical.digest(listOf(o.familyId,date,part))
                        c.removes+=RemoveEffect(removeKey,o.familyId,DatedInterval(date,part),fList(e.provenance));removeKeys+=removeKey
                    }
                }
            }
            return BoundIntent(c,o,e,fSet(removeKeys),fSet(fulfilled),key)
        }

        private fun addTargets(item:BoundIntent,coverage:MutableList<Atom>) {
            val(c,o,e)=Triple(item.course,item.original,item.intent)
            val addKeys=mutableSetOf<String>();val witnesses=mutableListOf<ExternalSatisfactionWitness>()
            if(e.kind!=ReviewedExceptionKind.CANCEL) {
                val date=e.targetDate!!
                val ranges=e.targetTime?.let(::timeRanges) ?: o.intervals
                val identity=targetIdentity(o,e)
                for(range in ranges) {
                    budget.atom(date)
                    val comparisons=coverage.filter { atom->budget.compare();atom.scope.date==date && atom.identity==identity }
                    comparisons.filter { it.course!==c }.forEach { other->
                        ImportResidualBuilder.intersection(range,other.scope.range)?.let { overlap->
                            val scope=DatedInterval(date,overlap)
                            val owner=other.course.id?.let { ObservationOwner.Stored(it) } ?: ObservationOwner.Planned(other.course.key)
                            witnesses+=ExternalSatisfactionWitness(ImportCanonical.digest(listOf(item.key,other.course.key,scope,identity)),loaded.snapshot.scopeToken,loaded.snapshot.settings.id,identity,scope,owner,ImportCanonical.digest(listOf(other.original.familyId,other.scope,other.identity)))
                        }
                    }
                    for(part in ImportResidualBuilder.subtract(range,comparisons.map { it.scope.range })) {
                        val scope=DatedInterval(date,part);val key=ImportCanonical.digest(listOf(item.key,scope,identity))
                        c.adds+=AddEffect(key,o.familyId,scope,identity,fList(e.provenance),"",key)
                        addSources[key]=e.source;addKeys+=key
                        coverage+=Atom(c,o,scope,identity,e.source,true)
                    }
                }
            }
            val oldIndex=c.groups.indexOfFirst { it.key==item.key }
            if(oldIndex<0) c.groups+=EffectGroup(item.key,item.removeKeys,fSet(addKeys),item.fulfilled,fList(witnesses.distinct()))
            else {
                val old=c.groups[oldIndex]
                c.groups[oldIndex]=old.copy(removeKeys=fSet(old.removeKeys+item.removeKeys),addKeys=fSet(old.addKeys+addKeys),fulfilledRemoveKeys=fSet((old.fulfilledRemoveKeys+item.fulfilled)-old.removeKeys-item.removeKeys),witnesses=fList((old.witnesses+witnesses).distinct()))
            }
        }

        /** A same-group equal move is neutral only while its original is active and no other
         * selected Remove competes for that family/date. Already-cancelled bases are never restored
         * by this reduction; a newly explicit Add then remains a real effect. */
        private fun neutralize(item:BoundIntent,all:List<BoundIntent>):Boolean {
            val e=item.intent;val o=item.original
            if(e.kind!=ReviewedExceptionKind.RESCHEDULE || e.originalDate!=e.targetDate || item.fulfilled.isNotEmpty() || targetIdentity(o,e)!=o.identity) return false
            if(all.any { it!==item && it.original.familyId==o.familyId && it.intent.originalDate==e.originalDate }) return false
            val removed=item.course.removes.filter { it.key in item.removeKeys }.map { it.scope.range }
            val target=e.targetTime?.let(::timeRanges) ?: o.intervals
            if(removed.isEmpty() || ImportResidualBuilder.union(removed)!=ImportResidualBuilder.union(target)) return false
            item.course.removes.removeAll { it.key in item.removeKeys }
            return true
        }

        private fun targetIdentity(o:LogicalOriginal,e:ReviewedExceptionIntent):OccurrenceIdentity {
            val overridden=buildSet {
                if(e.teacher!=null) add(com.lingion.sleepy.importing.ImportField.TEACHER)
                if(e.room!=null) add(com.lingion.sleepy.importing.ImportField.ROOM)
                if(e.experimentProject!=null) add(com.lingion.sleepy.importing.ImportField.EXPERIMENT_PROJECT)
                if(e.group!=null) add(com.lingion.sleepy.importing.ImportField.GROUP)
            }
            return o.identity.copy(teacher=e.teacher?.trim() ?: o.identity.teacher,room=e.room?.trim() ?: o.identity.room,
                project=e.experimentProject ?: o.identity.project,group=e.group ?: o.identity.group,unknown=fSet((o.identity.unknown-overridden)+e.retainedUnknownFields))
        }

        private fun detectConflicts(atoms:List<Atom>) {
            val previous=mutableListOf<Atom>()
            for(a in atoms.sortedWith(compareBy<Atom> { it.incoming }.thenBy { it.course.key }.thenBy { it.original.familyId }.thenBy { it.scope.date }.thenBy { it.scope.range.start })) {
                if(a.incoming) for(b in previous) {
                    budget.compare()
                    if(a.scope.date!=b.scope.date || a.identity==b.identity) continue
                    val overlap=ImportResidualBuilder.intersection(a.scope.range,b.scope.range) ?: continue
                    val incoming=side(a);val other=side(b)
                    conflicts+=ActualImportConflict(ImportCanonical.digest(listOf(reviewed.binding,incoming,other,overlap)),incoming,other,overlap)
                }
                previous+=a
            }
        }
        private fun side(a:Atom)=OccurrenceSide(a.course.id,a.source,a.original.familyId,a.identity,a.scope.date,a.scope.range)
    }

    private fun reviewedOriginal(c:ReviewedCourse,m:ReviewedMeeting,work:WorkCourse,budget:OccurrenceBudget,context:ImportReviewContext):LogicalOriginal {
        val original=CourseMeeting(day=m.day,weeks=fSet(m.weeks),sections=fSet(m.originalSections),customTime=(m.time as? ReviewedTimeSpec.Continuous)?.range,
            teacher=m.teacher?.trim(),room=m.room?.trim(),kind=m.kind,dates=fSet(m.dates),experimentProject=m.experimentProject,group=m.group,isExperiment=m.isExperiment,
            provenance=fList(m.provenance + if(m.time is ReviewedTimeSpec.SourcePeriods) m.time.intervals.flatMap { it.provenance } else emptyList()),declaredWeeks=fSet(m.declaredWeeks))
        val id=OccurrenceIdentity(work.metadata.courseName,work.metadata.sourceCourseCode,work.metadata.sourceClassCode,m.teacher?.trim() ?: c.identity.teacher.trim(),m.room?.trim() ?: c.identity.room.trim(),m.experimentProject,m.group,m.isExperiment,fSet(c.retainedUnknownFields+m.retainedUnknownFields))
        val intervals=timeRanges(m.time)
        val descriptor=descriptorFingerprint(original,id,intervals,m.time !is ReviewedTimeSpec.SourcePeriods)
        val family=ImportCanonical.digest(listOf(work.key,m.source,descriptor))
        val allocated=originalDates(original,context.termMonday,context.maxWeek).flatMap { date->intervals.map { range->budget.atom(date);DatedInterval(date,range) } }
        return LogicalOriginal(family,descriptor,immutableMeeting(original),id,emptySet(),fList(allocated),fList(intervals),m.time !is ReviewedTimeSpec.SourcePeriods,m.source)
    }

    private fun loadCourses(loaded:LoadedImportTarget,budget:OccurrenceBudget):List<WorkCourse> {
        if(loaded.states.keys.any { id->loaded.snapshot.courses.none { it.id==id } }) throw InvalidNormalizationState()
        return loaded.snapshot.courses.map { c->
            val work=WorkCourse("stored:${c.id}",c.id,c.asDraft().copy(meetings=emptyList()))
            val state=loaded.states[c.id]
            if(state!=null) {
                ImportNormalizationCodec.validate(state,loaded.snapshot.scopeToken,loaded.snapshot.settings.id,c,loaded.snapshot)
                work.originals+=state.graph.originals;work.removes+=state.graph.removes;work.adds+=state.graph.adds;work.groups+=state.graph.groups;work.sources+=state.graph.sources
            } else for(m in c.meetings) {
                val original=immutableMeeting(m.copy(id=0,exceptions=emptyList()))
                val identity=identity(c,m)
                val intervals=TimetableRules.periodIntervals(m,loaded.snapshot.periods)
                val descriptor=descriptorFingerprint(original,identity,intervals,true)
                val family=ImportCanonical.digest(listOf("legacy",c.id,m.id))
                val allocated=originalDates(original,loaded.snapshot.settings.startDate?.let(TimetableRules::mondayOf),loaded.snapshot.settings.totalWeeks).flatMap { date->intervals.map { range->budget.atom(date);DatedInterval(date,range) } }
                work.originals+=LogicalOriginal(family,descriptor,original,identity,fSet(setOf(m.id).filter { it>0 }),fList(allocated),fList(intervals))
                for(e in m.exceptions) {
                    val group=ImportCanonical.digest(listOf("legacy",c.id,m.id,e.id,e))
                    val removes=mutableSetOf<String>();val adds=mutableSetOf<String>()
                    e.originalDate?.let { date->intervals.forEach { range->
                        val key=ImportCanonical.digest(listOf(family,date,range));work.removes+=RemoveEffect(key,family,DatedInterval(date,range),fList(e.provenance));removes+=key
                    } }
                    e.targetDate?.let { date->
                        val ranges=e.customTime?.let(::listOf) ?: if(e.sections!=null) loaded.snapshot.periods.filter { it.number in e.sections }.map { TimeRange(it.start,it.end) } else intervals
                        for(range in ranges) {
                            budget.atom(date);val effective=identity.copy(teacher=e.teacher?.trim() ?: identity.teacher,room=e.room?.trim() ?: identity.room,project=e.experimentProject ?: identity.project,group=e.group ?: identity.group)
                            val scope=DatedInterval(date,range);val key=ImportCanonical.digest(listOf(group,scope,effective))
                            work.adds+=AddEffect(key,family,scope,effective,fList(e.provenance),"",key);adds+=key
                        }
                    }
                    work.groups+=EffectGroup(group,fSet(removes),fSet(adds))
                }
            }
            work
        }
    }

    private fun identity(c:ScheduledCourse,m:CourseMeeting,e:MeetingException?=null)=OccurrenceIdentity(c.courseName.trim(),c.sourceCourseCode,c.sourceClassCode,e?.teacher?.trim() ?: m.teacher?.trim() ?: c.teacher.trim(),e?.room?.trim() ?: m.room?.trim() ?: c.room.trim(),e?.experimentProject ?: m.experimentProject,e?.group ?: m.group,m.isExperiment)
    private fun activeAtoms(courses:List<WorkCourse>,budget:OccurrenceBudget,incoming:Set<String>,sources:Map<String,ReviewLocator>,restored:Map<Pair<String,DatedInterval>,ReviewLocator>):List<Atom> = courses.flatMap { c->c.originals.flatMap { o->
        o.allocated.flatMap { a->
            val cuts=c.removes.filter { it.familyId==o.familyId && it.scope.date==a.date }.map { it.scope.range }
            ImportResidualBuilder.subtract(a.range,cuts).map { range->budget.atom(a.date);val source=restored[o.familyId to a];Atom(c,o,DatedInterval(a.date,range),o.identity,source ?: o.source,o.familyId in incoming || source!=null) }
        } + c.adds.filter { it.familyId==o.familyId }.map { add->budget.atom(add.scope.date);Atom(c,o,add.scope,add.identity,sources[add.key],add.key in sources) }
    } }

    private fun semanticAtoms(atoms:List<Atom>,stripUnknown:Boolean=false):String = ImportCanonical.digest(atoms.groupBy { it.scope.date to if(stripUnknown) it.identity.copy(unknown=emptySet()) else it.identity }.map { (key,values)->listOf(key.first,key.second,ImportResidualBuilder.union(values.map { it.scope.range })) }.map(ImportCanonical::digest).sorted())
    private fun inertSignature(c:WorkCourse,stripUnknown:Boolean=false):String = ImportCanonical.digest(c.originals.filter { it.original.kind==MeetingKind.UNSCHEDULED }.map { if(stripUnknown) descriptorFingerprint(it.original,it.identity.copy(unknown=emptySet()),it.intervals,it.sectionsUseTarget) else it.descriptorFingerprint }.sorted())
    /** Family/negative/owned-Add semantics matter even when two live families cover the same time. */
    private fun effectSignature(c:WorkCourse):String = ImportCanonical.digest(c.originals.map { o->
        listOf(o.familyId,o.descriptorFingerprint,o.allocated,
            c.removes.filter { it.familyId==o.familyId }.groupBy { it.scope.date }.map { (date,items)->ImportCanonical.digest(listOf(date,ImportResidualBuilder.union(items.map { it.scope.range }))) }.sorted(),
            c.adds.filter { it.familyId==o.familyId }.map { listOf(it.scope,it.identity) }.map(ImportCanonical::digest).sorted())
    }.map(ImportCanonical::digest).sorted())
    private fun actualSignature(c:ScheduledCourse,s:TimetableSnapshot,budget:OccurrenceBudget):Pair<String,String> {
        val work=WorkCourse("actual",c.id,c.asDraft());val atoms=mutableListOf<Atom>()
        val dates=c.meetings.flatMap { originalDates(it,s.settings.startDate?.let(TimetableRules::mondayOf),s.settings.totalWeeks)+it.exceptions.mapNotNull { e->e.targetDate } }.toSet()
        for(date in dates) for(occurrence in TimetableRules.occurrencesOn(s.copy(courses=listOf(c)),date)) {
            val identity=identity(c,occurrence.meeting,occurrence.exception)
            // State unknowns must survive actual Domain's lack of an unknown-state column.
            val original=LogicalOriginal("", "",occurrence.meeting,identity,emptySet(),emptyList(),emptyList())
            for(range in occurrence.timeRanges) { budget.atom(date);atoms+=Atom(work,original,DatedInterval(date,range),identity,null,false) }
        }
        val inert=c.meetings.filter { it.kind==MeetingKind.UNSCHEDULED }.map { descriptorFingerprint(it,identity(c,it),emptyList(),true) }.sorted()
        return semanticAtoms(atoms) to ImportCanonical.digest(inert)
    }

    private data class FragmentShape(val sections:Set<Int>,val custom:TimeRange?,val cancelled:Boolean)
    private fun reconstruct(c:WorkCourse,s:TimetableSnapshot,budget:OccurrenceBudget):PlannedCourse {
        val fragments=mutableListOf<PlannedFragment>()
        for(o in c.originals) {
            if(o.original.kind==MeetingKind.UNSCHEDULED || o.original.kind==MeetingKind.RECURRING && s.settings.startDate==null) {
                fragments+=PlannedFragment(ImportCanonical.digest(listOf(o.familyId,"inert")),o.familyId,immutableMeeting(o.original.copy(id=0,exceptions=emptyList())))
                continue
            }
            val groups=linkedMapOf<FragmentShape,MutableSet<LocalDate>>()
            for((date,allocated) in o.allocated.groupBy { it.date }) {
                val cuts=ImportResidualBuilder.union(c.removes.filter { it.familyId==o.familyId && it.scope.date==date }.map { it.scope.range })
                for(cancelled in listOf(false,true)) {
                    val ranges=allocated.flatMap { a->if(cancelled) cuts.mapNotNull { ImportResidualBuilder.intersection(a.range,it) } else ImportResidualBuilder.subtract(a.range,cuts) }.distinct().sortedBy { it.start }
                    val sections=mutableSetOf<Int>();val custom=mutableListOf<TimeRange>()
                    for(range in ranges) {
                        val period=if(o.sectionsUseTarget && o.original.customTime==null) s.periods.firstOrNull { it.number in o.original.sections && it.start==range.start && it.end==range.end } else null
                        if(period!=null) sections+=period.number else custom+=range
                    }
                    if(sections.isNotEmpty()) groups.getOrPut(FragmentShape(fSet(sections),null,cancelled)) { linkedSetOf() }+=date
                    for(range in custom) groups.getOrPut(FragmentShape(emptySet(),range,cancelled)) { linkedSetOf() }+=date
                }
            }
            for((shape,dates) in groups) {
                val recurring=o.original.kind==MeetingKind.RECURRING
                val weeks=if(recurring) dates.map { ((ChronoUnit.DAYS.between(s.settings.startDate!!.let(TimetableRules::mondayOf),it))/7+1).toInt() }.toSet() else emptySet()
                val shapeRanges=shape.custom?.let(::listOf) ?: s.periods.filter { it.number in shape.sections }.map { TimeRange(it.start,it.end) }
                val meeting=o.original.copy(id=0,day=if(recurring) o.original.day else 0,weeks=fSet(weeks),dates=if(recurring) emptySet() else fSet(dates),sections=shape.sections,customTime=shape.custom,
                    teacher=if(o.identity.teacher==c.metadata.teacher && o.original.teacher==null) null else o.identity.teacher,
                    room=if(o.identity.room==c.metadata.room && o.original.room==null) null else o.identity.room,
                    exceptions=if(shape.cancelled) fList(dates.sorted().map { date->MeetingException(kind=ExceptionKind.CANCEL,originalDate=date,provenance=fList(c.removes.filter { it.familyId==o.familyId && it.scope.date==date && shapeRanges.any { range->ImportResidualBuilder.intersection(it.scope.range,range)!=null } }.flatMap { it.provenance }.distinct())) }) else emptyList())
                val key=ImportCanonical.digest(listOf(o.familyId,shape,dates))
                fragments+=PlannedFragment(key,o.familyId,immutableMeeting(meeting))
            }
        }
        val ownedAdds=mutableListOf<AddEffect>()
        for(add in c.adds) {
            val index=fragments.withIndex().filter { it.value.familyId==add.familyId }.minByOrNull { it.value.key }?.index ?: error("Missing owned Add anchor")
            val anchor=fragments[index]
            val exception=MeetingException(kind=ExceptionKind.EXTRA,targetDate=add.scope.date,customTime=add.scope.range,sections=emptySet(),teacher=add.identity.teacher,room=add.identity.room,experimentProject=add.identity.project,group=add.identity.group,provenance=fList(add.provenance))
            fragments[index]=anchor.copy(meeting=immutableMeeting(anchor.meeting.copy(exceptions=anchor.meeting.exceptions+exception)))
            ownedAdds+=add.copy(ownerFragmentKey=anchor.key)
        }
        val draft=c.metadata.copy(meetings=fList(fragments.map { it.meeting }),provenance=fList(c.metadata.provenance))
        val graph=PlannedNormalizationState(fList(c.originals),fList(c.removes),fList(ownedAdds),fList(c.groups),fList(c.sources))
        return PlannedCourse(c.key,c.id,draft,fList(fragments),graph)
    }

    internal fun descriptorFingerprint(m:CourseMeeting,identity:OccurrenceIdentity,intervals:List<TimeRange>,sectionsUseTarget:Boolean):String = ImportCanonical.digest(listOf(identity,m.kind,m.day,m.weeks,m.dates,m.declaredWeeks,m.sections,m.customTime,m.teacher,m.room,m.experimentProject,m.group,m.isExperiment,intervals,sectionsUseTarget,if(m.kind==MeetingKind.UNSCHEDULED) m.provenance.map { it.rawText.trim() }.distinct().sorted() else null))
    internal fun originalDates(m:CourseMeeting,monday:LocalDate?,maxWeek:Int):List<LocalDate> = when(m.kind) {
        MeetingKind.UNSCHEDULED->emptyList()
        MeetingKind.DATE_SPECIFIC->m.dates.sorted()
        MeetingKind.RECURRING->{ require(m.weeks.all { it in 1..maxWeek });if(monday==null) emptyList() else m.weeks.sorted().map { monday.plusWeeks(it-1L).plusDays(m.day-1L) } }
    }
    internal fun timeRanges(time:ReviewedTimeSpec):List<TimeRange> = when(time) {
        is ReviewedTimeSpec.Continuous->listOf(time.range)
        is ReviewedTimeSpec.TargetSections->time.intervals.map { it.range }
        is ReviewedTimeSpec.SourcePeriods->time.intervals.map { it.range }
        ReviewedTimeSpec.Unscheduled->emptyList()
    }
}

internal fun immutableMeeting(m:CourseMeeting):CourseMeeting = m.copy(weeks=fSet(m.weeks),sections=fSet(m.sections),dates=fSet(m.dates),declaredWeeks=fSet(m.declaredWeeks),provenance=fList(m.provenance),exceptions=fList(m.exceptions.map { e->e.copy(sections=e.sections?.let(::fSet),provenance=fList(e.provenance)) }))
internal class InvalidNormalizationState(message:String="INVALID_NORMALIZATION_STATE") : IllegalArgumentException(message)
