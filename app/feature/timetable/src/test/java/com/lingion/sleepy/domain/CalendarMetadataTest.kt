package com.lingion.sleepy.domain

import com.lingion.sleepy.domain.model.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CalendarMetadataTest {
    private fun source(length: Int) = ScheduleProvenance(rawText = "x".repeat(length))
    private fun draft() = CourseDraft("Synthetic legacy", meetings = listOf(
        CourseMeeting(day = 1, weeks = emptySet(), sections = setOf(999)),
    ))

    @Test fun totalRawTextIncludesCourseMeetingAndExceptionSources() {
        val excerpt = source(400_000)
        val meeting = draft().meetings.single().copy(provenance = listOf(excerpt), exceptions = listOf(
            MeetingException(kind = ExceptionKind.EXTRA, targetDate = LocalDate.of(2027, 2, 3), provenance = listOf(excerpt)),
        ))
        assertTrue(TimetableRules.validateMetadata(draft().copy(provenance = listOf(excerpt), meetings = listOf(meeting))).isNotEmpty())
    }

    @Test fun excerptCountIncludesCourseMeetingAndExceptionSources() {
        val excerpt = source(0)
        val meeting = draft().meetings.single().copy(provenance = List(5_000) { excerpt }, exceptions = listOf(
            MeetingException(kind = ExceptionKind.EXTRA, targetDate = LocalDate.of(2027, 2, 3), provenance = listOf(excerpt)),
        ))
        assertTrue(TimetableRules.validateMetadata(draft().copy(provenance = List(5_000) { excerpt }, meetings = listOf(meeting))).isNotEmpty())
    }

    @Test fun sourceCourseCodeRejectsBeyond2048UnicodeUnits() {
        assertTrue(TimetableRules.validateMetadata(draft().copy(sourceCourseCode = "x".repeat(2049))).isNotEmpty())
    }

    @Test fun sourceClassCodeRejectsBeyond2048UnicodeUnits() {
        assertTrue(TimetableRules.validateMetadata(draft().copy(sourceClassCode = "x".repeat(2049))).isNotEmpty())
    }

    @Test fun exactCapacityAndSourceCodeLimitsRemainReadable() {
        val bounded = draft().copy(sourceCourseCode = "x".repeat(2048), sourceClassCode = "x".repeat(2048),
            provenance = listOf(source(500_000), source(500_000)) + List(9_998) { source(0) })
        assertTrue(TimetableRules.validateMetadata(bounded).isEmpty())
    }

    @Test fun metadataValidationPreservesLegacyEmptyWeeksAndUnknownBaseSections() {
        assertTrue(TimetableRules.validateMetadata(draft()).isEmpty())
    }
}
