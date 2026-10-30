package com.lingion.sleepy.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT d.* FROM meeting_dates d JOIN course_meetings m ON d.meetingId=m.id JOIN scheduled_courses c ON m.courseId=c.id WHERE c.semesterId=:semesterId ORDER BY d.meetingId,d.epochDay")
    suspend fun dates(semesterId: Long = 1): List<MeetingDateEntity>
    @Query("SELECT d.* FROM meeting_declared_weeks d JOIN course_meetings m ON d.meetingId=m.id JOIN scheduled_courses c ON m.courseId=c.id WHERE c.semesterId=:semesterId ORDER BY d.meetingId,d.week")
    suspend fun declaredWeeks(semesterId: Long = 1): List<MeetingDeclaredWeekEntity>
    @Query("SELECT e.* FROM meeting_exceptions e JOIN course_meetings m ON e.meetingId=m.id JOIN scheduled_courses c ON m.courseId=c.id WHERE c.semesterId=:semesterId ORDER BY e.id")
    suspend fun exceptions(semesterId: Long = 1): List<MeetingExceptionEntity>
    @Query("SELECT s.* FROM exception_sections s JOIN meeting_exceptions e ON s.exceptionId=e.id JOIN course_meetings m ON e.meetingId=m.id JOIN scheduled_courses c ON m.courseId=c.id WHERE c.semesterId=:semesterId ORDER BY s.exceptionId,s.section")
    suspend fun exceptionSections(semesterId: Long = 1): List<ExceptionSectionEntity>
    @Query("SELECT p.* FROM schedule_provenance p WHERE p.courseId IN (SELECT id FROM scheduled_courses WHERE semesterId=:semesterId) OR p.meetingId IN (SELECT m.id FROM course_meetings m JOIN scheduled_courses c ON m.courseId=c.id WHERE c.semesterId=:semesterId) OR p.exceptionId IN (SELECT e.id FROM meeting_exceptions e JOIN course_meetings m ON e.meetingId=m.id JOIN scheduled_courses c ON m.courseId=c.id WHERE c.semesterId=:semesterId) ORDER BY p.position,p.id")
    suspend fun provenance(semesterId: Long = 1): List<ScheduleProvenanceEntity>
    @Query("SELECT s.* FROM import_normalization_state s JOIN scheduled_courses c ON s.courseId=c.id WHERE c.semesterId=:semesterId ORDER BY s.courseId")
    suspend fun importStates(semesterId: Long): List<ImportNormalizationStateEntity>
    @Query("DELETE FROM import_normalization_state WHERE courseId=:courseId AND courseId IN (SELECT id FROM scheduled_courses WHERE semesterId=:semesterId)")
    suspend fun deleteImportState(courseId: Long, semesterId: Long = 1): Int
    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun putImportState(state: ImportNormalizationStateEntity)
    @Insert suspend fun insertDates(rows: List<MeetingDateEntity>)
    @Insert suspend fun insertDeclaredWeeks(rows: List<MeetingDeclaredWeekEntity>)
    @Insert suspend fun insertException(row: MeetingExceptionEntity): Long
    @Insert suspend fun insertExceptionSections(rows: List<ExceptionSectionEntity>)
    @Insert suspend fun insertProvenance(rows: List<ScheduleProvenanceEntity>)
    @Query("DELETE FROM schedule_provenance WHERE courseId=:id AND courseId IN (SELECT id FROM scheduled_courses WHERE semesterId=:semesterId)") suspend fun deleteCourseProvenance(id: Long, semesterId: Long = 1)
    @Query("SELECT revision FROM semesters WHERE id = :semesterId")
    fun observeRevision(semesterId: Long = 1): Flow<Long?>

    @Query("SELECT * FROM semesters WHERE id = :semesterId")
    suspend fun semester(semesterId: Long = 1): SemesterEntity?

    @Query("SELECT * FROM periods WHERE semesterId = :semesterId ORDER BY number")
    suspend fun periods(semesterId: Long = 1): List<PeriodEntity>

    @Query("SELECT * FROM scheduled_courses WHERE semesterId = :semesterId ORDER BY id")
    suspend fun courses(semesterId: Long = 1): List<ScheduledCourseEntity>

    @Query("SELECT * FROM scheduled_courses WHERE semesterId = :semesterId AND id = :id")
    suspend fun course(id: Long, semesterId: Long = 1): ScheduledCourseEntity?

    @Query("SELECT m.* FROM course_meetings m INNER JOIN scheduled_courses c ON m.courseId = c.id WHERE c.semesterId = :semesterId ORDER BY m.id")
    suspend fun meetings(semesterId: Long = 1): List<CourseMeetingEntity>

    @Query("SELECT w.* FROM meeting_weeks w INNER JOIN course_meetings m ON w.meetingId = m.id INNER JOIN scheduled_courses c ON m.courseId = c.id WHERE c.semesterId = :semesterId ORDER BY w.meetingId,w.week")
    suspend fun weeks(semesterId: Long = 1): List<MeetingWeekEntity>

    @Query("SELECT s.* FROM meeting_sections s INNER JOIN course_meetings m ON s.meetingId = m.id INNER JOIN scheduled_courses c ON m.courseId = c.id WHERE c.semesterId = :semesterId ORDER BY s.meetingId,s.section")
    suspend fun sections(semesterId: Long = 1): List<MeetingSectionEntity>

    @Insert
    suspend fun insertSemester(semester: SemesterEntity)

    @Update
    suspend fun updateSemester(semester: SemesterEntity): Int

    @Insert
    suspend fun insertPeriods(periods: List<PeriodEntity>)

    @Query("DELETE FROM periods WHERE semesterId = :semesterId")
    suspend fun deletePeriods(semesterId: Long = 1)

    @Insert
    suspend fun insertCourse(course: ScheduledCourseEntity): Long

    @Update
    suspend fun updateCourse(course: ScheduledCourseEntity): Int

    @Query("DELETE FROM scheduled_courses WHERE semesterId = :semesterId AND id = :id")
    suspend fun deleteCourse(id: Long, semesterId: Long = 1): Int

    @Query("DELETE FROM course_meetings WHERE courseId = :courseId AND courseId IN (SELECT id FROM scheduled_courses WHERE semesterId = :semesterId)")
    suspend fun deleteMeetings(courseId: Long, semesterId: Long = 1)

    @Insert
    suspend fun insertMeeting(meeting: CourseMeetingEntity): Long

    @Insert
    suspend fun insertWeeks(weeks: List<MeetingWeekEntity>)

    @Insert
    suspend fun insertSections(sections: List<MeetingSectionEntity>)
}
