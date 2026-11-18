package com.aggapp.app.ui

import com.aggapp.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ExpressListRulesTest {
    private fun item(p: Platform, status: ExpressStatus, no: String? = "TEST123") =
        ExpressItem("id-${p.name}", p, "测试驿站", trackingNo = no, status = status)

    @Test fun partitionsWaitingTransitAndTerminalPackages() {
        val split = ExpressListRules.partition(listOf(item(Platform.PDD, ExpressStatus.IN_TRANSIT),
            item(Platform.SF, ExpressStatus.WAIT_PICKUP, "TEST456"),
            item(Platform.TAOBAO, ExpressStatus.PICKED, "TEST789")))
        assertEquals(listOf("TEST456"), split.waiting.map { it.trackingNo })
        assertEquals(listOf("TEST123"), split.transit.map { it.trackingNo })
        assertEquals(listOf("TEST789"), split.picked.map { it.trackingNo })
    }

    @Test fun mergingBorrowedCodeKeepsItsProvenanceAndImage() {
        val a = item(Platform.TAOBAO, ExpressStatus.WAIT_PICKUP).copy(pickupCode = "A-12")
        val b = item(Platform.PDD, ExpressStatus.WAIT_PICKUP).copy(imageUrl = "https://example.com/test.png")
        val merged = ExpressListRules.merge(a, b)
        assertEquals("A-12", merged.pickupCode)
        assertEquals(a.platform, merged.platform)
        assertEquals(b.imageUrl, merged.imageUrl)
    }

    @Test fun blankWaybillsDoNotDisappearOrMergeTogether() {
        val rows = ExpressListRules.deduplicate(listOf(item(Platform.PDD, ExpressStatus.UNKNOWN, null),
            item(Platform.SF, ExpressStatus.UNKNOWN, "")))
        assertEquals(2, rows.size)
    }

    @Test fun pickedStatusTakesPrecedenceOverAnOldPickupCode() {
        val merged = ExpressListRules.merge(item(Platform.PDD, ExpressStatus.WAIT_PICKUP).copy(pickupCode = "OLD"),
            item(Platform.TAOBAO, ExpressStatus.PICKED))
        assertEquals(ExpressStatus.PICKED, merged.status)
    }
}
