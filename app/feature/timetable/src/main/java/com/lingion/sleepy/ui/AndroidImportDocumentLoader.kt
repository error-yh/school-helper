/* SPDX-License-Identifier: GPL-3.0-only
 * SAF orchestration adapts the frozen Sleepy ImportSheet lifecycle; all decoding,
 * extraction, OCR and tabular parsing below reuse the existing bounded engines.
 */
package com.lingion.sleepy.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lingion.sleepy.domain.imports.FrozenImportSource
import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.*
import com.lingion.sleepy.importing.ocr.*
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ImportDocumentKind { AUTO, PDF, IMAGE, XLSX, CSV, JSON, TEXT }

/** Content decides binary formats. Names, extensions and school names are never inspected. */
internal object ImportDocumentSniffer {
    fun detect(prefix: ByteArray, mime: String?): ImportDocumentKind? {
        fun starts(vararg bytes: Int) = prefix.size >= bytes.size && bytes.indices.all { (prefix[it].toInt() and 255) == bytes[it] }
        if (starts(0x25,0x50,0x44,0x46,0x2d)) return ImportDocumentKind.PDF
        if (starts(137,80,78,71,13,10,26,10) || starts(255,216,255)) return ImportDocumentKind.IMAGE
        if (starts(80,75,3,4) || starts(80,75,5,6)) return ImportDocumentKind.XLSX
        if (starts(0xd0,0xcf,0x11,0xe0,0xa1,0xb1,0x1a,0xe1)) return null
        if (prefix.isEmpty() || prefix.any { (it.toInt() and 255) < 32 && it !in byteArrayOf(9,10,13) }) return null
        val text = prefix.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
        if (text.startsWith('{') || text.startsWith('[')) return ImportDocumentKind.JSON
        if (mime?.substringBefore(';')?.lowercase() in setOf("text/csv", "application/csv", "text/comma-separated-values")) return ImportDocumentKind.CSV
        return ImportDocumentKind.TEXT
    }
}

internal sealed interface LoadedImportDocument {
    data class Parsed(val source: FrozenImportSource) : LoadedImportDocument
    /** No selected sheet or confirmed mapping is implied by this result. */
    data class Tables(val sheets: List<WorksheetData>, val issues: List<ParseIssue>, val context: FormatParseContext,
        val documentDisplayName:String?=null) : LoadedImportDocument
    data class Rejected(val message: String) : LoadedImportDocument
}

/** Owns a counted immutable copy only during parsing; original provider files remain untouched. */
internal class AndroidImportDocumentLoader(context: Context) {
    private val app = context.applicationContext

