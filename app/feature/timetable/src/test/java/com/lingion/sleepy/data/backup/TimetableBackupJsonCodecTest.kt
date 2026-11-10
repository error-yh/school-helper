/* SPDX-License-Identifier: GPL-3.0-only
 * Tests verifying strict JSON bounds, duplicate key rejection, and zero tolerance for unknown fields.
 */
package com.lingion.sleepy.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TimetableBackupJsonCodecTest {

    @Test
    fun testEncodeAndDecodeRoundTrip_matchesExactly() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val json = TimetableBackupJsonCodec.encode(original)
        assertNotNull(json)
        assertTrue(json.isNotBlank())

        val decoded = TimetableBackupJsonCodec.decode(json)
        assertEquals(original.formatVersion, decoded.formatVersion)
        assertEquals(original.roomSchemaVersion, decoded.roomSchemaVersion)
        assertEquals(original.schemaIdentityHash, decoded.schemaIdentityHash)
        assertEquals(original.sourceAppId, decoded.sourceAppId)
        assertEquals(original.sourceVersionCode, decoded.sourceVersionCode)
        assertEquals(original.createdAt, decoded.createdAt)
        assertEquals(original.activeProfileId, decoded.activeProfileId)
        assertEquals(original.contentDigest, decoded.contentDigest)
        assertEquals(original.profiles.size, decoded.profiles.size)

        for (i in original.profiles.indices) {
            val pOrig = original.profiles[i]
            val pDec = decoded.profiles[i]
            assertEquals(pOrig.profileId, pDec.profileId)
            assertEquals(pOrig.courses, pDec.courses)
            assertEquals(pOrig.semesters, pDec.semesters)
            assertEquals(pOrig.periods, pDec.periods)
            assertEquals(pOrig.scheduledCourses, pDec.scheduledCourses)
            assertEquals(pOrig.courseMeetings, pDec.courseMeetings)
            assertEquals(pOrig.meetingWeeks, pDec.meetingWeeks)
            assertEquals(pOrig.meetingSections, pDec.meetingSections)
            assertEquals(pOrig.meetingDates, pDec.meetingDates)
            assertEquals(pOrig.meetingDeclaredWeeks, pDec.meetingDeclaredWeeks)
            assertEquals(pOrig.meetingExceptions, pDec.meetingExceptions)
            assertEquals(pOrig.exceptionSections, pDec.exceptionSections)
            assertEquals(pOrig.scheduleProvenance, pDec.scheduleProvenance)
            assertEquals(pOrig.importNormalizationState, pDec.importNormalizationState)
            assertEquals(pOrig.noticeAdjustmentEvents, pDec.noticeAdjustmentEvents)
            assertEquals(pOrig.sequences, pDec.sequences)
            assertEquals(pOrig.displayGroups, pDec.displayGroups)
        }
    }

    @Test
    fun testDecodeRejectsDuplicateKeys() {
        val jsonWithDuplicateKey = """
        {
            "formatVersion": 1,
            "formatVersion": 1,
            "roomSchemaVersion": 5,
            "schemaIdentityHash": "$TIMETABLE_BACKUP_SCHEMA_IDENTITY_HASH",
            "sourceAppId": "test",
            "sourceVersionCode": 2,
            "createdAt": 1000,
            "activeProfileId": "p1",
            "profiles": []
        }
        """.trimIndent()

        try {
            TimetableBackupJsonCodec.decode(jsonWithDuplicateKey)
            fail("Expected rejection on duplicate JSON key")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("重复的JSON键") == true)
        }
    }

    @Test
    fun testDecodeRejectsUnknownFields() {
        val jsonWithUnknownField = """
        {
            "formatVersion": 1,
            "roomSchemaVersion": 5,
            "schemaIdentityHash": "$TIMETABLE_BACKUP_SCHEMA_IDENTITY_HASH",
            "sourceAppId": "test",
            "sourceVersionCode": 2,
            "createdAt": 1000,
            "activeProfileId": "p1",
            "profiles": [],
            "unknownSecretField": "leaked_token"
        }
        """.trimIndent()

        try {
            TimetableBackupJsonCodec.decode(jsonWithUnknownField)
            fail("Expected rejection on unknown JSON field")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("未知字段") == true)
        }
    }

    @Test
    fun testDecodeRejectsExceedingDepthLimit() {
        val openBrackets = "[".repeat(66)
        val closeBrackets = "]".repeat(66)
        val deepJson = "{\"formatVersion\":1,\"profiles\":$openBrackets 1 $closeBrackets}"

        try {
            TimetableBackupJsonCodec.validateStrictJsonSyntax(deepJson)
            fail("Expected rejection on nesting depth > 64")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("嵌套深度超过") == true)
        }
    }

    @Test
    fun testDecodeRejectsExceedingProfileLimit() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val tooManyProfiles = (1..33).map { idx ->
            TimetableBackupFixtureHelper.createProfile("profile_$idx", idx.toLong())
        }
        val overloaded = original.copy(profiles = tooManyProfiles)

        try {
            TimetableBackupJsonCodec.encode(overloaded)
            fail("Expected rejection on > 32 profiles during encode")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("Profile数量超过") == true)
        }
    }

    @Test
    fun testDecodeRejectsMismatchedSchemaVersion() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val badVersion = original.copy(roomSchemaVersion = 4)
        val json = TimetableBackupJsonCodec.encode(badVersion)

        try {
            TimetableBackupJsonCodec.decode(json)
            fail("Expected rejection on roomSchemaVersion != 5")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("不支持的RoomSchema版本") == true)
        }
    }

    @Test
    fun testDecodeRejectsUnknownFieldInsideRow() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val json = TimetableBackupJsonCodec.encode(original)
        // Inject an unknown field into courses row
        val injectedJson = json.replace("\"courseName\":", "\"evilField\":\"injected\",\"courseName\":")

        try {
            TimetableBackupJsonCodec.decode(injectedJson)
            fail("Expected rejection on unknown field inside row")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("包含未知字段: evilField") == true)
        }
    }

    @Test
    fun testDecodeRejectsNumericFieldAsString() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val json = TimetableBackupJsonCodec.encode(original)
        // Inject string for formatVersion: "formatVersion":1 -> "formatVersion":"1"
        val injectedJson = json.replace("\"formatVersion\":1", "\"formatVersion\":\"1\"")

        try {
            TimetableBackupJsonCodec.decode(injectedJson)
            fail("Expected rejection on numeric field provided as string")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("不能为字符串") == true)
        }
    }
}
