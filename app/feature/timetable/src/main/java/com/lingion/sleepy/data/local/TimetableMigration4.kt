package com.lingion.sleepy.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add only the private import state; existing rows and sequences are untouched. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `import_normalization_state` (`courseId` INTEGER NOT NULL, `version` INTEGER NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`courseId`), FOREIGN KEY(`courseId`) REFERENCES `scheduled_courses`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
    }
}
