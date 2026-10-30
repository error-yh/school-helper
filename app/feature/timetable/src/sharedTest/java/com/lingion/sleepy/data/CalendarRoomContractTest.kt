package com.lingion.sleepy.data

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.lingion.sleepy.data.local.*
import com.lingion.sleepy.domain.model.*
import com.lingion.sleepy.domain.imports.*
import com.lingion.sleepy.domain.repository.ImportApplyResult
import com.lingion.sleepy.importing.RichCourseDraft
import com.lingion.sleepy.importing.RichMeetingDraft
import com.lingion.sleepy.importing.RichTimetableDraft
import com.lingion.sleepy.importing.formats.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The same contract executes with real Android SQLite on a device and native SQLite under Robolectric. */
abstract class CalendarRoomContractTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableSetOf<String>()
    private val opened = mutableListOf<TimetableDatabase>()
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        TimetableDatabase::class.java.canonicalName!!, FrameworkSQLiteOpenHelperFactory())
    @After fun cleanup() { opened.forEach { it.close() }; names.forEach { context.deleteDatabase(it) } }
    private fun name() = "synthetic_calendar_${UUID.randomUUID()}.db".also(names::add)
    private fun open(name: String) = Room.databaseBuilder(context, TimetableDatabase::class.java, name)
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).addCallback(CALENDAR_CALLBACK).build().also(opened::add)
    private suspend fun state(db: TimetableDatabase) = withTimeout(10_000) { RoomScheduleRepository(db).observeTimetable().first() }
    private val date = LocalDate.parse("2027-02-03")
    private val provenance = listOf(ScheduleProvenance("fixture", "synthetic", 1, SourceBounds(1f,2f,3f,4f), "Synthetic source"),
        ScheduleProvenance(rawText="Synthetic cell",sourceLocator="Sheet1!A3"))
    private fun draft() = CourseDraft("Synthetic", meetings = listOf(
        CourseMeeting(0, 0, emptySet(), setOf(1), teacher = "A", kind = MeetingKind.DATE_SPECIFIC, dates = setOf(date,date.plusDays(3)),
            experimentProject = "Project", group = "Group", isExperiment = true, provenance = provenance, declaredWeeks = setOf(6),
            exceptions = listOf(MeetingException(kind = ExceptionKind.RESCHEDULE, originalDate = date,
                targetDate = date.plusDays(1), sections = setOf(2, 3), teacher = "B", room = "", experimentProject = "Override project", group = "G2", provenance = provenance),
                MeetingException(kind=ExceptionKind.CANCEL,originalDate=date.plusDays(3),provenance=provenance),
                MeetingException(kind=ExceptionKind.EXTRA,targetDate=date.plusDays(4),customTime=TimeRange(LocalTime.NOON,LocalTime.of(13,0)),provenance=provenance))),
        CourseMeeting(0, 0, emptySet(), emptySet(), kind = MeetingKind.UNSCHEDULED, provenance = provenance),
    ), sourceCourseCode = "C", sourceClassCode = "K", provenance = provenance)

    @Test fun migrationTo4PreservesOldRowsAndCascadesOnlyNewState() {
        for (version in 1..3) {
            val name = name()
            helper.createDatabase(name, version).use { old ->
                old.execSQL("INSERT INTO courses VALUES(91,'Synthetic archive',1,1,1,1,2,0)")
                if (version >= 2) {
                    old.execSQL("INSERT INTO semesters VALUES(1,'Synthetic term',NULL,20,37)")
                    old.execSQL("INSERT INTO scheduled_courses(id,semesterId,courseName,teacher,room,color) VALUES(42,1,'Synthetic','T','R',7)")
                }
            }
            val chain = when(version) { 1 -> arrayOf(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4); 2 -> arrayOf(MIGRATION_2_3,MIGRATION_3_4); else -> arrayOf(MIGRATION_3_4) }
            helper.runMigrationsAndValidate(name,4,true,*chain).use { migrated ->
                migrated.query("SELECT count(*) FROM courses WHERE id=91").use { assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0)) }
                if (version >= 2) {
                    migrated.query("SELECT revision FROM semesters WHERE id=1").use { assertTrue(it.moveToFirst());assertEquals(37,it.getLong(0)) }
                    migrated.query("PRAGMA foreign_key_list(`import_normalization_state`)").use { assertTrue(it.moveToFirst());assertEquals("scheduled_courses",it.getString(2));assertEquals("CASCADE",it.getString(6)) }
                    // MigrationTestHelper opens its raw connection without Room's foreign-key configuration.
                    migrated.execSQL("PRAGMA foreign_keys=ON")
                    migrated.query("PRAGMA foreign_keys").use { assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0)) }
                    migrated.execSQL("INSERT INTO import_normalization_state(courseId,version,payload) VALUES(42,1,'synthetic')")
                    migrated.execSQL("DELETE FROM scheduled_courses WHERE id=42")
                    migrated.query("SELECT count(*) FROM import_normalization_state").use { assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0)) }
                }
            }
        }
    }
    @Test fun migration3to4PreservesEveryRowAndSequence() {
        val dbName=name();val old=helper.createDatabase(dbName,3)
        val statements=listOf(
            "INSERT INTO courses VALUES(91,'Archive',1,1,1,1,2,0)",
            "INSERT INTO semesters VALUES(1,'Term',20703,20,37)",
            "INSERT INTO periods VALUES(1,1,480,525)",
            "INSERT INTO scheduled_courses VALUES(42,1,'Course','Teacher','Room',7,'C','K')",
            "INSERT INTO course_meetings VALUES(43,42,1,NULL,NULL,NULL,NULL,'RECURRING',NULL,NULL,0)",
            "INSERT INTO meeting_weeks VALUES(43,1)","INSERT INTO meeting_sections VALUES(43,1)",
            "INSERT INTO meeting_dates VALUES(43,20703)","INSERT INTO meeting_declared_weeks VALUES(43,1)",
            "INSERT INTO meeting_exceptions VALUES(44,43,'CANCEL',20703,NULL,NULL,NULL,0,NULL,NULL,NULL,NULL)",
            "INSERT INTO exception_sections VALUES(44,1)",
            "INSERT INTO schedule_provenance(id,courseId,position,rawText) VALUES(45,42,0,'Synthetic')"
        )
        statements.forEach(old::execSQL)
        val tables=listOf("courses","semesters","periods","scheduled_courses","course_meetings","meeting_weeks","meeting_sections","meeting_dates","meeting_declared_weeks","meeting_exceptions","exception_sections","schedule_provenance","sqlite_sequence")
        fun rows(database:SupportSQLiteDatabase,table:String):List<List<String?>> = database.query("SELECT * FROM `$table` ORDER BY rowid").use { c ->
            buildList { while(c.moveToNext()) add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) }) }
        }
        val before=tables.associateWith { rows(old,it) };old.close()
        helper.runMigrationsAndValidate(dbName,4,true,MIGRATION_3_4).use { migrated ->
            assertEquals(before,tables.associateWith { rows(migrated,it) })
            migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun daoReadsAndDeletesOnlyTheRequestedSemester() = runBlocking<Unit> {
        val db=open(name());val dao=db.scheduleDao()
        dao.insertSemester(SemesterEntity(1,"First",null,20,0));dao.insertSemester(SemesterEntity(2,"Second",null,20,4))
        val first=dao.insertCourse(ScheduledCourseEntity(semesterId=1,courseName="First",teacher="",room="",color=1))
        val second=dao.insertCourse(ScheduledCourseEntity(semesterId=2,courseName="Second",teacher="",room="",color=2))
        assertEquals(listOf(first),dao.courses(1).map { it.id });assertEquals(listOf(second),dao.courses(2).map { it.id })
        assertNull(dao.course(first,2));assertEquals(0,dao.deleteCourse(first,2));assertEquals(1,dao.deleteCourse(second,2))
        assertEquals(first,dao.course(first,1)!!.id);assertEquals(4,dao.semester(2)!!.revision)
    }
    @Test fun correctedSourceTimeIsAppliedAndRetainsEvidenceAfterReopen() = runBlocking<Unit> {
        val dbName=name();val db=open(dbName);val repo=RoomScheduleRepository(db)
        state(db) // Open the same local profile/default semester as the host screen.
        val target=repo.readImportTarget(1)
        val json="""{"schemaVersion":1,"courses":[{"key":"correction","name":"Synthetic correction","meetings":[{"key":"one","kind":"DATE_SPECIFIC","dates":["2026-10-28"],"time":{"start":"09:20","end":"10:05"},"teacher":"Synthetic teacher","room":"Synthetic room","group":"","experimentProject":""}]}]}"""
        val parsed=VersionedJsonTimetableParser().parse(json.byteInputStream(),FormatParseContext("synthetic",20))
        val source=FrozenImportSource.fromFormat(parsed).withDocumentDisplayName("Synthetic course.json")
        val before=parsed.draft!!.courses.single().meetings.single()
        val corrected=source.correctMeeting(ReviewLocator(courseIndex=0,meetingIndex=0),
            before.copy(customTime=com.lingion.sleepy.importing.DraftTimeRange(LocalTime.of(9,35),LocalTime.of(10,20))),
            setOf(com.lingion.sleepy.importing.ImportField.TIME),20)
        val session=ImportReviewSession.create(corrected,ImportReviewContext(target.target,targetPeriods=target.snapshot.periods))
        val decisions=ImportReviewDecisions(evidence=session.requirements.filter {it.kind==ReviewRequirementKind.EVIDENCE}.map {session.reviewEvidence(it.fingerprint)})
        val reviewed=(RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
        val preview=repo.previewImport(reviewed);assertTrue(preview.ready)
        val result=repo.applyImport(ImportApplyCommand(target.target,reviewed.context.mode,reviewed,preview.preview.planDigest,emptyList()))
        assertTrue("Corrected apply: $result",result is ImportApplyResult.Applied)
        val saved=state(db);assertEquals(LocalTime.of(9,35),saved.courses.single().meetings.single().customTime!!.start)
        assertTrue(saved.courses.single().meetings.single().provenance.any {it.sourceId=="manual-correction"})
        assertTrue(saved.courses.single().meetings.single().provenance.any {it.sourceLocator=="document:display-name" && it.rawText=="Synthetic course.json"})
        db.close();assertEquals(saved,state(open(dbName)))
    }
    @Test fun renamedInputFileDoesNotDuplicateTheSameReviewedSchedule() = runBlocking<Unit> {
        val db=open(name());val repo=RoomScheduleRepository(db);state(db)
        suspend fun review(filename:String):ReviewedImport {
            val target=repo.readImportTarget(1)
            val meeting=RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.DATE_SPECIFIC,sections=setOf(1),dates=setOf(date),teacher="",room="",group="",experimentProject="")
            val source=FrozenImportSource.fromPdf(RichTimetableDraft(courses=listOf(RichCourseDraft("Synthetic input",meetings=listOf(meeting))))).withDocumentDisplayName(filename)
            val session=ImportReviewSession.create(source,ImportReviewContext(target.target,targetPeriods=target.snapshot.periods))
            val decisions=ImportReviewDecisions(evidence=session.requirements.filter {it.kind==ReviewRequirementKind.EVIDENCE}.map {session.reviewEvidence(it.fingerprint)})
            return (RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
        }
        val first=review("First.pdf");val preview=repo.previewImport(first);assertTrue(preview.ready)
        assertTrue(repo.applyImport(ImportApplyCommand(first.context.target,first.context.mode,first,preview.preview.planDigest,emptyList())) is ImportApplyResult.Applied)
        val saved=state(db)
        val renamed=review("Renamed.pdf");val duplicate=repo.previewImport(renamed)
        assertTrue(duplicate.ready);assertTrue(duplicate.unchanged)
        val result=repo.applyImport(ImportApplyCommand(renamed.context.target,renamed.context.mode,renamed,duplicate.preview.planDigest,emptyList()))
        assertTrue(result is ImportApplyResult.Applied && !result.changed)
        assertEquals(saved,state(db))
    }
    @Test fun reviewedImportIsAtomicIdempotentAndRejectsStaleRevision() = runBlocking<Unit> {
        val db=open(name());val repo=RoomScheduleRepository(db)
        val initial=state(db);val monday=LocalDate.parse("2026-09-07")
        repo.updateSettings(initial.settings.copy(startDate=monday),initial.periods)
        suspend fun reviewed(overrideTarget: ImportTarget? = null): ReviewedImport {
            val target=repo.readImportTarget(1)
            val source=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses=listOf(RichCourseDraft("Imported",meetings=listOf(RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,1,setOf(1),setOf(1))))))))
            val session=ImportReviewSession.create(source,ImportReviewContext(overrideTarget ?: target.target,termMonday=monday,targetPeriods=target.snapshot.periods))
            val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) })
            return (RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
        }
        val actualTarget=repo.readImportTarget(1).target
        for (badTarget in listOf(actualTarget.copy(scopeToken="other"),actualTarget.copy(databaseSchemaVersion=3),actualTarget.copy(expectedRevision=actualTarget.expectedRevision+1))) {
            val badReview=reviewed(badTarget);assertFalse(repo.previewImport(badReview).ready)
            val rejected=repo.applyImport(ImportApplyCommand(badTarget,badReview.context.mode,badReview,"invalid",emptyList()))
            assertTrue(rejected is ImportApplyResult.Rejected)
        }
        val first=reviewed();val preview=repo.previewImport(first);assertTrue(preview.ready)
        val command=ImportApplyCommand(first.context.target,first.context.mode,first,preview.preview.planDigest,emptyList())
        for (point in listOf("after_courses","after_children","after_state","before_revision")) {
            val before=state(db)
            repo.importTestFault={ if(it==point) error("synthetic") }
            assertTrue(repo.applyImport(command) is ImportApplyResult.Rejected);assertEquals(before,state(db))
            repo.importTestFault={ if(it==point) throw kotlinx.coroutines.CancellationException("synthetic") }
            try { repo.applyImport(command);fail("Expected cancellation") } catch (_: kotlinx.coroutines.CancellationException) { }
            assertEquals(before,state(db));assertTrue(db.scheduleDao().importStates(1).isEmpty())
        }
        repo.importTestFault={}
        val semesterBeforeOverflow=db.scheduleDao().semester(1)!!
        db.scheduleDao().updateSemester(semesterBeforeOverflow.copy(revision=Long.MAX_VALUE))
        val overflowReview=reviewed();val overflowPreview=repo.previewImport(overflowReview);assertTrue(overflowPreview.ready)
        val overflowBefore=state(db)
        assertTrue(repo.applyImport(ImportApplyCommand(overflowReview.context.target,overflowReview.context.mode,overflowReview,overflowPreview.preview.planDigest,emptyList())) is ImportApplyResult.Rejected)
        assertEquals(overflowBefore,state(db));assertTrue(db.scheduleDao().importStates(1).isEmpty())
        db.scheduleDao().updateSemester(semesterBeforeOverflow)
        val result=repo.applyImport(command);assertTrue(result is ImportApplyResult.Applied && result.changed)
        val after=state(db);assertEquals(1,after.courses.size)
        assertTrue(repo.applyImport(command) is ImportApplyResult.Rejected);assertEquals(after,state(db))
        val again=reviewed();val duplicate=repo.previewImport(again);assertTrue(duplicate.ready);assertTrue(duplicate.unchanged)
        val noOp=repo.applyImport(ImportApplyCommand(again.context.target,again.context.mode,again,duplicate.preview.planDigest,emptyList()))
        assertTrue(noOp is ImportApplyResult.Applied && !noOp.changed);assertEquals(after,state(db))
        val originalRow=db.scheduleDao().importStates(1).single()
        val savedState=ImportNormalizationCodec.decode(originalRow.payload,"local",1,after.courses.single(),after)
        val original=savedState.graph.originals.single()
        val unknownIdentity=original.identity.copy(unknown=original.identity.unknown + com.lingion.sleepy.importing.ImportField.TEACHER)
        val unknownOriginal=original.copy(identity=unknownIdentity,descriptorFingerprint=ImportOccurrencePlanner.descriptorFingerprint(original.original,unknownIdentity,original.intervals,original.sectionsUseTarget))
        val unknownState=savedState.copy(graph=savedState.graph.copy(originals=listOf(unknownOriginal)))
        db.scheduleDao().putImportState(originalRow.copy(payload=ImportNormalizationCodec.encode(unknownState)))
        try { repo.saveCourse(after.courses.single().asDraft().copy(meetings=after.courses.single().meetings.map { it.copy(weeks=setOf(2)) }),after.courses.single().id);fail("Unknown fields require review") } catch (_: IllegalArgumentException) { }
        assertEquals(after,state(db));assertEquals(unknownState,ImportNormalizationCodec.decode(db.scheduleDao().importStates(1).single().payload,"local",1,after.courses.single(),after))
        db.scheduleDao().putImportState(originalRow)
        val course=after.courses.single()
        repo.saveCourse(course.asDraft().copy(courseName="Renamed",color=99),course.id)
        val renamed=state(db);assertEquals("Renamed",renamed.courses.single().courseName)
        assertEquals(1,repo.readImportTarget(1).logicalBases.size)
        repo.updateSettings(renamed.settings.copy(name="Renamed term"),renamed.periods)
        val settingsChanged=state(db);assertEquals(1,repo.readImportTarget(1).logicalBases.size)
        try { repo.updateSettings(settingsChanged.settings,settingsChanged.periods.map { if(it.number==1) it.copy(start=it.start.plusMinutes(5)) else it });fail("Changed imported interval requires review") } catch (_: IllegalArgumentException) { }
        assertEquals(settingsChanged,state(db))
        val validState=db.scheduleDao().importStates(1).single()
        db.scheduleDao().putImportState(validState.copy(payload="invalid"))
        try { repo.readImportTarget(1);fail("Corrupt state must fail closed") } catch (_: IllegalArgumentException) { }
        db.scheduleDao().putImportState(validState)
        val edited=settingsChanged.courses.single()
        repo.saveCourse(edited.asDraft().copy(meetings=edited.meetings.map { it.copy(weeks=setOf(2)) }),edited.id)
        val rebased=state(db);assertEquals(setOf(2),rebased.courses.single().meetings.single().weeks)
        assertEquals(1,repo.readImportTarget(1).logicalBases.size)
        db.close();assertEquals(rebased,state(open(names.last())))
    }
    @Test fun partialCancellationRemainsIdempotentAfterReopen() = runBlocking<Unit> {
        val dbName=name();val db=open(dbName);val repo=RoomScheduleRepository(db)
        val initial=state(db);val monday=LocalDate.parse("2026-09-07")
        repo.updateSettings(initial.settings.copy(startDate=monday),initial.periods)
        suspend fun review(repository: RoomScheduleRepository): ReviewedImport {
            val target=repository.readImportTarget(1)
            val base=RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,1,setOf(1,2),setOf(1,2))
            val notice=com.lingion.sleepy.importing.RichExceptionDraft(com.lingion.sleepy.importing.RichExceptionKind.CANCEL,monday,sections=setOf(2))
            val source=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses=listOf(RichCourseDraft("Partial",meetings=listOf(base)))),pendingExceptions=listOf(PendingExceptionAttachment(exception=notice,reason="test"))))
            val session=ImportReviewSession.create(source,ImportReviewContext(target.target,termMonday=monday,targetPeriods=target.snapshot.periods))
            val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) },attachments=listOf(session.attachException(ReviewLocator(pendingIndex=0),ExceptionAttachment.Bound(session.inputBase(ReviewLocator(courseIndex=0,meetingIndex=0))))))
            val result=RichDraftConverter.convert(session,decisions);assertTrue(result.toString(),result is ImportReviewResult.Ready)
            return (result as ImportReviewResult.Ready).reviewed
        }
        val reviewed=review(repo);val preview=repo.previewImport(reviewed);assertTrue(preview.problems.toString(),preview.ready)
        assertTrue(repo.applyImport(ImportApplyCommand(reviewed.context.target,reviewed.context.mode,reviewed,preview.preview.planDigest,emptyList())) is ImportApplyResult.Applied)
        val before=state(db);assertTrue(before.courses.single().meetings.any { m -> m.exceptions.any { it.kind==ExceptionKind.CANCEL } })
        val originalDisplay=repo.readImportTarget(1).logicalBaseDetails.single()
        assertEquals(setOf(1,2),originalDisplay.original.weeks)
        assertEquals(setOf(1,2),originalDisplay.original.sections)
        assertEquals(2,originalDisplay.timeRanges.size)
        db.close();val reopened=open(dbName);val reRepo=RoomScheduleRepository(reopened)
        assertEquals(originalDisplay,reRepo.readImportTarget(1).logicalBaseDetails.single())
        val repeat=review(reRepo);val again=reRepo.previewImport(repeat);assertTrue(again.problems.toString(),again.ready);assertTrue(again.unchanged)
        val applied=reRepo.applyImport(ImportApplyCommand(repeat.context.target,repeat.context.mode,repeat,again.preview.planDigest,emptyList()))
        assertTrue(applied is ImportApplyResult.Applied && !applied.changed);assertEquals(before,state(reopened))
    }
    @Test fun replaceOnlyTouchesSelectedSemesterAndRejectsStaleDigest() = runBlocking<Unit> {
        val db=open(name());val repo=RoomScheduleRepository(db);val first=state(db);val dao=db.scheduleDao();val monday=LocalDate.parse("2026-09-07")
        repo.saveCourse(CourseDraft("Keep first",meetings=listOf(CourseMeeting(day=1,weeks=setOf(1),sections=setOf(1)))))
        dao.insertSemester(SemesterEntity(2,"Second",monday.toEpochDay(),20,9))
        dao.insertPeriods(first.periods.map { PeriodEntity(2,it.number,it.start.hour*60+it.start.minute,it.end.hour*60+it.end.minute) })
        val seedTarget=repo.readImportTarget(2)
        val seedSource=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses=listOf(RichCourseDraft("Replace me",meetings=listOf(RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,1,setOf(1),setOf(1))))))))
        val seedSession=ImportReviewSession.create(seedSource,ImportReviewContext(seedTarget.target,termMonday=monday,targetPeriods=seedTarget.snapshot.periods))
        val seedDecisions=ImportReviewDecisions(evidence=seedSession.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { seedSession.reviewEvidence(it.fingerprint) })
        val seed=(RichDraftConverter.convert(seedSession,seedDecisions) as ImportReviewResult.Ready).reviewed
        val seedPreview=repo.previewImport(seed);assertTrue(seedPreview.ready)
        assertTrue(repo.applyImport(ImportApplyCommand(seed.context.target,seed.context.mode,seed,seedPreview.preview.planDigest,emptyList())) is ImportApplyResult.Applied)
        val old=dao.courses(2).single().id;val oldStates=dao.importStates(2);assertEquals(1,oldStates.size)
        val before=state(db);val target=repo.readImportTarget(2)
        val source=FrozenImportSource.fromFormat(FormatParseResult(RichTimetableDraft(courses=listOf(RichCourseDraft("Replacement",meetings=listOf(RichMeetingDraft(com.lingion.sleepy.importing.MeetingKind.RECURRING,1,setOf(1),setOf(1))))))))
        val session=ImportReviewSession.create(source,ImportReviewContext(target.target,ImportMode.REPLACE,monday,20,target.snapshot.periods))
        val decisions=ImportReviewDecisions(evidence=session.requirements.filter { it.kind==ReviewRequirementKind.EVIDENCE }.map { session.reviewEvidence(it.fingerprint) })
        val reviewed=(RichDraftConverter.convert(session,decisions) as ImportReviewResult.Ready).reviewed
        val preview=repo.previewImport(reviewed);assertTrue(preview.problems.toString(),preview.ready)
        val bad=ImportApplyCommand(target.target,ImportMode.REPLACE,reviewed,"bad digest",emptyList())
        assertTrue(repo.applyImport(bad) is ImportApplyResult.Rejected);assertEquals(old,dao.courses(2).single().id);assertEquals(10,dao.semester(2)!!.revision)
        val replaceCommand=ImportApplyCommand(target.target,ImportMode.REPLACE,reviewed,preview.preview.planDigest,emptyList())
        repo.importTestFault={ if(it=="after_state") error("synthetic") }
        assertTrue(repo.applyImport(replaceCommand) is ImportApplyResult.Rejected)
        assertEquals(target.snapshot,repo.readImportTarget(2).snapshot);assertEquals(oldStates,dao.importStates(2));assertEquals(before,state(db))
        repo.importTestFault={}
        val result=repo.applyImport(replaceCommand)
        assertTrue(result is ImportApplyResult.Applied && result.changed)
        assertEquals(before,state(db));assertEquals(listOf("Replacement"),dao.courses(2).map { it.courseName });assertEquals(11,dao.semester(2)!!.revision)
    }
    @Test fun fullFieldRoundtripStableIdsEditReopenAndCascade() = runBlocking<Unit> {
        val name = name(); val db = open(name); val repository = RoomScheduleRepository(db)
        val id = repository.saveCourse(draft()); val saved = state(db)
        val course = saved.courses.single()
        assertEquals(draft(), course.asDraft().copy(meetings = course.meetings.map { m -> m.copy(id = 0, exceptions = m.exceptions.map { it.copy(id = 0) }) }))
        repository.saveCourse(course.asDraft().copy(courseName = "Edited"), id)
        val edited = state(db)
        assertEquals(course.meetings.map { it.id }, edited.courses.single().meetings.map { it.id })
        assertEquals(course.meetings.first().exceptions.map { it.id }, edited.courses.single().meetings.first().exceptions.map { it.id })
        db.close(); val reopened = open(name)
        assertEquals(edited, state(reopened)); assertEquals(4, reopened.openHelper.readableDatabase.version)
        RoomScheduleRepository(reopened).deleteCourse(id)
        for (table in listOf("course_meetings", "meeting_dates", "meeting_exceptions", "exception_sections", "schedule_provenance")) {
            reopened.openHelper.readableDatabase.query("SELECT count(*) FROM `$table`").use { assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0)) }
        }
    }
    @Test fun actualMigration2to3PreservesEveryOldColumnAndRow() = runBlocking<Unit> {
        val name = name()
        val old = helper.createDatabase(name, 2)
        old.execSQL("INSERT INTO courses VALUES(91,'Synthetic archive',1,1,1,1,2,0)")
        old.execSQL("INSERT INTO semesters VALUES(1,'Synthetic term',NULL,20,37)")
        old.execSQL("INSERT INTO periods VALUES(1,1,480,525)")
        old.execSQL("INSERT INTO scheduled_courses VALUES(7,1,'Synthetic','Teacher','Room',123)")
        old.execSQL("INSERT INTO course_meetings VALUES(9,7,1,NULL,NULL,NULL,NULL)")
        old.execSQL("INSERT INTO meeting_weeks VALUES(9,1)")
        old.execSQL("INSERT INTO meeting_sections VALUES(9,1)")
        val tables = listOf("courses","semesters","periods","scheduled_courses","course_meetings","meeting_weeks","meeting_sections")
        val columns = tables.associateWith { table -> old.query("SELECT * FROM `$table`").use { it.columnNames.toList() } }
        fun rows(db: SupportSQLiteDatabase, table: String): List<List<String?>> = db.query("SELECT ${columns.getValue(table).joinToString { "`$it`" }} FROM `$table`").use { c ->
            buildList { while(c.moveToNext()) add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) }) }
        }
        val before = tables.associateWith { rows(old,it) }; old.close()
        helper.runMigrationsAndValidate(name,3,true,MIGRATION_2_3).use { migrated ->
            assertEquals(before,tables.associateWith { rows(migrated,it) })
            migrated.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
        val db = open(name); val snapshot = state(db)
        assertEquals(37L,snapshot.revision)
        assertEquals(7L,snapshot.courses.single().id)
        assertEquals(9L,snapshot.courses.single().meetings.single().id)
        assertEquals(MeetingKind.RECURRING,snapshot.courses.single().meetings.single().kind)
    }
    @Test fun actualMigrationChain1to3KeepsArchiveAndIds() = runBlocking<Unit> {
        val name = name()
        helper.createDatabase(name,1).use { it.execSQL("INSERT INTO courses VALUES(91,'Synthetic archive',1,1,1,1,2,0)") }
        helper.runMigrationsAndValidate(name,3,true,MIGRATION_1_2,MIGRATION_2_3).close()
        val db = open(name)
        assertEquals(91L,state(db).courses.single().id)
        db.openHelper.readableDatabase.query("SELECT courseName FROM courses WHERE id=91").use { assertTrue(it.moveToFirst()); assertEquals("Synthetic archive",it.getString(0)) }
    }
    @Test fun exceptionSectionRemovalAndFailedChildWriteRollbackEntireRevision() = runBlocking<Unit> {
        val db = open(name()); val repo = RoomScheduleRepository(db)
        repo.saveCourse(draft()); val before = state(db)
        try { repo.updateSettings(before.settings,before.periods.filter { it.number != 3 }); fail("Expected referenced section rejection") } catch (_: IllegalArgumentException) { }
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_failure BEFORE INSERT ON exception_sections BEGIN SELECT RAISE(ABORT,'synthetic failure'); END")
        try { repo.saveCourse(before.courses.single().asDraft().copy(courseName = "Must roll back"), before.courses.single().id); fail("Expected SQL failure") } catch (_: android.database.SQLException) { }
        assertEquals(before,state(db))
        db.close(); assertEquals(before,state(open(names.last())))
    }
    @Test fun failureAfterReal2to3MigrationRollsBackVersionAndNewColumns() {
        val name = name()
        helper.createDatabase(name,2).use { it.execSQL("INSERT INTO semesters VALUES(1,'Synthetic',NULL,20,12)") }
        var ran = false
        val failure = object: Migration(2,3) { override fun migrate(db: SupportSQLiteDatabase) { MIGRATION_2_3.migrate(db); ran = true; error("Synthetic failure") } }
        val db = Room.databaseBuilder(context,TimetableDatabase::class.java,name).addMigrations(failure, MIGRATION_3_4).build()
        try { assertThrows(RuntimeException::class.java) { db.openHelper.writableDatabase }; assertTrue(ran) } finally { db.close() }
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(2,raw.version)
            raw.rawQuery("SELECT revision FROM semesters",null).use { assertTrue(it.moveToFirst()); assertEquals(12,it.getInt(0)) }
            raw.rawQuery("SELECT name FROM sqlite_master WHERE name='meeting_exceptions'",null).use { assertFalse(it.moveToFirst()) }
        }
    }
    @Test fun profilesAreIsolatedAndUnknownEnumFailsClosed() = runBlocking<Unit> {
        val profileA = "synth_cal_${UUID.randomUUID().toString().replace("-", "")}"; val profileB = profileA + "b"
        names += "timetable_$profileA.db"; names += "timetable_$profileB.db"
        val a = TimetableDatabase.open(context,profileA).also(opened::add)
        val b = TimetableDatabase.open(context,profileB).also(opened::add)
        RoomScheduleRepository(a).saveCourse(draft()); assertTrue(state(b).courses.isEmpty()); assertEquals(profileA,state(a).scopeToken)
        assertThrows(android.database.SQLException::class.java) { a.openHelper.writableDatabase.execSQL("UPDATE course_meetings SET kind='FUTURE'") }
        a.openHelper.writableDatabase.execSQL("DROP TRIGGER calendar_course_meetings_update")
        a.openHelper.writableDatabase.execSQL("UPDATE course_meetings SET kind='FUTURE'")
        try { state(a); fail("Expected unsupported kind failure") } catch (_: IllegalArgumentException) { }
    }
    @Test fun settingsCannotOrphanOriginalDateExceptionsAndFailureIsAtomic() = runBlocking<Unit> {
        val db = open(name()); val repo = RoomScheduleRepository(db)
        val initial = state(db); val start = LocalDate.parse("2026-09-07")
        repo.updateSettings(initial.settings.copy(startDate=start),initial.periods)
        repo.saveCourse(CourseDraft("Synthetic recurrence",meetings=listOf(CourseMeeting(0,1,setOf(1),setOf(1),
            exceptions=listOf(MeetingException(kind=ExceptionKind.CANCEL,originalDate=start))))))
        val before = state(db)
        try { repo.updateSettings(before.settings.copy(startDate=start.plusWeeks(1)),before.periods); fail("Expected orphan rejection") } catch (_: IllegalArgumentException) { }
        assertEquals(before,state(db)); db.close(); assertEquals(before,state(open(names.last())))
    }
    @Test fun periodTimeEditCannotCreateDuplicateExtrasAndFailureIsAtomicAfterReopen() = runBlocking<Unit> {
        val name = name(); val db = open(name); val repo = RoomScheduleRepository(db)
        val initial = state(db)
        repo.updateSettings(initial.settings, listOf(Period(1, LocalTime.of(8, 0), LocalTime.of(8, 45))))
        val extraDate = date.plusDays(1)
        repo.saveCourse(CourseDraft("Synthetic extras", meetings = listOf(
            CourseMeeting(day = 0, weeks = emptySet(), sections = setOf(1),
                kind = MeetingKind.DATE_SPECIFIC, dates = setOf(date), exceptions = listOf(
                    MeetingException(kind = ExceptionKind.EXTRA, targetDate = extraDate,
                        customTime = TimeRange(LocalTime.NOON, LocalTime.of(13, 0))),
                    MeetingException(kind = ExceptionKind.EXTRA, targetDate = extraDate, sections = setOf(1)),
                )),
        )))
        val before = state(db)
        try {
            repo.updateSettings(before.settings, listOf(Period(1, LocalTime.NOON, LocalTime.of(13, 0))))
            fail("Expected period time edit duplicate-extra rejection")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, state(db))
        db.close(); assertEquals(before, state(open(name)))
    }
    @Test fun newTableConstraintsRejectMalformedTimeSourceOwnerAndDateKind() = runBlocking<Unit> {
        val db=open(name()); RoomScheduleRepository(db).saveCourse(draft())
        val meeting=state(db).courses.single().meetings.first().id
        val sql=db.openHelper.writableDatabase
        for(statement in listOf(
            "INSERT INTO meeting_exceptions(meetingId,kind,originalEpochDay,targetEpochDay,customStartMinute,customEndMinute,hasSectionsOverride) VALUES($meeting,'EXTRA',NULL,1,480,NULL,0)",
            "INSERT INTO meeting_exceptions(meetingId,kind,hasSectionsOverride) VALUES($meeting,'FUTURE',0)",
            "INSERT INTO schedule_provenance(position,rawText) VALUES(0,'Synthetic')",
            "INSERT INTO meeting_declared_weeks(meetingId,week) VALUES($meeting,0)",
        )) assertThrows(android.database.SQLException::class.java) { sql.execSQL(statement) }
        val pending=state(db).courses.single().meetings.last().id
        assertThrows(android.database.SQLException::class.java) { sql.execSQL("INSERT INTO meeting_dates VALUES($pending,1)") }
    }
    @Test fun cancellationAfterActualNestedCourseWriteRollsBackAllChildrenAndRevision() = runBlocking<Unit> {
        val db=open(name()); val repo=RoomScheduleRepository(db)
        repo.saveCourse(draft()); val before=state(db)
        try {
            db.withTransaction {
                repo.saveCourse(before.courses.single().asDraft().copy(courseName="Must roll back"),before.courses.single().id)
                throw kotlinx.coroutines.CancellationException("Synthetic cancellation after actual writes")
            }
        } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(before,state(db)); db.close(); assertEquals(before,state(open(names.last())))
    }
    @Test fun readRejectsRescheduleWhoseOriginalDateHasNoBaseOccurrence() = runBlocking<Unit> {
        val db = open(name()); RoomScheduleRepository(db).saveCourse(draft())
        val meeting = state(db).courses.single().meetings.first().id
        // This row satisfies the installed SQL triggers but cannot refer to this date-specific meeting.
        db.openHelper.writableDatabase.execSQL("INSERT INTO meeting_exceptions(meetingId,kind,originalEpochDay,targetEpochDay,hasSectionsOverride) VALUES($meeting,'RESCHEDULE',1,2,0)")
        try { state(db); fail("Expected invalid original-date read rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun readRejectsDuplicateEffectiveExtraOccurrences() = runBlocking<Unit> {
        val db = open(name()); RoomScheduleRepository(db).saveCourse(draft())
        val meeting = state(db).courses.single().meetings.first().id
        db.openHelper.writableDatabase.execSQL("INSERT INTO meeting_exceptions(meetingId,kind,targetEpochDay,customStartMinute,customEndMinute,hasSectionsOverride) SELECT meetingId,kind,targetEpochDay,customStartMinute,customEndMinute,hasSectionsOverride FROM meeting_exceptions WHERE meetingId=$meeting AND kind='EXTRA'")
        try { state(db); fail("Expected duplicate extra read rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun readRejectsExceptionSectionWithoutConfiguredPeriod() = runBlocking<Unit> {
        val db = open(name()); RoomScheduleRepository(db).saveCourse(draft())
        // Positive section numbers are structurally valid; their configured times are a cross-table contract.
        db.openHelper.writableDatabase.execSQL("UPDATE exception_sections SET section=999 WHERE section=3")
        try { state(db); fail("Expected unknown exception section read rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun readRejectsAggregateSourceCapacityWithInstalledSqlConstraints() = runBlocking<Unit> {
        val db = open(name()); RoomScheduleRepository(db).saveCourse(draft())
        val course = state(db).courses.single().id
        val sql = db.openHelper.writableDatabase
        sql.execSQL("DELETE FROM schedule_provenance")
        repeat(3) { position ->
            sql.execSQL("INSERT INTO schedule_provenance(courseId,position,rawText) VALUES(?,?,?)",
                arrayOf<Any>(course, position, "x".repeat(400_000)))
        }
        try { state(db); fail("Expected aggregate source capacity read rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun duplicateOriginalDateRemainsRejectedByInstalledSqlConstraint() = runBlocking<Unit> {
        val db = open(name()); RoomScheduleRepository(db).saveCourse(draft())
        val before = state(db); val meeting = before.courses.single().meetings.first().id
        assertThrows(android.database.SQLException::class.java) {
            db.openHelper.writableDatabase.execSQL("INSERT INTO meeting_exceptions(meetingId,kind,originalEpochDay,hasSectionsOverride) VALUES($meeting,'CANCEL',${date.toEpochDay()},0)")
        }
        assertEquals(before, state(db))
    }
    @Test fun readRetainsLegacyInertWeeksAndUnconfiguredBaseSectionsWithoutExceptions() = runBlocking<Unit> {
        val name = name()
        helper.createDatabase(name, 2).use { old ->
            old.execSQL("INSERT INTO semesters VALUES(1,'Synthetic legacy',NULL,20,8)")
            old.execSQL("INSERT INTO scheduled_courses VALUES(7,1,'Synthetic legacy','','',123)")
            old.execSQL("INSERT INTO course_meetings VALUES(9,7,1,NULL,NULL,NULL,NULL)")
            old.execSQL("INSERT INTO meeting_sections VALUES(9,999)")
        }
        val snapshot = state(open(name)); val meeting = snapshot.courses.single().meetings.single()
        assertEquals(8L, snapshot.revision)
        assertTrue(snapshot.periods.isEmpty()); assertTrue(meeting.weeks.isEmpty())
        assertEquals(setOf(999), meeting.sections); assertTrue(meeting.exceptions.isEmpty())
    }
}

