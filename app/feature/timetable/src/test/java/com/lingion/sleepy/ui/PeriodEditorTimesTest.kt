package com.lingion.sleepy.ui
import com.lingion.sleepy.domain.model.Period
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test
class PeriodEditorTimesTest {
    @Test fun sharedDurationChangesEndsWithoutMovingStartsOrBreaks() {
        val original=listOf(Period(1,LocalTime.of(8,0),LocalTime.of(8,20)),Period(2,LocalTime.of(8,55),LocalTime.of(9,15)))
        val actual=PeriodEditorTimes.withDuration(original,45)
        assertEquals(original.map {it.start},actual.map {it.start})
        assertEquals(listOf(LocalTime.of(8,45),LocalTime.of(9,40)),actual.map {it.end})
        assertEquals(listOf(1,2),actual.map {it.number})
        assertEquals(LocalTime.of(8,20),original.first().end)
    }
    @Test fun durationCannotWrapIntoTheNextDay() {
        try {PeriodEditorTimes.withDuration(listOf(Period(9,LocalTime.of(23,40),LocalTime.of(23,50))),45);fail("Cross-day period must be rejected")}
        catch(_:IllegalArgumentException) {}
    }
    @Test fun zeroOrNegativeDurationCannotBeSaved() {
        for(minutes in listOf(0,-1)) {
            try {PeriodEditorTimes.withDuration(listOf(Period(1,LocalTime.of(8,0),LocalTime.of(8,20))),minutes);fail("Invalid duration must be rejected")}
            catch(_:IllegalArgumentException) {}
        }
    }
}
