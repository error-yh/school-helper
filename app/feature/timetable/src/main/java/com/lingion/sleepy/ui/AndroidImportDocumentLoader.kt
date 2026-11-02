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
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class ImportDocumentKind { AUTO, PDF, IMAGE, XLSX, CSV, JSON, TEXT }

/** Bounded content families. Only bytes decide; names and extensions never participate. */
internal enum class ImportContentFormat { PDF, PNG, JPEG, WEBP, HEIF, GIF, BMP, TIFF, ZIP, OLE, JSON, CSV, TEXT, BINARY }

internal data class ImportContentProbe(val format: ImportContentFormat, val animated: Boolean = false) {
    val documentKind: ImportDocumentKind? get() = when (format) {
        ImportContentFormat.PDF -> ImportDocumentKind.PDF
        ImportContentFormat.PNG, ImportContentFormat.JPEG -> ImportDocumentKind.IMAGE
        ImportContentFormat.WEBP -> if (animated) null else ImportDocumentKind.IMAGE
        ImportContentFormat.ZIP -> ImportDocumentKind.XLSX
        ImportContentFormat.JSON -> ImportDocumentKind.JSON
        ImportContentFormat.CSV -> ImportDocumentKind.CSV
        ImportContentFormat.TEXT -> null
        else -> null
    }
    /** Decoders that already pass the bounded container/orientation gate. */
    val supportedImage: Boolean get() = format == ImportContentFormat.PNG || format == ImportContentFormat.JPEG ||
        (format == ImportContentFormat.WEBP && !animated)
    val label: String get() = when (format) {
        ImportContentFormat.PDF -> "PDF"
        ImportContentFormat.PNG -> "PNG"
        ImportContentFormat.JPEG -> "JPG"
        ImportContentFormat.WEBP -> if (animated) "动画 WebP" else "WebP"
        ImportContentFormat.HEIF -> if (animated) "动画 HEIF/AVIF" else "HEIF/HEIC 或 AVIF"
        ImportContentFormat.GIF -> "GIF"
        ImportContentFormat.BMP -> "BMP"
        ImportContentFormat.TIFF -> "TIFF"
        ImportContentFormat.ZIP -> "ZIP"
        ImportContentFormat.OLE -> "旧版 XLS"
        ImportContentFormat.JSON -> "JSON 文本"
        ImportContentFormat.CSV -> "CSV 文本"
        ImportContentFormat.TEXT -> "UTF-8 文本"
        ImportContentFormat.BINARY -> "未知二进制"
    }
}

