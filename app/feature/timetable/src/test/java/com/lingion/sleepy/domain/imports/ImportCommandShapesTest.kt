/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.importing.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class ImportCommandShapesTest {
    private fun session():ImportReviewSession = ImportReviewSession.create(FrozenImportSource.fromPdf(RichTimetableDraft(courses = listOf(RichCourseDraft("x",meetings = listOf(RichMeetingDraft(MeetingKind.RECURRING,1,setOf(1),customTime = DraftTimeRange(LocalTime.parse("08:00"),LocalTime.parse("08:45")))))))),ImportReviewContext(ImportTarget("local",1,0,3)))
    @Test fun `preview copies conflict fingerprints before exposing snapshot`() {
        val s = session()
        val v = (RichDraftConverter.convert(s) as ImportReviewResult.Ready).reviewed
        val fingerprints = linkedSetOf("a")
        val preview = ImportPlanPreview(s.context.target,v.reviewDigest,"later-c2-plan",v.semanticFingerprint,v.counts,fingerprints)
        fingerprints.clear()
        assertEquals(setOf("a"),preview.conflictFingerprints)
        assertThrows(UnsupportedOperationException::class.java) { (preview.conflictFingerprints as MutableSet<String>).clear() }
    }
    @Test fun `apply shape copies decisions rather than retaining caller mutable list`() {
        val s = session()
        val v = (RichDraftConverter.convert(s) as ImportReviewResult.Ready).reviewed
        val decisions = mutableListOf(s.reviewConflict("a",ConflictReviewChoice.KEEP))
        val command = ImportApplyCommand(s.context.target,ImportMode.APPEND,v,"later-c2-plan",decisions)
        decisions.clear()
        assertEquals(1,command.conflictDecisions.size)
        assertThrows(UnsupportedOperationException::class.java) { (command.conflictDecisions as MutableList<ConflictReviewDecision>).clear() }
    }
}
