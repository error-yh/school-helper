/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.notice

import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.importing.NoticeDateBasis
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class NoticeEventCodecTest {
    private val view = NoticeOccurrenceView(
        LocalDate.of(2026, 11, 13), setOf(5, 6),
        listOf(TimeRange(LocalTime.of(14, 0), LocalTime.of(14, 45)), TimeRange(LocalTime.of(14, 55), LocalTime.of(15, 40))),
        "张老师", "A101", null, null,
    )

    @Test fun `occurrence view round-trips through the bounded codec`() {
        assertEquals(view, NoticeEventCodec.decodeView(NoticeEventCodec.encodeView(view)))
    }

    @Test fun `payload round-trips including a replaced row snapshot`() {
        val previous = NoticePreviousException(
            "RESCHEDULE", LocalDate.of(2026, 11, 13).toEpochDay(), LocalDate.of(2026, 11, 14).toEpochDay(),
            null, null, true, listOf(5, 6), "张老师", "A101", null, null,
            listOf(NoticeProvenanceSnapshot("notice", "text", 1, 1f, 2f, 3f, 4f, "synthetic", "notice:1")),
        )
        val payload = NoticeEventPayload(mode = NoticeWriteMode.MANUAL, exceptionIds = listOf(3L), exceptionFingerprints = listOf("a".repeat(64)), previous = previous)
        assertEquals(payload, NoticeEventCodec.decodePayload(NoticeEventCodec.encodePayload(payload)))
    }

    @Test fun `date basis round-trips`() {
        val basis = NoticeDateBasis(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 9, 7), 16, null)
        assertEquals(basis, NoticeEventCodec.decodeBasis(NoticeEventCodec.encodeBasis(basis)))
    }

    @Test fun `unknown keys, bad versions and mismatched fingerprints fail closed`() {
        val encoded = NoticeEventCodec.encodePayload(NoticeEventPayload(mode = NoticeWriteMode.MANUAL, exceptionIds = emptyList(), exceptionFingerprints = emptyList(), previous = null))
        val withUnknown = encoded.dropLast(1) + ",\"extra\":1}"
        assertThrows(IllegalArgumentException::class.java) { NoticeEventCodec.decodePayload(withUnknown) }
        assertThrows(IllegalArgumentException::class.java) {
            NoticeEventCodec.decodePayload(encoded.replace("\"version\":1", "\"version\":2"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NoticeEventCodec.decodePayload(NoticeEventCodec.encodePayload(
                NoticeEventPayload(mode = NoticeWriteMode.MANUAL, exceptionIds = listOf(1L), exceptionFingerprints = listOf("not-a-hash"), previous = null)
            ))
        }
        assertThrows(IllegalArgumentException::class.java) {
            val nine = (1L..9L).toList()
            NoticeEventCodec.encodePayload(NoticeEventPayload(mode = NoticeWriteMode.MANUAL, exceptionIds = nine, exceptionFingerprints = nine.map { "a".repeat(64) }, previous = null))
        }
    }

    @Test fun `oversized and malformed views fail closed`() {
        assertThrows(IllegalArgumentException::class.java) { NoticeEventCodec.decodeView("{\"date\":1}") }
        assertThrows(IllegalArgumentException::class.java) { NoticeEventCodec.decodeView("x".repeat(NoticeEventCodec.MAX_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) {
            val day = view.date.toEpochDay()
            NoticeEventCodec.decodeView(NoticeEventCodec.encodeView(view).replace("\"date\":$day", "\"date\":\"bad\""))
        }
    }
}