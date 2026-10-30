package com.lingion.sleepy.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Unchanged v1 schema retained as a migration archive. No application DAO writes this table. */
@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val courseName: String,
    val day: Int,
    val startNode: Int,
    val step: Int,
    val startWeek: Int,
    val endWeek: Int,
    val type: Int = 0,
)
