package com.lingion.sleepy.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseTest {
    @Test
    fun everyWeekIncludesBothBoundariesOnly() {
        val course = course(startWeek = 3, endWeek = 6)

        assertFalse(course.inWeek(2))
        assertTrue(course.inWeek(3))
        assertTrue(course.inWeek(6))
        assertFalse(course.inWeek(7))
    }

    @Test
    fun oddWeeksUseAbsoluteWeekParityInsideRange() {
        val course = course(startWeek = 2, endWeek = 6, type = 1)

        assertFalse(course.inWeek(1))
        assertFalse(course.inWeek(2))
        assertTrue(course.inWeek(3))
        assertTrue(course.inWeek(5))
        assertFalse(course.inWeek(6))
        assertFalse(course.inWeek(7))
    }

    @Test
    fun evenWeeksUseAbsoluteWeekParityInsideRange() {
        val course = course(startWeek = 3, endWeek = 7, type = 2)

        assertFalse(course.inWeek(2))
        assertFalse(course.inWeek(3))
        assertTrue(course.inWeek(4))
        assertTrue(course.inWeek(6))
        assertFalse(course.inWeek(7))
        assertFalse(course.inWeek(8))
    }

    @Test
    fun specifiedSingleWeekDoesNotExpandBeyondItsRange() {
        val course = course(startWeek = 6, endWeek = 6, type = 3)

        assertFalse(course.inWeek(5))
        assertTrue(course.inWeek(6))
        assertFalse(course.inWeek(7))
    }

    @Test
    fun unknownLegacyTypeRetainsUpstreamRangeFallback() {
        val course = course(startWeek = 3, endWeek = 4, type = 42)

        assertFalse(course.inWeek(2))
        assertTrue(course.inWeek(3))
        assertTrue(course.inWeek(4))
        assertFalse(course.inWeek(5))
    }

    @Test
    fun dayMustBeBetweenMondayAndSunday() {
        assertThrows(IllegalArgumentException::class.java) { course(day = 0) }
        assertThrows(IllegalArgumentException::class.java) { course(day = 8) }
        course(day = 1)
        course(day = 7)
    }

    @Test
    fun startNodeMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { course(startNode = 0) }
        assertThrows(IllegalArgumentException::class.java) { course(startNode = -1) }
        course(startNode = 1)
    }

    @Test
    fun stepMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { course(step = 0) }
        assertThrows(IllegalArgumentException::class.java) { course(step = -1) }
        course(step = 1)
    }

    @Test
    fun weekRangeMustStartAtOneOrLaterAndBeOrdered() {
        assertThrows(IllegalArgumentException::class.java) { course(startWeek = 0) }
        assertThrows(IllegalArgumentException::class.java) { course(startWeek = -1) }
        assertThrows(IllegalArgumentException::class.java) {
            course(startWeek = 8, endWeek = 7)
        }
        course(startWeek = 1, endWeek = 1)
    }

    private fun course(
        day: Int = 1,
        startNode: Int = 1,
        step: Int = 2,
        startWeek: Int = 1,
        endWeek: Int = 16,
        type: Int = 0,
    ) = Course(
        courseName = "Synthetic course",
        day = day,
        startNode = startNode,
        step = step,
        startWeek = startWeek,
        endWeek = endWeek,
        type = type,
    )
}
