package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.domain.model.defaultPeriods
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ImportSourceCorrectionTest {
    private val context=ImportReviewContext(ImportTarget("synthetic",1,0,4),termMonday=LocalDate.parse("2026-09-07"),targetPeriods=defaultPeriods())
    private val position=SourceExcerpt(SourcePosition(1,TextBox(1f,1f,10f,10f)),"Synthetic malformed time")
    private fun decisions(session:ImportReviewSession)=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) })
    @Test fun typedTimeCorrectionKeepsOriginalEvidenceAndRequiresNewReview() {
        val warning=ParseIssue("INVALID_TIME_RANGE","Invalid source time",position)
        val meeting=RichMeetingDraft(MeetingKind.RECURRING,1,setOf(1),emptySet(),
            DraftTimeRange(LocalTime.of(10,0),LocalTime.of(9,0)),sources=listOf(position),warnings=listOf(warning),missingFields=setOf(ImportField.TIME))
        val source=FrozenImportSource.fromPdf(RichTimetableDraft(courses=listOf(RichCourseDraft("Synthetic",meetings=listOf(meeting)))))
        val old=ImportReviewSession.create(source,context)
        assertTrue(RichDraftConverter.convert(old,decisions(old)) is ImportReviewResult.Blocked)
        val edited=source.correctMeeting(ReviewLocator(courseIndex=0,meetingIndex=0),
            meeting.copy(customTime=DraftTimeRange(LocalTime.of(9,0),LocalTime.of(10,0))),setOf(ImportField.TIME),20)
        assertNotEquals(source.contentDigest,edited.contentDigest)
        assertSame(source,edited.originalSource)
        assertEquals(meeting,source.format.draft!!.courses.single().meetings.single())
        assertEquals(listOf(warning),edited.format.draft!!.courses.single().meetings.single().warnings)
        assertEquals(listOf(position),edited.format.draft!!.courses.single().meetings.single().sources)
        val next=ImportReviewSession.create(edited,context)
        assertTrue(RichDraftConverter.convert(next,decisions(old)) is ImportReviewResult.Blocked)
        assertTrue(RichDraftConverter.convert(next,decisions(next)) is ImportReviewResult.Ready)
    }

    @Test fun correctingOneMeetingCannotClearAnotherMeetingsMalformedWeek() {
        val warning=ParseIssue("INVALID_WEEKS","Malformed weeks")
        val meeting=RichMeetingDraft(MeetingKind.RECURRING,1,setOf(0),setOf(1),warnings=listOf(warning))
        val source=FrozenImportSource.fromPdf(RichTimetableDraft(courses=listOf(RichCourseDraft("Synthetic",meetings=listOf(meeting,meeting)))))
        val one=source.correctMeeting(ReviewLocator(courseIndex=0,meetingIndex=0),meeting.copy(weeks=setOf(1)),setOf(ImportField.WEEKS),20)
        val session=ImportReviewSession.create(one,context)
        val blocked=RichDraftConverter.convert(session,decisions(session)) as ImportReviewResult.Blocked
        assertTrue(blocked.problems.any { it.source.meetingIndex==1 && it.code.contains("INVALID_WEEKS") })
        val both=one.correctMeeting(ReviewLocator(courseIndex=0,meetingIndex=1),meeting.copy(weeks=setOf(3)),setOf(ImportField.WEEKS),20)
        val ready=ImportReviewSession.create(both,context)
        assertTrue(RichDraftConverter.convert(ready,decisions(ready)) is ImportReviewResult.Ready)
        assertSame(source,both.originalSource)
    }

    @Test fun unrelatedMetadataEditCannotAuthorizeAnInvalidTime() {
        val meeting=RichMeetingDraft(MeetingKind.RECURRING,1,setOf(1),emptySet(),
            DraftTimeRange(LocalTime.of(10,0),LocalTime.of(9,0)),warnings=listOf(ParseIssue("INVALID_TIME_RANGE","Invalid")))
        val source=FrozenImportSource.fromPdf(RichTimetableDraft(courses=listOf(RichCourseDraft("Synthetic",meetings=listOf(meeting)))))
        try { source.correctMeeting(ReviewLocator(courseIndex=0,meetingIndex=0),meeting.copy(room="Synthetic room"),setOf(ImportField.ROOM),20);fail("Invalid time cannot be accepted by editing unrelated metadata") }
        catch(_:IllegalArgumentException) { }
    }
}
