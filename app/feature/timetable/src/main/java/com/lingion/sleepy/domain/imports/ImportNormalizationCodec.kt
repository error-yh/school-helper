/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.importing.ImportField
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalTime

/** Private versioned state, not an external import format or authentication of arbitrary DB edits.
 * JSON objects have a fixed key order; sets sort, ordered history/provenance lists retain order.
 * Hydration requires C3's ACTUAL assigned IDs and then validates against the loaded owned course. */
internal object ImportNormalizationCodec {
    const val VERSION=1
    const val MAX_BYTES=4_194_304
    private const val MAX_ITEMS=200_000
    private val hash=Regex("[0-9a-f]{64}")
    private val json=Json { isLenient=false;ignoreUnknownKeys=false }

    fun encode(state:StoredNormalizationState):String = guarded {
        structural(state)
        val payload=stateJson(state).toString()
        bounded(payload)
        payload
    }
    fun decode(payload:String,scope:String,semesterId:Long,course:ScheduledCourse,snapshot:TimetableSnapshot):StoredNormalizationState = guarded {
        bounded(payload)
        val state=readState(json.parseToJsonElement(payload).jsonObject)
        // Persistence accepts only our canonical representation, rejecting unknown/duplicate keys,
        // alternate encodings and unbounded trees rather than silently discarding fields.
        if(encode(state)!=payload) invalid()
        validate(state,scope,semesterId,course,snapshot)
        state
    }
    fun hydrate(plan:PlannedCourse,course:ScheduledCourse,scope:String,semesterId:Long,snapshot:TimetableSnapshot,
        fragmentIds:Map<String,Long>,addExceptionIds:Map<String,Long>,plannedCourseIds:Map<String,Long> = emptyMap()):StoredNormalizationState = guarded {
        if(fragmentIds.keys!=plan.fragments.map { it.key }.toSet() || addExceptionIds.keys!=plan.state.adds.map { it.exceptionKey }.toSet()) invalid()
        val fragments=plan.fragments.map { f->
            val id=fragmentIds.getValue(f.key)
            val actual=course.meetings.singleOrNull { it.id==id } ?: invalid()
            if(meetingFingerprint(actual)!=meetingFingerprint(f.meeting)) invalid()
            OwnedFragment(f.key,f.familyId,id,meetingFingerprint(actual))
        }
        val adds=plan.state.adds.map { add->
            val fragment=fragments.singleOrNull { it.key==add.ownerFragmentKey } ?: invalid()
            val meeting=course.meetings.single { it.id==fragment.meetingId }
            val exception=meeting.exceptions.singleOrNull { it.id==addExceptionIds[add.exceptionKey] } ?: invalid()
            if(exceptionFingerprint(exception)!=exceptionFingerprint(addException(add))) invalid()
            OwnedAdd(add.key,fragment.key,exception.id,exceptionFingerprint(exception))
        }
        val graph=plan.state.copy(groups=fList(plan.state.groups.map { group->group.copy(witnesses=fList(group.witnesses.map { witness->
            val owner=when(val observed=witness.owner) {
                is ObservationOwner.Stored -> observed
                is ObservationOwner.Planned -> {
                    val id=plannedCourseIds[observed.courseKey] ?: invalid()
                    if(id==course.id || snapshot.courses.none { it.id==id }) invalid()
                    ObservationOwner.Stored(id)
                }
            }
            witness.copy(owner=owner)
        })) }))
        val state=StoredNormalizationState(VERSION,scope,semesterId,course.id,freezeGraph(graph),fList(fragments),fList(adds),fList(sourceReferences(course)))
        validate(state,scope,semesterId,course,snapshot)
        state
    }

