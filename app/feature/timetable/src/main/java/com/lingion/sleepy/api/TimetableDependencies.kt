package com.lingion.sleepy.api

import com.lingion.sleepy.domain.repository.ScheduleRepository
import com.lingion.sleepy.domain.repository.ImportRepository
import com.lingion.sleepy.domain.repository.NoticeAdjustmentRepository

data class TimetableDependencies(
    val repository: ScheduleRepository,
    val imports: ImportRepository? = null,
    val noticeAdjustments: NoticeAdjustmentRepository? = null,
)
