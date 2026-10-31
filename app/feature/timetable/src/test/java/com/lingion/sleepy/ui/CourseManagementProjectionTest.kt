package com.lingion.sleepy.ui

import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.ExceptionKind
import com.lingion.sleepy.domain.model.MeetingException
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.ScheduleProvenance
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.defaultPeriods
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * Focused tests for the read-only course display grouping. They cover identity safety, ordering and
 * the count/invariant contract; they intentionally do not mirror private helper details.
 */
class CourseManagementProjectionTest {
    private val scope = CourseGroupScope("local", 1L)
    private val firstDate = LocalDate.parse("2026-10-19")

    @Test
    fun sameCourseIdKeepsEveryMeetingInOneGroup() {
        val target = course(
            1,
            "高等数学",
            meetings = listOf(
                meeting(id = 11, day = 1, sections = setOf(1, 2), weeks = setOf(1, 2)),
                meeting(id = 12, kind = MeetingKind.DATE_SPECIFIC, day = 0, weeks = emptySet(), sections = setOf(3), dates = setOf(firstDate)),
                meeting(id = 13, kind = MeetingKind.UNSCHEDULED, day = 0, weeks = emptySet(), sections = emptySet()),
            ),
        )
        val projection = project(listOf(target))

        assertEquals(1, projection.groupCount)
        assertEquals(1, projection.recordCount)
        assertEquals(3, projection.meetingCount)
        val group = projection.groups.single()
        assertSame(target, group.courses.single())
        assertEquals(listOf(11L, 12L), group.scheduledRows.map { it.meeting.id })
        assertEquals(listOf(13L), group.unscheduledRows.map { it.meeting.id })
        assertEquals(listOf(11L, 12L, 13L), group.rows.map { it.meeting.id })
    }

    @Test
    fun explicitCodeAndClassGroupRecordsAcrossDifferentIds() {
        val first = course(
            1, "Hadoop实验", code = "BD101", classCode = "CS2101",
            meetings = listOf(meeting(id = 1)),
        )
        val second = course(
            2, "Hadoop实验（二）", code = " bd101 ", classCode = "cs2101",
            meetings = listOf(meeting(id = 2), meeting(id = 3, day = 3)),
        )
        val projection = project(listOf(first, second))

        assertEquals(1, projection.groupCount)
        assertEquals(2, projection.recordCount)
        assertEquals(3, projection.meetingCount)
        val group = projection.groups.single()
        assertEquals(setOf(1L, 2L), group.courses.map { it.id }.toSet())
        assertEquals(3, group.scheduledRows.size)
        // The card title stays the first record's own name; records are not merged into a fake course.
        assertEquals("Hadoop实验", group.displayName)
    }

    @Test
    fun explicitCodeWithDifferentClassStaysApart() {
        val projection = project(
            listOf(
                course(1, "Hadoop实验", code = "BD101", classCode = "CS2101"),
                course(2, "Hadoop实验", code = "BD101", classCode = "CS2102"),
            ),
        )

        assertEquals(2, projection.groupCount)
        assertEquals(2, projection.recordCount)
        assertEquals(listOf(1L, 2L), projection.groups.map { it.courses.single().id })
    }

    @Test
    fun sameNameWithDifferentCodesStaysApart() {
        val projection = project(
            listOf(
                course(1, "大学英语", code = "EN101", classCode = "CS2101"),
                course(2, "大学英语", code = "EN102", classCode = "CS2101"),
            ),
        )

        assertEquals(2, projection.groupCount)
        assertTrue(projection.hasConservativeSplit)
    }

    @Test
    fun missingCodeNeverGroupsBySameName() {
        val projection = project(
            listOf(
                course(1, "数据结构实验", meetings = listOf(meeting(id = 1))),
                course(2, "数据结构实验", meetings = listOf(meeting(id = 2))),
            ),
        )

        assertEquals(2, projection.groupCount)
        assertEquals(2, projection.recordCount)
        assertEquals(2, projection.meetingCount)
        assertEquals(2, projection.unattributedRecordCount)
        assertTrue(projection.hasConservativeSplit)
        assertNotEquals(projection.groups[0].key, projection.groups[1].key)
    }