    fun validate(state:StoredNormalizationState,scope:String,semesterId:Long,course:ScheduledCourse,snapshot:TimetableSnapshot) = guarded {
        structural(state)
        if(state.scopeToken!=scope || state.semesterId!=semesterId || state.courseId!=course.id || snapshot.scopeToken!=scope || snapshot.settings.id!=semesterId) invalid()
        val originals=state.graph.originals.associateBy { it.familyId }
        val fragments=state.fragments.associateBy { it.key }
        if(state.fragments.map { it.meetingId }.toSet()!=course.meetings.map { it.id }.toSet() || state.fragments.size!=course.meetings.size) invalid()
        val budget=OccurrenceBudget()
        val aliases=mutableSetOf<Long>()
        for(o in state.graph.originals) {
            if(o.original.id!=0L || o.original.exceptions.isNotEmpty()) invalid()
            if(o.identity.name!=course.courseName.trim() || o.identity.courseCode!=course.sourceCourseCode || o.identity.classCode!=course.sourceClassCode) invalid()
            if(o.descriptorFingerprint!=ImportOccurrencePlanner.descriptorFingerprint(o.original,o.identity,o.intervals,o.sectionsUseTarget)) invalid()
            if(o.sectionsUseTarget && o.intervals!=TimetableRules.periodIntervals(o.original,snapshot.periods)) invalid()
            if(!o.sectionsUseTarget && (o.original.customTime!=null || o.intervals.size!=o.original.sections.size || o.intervals.sortedBy { it.start }.zipWithNext().any { (a,b)->a.end>b.start })) invalid()
            if(o.oldMeetingAliases.any { it<=0 || !aliases.add(it) }) invalid()
            val dates=ImportOccurrencePlanner.originalDates(o.original,snapshot.settings.startDate?.let(TimetableRules::mondayOf),snapshot.settings.totalWeeks).toSet()
            for(a in o.allocated) {
                budget.atom(a.date)
                if(a.date !in dates || ImportResidualBuilder.subtract(a.range,o.intervals).isNotEmpty()) invalid()
            }
            if(o.allocated.groupBy { it.date }.values.any { values->values.sortedBy { it.range.start }.zipWithNext().any { (a,b)->a.range.end>b.range.start } }) invalid()
        }
        for(f in state.fragments) {
            val m=course.meetings.singleOrNull { it.id==f.meetingId } ?: invalid()
            if(f.familyId !in originals || f.shapeFingerprint!=meetingFingerprint(m)) invalid()
        }
        if(state.ownedAdds.map { it.key }.toSet()!=state.graph.adds.map { it.key }.toSet()) invalid()
        for(add in state.graph.adds) {
            budget.atom(add.scope.date)
            val owned=state.ownedAdds.singleOrNull { it.key==add.key } ?: invalid()
            val fragment=fragments[owned.fragmentKey] ?: invalid()
            if(fragment.familyId!=add.familyId || add.ownerFragmentKey!=fragment.key) invalid()
            val meeting=course.meetings.single { it.id==fragment.meetingId }
            val e=meeting.exceptions.singleOrNull { it.id==owned.exceptionId } ?: invalid()
            if(e.kind!=ExceptionKind.EXTRA || owned.shapeFingerprint!=exceptionFingerprint(e) || owned.shapeFingerprint!=exceptionFingerprint(addException(add))) invalid()
        }
        if(course.meetings.flatMap { m->m.exceptions.filter { it.kind!=ExceptionKind.CANCEL } }.map { it.id }.toSet()!=state.ownedAdds.map { it.exceptionId }.toSet()) invalid()
        for(remove in state.graph.removes) {
            budget.atom(remove.scope.date)
            val o=originals[remove.familyId] ?: invalid()
            if(ImportResidualBuilder.subtract(remove.scope.range,o.allocated.filter { it.date==remove.scope.date }.map { it.range }).isNotEmpty()) invalid()
        }
        // Negative and positive coverage are independently checked against actual owned Domain rows.
        for(o in originals.values) {
            val familyMeetings=state.fragments.filter { it.familyId==o.familyId }.map { f->course.meetings.single { it.id==f.meetingId } }
            val expectedNegative=state.graph.removes.filter { it.familyId==o.familyId }.map { it.scope }
            val actualNegative=familyMeetings.flatMap { m->m.exceptions.filter { it.kind==ExceptionKind.CANCEL }.flatMap { e->TimetableRules.periodIntervals(m,snapshot.periods).map { DatedInterval(e.originalDate!!,it) } } }
            if(coverage(expectedNegative)!=coverage(actualNegative)) invalid()
            val expectedPositive=o.allocated.flatMap { a->ImportResidualBuilder.subtract(a.range,expectedNegative.filter { it.date==a.date }.map { it.range }).map { DatedInterval(a.date,it) } }
            val actualPositive=familyMeetings.flatMap { m->ImportOccurrencePlanner.originalDates(m,snapshot.settings.startDate?.let(TimetableRules::mondayOf),snapshot.settings.totalWeeks).filter { date->m.exceptions.none { it.originalDate==date } }.flatMap { date->TimetableRules.periodIntervals(m,snapshot.periods).map { DatedInterval(date,it) } } }
            if(coverage(expectedPositive)!=coverage(actualPositive)) invalid()
            for(m in familyMeetings) {
                if((m.teacher ?: course.teacher).trim()!=o.identity.teacher || (m.room ?: course.room).trim()!=o.identity.room || m.experimentProject!=o.identity.project || m.group!=o.identity.group || m.isExperiment!=o.identity.isExperiment) invalid()
            }
        }
        if(state.sourceReferences!=sourceReferences(course)) invalid()
        if(TimetableRules.validateCourse(course.asDraft(),snapshot.settings,snapshot.periods).isNotEmpty()) invalid()
    }

