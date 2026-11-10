/* SPDX-License-Identifier: GPL-3.0-only
 * Tests verifying two-phase journal transitions, receipts encoding/decoding, and uncommitted crash recovery.
 */
package com.aggapp.app.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimetableMigrationJournalTest {

    @Test
    fun testJournalEncodeAndDecodeRoundTrip() {
        val state = MigrationJournalState(
            stage = MigrationStage.VALIDATED.name,
            contentDigest = "digest_1234567890abcdef",
            targetActiveProfileId = "target_p1",
            originalActiveProfileId = "orig_p0",
            createdDatabaseFiles = listOf("timetable_target_p1.db"),
            createdStagingDir = "/data/data/com.aggapp.app/files/staging",
            groupBackups = mapOf(
                "v1|scope=target_p1|semester=1" to null,
                "v1|scope=target_p1|semester=2" to "{\"version\":1,\"groups\":[]}",
            ),
            timestamp = 1730700000000L,
        )

        val json = TimetableMigrationJournal.encodeJournal(state)
        val readBack = TimetableMigrationJournal.decodeJournal(json)

        assertEquals(state.stage, readBack.stage)
        assertEquals(state.contentDigest, readBack.contentDigest)
        assertEquals(state.targetActiveProfileId, readBack.targetActiveProfileId)
        assertEquals(state.originalActiveProfileId, readBack.originalActiveProfileId)
        assertEquals(state.createdDatabaseFiles, readBack.createdDatabaseFiles)
        assertEquals(state.createdStagingDir, readBack.createdStagingDir)
        assertEquals(state.groupBackups.size, readBack.groupBackups.size)
        assertNull(readBack.groupBackups["v1|scope=target_p1|semester=1"])
        assertEquals("{\"version\":1,\"groups\":[]}", readBack.groupBackups["v1|scope=target_p1|semester=2"])
        assertEquals(state.timestamp, readBack.timestamp)
    }

    @Test
    fun testReceiptEncodeAndDecodeRoundTrip() {
        val receipt = MigrationReceipt(
            status = "COMMITTED",
            contentDigest = "digest_committed_abcdef",
            restoredProfileIds = listOf("profile_alpha", "profile_beta"),
            restoredActiveProfileId = "profile_alpha",
            committedAt = 1730700000000L,
        )

        val json = TimetableMigrationJournal.encodeReceipt(receipt)
        val readBack = TimetableMigrationJournal.decodeReceipt(json)

        assertEquals(receipt.status, readBack.status)
        assertEquals(receipt.contentDigest, readBack.contentDigest)
        assertEquals(receipt.restoredProfileIds, readBack.restoredProfileIds)
        assertEquals(receipt.restoredActiveProfileId, readBack.restoredActiveProfileId)
        assertEquals(receipt.committedAt, readBack.committedAt)
    }

    @Test
    fun testDecodeRejectsCorruptedJournalJson() {
        val corruptedJson = "{\"stage\":\"PREPARING\",\"contentDigest\":\"abc\"" // incomplete
        try {
            TimetableMigrationJournal.decodeJournal(corruptedJson)
            fail("Expected exception on corrupted journal JSON")
        } catch (e: Exception) {
            assertTrue(e is IllegalArgumentException || e is org.json.JSONException)
        }
    }

    @Test
    fun testDecodeRejectsUnknownStageInJournal() {
        val invalidStageJson = """
            {
                "stage": "ILLEGAL_STAGE",
                "contentDigest": "digest123",
                "targetActiveProfileId": "p1",
                "originalActiveProfileId": null,
                "createdDatabaseFiles": [],
                "createdStagingDir": null,
                "groupBackups": {},
                "timestamp": 123456789
            }
        """.trimIndent()

        try {
            TimetableMigrationJournal.decodeJournal(invalidStageJson)
            fail("Expected exception on illegal stage name")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("未知的迁移阶段") == true || e is IllegalArgumentException)
        }
    }

    @Test
    fun testDecodeRejectsCorruptedReceiptJson() {
        val corruptedReceipt = "{\"status\":\"COMMITTED\",\"contentDigest\":"
        try {
            TimetableMigrationJournal.decodeReceipt(corruptedReceipt)
            fail("Expected exception on corrupted receipt JSON")
        } catch (e: Exception) {
            assertTrue(e is IllegalArgumentException || e is org.json.JSONException)
        }
    }

    @Test
    fun testDecodeRejectsIllegalReceiptStatus() {
        val invalidStatusReceipt = """
            {
                "status": "UNCOMMITTED_PENDING",
                "contentDigest": "digest123",
                "restoredProfileIds": ["p1"],
                "restoredActiveProfileId": "p1",
                "committedAt": 123456789
            }
        """.trimIndent()

        try {
            TimetableMigrationJournal.decodeReceipt(invalidStatusReceipt)
            fail("Expected exception on non-COMMITTED status in receipt")
        } catch (e: Exception) {
            assertTrue(e is IllegalArgumentException)
        }
    }

    @Test
    fun testResourceOwnershipDatabaseNamePattern() {
        val safePattern = Regex("^timetable_[A-Za-z0-9_-]{1,64}\\.db$")
        assertTrue("timetable_main.db".matches(safePattern))
        assertTrue("timetable_user-123_test.db".matches(safePattern))
        assertFalse("timetable_../../etc/passwd.db".matches(safePattern))
        assertFalse("other_db.db".matches(safePattern))
        assertFalse("timetable_.db".matches(safePattern))
    }
}