/** Content decides binary formats. Names, extensions and school names are never inspected. */
internal object ImportDocumentSniffer {
    private val csvMimes = setOf("text/csv", "application/csv", "text/comma-separated-values")
    private val heifBrands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "mif2", "msf1", "miaf", "avif", "avis")

    fun detect(prefix: ByteArray, mime: String?): ImportDocumentKind? = probe(prefix, mime).documentKind

    /** [totalBytes] is the counted private copy, used only to bound container size fields. */
    fun probe(prefix: ByteArray, mime: String?, totalBytes: Long? = null): ImportContentProbe {
        fun starts(vararg bytes: Int) = prefix.size >= bytes.size && bytes.indices.all { (prefix[it].toInt() and 255) == bytes[it] }
        if (starts(0x25, 0x50, 0x44, 0x46, 0x2d)) return ImportContentProbe(ImportContentFormat.PDF)
        if (starts(137, 80, 78, 71, 13, 10, 26, 10)) return ImportContentProbe(ImportContentFormat.PNG)
        if (starts(255, 216, 255)) return ImportContentProbe(ImportContentFormat.JPEG)
        if (starts(80, 75, 3, 4) || starts(80, 75, 5, 6)) return ImportContentProbe(ImportContentFormat.ZIP)
        if (starts(0xd0, 0xcf, 0x11, 0xe0, 0xa1, 0xb1, 0x1a, 0xe1)) return ImportContentProbe(ImportContentFormat.OLE)
        webp(prefix, totalBytes)?.let { return it }
        heif(prefix)?.let { return it }
        if (starts(0x47, 0x49, 0x46, 0x38) && prefix.size >= 6 && (prefix[4].toInt() and 255) in setOf(0x37, 0x39) && prefix[5] == 0x61.toByte())
            return ImportContentProbe(ImportContentFormat.GIF)
        if (bmp(prefix, totalBytes)) return ImportContentProbe(ImportContentFormat.BMP)
        if (starts(0x49, 0x49, 0x2a, 0x00) || starts(0x4d, 0x4d, 0x00, 0x2a)) return ImportContentProbe(ImportContentFormat.TIFF)
        if (prefix.isEmpty() || prefix.any { (it.toInt() and 255) < 32 && it !in byteArrayOf(9, 10, 13) })
            return ImportContentProbe(ImportContentFormat.BINARY)
        val text = prefix.toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
        if (text.startsWith('{') || text.startsWith('[')) return ImportContentProbe(ImportContentFormat.JSON)
        if (mime?.substringBefore(';')?.trim()?.lowercase() in csvMimes) return ImportContentProbe(ImportContentFormat.CSV)
        return ImportContentProbe(ImportContentFormat.TEXT)
    }

    private fun ascii(prefix: ByteArray, offset: Int, length: Int): String? =
        if (offset < 0 || prefix.size < offset + length) null else String(prefix, offset, length, Charsets.US_ASCII)

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 255) or ((bytes[offset + 1].toLong() and 255) shl 8) or
            ((bytes[offset + 2].toLong() and 255) shl 16) or ((bytes[offset + 3].toLong() and 255) shl 24)

    private fun be32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 255) shl 24) or ((bytes[offset + 1].toLong() and 255) shl 16) or
            ((bytes[offset + 2].toLong() and 255) shl 8) or (bytes[offset + 3].toLong() and 255)

    /** RIFF/WEBP framing and the first chunk family. A truncated or mis-sized copy is still
     * named WebP so the bounded decoder gate can report damage instead of "unknown file".
     */
    private fun webp(prefix: ByteArray, total: Long?): ImportContentProbe? {
        if (prefix.size < 16 || ascii(prefix, 0, 4) != "RIFF" || ascii(prefix, 8, 4) != "WEBP") return null
        if (ascii(prefix, 12, 4) !in setOf("VP8 ", "VP8L", "VP8X")) return null
        var animated = false
        var offset = 12
        var chunks = 0
        while (offset + 8 <= prefix.size && chunks++ < 64) {
            val name = ascii(prefix, offset, 4) ?: break
            val size = le32(prefix, offset + 4)
            if (name == "ANIM" || name == "ANMF") animated = true
            if (name == "VP8X" && offset + 9 <= prefix.size) animated = animated || ((prefix[offset + 8].toInt() and 0x02) != 0)
            if (size < 0 || size > Int.MAX_VALUE) break
            val next = offset.toLong() + 8 + size + (size and 1L)
            if (next <= offset) break
            if (next >= prefix.size) break
            if (total != null && next > total) break
            offset = next.toInt()
        }
        return ImportContentProbe(ImportContentFormat.WEBP, animated)
    }

    /** ISO base media file type box: HEIF/HEIC or AVIF brands only, never arbitrary "ftyp". */
    private fun heif(prefix: ByteArray): ImportContentProbe? {
        if (prefix.size < 16 || ascii(prefix, 4, 4) != "ftyp") return null
        val box = be32(prefix, 0)
        if (box < 16 || box > prefix.size) return null
        val brands = mutableListOf<String>()
        ascii(prefix, 8, 4)?.trim()?.lowercase()?.let { brands += it }
        var offset = 16
        while (offset + 4 <= box.toInt()) {
            ascii(prefix, offset, 4)?.trim()?.lowercase()?.let { brands += it }
            offset += 4
        }
        if (brands.none { it in heifBrands }) return null
        return ImportContentProbe(ImportContentFormat.HEIF, brands.any { it == "avis" || it == "msf1" })
    }

    private fun bmp(prefix: ByteArray, total: Long?): Boolean {
        if (prefix.size < 26 || prefix[0] != 'B'.code.toByte() || prefix[1] != 'M'.code.toByte()) return false
        val declared = le32(prefix, 2)
        val pixelOffset = le32(prefix, 10)
        return declared >= 26 && pixelOffset >= 26 && pixelOffset <= declared && (total == null || declared <= total + 2)
    }
}