    private fun structural(s:StoredNormalizationState) {
        if(s.version!=VERSION || s.scopeToken.isBlank() || s.scopeToken.length>2048 || s.semesterId<=0 || s.courseId<=0) invalid()
        val g=s.graph
        val counts=listOf(g.originals.size,g.removes.size,g.adds.size,g.groups.size,g.sources.size,s.fragments.size,s.ownedAdds.size,s.sourceReferences.size)
        if(counts.any { it>10_000 } || counts.sum()>MAX_ITEMS || g.originals.sumOf { it.allocated.size.toLong()+it.intervals.size+it.oldMeetingAliases.size }>MAX_ITEMS) invalid()
        fun unique(keys:List<String>) { if(keys.size!=keys.toSet().size || keys.any { !hash.matches(it) }) invalid() }
        unique(g.originals.map { it.familyId });unique(g.removes.map { it.key });unique(g.adds.map { it.key });unique(g.groups.map { it.key });unique(s.fragments.map { it.key });unique(s.ownedAdds.map { it.key })
        if(g.originals.isEmpty() || s.fragments.isEmpty()) invalid()
        val originals=g.originals.associateBy { it.familyId };val removes=g.removes.associateBy { it.key };val adds=g.adds.associateBy { it.key }
        var sourceItems=0L;var sourceCharacters=0L
        fun boundSources(values:List<ScheduleProvenance>) {
            sourceItems+=values.size
            if(sourceItems>MAX_ITEMS) invalid()
            for(p in values) { sourceCharacters+=p.rawText.length+(p.sourceId?.length ?: 0)+(p.sourceLocator?.length ?: 0)+(p.adapterId?.length ?: 0);if(sourceCharacters>MAX_BYTES) invalid() }
        }
        g.originals.forEach { boundSources(it.original.provenance) };g.removes.forEach { boundSources(it.provenance) };g.adds.forEach { boundSources(it.provenance) }
        if(s.fragments.any { it.meetingId<=0 || !hash.matches(it.shapeFingerprint) || it.familyId !in originals } || s.fragments.map { it.meetingId }.toSet().size!=s.fragments.size) invalid()
        if(s.ownedAdds.any { it.exceptionId<=0 || !hash.matches(it.shapeFingerprint) } || s.ownedAdds.map { it.exceptionId }.toSet().size!=s.ownedAdds.size) invalid()
        for(o in g.originals) {
            if(!hash.matches(o.descriptorFingerprint) || o.original.exceptions.isNotEmpty() || o.original.id!=0L || o.intervals.size>1000) invalid()
            if(o.identity.unknown.any { it !in optionalMetadataFields }) invalid()
        }
        if(g.removes.any { it.familyId !in originals } || g.adds.any { it.familyId !in originals || !hash.matches(it.ownerFragmentKey) || !hash.matches(it.exceptionKey) }) invalid()
        val addOwners=mutableMapOf<String,Int>();val removeOwners=mutableMapOf<String,Int>()
        var operations=0L
        for(group in g.groups) {
            operations+=group.removeKeys.size+group.addKeys.size+group.fulfilledRemoveKeys.size+group.witnesses.size
            if(operations>MAX_ITEMS || group.removeKeys.any { it !in removes } || group.fulfilledRemoveKeys.any { it !in removes } || group.addKeys.any { it !in adds }) invalid()
            val families=(group.removeKeys+group.fulfilledRemoveKeys).map { removes.getValue(it).familyId }.toSet()+group.addKeys.map { adds.getValue(it).familyId }
            if(families.size>1) invalid()
            group.addKeys.forEach { addOwners[it]=(addOwners[it] ?: 0)+1 };group.removeKeys.forEach { removeOwners[it]=(removeOwners[it] ?: 0)+1 }
            for(w in group.witnesses) if(!hash.matches(w.key) || w.scopeToken!=s.scopeToken || w.semesterId!=s.semesterId || w.owner !is ObservationOwner.Stored || w.owner.courseId<=0 || w.owner.courseId==s.courseId || !hash.matches(w.observedOwnerFingerprint)) invalid()
        }
        if(adds.keys.any { addOwners[it]!=1 } || removes.keys.any { removeOwners[it]!=1 }) invalid()
        for(source in g.sources) {
            val p=source.position
            if(source.familyId !in originals || !hash.matches(source.contentFingerprint) || p.courseIndex==null && p.pendingIndex==null || (p.field?.length ?: 0)>256 ||
                listOfNotNull(p.courseIndex,p.meetingIndex,p.exceptionIndex,p.pendingIndex,p.relativeIndex).any { it>10_000 } || p.courseIndex!=null && p.meetingIndex==null) invalid()
        }
        for(ref in s.sourceReferences) if(ref.courseId!=s.courseId || ref.meetingId!=null && ref.meetingId<=0 || ref.exceptionId!=null && (ref.exceptionId<=0 || ref.meetingId==null) || ref.position !in 0..9999 || !hash.matches(ref.contentFingerprint)) invalid()
    }

