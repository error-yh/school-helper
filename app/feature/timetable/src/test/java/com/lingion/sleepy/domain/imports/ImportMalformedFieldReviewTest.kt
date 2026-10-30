/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class ImportMalformedFieldReviewTest {
    private val first = ReviewLocator(courseIndex = 0, meetingIndex = 0)
    private val context = ImportReviewContext(ImportTarget("local", 1, 0, 3), targetPeriods = listOf(Period(1, LocalTime.parse("08:00"), LocalTime.parse("08:45"))))

    private fun parse(csv: String): FormatParseResult {
        val parseContext = FormatParseContext("malformed-field-csv", 20)
        val table = Utf8CsvReader().read(csv.byteInputStream(), parseContext).table!!
        val columns = HeaderMappingInspector().inspect(table).candidates.associate { it.field to it.column }
        return TabularTimetableParser().parse(table, ConfirmedColumnMapping(setOf(1), columns), parseContext)
    }

    private fun session(raw: FormatParseResult, selected: Set<ReviewLocator>? = null) =
        ImportReviewSession.create(FrozenImportSource.fromFormat(raw), context, selectedMeetings = selected)

    private fun acknowledged(s: ImportReviewSession) = ImportReviewDecisions(
        evidence = s.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { s.reviewEvidence(it.fingerprint) },
        unscheduled = s.selectedMeetings.filter { s.meeting(it).kind == MeetingKind.UNSCHEDULED }.map(s::keepUnscheduled),
    )

    private fun assertBlocked(csv: String, issue: String) {
        val raw = parse(csv)
        assertNotNull(raw.draft)
        assertTrue(raw.issues.toString(), raw.issues.any { it.code == issue })
        val s = session(raw)
        val result = RichDraftConverter.convert(s, acknowledged(s))
        assertTrue(result.toString(), result is ImportReviewResult.Blocked)
    }

    @Test fun `rejected supplied clock cannot become retained unscheduled through acknowledgements`() {
        assertBlocked("name,timeRange\nPractice,09:00-08:00", "INVALID_TIME_RANGE")
    }

    @Test fun `invalid lexical clock retains its parser failure`() {
        assertBlocked("name,timeRange\nPractice,invalid-clock", "INVALID_TIME")
    }

    @Test fun `valid section cannot silently substitute for rejected supplied clock`() {
        assertBlocked("name,dayOfWeek,weeks,sections,startTime,endTime\nCourse,1,1,1,09:00,08:00", "INVALID_TIME_RANGE")
    }

    @Test fun `incomplete supplied clock requires correction`() {
        assertBlocked("name,dayOfWeek,weeks,sections,startTime,endTime\nCourse,1,1,1,08:00,", "INCOMPLETE_TIME")
    }

    @Test fun `ambiguous supplied clock mappings require correction`() {
        assertBlocked("name,dayOfWeek,weeks,sections,timeRange,startTime,endTime\nCourse,1,1,1,08:00-08:45,09:00,09:45", "AMBIGUOUS_TIME_MAPPING")
    }

    @Test fun `malformed supplied date cannot become retained unscheduled`() {
        assertBlocked("name,dates,startTime,endTime\nCourse,2026-02-30,08:00,08:45", "INVALID_DATE")
    }

    @Test fun `malformed provided weekday cannot be acknowledged away`() {
        assertBlocked("name,dayOfWeek,weeks,timeRange\nCourse,8,1,08:00-08:45", "INVALID_DAY_OF_WEEK")
    }

    @Test fun `malformed provided weeks cannot be acknowledged away`() {
        assertBlocked("name,dayOfWeek,weeks,timeRange\nCourse,1,1、x,08:00-08:45", "INVALID_WEEKS")
    }

    @Test fun `malformed provided sections cannot be acknowledged away`() {
        assertBlocked("name,dayOfWeek,weeks,sections,timeRange\nCourse,1,1,1、x,08:00-08:45", "INVALID_SECTIONS")
    }

    @Test fun `genuinely absent time still permits explicit unscheduled retention`() {
        val s = session(parse("name,weeks\nPractice,1"))
        val result = RichDraftConverter.convert(s, acknowledged(s))
        assertTrue(result.toString(), result is ImportReviewResult.Ready)
        assertEquals(ReviewedTimeSpec.Unscheduled, (result as ImportReviewResult.Ready).reviewed.courses.single().meetings.single().time)
    }

    @Test fun `deselected malformed row does not block unrelated valid row`() {
        val raw = parse("name,dayOfWeek,weeks,sections,startTime,endTime\nValid,1,1,1,08:00,08:45\nInvalid,1,1,1,09:00,08:00")
        assertTrue(raw.issues.any { it.code == "INVALID_TIME_RANGE" })
        val s = session(raw, setOf(first))
        val result = RichDraftConverter.convert(s, acknowledged(s))
        assertTrue(result.toString(), result is ImportReviewResult.Ready)
        assertEquals("Valid", (result as ImportReviewResult.Ready).reviewed.courses.single().identity.name)
    }

    @Test fun `empty mapped clocks and source periods alone remain usable`() {
        val raw = parse("name,dayOfWeek,weeks,sections,startTime,endTime\nCourse,1,1,1,,")
        val withProposal = raw.copy(draft = raw.draft!!.copy(periodProposals = listOf(PeriodProposal(1, DraftTimeRange(LocalTime.parse("09:00"), LocalTime.parse("09:45")), emptyList()))))
        val s = session(withProposal)
        val d = acknowledged(s).copy(times = listOf(s.reviewTime(first, TimeResolutionChoice.USE_SOURCE_PERIODS)))
        val result = RichDraftConverter.convert(s, d)
        assertTrue(result.toString(), result is ImportReviewResult.Ready)
        assertTrue((result as ImportReviewResult.Ready).reviewed.courses.single().meetings.single().time is ReviewedTimeSpec.SourcePeriods)
    }

    @Test fun `malformed pending cancellation clock cannot become unrestricted cancellation`() {
        val raw = parse("name,exceptionKind,originalDate,startTime,endTime\nCourse,CANCEL,2026-11-02,09:00,08:00")
        assertEquals("INVALID_TIME_RANGE", raw.pendingExceptions.single().exception.warnings.single().code)
        val s = session(raw)
        val p = ReviewLocator(pendingIndex = 0)
        val attached = s.attachException(p, ExceptionAttachment.Bound(LogicalBaseReference.StoredLogicalBase("local", 1, 3, "base", "fp")))
        val result = RichDraftConverter.convert(s, acknowledged(s).copy(attachments = listOf(attached)))
        assertTrue(result.toString(), result is ImportReviewResult.Blocked)
    }

    @Test fun `unlocated required field failure cannot be bypassed by generic batch review`() {
        val raw = parse("name,dayOfWeek,weeks,timeRange\nCourse,1,1,08:00-08:45").copy(issues = listOf(ParseIssue("INVALID_DATE", "Unlocated source failure")))
        val s = session(raw)
        assertTrue(RichDraftConverter.convert(s, acknowledged(s)) is ImportReviewResult.Blocked)
    }

    @Test fun `new valid parse invalidates old decisions and leaves original source evidence intact`() {
        val original = session(parse("name,timeRange\nPractice,09:00-08:00"))
        val old = acknowledged(original)
        val corrected = session(parse("name,timeRange\nPractice,"))
        assertTrue(RichDraftConverter.convert(corrected, old) is ImportReviewResult.Blocked)
        assertTrue(RichDraftConverter.convert(corrected, acknowledged(corrected)) is ImportReviewResult.Ready)
        assertTrue(original.source.format.draft!!.courses.single().meetings.single().warnings.any { it.code == "INVALID_TIME_RANGE" })
        assertTrue(original.source.format.sources.any { it.rawText == "09:00-08:00" })
    }
}