internal sealed interface LoadedImportDocument {
    data class Parsed(val source: FrozenImportSource) : LoadedImportDocument
    /** No selected sheet or confirmed mapping is implied by this result. */
    data class Tables(val sheets: List<WorksheetData>, val issues: List<ParseIssue>, val context: FormatParseContext,
        val documentDisplayName:String?=null) : LoadedImportDocument
    data class Rejected(val message: String) : LoadedImportDocument
}

/** Stable, provider-independent failure messages; each names the real stage that failed. */
internal object ImportReadMessages {
    const val EMPTY = "所选文件为空或读取不到任何内容，请重新选择原始文件后重试。"
    const val OPEN = "无法打开所选文件，请重新选择。"
    const val UNREADABLE = "无法读取所选文件（权限或存储不可用），请重新选择后重试。"
    const val GENERIC = "文件读取或识别失败，请重新选择后重试。"
    const val LIMIT = "文件超出识别限制（大小、像素或内存预算），请缩小后重试。"
    const val UNSUPPORTED_IMAGE = "暂不支持该图片的编码或变体，请使用静态 JPG/PNG 图片后重试。"
    const val INVALID_IMAGE = "图片内容不完整或已损坏，无法安全解码，请重新导出为 JPG/PNG 后重试。"
    const val INVALID_ORIENTATION = "图片方向信息无效，无法安全显示，请重新导出为 JPG/PNG 后重试。"
    const val PDF_REJECTED = "PDF 无法安全解析（可能已加密或损坏），请导出为图片或文本后重试。"
    const val PDF_GEOMETRY = "PDF 页面尺寸或旋转不受支持，请重新导出后重试。"
    const val PARSE = "文件结构无法解析，请检查格式和内容。"
    const val PARSE_LIMIT = "文件超过导入容量限制，请缩小后重试。"
    const val TEXT_NOTICE_REJECTED = "已取消文字通知导入功能，不再支持导入普通文本或通知文件；请使用 JSON、CSV 或表格等结构化课表文件。"

    fun content(probe: ImportContentProbe): String = when (probe.format) {
        ImportContentFormat.OLE -> "所选文件是旧版 XLS（Excel 97-2003）表格；当前仅支持 XLSX，请另存为 XLSX 后重试。"
        ImportContentFormat.WEBP -> if (probe.animated)
            "所选文件是动画 WebP 图片，当前不支持动画图片；请改用静态 JPG/PNG 图片。"
        else "所选文件是 WebP 图片，当前版本暂不支持该编码；请转换为 JPG/PNG 后重试。"
        ImportContentFormat.HEIF -> if (probe.animated)
            "所选文件是动画 HEIF/AVIF 图片，当前不支持动画图片；请改用静态 JPG/PNG 图片。"
        else "所选文件是 HEIF/HEIC 或 AVIF 图片，当前版本暂不支持该编码；请转换为 JPG/PNG，或把相机格式改为 JPEG 后重试。"
        ImportContentFormat.GIF -> "所选文件是 GIF 图片，当前版本不支持 GIF；请转换为静态 JPG/PNG 后重试。"
        ImportContentFormat.BMP -> "所选文件是 BMP 图片，请转换为 JPG/PNG 后重试。"
        ImportContentFormat.TIFF -> "所选文件是 TIFF 图片，请转换为 JPG/PNG 后重试。"
        ImportContentFormat.TEXT -> TEXT_NOTICE_REJECTED
        ImportContentFormat.BINARY -> "无法识别所选文件的内容格式。请选择 PDF、JPG/PNG、XLSX、CSV 或 JSON 文件；文件名后缀不作为格式判断依据。"
        else -> "所选内容不是可识别的 ${probe.label} 文件，请重新选择或切换导入类型。"
    }

