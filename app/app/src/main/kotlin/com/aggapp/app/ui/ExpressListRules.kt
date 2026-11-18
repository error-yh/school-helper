package com.aggapp.app.ui

import com.aggapp.core.model.ExpressItem
import com.aggapp.core.model.ExpressStatus

enum class ExpressTab(val label: String) { WAIT("待取件"), TRANSIT("运输中"), PICKED("已签收") }

data class ExpressPartition(val waiting: List<ExpressItem>, val transit: List<ExpressItem>, val picked: List<ExpressItem>)

/** Upstream v1.0.10 presentation, with terminal status precedence and stable missing-waybill identity. */
object ExpressListRules {
    fun partition(items: List<ExpressItem>): ExpressPartition = ExpressPartition(
        items.filter { it.status == ExpressStatus.WAIT_PICKUP },
        items.filter { it.status == ExpressStatus.IN_TRANSIT || it.status == ExpressStatus.UNKNOWN },
        items.filter { it.status == ExpressStatus.PICKED || it.status == ExpressStatus.TIMEOUT },
    )

    fun deduplicate(items: List<ExpressItem>): List<ExpressItem> {
        val rows = linkedMapOf<String, ExpressItem>()
        for (item in items) {
            val key = item.trackingNo?.takeIf { it.isNotBlank() }?.let { "waybill:$it" }
                ?: "item:${item.platform.name}:${item.id}"
            rows[key] = rows[key]?.let { merge(it, item) } ?: item
        }
        return rows.values.toList()
    }

    fun merge(a: ExpressItem, b: ExpressItem): ExpressItem {
        val main = if (!a.pickupCode.isNullOrBlank()) a else b
        val other = if (main === a) b else a
        val status = when {
            a.status == ExpressStatus.PICKED || b.status == ExpressStatus.PICKED -> ExpressStatus.PICKED
            a.status == ExpressStatus.TIMEOUT || b.status == ExpressStatus.TIMEOUT -> ExpressStatus.TIMEOUT
            a.status == ExpressStatus.WAIT_PICKUP || b.status == ExpressStatus.WAIT_PICKUP -> ExpressStatus.WAIT_PICKUP
            a.status == ExpressStatus.IN_TRANSIT || b.status == ExpressStatus.IN_TRANSIT -> ExpressStatus.IN_TRANSIT
            else -> ExpressStatus.UNKNOWN
        }
        return main.copy(station = main.station.ifBlank { other.station },
            stationAddress = main.stationAddress ?: other.stationAddress,
            summary = main.summary ?: other.summary, imageUrl = main.imageUrl ?: other.imageUrl,
            arrivedAt = listOfNotNull(main.arrivedAt, other.arrivedAt).minOrNull(), status = status)
    }
}
