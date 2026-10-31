package com.lingion.sleepy.ui

import com.lingion.sleepy.data.CourseDisplayGroup
import com.lingion.sleepy.data.CourseDisplayGroupKey
import com.lingion.sleepy.data.CourseGroupMember
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.defaultPeriods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CourseDisplayGroupsTest {
    @Test
    fun signatureFollowsIdentityAndIgnoresScheduleEdits() {
        val base = course(1, "Hadoop实验", code = "BD101", classCode = "CS2101")
        assertEquals(courseDisplaySignature(base), courseDisplaySignature(base.copy(teacher = "老师", room = "教室")))
        assertEquals(courseDisplaySignature(base), courseDisplaySignature(base.copy(meetings = listOf(meeting(day = 5, sections = setOf(5, 6))))))
        assertNotEquals(courseDisplaySignature(base), courseDisplaySignature(base.copy(courseName = "Hadoop 实验（二）")))
        assertNotEquals(courseDisplaySignature(base), courseDisplaySignature(base.copy(sourceCourseCode = "BD102")))
        assertNotEquals(courseDisplaySignature(base), courseDisplaySignature(base.copy(sourceClassCode = "CS2102")))
    }

    @Test
    fun reconcileKeepsValidGroupsAndCountsInvalidMembers() {
        val first = course(1, "Hadoop实验", code = "BD101", classCode = "CS2101")
        val second = course(2, "Hadoop实验", code = "BD101", classCode = "CS2101")
        val third = course(3, "Hadoop实验", code = "BD101", classCode = "CS2101")
        val stored = listOf(group("g1", "Hadoop 实验", listOf(memberOf(first), memberOf(second), memberOf(third))))
        // The third record was deleted from the snapshot.
        val result = reconcileManualGroups(stored, listOf(first, second))

        assertEquals(1, result.effective.size)
        assertEquals(setOf(1L, 2L), result.effective.single().courses.map { it.id }.toSet())
        assertEquals(1, result.invalidMemberCount)
        assertEquals(0, result.staleGroupCount)
    }

    @Test
    fun reconcileInvalidatesMembersWhoseRecordWasReplaced() {
        val original = course(1, "Hadoop实验", code = "BD101", classCode = "CS2101")
        val replaced = course(1, "线性代数", code = "MA101", classCode = "CS2101")
        val stored = listOf(group("g1", "Hadoop 实验", listOf(memberOf(original), memberOf(course(2, "Hadoop实验", code = "BD101", classCode = "CS2101")))))

        val result = reconcileManualGroups(stored, listOf(replaced, course(2, "Hadoop实验", code = "BD101", classCode = "CS2101")))

        // One valid member left: the group is not applied, its records fall back to automatic rules.
        assertTrue(result.effective.isEmpty())
        assertEquals(1, result.invalidMemberCount)
        assertEquals(1, result.staleGroupCount)
    }

    @Test
    fun emptyOrFailedSnapshotNeverDeletesStoredGroups() {
        val stored = listOf(group("g1", "Hadoop 实验", listOf(memberOf(course(1, "A")), memberOf(course(2, "A")))))
        val result = reconcileManualGroups(stored, emptyList())

        assertTrue(result.effective.isEmpty())
        assertEquals(2, result.invalidMemberCount)
        assertEquals(1, result.staleGroupCount)
        // The stored list itself is untouched: only the projection is empty.
        assertEquals(2, stored.single().members.size)
    }

    @Test
    fun conflictsAreDetectedAcrossGroupsButNotForTheEditedGroup() {
        val groups = listOf(
            group("g1", "组一", listOf(CourseGroupMember(1, "a"), CourseGroupMember(2, "a"))),
            group("g2", "组二", listOf(CourseGroupMember(3, "b"), CourseGroupMember(4, "b"))),
        )
        val conflicts = findMemberConflicts(groups, listOf(2, 3, 5), editingGroupId = null)
        assertEquals(listOf(2L to "g1", 3L to "g2"), conflicts.map { it.courseId to it.groupId })

        assertEquals(emptyList<GroupMemberConflict>(), findMemberConflicts(groups, listOf(1, 2), editingGroupId = "g1"))
    }

    @Test
    fun upsertCreatesAndUpdatesOneGroup() {
        val created = upsertManualGroup(
            groups = emptyList(),
            editingGroupId = null,
            groupId = "g1",
            name = "  数据结构实验  ",
            members = listOf(CourseGroupMember(1, "s1"), CourseGroupMember(2, "s2")),
            allowMove = false,
        )
        val saved = created as GroupSaveResult.Saved
        assertEquals("数据结构实验", saved.groups.single().name)
        assertEquals(listOf(1L, 2L), saved.groups.single().members.map { it.courseId })

        val updated = upsertManualGroup(
            groups = saved.groups,
            editingGroupId = "g1",
            groupId = "g1",
            name = "数据结构",
            members = listOf(CourseGroupMember(1, "s1"), CourseGroupMember(2, "s2"), CourseGroupMember(3, "s3")),
            allowMove = false,
        ) as GroupSaveResult.Saved
        assertEquals(1, updated.groups.size)
        assertEquals("数据结构", updated.groups.single().name)
        assertEquals(3, updated.groups.single().members.size)
    }

    @Test
    fun upsertRejectsOverlapUntilTheUserExplicitlyMovesTheMember() {
        val groups = listOf(
            group("g1", "组一", listOf(CourseGroupMember(1, "a"), CourseGroupMember(2, "a"))),
        )
        val rejected = upsertManualGroup(groups, null, "g2", "组二", listOf(CourseGroupMember(2, "a"), CourseGroupMember(3, "b")), allowMove = false)
        val conflicts = (rejected as GroupSaveResult.Rejected).conflicts
        assertEquals(listOf(2L to "g1"), conflicts.map { it.courseId to it.groupId })
        assertEquals(listOf("组一"), conflicts.map { it.groupName })

        val moved = upsertManualGroup(groups, null, "g2", "组二", listOf(CourseGroupMember(2, "a"), CourseGroupMember(3, "b")), allowMove = true) as GroupSaveResult.Saved
        // The donor group drops below two members and disappears; the record returns to automatic rules.
        assertEquals(listOf("组二"), moved.groups.map { it.name })
        assertEquals(listOf(2L, 3L), moved.groups.single().members.map { it.courseId })
    }

    @Test
    fun upsertDropsDonorMembersAndRefusesDegenerateGroups() {
        val groups = listOf(
            group("g1", "组一", listOf(CourseGroupMember(1, "a"), CourseGroupMember(2, "a"))),
            group("g2", "组二", listOf(CourseGroupMember(3, "b"), CourseGroupMember(4, "b"))),
        )
        val moved = upsertManualGroup(groups, null, "g3", "组三", listOf(CourseGroupMember(3, "b"), CourseGroupMember(5, "c")), allowMove = true) as GroupSaveResult.Saved
        assertEquals(setOf("组一", "组三"), moved.groups.map { it.name }.toSet())

        assertEquals(GroupSaveResult.Invalid, upsertManualGroup(groups, null, "g4", "单条", listOf(CourseGroupMember(1, "a")), allowMove = false))
        assertEquals(
            GroupSaveResult.Invalid,
            upsertManualGroup(groups, null, "g4", "重复", listOf(CourseGroupMember(1, "a"), CourseGroupMember(1, "a")), allowMove = true),
        )
        assertEquals(
            GroupSaveResult.Invalid,
            upsertManualGroup(groups, null, "g4", "   ", listOf(CourseGroupMember(1, "a"), CourseGroupMember(2, "a")), allowMove = true),
        )
    }

    @Test
    fun removingAGroupOnlyDropsTheRelation() {
        val groups = listOf(
            group("g1", "组一", listOf(CourseGroupMember(1, "a"), CourseGroupMember(2, "a"))),
            group("g2", "组二", listOf(CourseGroupMember(3, "b"), CourseGroupMember(4, "b"))),
        )
        assertEquals(listOf("组二"), removeManualGroup(groups, "g1").map { it.name })
        assertEquals(groups.size, groups.size) // the input list is never mutated
    }

    @Test
    fun removingAMemberDropsTheGroupBelowTwoMembers() {
        val three = listOf(group("g1", "组一", listOf(CourseGroupMember(1, "a"), CourseGroupMember(2, "a"), CourseGroupMember(3, "a"))))
        val kept = removeManualMember(three, "g1", 3)
        assertEquals(listOf(1L, 2L), kept.single().members.map { it.courseId })

        val two = listOf(group("g2", "组二", listOf(CourseGroupMember(1, "a"), CourseGroupMember(2, "a"))))
        assertTrue(removeManualMember(two, "g2", 1).isEmpty())
    }

    @Test
    fun pruningDropsStaleBindingsOnlyFromANonEmptySnapshot() {
        val first = course(1, "Hadoop实验")
        val second = course(2, "Hadoop实验")
        val third = course(3, "Hadoop实验")
        val stored = listOf(group("g1", "Hadoop 实验", listOf(memberOf(first), memberOf(second), memberOf(third))))

        assertNull(pruneInvalidMembers(stored, listOf(first, second, third)))
        // An empty snapshot is never evidence of deletion: the stored relation is kept as is.
        assertNull(pruneInvalidMembers(stored, emptyList()))

        val pruned = pruneInvalidMembers(stored, listOf(first, second))
        assertEquals(listOf(1L, 2L), pruned!!.single().members.map { it.courseId })

        // The record with id 3 now belongs to an unrelated course, so the old binding is dropped.
        val replaced = pruneInvalidMembers(stored, listOf(first, second, course(3, "线性代数")))
        assertEquals(listOf(1L, 2L), replaced!!.single().members.map { it.courseId })

        // A group left with a single valid member is removed, so its record returns to the automatic rules.
        assertTrue(pruneInvalidMembers(stored, listOf(first))!!.isEmpty())
    }

    @Test
    fun recordSummaryMentionsTheScheduleWithoutClaimingOccurrences() {
        val recurring = course(1, "A", meetings = listOf(meeting(day = 1, weeks = setOf(1, 2), sections = setOf(1, 2))))
        val summary = courseRecordSummary(recurring, defaultPeriods())
        assertTrue(summary.contains("周期安排"))
        assertTrue(summary.contains("周一"))

        val dated = course(
            2, "B",
            meetings = listOf(meeting(kind = MeetingKind.DATE_SPECIFIC, day = 0, weeks = emptySet(), dates = setOf(LocalDate.parse("2026-10-19")))),
        )
        assertTrue(courseRecordSummary(dated, defaultPeriods()).contains("指定日期"))
    }

    @Test
    fun manualViewIsBoundToTheLoadedScopeAndSnapshot() {
        val first = course(1, "Hadoop实验")
        val second = course(2, "Hadoop实验")
        val groups = listOf(group("g1", "组", listOf(memberOf(first), memberOf(second))))
        val courses = listOf(first, second)
        val keyA = CourseDisplayGroupKey("local", 1)
        val keyB = CourseDisplayGroupKey("local", 2)

        assertNull(manualGroupView(true, keyA, 3, groups, keyB, courses, 7, unavailable = false))
        assertNull(manualGroupView(false, null, 3, groups, keyA, courses, 7, unavailable = false))
        assertNull(manualGroupView(true, keyA, 3, groups, keyA, courses, 7, unavailable = true))

        val view = manualGroupView(true, keyA, 3, groups, keyA, courses, 7, unavailable = false)!!
        assertEquals(GroupWriteScope(keyA, 3), view.scope)
        assertEquals(7, view.snapshotRevision)
        assertEquals(1, view.reconcile.effective.size)
    }

    @Test
    fun pruneTrackerAllowsOneAttemptPerScopeGenerationAndRevision() {
        val first = course(1, "Hadoop实验")
        val second = course(2, "Hadoop实验")
        val third = course(3, "Hadoop实验")
        val groups = listOf(group("g1", "组", listOf(memberOf(first), memberOf(second), memberOf(third))))
        val keyA = CourseDisplayGroupKey("local", 1)
        val view = manualGroupView(true, keyA, 4, groups, keyA, listOf(first, second), 9, unavailable = false)!!

        val tracker = PruneAttemptTracker()
        assertTrue(tracker.begin(view))
        // A failed write changes nothing the tracker keys on, so the same input is not retried.
        assertFalse(tracker.begin(view))
        assertTrue(tracker.begin(view.copy(snapshotRevision = 10)))
        assertTrue(tracker.begin(view.copy(scope = view.scope.copy(generation = 5))))
    }

    @Test
    fun pruneTrackerIgnoresViewsWithoutInvalidMembers() {
        val first = course(1, "Hadoop实验")
        val second = course(2, "Hadoop实验")
        val groups = listOf(group("g1", "组", listOf(memberOf(first), memberOf(second))))
        val keyA = CourseDisplayGroupKey("local", 1)
        val view = manualGroupView(true, keyA, 1, groups, keyA, listOf(first, second), 1, unavailable = false)!!

        assertFalse(PruneAttemptTracker().begin(view))
    }

    @Test
    fun longLegitimateIdentityIsDigestedWithoutTruncatingAnyField() {
        val longName = "课程".repeat(2000)
        val longCode = "X".repeat(2048)
        val longClass = "Y".repeat(2048)
        val original = course(1, longName, code = longCode, classCode = longClass)
        val signature = courseDisplaySignature(original)

        assertTrue(signature.startsWith("d1:"))
        assertTrue(signature.length <= 400)
        // A different field value must not collapse to the same bounded signature.
        assertNotEquals(signature, courseDisplaySignature(original.copy(sourceCourseCode = longCode + "Z")))
        assertNotEquals(signature, courseDisplaySignature(original.copy(courseName = longName + "别")))
    }

    @Test
    fun shortIdentityKeepsThePlainSignatureSoStoredValuesStayValid() {
        val course = course(1, "Hadoop实验", code = "BD101", classCode = "CS2101")
        assertEquals("hadoop实验|bd101|cs2101", courseDisplaySignature(course))
    }

    @Test
    fun upsertRejectsMoreThanTheMemberLimitWithoutThrowing() {
        val hundred = (1L..100L).map { CourseGroupMember(it, "s$it") }
        assertTrue(upsertManualGroup(emptyList(), null, "g1", "组", hundred, allowMove = true) is GroupSaveResult.Saved)

        val overLimit = hundred + CourseGroupMember(101, "s101")
        assertEquals(
            GroupSaveResult.Invalid,
            upsertManualGroup(emptyList(), null, "g2", "组", overLimit, allowMove = true),
        )
    }

    private fun group(id: String, name: String, members: List<CourseGroupMember>) = CourseDisplayGroup(id, name, members)

    private fun memberOf(course: ScheduledCourse) = CourseGroupMember(course.id, courseDisplaySignature(course))

    private fun course(
        id: Long,
        name: String,
        code: String? = null,
        classCode: String? = null,
        teacher: String = "",
        room: String = "",
        meetings: List<CourseMeeting> = listOf(meeting()),
    ) = ScheduledCourse(
        id = id,
        courseName = name,
        teacher = teacher,
        room = room,
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
        weeks = weeks,
        sections = sections,
        kind = kind,
        dates = dates,
    )
}