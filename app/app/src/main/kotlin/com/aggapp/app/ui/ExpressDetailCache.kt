package com.aggapp.app.ui

import com.aggapp.core.model.*

/** Memory only. Exact credential equality prevents reuse across account/token changes. */
class ExpressDetailCache(private val capacity: Int = 32) {
    private data class Key(val credential: Credential, val platform: Platform, val id: String, val waybill: String?, val status: ExpressStatus)
    private data class Entry(val time: Long, val detail: ExpressDetail)
    private val entries = LinkedHashMap<Key, Entry>(16, 0.75f, true)
    init { require(capacity > 0) }
    private fun key(c: Credential, i: ExpressItem) = Key(c, i.platform, i.id, i.trackingNo, i.status)
    fun get(c: Credential, i: ExpressItem, now: Long): ExpressDetail? {
        val key = key(c, i)
        val entry = entries[key] ?: return null
        val ttl = if (i.status == ExpressStatus.PICKED || i.status == ExpressStatus.TIMEOUT) 600_000L else 60_000L
        if (now < entry.time || now - entry.time >= ttl) { entries.remove(key); return null }
        return entry.detail
    }
    fun put(c: Credential, i: ExpressItem, d: ExpressDetail, now: Long) {
        if (c.platform != i.platform || (i.trackingNo != null && i.trackingNo != d.trackingNo)) return
        entries[key(c, i)] = Entry(now, d)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
    fun clear(platforms: Set<Platform>) { entries.keys.removeAll { it.platform in platforms } }
}