    internal fun meetingFingerprint(m:CourseMeeting):String = ImportCanonical.digest(m.copy(id=0,exceptions=m.exceptions.map { it.copy(id=0) }))
    private fun exceptionFingerprint(e:MeetingException):String = ImportCanonical.digest(e.copy(id=0))
    private fun addException(add:AddEffect)=MeetingException(kind=ExceptionKind.EXTRA,targetDate=add.scope.date,customTime=add.scope.range,sections=emptySet(),teacher=add.identity.teacher,room=add.identity.room,experimentProject=add.identity.project,group=add.identity.group,provenance=add.provenance)
    private fun coverage(values:List<DatedInterval>):String = ImportCanonical.digest(values.groupBy { it.date }.map { (date,items)->ImportCanonical.digest(listOf(date,ImportResidualBuilder.union(items.map { it.range }))) }.sorted())
    private fun sourceReferences(c:ScheduledCourse):List<StoredSourceReference> = buildList {
        c.provenance.forEachIndexed { i,p->add(StoredSourceReference(c.id,null,null,i,ImportCanonical.digest(p))) }
        for(m in c.meetings) {
            m.provenance.forEachIndexed { i,p->add(StoredSourceReference(c.id,m.id,null,i,ImportCanonical.digest(p))) }
            for(e in m.exceptions) e.provenance.forEachIndexed { i,p->add(StoredSourceReference(c.id,m.id,e.id,i,ImportCanonical.digest(p))) }
        }
    }

