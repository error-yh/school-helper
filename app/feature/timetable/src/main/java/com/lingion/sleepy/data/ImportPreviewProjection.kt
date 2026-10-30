/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.data

import com.lingion.sleepy.domain.imports.*
import com.lingion.sleepy.domain.repository.*

/** Project the actual transaction-loaded plan; the UI must not replan without persisted state. */
internal fun projectImportPreview(plan:ImportOccurrencePlan,decisions:List<ConflictReviewDecision>):ImportPreviewResult {
    fun occurrence(side:OccurrenceSide)=ImportOccurrenceView(side.courseId,side.source,side.date,side.range,
        side.identity.name,side.identity.teacher,side.identity.room,side.identity.project,side.identity.group)
    val skipKeys=decisions.filter { it.choice==ConflictReviewChoice.SKIP }.map { it.conflictFingerprint }.toSet()
    val skipped=plan.conflicts.filter { it.fingerprint in skipKeys }.mapNotNull { it.incoming.source }.distinct().size
    val changes=ImportChangeSummary(plan.courses.count { it.existingCourseId==null },plan.courses.count { it.existingCourseId!=null },
        plan.removedCourseIds.size,plan.courses.sumOf { it.fragments.size },skipped,
        if(plan.unchanged && skipped==0) plan.preview.counts.selectedSourceMeetings else null)
    return ImportPreviewResult(plan.preview,plan.conflicts.map { ImportConflictView(it.fingerprint,occurrence(it.incoming),occurrence(it.other),it.overlap) },
        plan.problems.map { ImportProblemView(it.code,it.source) },plan.unchanged,changes,plan.sourceRelationRequired)
}
