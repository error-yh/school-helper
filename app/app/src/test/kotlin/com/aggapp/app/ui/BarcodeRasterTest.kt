package com.aggapp.app.ui

import org.junit.Assert.*
import org.junit.Test

class BarcodeRasterTest {
    @Test fun rendersOpaqueBlackWhiteBarsWithWhiteQuietZone() {
        val pixels = BarcodeRaster.render("TEST123456", 1000, 100)!!
        assertEquals(100000, pixels.size)
        assertTrue(pixels.all { it == -1 || it == -16777216 })
        assertTrue(pixels.take(10).all { it == -1 })
        assertTrue(pixels.any { it == -16777216 })
    }
    @Test fun refusesOversizedOrTooNarrowBitmapsRatherThanClippingBars() {
        assertNull(BarcodeRaster.render("TEST123456789", 10, 100))
        assertNull(BarcodeRaster.render("TEST123", 100000, 100000))
    }
}
