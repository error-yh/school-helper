/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.ui

import com.lingion.sleepy.importing.formats.*
import com.lingion.sleepy.importing.ParseIssue
import java.time.LocalDate
import java.util.UUID

/** The parsers enforce their own limits; source identifiers carry no filename or URI. */
internal object ImportSourceLoader {
    const val MAX_TEXT_CHARS = 500_000
    private fun tooLarge() = FormatParseResult(null, issues = listOf(ParseIssue("LIMIT_EXCEEDED", "来源内容过长")))
    fun json(text: String, maxWeek: Int): FormatParseResult =
        if (text.length > MAX_TEXT_CHARS) tooLarge() else VersionedJsonTimetableParser().parse(text.byteInputStream(), FormatParseContext(UUID.randomUUID().toString(), maxWeek))

    fun notice(text: String, maxWeek: Int, referenceDate: LocalDate, monday: LocalDate?): FormatParseResult =
        if (text.length > MAX_TEXT_CHARS) tooLarge() else NoticeTimetableParser().parse(text, NoticeParseContext(FormatParseContext(UUID.randomUUID().toString(), maxWeek), referenceDate, monday))
}
