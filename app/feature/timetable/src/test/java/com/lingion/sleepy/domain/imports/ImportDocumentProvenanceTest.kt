package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import com.lingion.sleepy.importing.ocr.*
import com.lingion.sleepy.domain.model.defaultPeriods
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ImportDocumentProvenanceTest {
    private val meeting=RichMeetingDraft(MeetingKind.RECURRING,1,setOf(1,3,8),setOf(1),teacher="",room="",group="",experimentProject="")
    private fun parsed()=FormatParseResult(RichTimetableDraft(courses=listOf(RichCourseDraft("Synthetic",meetings=listOf(meeting,meeting.copy(dayOfWeek=3))))),
        sources=listOf(ImportSourceRef("source","Sheet1!A3","Synthetic source",DraftPath(0,0))))
    @Test fun documentLabelIsMetadataAndRetainsOriginalLocationsThroughReview() {
        val original=FrozenImportSource.fromFormat(parsed())
        val named=original.withDocumentDisplayName("Synthetic schedule.pdf")
        assertNotEquals(original.contentDigest,named.contentDigest)
        assertNotEquals(original.sessionId,named.sessionId)
        assertEquals(1,original.format.sources.size)
        assertTrue(named.format.sources.containsAll(original.format.sources))
        val refs=named.format.sources.filter {it.sourceLocator=="document:display-name"}
        assertEquals(setOf(DraftPath(0),DraftPath(0,0),DraftPath(0,1)),refs.map {it.path}.toSet())
        assertTrue(refs.all {it.rawText=="Synthetic schedule.pdf"})
        val context=ImportReviewContext(ImportTarget("synthetic",1,0,4),termMonday=LocalDate.parse("2026-09-07"),targetPeriods=defaultPeriods())
        val session=ImportReviewSession.create(named,context)
        val decisions=ImportReviewDecisions(evidence=session.requirements.filter {it.kind==ReviewRequirementKind.EVIDENCE}.map {session.reviewEvidence(it.fingerprint)})
        val reviewed=(RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
        assertTrue(reviewed.courses.single().provenance.any {it.sourceLocator=="document:display-name" && it.rawText=="Synthetic schedule.pdf"})
        assertTrue(reviewed.courses.single().meetings.all {m->m.provenance.any {it.sourceLocator=="document:display-name"}})
    }
    @Test fun sourceCorrectionKeepsDocumentMetadataAndOriginalEvidence() {
        val named=FrozenImportSource.fromFormat(parsed()).withDocumentDisplayName("Synthetic.csv")
        val edited=named.correctMeeting(ReviewLocator(courseIndex=0,meetingIndex=0),meeting.copy(room="Edited"),setOf(ImportField.ROOM),20)
        assertSame(named,edited.originalSource)
        assertTrue(edited.format.sources.containsAll(named.format.sources))
    }
    @Test fun addingDocumentLabelCannotDiscardOuterOcrEvidence() {
        val outer=OcrLayoutParseResult(parsed(),OcrLayoutDocument(emptyList()))
        val original=FrozenImportSource.fromOcr(outer)
        val named=original.withDocumentDisplayName("Synthetic.png")
        assertNotNull(named.ocr)
        assertEquals(original.ocr!!.document,named.ocr!!.document)
        assertEquals(named.format,named.ocr!!.formatResult)
    }
    @Test fun labelsAreBoundedAndCannotContainControlCharacters() {
        for(label in listOf(""," ","a\nb","a\u0000b","x".repeat(257))) {
            try {FrozenImportSource.fromFormat(parsed()).withDocumentDisplayName(label);fail("Invalid provider label accepted")}
            catch(_:IllegalArgumentException) { }
        }
    }
    @Test fun pendingNoticeAndNestedExceptionKeepTheirOwnFileReference() {
        val change=RichExceptionDraft(RichExceptionKind.CANCEL,originalDate=LocalDate.parse("2026-09-07"))
        val initial=parsed().copy(draft=RichTimetableDraft(courses=listOf(RichCourseDraft("Synthetic",meetings=listOf(meeting.copy(exceptions=listOf(change)))))),
            pendingExceptions=listOf(PendingExceptionAttachment(exception=change,reason="synthetic",sources=parsed().sources)))
        val named=FrozenImportSource.fromFormat(initial).withDocumentDisplayName("Synthetic notice.txt")
        assertTrue(named.format.sources.any {it.path==DraftPath(0,0,0) && it.sourceLocator=="document:display-name"})
        assertEquals(change,named.format.pendingExceptions.single().exception)
        assertTrue(named.format.pendingExceptions.single().sources.containsAll(initial.pendingExceptions.single().sources))
        assertTrue(named.format.pendingExceptions.single().sources.any {it.rawText=="Synthetic notice.txt" && it.sourceLocator=="document:display-name"})
    }
    @Test fun replacingProviderLabelDoesNotAccumulateStaleNamesOrChangeSourceText() {
        val first=FrozenImportSource.fromFormat(parsed()).withDocumentDisplayName("First.pdf")
        val second=first.withDocumentDisplayName("Second.pdf")
        assertEquals(first.format.sources.size,second.format.sources.size)
        assertTrue(second.format.sources.filter {it.sourceLocator=="document:display-name"}.all {it.rawText=="Second.pdf"})
        assertTrue(first.format.sources.any {it.rawText=="First.pdf"})
        assertEquals(parsed().draft,second.format.draft)
        assertTrue(second.format.sources.containsAll(parsed().sources))
    }
}
