/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ImportResidualBuilderTest {
    private fun r(a:String,b:String) = TimeRange(LocalTime.parse(a),LocalTime.parse(b))
    @Test fun `continuous subtraction retains the break as real time`() {
        assertEquals(listOf(r("08:45","09:40")),ImportResidualBuilder.subtract(r("08:00","09:40"),listOf(r("08:00","08:45"))))
    }
    @Test fun `half open endpoints and sparse intervals never bridge`() {
        assertEquals(listOf(r("08:00","08:45")),ImportResidualBuilder.subtract(r("08:00","08:45"),listOf(r("08:45","08:55"))))
        assertEquals(listOf(r("08:00","08:30"),r("09:00","10:00")),ImportResidualBuilder.subtract(r("08:00","10:00"),listOf(r("08:30","09:00"))))
    }
    @Test fun `bounded random interval subtraction equals minute set difference`() {
        val random = java.util.Random(7123)
        repeat(200) {
            val start = random.nextInt(1000); val end = start + random.nextInt(1439-start)+1
            val cuts = List(5) { val a=random.nextInt(1439); val b=a+random.nextInt(1439-a)+1; TimeRange(LocalTime.ofSecondOfDay(a*60L),LocalTime.ofSecondOfDay(b*60L)) }
            val actual = ImportResidualBuilder.subtract(TimeRange(LocalTime.ofSecondOfDay(start*60L),LocalTime.ofSecondOfDay(end*60L)),cuts)
            fun minutes(t:TimeRange) = (t.start.toSecondOfDay()/60 until t.end.toSecondOfDay()/60).toSet()
            assertEquals((start until end).toSet()-cuts.flatMap { minutes(it) }.toSet(),actual.flatMap { minutes(it) }.toSet())
        }
    }
}
