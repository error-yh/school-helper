package com.aggapp.app.ui

import com.aggapp.core.model.*
import org.junit.Assert.*
import org.junit.Test

class ExpressDetailCacheTest {
    private val a = Credential(Platform.PDD, "synthetic-account-a", capturedAt = 1L)
    private val item = ExpressItem("same-id", Platform.PDD, "测试驿站", trackingNo = "TEST123")
    private val detail = ExpressDetail("TEST123", goodsName = "合成包裹")

    @Test fun cacheNeverCrossesAccountOrPlatformAndExpires() {
        val cache = ExpressDetailCache()
        cache.put(a, item, detail, 1000L)
        assertEquals(detail, cache.get(a, item, 1001L))
        assertNull(cache.get(a.copy(token = "synthetic-account-b"), item, 1001L))
        assertNull(cache.get(a, item.copy(platform = Platform.SF), 1001L))
        assertNull(cache.get(a, item, 1000L + 60001L))
        assertNull(cache.get(a, item, 999L))
    }

    @Test fun cacheIsBoundedAndExplicitInvalidationRemovesSensitiveDetails() {
        val cache = ExpressDetailCache(capacity = 2)
        cache.put(a, item, detail, 1000L)
        cache.put(a, item.copy(id = "second"), detail, 1001L)
        cache.put(a, item.copy(id = "third"), detail, 1002L)
        assertNull(cache.get(a, item, 1003L))
        cache.clear(setOf(Platform.PDD))
        assertNull(cache.get(a, item.copy(id = "third"), 1003L))
    }
}
