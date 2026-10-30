/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidPdfTextExtractorTest {
    private val context get()=ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun pdf(withText: Boolean=true): ByteArray {
        PDFBoxResourceLoader.init(context)
        return PDDocument().use { doc ->
            val page=PDPage();doc.addPage(page)
            if(withText) PDPageContentStream(doc,page).use { stream ->
                stream.beginText();stream.setFont(PDType1Font.HELVETICA,12f);stream.newLineAtOffset(50f,700f)
                stream.showText("Synthetic timetable text positions");stream.endText()
            }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
    }
    @Test fun extractsSyntheticPdfWithTopLeftBounds() {
        val result=AndroidPdfTextExtractor(context).extract(ByteArrayInputStream(pdf()))
        assertTrue(result.issues.isEmpty())
        val p=result.document!!.pages.single()
        assertEquals(1,p.number)
        assertTrue(p.text.isNotEmpty())
        assertTrue(p.text.all { it.position.page==1 && it.position.bounds.left>=0 && it.position.bounds.top>=0 })
        assertEquals("Synthetic timetable text positions",p.text.joinToString(""){it.text})
    }
    @Test fun imageOnlyPageRequestsOcrAndCorruptionIsReportedWithoutExceptionText() {
        val scan=AndroidPdfTextExtractor(context).extract(ByteArrayInputStream(pdf(false)))
        assertEquals(setOf(1),scan.pagesNeedingOcr)
        assertEquals("OCR_REQUIRED",scan.issues.single().code)
        val corrupt=AndroidPdfTextExtractor(context).extract(ByteArrayInputStream("not a pdf".toByteArray()))
        assertNull(corrupt.document)
        assertEquals("PDF_UNREADABLE",corrupt.issues.single().code)
    }
    @Test fun byteLimitIsEnforcedBeforeLoading() {
        val result=AndroidPdfTextExtractor(context,maxBytes=3).extract(ByteArrayInputStream(ByteArray(4)))
        assertNull(result.document)
        assertEquals("LIMIT_EXCEEDED",result.issues.single().code)
    }
    @Test fun blankPagesBeforeAndAfterTextRetainNumbersDimensionsAndOcrRequirements() {
        PDFBoxResourceLoader.init(context)
        val bytes=PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle(300f,400f)))
            val textPage=PDPage(PDRectangle(500f,600f)).also { it.rotation=90 };doc.addPage(textPage)
            PDPageContentStream(doc,textPage).use { stream ->
                stream.beginText();stream.setFont(PDType1Font.HELVETICA,12f);stream.newLineAtOffset(50f,400f)
                stream.showText("Synthetic middle page retains original page number");stream.endText()
            }
            doc.addPage(PDPage(PDRectangle(700f,800f)))
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        val result=AndroidPdfTextExtractor(context).extract(ByteArrayInputStream(bytes))
        assertEquals(listOf(1,2,3),result.document!!.pages.map { it.number })
        assertEquals(setOf(1,3),result.pagesNeedingOcr)
        assertEquals(listOf(300f to 400f,600f to 500f,700f to 800f),result.document.pages.map { it.width to it.height })
        assertTrue(result.document.pages.first().text.isEmpty())
        assertTrue(result.document.pages.last().text.isEmpty())
        assertTrue(result.document.pages[1].text.isNotEmpty())
        assertTrue(result.document.pages[1].text.all { it.position.page==2 })
    }
    @Test fun multipleBlankPagesAreAllPreservedAsOcrCandidates() {
        PDFBoxResourceLoader.init(context)
        val bytes=PDDocument().use { doc ->
            repeat(3) { doc.addPage(PDPage()) }
            ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
        }
        val result=AndroidPdfTextExtractor(context).extract(ByteArrayInputStream(bytes))
        assertEquals(listOf(1,2,3),result.document!!.pages.map { it.number })
        assertEquals(setOf(1,2,3),result.pagesNeedingOcr)
        assertTrue(result.document.pages.all { it.text.isEmpty() })
    }
}
