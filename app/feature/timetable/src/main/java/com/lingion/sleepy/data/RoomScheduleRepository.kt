package com.lingion.sleepy.data

import androidx.room.withTransaction
import com.lingion.sleepy.data.local.CourseMeetingEntity
import com.lingion.sleepy.data.local.MeetingSectionEntity
import com.lingion.sleepy.data.local.MeetingWeekEntity
import com.lingion.sleepy.data.local.PeriodEntity
import com.lingion.sleepy.data.local.ScheduledCourseEntity
import com.lingion.sleepy.data.local.SemesterEntity
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.data.local.ImportNormalizationStateEntity
import com.lingion.sleepy.domain.imports.*
import com.lingion.sleepy.domain.repository.*
import kotlinx.coroutines.CancellationException
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.Period
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.model.defaultPeriods
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.repository.ScheduleRepository
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class RoomScheduleRepository(private val database: TimetableDatabase) : ScheduleRepository, ImportRepository {
    private val dao = database.scheduleDao()
    internal var importTestFault: (String) -> Unit = {}

    override fun observeTimetable(): Flow<TimetableSnapshot> = flow {
        database.withTransaction { initializeIfEmpty() }
        // Every write changes the semester revision last, in the same transaction as its child rows.
        // Read the actual revision and all nested rows together; racing invalidations may be coalesced.
        emitAll(dao.observeRevision().map {
            database.withTransaction { readSnapshot() }
        }.distinctUntilChanged())
    }

    override suspend fun saveCourse(draft: CourseDraft, id: Long?): Long = database.withTransaction {
        initializeIfEmpty()
        val before = readSnapshot()
        val normalized = draft.copy(
            courseName = draft.courseName.trim(), teacher = draft.teacher.trim(), room = draft.room.trim(),
            meetings = draft.meetings.map { it.copy(teacher = it.teacher?.trim(), room = it.room?.trim()) },
        )
        val errors = TimetableRules.validateCourse(normalized, before.settings, before.periods)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        val oldCourse = if (id == null) null else {
            require(id > 0) { "课程标识必须为正整数" }
            requireNotNull(before.courses.singleOrNull { it.id == id }) { "未找到要编辑的课程" }
        }
        val storedState = id?.let { loadImportTarget(1).states[it] }
        if (storedState != null && oldCourse != null && normalized.meetings == oldCourse.meetings && normalized.provenance == oldCourse.provenance) {
            check(dao.updateCourse(ScheduledCourseEntity(id,1,normalized.courseName,normalized.teacher,normalized.room,normalized.color,normalized.sourceCourseCode,normalized.sourceClassCode)) == 1)
            val originals = storedState.graph.originals.map { original ->
                val unknown = original.identity.unknown.toMutableSet()
                if (original.original.teacher == null && oldCourse.teacher != normalized.teacher) unknown.remove(com.lingion.sleepy.importing.ImportField.TEACHER)
                if (original.original.room == null && oldCourse.room != normalized.room) unknown.remove(com.lingion.sleepy.importing.ImportField.ROOM)
                val identity = original.identity.copy(name=normalized.courseName,courseCode=normalized.sourceCourseCode,classCode=normalized.sourceClassCode,
                    teacher=(original.original.teacher ?: normalized.teacher).trim(),room=(original.original.room ?: normalized.room).trim(),unknown=unknown)
                original.copy(identity=identity,descriptorFingerprint=ImportOccurrencePlanner.descriptorFingerprint(original.original,identity,original.intervals,original.sectionsUseTarget))
            }
            val additions = storedState.graph.adds.map { it.copy(identity=it.identity.copy(name=normalized.courseName,courseCode=normalized.sourceCourseCode,classCode=normalized.sourceClassCode)) }
            val updated = storedState.copy(graph=storedState.graph.copy(originals=originals,adds=additions))
            val actual=readSnapshot(1);val actualCourse=actual.courses.single { it.id==id }
            ImportNormalizationCodec.validate(updated,actual.scopeToken,1,actualCourse,actual)
            dao.putImportState(ImportNormalizationStateEntity(id,updated.version,ImportNormalizationCodec.encode(updated)))
            incrementRevision()
            return@withTransaction id
        }
        if (storedState != null) {
            // A legacy rebase is safe only when the graph contains no effects or externally absorbed gaps.
            require(storedState.graph.originals.all { it.identity.unknown.isEmpty() }) { "含未知导入信息的安排需要专门的审核编辑" }
            require(storedState.graph.removes.isEmpty() && storedState.graph.adds.isEmpty() && storedState.graph.groups.isEmpty()) { "已有导入例外的安排需要专门的审核编辑" }
            require(storedState.graph.originals.all { original ->
                val dates=ImportOccurrencePlanner.originalDates(original.original,before.settings.startDate?.let(TimetableRules::mondayOf),before.settings.totalWeeks)
                dates.all { date -> original.intervals.all { range -> ImportResidualBuilder.subtract(range,original.allocated.filter { it.date==date }.map { it.range }).isEmpty() } }
            }) { "已有部分覆盖的安排需要专门的审核编辑" }
            check(dao.deleteImportState(id,1)==1)
        }
        val course = ScheduledCourseEntity(id = id ?: 0, semesterId = 1, courseName = normalized.courseName,
            teacher = normalized.teacher, room = normalized.room, color = normalized.color,
            sourceCourseCode = normalized.sourceCourseCode, sourceClassCode = normalized.sourceClassCode)
        val courseId = if (id == null) dao.insertCourse(course) else {
            check(dao.updateCourse(course) == 1) { "课程更新失败" }
            id
        }
        writeCourseChildren(courseId, normalized, oldCourse, 1)
        incrementRevision()
        courseId
    }

    override suspend fun deleteCourse(id: Long) {
        database.withTransaction {
            initializeIfEmpty()
            require(id > 0 && dao.course(id) != null) { "未找到要删除的课程" }
            check(dao.deleteCourse(id) == 1) { "课程删除失败" }
            incrementRevision()
        }
    }

    override suspend fun updateSettings(settings: SemesterSettings, periods: List<Period>) {
        database.withTransaction {
            initializeIfEmpty()
            val before = readSnapshot()
            val normalized = settings.copy(name = settings.name.trim())
            require(normalized.id == before.settings.id) { "不能修改当前学期标识" }
            val errors = TimetableRules.validateSettings(normalized) + TimetableRules.validatePeriods(periods)
            require(errors.isEmpty()) { errors.joinToString("\n") }
            val meetings = before.courses.flatMap { it.meetings }
            // Date-bound cancellations/reschedules must keep referring to a real base occurrence.
            // Do not blanket-validate legacy courses: inert weeks and missing archived periods stay readable.
            require(meetings.all { meeting -> meeting.exceptions.all { change ->
                change.originalDate?.let { TimetableRules.baseOccursOn(meeting, normalized, it) } ?: true
            } }) { "学期修改会使已有例外的原日期失效" }
            require(meetings.none { meeting -> meeting.weeks.any { it > normalized.totalWeeks } }) {
                "学期总周数不能小于已有课程的周次"
            }
            val removedNumbers = before.periods.map { it.number }.toSet() - periods.map { it.number }.toSet()
            require(meetings.none { meeting ->
                (meeting.kind != MeetingKind.UNSCHEDULED && meeting.customTime == null && meeting.sections.any(removedNumbers::contains)) ||
                meeting.exceptions.any { change ->
                    val effectiveTime = change.customTime ?: if (change.sections != null) null else meeting.customTime
                    effectiveTime == null && (change.sections ?: meeting.sections).any(removedNumbers::contains)
                }
            }) {
                "不能删除已有课程使用的节次"
            }
            val exceptionErrors = before.courses.flatMap { course ->
                TimetableRules.validateExceptions(course.asDraft(), normalized, periods)
            }
            require(exceptionErrors.isEmpty()) { exceptionErrors.joinToString("\n") }
            val states = loadImportTarget(before.settings.id).states
            val candidate = before.copy(settings=normalized,periods=periods)
            states.forEach { (id,state) ->
                ImportNormalizationCodec.validate(state,candidate.scopeToken,candidate.settings.id,candidate.courses.single { it.id==id },candidate)
            }
            // UPDATE retains the semester row and therefore cannot cascade-delete its courses.
            val semester = requireNotNull(dao.semester())
            dao.deletePeriods()
            dao.insertPeriods(periods.sortedBy { it.number }.map { it.toEntity(normalized.id) })
            check(dao.updateSemester(SemesterEntity(normalized.id, normalized.name, normalized.startDate?.toEpochDay(),
                normalized.totalWeeks, Math.addExact(semester.revision, 1))) == 1)
        }
    }

    override suspend fun readImportTarget(semesterId: Long): ImportTargetView = database.withTransaction {
        val loaded = loadImportTarget(semesterId)
        val snap = loaded.snapshot
        val details=ImportOccurrencePlanner.logicalBaseDetails(loaded)
        ImportTargetView(ImportTarget(snap.scopeToken, semesterId, snap.revision, loaded.databaseSchemaVersion), snap, details.map { it.reference },details)
    }

    private suspend fun loadImportTarget(semesterId: Long): LoadedImportTarget {
        val snapshot = readSnapshot(semesterId)
        val version = database.openHelper.readableDatabase.version
        val states = dao.importStates(semesterId).associate { row ->
            val course = snapshot.courses.singleOrNull { it.id == row.courseId } ?: throw InvalidNormalizationState()
            val state = ImportNormalizationCodec.decode(row.payload, snapshot.scopeToken, semesterId, course, snapshot)
            if (row.version != state.version) throw InvalidNormalizationState()
            row.courseId to state
        }
        return LoadedImportTarget(snapshot, version, states)
    }

    private fun verifiedReview(reviewed: ReviewedImport): ReviewedImport {
        val meetings = reviewed.sourceMappings.keys.filter { it.courseIndex != null && it.meetingIndex != null && it.exceptionIndex == null && it.pendingIndex == null }.toSet()
        val pending = reviewed.exceptions.mapNotNull { it.source.pendingIndex }.toSet()
        val exceptions = reviewed.exceptions.map { it.source }.filter { it.exceptionIndex != null }.toSet()
        val session = ImportReviewSession.create(reviewed.source, reviewed.context, meetings, pending, exceptions)
        val converted = RichDraftConverter.convert(session, reviewed.decisions) as? ImportReviewResult.Ready ?: error("INVALID_REVIEW")
        check(converted.reviewed.reviewDigest == reviewed.reviewDigest && converted.reviewed.binding == reviewed.binding) { "INVALID_REVIEW" }
        return converted.reviewed
    }

    override suspend fun previewImport(reviewed: ReviewedImport, conflicts: List<ConflictReviewDecision>): ImportPreviewResult = database.withTransaction {
        projectImportPreview(ImportOccurrencePlanner.plan(verifiedReview(reviewed), loadImportTarget(reviewed.context.target.semesterId), conflicts),conflicts)
    }

    override suspend fun applyImport(command: ImportApplyCommand): ImportApplyResult = try {
        database.withTransaction {
            val reviewed = verifiedReview(command.reviewed)
            val loaded = loadImportTarget(command.target.semesterId)
            val plan = ImportOccurrencePlanner.plan(reviewed, loaded, command.conflictDecisions)
            if (!plan.ready) return@withTransaction ImportApplyResult.Rejected(plan.problems.first().code)
            if (plan.preview.planDigest != command.expectedPlanDigest) return@withTransaction ImportApplyResult.Rejected("STALE_PLAN")
            if (plan.unchanged) return@withTransaction ImportApplyResult.Applied(false, loaded.snapshot.revision)
            val semesterId = command.target.semesterId
            val previous = loaded.snapshot.courses.associateBy { it.id }
            for (id in plan.removedCourseIds) check(dao.deleteCourse(id, semesterId) == 1)
            // Allocate all real course IDs before cross-course observations are hydrated.
            val courseIds = plan.courses.associate { planned ->
                val draft = planned.draft
                val id = planned.existingCourseId ?: dao.insertCourse(ScheduledCourseEntity(semesterId=semesterId, courseName=draft.courseName,
                    teacher=draft.teacher, room=draft.room, color=draft.color, sourceCourseCode=draft.sourceCourseCode, sourceClassCode=draft.sourceClassCode))
                planned.key to id
            }
            importTestFault("after_courses")
            val children = plan.courses.associate { planned ->
                val id = courseIds.getValue(planned.key); val draft = planned.draft
                if (planned.existingCourseId != null) check(dao.updateCourse(ScheduledCourseEntity(id,semesterId,draft.courseName,draft.teacher,draft.room,draft.color,draft.sourceCourseCode,draft.sourceClassCode)) == 1)
                planned.key to writeCourseChildren(id, draft, previous[id], semesterId)
            }
            importTestFault("after_children")
            val after = readSnapshot(semesterId)
            for (planned in plan.courses) {
                val ids = children.getValue(planned.key)
                check(ids.size == planned.fragments.size)
                val fragments = planned.fragments.mapIndexed { index, fragment -> fragment.key to ids[index].first }.toMap()
                val additions = mutableMapOf<String,Long>()
                planned.fragments.forEachIndexed { index, fragment ->
                    fragment.meeting.exceptions.forEachIndexed { eIndex, e ->
                        if (e.kind == com.lingion.sleepy.domain.model.ExceptionKind.EXTRA) {
                            val add = planned.state.adds.single { it.ownerFragmentKey == fragment.key && it.scope.date == e.targetDate && it.scope.range == e.customTime && it.identity.teacher == e.teacher && it.identity.room == e.room && it.identity.group == e.group && it.identity.project == e.experimentProject }
                            additions[add.exceptionKey] = ids[index].second[eIndex]
                        }
                    }
                }
                val course = after.courses.single { it.id == courseIds.getValue(planned.key) }
                val state = ImportNormalizationCodec.hydrate(planned, course, after.scopeToken, semesterId, after, fragments, additions, courseIds)
                dao.putImportState(ImportNormalizationStateEntity(course.id, state.version, ImportNormalizationCodec.encode(state)))
            }
            importTestFault("after_state")
            val next = Math.addExact(loaded.snapshot.revision, 1)
            val semester = checkNotNull(dao.semester(semesterId))
            importTestFault("before_revision")
            check(semester.revision == loaded.snapshot.revision && dao.updateSemester(semester.copy(revision=next)) == 1)
            ImportApplyResult.Applied(true, next)
        }
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { ImportApplyResult.Rejected("IMPORT_FAILED") }

    /** Reused by ordinary editing and the atomic import writer inside their transactions. */
    private suspend fun writeCourseChildren(courseId: Long, normalized: CourseDraft, oldCourse: ScheduledCourse?, semesterId: Long): List<Pair<Long,List<Long>>> {
        val previousMeetingIds = oldCourse?.meetings?.map { it.id }?.toSet().orEmpty()
        val reusedIds = normalized.meetings.map { it.id }.filter { it in previousMeetingIds }
        require(reusedIds.distinct().size == reusedIds.size) { "上课安排标识不能重复" }
        dao.deleteMeetings(courseId, semesterId)
        dao.deleteCourseProvenance(courseId, semesterId)
        dao.insertProvenance(normalized.provenance.toEntities(courseId = courseId))
        val ids = mutableListOf<Pair<Long,List<Long>>>()
        normalized.meetings.forEach { meeting ->
            // A caller cannot claim an ID belonging to another course. New meetings receive new IDs.
            val meetingId = dao.insertMeeting(CourseMeetingEntity(
                id = meeting.id.takeIf { it in previousMeetingIds } ?: 0,
                courseId = courseId, day = meeting.day,
                customStartMinute = meeting.customTime?.start?.minuteOfDay(),
                customEndMinute = meeting.customTime?.end?.minuteOfDay(),
                teacher = meeting.teacher, room = meeting.room,
                kind = meeting.kind.name, experimentProject = meeting.experimentProject, group = meeting.group, isExperiment = meeting.isExperiment,
            ))
            dao.insertWeeks(meeting.weeks.sorted().map { MeetingWeekEntity(meetingId, it) })
            dao.insertSections(meeting.sections.sorted().map { MeetingSectionEntity(meetingId, it) })
            val exceptionIds = dao.writeCalendarChildren(meetingId, meeting, oldCourse?.meetings?.singleOrNull { it.id == meeting.id })
            ids += meetingId to exceptionIds
        }
        return ids
    }

    private suspend fun initializeIfEmpty() {
        if (dao.semester() != null) return
        val settings = SemesterSettings()
        dao.insertSemester(SemesterEntity(settings.id, settings.name, null, settings.totalWeeks, 0))
        dao.insertPeriods(defaultPeriods().map { it.toEntity(settings.id) })
    }

    /** Call only from a Room transaction so the revision, settings and child rows form one snapshot. */
    private suspend fun readSnapshot(semesterId: Long = 1): TimetableSnapshot {
        val semester = checkNotNull(dao.semester(semesterId)) { "学期配置缺失" }
        val periods = dao.periods(semesterId).map { Period(it.number, timeOfMinute(it.startMinute), timeOfMinute(it.endMinute)) }
        val courses = dao.courses(semesterId)
        val meetingsByCourse = dao.meetings(semesterId).groupBy { it.courseId }
        val weeksByMeeting = dao.weeks(semesterId).groupBy { it.meetingId }
        val sectionsByMeeting = dao.sections(semesterId).groupBy { it.meetingId }
        val datesByMeeting = dao.dates(semesterId).groupBy { it.meetingId }
        val declaredByMeeting = dao.declaredWeeks(semesterId).groupBy { it.meetingId }
        val calendar = CalendarRowCodec(dao.provenance(semesterId), dao.exceptions(semesterId), dao.exceptionSections(semesterId))
        return TimetableSnapshot(
            settings = SemesterSettings(semester.id, semester.name, semester.startEpochDay?.let(LocalDate::ofEpochDay), semester.totalWeeks),
            periods = periods,
            courses = courses.map { course ->
                ScheduledCourse(course.id, course.courseName, course.teacher, course.room, course.color,
                    meetingsByCourse[course.id].orEmpty().map { meeting ->
                        check((meeting.customStartMinute == null) == (meeting.customEndMinute == null)) { "课程自定义时间数据不完整" }
                        CourseMeeting(
                            id = meeting.id, day = meeting.day,
                            weeks = weeksByMeeting[meeting.id].orEmpty().map { it.week }.toSet(),
                            sections = sectionsByMeeting[meeting.id].orEmpty().map { it.section }.toSet(),
                            customTime = meeting.customStartMinute?.let { start ->
                                TimeRange(timeOfMinute(start), timeOfMinute(checkNotNull(meeting.customEndMinute)))
                            },
                            teacher = meeting.teacher, room = meeting.room,
                            kind = MeetingKind.valueOf(meeting.kind),
                            dates = datesByMeeting[meeting.id].orEmpty().map { LocalDate.ofEpochDay(it.epochDay) }.toSet(),
                            experimentProject = meeting.experimentProject, group = meeting.group, isExperiment = meeting.isExperiment,
                            provenance = calendar.meetingSources(meeting.id), exceptions = calendar.exceptions(meeting.id),
                            declaredWeeks = declaredByMeeting[meeting.id].orEmpty().map { it.week }.toSet(),
                        )
                    }, sourceCourseCode = course.sourceCourseCode, sourceClassCode = course.sourceClassCode,
                    provenance = calendar.courseSources(course.id))
            },
            revision = semester.revision,
            scopeToken = database.scopeToken,
        ).also { snapshot ->
            // Validate new metadata and exception contracts while keeping legacy base rows lossless.
            snapshot.courses.forEach { course ->
                val draft = course.asDraft()
                val errors = TimetableRules.validateMetadata(draft) +
                    TimetableRules.validateExceptions(draft, snapshot.settings, snapshot.periods)
                require(errors.isEmpty()) { errors.joinToString("\n") }
            }
        }
    }

    private suspend fun incrementRevision() {
        val semester = checkNotNull(dao.semester())
        check(dao.updateSemester(semester.copy(revision = Math.addExact(semester.revision, 1))) == 1)
    }

    private fun Period.toEntity(semesterId: Long) = PeriodEntity(semesterId, number, start.minuteOfDay(), end.minuteOfDay())
    private fun LocalTime.minuteOfDay() = hour * 60 + minute
    private fun timeOfMinute(value: Int): LocalTime {
        require(value in 0..1439) { "时间必须在同一天内" }
        return LocalTime.of(value / 60, value % 60)
    }
}
