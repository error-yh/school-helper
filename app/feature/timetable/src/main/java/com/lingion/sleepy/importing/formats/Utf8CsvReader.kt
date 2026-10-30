/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import java.io.InputStream

/** RFC-style comma records. Content whitespace and quoted CRLF are retained verbatim. */
class Utf8CsvReader {
    fun read(input: InputStream, context: FormatParseContext): TableReadResult = try {
        val text = BoundedImportInput.readUtf8(input, context)
        if (text.isEmpty()) reject("EMPTY_TABLE")
        val rows = mutableListOf<ImportRow>()
        var cells = mutableListOf<ImportCell>()
        val field = StringBuilder()
        var state = 0 // 0 field start, 1 literal, 2 quoted, 3 closed quote
        var total = 0
        var count = 0
        var width: Int? = null
        fun append(c: Char) {
            if (field.length >= context.limits.maxFieldUnits || total >= context.limits.maxTextUnits) reject("LIMIT_EXCEEDED")
            field.append(c); total++
        }
        fun endField() {
            if (++count > context.limits.maxCells || cells.size >= context.limits.maxColumns) reject("LIMIT_EXCEEDED")
            cells += ImportCell(cells.size + 1, field.toString(), "csv:record:${rows.size + 1}!column:${cells.size + 1}")
            field.setLength(0); state = 0
        }
        fun endRow() {
            endField()
            if (width == null) width = cells.size else if (width != cells.size) reject()
            if (rows.size >= context.limits.maxRows) reject("LIMIT_EXCEEDED")
            rows += ImportRow(rows.size + 1, cells.toList()); cells = mutableListOf()
        }
        var i = 0
        while (i < text.length) {
            context.cancellation.check()
            val c = text[i++]
            if (state == 2) {
                if (c == '"') state = 3 else append(c)
            } else when (c) {
                '"' -> when (state) { 0 -> state = 2; 3 -> { append('"'); state = 2 }; else -> reject() }
                ',' -> endField()
                '\n' -> endRow()
                '\r' -> { if (i >= text.length || text[i++] != '\n') reject(); endRow() }
                else -> { if (state == 3) reject(); append(c); state = 1 }
            }
        }
        if (state == 2) reject()
        if (field.isNotEmpty() || cells.isNotEmpty() || state != 0) endRow()
        TableReadResult(ImportTable(context.sourceId, rows.toList()))
    } catch (failure: ImportRejected) { TableReadResult(null, listOf(publicIssue(failure.code))) }
}