    private fun bounded(payload:String) {
        if(payload.length>MAX_BYTES || payload.toByteArray(Charsets.UTF_8).size>MAX_BYTES) invalid()
        var depth=0;var quoted=false;var escaped=false
        for(c in payload) {
            if(quoted) { if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') quoted=false }
            else when(c) { '"'->quoted=true;'[','{'->{ if(++depth>32) invalid() };']','}'->{ if(--depth<0) invalid() } }
        }
        if(depth!=0 || quoted) invalid()
    }
    private inline fun <T> guarded(block:()->T):T = try { block() } catch(e:InvalidNormalizationState) { throw e } catch(_:Exception) { throw InvalidNormalizationState() }
    private fun invalid():Nothing = throw InvalidNormalizationState()

    private fun stateJson(s:StoredNormalizationState)=obj("version" to num(s.version),"scope" to str(s.scopeToken),"semester" to num(s.semesterId),"course" to num(s.courseId),
        "graph" to graphJson(s.graph),"fragments" to arr(s.fragments) { f->obj("key" to str(f.key),"family" to str(f.familyId),"meeting" to num(f.meetingId),"shape" to str(f.shapeFingerprint)) },
        "ownedAdds" to arr(s.ownedAdds) { a->obj("key" to str(a.key),"fragment" to str(a.fragmentKey),"exception" to num(a.exceptionId),"shape" to str(a.shapeFingerprint)) },
        "sources" to arr(s.sourceReferences) { p->obj("course" to num(p.courseId),"meeting" to nullableNum(p.meetingId),"exception" to nullableNum(p.exceptionId),"position" to num(p.position),"content" to str(p.contentFingerprint)) })
    private fun readState(o:JsonObject)=StoredNormalizationState(o.int("version"),o.text("scope"),o.long("semester"),o.long("course"),readGraph(o.getValue("graph").jsonObject),
        fList(o.list("fragments").map { it.jsonObject.let { f->OwnedFragment(f.text("key"),f.text("family"),f.long("meeting"),f.text("shape")) } }),
        fList(o.list("ownedAdds").map { it.jsonObject.let { a->OwnedAdd(a.text("key"),a.text("fragment"),a.long("exception"),a.text("shape")) } }),
        fList(o.list("sources").map { it.jsonObject.let { p->StoredSourceReference(p.long("course"),p.nullLong("meeting"),p.nullLong("exception"),p.int("position"),p.text("content")) } }))
    private fun graphJson(g:PlannedNormalizationState)=obj(
        "originals" to arr(g.originals) { o->obj("family" to str(o.familyId),"descriptor" to str(o.descriptorFingerprint),"original" to meetingJson(o.original),"identity" to identityJson(o.identity),"aliases" to arr(o.oldMeetingAliases.sorted(),::num),"allocated" to arr(o.allocated,::datedJson),"intervals" to arr(o.intervals,::rangeJson),"targetSections" to JsonPrimitive(o.sectionsUseTarget),"source" to (o.source?.let(::locatorJson) ?: JsonNull)) },
        "removes" to arr(g.removes) { r->obj("key" to str(r.key),"family" to str(r.familyId),"scope" to datedJson(r.scope),"provenance" to arr(r.provenance,::provenanceJson)) },
        "adds" to arr(g.adds) { a->obj("key" to str(a.key),"family" to str(a.familyId),"scope" to datedJson(a.scope),"identity" to identityJson(a.identity),"provenance" to arr(a.provenance,::provenanceJson),"owner" to str(a.ownerFragmentKey),"exceptionKey" to str(a.exceptionKey)) },
        "groups" to arr(g.groups) { g0->obj("key" to str(g0.key),"removes" to strings(g0.removeKeys.sorted()),"adds" to strings(g0.addKeys.sorted()),"fulfilled" to strings(g0.fulfilledRemoveKeys.sorted()),"witnesses" to arr(g0.witnesses) { w->obj("key" to str(w.key),"scope" to str(w.scopeToken),"semester" to num(w.semesterId),"identity" to identityJson(w.identity),"interval" to datedJson(w.scope),"observedCourse" to num((w.owner as ObservationOwner.Stored).courseId),"observedOwner" to str(w.observedOwnerFingerprint)) }) },
        "sources" to arr(g.sources) { p->obj("family" to str(p.familyId),"position" to locatorJson(p.position),"content" to str(p.contentFingerprint)) })
    private fun readGraph(o:JsonObject)=PlannedNormalizationState(
        fList(o.list("originals").map { it.jsonObject.let { a->LogicalOriginal(a.text("family"),a.text("descriptor"),readMeeting(a.getValue("original").jsonObject),readIdentity(a.getValue("identity").jsonObject),fSet(a.list("aliases").map { v->v.jsonPrimitive.long }),fList(a.list("allocated").map(::readDated)),fList(a.list("intervals").map(::readRange)),a.getValue("targetSections").jsonPrimitive.boolean,a.getValue("source").takeUnless { v->v==JsonNull }?.let(::readLocator)) } }),
        fList(o.list("removes").map { it.jsonObject.let { r->RemoveEffect(r.text("key"),r.text("family"),readDated(r.getValue("scope")),fList(r.list("provenance").map(::readProvenance))) } }),
        fList(o.list("adds").map { it.jsonObject.let { a->AddEffect(a.text("key"),a.text("family"),readDated(a.getValue("scope")),readIdentity(a.getValue("identity").jsonObject),fList(a.list("provenance").map(::readProvenance)),a.text("owner"),a.text("exceptionKey")) } }),
        fList(o.list("groups").map { it.jsonObject.let { g->EffectGroup(g.text("key"),g.stringSet("removes"),g.stringSet("adds"),g.stringSet("fulfilled"),fList(g.list("witnesses").map { v->v.jsonObject.let { w->ExternalSatisfactionWitness(w.text("key"),w.text("scope"),w.long("semester"),readIdentity(w.getValue("identity").jsonObject),readDated(w.getValue("interval")),ObservationOwner.Stored(w.long("observedCourse")),w.text("observedOwner")) } })) } }),
        fList(o.list("sources").map { it.jsonObject.let { p->SourceContentReference(p.text("family"),readLocator(p.getValue("position")),p.text("content")) } }))
    private fun meetingJson(m:CourseMeeting)=obj("kind" to str(m.kind.name),"day" to num(m.day),"weeks" to arr(m.weeks.sorted(),::num),"declared" to arr(m.declaredWeeks.sorted(),::num),"dates" to strings(m.dates.sorted().map { it.toString() }),"sections" to arr(m.sections.sorted(),::num),"time" to (m.customTime?.let(::rangeJson) ?: JsonNull),"teacher" to nullableStr(m.teacher),"room" to nullableStr(m.room),"project" to nullableStr(m.experimentProject),"group" to nullableStr(m.group),"experiment" to JsonPrimitive(m.isExperiment),"provenance" to arr(m.provenance,::provenanceJson))
    private fun readMeeting(o:JsonObject)=immutableMeeting(CourseMeeting(day=o.int("day"),weeks=fSet(o.list("weeks").map { it.jsonPrimitive.int }),sections=fSet(o.list("sections").map { it.jsonPrimitive.int }),customTime=o.getValue("time").takeUnless { it==JsonNull }?.let(::readRange),teacher=o.nullText("teacher"),room=o.nullText("room"),kind=MeetingKind.valueOf(o.text("kind")),dates=fSet(o.list("dates").map { LocalDate.parse(it.jsonPrimitive.content) }),experimentProject=o.nullText("project"),group=o.nullText("group"),isExperiment=o.getValue("experiment").jsonPrimitive.boolean,provenance=fList(o.list("provenance").map(::readProvenance)),declaredWeeks=fSet(o.list("declared").map { it.jsonPrimitive.int })))
    private fun identityJson(i:OccurrenceIdentity)=obj("name" to str(i.name),"code" to nullableStr(i.courseCode),"class" to nullableStr(i.classCode),"teacher" to str(i.teacher),"room" to str(i.room),"project" to nullableStr(i.project),"group" to nullableStr(i.group),"experiment" to JsonPrimitive(i.isExperiment),"unknown" to strings(i.unknown.map { it.name }.sorted()))
    private fun readIdentity(o:JsonObject)=OccurrenceIdentity(o.text("name"),o.nullText("code"),o.nullText("class"),o.text("teacher"),o.text("room"),o.nullText("project"),o.nullText("group"),o.getValue("experiment").jsonPrimitive.boolean,fSet(o.list("unknown").map { ImportField.valueOf(it.jsonPrimitive.content) }))
    private fun rangeJson(r:TimeRange)=strings(listOf(r.start.toString(),r.end.toString()))
    private fun readRange(e:JsonElement):TimeRange { val a=e.jsonArray;if(a.size!=2) invalid();return TimeRange(LocalTime.parse(a[0].jsonPrimitive.content),LocalTime.parse(a[1].jsonPrimitive.content)) }
    private fun datedJson(d:DatedInterval)=obj("date" to str(d.date.toString()),"range" to rangeJson(d.range))
    private fun readDated(e:JsonElement)=e.jsonObject.let { DatedInterval(LocalDate.parse(it.text("date")),readRange(it.getValue("range"))) }
    private fun locatorJson(p:ReviewLocator)=JsonArray(listOf(num(p.sourceOrdinal),nullableNum(p.courseIndex),nullableNum(p.meetingIndex),nullableNum(p.exceptionIndex),nullableNum(p.pendingIndex),nullableNum(p.relativeIndex),nullableStr(p.field)))
    private fun readLocator(e:JsonElement):ReviewLocator { val a=e.jsonArray;if(a.size!=7) invalid();return ReviewLocator(a[0].jsonPrimitive.int,a[1].nullableInt(),a[2].nullableInt(),a[3].nullableInt(),a[4].nullableInt(),a[5].nullableInt(),a[6].takeUnless { it==JsonNull }?.jsonPrimitive?.content) }
    private fun provenanceJson(p:ScheduleProvenance)=obj("source" to nullableStr(p.sourceId),"adapter" to nullableStr(p.adapterId),"page" to nullableNum(p.page),"bounds" to (p.bounds?.let { JsonArray(listOf(JsonPrimitive(it.left),JsonPrimitive(it.top),JsonPrimitive(it.right),JsonPrimitive(it.bottom))) } ?: JsonNull),"raw" to str(p.rawText),"locator" to nullableStr(p.sourceLocator))
    private fun readProvenance(e:JsonElement)=e.jsonObject.let { p->ScheduleProvenance(p.nullText("source"),p.nullText("adapter"),p.nullLong("page")?.toInt(),p.getValue("bounds").takeUnless { it==JsonNull }?.jsonArray?.let { a->if(a.size!=4) invalid();SourceBounds(a[0].jsonPrimitive.float,a[1].jsonPrimitive.float,a[2].jsonPrimitive.float,a[3].jsonPrimitive.float) },p.text("raw"),p.nullText("locator")) }
    private fun freezeGraph(g:PlannedNormalizationState)=g.copy(originals=fList(g.originals.map { o->o.copy(original=immutableMeeting(o.original),identity=o.identity.copy(unknown=fSet(o.identity.unknown)),oldMeetingAliases=fSet(o.oldMeetingAliases),allocated=fList(o.allocated),intervals=fList(o.intervals)) }),removes=fList(g.removes.map { it.copy(provenance=fList(it.provenance)) }),adds=fList(g.adds.map { it.copy(identity=it.identity.copy(unknown=fSet(it.identity.unknown)),provenance=fList(it.provenance)) }),groups=fList(g.groups.map { it.copy(removeKeys=fSet(it.removeKeys),addKeys=fSet(it.addKeys),fulfilledRemoveKeys=fSet(it.fulfilledRemoveKeys),witnesses=fList(it.witnesses.map { w->w.copy(identity=w.identity.copy(unknown=fSet(w.identity.unknown))) })) }),sources=fList(g.sources))
    private fun obj(vararg fields:Pair<String,JsonElement>)=JsonObject(linkedMapOf(*fields))
    private fun str(s:String)=JsonPrimitive(s)
    private fun num(n:Number)=JsonPrimitive(n)
    private fun nullableStr(s:String?):JsonElement=s?.let(::str) ?: JsonNull
    private fun nullableNum(n:Number?):JsonElement=n?.let(::num) ?: JsonNull
    private fun <T> arr(values:Collection<T>,block:(T)->JsonElement)=JsonArray(values.map(block))
    private fun strings(values:Collection<String>)=arr(values,::str)
    private fun JsonObject.text(k:String)=getValue(k).jsonPrimitive.let { if(!it.isString) invalid();it.content }
    private fun JsonObject.nullText(k:String)=getValue(k).takeUnless { it==JsonNull }?.jsonPrimitive?.let { if(!it.isString) invalid();it.content }
    private fun JsonObject.int(k:String)=long(k).let { if(it !in Int.MIN_VALUE..Int.MAX_VALUE) invalid();it.toInt() }
    private fun JsonObject.long(k:String)=getValue(k).jsonPrimitive.let { if(it.isString) invalid();it.long }
    private fun JsonObject.nullLong(k:String)=getValue(k).takeUnless { it==JsonNull }?.jsonPrimitive?.let { if(it.isString) invalid();it.long }
    private fun JsonObject.list(k:String)=getValue(k).jsonArray.also { if(it.size>MAX_ITEMS) invalid() }
    private fun JsonObject.stringSet(k:String)=fSet(list(k).map { it.jsonPrimitive.content })
    private fun JsonElement.nullableInt()=takeUnless { it==JsonNull }?.jsonPrimitive?.int
}