    @Test
    fun blankOrWhitespaceCodeIsNotIdentityEvidence() {
        val projection = project(
            listOf(
                course(1, "操作系统", code = "", classCode = "CS2101"),
                course(2, "操作系统", code = "   ", classCode = "CS2101"),
            ),
        )

        assertEquals(2, projection.groupCount)
        assertEquals(2, projection.unattributedRecordCount)
    }

    @Test
    fun classCodeOnOneRecordOnlyIsNotAWildcard() {
        val projection = project(
            listOf(
                course(1, "计算机网络", code = "CN201", classCode = "CS2101"),
                course(2, "计算机网络", code = "CN201", classCode = null),
            ),
        )

        assertEquals(2, projection.groupCount)
        // The record without a class code has no confirmable identity at all.
        assertEquals(1, projection.unattributedRecordCount)
    }

    @Test
    fun sharedDocumentContextGroupsRecordsWithoutClassCode() {
        val document = ScheduleProvenance(sourceId = "doc-a", adapterId = "pdf", rawText = "excerpt")
        val sameDocument = project(
            listOf(
                course(1, "软件工程", code = "SE201", provenance = listOf(document), meetings = listOf(meeting(id = 1))),
                course(2, "软件工程实验", code = "SE201", provenance = listOf(document), meetings = listOf(meeting(id = 2))),
            ),
        )
        assertEquals(1, sameDocument.groupCount)
        assertEquals(2, sameDocument.meetingCount)

        val otherDocument = project(
            listOf(
                course(1, "软件工程", code = "SE201", provenance = listOf(document)),
                course(2, "软件工程", code = "SE201", provenance = listOf(document.copy(sourceId = "doc-b"))),
            ),
        )
        assertEquals(2, otherDocument.groupCount)

        val genericOnly = project(
            listOf(
                course(1, "软件工程", code = "SE201", provenance = listOf(document.copy(sourceId = "document-metadata"))),
                course(2, "软件工程", code = "SE201", provenance = listOf(document.copy(sourceId = "document-metadata"))),
            ),
        )
        assertEquals(2, genericOnly.groupCount)
    }

    @Test
    fun scopeAndSemesterArePartOfEveryGroupKey() {
        val records = listOf(course(1, "Hadoop实验", code = "BD101", classCode = "CS2101"))
        val local = project(records, CourseGroupScope("local", 1))
        val otherProfile = project(records, CourseGroupScope("work-profile", 1))
        val otherSemester = project(records, CourseGroupScope("local", 2))

        assertTrue(local.groups.single().key.contains("s=local"))
        assertTrue(local.groups.single().key.contains("t=1"))
        assertNotEquals(local.groups.single().key, otherProfile.groups.single().key)
        assertNotEquals(local.groups.single().key, otherSemester.groups.single().key)
        assertEquals(local.groups.map { it.key }, project(records).groups.map { it.key })
    }

    @Test
    fun meetingOverridesWinAndCourseDefaultsFillTheRest() {
        val target = course(
            1, "编译原理", teacher = "默认教师", room = "默认教室",
            meetings = listOf(
                meeting(id = 1, teacher = "机房教师", room = "机房B", project = "词法分析", group = "第1组"),
                meeting(id = 2, day = 3, teacher = "  ", room = null),
            ),
        )
        val rows = project(listOf(target)).groups.single().scheduledRows

        assertEquals("机房教师", rows[0].teacherText)
        assertEquals("机房B", rows[0].roomText)
        assertEquals("词法分析", rows[0].projectText)
        assertEquals("第1组", rows[0].groupText)
        // A blank override falls back to the record default instead of hiding it.
        assertEquals("默认教师", rows[1].teacherText)
        assertEquals("默认教室", rows[1].roomText)
        assertNull(rows[1].projectText)
        assertEquals(2, target.meetings.size)
    }

    @Test
    fun customTimeIsNotReplacedByPeriodTimes() {
        val target = course(
            1, "午间答疑",
            meetings = listOf(
                meeting(
                    id = 1, day = 2, sections = setOf(3),
                    customTime = TimeRange(LocalTime.of(12, 0), LocalTime.of(12, 45)),
                ),
            ),
        )
        val row = project(listOf(target)).groups.single().scheduledRows.single()

        assertEquals("自定义时间", row.sectionsText)
        assertEquals("12:00–12:45", row.timeText)
        assertNull(row.missingSectionsText)
    }

