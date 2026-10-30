/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/** Deliberately limited format grammar, never a spreadsheet display/calculation engine. */
object ExcelCellFormat {
    fun classify(id: Int, code: String?): NumberFormatKind {
        if (code == null) return when (id) {
            0 -> NumberFormatKind.GENERAL
            in 1..13, in 37..44, 48 -> NumberFormatKind.NUMBER
            49 -> NumberFormatKind.TEXT
            in 14..17 -> NumberFormatKind.DATE
            in 18..21, 45 -> if (id in 18..19) NumberFormatKind.UNSUPPORTED else NumberFormatKind.TIME
            22 -> NumberFormatKind.DATE_TIME
            else -> NumberFormatKind.UNSUPPORTED
        }
        if (code.length > 16_384) return NumberFormatKind.UNSUPPORTED
        // Excel's explicit escaped ISO date spelling; no generic escape removal rule.
        if (code == "yyyy\\-mm\\-dd") return NumberFormatKind.DATE
        // Quotes/escapes are literals. Brackets and sections are recognized as unsupported,
        // so conditions, colors, locale, elapsed and AM/PM can never change interpretation.
        val tokens = StringBuilder()
        var i = 0
        while (i < code.length) {
            when (val c = code[i++]) {
                '"' -> { while (i < code.length && code[i] != '"') i++; if (i == code.length) return NumberFormatKind.UNSUPPORTED; i++ }
                '\\' -> { if (i == code.length) return NumberFormatKind.UNSUPPORTED; i++ }
                '[', ']', ';', '*', '_' -> return NumberFormatKind.UNSUPPORTED
                else -> tokens.append(c.lowercaseChar())
            }
        }
        val format = tokens.toString().trim()
        if (format == "general") return NumberFormatKind.GENERAL
        if (format == "@") return NumberFormatKind.TEXT
        if (format.matches(Regex("[0#?,. %+-]+"))) return NumberFormatKind.NUMBER
        val dates = setOf("yyyy/mm/dd", "yyyy-mm-dd", "yyyymd", "yyyy年m月d日", "m/d", "mm/dd", "mm-dd-yy", "d-mmm-yy", "d-mmm", "mmm-yy")
        val times = setOf("h:mm", "hh:mm", "h:mm:ss", "hh:mm:ss")
        if (format in dates) return NumberFormatKind.DATE
        if (format in times) return NumberFormatKind.TIME
        if (dates.any { date -> times.any { time -> format == "$date $time" || format == "${date}t$time" } }) return NumberFormatKind.DATE_TIME
        return NumberFormatKind.UNSUPPORTED
    }

    /** Exact minutes, or explicitly reviewed producer binary64 equivalence. No timezone. */
    internal fun temporal(number: BigDecimal, kind: NumberFormatKind, date1904: Boolean, onNormalized:()->Unit = {}): ExcelTemporalValue {
        if (kind !in setOf(NumberFormatKind.DATE, NumberFormatKind.TIME, NumberFormatKind.DATE_TIME)) reject("UNSUPPORTED_NUMBER_FORMAT")
        if (number.signum() < 0 || number > BigDecimal("3652059")) reject("INVALID_EXCEL_DATE")
        // The preceding bound makes truncation exact for the integral day, on minSdk 26 too.
        val day = number.toLong()
        val minutes = number.remainder(BigDecimal.ONE).multiply(BigDecimal(1440))
        var normalized=false
        val minute = try { minutes.intValueExact() } catch (_: ArithmeticException) {
            val nearest=minutes.setScale(0,RoundingMode.HALF_UP).intValueExact()
            // Midnight is already exactly representable; no underflow or day carry fallback.
            if(nearest !in 1..1439) reject("INVALID_EXCEL_DATE")
            val original=number.toDouble()
            val canonical=day.toDouble()+nearest.toDouble()/1440.0
            if(!original.isFinite() || original.toBits()!=canonical.toBits()) reject("INVALID_EXCEL_DATE")
            normalized=true; nearest
        }
        if (kind == NumberFormatKind.TIME && (day != 0L || minute !in 0..1439)) reject("INVALID_EXCEL_DATE")
        val date = if (kind == NumberFormatKind.TIME) null else {
            if (!date1904 && (day == 60L || day == 0L)) reject("INVALID_EXCEL_DATE")
            try {
                val value = if (date1904) LocalDate.of(1904,1,1).plusDays(day)
                else LocalDate.of(1899,12,31).plusDays(if (day >= 61) day - 1 else day)
                if (value.year > 9999) reject("INVALID_EXCEL_DATE")
                value
            } catch (_: DateTimeException) { reject("INVALID_EXCEL_DATE") }
        }
        val time = if (kind != NumberFormatKind.DATE || minute != 0) LocalTime.of(minute / 60, minute % 60) else null
        if(normalized) onNormalized()
        return ExcelTemporalValue(date, time)
    }
}
