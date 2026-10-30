package com.lingion.sleepy.data

import com.lingion.sleepy.data.local.*
import com.lingion.sleepy.domain.model.*
import java.time.LocalDate
import java.time.LocalTime

/** Strict decoding is shared by all repository reads; corrupt/future types never become defaults. */
internal class CalendarRowCodec(
    sources: List<ScheduleProvenanceEntity>,
    exceptions: List<MeetingExceptionEntity>,
    exceptionSections: List<ExceptionSectionEntity>,
) {
    private val courseSources = sources.filter { it.courseId != null }.groupBy { it.courseId }
    private val meetingSources = sources.filter { it.meetingId != null }.groupBy { it.meetingId }
    private val exceptionSources = sources.filter { it.exceptionId != null }.groupBy { it.exceptionId }
    private val exceptionsByMeeting = exceptions.groupBy { it.meetingId }
    private val sectionsByException = exceptionSections.groupBy { it.exceptionId }
    init {
        require(sources.all { listOf(it.courseId,it.meetingId,it.exceptionId).count { owner -> owner != null } == 1 }) { "来源归属数据无效" }
    }
    fun courseSources(id: Long) = decodeSources(courseSources[id].orEmpty())
    fun meetingSources(id: Long) = decodeSources(meetingSources[id].orEmpty())
    fun exceptions(id: Long): List<MeetingException> = exceptionsByMeeting[id].orEmpty().map { row ->
        require(row.hasSectionsOverride || sectionsByException[row.id].isNullOrEmpty()) { "例外节次数据不完整" }
        MeetingException(row.id, ExceptionKind.valueOf(row.kind), row.originalEpochDay?.let(LocalDate::ofEpochDay),
            row.targetEpochDay?.let(LocalDate::ofEpochDay), decodeTime(row.customStartMinute,row.customEndMinute),
            if(row.hasSectionsOverride) sectionsByException[row.id].orEmpty().map { it.section }.toSet() else null,
            row.teacher,row.room,row.experimentProject,row.group,decodeSources(exceptionSources[row.id].orEmpty()))
    }
    private fun decodeSources(rows: List<ScheduleProvenanceEntity>): List<ScheduleProvenance> = rows.mapIndexed { index,row ->
        require(row.position == index) { "来源顺序数据无效" }
        val coordinates = listOf(row.left,row.top,row.right,row.bottom)
        require(coordinates.all { it == null } || coordinates.all { it != null }) { "来源坐标数据不完整" }
        ScheduleProvenance(row.sourceId,row.adapterId,row.page,
            if(row.left == null) null else SourceBounds(row.left,requireNotNull(row.top),requireNotNull(row.right),requireNotNull(row.bottom)),row.rawText,row.sourceLocator)
    }
}

internal fun decodeTime(start: Int?, end: Int?): TimeRange? {
    require((start == null) == (end == null)) { "自定义时间数据不完整" }
    return start?.let { TimeRange(timeOfMinute(it),timeOfMinute(requireNotNull(end))) }
}
internal fun timeOfMinute(value: Int): LocalTime {
    require(value in 0..1439) { "时间必须在同一天内" }
    return LocalTime.of(value/60,value%60)
}
internal fun LocalTime.minuteOfDay() = hour*60+minute

internal fun List<ScheduleProvenance>.toEntities(courseId: Long? = null, meetingId: Long? = null, exceptionId: Long? = null) =
    mapIndexed { index,source -> ScheduleProvenanceEntity(courseId=courseId,meetingId=meetingId,exceptionId=exceptionId,
        position=index,sourceId=source.sourceId,adapterId=source.adapterId,page=source.page,
        left=source.bounds?.left,top=source.bounds?.top,right=source.bounds?.right,bottom=source.bounds?.bottom,rawText=source.rawText,sourceLocator=source.sourceLocator) }

internal suspend fun ScheduleDao.writeCalendarChildren(meetingId: Long, meeting: CourseMeeting, previous: CourseMeeting?): List<Long> {
    insertDates(meeting.dates.sorted().map { MeetingDateEntity(meetingId,it.toEpochDay()) })
    insertDeclaredWeeks(meeting.declaredWeeks.sorted().map { MeetingDeclaredWeekEntity(meetingId,it) })
    insertProvenance(meeting.provenance.toEntities(meetingId=meetingId))
    val owned = previous?.exceptions.orEmpty().map { it.id }.toSet()
    val reused = meeting.exceptions.map { it.id }.filter { it in owned }
    require(reused.distinct().size == reused.size) { "例外标识不能重复" }
    val ids = mutableListOf<Long>()
    meeting.exceptions.forEach { change ->
        val id = insertException(MeetingExceptionEntity(change.id.takeIf { it in owned } ?: 0,meetingId,change.kind.name,
            change.originalDate?.toEpochDay(),change.targetDate?.toEpochDay(),change.customTime?.start?.minuteOfDay(),
            change.customTime?.end?.minuteOfDay(),change.sections != null,change.teacher,change.room,change.experimentProject,change.group))
        ids += id
        insertExceptionSections(change.sections.orEmpty().sorted().map { ExceptionSectionEntity(id,it) })
        insertProvenance(change.provenance.toEntities(exceptionId=id))
    }
    return ids
}