    @Test
    fun nonConsecutiveSectionsKeepEveryInterval() {
        val target = course(1, "体育", meetings = listOf(meeting(id = 1, day = 5, sections = setOf(1, 3))))
        val row = project(listOf(target)).groups.single().scheduledRows.single()

        assertEquals("第 1,3 节", row.sectionsText)
        assertEquals("08:00–08:45、10:00–10:45", row.timeText)
        assertNull(row.missingSectionsText)
    }

    @Test
    fun mixedKindsFollowTheDocumentedOrder() {
        val target = course(
            1, "数据库",
            meetings = listOf(
                meeting(id = 21, kind = MeetingKind.DATE_SPECIFIC, day = 0, weeks = emptySet(), dates = setOf(firstDate.plusWeeks(2))),
                meeting(id = 22, day = 4, sections = setOf(3, 4), weeks = setOf(3, 4)),
                meeting(id = 23, day = 2, sections = setOf(5, 6), weeks = setOf(1, 2)),
                meeting(id = 24, kind = MeetingKind.UNSCHEDULED, day = 0, weeks = emptySet(), sections = emptySet()),
                meeting(id = 25, kind = MeetingKind.DATE_SPECIFIC, day = 0, weeks = emptySet(), dates = setOf(firstDate)),
            ),
        )
        val group = project(listOf(target)).groups.single()

        // Recurring rows come first (by weekday), then date-specific rows (by earliest date),
        // and the unscheduled row is listed separately instead of being mixed in.
        assertEquals(listOf(23L, 22L, 25L, 21L), group.scheduledRows.map { it.meeting.id })
        assertEquals(listOf(24L), group.unscheduledRows.map { it.meeting.id })
        assertEquals("待排期 · 尚不产生上课记录", group.unscheduledRows.single().scheduleText)
    }

    @Test
    fun multiDateMeetingStaysOneRowWithEveryDate() {
        val dates = setOf(firstDate, firstDate.plusDays(7), firstDate.plusDays(14))
        val target = course(
            1, "形势与政策",
            meetings = listOf(meeting(id = 1, kind = MeetingKind.DATE_SPECIFIC, day = 0, weeks = emptySet(), dates = dates)),
        )
        val projection = project(listOf(target))
        val row = projection.groups.single().scheduledRows.single()

        assertEquals(1, projection.meetingCount)
        dates.sorted().forEach { assertTrue(row.scheduleText.contains(it.toString())) }
        assertEquals("指定日期 2026-10-19、2026-10-26、2026-11-02", row.scheduleText)
    }

    @Test
    fun exceptionsAreReportedButNeverCountedAsArrangements() {
        val target = course(
            1, "线性代数",
            meetings = listOf(
                meeting(
                    id = 1, day = 1, weeks = setOf(1, 2), sections = setOf(1, 2),
                    exceptions = listOf(
                        MeetingException(kind = ExceptionKind.CANCEL, originalDate = firstDate),
                        MeetingException(kind = ExceptionKind.EXTRA, targetDate = firstDate.plusDays(1), sections = setOf(5, 6)),
                    ),
                ),
            ),
        )
        val projection = project(listOf(target))
        val row = projection.groups.single().scheduledRows.single()

        assertEquals(1, projection.meetingCount)
        assertEquals(2, row.exceptionTexts.size)
        assertTrue(row.exceptionTexts[0].startsWith("停课"))
        assertTrue(row.exceptionTexts[1].startsWith("加课"))
    }

