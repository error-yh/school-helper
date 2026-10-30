/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing

data class WeekExpressionResult(val weeks: Set<Int> = emptySet(), val errors: List<String> = emptyList())
/** A parity suffix applies only to the preceding comma-separated term. Invalid input returns no partial set. */
object WeekExpressionParser {
    fun parse(input: String, maxWeek: Int = 53): WeekExpressionResult {
        fun invalid() = WeekExpressionResult(errors=listOf("Invalid or out-of-bounds week expression"))
        if (maxWeek !in 1..1000 || input.length !in 1..2048) return invalid()
        val normalized = input.replace(Regex("\\s+"), "").replace('，', ',').replace('、', ',')
            .replace('～', '-').replace('~', '-').replace('—', '-').replace('－', '-')
            .replace('（', '(').replace('）', ')').replace('至', '-')
        val term = Regex("^第?(\\d{1,4})(?:-(\\d{1,4}))?周?(?:\\(([单双])(?:周)?\\))?$")
        val weeks = sortedSetOf<Int>()
        for (part in normalized.split(',')) {
            val match = term.matchEntire(part) ?: return invalid()
            val first = match.groupValues[1].toIntOrNull() ?: return invalid()
            val last = match.groupValues[2].toIntOrNull() ?: first
            if (first !in 1..maxWeek || last !in first..maxWeek) return invalid()
            val parity = match.groupValues[3]
            val expanded = (first..last).filter { parity.isEmpty() || it % 2 == if(parity == "单") 1 else 0 }
            if (expanded.isEmpty()) return invalid()
            weeks += expanded
        }
        return WeekExpressionResult(weeks)
    }
}
