package com.lingion.sleepy.ui

import org.junit.Assert.*
import org.junit.Test

class ImportSourceLoaderTest {
    @Test fun deviceDateFixtureCanCreateAReviewWithoutAnyCalendar() {
        val json="""{"schemaVersion":1,"courses":[{"key":"synthetic","name":"Synthetic device fixture","meetings":[{"key":"one","kind":"DATE_SPECIFIC","dates":["2026-10-28"],"time":{"start":"09:20","end":"10:05"},"teacher":"Synthetic teacher","room":"Synthetic room","group":"","experimentProject":""}]}]}"""
        val parsed=ImportSourceLoader.json(json,20)
        assertNotNull(parsed.issues.toString(),parsed.draft)
        val source=com.lingion.sleepy.domain.imports.FrozenImportSource.fromFormat(parsed)
        val session=com.lingion.sleepy.domain.imports.ImportReviewSession.create(source,
            com.lingion.sleepy.domain.imports.ImportReviewContext(com.lingion.sleepy.domain.imports.ImportTarget("synthetic",1,0,4),targetPeriods=com.lingion.sleepy.domain.model.defaultPeriods()))
        assertEquals("Synthetic device fixture",session.source.format.draft!!.courses.single().name)
    }
    @Test fun jsonRetainsSourceAndRejectsInvalidDocuments() {
        val valid = """{"schemaVersion":1,"courses":[{"key":"c","name":"Synthetic","meetings":[{"key":"m","kind":"RECURRING","dayOfWeek":1,"weeks":[1],"sections":[1]}]}]}"""
        val parsed = ImportSourceLoader.json(valid, 20)
        assertEquals("Synthetic", parsed.draft!!.courses.single().name)
        assertTrue(parsed.sources.isNotEmpty())
        assertNull(ImportSourceLoader.json("{broken", 20).draft)
    }
}
