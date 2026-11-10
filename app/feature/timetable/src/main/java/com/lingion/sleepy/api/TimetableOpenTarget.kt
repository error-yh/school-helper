package com.lingion.sleepy.api

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.CourseOccurrence
import com.lingion.sleepy.domain.model.TimetableSnapshot
import java.time.LocalDate

data class TimetableOpenTarget(
    val date: LocalDate? = null,
    val occurrence: TimetableOccurrenceKey? = null,
)

data class TimetableOccurrenceKey(
    val scope: String,
    val courseId: Long,
    val meetingId: Long,
    val exceptionId: Long?,
) {
    fun resolve(snapshot: TimetableSnapshot, date: LocalDate): CourseOccurrence? {
        if (snapshot.scopeToken != scope) return null
        val course = snapshot.courses.singleOrNull { it.id == courseId } ?: return null
        return TimetableRules.occurrencesOn(snapshot.copy(courses = listOf(course)), date).singleOrNull {
            it.meeting.id == meetingId && it.exception?.id == exceptionId &&
                it.timeRanges.isNotEmpty() && it.missingSections.isEmpty()
        }
    }
}