    /** The requested type is known but the bytes are a different, still recognizable family. */
    fun mismatch(probe: ImportContentProbe, requested: ImportDocumentKind): String {
        val label = when (requested) {
            ImportDocumentKind.PDF -> "PDF"
            ImportDocumentKind.IMAGE -> "图片"
            ImportDocumentKind.XLSX -> "XLSX 表格"
            else -> "所选类型"
        }
        return "所选文件内容是${probe.label}，不是$label；请选择正确的导入类型或更换文件。"
    }

    fun raster(failure: RasterFailure): String = when (failure) {
        RasterFailure.LIMIT_EXCEEDED -> LIMIT
        RasterFailure.UNSUPPORTED_FORMAT -> UNSUPPORTED_IMAGE
        RasterFailure.INVALID_IMAGE -> INVALID_IMAGE
        RasterFailure.INVALID_ORIENTATION -> INVALID_ORIENTATION
        RasterFailure.PDF_REJECTED -> PDF_REJECTED
        RasterFailure.PDF_GEOMETRY_UNSUPPORTED -> PDF_GEOMETRY
        RasterFailure.RASTER_UNREADABLE -> UNREADABLE
    }
}

/** Owns a counted immutable copy only during parsing; original provider files remain untouched. */
internal class AndroidImportDocumentLoader(context: Context) {
    private val app = context.applicationContext

