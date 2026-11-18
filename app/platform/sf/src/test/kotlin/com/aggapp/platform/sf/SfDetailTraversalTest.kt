package com.aggapp.platform.sf

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Test

class SfDetailTraversalTest {
    @Test fun recognizedAlternateTrackFieldsArePreservedForDisplay() {
        val data = Json.parseToJsonElement("""{"routes":[{"scanTime":"2026-11-17 10:00","desc":"已揽收"}]}""")
        val tracks = SfAdapter().parseDetailTracks(data)
        assertEquals("2026-11-17 10:00", tracks.single().opTime)
        assertEquals("已揽收", tracks.single().description)
    }
    @Test fun skipsEmptyMetadataAndFindsLaterNestedRouteArray() {
        val response = Json.parseToJsonElement("""{"metadata":{},"payload":{"routes":[{"acceptTime":"2026-11-17 10:00","remark":"合成轨迹"}]}}""")
        val method = SfAdapter::class.java.getDeclaredMethod("findTracks", JsonElement::class.java).apply { isAccessible = true }
        val result = method.invoke(SfAdapter(), response) as List<*>
        assertEquals(1, result.size)
    }
}
