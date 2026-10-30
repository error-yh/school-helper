/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import com.lingion.sleepy.importing.*
import java.time.LocalDate
import java.time.LocalTime
import java.math.BigDecimal

/** Product bounds, also injectable downwards for deterministic resource tests. */
data class ImportLimits(
    val maxInputBytes: Int = 12_000_000,
    val maxFieldUnits: Int = 16_384,
    val maxTextUnits: Int = 500_000,
    val maxJsonDepth: Int = 32,
    val maxJsonElements: Int = 100_000,
    val maxCourses: Int = 2_000,
    val maxMeetings: Int = 10_000,
    val maxExceptions: Int = 10_000,
    val maxCells: Int = 50_000,
    val maxRows: Int = 100_000,
    val maxColumns: Int = 16_384,
    val maxZipEntries: Int = 512,
    val maxInflatedBytes: Int = 64_000_000,
    val maxInflatedEntryBytes: Int = 16_000_000,
    val maxExpansionRatio: Int = 200,
    val expansionRatioThreshold: Int = 1_000_000,
    val maxXmlDepth: Int = 64,
    val maxXmlAttributes: Int = 64,
    val maxXmlEvents: Int = 1_000_000,
    val maxSheets: Int = 200,
    val maxMerges: Int = 10_000,
    val maxMergeComparisons: Int = 1_000_000,
) {
    init {
        require(maxInputBytes in 1..12_000_000 && maxFieldUnits in 1..16_384 && maxTextUnits in 1..500_000)
        require(maxJsonDepth in 1..32 && maxJsonElements in 1..100_000)
        require(maxCourses in 1..2_000 && maxMeetings in 1..10_000 && maxExceptions in 1..10_000)
        require(maxCells in 1..50_000 && maxRows in 1..100_000 && maxColumns in 1..16_384)
        require(maxZipEntries in 1..512 && maxInflatedBytes in 1..64_000_000 && maxInflatedEntryBytes in 1..16_000_000)
        require(maxExpansionRatio in 1..200 && expansionRatioThreshold in 1..1_000_000)
        require(maxXmlDepth in 1..64 && maxXmlAttributes in 1..64 && maxXmlEvents in 1..1_000_000)
        require(maxSheets in 1..200 && maxMerges in 1..10_000)
        require(maxMergeComparisons in 1..1_000_000)
    }
}

fun interface ImportCancellation { fun check() }

/** No clock, URI resolution, semester identity, database, or dispatcher ownership. */
data class FormatParseContext(
    val sourceId: String,
    val maxWeek: Int,
    val limits: ImportLimits = ImportLimits(),
    val cancellation: ImportCancellation = ImportCancellation { },
) {
    init { require(sourceId.isNotBlank() && sourceId.length <= 256); require(maxWeek in 1..1000) }
}

/** Indices are zero based and live only for this immutable parse result. */
data class DraftPath(val courseIndex: Int? = null, val meetingIndex: Int? = null, val exceptionIndex: Int? = null, val field: String? = null)
data class ImportSourceRef(
    val sourceId: String, val sourceLocator: String, val rawText: String, val path: DraftPath,
    val row: Int? = null, val column: Int? = null, val formula: String? = null,
) { init { require(row == null || row > 0); require(column == null || column > 0) } }

/** Null payload means structural rejection; never adopt a failed document's partial drafts. */
data class FormatParseResult(
    val draft: RichTimetableDraft?,
    val sources: List<ImportSourceRef> = emptyList(),
    val issues: List<ParseIssue> = emptyList(),
    val semesterStartDateProposal: LocalDate? = null,
    val courseKeys: Map<Int, String> = emptyMap(),
    val meetingKeys: Map<DraftPath, String> = emptyMap(),
    val pendingExceptions: List<PendingExceptionAttachment> = emptyList(),
    val relativeDates: List<RelativeDateProposal> = emptyList(),
    val unparsedSources: List<ImportSourceRef> = emptyList(),
)

/** Appended defaults preserve CSV positional constructors. Formula caches never have typedValue. */
data class ImportCell(
    val column: Int, val rawText: String, val sourceLocator: String,
    val typedValue: CellValue? = null,
    val style: CellStyle? = null,
    val formula: String? = null,
    val mergeAnchor: String? = null,
    val issues: List<ParseIssue> = emptyList(),
)

sealed interface CellValue {
    data object Empty : CellValue
    data class Text(val text: String) : CellValue
    data class Number(val number: BigDecimal, val temporal: ExcelTemporalValue? = null) : CellValue
    data class BooleanValue(val value: Boolean) : CellValue
    data class Temporal(val value: ExcelTemporalValue) : CellValue
}

/** Capability only: subsequent confirmed column mapping decides whether date/time is used. */
data class ExcelTemporalValue(val date: LocalDate? = null, val time: LocalTime? = null)
enum class NumberFormatKind { GENERAL, NUMBER, TEXT, DATE, TIME, DATE_TIME, UNSUPPORTED }
data class CellStyle(val index: Int, val numberFormatId: Int, val formatCode: String?, val kind: NumberFormatKind)
enum class SheetVisibility { VISIBLE, HIDDEN, VERY_HIDDEN }
enum class SheetKind { WORKSHEET, UNSUPPORTED }
/** Inclusive real range; covered coordinates are not expanded into synthetic cells. */
data class MergeRegion(val firstRow: Int, val firstColumn: Int, val lastRow: Int, val lastColumn: Int)
data class WorksheetData(
    val number: Int, val name: String, val relationshipId: String, val visibility: SheetVisibility,
    val kind: SheetKind, val table: ImportTable?, val merges: List<MergeRegion> = emptyList(),
    val issues: List<ParseIssue> = emptyList(),
)
data class WorkbookData(val sourceId: String, val date1904: Boolean, val sheets: List<WorksheetData>)
data class WorkbookReadResult(val workbook: WorkbookData?, val issues: List<ParseIssue> = emptyList())
data class ImportRow(val number: Int, val cells: List<ImportCell>)
data class ImportTable(val sourceId: String, val rows: List<ImportRow>)
data class TableReadResult(val table: ImportTable?, val issues: List<ParseIssue> = emptyList())

internal class ImportRejected(val code: String) : RuntimeException()
internal fun reject(code: String = "INVALID_STRUCTURE"): Nothing = throw ImportRejected(code)
internal fun publicIssue(code: String) = ParseIssue(code, when (code) {
    "LIMIT_EXCEEDED" -> "Import resource limit exceeded"
    "INVALID_UTF8" -> "Input is not valid UTF-8"
    "IMPORT_UNREADABLE" -> "Input could not be read"
    "EMPTY_TABLE" -> "The selected table is empty"
    "UNSUPPORTED_SCHEMA_VERSION" -> "Unsupported timetable schema version"
    "UNSUPPORTED_FORMAT" -> "Unsupported import format"
    "FORMULA_UNSUPPORTED" -> "Formula requires review; cached value is unverified"
    "EXTERNAL_REFERENCE_REJECTED" -> "External reference rejected"
    "UNSUPPORTED_NUMBER_FORMAT" -> "Number format requires review"
    "INVALID_EXCEL_DATE" -> "Spreadsheet date or time requires correction"
    "XLSX_PASSIVE_METADATA_IGNORED" -> "Passive spreadsheet metadata was skipped; review imported cells"
    "XLSX_TIME_SERIAL_NORMALIZED" -> "Spreadsheet binary64 time representation was normalized; review time"
    "INVALID_CELL_VALUE" -> "Spreadsheet cell value requires correction"
    else -> "Invalid import structure"
})