    suspend fun load(uri: Uri, kind: ImportDocumentKind, maxWeek: Int, termMonday: LocalDate?, referenceDate: LocalDate,
                     readContext: ImportReadContext = ImportReadContext()): LoadedImportDocument = withContext(Dispatchers.IO) {
        val request = currentCoroutineContext()
        val check = { request.ensureActive() }
        val context = FormatParseContext(UUID.randomUUID().toString(), maxWeek, cancellation = ImportCancellation(check))
        val scheme = uri.scheme
        val provider = if (scheme == "content") uri.authority else null
        var mime: String? = null
        var probe: ImportContentProbe? = null
        var selected: ImportDocumentKind? = null
        var diagnosed = false

        fun diagnose(stage: String, bytes: Long, digest: String?, magic: String?) {
            diagnosed = true
            ImportReadDiagnostics.record(app,
                ImportReadFacts(kind.name, readContext.hasDocumentRequest, readContext.textKind, scheme, provider, mime, bytes,
                    digest, magic, probe?.format?.name?.let { f -> if (probe?.animated == true) "$f (animated)" else f }, selected?.name, stage))
        }

        fun reject(message: String, stage: String, copy: BoundedPrivateRasterSource?, prefix: ByteArray?) =
            LoadedImportDocument.Rejected(message).also {
                val digest = copy?.let { source -> runCatching { sha256(source) }.getOrNull() }
                if (!diagnosed) diagnose(stage, copy?.byteCount ?: 0L, digest, prefix?.let(::magicHex))
            }

        try {
            check()
            val displayName = readDisplayName(uri)
            check()
            val stream = try {
                app.contentResolver.openInputStream(uri)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: SecurityException) {
                return@withContext reject(ImportReadMessages.UNREADABLE, ImportReadFacts.STAGE_SNIFF, null, null)
            } catch (_: java.io.FileNotFoundException) {
                return@withContext reject(ImportReadMessages.UNREADABLE, ImportReadFacts.STAGE_SNIFF, null, null)
            }
            ?: return@withContext reject(ImportReadMessages.OPEN, ImportReadFacts.STAGE_SNIFF, null, null)
            stream.use { input ->
                BoundedPrivateRasterSource.copyTo(File(app.noBackupFilesDir, "import-raster"), input, context.limits.maxInputBytes, check).use { copy ->
                    if (copy.byteCount < 1) return@withContext reject(ImportReadMessages.EMPTY, ImportReadFacts.STAGE_EMPTY, copy, null)
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
                    mime = runCatching { app.contentResolver.getType(uri) }.getOrNull()
                    val detectedProbe = ImportDocumentSniffer.probe(prefix, mime, copy.byteCount).also { probe = it }
                    val detected = detectedProbe.documentKind
                    // An explicit text choice cannot turn a rejected binary file into trusted input.
                    val binary = setOf(ImportDocumentKind.PDF, ImportDocumentKind.IMAGE, ImportDocumentKind.XLSX)
                    selected = if (kind == ImportDocumentKind.AUTO || detected in binary) detected else if (detected != null) kind else null
                    check()
                    val result = when (selected) {
                        ImportDocumentKind.PDF -> if (detectedProbe.format == ImportContentFormat.PDF) pdf(copy, context, termMonday, referenceDate)
                            else reject(ImportReadMessages.mismatch(detectedProbe, ImportDocumentKind.PDF), ImportReadFacts.STAGE_SNIFF, copy, prefix)
                        ImportDocumentKind.IMAGE -> if (detectedProbe.supportedImage) image(copy, context, termMonday, referenceDate)
                            else reject(ImportReadMessages.mismatch(detectedProbe, ImportDocumentKind.IMAGE), ImportReadFacts.STAGE_SNIFF, copy, prefix)
                        ImportDocumentKind.XLSX -> if (detectedProbe.format == ImportContentFormat.ZIP) copy.openRead().use { source ->
                            val result = XlsxWorkbookReader().read(source, context)
                            result.workbook?.let { LoadedImportDocument.Tables(it.sheets, result.issues, context.copy(cancellation = ImportCancellation {})) }
                                ?: rejectFormat(result.issues)
                        } else reject(ImportReadMessages.mismatch(detectedProbe, ImportDocumentKind.XLSX), ImportReadFacts.STAGE_SNIFF, copy, prefix)
                        ImportDocumentKind.CSV -> copy.openRead().use { source ->
                            val result = Utf8CsvReader().read(source, context)
                            result.table?.let { LoadedImportDocument.Tables(listOf(WorksheetData(1, "CSV 表格", "csv", SheetVisibility.VISIBLE, SheetKind.WORKSHEET, it)), result.issues, context.copy(cancellation = ImportCancellation {})) }
                                ?: rejectFormat(result.issues)
                        }
                        ImportDocumentKind.JSON -> copy.openRead().use { source -> format(VersionedJsonTimetableParser().parse(source, context)) }
                        ImportDocumentKind.TEXT -> reject(ImportReadMessages.TEXT_NOTICE_REJECTED, ImportReadFacts.STAGE_SNIFF, copy, prefix)
                        else -> reject(ImportReadMessages.content(detectedProbe), ImportReadFacts.STAGE_SNIFF, copy, prefix)
                    }
                    if (!diagnosed) {
                        val stage = when (selected) {
                            ImportDocumentKind.IMAGE -> ImportReadFacts.STAGE_IMAGE
                            ImportDocumentKind.PDF -> ImportReadFacts.STAGE_PDF
                            ImportDocumentKind.XLSX, ImportDocumentKind.CSV -> ImportReadFacts.STAGE_TABLE
                            null -> ImportReadFacts.STAGE_REJECTED
                            else -> ImportReadFacts.STAGE_TEXT
                        }
                        diagnose(if (result is LoadedImportDocument.Parsed || result is LoadedImportDocument.Tables) ImportReadFacts.STAGE_OK else stage,
                            copy.byteCount, runCatching { sha256(copy) }.getOrNull(), magicHex(prefix))
                    }
                    result.withDocumentName(displayName).also { check() }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: RasterException) {
            LoadedImportDocument.Rejected(ImportReadMessages.raster(failure.code)).also {
                diagnose(ImportReadFacts.STAGE_DECODE, 0L, null, null)
            }
        }
        catch (_: ImportRejected) { LoadedImportDocument.Rejected("文件内容或编码不受支持，请检查后重试。").also { diagnose(ImportReadFacts.STAGE_REJECTED, 0L, null, null) } }
        catch (_: Exception) { LoadedImportDocument.Rejected(ImportReadMessages.GENERIC).also { diagnose(ImportReadFacts.STAGE_REJECTED, 0L, null, null) } }
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
        if (issues.any { it.code == "LIMIT_EXCEEDED" }) ImportReadMessages.PARSE_LIMIT else ImportReadMessages.PARSE)

    private fun sha256(source: BoundedPrivateRasterSource): String = source.openRead().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** Only the leading 16 bytes; enough to identify the container without carrying the file. */
    private fun magicHex(prefix: ByteArray): String = prefix.take(16).joinToString(" ") { byte -> "%02X".format(byte) }

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