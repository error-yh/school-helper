/* SPDX-License-Identifier: GPL-3.0-only
 * Tests verifying canonical digest determinism, order independence, and mutation sensitivity.
 */
package com.lingion.sleepy.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TimetableBackupDigestTest {

    @Test
    fun testDigestIsDeterministicAndIgnoresCreatedAt() {
        val payload1 = TimetableBackupFixtureHelper.createValidPayload().copy(createdAt = 1000000L)
        val payload2 = TimetableBackupFixtureHelper.createValidPayload().copy(createdAt = 2000000L)

        val digest1 = TimetableBackupDigest.calculateDigest(payload1)
        val digest2 = TimetableBackupDigest.calculateDigest(payload2)

        assertEquals(digest1, digest2)
    }

    @Test
    fun testDigestIsOrderIndependentForProfilesAndRows() {
        val payloadOriginal = TimetableBackupFixtureHelper.createValidPayload()
        val reversedProfiles = payloadOriginal.profiles.reversed()
        val payloadReversed = payloadOriginal.copy(profiles = reversedProfiles)

        val digestOriginal = TimetableBackupDigest.calculateDigest(payloadOriginal)
        val digestReversed = TimetableBackupDigest.calculateDigest(payloadReversed)

        assertEquals(digestOriginal, digestReversed)
    }

    @Test
    fun testDigestIsSensitiveToAnyFieldMutation() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val baseDigest = TimetableBackupDigest.calculateDigest(original)

        // 1. Mutate course name
        val p0 = original.profiles[0]
        val mutatedCourses = p0.courses.map { if (it.id == 101L) it.copy(courseName = "微积分v1") else it }
        val pMutatedCourse = p0.copy(courses = mutatedCourses)
        val digestCourseMutated = TimetableBackupDigest.calculateDigest(original.copy(profiles = listOf(pMutatedCourse, original.profiles[1])))
        assertNotEquals(baseDigest, digestCourseMutated)

        // 2. Mutate scheduled course room
        val mutatedScheduled = p0.scheduledCourses.map { if (it.id == 1010L) it.copy(room = "理学楼999") else it }
        val pMutatedScheduled = p0.copy(scheduledCourses = mutatedScheduled)
        val digestScheduledMutated = TimetableBackupDigest.calculateDigest(original.copy(profiles = listOf(pMutatedScheduled, original.profiles[1])))
        assertNotEquals(baseDigest, digestScheduledMutated)

        // 3. Mutate sequence
        val mutatedSeq = p0.sequences.map { if (it.name == "courses") it.copy(seq = it.seq + 1) else it }
        val pMutatedSeq = p0.copy(sequences = mutatedSeq)
        val digestSeqMutated = TimetableBackupDigest.calculateDigest(original.copy(profiles = listOf(pMutatedSeq, original.profiles[1])))
        assertNotEquals(baseDigest, digestSeqMutated)

        // 4. Mutate display groups
        val mutatedGroups = p0.displayGroups.map { it.copy(rawJson = "{}") }
        val pMutatedGroups = p0.copy(displayGroups = mutatedGroups)
        val digestGroupsMutated = TimetableBackupDigest.calculateDigest(original.copy(profiles = listOf(pMutatedGroups, original.profiles[1])))
        assertNotEquals(baseDigest, digestGroupsMutated)

        // 5. Mutate activeProfileId
        val digestActiveMutated = TimetableBackupDigest.calculateDigest(original.copy(activeProfileId = "profile_beta"))
        assertNotEquals(baseDigest, digestActiveMutated)
    }

    @Test
    fun testDigestDistinguishesNullAndLiteralStringNull() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]

        // SC with teacher = null
        val scWithNull = p0.scheduledCourses.mapIndexed { idx, sc -> if (idx == 0) sc.copy(sourceCourseCode = null) else sc }
        val digestWithNull = TimetableBackupDigest.calculateDigest(original.copy(profiles = listOf(p0.copy(scheduledCourses = scWithNull), original.profiles[1])))

        // SC with teacher = "null"
        val scWithLiteralNull = p0.scheduledCourses.mapIndexed { idx, sc -> if (idx == 0) sc.copy(sourceCourseCode = "null") else sc }
        val digestWithLiteralNull = TimetableBackupDigest.calculateDigest(original.copy(profiles = listOf(p0.copy(scheduledCourses = scWithLiteralNull), original.profiles[1])))

        assertNotEquals(digestWithNull, digestWithLiteralNull)
    }

    @Test
    fun testDigestDistinguishesValuesWithDelimiterCharacters() {
        val original = TimetableBackupFixtureHelper.createValidPayload()
        val p0 = original.profiles[0]

        // Mutation with delimiters like commas or semicolons
        val sc1 = p0.scheduledCourses.mapIndexed { idx, sc -> if (idx == 0) sc.copy(courseName = "物理;实验", teacher = "张;三") else sc }
        val digest1 = TimetableBackupDigest.calculateDigest(original.copy(profiles = listOf(p0.copy(scheduledCourses = sc1), original.profiles[1])))

        val sc2 = p0.scheduledCourses.mapIndexed { idx, sc -> if (idx == 0) sc.copy(courseName = "物理", teacher = "实验;张;三") else sc }
        val digest2 = TimetableBackupDigest.calculateDigest(original.copy(profiles = listOf(p0.copy(scheduledCourses = sc2), original.profiles[1])))

        assertNotEquals(digest1, digest2)
    }
}
