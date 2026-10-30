/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.TimeRange
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class RichDraftConverterTest {
    private val p = ReviewLocator(courseIndex = 0, meetingIndex = 0)
    private fun time(a:String,b:String) = DraftTimeRange(LocalTime.parse(a), LocalTime.parse(b))
    private fun meeting(kind:MeetingKind = MeetingKind.RECURRING) = RichMeetingDraft(kind, 2, setOf(1,3,7), setOf(1,3), customTime = time("08:00","09:40"))
    private fun source(m:RichMeetingDraft, courses:List<RichCourseDraft>? = null, periods:List<PeriodProposal> = emptyList()) = FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses = courses ?: listOf(RichCourseDraft("Signals", "0012", "0004", listOf(m))), periodProposals = periods)))
    private fun session(source:FrozenImportSource, calendar:Boolean = true) = ImportReviewSession.create(source, ImportReviewContext(ImportTarget("local", 1, 0, 3), termMonday = if(calendar) LocalDate.parse("2026-09-07") else null, targetPeriods = listOf(Period(1,LocalTime.parse("09:00"),LocalTime.parse("09:45")),Period(3,LocalTime.parse("10:00"),LocalTime.parse("10:45")))))
    private fun ready(s:ImportReviewSession, d:ImportReviewDecisions = ImportReviewDecisions()):ReviewedImport {
        val result = RichDraftConverter.convert(s,d)
        assertTrue(result.toString(), result is ImportReviewResult.Ready)
        return (result as ImportReviewResult.Ready).reviewed
    }
    @Test fun `sparse recurring weeks and sections stay exact`() {
        val value = ready(session(source(meeting()))).courses.single()
        assertEquals("0012", value.identity.sourceCourseCode)
        assertEquals("0004", value.identity.sourceClassCode)
        assertEquals(setOf(1,3,7),value.meetings.single().weeks)
        assertEquals(setOf(1,3),value.meetings.single().originalSections)
    }
    @Test fun `date specific weeks become declarations without calendar`() {
        val m = meeting(MeetingKind.DATE_SPECIFIC).copy(dayOfWeek = null, dates = setOf(LocalDate.parse("2026-10-01")))
        val out = ready(session(source(m),false))
        assertEquals(emptySet<Int>(), out.courses.single().meetings.single().weeks)
        assertEquals(setOf(1,3,7), out.courses.single().meetings.single().declaredWeeks)
        assertEquals(0,out.courses.single().meetings.single().day)
        assertFalse(out.requiresCalendarForOccurrencePlanning)
    }
    @Test fun `unscheduled known weeks are declarations`() {
        val s = session(source(meeting(MeetingKind.UNSCHEDULED).copy(dayOfWeek = null)))
        assertTrue(RichDraftConverter.convert(s) is ImportReviewResult.Blocked)
        val out = ready(s,ImportReviewDecisions(unscheduled = listOf(s.keepUnscheduled(p))))
        assertTrue(out.courses.single().meetings.single().weeks.isEmpty())
        assertEquals(setOf(1,3,7),out.courses.single().meetings.single().declaredWeeks)
    }
    @Test fun `unscheduled dates cannot be silently cleared`() {
        val s = session(source(meeting(MeetingKind.UNSCHEDULED).copy(dates = setOf(LocalDate.parse("2026-10-01")))))
        assertTrue(RichDraftConverter.convert(s) is ImportReviewResult.Blocked)
    }
    @Test fun `recurring missing weekday is blocked`() {
        assertTrue(RichDraftConverter.convert(session(source(meeting().copy(dayOfWeek = null)))) is ImportReviewResult.Blocked)
    }
    @Test fun `recurring without calendar retains planning gate`() {
        assertTrue(ready(session(source(meeting()),false)).requiresCalendarForOccurrencePlanning)
    }
    @Test fun `first meeting teacher does not become course default`() {
        val course = RichCourseDraft("Signals", meetings = listOf(meeting().copy(teacher = "Ada",room = "Lab"),meeting().copy(teacher = null,room = "")))
        val out = ready(session(source(meeting(),listOf(course)))).courses.single()
        assertEquals("",out.identity.teacher)
        assertEquals("",out.identity.room)
        assertEquals("Ada",out.meetings[0].teacher)
        assertNull(out.meetings[1].teacher)
        assertEquals("",out.meetings[1].room)
    }
    @Test fun `group project experiment and null override semantics survive`() {
        val out = ready(session(source(meeting().copy(group = "G2", experimentProject = "P1", isExperiment = true,teacher = "",room = null)))).courses.single().meetings.single()
        assertEquals("G2",out.group);assertEquals("P1",out.experimentProject);assertTrue(out.isExperiment);assertEquals("",out.teacher);assertNull(out.room)
    }
    @Test fun `known source periods different from target require typed decision`() {
        val proposals = listOf(PeriodProposal(1,time("08:00","08:45"),emptyList()),PeriodProposal(3,time("08:55","09:40"),emptyList()))
        val s = session(source(meeting().copy(customTime = null), periods = proposals))
        assertTrue(RichDraftConverter.convert(s) is ImportReviewResult.Blocked)
        val out = ready(s,ImportReviewDecisions(times = listOf(s.reviewTime(p,TimeResolutionChoice.USE_SOURCE_PERIODS))))
        val intervals = (out.courses.single().meetings.single().time as ReviewedTimeSpec.SourcePeriods).intervals
        assertEquals(2,intervals.size)
        assertEquals(LocalTime.parse("08:45"),intervals[0].range.end)
        assertEquals(LocalTime.parse("08:55"),intervals[1].range.start)
    }
    @Test fun `explicit target time choice is bound and keeps target intervals`() {
        val s = session(source(meeting().copy(customTime = null), periods = listOf(PeriodProposal(1,time("08:00","08:45"),emptyList()))))
        val out = ready(s,ImportReviewDecisions(times = listOf(s.reviewTime(p,TimeResolutionChoice.USE_TARGET_PERIODS))))
        assertTrue(out.courses.single().meetings.single().time is ReviewedTimeSpec.TargetSections)
    }
    @Test fun `explicit continuous custom time never invents a break`() {
        val spec = ready(session(source(meeting()))).courses.single().meetings.single().time as ReviewedTimeSpec.Continuous
        assertEquals(TimeRange(LocalTime.parse("08:00"),LocalTime.parse("09:40")),spec.range)
    }
    @Test fun `missing time name and malformed exact minute fields fail closed`() {
        for (m in listOf(meeting().copy(customTime = null, sections = emptySet()),meeting().copy(customTime = time("09:00","08:00")),meeting().copy(customTime = time("08:00:01","09:00")))) {
            assertTrue(RichDraftConverter.convert(session(source(m))) is ImportReviewResult.Blocked)
        }
        assertTrue(RichDraftConverter.convert(session(source(meeting(),listOf(RichCourseDraft(" ",meetings = listOf(meeting())))))) is ImportReviewResult.Blocked)
    }
    @Test fun `formula source preserves raw text and separate formula provenance`() {
        val raw = ImportSourceRef("file-a","sheet:2:R3C4","cached 99",DraftPath(0,0),3,4,"SUM(A1:A2)")
        val s = session(FrozenImportSource.fromFormat(FormatParseResult(source(meeting()).format.draft,sources = listOf(raw))))
        assertTrue(RichDraftConverter.convert(s) is ImportReviewResult.Blocked)
        val provenance = ready(s,ImportReviewDecisions(evidence = s.requirements.filter { it.kind == ReviewRequirementKind.EVIDENCE }.map { s.reviewEvidence(it.fingerprint) })).courses.single().meetings.single().provenance
        assertTrue(provenance.any { it.rawText == "cached 99" && it.page == null && it.bounds == null && it.sourceLocator == "sheet:2:R3C4" })
        assertTrue(provenance.any { it.rawText == "SUM(A1:A2)" && it.sourceLocator == "sheet:2:R3C4:formula" })
    }
    @Test fun `canonical geometric provenance is copied once at actual page`() {
        val box = TextBox(10f,20f,30f,40f)
        val m = meeting().copy(sources = listOf(SourceExcerpt(SourcePosition(7,box),"original")))
        val source = ready(session(source(m))).courses.single().meetings.single().provenance.single()
        assertEquals(7,source.page); assertEquals(10f,source.bounds!!.left);assertEquals(40f,source.bounds.bottom)
    }
    @Test fun `invalid item can be deselected while retaining valid item`() {
        val src = source(meeting(),listOf(RichCourseDraft("Signals",meetings = listOf(meeting(),meeting(MeetingKind.UNSCHEDULED).copy(dates = setOf(LocalDate.parse("2026-10-01")))))))
        val base = session(src)
        val s = ImportReviewSession.create(src,base.context,selectedMeetings = setOf(p),selectedPending = emptySet())
        assertEquals(1,ready(s).courses.single().meetings.size)
    }

    @Test fun `date source week declarations do not become target recurring bounds`() {
        val m = meeting(MeetingKind.DATE_SPECIFIC).copy(dayOfWeek = null,weeks = setOf(1,25),dates = setOf(LocalDate.parse("2026-11-01")))
        val out = ready(session(source(m),false))
        assertEquals(setOf(1,25),out.courses.single().meetings.single().declaredWeeks)
        assertTrue(out.courses.single().meetings.single().weeks.isEmpty())
    }
}
