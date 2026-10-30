/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import com.lingion.sleepy.importing.ocr.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ImportSourceEnvelopeTest {
    @Test fun `caller mutation cannot rewrite a source snapshot`() {
        val weeks = linkedSetOf(1, 3, 7)
        val meetings = mutableListOf(RichMeetingDraft(MeetingKind.RECURRING, 2, weeks, setOf(1)))
        val input = FormatParseResult(RichTimetableDraft(courses = listOf(RichCourseDraft("Signals", meetings = meetings))))
        val frozen = FrozenImportSource.fromFormat(input)
        weeks.clear(); meetings.clear()
        assertEquals(1, frozen.format.draft!!.courses.single().meetings.size)
        assertEquals(setOf(1, 3, 7), frozen.format.draft!!.courses.single().meetings.single().weeks)
    }
    @Test fun `all source nested evidence is frozen before digest and conversion`() {
        val box = TextBox(1f,2f,30f,40f)
        val excerpt = SourceExcerpt(SourcePosition(3,box),"raw")
        val issues = mutableListOf(ParseIssue("original-warning","message",excerpt))
        val missing = linkedSetOf(ImportField.TEACHER)
        val weeks = linkedSetOf(1,3)
        val sections = linkedSetOf(1,2)
        val sourceExcerpts = mutableListOf(excerpt)
        val exceptions = mutableListOf(RichExceptionDraft(RichExceptionKind.CANCEL,LocalDate.parse("2026-11-02"),sections = sections,sources = sourceExcerpts,warnings = issues,missingFields = missing))
        val course = RichCourseDraft("Test",meetings = mutableListOf(RichMeetingDraft(MeetingKind.RECURRING,1,weeks,sections,sources = sourceExcerpts,warnings = issues,missingFields = missing,exceptions = exceptions)),sources = sourceExcerpts,warnings = issues,missingFields = missing)
        val source = ImportSourceRef("id","sheet:1:R3C4","cached",DraftPath(0,0),3,4,"SUM(A1:A2)")
        val refs = mutableListOf(source)
        val courseKeys = linkedMapOf(0 to "c")
        val meetingKeys = linkedMapOf(DraftPath(0,0) to "m")
        val candidates = mutableListOf(DraftPath(0,0))
        val pending = mutableListOf(PendingExceptionAttachment(exception = exceptions.single(),sources = refs,reason = "unbound",declaredWeeks = weeks,candidatePaths = candidates))
        val relative = mutableListOf(RelativeDateProposal("明天",source,LocalDate.parse("2026-10-27"),LocalDate.parse("2026-10-28"),"REFERENCE_DAY_OFFSET"))
        val periodSources = mutableListOf(excerpt)
        val format = FormatParseResult(RichTimetableDraft(listOf(course),listOf(PeriodProposal(1,DraftTimeRange(LocalTime.parse("08:00"),LocalTime.parse("08:45")),periodSources)),issues,sourceExcerpts,missing,linkedSetOf("adapter")),refs,issues,courseKeys = courseKeys,meetingKeys = meetingKeys,pendingExceptions = pending,relativeDates = relative,unparsedSources = refs)
        val span = OcrSpan("unmodified",OcrLevel.WORD,90f,excerpt.position)
        val spans = mutableListOf(span)
        val native = linkedSetOf(OcrIssue.OCR_VARIANT_DISAGREEMENT)
        val variantIssues = linkedSetOf(OcrIssue.OCR_REVIEW_REQUIRED)
        val texts = mutableListOf(PositionedText("page text",excerpt.position))
        val ids = mutableListOf(OcrSpanId(3,OcrVariantId.ORIGINAL,0))
        val regionIds = linkedSetOf("region")
        val paths = linkedSetOf(DraftPath(0,0))
        val alternatives = mutableListOf("alternate")
        val memberIds = mutableListOf(CandidateCourseId("one"))
        val regionList = mutableListOf("region")
        val evidence = linkedSetOf("anchor")
        val page = PositionedPage(3,100f,200f,texts)
        val variant = OcrVariantEvidence(OcrVariantId.ORIGINAL,100,200,PageAffineTransform.IDENTITY,page,spans,variantIssues,null,OcrWordQuality.EMPTY,true)
        val result = OcrLayoutParseResult(format,OcrLayoutDocument(mutableListOf(OcrLayoutPage(3,100f,200f,mutableListOf(variant),OcrVariantId.ORIGINAL,textLayerEvidence = page,nativeIssues = native))),
            mutableListOf(OcrLayoutCandidate("adapter",3,evidence,ids)),mutableListOf(RegionEvidence("region",3,OcrVariantId.ORIGINAL,box,ids,OcrRegionKind.RECORD,issues,alternatives)),
            mutableListOf(OcrSpanCoverage(ids.single(),span,SpanCoverageStatus.UNPARSED,regionIds,paths)),mutableListOf(OcrCandidateCourse(memberIds.single(),course,regionList,ids,DraftPath(0))),
            mutableListOf(ArchiveIdentityProposal(memberIds,ArchiveIdentityEvidence.SAME_TITLE,ids)))
        val frozen = FrozenImportSource.fromOcr(result)
        val digest = frozen.contentDigest
        issues.clear();missing.clear();weeks.clear();sections.clear();sourceExcerpts.clear();exceptions.clear();refs.clear();courseKeys.clear();meetingKeys.clear();candidates.clear();pending.clear();relative.clear();periodSources.clear();spans.clear();native.clear();variantIssues.clear();texts.clear();ids.clear();regionIds.clear();paths.clear();alternatives.clear();memberIds.clear();regionList.clear();evidence.clear()
        val f = frozen.format
        assertSame(f,frozen.ocr!!.formatResult)
        assertEquals(digest,frozen.contentDigest)
        assertEquals(digest,ImportCanonical.digest(listOf(frozen.format,frozen.ocr)))
        assertEquals(setOf(1,3),f.draft!!.courses.single().meetings.single().weeks)
        assertEquals(setOf(1,2),f.draft!!.courses.single().meetings.single().exceptions.single().sections)
        assertEquals(ImportField.TEACHER,f.draft!!.missingFields.single())
        assertEquals("original-warning",f.issues.single().code)
        assertEquals("raw",f.draft!!.unparsed.single().rawText)
        assertEquals("c",f.courseKeys[0]); assertEquals("m",f.meetingKeys[DraftPath(0,0)])
        assertEquals("SUM(A1:A2)",f.sources.single().formula);assertEquals(3,f.sources.single().row);assertEquals(4,f.sources.single().column)
        assertEquals(1,f.pendingExceptions.single().candidatePaths.size);assertEquals(1,f.relativeDates.size)
        assertEquals(1,f.draft!!.periodProposals.single().sources.size)
        val o = frozen.ocr!!
        assertEquals("unmodified",o.document.pages.single().variants.single().spans.single().rawText)
        assertEquals(OcrIssue.OCR_VARIANT_DISAGREEMENT,o.document.pages.single().nativeIssues.single())
        assertEquals(OcrIssue.OCR_REVIEW_REQUIRED,o.document.pages.single().variants.single().issues.single())
        assertEquals("page text",o.document.pages.single().textLayerEvidence!!.text.single().rawText)
        assertEquals("alternate",o.regions.single().alternatives.single())
        assertEquals("region",o.coverage.single().regionIds.single());assertEquals(DraftPath(0,0),o.coverage.single().paths.single())
        assertEquals(1,o.candidateCourses.single().course.meetings.single().exceptions.size)
        assertEquals("one",o.identityProposals.single().memberIds.single().value)
        assertEquals("anchor",o.layoutCandidates.single().evidence.single())
        assertNotEquals(digest,FrozenImportSource.fromOcr(result).contentDigest)
        assertThrows(UnsupportedOperationException::class.java) { (f.draft!!.courses.single().meetings.single().weeks as MutableSet<Int>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (o.document.pages.single().variants as MutableList<OcrVariantEvidence>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (f.courseKeys as MutableMap<Int,String>).clear() }
    }
    @Test fun `set order does not change canonical source content`() {
        fun source(weeks:Set<Int>) = FrozenImportSource.fromPdf(RichTimetableDraft(courses = listOf(RichCourseDraft("x",meetings = listOf(RichMeetingDraft(MeetingKind.RECURRING,1,weeks,setOf(1)))))))
        assertEquals(source(linkedSetOf(1,3,7)).contentDigest,source(linkedSetOf(7,1,3)).contentDigest)
    }
    @Test fun `alias changed payload rejects old review but cannot alter old reviewed output`() {
        val weeks = linkedSetOf(1,3)
        val draft = RichTimetableDraft(courses = listOf(RichCourseDraft("x",meetings = listOf(RichMeetingDraft(MeetingKind.RECURRING,1,weeks,customTime = DraftTimeRange(LocalTime.parse("08:00"),LocalTime.parse("08:45")))))))
        val context = ImportReviewContext(ImportTarget("local",1,0,3))
        val first = ImportReviewSession.create(FrozenImportSource.fromPdf(draft),context)
        val oldDecision = first.reviewConflict("conflict",ConflictReviewChoice.KEEP)
        val old = (RichDraftConverter.convert(first,ImportReviewDecisions(conflicts = listOf(oldDecision))) as ImportReviewResult.Ready).reviewed
        weeks += 7
        val second = ImportReviewSession.create(FrozenImportSource.fromPdf(draft),context)
        val result = RichDraftConverter.convert(second,ImportReviewDecisions(conflicts = listOf(oldDecision))) as ImportReviewResult.Blocked
        assertTrue(result.problems.any { it.code == "STALE_REVIEW_BINDING" })
        assertEquals(setOf(1,3),old.courses.single().meetings.single().weeks)
    }
}
