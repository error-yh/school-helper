/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.importing.ImportField
import java.time.LocalDate

/** Pure loaded values. C3 must load and recheck these inside the write transaction. */
internal data class LoadedImportTarget(val snapshot:TimetableSnapshot,val databaseSchemaVersion:Int,
    val states:Map<Long,StoredNormalizationState> = emptyMap())

/** Matching uses effective metadata and explicit unknown state; unknown is never a wildcard. */
internal data class OccurrenceIdentity(val name:String,val courseCode:String?,val classCode:String?,
    val teacher:String,val room:String,val project:String?,val group:String?,val isExperiment:Boolean,
    val unknown:Set<ImportField> = emptySet())
internal data class DatedInterval(val date:LocalDate,val range:TimeRange)
internal data class OccurrenceSide(val courseId:Long?,val source:ReviewLocator?,val familyId:String,
    val identity:OccurrenceIdentity,val date:LocalDate,val range:TimeRange)
internal data class ActualImportConflict(val fingerprint:String,val incoming:OccurrenceSide,val other:OccurrenceSide,val overlap:TimeRange)
internal data class OccurrencePlanProblem(val code:String,val source:ReviewLocator? = null)
internal enum class FragmentRole { BASE_FRAGMENT }
internal data class PlannedFragment(val key:String,val familyId:String,val meeting:CourseMeeting,val role:FragmentRole = FragmentRole.BASE_FRAGMENT)
internal data class PlannedCourse(val key:String,val existingCourseId:Long?,val draft:CourseDraft,
    val fragments:List<PlannedFragment>,val state:PlannedNormalizationState)

/** No public constructor or mutable canApply permit. All outputs are immutable snapshots. */
internal class ImportOccurrencePlan internal constructor(val preview:ImportPlanPreview,
    val courses:List<PlannedCourse>,val removedCourseIds:Set<Long>,val conflicts:List<ActualImportConflict>,
    val problems:List<OccurrencePlanProblem>,val unchanged:Boolean,
    /** The reviewer can still answer the whole-batch source relation; it is not a plan permit. */
    val sourceRelationRequired:Boolean = false) {
    val ready:Boolean get() = problems.isEmpty()
}

internal data class LogicalOriginal(val familyId:String,val descriptorFingerprint:String,val original:CourseMeeting,
    val identity:OccurrenceIdentity,val oldMeetingAliases:Set<Long>,val allocated:List<DatedInterval>,
    val intervals:List<TimeRange>,val sectionsUseTarget:Boolean = true,val source:ReviewLocator? = null)
internal data class RemoveEffect(val key:String,val familyId:String,val scope:DatedInterval,val provenance:List<ScheduleProvenance>)
internal data class AddEffect(val key:String,val familyId:String,val scope:DatedInterval,val identity:OccurrenceIdentity,
    val provenance:List<ScheduleProvenance>,val ownerFragmentKey:String,val exceptionKey:String)
/** A planned observation is resolved to an actual course ID only during hydration. */
internal sealed interface ObservationOwner {
    data class Stored(val courseId:Long):ObservationOwner
    data class Planned(val courseKey:String):ObservationOwner
}
/** Observations carry no live ownership, FK or replay obligation. */
internal data class ExternalSatisfactionWitness(val key:String,val scopeToken:String,val semesterId:Long,
    val identity:OccurrenceIdentity,val scope:DatedInterval,val owner:ObservationOwner,val observedOwnerFingerprint:String)
internal data class EffectGroup(val key:String,val removeKeys:Set<String>,val addKeys:Set<String>,
    val fulfilledRemoveKeys:Set<String> = emptySet(),val witnesses:List<ExternalSatisfactionWitness> = emptyList())
/** Prior accepted reviewed-input alias, distinct from actual stored provenance positions below. */
internal data class SourceContentReference(val familyId:String,val position:ReviewLocator,val contentFingerprint:String)
internal data class PlannedNormalizationState(val originals:List<LogicalOriginal>,val removes:List<RemoveEffect>,
    val adds:List<AddEffect>,val groups:List<EffectGroup>,val sources:List<SourceContentReference>)
internal data class OwnedFragment(val key:String,val familyId:String,val meetingId:Long,val shapeFingerprint:String)
internal data class OwnedAdd(val key:String,val fragmentKey:String,val exceptionId:Long,val shapeFingerprint:String)
internal data class StoredSourceReference(val courseId:Long,val meetingId:Long?,val exceptionId:Long?,val position:Int,val contentFingerprint:String)
/** Only hydrated after C3 has assigned real IDs. Never a CourseDraft or external import field. */
internal data class StoredNormalizationState(val version:Int,val scopeToken:String,val semesterId:Long,val courseId:Long,
    val graph:PlannedNormalizationState,val fragments:List<OwnedFragment>,val ownedAdds:List<OwnedAdd>,val sourceReferences:List<StoredSourceReference>)

internal class ImportPlanningLimit : IllegalArgumentException("OCCURRENCE_LIMIT")
internal class OccurrenceBudget {
    private var atoms=0;private var comparisons=0;private val dates=hashSetOf<LocalDate>()
    fun atom(date:LocalDate) { if(++atoms>200_000 || (dates.add(date)&&dates.size>10_000)) throw ImportPlanningLimit() }
    fun compare() { if(++comparisons>200_000) throw ImportPlanningLimit() }
}
