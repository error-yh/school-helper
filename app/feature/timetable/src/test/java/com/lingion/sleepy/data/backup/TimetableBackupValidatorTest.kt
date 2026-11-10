/* SPDX-License-Identifier: GPL-3.0-only
 * Tests verifying comprehensive semantic validation across Schema 5 tables, foreign keys, and sequences.
 */
package com.lingion.sleepy.data.backup

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import com.lingion.sleepy.importing.NoticeIntentKind
import com.lingion.sleepy.ui.computeCourseDisplaySignature

class TimetableBackupValidatorTest {

    @Test
    fun testValidateValidPayload_passes() {
        val payload = TimetableBackupFixtureHelper.createValidPayload()
        TimetableBackupValidator.validate(payload)
    }

    @Test
    fun testValidateRejectsDuplicateProfileId() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val duplicateProfiles = listOf(original.profiles[0], original.profiles[0].copy())
        val invalidPayload = original.copy(profiles = duplicateProfiles)

        try {
            TimetableBackupValidator.validate(invalidPayload)
            fail("Expected rejection on duplicate profile ID")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("存在重复的Profile ID") == true)
        }
    }

    @Test
    fun testValidateRejectsMissingActiveProfile() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val invalidPayload = original.copy(activeProfileId = "non_existent_profile")

        try {
            TimetableBackupValidator.validate(invalidPayload)
            fail("Expected rejection when active profile is missing")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("未包含在导出的Profile列表中") == true)
        }
    }

    @Test
    fun testValidateRejectsDanglingForeignKey() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]
        // Dangling foreign key in periods (modify single period to avoid primary key collision)
        val badPeriods = p0.periods.mapIndexed { idx, p -> if (idx == 0) p.copy(semesterId = 999999L) else p }
        val badProfile = p0.copy(periods = badPeriods)
        val invalidPayload = original.copy(profiles = listOf(badProfile, original.profiles[1]))

        try {
            TimetableBackupValidator.validate(invalidPayload)
            fail("Expected rejection on dangling foreign key")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("外键悬空") == true)
        }
    }

    @Test
    fun testValidateRejectsSequenceLessThanMaxId() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]
        // courses max id is 102, set seq to 10
        val badSeqs = p0.sequences.map { if (it.name == "courses") it.copy(seq = 10L) else it }
        val badProfile = p0.copy(sequences = badSeqs)
        val invalidPayload = original.copy(profiles = listOf(badProfile, original.profiles[1]))

        try {
            TimetableBackupValidator.validate(invalidPayload)
            fail("Expected rejection when sequence is less than max ID")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("自增序号无效") == true)
        }
    }

    @Test
    fun testValidateRejectsMismatchedDisplayGroupSignature() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]
        // Display group referencing non-existent course id 88888
        val badJson = """{"version":1,"groups":[{"id":"g1","name":"组","members":[{"courseId":88888,"signature":"SIG1"},{"courseId":${p0.scheduledCourses[0].id},"signature":"SIG2"}]}]}"""
        val badGroups = listOf(CourseDisplayGroupBackupDto(p0.displayGroups[0].storageKey, badJson))
        val badProfile = p0.copy(displayGroups = badGroups)
        val invalidPayload = original.copy(profiles = listOf(badProfile, original.profiles[1]))

        try {
            TimetableBackupValidator.validate(invalidPayload)
            fail("Expected rejection on display group referencing non-existent course ID")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("展示分组引用了不存在的课程ID") == true)
        }
    }

    @Test
    fun testValidateRejectsCorruptedNormalizationStateJson() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]
        val badState = p0.importNormalizationState.map { it.copy(payload = "not a valid json") }
        val badProfile = p0.copy(importNormalizationState = badState)

        try {
            TimetableBackupValidator.validate(original.copy(profiles = listOf(badProfile, original.profiles[1])))
            fail("Expected rejection on corrupted normalization payload")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("不是有效的JSON") == true)
        }
    }

    @Test
    fun testValidateRejectsNoticeAdjustmentDuplicateActiveKey() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]
        // Create duplicate activeKey for APPLIED events
        val badEvents = p0.noticeAdjustmentEvents + p0.noticeAdjustmentEvents[0].copy(id = 999999999L)
        val badProfile = p0.copy(noticeAdjustmentEvents = badEvents)

        try {
            TimetableBackupValidator.validate(original.copy(profiles = listOf(badProfile, original.profiles[1])))
            fail("Expected rejection on duplicate activeKey in APPLIED events")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("重复的活动事件 activeKey") == true)
        }
    }

    @Test
    fun testValidateRejectsNoticeAdjustmentScopeTokenMismatch() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]
        val badEvents = p0.noticeAdjustmentEvents.map { it.copy(scopeToken = "wrong_scope") }
        val badProfile = p0.copy(noticeAdjustmentEvents = badEvents)

        try {
            TimetableBackupValidator.validate(original.copy(profiles = listOf(badProfile, original.profiles[1])))
            fail("Expected rejection on scopeToken mismatch")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("与 Profile ID") == true)
        }
    }

    @Test
    fun testValidateRejectsIllegalSequenceTable() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]
        val badSeqs = p0.sequences + SqliteSequenceBackupDto(name = "evil_table", seq = 100L)
        val badProfile = p0.copy(sequences = badSeqs)

        try {
            TimetableBackupValidator.validate(original.copy(profiles = listOf(badProfile, original.profiles[1])))
            fail("Expected rejection on illegal sequence table")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("发现未知或非白名单的sqlite_sequence表名") == true)
        }
    }

    @Test
    fun testValidateRejectsNoticeAdjustmentIllegalKind() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]
        val badEvents = p0.noticeAdjustmentEvents.mapIndexed { idx, ev ->
            if (idx == 0) ev.copy(kind = "MALICIOUS_OR_UNSUPPORTED_KIND") else ev
        }
        val badProfile = p0.copy(noticeAdjustmentEvents = badEvents)

        try {
            TimetableBackupValidator.validate(original.copy(profiles = listOf(badProfile, original.profiles[1])))
            fail("Expected rejection on unsupported notice adjustment kind")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("notice_adjustment_events kind 无效") == true)
        }
    }

    @Test
    fun testValidateRejectsTamperedDisplayGroupSignature() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]
        // Tamper signature
        val badJson = """{"version":1,"groups":[{"id":"g1","name":"组","members":[{"courseId":${p0.scheduledCourses[0].id},"signature":"TAMPERED_SIG"},{"courseId":${p0.scheduledCourses[1].id},"signature":"${TimetableBackupValidator.computeCourseSignature(p0.scheduledCourses[1])}"}]}]}"""
        val badGroups = listOf(CourseDisplayGroupBackupDto(p0.displayGroups[0].storageKey, badJson))
        val badProfile = p0.copy(displayGroups = badGroups)

        try {
            TimetableBackupValidator.validate(original.copy(profiles = listOf(badProfile, original.profiles[1])))
            fail("Expected rejection on tampered display group signature")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("签名不匹配") == true)
        }
    }

    @Test
    fun testValidateRejectsDuplicateDisplayGroupStorageKey() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]
        val dupGroups = p0.displayGroups + p0.displayGroups[0].copy()
        val badProfile = p0.copy(displayGroups = dupGroups)

        try {
            TimetableBackupValidator.validate(original.copy(profiles = listOf(badProfile, original.profiles[1])))
            fail("Expected rejection on duplicate storageKey")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("存在重复的展示分组storageKey") == true)
        }
    }

    @Test
    fun testValidateAcceptsAllProductionNoticeIntentKinds() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]
        val eventTemplate = p0.noticeAdjustmentEvents[0]
        
        val allKindsEvents = NoticeIntentKind.entries.mapIndexed { idx, kind ->
            eventTemplate.copy(
                id = 9000L + idx,
                requestKey = "req_kind_${kind.name}",
                semanticKey = "sem_kind_${kind.name}",
                kind = kind.name,
                activeKey = "active_kind_${kind.name}",
            )
        }
        val validPayload = original.copy(profiles = listOf(p0.copy(noticeAdjustmentEvents = allKindsEvents), original.profiles[1]))
        TimetableBackupValidator.validate(validPayload)
    }

    @Test
    fun testValidateRejectsIllegalNoticeIntentKind() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]
        val badEvents = listOf(p0.noticeAdjustmentEvents[0].copy(kind = "INVALID_EVENT_KIND_XYZ"))
        val invalidPayload = original.copy(profiles = listOf(p0.copy(noticeAdjustmentEvents = badEvents), original.profiles[1]))

        try {
            TimetableBackupValidator.validate(invalidPayload)
            fail("Expected rejection on invalid notice event kind")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("notice_adjustment_events kind 无效") == true)
        }
    }

    @Test
    fun testComputeCourseSignatureMatchesProductionAndHashesLongIdentities() {
        val shortCourse = ScheduledCourseBackupDto(
            id = 1L,
            semesterId = 1L,
            courseName = "普通物理",
            teacher = "T",
            room = "R",
            color = 0xFF112233L,
            sourceCourseCode = "PHY101",
            sourceClassCode = "C1",
        )
        val shortSig = TimetableBackupValidator.computeCourseSignature(shortCourse)
        val prodShortSig = computeCourseDisplaySignature(shortCourse.courseName, shortCourse.sourceCourseCode, shortCourse.sourceClassCode)
        org.junit.Assert.assertEquals(prodShortSig, shortSig)
        org.junit.Assert.assertEquals("普通物理|phy101|c1", shortSig)

        val longName = "极长课程名称".repeat(70) // length > 400
        val longCourse = shortCourse.copy(courseName = longName)
        val longSig = TimetableBackupValidator.computeCourseSignature(longCourse)
        val prodLongSig = computeCourseDisplaySignature(longCourse.courseName, longCourse.sourceCourseCode, longCourse.sourceClassCode)
        org.junit.Assert.assertEquals(prodLongSig, longSig)
        assertTrue(longSig.startsWith("d1:"))
    }
}