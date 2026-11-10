/* SPDX-License-Identifier: GPL-3.0-only
 * Core verification test covering full round-trip, corruption rejection, and duplicate idempotency.
 */
package com.lingion.sleepy.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimetableBackupRedReproTest {

    @Test
    fun testFullRoundTrip_reproClosed() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        TimetableBackupValidator.validate(original)

        val json = TimetableBackupJsonCodec.encode(original)
        assertNotNull(json)

        val decoded = TimetableBackupJsonCodec.decode(json)
        TimetableBackupValidator.validate(decoded)

        assertEquals(original.contentDigest, decoded.contentDigest)
        assertEquals(original.profiles.size, decoded.profiles.size)
        assertEquals(original.totalRowCount(), decoded.totalRowCount())
    }

    @Test
    fun testCorruptedData_reproClosed() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]
        val corruptedSeqs = p0.sequences.map { if (it.name == "courses") it.copy(seq = 0L) else it }
        val corruptedProfile = p0.copy(sequences = corruptedSeqs)
        val corruptedPayload = original.copy(profiles = listOf(corruptedProfile, original.profiles[1]))

        try {
            TimetableBackupValidator.validate(corruptedPayload)
            fail("Corrupted payload must be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("自增序号无效") == true)
        }
    }

    @Test
    fun testDuplicateImportIdempotent_reproClosed() {
        val payload1 = TimetableBackupFixtureHelper.createValidPayload()
        val payload2 = payload1.copy(createdAt = System.currentTimeMillis() + 999999L)

        val digest1 = TimetableBackupDigest.calculateDigest(payload1)
        val digest2 = TimetableBackupDigest.calculateDigest(payload2)

        assertEquals(digest1, digest2)
    }
}
