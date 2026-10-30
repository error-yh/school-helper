/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.IOException
import java.io.InputStream

/** A null document is a structural rejection. Sparse/unreliable text pages explicitly request OCR. */
data class PdfTextExtraction(
    val document: PositionedDocument? = null,
    val issues: List<ParseIssue> = emptyList(),
    val pagesNeedingOcr: Set<Int> = emptySet(),
)

/** PDFBox Android 2.0.27.0, read-only text layer extraction. Does not load attachments, URLs or actions. */
class AndroidPdfTextExtractor(
    context: Context,
    private val maxBytes: Int = 12_000_000,
    private val maxPages: Int = 30,
    private val maxGlyphs: Int = 500_000,
) {
    private val appContext=context.applicationContext

    /** Caller owns [input]. Run off the main thread; this method deliberately performs no persistence. */
    fun extract(input: InputStream): PdfTextExtraction = extractChecked(input, {})

    internal fun extractChecked(input: InputStream, checkCancellation: () -> Unit,
        inspectPages: ((List<PdfPageRasterMetadata>) -> Unit)? = null): PdfTextExtraction = try {
        require(maxPages in 1..100 && maxGlyphs in 1..2_000_000)
        val bytes=BoundedPdfInput.read(input,maxBytes,checkCancellation)
        PDFBoxResourceLoader.init(appContext)
        PDDocument.load(bytes).use { document ->
            if(document.numberOfPages !in 1..maxPages) return PdfTextExtraction(issues=listOf(ParseIssue("LIMIT_EXCEEDED","PDF exceeds the page limit")))
            if(document.isEncrypted || !document.currentAccessPermission.canExtractContent())
                return PdfTextExtraction(issues=listOf(ParseIssue("PDF_EXTRACTION_RESTRICTED","PDF text extraction is restricted")))
            checkCancellation()
            inspectPages?.invoke(document.pages.mapIndexed { index,page ->
                checkCancellation()
                val crop=page.cropBox; val media=page.mediaBox
                PdfPageRasterMetadata(index+1,crop.lowerLeftX,crop.lowerLeftY,crop.upperRightX,crop.upperRightY,
                    page.rotation,page.userUnit,media.lowerLeftX,media.lowerLeftY,media.upperRightX,media.upperRightY)
            })
            // PDFTextStripper skips startPage/endPage for pages without a content stream.
            // Inventory the document first so blank pages retain their real positions and dimensions.
            val pages=document.pages.mapIndexed { index,page ->
                checkCancellation()
                val landscape=page.rotation % 180 != 0
                PositionedPage(index+1,if(landscape) page.cropBox.height else page.cropBox.width,
                    if(landscape) page.cropBox.width else page.cropBox.height,emptyList())
            }.toMutableList()
            var glyphCount=0
            val stripper=object: PDFTextStripper() {
                private var number=0
                private var spans=mutableListOf<PositionedText>()
                override fun startPage(page: PDPage) {
                    number=getCurrentPageNo(); spans=mutableListOf()
                    super.startPage(page)
                }
                override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                    for(t in textPositions) {
                        checkCancellation()
                        glyphCount+=t.unicode.length
                        if(glyphCount>maxGlyphs) throw PdfInputLimitException()
                        spans+=PositionedText(t.unicode,SourcePosition(number,TextBox(t.xDirAdj,t.yDirAdj-t.heightDir,t.xDirAdj+t.widthDirAdj,t.yDirAdj)))
                    }
                }
                override fun endPage(page: PDPage) {
                    pages[number-1]=pages[number-1].copy(text=spans.toList())
                    super.endPage(page)
                }
            }
            stripper.sortByPosition=true
            stripper.getText(document)
            checkCancellation()
            val ocrPages=pages.filter { page ->
                val text=page.text.joinToString(""){it.text}.filterNot { it.isWhitespace() }
                text.length<20 || text.count { it=='\uFFFD' || it=='\u0000' } > text.length/10
            }.map { it.number }.toSet()
            PdfTextExtraction(PositionedDocument(pages),if(ocrPages.isEmpty()) emptyList() else listOf(ParseIssue("OCR_REQUIRED","Some pages lack a reliable text layer")),ocrPages)
        }
    } catch(_: PdfInputLimitException) {
        PdfTextExtraction(issues=listOf(ParseIssue("LIMIT_EXCEEDED","PDF exceeds extraction limits")))
    } catch(_: IOException) {
        // Never copy library exception messages: they may contain source text or private file names.
        PdfTextExtraction(issues=listOf(ParseIssue("PDF_UNREADABLE","PDF could not be read; it may be damaged or password protected")))
    } catch(_: IllegalArgumentException) {
        PdfTextExtraction(issues=listOf(ParseIssue("PDF_INVALID","PDF content or extraction options are invalid")))
    }
}

/** Internal metadata inventory is never a render permission capability on its own. */
internal data class PdfPageRasterMetadata(val number: Int, val left: Float, val bottom: Float, val right: Float, val top: Float,
    val rotation: Int, val userUnit: Float, val mediaLeft: Float, val mediaBottom: Float, val mediaRight: Float, val mediaTop: Float)
