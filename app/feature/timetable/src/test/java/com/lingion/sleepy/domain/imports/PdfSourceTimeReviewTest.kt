/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.FormatParseResult
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** PDF periods can supply time only after an explicit, valid time-source decision. */
class PdfSourceTimeReviewTest {
    private val source=ReviewLocator(courseIndex=0,meetingIndex=0)
    private fun session(periods:Int=2,invalidClock:Boolean=false):ImportReviewSession {
        val meeting=RichMeetingDraft(MeetingKind.RECURRING,dayOfWeek=1,weeks=setOf(1,3),sections=setOf(1,2),
            missingFields=setOf(ImportField.TIME),warnings=if(invalidClock) listOf(ParseIssue("INVALID_TIME","Synthetic malformed explicit clock")) else emptyList())
        val proposals=listOf(PeriodProposal(1,DraftTimeRange(LocalTime.of(8,0),LocalTime.of(8,45)),emptyList()),
            PeriodProposal(2,DraftTimeRange(LocalTime.of(8,55),LocalTime.of(9,40)),emptyList())).take(periods)
        return ImportReviewSession.create(FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(
            courses=listOf(RichCourseDraft("Synthetic PDF course",meetings=listOf(meeting))),periodProposals=proposals))),
            ImportReviewContext(ImportTarget("local",1,7,3),ImportMode.APPEND,LocalDate.of(2026,9,7),targetPeriods=com.lingion.sleepy.domain.model.defaultPeriods()))
    }
    private fun decisions(s:ImportReviewSession,choose:Boolean)=ImportReviewDecisions(
        evidence=s.requirements.filter {it.kind==ReviewRequirementKind.EVIDENCE}.map {s.reviewEvidence(it.fingerprint)},
        times=if(choose) listOf(s.reviewTime(source,TimeResolutionChoice.USE_SOURCE_PERIODS)) else emptyList())
    @Test fun completeReviewedSourcePeriodsResolveOnlyTheMissingCustomClock() {
        val s=session();val result=RichDraftConverter.convert(s,decisions(s,true))
        assertTrue("Explicit valid source periods must resolve PDF time; actual=$result",result is ImportReviewResult.Ready)
        val time=(result as ImportReviewResult.Ready).reviewed.courses.single().meetings.single().time as ReviewedTimeSpec.SourcePeriods
        assertEquals(listOf(LocalTime.of(8,0),LocalTime.of(8,55)),time.intervals.map {it.range.start})
        assertEquals(listOf(LocalTime.of(8,45),LocalTime.of(9,40)),time.intervals.map {it.range.end})
    }
    @Test fun sourcePeriodChoiceIsStillMandatory() {
        val s=session();assertTrue(RichDraftConverter.convert(s,decisions(s,false)) is ImportReviewResult.Blocked)
    }
    @Test fun incompleteSourcePeriodCoverageStillBlocks() {
        val s=session(periods=1);assertTrue(RichDraftConverter.convert(s,decisions(s,true)) is ImportReviewResult.Blocked)
    }
    @Test fun malformedExplicitClockCannotBeBypassedByAcknowledgmentAndPeriodChoice() {
        val s=session(invalidClock=true);assertTrue(RichDraftConverter.convert(s,decisions(s,true)) is ImportReviewResult.Blocked)
    }
    @Test fun sectionOnlyPdfRequiresExplicitTargetPeriodReview() {
        val s=session(periods=0)
        assertTrue("Section-only PDF must expose a time choice",s.requirements.any {it.kind==ReviewRequirementKind.TIME && it.source==source})
        assertTrue("No implicit clock inference",RichDraftConverter.convert(s,decisions(s,false)) is ImportReviewResult.Blocked)
        val reviewed=decisions(s,false).copy(times=listOf(s.reviewTime(source,TimeResolutionChoice.USE_TARGET_PERIODS)))
        val result=RichDraftConverter.convert(s,reviewed)
        assertTrue("Explicit complete target periods resolve section-only PDF",result is ImportReviewResult.Ready)
        assertTrue((result as ImportReviewResult.Ready).reviewed.courses.single().meetings.single().time is ReviewedTimeSpec.TargetSections)
    }
    @Test fun sectionOnlyPdfCannotSelectAbsentSourcePeriods() {
        val s=session(periods=0)
        assertTrue("Section-only PDF must expose a time choice",s.requirements.any {it.kind==ReviewRequirementKind.TIME})
        assertTrue(RichDraftConverter.convert(s,decisions(s,true)) is ImportReviewResult.Blocked)
    }
}
