/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing

import org.junit.Assert.*
import org.junit.Test

class WeekExpressionParserTest {
    @Test fun `sparse intervals stay sparse`() {
        assertEquals(setOf(1,2,3,7,8,10,11), WeekExpressionParser.parse("1-3周,7-8周,10-11周").weeks)
    }
    @Test fun `parity is scoped to its immediately preceding interval`() {
        assertEquals(setOf(1,2,3,4,5,7,9,11,13,17), WeekExpressionParser.parse("1~5,7~13(单),17周").weeks)
        assertEquals(setOf(2,6,8,10,12,14,16), WeekExpressionParser.parse("2周,6-16周(双)").weeks)
    }
    @Test fun `accept all required expressions`() {
        mapOf("5-7周(单)" to setOf(5,7), "6-16周(双)" to (6..16 step 2).toSet(),
            "1~3,5~13,16~17周" to ((1..3)+(5..13)+(16..17)).toSet(),
            "第5周" to setOf(5), "2-16周(双)" to (2..16 step 2).toSet()).forEach { (input, expected) ->
            assertEquals(input, expected, WeekExpressionParser.parse(input).weeks)
        }
    }
    @Test fun `invalid expressions never silently partially succeed`() {
        listOf("", "1-3周,垃圾", "0-5周", "5-3周", "1-999999999周", "1-3周,", "1-3周(单双)", "1;2周").forEach {
            val result = WeekExpressionParser.parse(it)
            assertTrue(it, result.errors.isNotEmpty())
            assertTrue(it, result.weeks.isEmpty())
        }
    }
    @Test fun `supports explicit configured wide academic bound and Chinese range`() {
        assertEquals(setOf(998,999,1000), WeekExpressionParser.parse("998至1000周", maxWeek=1000).weeks)
    }
    @Test fun `enforces configured academic bound and input length`() {
        assertTrue(WeekExpressionParser.parse("1-54周", maxWeek = 53).errors.isNotEmpty())
        assertTrue(WeekExpressionParser.parse("1,".repeat(3000)).errors.isNotEmpty())
        assertTrue(WeekExpressionParser.parse("2周(单)").errors.isNotEmpty())
    }
}