    suspend fun load(uri: Uri, kind: ImportDocumentKind, maxWeek: Int, termMonday: LocalDate?, referenceDate: LocalDate): LoadedImportDocument = withContext(Dispatchers.IO) {
        val request = currentCoroutineContext()
        val check = { request.ensureActive() }
        val context = FormatParseContext(UUID.randomUUID().toString(), maxWeek, cancellation = ImportCancellation(check))
        try {
            check()
            val displayName=readDisplayName(uri)
            check()
            val stream = app.contentResolver.openInputStream(uri) ?: return@withContext LoadedImportDocument.Rejected("无法打开所选文件，请重新选择。")
            stream.use { input ->
                BoundedPrivateRasterSource.copyTo(File(app.noBackupFilesDir, "import-raster"), input, context.limits.maxInputBytes, check).use { copy ->
                    val prefix = copy.openRead().use { source ->
                        val buffer = ByteArray(512)
                        var count = 0
                        while (count < buffer.size) {
                            check()
                            val n = source.read(buffer, count, buffer.size - count)
                            if (n < 0) break
                            if (n == 0) {
                                val one = source.read(); if (one < 0) break
                                buffer[count++] = one.toByte()
                            } else count += n
                        }
                        buffer.copyOf(count)
                    }
                    val detected = ImportDocumentSniffer.detect(prefix, app.contentResolver.getType(uri))
                    // An explicit text choice cannot turn a rejected binary file into trusted input.
                    val binary = setOf(ImportDocumentKind.PDF, ImportDocumentKind.IMAGE, ImportDocumentKind.XLSX)
                    val selected = if (kind == ImportDocumentKind.AUTO || detected in binary) detected else if (detected != null) kind else null
                    when (selected) {
                        ImportDocumentKind.PDF -> pdf(copy, context, termMonday, referenceDate)
                        ImportDocumentKind.IMAGE -> image(copy, context, termMonday, referenceDate)
                        ImportDocumentKind.XLSX -> copy.openRead().use { source ->
                            val result = XlsxWorkbookReader().read(source, context)
                            result.workbook?.let { LoadedImportDocument.Tables(it.sheets, result.issues, context.copy(cancellation = ImportCancellation {})) }
                                ?: rejectFormat(result.issues)
                        }
                        ImportDocumentKind.CSV -> copy.openRead().use { source ->
                            val result = Utf8CsvReader().read(source, context)
                            result.table?.let { LoadedImportDocument.Tables(listOf(WorksheetData(1, "CSV 表格", "csv", SheetVisibility.VISIBLE, SheetKind.WORKSHEET, it)), result.issues, context.copy(cancellation = ImportCancellation {})) }
                                ?: rejectFormat(result.issues)
                        }
                        ImportDocumentKind.JSON -> copy.openRead().use { source -> format(VersionedJsonTimetableParser().parse(source, context)) }
                        ImportDocumentKind.TEXT -> copy.openRead().use { source ->
                            val text = BoundedImportInput.readUtf8(source, context)
                            format(NoticeTimetableParser().parse(text, NoticeParseContext(context, referenceDate, termMonday)))
                        }
                        else -> LoadedImportDocument.Rejected("不支持此文件。请选择 PDF、JPG/PNG、XLSX、CSV、JSON 或 UTF-8 文本；旧 XLS 请另存为 XLSX。")
                    }.withDocumentName(displayName).also { check() }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RasterException) { LoadedImportDocument.Rejected("文件超出识别限制或无法安全解码，请缩小文件或检查格式后重试。") }
        catch (_: ImportRejected) { LoadedImportDocument.Rejected("文件内容或编码不受支持，请检查后重试。") }
        catch (_: Exception) { LoadedImportDocument.Rejected("文件读取或识别失败，请重新选择后重试。") }
    }

    /** Called only after the user has selected a sheet and explicitly confirmed its column mapping. */
    fun mapTable(document: LoadedImportDocument.Tables, sheetNumber: Int, mapping: ConfirmedColumnMapping): LoadedImportDocument {
        val sheet = document.sheets.singleOrNull { it.number == sheetNumber }
            ?: return LoadedImportDocument.Rejected("请选择有效的工作表。")
        val table = sheet.table ?: return LoadedImportDocument.Rejected("该工作表不包含可导入的单元格。")
        val parsed = TabularTimetableParser().parse(table, mapping, document.context)
        return format(parsed.copy(issues = (document.issues + sheet.issues + parsed.issues).distinct())).withDocumentName(document.documentDisplayName)
    }

    private fun readDisplayName(uri:Uri):String? = try {
        app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {cursor->
            val column=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if(column<0 || !cursor.moveToFirst() || cursor.isNull(column)) null
            else cursor.getString(column)?.takeIf {it.isNotBlank() && it.length<=256 && it.none(Char::isISOControl)}
        }
    } catch(cancelled:CancellationException) {throw cancelled} catch(_:Exception) {null}

    private fun LoadedImportDocument.withDocumentName(name:String?):LoadedImportDocument = when {
        name==null->this
        this is LoadedImportDocument.Parsed->copy(source=source.withDocumentDisplayName(name))
        this is LoadedImportDocument.Tables->copy(documentDisplayName=name)
        else->this
    }

    private fun format(result: FormatParseResult): LoadedImportDocument =
        if (result.draft == null) rejectFormat(result.issues) else LoadedImportDocument.Parsed(FrozenImportSource.fromFormat(result))

    private fun rejectFormat(issues: List<ParseIssue>): LoadedImportDocument.Rejected = LoadedImportDocument.Rejected(
        if (issues.any { it.code == "LIMIT_EXCEEDED" }) "文件超过导入容量限制，请缩小后重试。" else "文件结构无法解析，请检查格式和内容。")

    private suspend fun image(copy: BoundedPrivateRasterSource, context: FormatParseContext, monday: LocalDate?, reference: LocalDate): LoadedImportDocument {
        val resources = RasterResources(OcrWorkBudget())
        val page = copy.openRead().use { input -> AndroidImportImageDecoder(app).withDecoded(input, resources) { decoded -> recognize(decoded.page, resources) } }
        val result = OcrTimetableParser().parse(OcrLayoutDocument(listOf(page)), OcrLayoutContext(context, confirmedTermMonday = monday, referenceDate = reference))
        return if (result.formatResult.draft == null) rejectFormat(result.formatResult.issues) else LoadedImportDocument.Parsed(FrozenImportSource.fromOcr(result))
    }

    private suspend fun recognize(page: OcrRasterPage, resources: RasterResources): OcrLayoutPage {
        return RapidTimetableRecognizer.recognize(app, page, resources)
    }

    private suspend fun pdf(copy: BoundedPrivateRasterSource, context: FormatParseContext, monday: LocalDate?, reference: LocalDate): LoadedImportDocument {
        val extraction = copy.openRead().use { AndroidPdfTextExtractor(app).extractChecked(it, context.cancellation::check) }
        val document = extraction.document ?: return rejectFormat(extraction.issues)
        val native = PdfTimetableParser().parse(PositionedDocument(document.pages.filter { it.number !in extraction.pagesNeedingOcr }))
        if (extraction.pagesNeedingOcr.isEmpty()) return format(FormatParseResult(native, issues = extraction.issues))
        val resources = RasterResources(OcrWorkBudget())
        val pages = mutableListOf<OcrLayoutPage>()
        // The renderer reassesses extraction permissions against this same immutable copy.
        copy.openRead().use { input -> AndroidPdfOcrRenderer(app).withRenderedPages(input, extraction.pagesNeedingOcr.sorted(), resources) { page ->
            pages += recognize(page, resources).copy(textLayerEvidence = document.pages.single { it.number == page.pageNumber })
        } }
        val textUnits = document.pages.sumOf { it.text.sumOf { span -> span.rawText.length.toLong() } } +
            pages.sumOf { it.variants.sumOf { variant -> variant.spans.sumOf { span -> span.rawText.length.toLong() } } }
        if (textUnits > context.limits.maxTextUnits) throw RasterException(RasterFailure.LIMIT_EXCEEDED)
        val ocr = OcrTimetableParser().parse(OcrLayoutDocument(pages), OcrLayoutContext(context, confirmedTermMonday = monday, referenceDate = reference))
        if (ocr.formatResult.draft == null) return rejectFormat(ocr.formatResult.issues)
        val merged = mergePdfTextAndOcr(native, ocr, extraction.issues)
        val draft = checkNotNull(merged.formatResult.draft)
        if (draft.courses.size > context.limits.maxCourses || draft.courses.sumOf { it.meetings.size } > context.limits.maxMeetings)
            throw RasterException(RasterFailure.LIMIT_EXCEEDED)
        return LoadedImportDocument.Parsed(FrozenImportSource.fromOcr(merged))
    }
}

/** OCR courses stay first so all original candidate/source/coverage paths remain exact. */
internal fun mergePdfTextAndOcr(native: RichTimetableDraft, ocr: OcrLayoutParseResult, extractionIssues: List<ParseIssue>): OcrLayoutParseResult {
    val recognized = requireNotNull(ocr.formatResult.draft)
    val merged = recognized.copy(courses = recognized.courses + native.courses,
        periodProposals = recognized.periodProposals + native.periodProposals,
        issues = recognized.issues + native.issues, unparsed = recognized.unparsed + native.unparsed,
        missingFields = recognized.missingFields + native.missingFields, adapterIds = recognized.adapterIds + native.adapterIds)
    return ocr.copy(formatResult = ocr.formatResult.copy(draft = merged, issues = ocr.formatResult.issues + extractionIssues))
}
