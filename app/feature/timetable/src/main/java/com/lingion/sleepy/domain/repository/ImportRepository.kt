package com.lingion.sleepy.domain.repository

import com.lingion.sleepy.domain.imports.*
import com.lingion.sleepy.domain.model.*
import java.time.LocalDate

data class ImportTargetView(val target: ImportTarget, val snapshot: TimetableSnapshot, val logicalBases: List<LogicalBaseReference.StoredLogicalBase>,val logicalBaseDetails:List<ImportLogicalBaseDisplay> = emptyList())
data class ImportOccurrenceView(val courseId: Long?, val source: ReviewLocator?, val date: LocalDate, val range: TimeRange,
    val courseName:String="",val teacher:String="",val room:String="",val project:String?=null,val group:String?=null)
data class ImportConflictView(val fingerprint: String, val incoming: ImportOccurrenceView, val other: ImportOccurrenceView, val overlap: TimeRange)
data class ImportProblemView(val code: String, val source: ReviewLocator?)
data class ImportChangeSummary(val newCourses:Int,val updatedCourses:Int,val removedCourses:Int,val writtenMeetingFragments:Int,val skippedSourceMeetings:Int,val duplicateSourceMeetings:Int?)

data class ImportPreviewResult(val preview: ImportPlanPreview, val conflicts: List<ImportConflictView>, val problems: List<ImportProblemView>, val unchanged: Boolean,val changes:ImportChangeSummary?=null,val sourceRelationRequired:Boolean=false) {
    val ready: Boolean get() = problems.isEmpty()
}
sealed interface ImportApplyResult {
    data class Applied(val changed: Boolean, val revision: Long): ImportApplyResult
    data class Rejected(val code: String): ImportApplyResult
}
interface ImportRepository {
    suspend fun readImportTarget(semesterId: Long): ImportTargetView
    suspend fun previewImport(reviewed: ReviewedImport, conflicts: List<ConflictReviewDecision> = reviewed.decisions.conflicts): ImportPreviewResult
    suspend fun applyImport(command: ImportApplyCommand): ImportApplyResult
}
