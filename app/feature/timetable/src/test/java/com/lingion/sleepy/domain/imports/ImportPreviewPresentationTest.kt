package com.lingion.sleepy.domain.imports

import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.importing.RichCourseDraft
import com.lingion.sleepy.importing.RichMeetingDraft
import com.lingion.sleepy.importing.RichTimetableDraft
import com.lingion.sleepy.importing.formats.FormatParseResult
import com.lingion.sleepy.data.projectImportPreview
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ImportPreviewPresentationTest {
    private val monday=LocalDate.parse("2026-09-07")
    private val original=CourseMeeting(7,1,setOf(1,3),setOf(1,3),experimentProject="Original project")
    private val snapshot=TimetableSnapshot(SemesterSettings(startDate=monday),courses=listOf(
        ScheduledCourse(4,"Existing","Old teacher","Old room",0,listOf(original))),scopeToken="synthetic")
    private val loaded=LoadedImportTarget(snapshot,4)
    private fun review(mode:ImportMode=ImportMode.APPEND):Pair<ImportReviewSession,ReviewedImport> {
        val source=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses=listOf(RichCourseDraft("Incoming",meetings=listOf(
            RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,1,setOf(1),setOf(1),teacher="New teacher",room="New room",experimentProject="New project")))))))
        val session=ImportReviewSession.create(source,ImportReviewContext(ImportTarget("synthetic",1,0,4),mode,monday,20,snapshot.periods))
        return session to (RichDraftConverter.convert(session) as ImportReviewResult.Ready).reviewed
    }
    @Test fun logicalBaseDisplayUsesWholeOriginalAndDisjointTimes() {
        val shown=ImportOccurrencePlanner.logicalBaseDetails(loaded).single()
        assertEquals(ImportOccurrencePlanner.logicalBases(loaded).single(),shown.reference)
        assertEquals(setOf(1,3),shown.original.weeks);assertEquals(setOf(1,3),shown.original.sections)
        assertEquals(2,shown.timeRanges.size);assertEquals("Old teacher",shown.teacher)
        assertEquals("Original project",shown.project)
    }
    @Test fun actualConflictCarriesBothEffectiveIdentitiesAndSkipIsNotReportedAsDuplicate() {
        val (session,reviewed)=review()
        val plan=ImportOccurrencePlanner.plan(reviewed,loaded)
        val view=projectImportPreview(plan,emptyList())
        val conflict=view.conflicts.single()
        assertEquals("Incoming",conflict.incoming.courseName);assertEquals("Existing",conflict.other.courseName)
        assertEquals("New teacher",conflict.incoming.teacher);assertEquals("Old teacher",conflict.other.teacher)
        assertEquals("New project",conflict.incoming.project);assertEquals("Original project",conflict.other.project)
        assertEquals(1,view.changes!!.newCourses)
        val decision=session.reviewConflict(conflict.fingerprint,ConflictReviewChoice.SKIP)
        val skipped=projectImportPreview(ImportOccurrencePlanner.plan(reviewed,loaded,listOf(decision)),listOf(decision))
        assertTrue(skipped.ready);assertTrue(skipped.unchanged)
        assertEquals(1,skipped.changes!!.skippedSourceMeetings);assertEquals(0,skipped.changes!!.newCourses)
        assertNull(skipped.changes!!.duplicateSourceMeetings)
    }
    @Test fun replaceSummaryCountsOnlyCoursesActuallyRemovedAndWritten() {
        val (_,reviewed)=review(ImportMode.REPLACE)
        val view=projectImportPreview(ImportOccurrencePlanner.plan(reviewed,loaded),emptyList())
        assertTrue(view.ready)
        assertEquals(1,view.changes!!.removedCourses);assertEquals(1,view.changes!!.newCourses)
        assertEquals(1,view.changes!!.writtenMeetingFragments)
    }
}
