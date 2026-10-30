package com.lingion.sleepy.api

import com.lingion.sleepy.domain.repository.ScheduleRepository
import com.lingion.sleepy.domain.repository.ImportRepository

data class TimetableDependencies(val repository: ScheduleRepository, val imports: ImportRepository? = null)
