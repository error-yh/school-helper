/* SPDX-License-Identifier: GPL-3.0-only
 * Room aggregation adapted from Sleepy contributors, frozen
 * 3dafa110f74ac4f32464ce1efddb24197a4091d9:
 * data/jw/JwChaoxingParser.kt:132-156 (distinct nonblank rooms joined by /).
 * Unlike its whole-node/week union, require identical complete scopes here.
 */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.importing.ImportField
import com.lingion.sleepy.importing.MeetingKind

/** Candidates only: the content-bound EVIDENCE requirement authorizes conversion. */
internal fun ImportReviewSession.sameLessonSourceGroups():List<List<ReviewLocator>> {
    // Do not change exception attachment identities as a side effect of grouping.
    if(selectedExceptions.isNotEmpty() || selectedPending.isNotEmpty()) return emptyList()
    return selectedMeetings.sortedWith(compareBy({it.courseIndex},{it.meetingIndex})).filter { p ->
        val m=meeting(p)
        m.kind!=MeetingKind.UNSCHEDULED && m.exceptions.isEmpty() &&
            !m.room.isNullOrBlank() && !m.teacher.isNullOrBlank() &&
            ImportField.ROOM !in m.missingFields && ImportField.TEACHER !in m.missingFields
    }.groupBy { p ->
        val m=meeting(p)
        p.courseIndex to m.copy(room=null,sections=if(m.customTime!=null) emptySet() else m.sections,
            sources=emptyList(),warnings=emptyList(),missingFields=emptySet())
    }.values.filter { group -> group.map {meeting(it).room}.distinct().size>1 || group.map {meeting(it).sections}.distinct().size>1 }
}

/** Keeps every source mapping/provenance. Never widens weeks or effective time.
 * Exact own-clock fragments retain the union of printed section labels, including gaps. */
internal fun coalesceReviewedLessons(
    session:ImportReviewSession, members:Set<ReviewLocator>, converted:List<ReviewedMeeting>,
):List<Pair<ReviewedMeeting,List<ReviewLocator>>> {
    val candidates=session.sameLessonSourceGroups().filter {group -> group.all {it in members}}
    val bySource=converted.associateBy {it.source}
    val groupBySource=candidates.flatMap {group -> group.map {it to group}}.toMap()
    val consumed=mutableSetOf<ReviewLocator>()
    return buildList {
        for(meeting in converted) {
            if(meeting.source in consumed) continue
            val group=groupBySource[meeting.source]
            val parts=group.orEmpty().mapNotNull(bySource::get)
            if(group!=null && parts.size==group.size && parts.map {it.time}.distinct().size==1) {
                val rooms=parts.mapNotNull {it.room}.filter {it.isNotBlank()}.distinct().sorted().joinToString("/")
                add(meeting.copy(room=rooms,originalSections=fSet(parts.flatMap {it.originalSections}),provenance=fList(parts.flatMap {it.provenance}.distinct()),
                    retainedUnknownFields=fSet(parts.flatMap {it.retainedUnknownFields})) to group)
                consumed+=group
            } else {
                add(meeting to listOf(meeting.source));consumed+=meeting.source
            }
        }
    }
}
