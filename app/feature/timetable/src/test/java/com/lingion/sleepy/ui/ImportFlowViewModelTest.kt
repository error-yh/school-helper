package com.lingion.sleepy.ui

import com.lingion.sleepy.domain.imports.*
import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.domain.repository.*
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImportFlowViewModelTest {
    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val json="""{"schemaVersion":1,"courses":[{"key":"c","name":"Synthetic","meetings":[{"key":"m","kind":"RECURRING","dayOfWeek":1,"weeks":[1],"sections":[1]}]}]}"""
    @Test fun explicitConfirmationIsRequiredAndDoubleApplyWritesOnce() = runBlocking {
        val repository=FakeImportRepository();val model=ImportFlowViewModel(repository)
        model.text(json);model.parse()
        withTimeout(10_000) { while(model.state.value.busy) delay(10) }
        val session=checkNotNull(model.state.value.session)
        session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.forEach { model.evidence(it.fingerprint,true) }
        session.requirements.filter { it.kind==ReviewRequirementKind.RETAIN_UNKNOWN_OPTIONAL_METADATA || it.kind==ReviewRequirementKind.KEEP_UNSCHEDULED }.forEach { model.acceptRequirement(it,true) }
        model.check();withTimeout(10_000) { while(model.state.value.busy) delay(10) }
        assertTrue(model.state.value.problems.toString(),model.state.value.preview?.ready==true)
        model.apply();assertEquals(0,repository.writes)
        model.confirm();assertTrue(model.state.value.confirming)
        model.apply();model.apply()
        assertEquals(1,repository.writes);assertTrue(model.state.value.completed)
    }
    @Test fun conflictChoiceReplansBeforeConfirmation() = runBlocking {
        val repository=FakeImportRepository(withConflict=true);val model=ImportFlowViewModel(repository)
        model.text(json);model.parse();withTimeout(10_000) { while(model.state.value.busy) delay(10) }
        val session=checkNotNull(model.state.value.session)
        session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.forEach { model.evidence(it.fingerprint,true) }
        session.requirements.filter { it.kind==ReviewRequirementKind.RETAIN_UNKNOWN_OPTIONAL_METADATA || it.kind==ReviewRequirementKind.KEEP_UNSCHEDULED }.forEach { model.acceptRequirement(it,true) }
        model.check();withTimeout(10_000) { while(model.state.value.busy) delay(10) }
        model.confirm();assertFalse(model.state.value.confirming)
        model.resolveConflict("conflict",ConflictReviewChoice.SKIP);withTimeout(10_000) { while(model.state.value.busy) delay(10) }
        assertTrue(model.state.value.preview!!.ready);model.confirm();assertTrue(model.state.value.confirming)
        model.apply();assertEquals(1,repository.writes);assertEquals(ConflictReviewChoice.SKIP,repository.lastChoice)
    }
    @Test fun oneReviewActionCoversTheWholeImportWithoutWriting() = runBlocking {
        val repository=FakeImportRepository();val model=ImportFlowViewModel(repository)
        val timed=json.replace("\"courses\":","\"periods\":[{\"section\":1,\"start\":\"09:00\",\"end\":\"09:45\"}],\"courses\":")
        model.text(timed);model.parse();withTimeout(10_000) {while(model.state.value.busy) delay(10)}
        assertEquals(0,repository.writes)
        model.check();withTimeout(10_000) {while(model.state.value.busy) delay(10)}
        assertTrue(model.state.value.problems.toString(),model.state.value.preview?.ready==true)
        assertEquals(0,repository.writes)
        model.dismissPreview();model.apply();assertEquals(0,repository.writes)
    }
    @Test fun wholeImportReviewNeverPassesMissingRequiredTime() = runBlocking {
        val repository=FakeImportRepository();val model=ImportFlowViewModel(repository)
        model.text(json.replace("\"sections\":[1]","\"sections\":[]"));model.parse()
        withTimeout(10_000) {while(model.state.value.busy) delay(10)}
        model.check();withTimeout(10_000) {while(model.state.value.busy) delay(10)}
        assertNull(model.state.value.preview);assertFalse(model.state.value.confirming)
        model.apply();assertEquals(0,repository.writes)
    }
    @Test fun allConflictChoicesAreBoundAndReplannedInOneBatch() = runBlocking {
        val repository=FakeImportRepository(withConflict=true,conflictCount=20);val model=ImportFlowViewModel(repository)
        model.text(json);model.parse();withTimeout(10_000) {while(model.state.value.busy) delay(10)}
        model.check();withTimeout(10_000) {while(model.state.value.busy) delay(10)}
        assertEquals(1,repository.previews)
        model.resolveAllConflicts(ConflictReviewChoice.SKIP)
        withTimeout(10_000) {while(model.state.value.busy) delay(10)}
        assertEquals(2,repository.previews)
        assertEquals(20,repository.requestedDecisions.size)
        assertTrue(repository.requestedDecisions.all {it.choice==ConflictReviewChoice.SKIP && it.binding==model.state.value.session!!.binding})
        assertTrue(model.state.value.preview!!.ready)
        assertEquals(0,repository.writes)
    }
    @Test fun oneBatchSourceAnswerReplansTheWholeImport() = runBlocking {
        val repository=FakeImportRepository(withSourceRelation=true);val model=ImportFlowViewModel(repository)
        model.text(json);model.parse();withTimeout(10_000) { while(model.state.value.busy) delay(10) }
        val session=checkNotNull(model.state.value.session)
        session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.forEach { model.evidence(it.fingerprint,true) }
        session.requirements.filter { it.kind==ReviewRequirementKind.RETAIN_UNKNOWN_OPTIONAL_METADATA || it.kind==ReviewRequirementKind.KEEP_UNSCHEDULED }.forEach { model.acceptRequirement(it,true) }
        model.check();withTimeout(10_000) { while(model.state.value.busy) delay(10) }
        assertFalse(model.state.value.preview!!.ready)
        assertTrue(model.state.value.preview!!.sourceRelationRequired)
        assertNull(repository.requestedSourceRelation)
        assertEquals(0,repository.writes)
        model.confirm();assertFalse(model.state.value.confirming)
        model.confirmDifferentSource();withTimeout(10_000) { while(model.state.value.busy) delay(10) }
        assertEquals(SourceRelationChoice.DIFFERENT_SOURCE,repository.requestedSourceRelation)
        assertEquals(2,repository.previews)
        assertEquals(0,repository.writes)
        assertTrue(model.state.value.preview!!.ready);assertFalse(model.state.value.preview!!.sourceRelationRequired)
        model.confirm();assertTrue(model.state.value.confirming)
        model.apply();assertEquals(1,repository.writes)
    }
    private class FakeImportRepository(private val withConflict:Boolean=false,private val conflictCount:Int=1,private val withSourceRelation:Boolean=false) : ImportRepository {
        var writes=0
        var previews=0
        var requestedDecisions:List<ConflictReviewDecision> = emptyList()
        var lastChoice:ConflictReviewChoice?=null
        var requestedSourceRelation:SourceRelationChoice?=null
        private val snapshot=TimetableSnapshot(settings=SemesterSettings(startDate=LocalDate.parse("2026-09-07")),periods=defaultPeriods(),scopeToken="local")
        private val target=ImportTarget("local",1,0,4)
        override suspend fun readImportTarget(semesterId:Long)=ImportTargetView(target,snapshot,emptyList())
        override suspend fun previewImport(reviewed:ReviewedImport, conflicts:List<ConflictReviewDecision>):ImportPreviewResult {
            previews++;requestedDecisions=conflicts;requestedSourceRelation=reviewed.decisions.sourceRelation?.choice
            val range=TimeRange(LocalTime.of(8,0),LocalTime.of(8,45));val side=ImportOccurrenceView(null,null,LocalDate.parse("2026-09-07"),range)
            val found=if(withConflict) (0 until conflictCount).map {ImportConflictView(if(conflictCount==1) "conflict" else "conflict-$it",side,side,range)} else emptyList()
            val sourceRelationRequired=withSourceRelation && reviewed.decisions.sourceRelation==null
            val problems=buildList {
                if(withConflict && conflicts.map {it.conflictFingerprint}.toSet()!=found.map {it.fingerprint}.toSet()) add(ImportProblemView("CONFLICT_REVIEW_REQUIRED",null))
                if(sourceRelationRequired) add(ImportProblemView("ARCHIVE_MEMBERSHIP_REVIEW_REQUIRED",null))
            }
            return ImportPreviewResult(ImportPlanPreview(target,reviewed.reviewDigest,"digest",reviewed.semanticFingerprint,reviewed.counts,found.map { it.fingerprint }.toSet()),found,problems,false,sourceRelationRequired=sourceRelationRequired)
        }
        override suspend fun applyImport(command:ImportApplyCommand):ImportApplyResult { writes++;lastChoice=command.conflictDecisions.singleOrNull()?.choice;return ImportApplyResult.Applied(true,1) }
    }
}