    @Test
    fun groupingNeverLosesOrDuplicatesRecordsAndMeetings() {
        val records = listOf(
            course(1, "Hadoop实验", code = "BD101", classCode = "CS2101", meetings = listOf(meeting(id = 1), meeting(id = 2, day = 3))),
            course(2, "Hadoop实验（二）", code = "BD101", classCode = "CS2101", meetings = listOf(meeting(id = 3))),
            course(3, "体育", meetings = listOf(meeting(id = 4, day = 5))),
            course(4, "形势与政策", meetings = listOf(meeting(id = 5, kind = MeetingKind.UNSCHEDULED, day = 0, weeks = emptySet(), sections = emptySet()))),
        )
        val projection = project(records)

        assertEquals(records.size, projection.recordCount)
        assertEquals(records.sumOf { it.meetings.size }, projection.meetingCount)
        assertEquals(records.size, projection.groups.sumOf { it.courses.size })
        val seen = projection.groups.flatMap { it.rows }.map { Triple(it.course.id, it.meeting.id, it.meetingIndex) }
        assertEquals(seen.size, seen.distinct().size)
        assertEquals(projection.meetingCount, seen.size)
        assertTrue(projection.groups.all { it.key.isNotBlank() })
        assertEquals(projection.groupCount, projection.groups.map { it.key }.distinct().size)
    }

    @Test
    fun duplicateMeetingIdsStillProduceUniqueRowKeys() {
        val target = course(1, "合成课程", meetings = listOf(meeting(id = 0, day = 1), meeting(id = 0, day = 2)))
        val first = project(listOf(target))
        val rows = first.groups.single().scheduledRows

        assertEquals(2, rows.size)
        assertEquals(2, rows.map { it.key }.distinct().size)
        assertEquals(rows.map { it.key }, project(listOf(target)).groups.single().scheduledRows.map { it.key })
    }

    @Test
    fun updatedInputRecomputesGroupsCountsAndKeys() {
        val merged = listOf(
            course(1, "Hadoop实验", code = "BD101", classCode = "CS2101", meetings = listOf(meeting(id = 1))),
            course(2, "Hadoop实验二", code = "BD101", classCode = "CS2101", meetings = listOf(meeting(id = 2))),
        )
        val before = project(merged)
        assertEquals(1, before.groupCount)
        assertEquals(2, before.recordCount)

        val afterDelete = project(listOf(merged.first()))
        assertEquals(1, afterDelete.groupCount)
        assertEquals(1, afterDelete.recordCount)
        assertEquals(1, afterDelete.meetingCount)
        // The key follows the confirmed identity, not the current member set.
        assertEquals(before.groups.single().key, afterDelete.groups.single().key)

        val afterClassEdit = project(listOf(merged.first(), merged[1].copy(sourceClassCode = "CS2102")))
        assertEquals(2, afterClassEdit.groupCount)
        assertEquals(2, afterClassEdit.recordCount)

        val afterCodeEdit = project(listOf(merged.first().copy(sourceCourseCode = "BD999", meetings = listOf(meeting(id = 1)))))
        assertNotEquals(before.groups.single().key, afterCodeEdit.groups.single().key)
    }

    private fun project(
        courses: List<ScheduledCourse>,
        groupScope: CourseGroupScope = scope,
    ) = projectCourseManagement(courses, defaultPeriods(), groupScope)

    private fun course(
        id: Long,
        name: String,
        code: String? = null,
        classCode: String? = null,
        meetings: List<CourseMeeting> = listOf(meeting()),
        teacher: String = "",
        room: String = "",
        provenance: List<ScheduleProvenance> = emptyList(),
    ) = ScheduledCourse(
        id = id,
        courseName = name,
        teacher = teacher,
        room = room,
        color = 0xFF6750A4,
        meetings = meetings,
        sourceCourseCode = code,
        sourceClassCode = classCode,
        provenance = provenance,
    )

    private fun meeting(
        id: Long = 0,
        day: Int = 1,
        weeks: Set<Int> = setOf(1, 2, 3),
        sections: Set<Int> = setOf(1, 2),
        kind: MeetingKind = MeetingKind.RECURRING,
        dates: Set<LocalDate> = emptySet(),
        customTime: TimeRange? = null,
        teacher: String? = null,
        room: String? = null,
        project: String? = null,
        group: String? = null,
        exceptions: List<MeetingException> = emptyList(),
    ) = CourseMeeting(
        id = id,
        day = day,
        weeks = weeks,
        sections = sections,
        customTime = customTime,
        teacher = teacher,
        room = room,
        kind = kind,
        dates = dates,
        experimentProject = project,
        group = group,
        exceptions = exceptions,
    )
}