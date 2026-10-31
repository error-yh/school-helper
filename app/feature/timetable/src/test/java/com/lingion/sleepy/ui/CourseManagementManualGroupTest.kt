package com.lingion.sleepy.ui

import com.lingion.sleepy.data.CourseDisplayGroup
import com.lingion.sleepy.data.CourseGroupMember
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.defaultPeriods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CourseManagementManualGroupTest {
    private val scope = CourseGroupScope("local", 1L)
    private val periods = defaultPeriods()

    @Test
    fun sameNameWithoutIdentityStaysApartUntilTheUserConfirms() {
        val first = course(1, "Hadoop实验")
        val second = course(2, "Hadoop实验")

        val automatic = projectCourseManagement(listOf(first, second), periods, scope)
        assertEquals(2, automatic.groupCount)

        val manual = projectCourseManagement(
            listOf(first, second), periods, scope,
            listOf(effective("g1", "Hadoop 实验", first, second)),
        )
        assertEquals(1, manual.groupCount)
        assertEquals(2, manual.recordCount)
    }

    @Test
    fun confirmedGroupIsOneCardWithEveryArrangement() {
        val first = course(
            1, "Hadoop实验",
            meetings = listOf(meeting(1, day = 1), meeting(2, kind = MeetingKind.DATE_SPECIFIC, day = 0, dates = setOf(LocalDate.parse("2026-10-19")))),
        )
        val second = course(2, "Hadoop实验", meetings = listOf(meeting(3, day = 3)))

        val projection = projectCourseManagement(listOf(first, second), periods, scope, listOf(effective("g1", "Hadoop 实验", first, second)))

        val group = projection.groups.single()
        assertEquals("manual:g1", group.key)
        assertEquals("Hadoop 实验", group.displayName)
        assertEquals("g1", group.manualId)
        assertEquals(listOf(1L, 2L), group.courses.map { it.id })
        assertEquals(3, group.meetingCount)
        assertEquals(3, group.scheduledRows.size)
        assertEquals(0, group.unscheduledRows.size)
        assertEquals(listOf(1L, 3L, 2L), group.scheduledRows.map { it.meeting.id })
    }

    @Test
    fun unselectedRecordsKeepTheirAutomaticCards() {
        val first = course(1, "Hadoop实验")
        val second = course(2, "Hadoop实验")
        val third = course(3, "线性代数")

        val projection = projectCourseManagement(listOf(first, second, third), periods, scope, listOf(effective("g1", "Hadoop 实验", first, second)))

        assertEquals(2, projection.groupCount)
        val allIds = projection.groups.flatMap { group -> group.courses.map { it.id } }
        assertEquals(listOf(1L, 2L, 3L), allIds.distinct().sorted())
        assertEquals(3, allIds.size)
        assertEquals(3, projection.recordCount)
        assertEquals(projection.meetingCount, projection.groups.sumOf { it.meetingCount })
    }

    @Test
    fun manualMembersAreNeverRepeatedInAnAutomaticGroup() {
        // Same explicit code and class: the automatic rules would have merged all three.
        val first = course(1, "Hadoop实验", code = "BD101", classCode = "CS2101")
        val second = course(2, "Hadoop实验二", code = "BD101", classCode = "CS2101")
        val third = course(3, "Hadoop实验三", code = "BD101", classCode = "CS2101")

        val automatic = projectCourseManagement(listOf(first, second, third), periods, scope)
        assertEquals(1, automatic.groupCount)

        val manual = projectCourseManagement(listOf(first, second, third), periods, scope, listOf(effective("g1", "D1", first, second)))
        assertEquals(2, manual.groupCount)
        assertEquals(3, manual.groups.flatMap { it.courses }.map { it.id }.distinct().size)
        assertEquals(3, manual.recordCount)
        assertEquals(automatic.meetingCount, manual.meetingCount)
        assertEquals(3, manual.groups.sumOf { it.recordCount })
    }

    @Test
    fun degenerateGroupNeverHidesRecords() {
        val first = course(1, "Hadoop实验")
        val second = course(2, "线性代数")

        val projection = projectCourseManagement(listOf(first, second), periods, scope, listOf(effective("g1", "只剩下一条", first)))

        assertEquals(2, projection.groupCount)
        assertTrue(projection.groups.all { it.manualId == null })
    }

    @Test
    fun staleMembersFallBackToTheConservativeRules() {
        val first = course(1, "Hadoop实验")
        val second = course(2, "Hadoop实验")
        val stored = effective("g1", "Hadoop 实验", first, second)

        // The record with id 2 was replaced by an unrelated course after a clear/re-import.
        val replaced = course(2, "线性代数")
        val reconcile = reconcileManualGroups(
            listOf(
                CourseDisplayGroup(
                    "g1", "Hadoop 实验",
                    listOf(
                        CourseGroupMember(1, courseDisplaySignature(first)),
                        CourseGroupMember(2, courseDisplaySignature(second)),
                    ),
                ),
            ),
            listOf(first, replaced),
        )
        val projection = projectCourseManagement(listOf(first, replaced), periods, scope, reconcile.effective)

        assertEquals(2, projection.groupCount)
        assertTrue(projection.groups.all { it.manualId == null })
        assertEquals(0, projection.groups.count { it.manualId != null })
    }

    @Test
    fun mixedKindsInsideAGroupKeepScheduledAndUnscheduledRows() {
        val first = course(
            1, "Hadoop实验",
            meetings = listOf(
                meeting(1, day = 2),
                meeting(2, kind = MeetingKind.UNSCHEDULED, day = 0, weeks = emptySet(), sections = emptySet()),
            ),
        )
        val second = course(
            2, "Hadoop实验",
            meetings = listOf(meeting(3, kind = MeetingKind.DATE_SPECIFIC, day = 0, dates = setOf(LocalDate.parse("2026-10-19")))),
        )

        val group = projectCourseManagement(listOf(first, second), periods, scope, listOf(effective("g1", "Hadoop 实验", first, second))).groups.single()

        assertEquals(1, group.unscheduledRows.size)
        assertEquals(2, group.scheduledRows.size)
        assertEquals(3, group.meetingCount)
        assertEquals("未排定", group.unscheduledRows.single().kindLabel)
    }

    @Test
    fun totalsAndKeysAreStableAcrossCalls() {
        val first = course(1, "Hadoop实验")
        val second = course(2, "Hadoop实验")
        val third = course(3, "体育", meetings = listOf(meeting(4, day = 5)))

        val automatic = projectCourseManagement(listOf(first, second, third), periods, scope)
        val a = projectCourseManagement(listOf(first, second, third), periods, scope, listOf(effective("g1", "Hadoop 实验", first, second)))
        val b = projectCourseManagement(listOf(first, second, third), periods, scope, listOf(effective("g1", "Hadoop 实验", first, second)))

        assertEquals(automatic.recordCount, a.recordCount)
        assertEquals(automatic.meetingCount, a.meetingCount)
        assertEquals(a.groups.map { it.key }, b.groups.map { it.key })
        assertEquals(2, a.groups.size) // one manual card plus the remaining automatic card
        assertTrue(a.groups.first().manualId != null)
    }

    private fun effective(id: String, name: String, vararg courses: ScheduledCourse) =
        EffectiveManualGroup(id, name, courses.toList())

    private fun course(
        id: Long,
        name: String,
        code: String? = null,
        classCode: String? = null,
        meetings: List<CourseMeeting> = listOf(meeting()),
    ) = ScheduledCourse(
        id = id,
        courseName = name,
        teacher = "",
        room = "",
        color = 0xFF6750A4,
        meetings = meetings,
        sourceCourseCode = code,
        sourceClassCode = classCode,
    )

    private fun meeting(
        id: Long = 0,
        day: Int = 1,
        weeks: Set<Int> = setOf(1, 2),
        sections: Set<Int> = setOf(1, 2),
        kind: MeetingKind = MeetingKind.RECURRING,
        dates: Set<LocalDate> = emptySet(),
    ) = CourseMeeting(
        id = id,
        day = day,
        weeks = if (kind == MeetingKind.RECURRING) weeks else emptySet(),
        sections = sections,
        kind = kind,
        dates = dates,
    )
}