/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Add only the notice adjustment journal. No existing table, row, sequence, profile or import
 * state is touched; a failure keeps the v4 archive/version intact because Room runs this inside
 * its upgrade transaction.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `notice_adjustment_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`requestKey` TEXT NOT NULL, `semanticKey` TEXT NOT NULL, `confirmDigest` TEXT NOT NULL, " +
                "`scopeToken` TEXT NOT NULL, `semesterId` INTEGER NOT NULL, `kind` TEXT NOT NULL, " +
                "`courseId` INTEGER, `courseTitle` TEXT, `logicalBaseId` TEXT, `structureFingerprint` TEXT, " +
                "`meetingId` INTEGER, `originalEpochDay` INTEGER, `targetEpochDay` INTEGER, " +
                "`beforeJson` TEXT, `afterJson` TEXT, `afterFingerprint` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, " +
                "`sourceText` TEXT, `sourceDigest` TEXT NOT NULL, `dateBasisJson` TEXT, " +
                "`appliedRevision` INTEGER NOT NULL, `undoneRevision` INTEGER, `state` TEXT NOT NULL, `activeKey` TEXT, " +
                "FOREIGN KEY(`courseId`) REFERENCES `scheduled_courses`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL, " +
                "FOREIGN KEY(`meetingId`) REFERENCES `course_meetings`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_notice_adjustment_events_scopeToken_semesterId_activeKey` " +
                "ON `notice_adjustment_events` (`scopeToken`, `semesterId`, `activeKey`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_notice_adjustment_events_scopeToken_semesterId_courseId` " +
                "ON `notice_adjustment_events` (`scopeToken`, `semesterId`, `courseId`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_notice_adjustment_events_semesterId_appliedRevision` " +
                "ON `notice_adjustment_events` (`semesterId`, `appliedRevision`)"
        )
    }
}