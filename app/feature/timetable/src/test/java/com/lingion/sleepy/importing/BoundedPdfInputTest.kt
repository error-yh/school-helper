/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test
class BoundedPdfInputTest {
    @Test fun `limit accepts exact size and rejects excess before PDF loading`() {
        assertArrayEquals(byteArrayOf(1,2,3),BoundedPdfInput.read(ByteArrayInputStream(byteArrayOf(1,2,3)),3))
        try { BoundedPdfInput.read(ByteArrayInputStream(ByteArray(4)),3); fail("Expected byte bound rejection") } catch(_: PdfInputLimitException) { }
    }
}
