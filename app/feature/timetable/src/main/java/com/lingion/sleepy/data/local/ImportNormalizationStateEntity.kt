package com.lingion.sleepy.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(tableName = "import_normalization_state", foreignKeys = [ForeignKey(
    entity = ScheduledCourseEntity::class, parentColumns = ["id"], childColumns = ["courseId"], onDelete = ForeignKey.CASCADE,
)])
data class ImportNormalizationStateEntity(@PrimaryKey val courseId: Long, val version: Int, val payload: String)
