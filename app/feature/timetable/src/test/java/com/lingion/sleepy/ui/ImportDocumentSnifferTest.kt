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

    @Test fun knownImageContainersAreNamedWithoutFilenamesAndNeverBecomeTrustedImages() {
        val staticWebp = riff("VP8 ", byteArrayOf(1, 2, 3, 4))
        val losslessWebp = riff("VP8L", byteArrayOf(0x2f, 1, 2, 3))
        val alphaWebp = riff("VP8X", byteArrayOf(0x10) + ByteArray(9))
        val animatedWebp = riff("VP8X", byteArrayOf(0x02) + ByteArray(9))
        for (bytes in listOf(staticWebp, losslessWebp, alphaWebp)) {
            val probe = ImportDocumentSniffer.probe(bytes, "image/jpeg", bytes.size.toLong())
            assertEquals(ImportContentFormat.WEBP, probe.format)
            assertFalse(probe.animated)
            assertEquals("Static WebP is an image only through its bytes", ImportDocumentKind.IMAGE, probe.documentKind)
            assertTrue(probe.supportedImage)
            assertEquals(ImportDocumentKind.IMAGE, ImportDocumentSniffer.detect(bytes, "text/plain"))
        }
        val animated = ImportDocumentSniffer.probe(animatedWebp, null, animatedWebp.size.toLong())
        assertEquals(ImportContentFormat.WEBP, animated.format)
        assertTrue(animated.animated)
        assertNull(animated.documentKind)
        assertTrue(ImportReadMessages.content(animated).contains("动画"))

        val heic = isoBmff("heic", "mif1")
        assertEquals(ImportContentFormat.HEIF, ImportDocumentSniffer.probe(heic, null, heic.size.toLong()).format)
        assertTrue(ImportReadMessages.content(ImportDocumentSniffer.probe(heic, null, heic.size.toLong())).contains("HEIF"))
        assertEquals(ImportContentFormat.HEIF, ImportDocumentSniffer.probe(isoBmff("avif", "mif1"), null).format)
        assertTrue(ImportDocumentSniffer.probe(isoBmff("avis", "msf1"), null).animated)
        assertEquals(ImportContentFormat.GIF, ImportDocumentSniffer.probe("GIF89a".toByteArray(), null).format)
        assertEquals(ImportContentFormat.BMP, ImportDocumentSniffer.probe(bmp(640, 480), null).format)
        assertEquals(ImportContentFormat.TIFF, ImportDocumentSniffer.probe(byteArrayOf(0x49, 0x49, 0x2a, 0), null).format)
        // None of these may be routed as a supported image or table.
        for (bytes in listOf(heic, "GIF89a".toByteArray(), bmp(640, 480))) {
            assertNull(ImportDocumentSniffer.detect(bytes, "image/jpeg"))
        }
    }

    @Test fun webpFragmentsAreLabeledButNeverAuthorizedWithoutTheContainerGate() {
        val valid = riff("VP8 ", byteArrayOf(1, 2, 3, 4))
        assertEquals(ImportContentFormat.WEBP, ImportDocumentSniffer.probe(valid, null, valid.size.toLong()).format)
        // A truncated or mis-sized copy keeps the WebP label so the decoder gate can report
        // damage; it still never reaches BitmapFactory without a complete container.
        val truncated = valid.copyOf(valid.size - 3)
        assertEquals(ImportContentFormat.WEBP, ImportDocumentSniffer.probe(truncated, null, truncated.size.toLong()).format)
        val wrongSize = valid.copyOf().also { it[4] = (it[4] + 40).toByte() }
        assertEquals(ImportContentFormat.WEBP, ImportDocumentSniffer.probe(wrongSize, null, valid.size.toLong()).format)
        assertEquals(ImportContentFormat.BINARY, ImportDocumentSniffer.probe("RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WAVE".toByteArray(), null).format)
        val shortFtyp = isoBmff("heic", "mif1").copyOf(12)
        assertNotEquals(ImportContentFormat.HEIF, ImportDocumentSniffer.probe(shortFtyp, null).format)
    }

    @Test fun emptyAndUnknownBinaryContentIsNotFoldedIntoATextOrImageChoice() {
        assertEquals(ImportContentFormat.BINARY, ImportDocumentSniffer.probe(ByteArray(0), "image/jpeg").format)
        assertEquals(ImportContentFormat.BINARY, ImportDocumentSniffer.probe(byteArrayOf(0, 1, 2, 3), "image/png").format)
        assertEquals(ImportContentFormat.OLE, ImportDocumentSniffer.probe(byteArrayOf(0xd0.toByte(),0xcf.toByte(),0x11,0xe0.toByte(),0xa1.toByte(),0xb1.toByte(),0x1a,0xe1.toByte()), null).format)
        assertTrue(ImportReadMessages.content(ImportDocumentSniffer.probe(byteArrayOf(0, 1, 2, 3), null)).contains("无法识别"))
        assertTrue(ImportReadMessages.content(ImportDocumentSniffer.probe(byteArrayOf(0xd0.toByte(),0xcf.toByte(),0x11,0xe0.toByte(),0xa1.toByte(),0xb1.toByte(),0x1a,0xe1.toByte()), null)).contains("XLSX"))
    }

    @Test fun failureMessagesNameTheActualStageInsteadOfOneUnsupportedFileNotice() {
        val image = ImportDocumentSniffer.probe(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), null)
        assertEquals(ImportContentFormat.JPEG, image.format)
        assertTrue(image.supportedImage)
        assertTrue(ImportReadMessages.raster(com.lingion.sleepy.importing.ocr.RasterFailure.LIMIT_EXCEEDED).contains("超出识别限制"))
        assertTrue(ImportReadMessages.raster(com.lingion.sleepy.importing.ocr.RasterFailure.INVALID_IMAGE).contains("损坏"))
        assertTrue(ImportReadMessages.raster(com.lingion.sleepy.importing.ocr.RasterFailure.UNSUPPORTED_FORMAT).contains("不支持"))
        assertTrue(ImportReadMessages.raster(com.lingion.sleepy.importing.ocr.RasterFailure.RASTER_UNREADABLE).contains("无法读取"))
        val text = ImportDocumentSniffer.probe("下周三增加实验".toByteArray(), "text/plain")
        assertEquals(ImportContentFormat.TEXT, text.format)
        assertTrue(ImportReadMessages.mismatch(text, ImportDocumentKind.IMAGE).contains("不是图片"))
        assertTrue(ImportReadMessages.EMPTY.contains("为空"))
        assertTrue(ImportReadMessages.PARSE.contains("结构"))
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    private fun riff(chunk: String, payload: ByteArray): ByteArray {
        val padded = if (payload.size % 2 == 0) payload else payload + 0
        val body = chunk.toByteArray() + le32(padded.size) + padded
        val total = 12 + body.size
        return "RIFF".toByteArray() + le32(total - 8) + "WEBP".toByteArray() + body
    }

    private fun isoBmff(major: String, compatible: String): ByteArray {
        val payload = major.toByteArray() + ByteArray(4) + compatible.toByteArray()
        val size = 8 + payload.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + "ftyp".toByteArray() + payload
    }

    private fun bmp(width: Int, height: Int): ByteArray {
        val size = 54 + width * height * 3
        return "BM".toByteArray() + le32(size) + ByteArray(4) + le32(54) + le32(40) + le32(width) + le32(height) +
            byteArrayOf(1, 0, 24, 0) + ByteArray(24)
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
