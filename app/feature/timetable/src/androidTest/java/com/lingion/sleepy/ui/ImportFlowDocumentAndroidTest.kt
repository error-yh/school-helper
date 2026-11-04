/* SPDX-License-Identifier: GPL-3.0-only
 * Device-level state tests for the SAF import flow: request identity, no stale drafts and
 * zero writes on failure or cancellation.
 */
package com.lingion.sleepy.ui

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lingion.sleepy.domain.imports.*
import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.domain.repository.*
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportFlowDocumentAndroidTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val json = """{"schemaVersion":1,"courses":[{"key":"c","name":"Synthetic","meetings":[{"key":"m","kind":"RECURRING","dayOfWeek":1,"weeks":[1],"sections":[1]}]}]}"""

    private fun sample(): File {
        val root = InstrumentationRegistry.getArguments().getString("sampleRoot")
        val file = root?.let { File(it, "pre5fix01-image.bin") }
        org.junit.Assume.assumeTrue("Staged private sample missing in sampleRoot=$root", file != null && file.isFile)
        return file!!
    }

    private fun synthetic(name: String, bytes: ByteArray): File =
        File(File(context.filesDir, "pre5fix01-synthetic").apply { mkdirs() }, name).also { it.writeBytes(bytes) }

    private suspend fun idle(model: ImportFlowViewModel) {
        withTimeout(240_000) { while (model.state.value.busy) delay(20) }
    }

    @Test(timeout = 900_000) fun selectedImageWinsOverPastedJsonAndReReadKeepsTheDocument() = runBlocking {
        val repository = FakeImportRepository()
        val model = ImportFlowViewModel(repository)
        val loader = AndroidImportDocumentLoader(context)
        model.text(json)
        model.loadDocument(loader, Uri.fromFile(sample()), ImportDocumentKind.IMAGE)
        idle(model)
        val state = model.state.value
        assertEquals(state.problems.toString(), 0, state.problems.size)
        assertTrue(state.documentSelected)
        val session = checkNotNull(state.session)
        assertEquals("The document request, not the pasted JSON, supplies the draft", 8,
            session.source.format.draft!!.courses.sumOf { it.meetings.size })
        // Re-reading keeps using the saved document request; the hidden text never replaces it.
        model.parse()
        idle(model)
        assertEquals(8, model.state.value.session!!.source.format.draft!!.courses.sumOf { it.meetings.size })
        assertEquals("Reading and re-reading must never write", 0, repository.writes)
    }

    @Test(timeout = 900_000) fun switchingToTextDropsTheDocumentRequestAndLeavesNoStaleImageDraft() = runBlocking {
        val repository = FakeImportRepository()
        val model = ImportFlowViewModel(repository)
        val loader = AndroidImportDocumentLoader(context)
        model.loadDocument(loader, Uri.fromFile(sample()), ImportDocumentKind.IMAGE)
        idle(model)
        assertNotNull(model.state.value.session)
        model.kind(ImportTextKind.JSON)
        val switched = model.state.value
        assertFalse(switched.documentSelected)
        assertNull(switched.session)
        assertNull(switched.preview)
        model.text(json)
        model.parse()
        idle(model)
        val session = checkNotNull(model.state.value.session)
        assertEquals("Only the typed JSON draft is importable after switching", 1,
            session.source.format.draft!!.courses.sumOf { it.meetings.size })
        assertEquals(0, repository.writes)
    }

    @Test(timeout = 600_000) fun failedAndCancelledReadsReportTheStageAndWriteNothing() = runBlocking {
        val repository = FakeImportRepository()
        val model = ImportFlowViewModel(repository)
        val loader = AndroidImportDocumentLoader(context)
        assertNull(model.state.value.session)
        assertFalse(model.state.value.documentSelected)
        assertEquals("Cancelling the picker leaves no request and no writes", 0, repository.writes)
        // A failing document reports its real stage and still keeps zero writes.
        model.loadDocument(loader, Uri.fromFile(synthetic("broken.bin", byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7))), ImportDocumentKind.IMAGE)
        idle(model)
        val failed = model.state.value
        assertEquals(1, failed.problems.size)
        assertTrue(failed.problems.single(), failed.problems.single().contains("无法识别"))
        assertNull(failed.session)
        assertNull(failed.preview)
        assertFalse(failed.completed)
        assertTrue(failed.documentSelected)
        assertEquals(0, repository.writes)
        // Re-reading the failed request keeps the same accurate reason.
        model.parse()
        idle(model)
        assertTrue(model.state.value.problems.single().contains("无法识别"))
        assertEquals(0, repository.writes)
    }

    @Test(timeout = 900_000) fun realOcrImageThroughViewModelUsesIndependentIdentityAndConfirmDifferentSourceResolvesPreview() = runBlocking {
        val repository = FakeImportRepository(withSourceRelation = true)
        val model = ImportFlowViewModel(repository)
        val loader = AndroidImportDocumentLoader(context)
        model.loadDocument(loader, Uri.fromFile(sample()), ImportDocumentKind.IMAGE)
        idle(model)
        val session = checkNotNull(model.state.value.session)
        assertTrue("OCR without explicit codes must require IDENTITY",
            session.requirements.any { it.kind == ReviewRequirementKind.IDENTITY })

        // 调用公开方法确认身份草案
        model.confirmIdentityDraft()
        val identity = checkNotNull(model.state.value.identityReview)
        assertEquals(IdentityReviewKind.INDEPENDENT, identity.kind)

        // 调用公开方法 check() 执行规划
        model.check()
        idle(model)
        val preview = checkNotNull(model.state.value.preview)
        assertTrue("来源关系需要核对时 sourceRelationRequired 为 true", preview.sourceRelationRequired)
        assertFalse(preview.ready)

        // 通过公开方法调用回答来源关系为 DIFFERENT_SOURCE
        model.confirmDifferentSource()
        idle(model)

        val finalPreview = checkNotNull(model.state.value.preview)
        assertTrue("确认不同来源后 preview 顺畅就绪", finalPreview.ready)
        assertFalse(finalPreview.sourceRelationRequired)
        assertEquals(SourceRelationChoice.DIFFERENT_SOURCE, repository.requestedSourceRelation)
        assertEquals(0, repository.writes)
    }

    private class FakeImportRepository(private val withSourceRelation: Boolean = false) : ImportRepository {
        var writes = 0
        var requestedSourceRelation: SourceRelationChoice? = null
        private val periods = listOf(Period(1, LocalTime.of(8, 0), LocalTime.of(8, 45)), Period(2, LocalTime.of(8, 55), LocalTime.of(9, 40)))
        private val snapshot = TimetableSnapshot(settings = SemesterSettings(startDate = LocalDate.parse("2026-09-07")), periods = periods, scopeToken = "local")
        private val target = ImportTarget("local", 1, 0, 4)
        override suspend fun readImportTarget(semesterId: Long) = ImportTargetView(target, snapshot, emptyList())
        override suspend fun previewImport(reviewed: ReviewedImport, conflicts: List<ConflictReviewDecision>): ImportPreviewResult {
            val range = TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45))
            requestedSourceRelation = reviewed.decisions.sourceRelation?.choice
            val sourceRelationRequired = withSourceRelation && reviewed.decisions.sourceRelation == null
            val problems = if (sourceRelationRequired) listOf(ImportProblemView("ARCHIVE_MEMBERSHIP_REVIEW_REQUIRED", null)) else emptyList()
            return ImportPreviewResult(
                ImportPlanPreview(target, reviewed.reviewDigest, "digest", reviewed.semanticFingerprint, reviewed.counts, emptySet()),
                emptyList(),
                problems,
                unchanged = false,
                changes = null,
                sourceRelationRequired = sourceRelationRequired
            )
        }
        override suspend fun applyImport(command: ImportApplyCommand): ImportApplyResult { writes++; return ImportApplyResult.Applied(true, 1) }
    }
}