package com.lingion.sleepy.ui

import org.junit.Assert.*
import org.junit.Test
import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import com.lingion.sleepy.importing.ocr.*

class ImportDocumentSnifferTest {
    @Test fun actualBytesTakePriorityOverMisleadingProviderMime() {
        assertEquals(ImportDocumentKind.PDF, ImportDocumentSniffer.detect("%PDF-1.7".toByteArray(), "image/png"))
        assertEquals(ImportDocumentKind.IMAGE, ImportDocumentSniffer.detect(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), "application/pdf"))
        assertEquals(ImportDocumentKind.IMAGE, ImportDocumentSniffer.detect(byteArrayOf(137.toByte(),80,78,71,13,10,26,10), null))
    }

    @Test fun zipIsSentToVerifiedWorkbookReaderAndOldXlsIsRejected() {
        assertEquals(ImportDocumentKind.XLSX, ImportDocumentSniffer.detect(byteArrayOf(80,75,3,4), null))
        assertNull(ImportDocumentSniffer.detect(byteArrayOf(0xd0.toByte(),0xcf.toByte(),0x11,0xe0.toByte(),0xa1.toByte(),0xb1.toByte(),0x1a,0xe1.toByte()), "application/vnd.ms-excel"))
    }

    @Test fun textRoutingRecognizesBomJsonAndCsvMimeWithoutFilename() {
        assertEquals(ImportDocumentKind.JSON, ImportDocumentSniffer.detect("\uFEFF  {\"schemaVersion\":1}".toByteArray(), null))
        assertEquals(ImportDocumentKind.CSV, ImportDocumentSniffer.detect("name,weeks,sections".toByteArray(), "text/csv"))
        assertEquals(ImportDocumentKind.TEXT, ImportDocumentSniffer.detect("下周三增加实验".toByteArray(), "text/plain"))
        assertNull(ImportDocumentSniffer.detect(byteArrayOf(0,1,2,3,4), "application/octet-stream"))
    }

    @Test fun mixedPdfRetainsNativeCoursesAndAllOuterOcrEvidenceWithoutMovingPaths() {
        val raw = SourceExcerpt(SourcePosition(2, TextBox(1f, 1f, 10f, 10f)), "Synthetic unparsed")
        val path = DraftPath(courseIndex = 0, meetingIndex = 0)
        val source = ImportSourceRef("synthetic", "ocr:page:1", "Synthetic", path)
        val warning = ParseIssue("OCR_VARIANT_DISAGREEMENT", "Review variants")
        val recognized = RichCourseDraft("OCR", meetings = listOf(RichMeetingDraft(MeetingKind.UNSCHEDULED)))
        val outer = OcrLayoutParseResult(FormatParseResult(RichTimetableDraft(courses = listOf(recognized)),
            sources = listOf(source), issues = listOf(warning)), OcrLayoutDocument(emptyList()),
            identityProposals = listOf(ArchiveIdentityProposal(listOf(CandidateCourseId("one")), ArchiveIdentityEvidence.SAME_TITLE, emptyList())))
        val native = RichTimetableDraft(courses = listOf(RichCourseDraft("Text")), unparsed = listOf(raw))
        val merged = mergePdfTextAndOcr(native, outer, listOf(ParseIssue("OCR_REQUIRED", "Scanned page")))
        assertEquals(listOf("OCR", "Text"), merged.formatResult.draft!!.courses.map { it.name })
        assertEquals(path, merged.formatResult.sources.single().path)
        assertEquals(outer.identityProposals, merged.identityProposals)
        assertSame(outer.document, merged.document)
        assertEquals(listOf(raw), merged.formatResult.draft!!.unparsed)
        assertTrue(merged.formatResult.issues.contains(warning))
        assertTrue(merged.formatResult.issues.any { it.code == "OCR_REQUIRED" })
    }
}
