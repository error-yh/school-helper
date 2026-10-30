package com.lingion.sleepy.domain.repository

import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimetableSnapshot
import kotlinx.coroutines.flow.Flow

interface ScheduleRepository {
    fun observeTimetable(): Flow<TimetableSnapshot>
    suspend fun saveCourse(draft: CourseDraft, id: Long? = null): Long
    suspend fun deleteCourse(id: Long)
    suspend fun updateSettings(settings: SemesterSettings, periods: List<Period>)
}
