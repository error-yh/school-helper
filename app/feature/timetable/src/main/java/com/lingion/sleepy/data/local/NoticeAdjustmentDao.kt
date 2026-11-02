/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface NoticeAdjustmentDao {
    @Insert
    suspend fun insert(event: NoticeAdjustmentEventEntity): Long

    @Query("SELECT * FROM notice_adjustment_events WHERE scopeToken=:scopeToken AND semesterId=:semesterId AND activeKey=:activeKey LIMIT 1")
    suspend fun active(scopeToken: String, semesterId: Long, activeKey: String): NoticeAdjustmentEventEntity?

    @Query("SELECT * FROM notice_adjustment_events WHERE scopeToken=:scopeToken AND semesterId=:semesterId AND id=:id")
    suspend fun event(scopeToken: String, semesterId: Long, id: Long): NoticeAdjustmentEventEntity?

    @Query("SELECT * FROM notice_adjustment_events WHERE id=:id")
    suspend fun eventById(id: Long): NoticeAdjustmentEventEntity?

    @Query(
        "SELECT * FROM notice_adjustment_events WHERE scopeToken=:scopeToken AND semesterId=:semesterId " +
            "AND (:beforeId IS NULL OR id < :beforeId) ORDER BY id DESC LIMIT :limit"
    )
    suspend fun history(scopeToken: String, semesterId: Long, beforeId: Long?, limit: Int): List<NoticeAdjustmentEventEntity>

    @Query(
        "SELECT COUNT(*) FROM notice_adjustment_events WHERE scopeToken=:scopeToken AND semesterId=:semesterId " +
            "AND state='APPLIED' AND courseId=:courseId AND logicalBaseId=:logicalBaseId " +
            "AND structureFingerprint=:structureFingerprint AND originalEpochDay=:originalEpochDay AND id > :afterId"
    )
    suspend fun laterActiveOnLogicalOccurrence(
        scopeToken: String, semesterId: Long, courseId: Long?,
        logicalBaseId: String, structureFingerprint: String, originalEpochDay: Long, afterId: Long,
    ): Int

    @Query(
        "UPDATE notice_adjustment_events SET state='UNDONE', undoneRevision=:revision, activeKey=NULL " +
            "WHERE id=:id AND state='APPLIED'"
    )
    suspend fun markUndone(id: Long, revision: Long): Int

    @Query(
        "SELECT * FROM meeting_exceptions WHERE id=:id AND meetingId IN " +
            "(SELECT m.id FROM course_meetings m JOIN scheduled_courses c ON m.courseId=c.id WHERE c.semesterId=:semesterId)"
    )
    suspend fun exception(id: Long, semesterId: Long): MeetingExceptionEntity?

    @Query("SELECT * FROM exception_sections WHERE exceptionId=:exceptionId ORDER BY section")
    suspend fun exceptionSections(exceptionId: Long): List<ExceptionSectionEntity>

    @Query("SELECT * FROM schedule_provenance WHERE exceptionId=:exceptionId ORDER BY position,id")
    suspend fun exceptionProvenance(exceptionId: Long): List<ScheduleProvenanceEntity>

    @Query(
        "DELETE FROM meeting_exceptions WHERE id=:id AND meetingId IN " +
            "(SELECT m.id FROM course_meetings m JOIN scheduled_courses c ON m.courseId=c.id WHERE c.semesterId=:semesterId)"
    )
    suspend fun deleteException(id: Long, semesterId: Long): Int

    @Query("SELECT * FROM course_meetings WHERE id=:id AND courseId IN (SELECT id FROM scheduled_courses WHERE semesterId=:semesterId)")
    suspend fun meeting(id: Long, semesterId: Long): CourseMeetingEntity?
}