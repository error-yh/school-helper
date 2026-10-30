/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.ui

import com.lingion.sleepy.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class CompactWeekProjectionTest {

    private val baseSemester = SemesterSettings(
        startDate = LocalDate.of(2026, 9, 7), // Week 1 Monday
        totalWeeks = 20,
    )
    private val monday = LocalDate.of(2026, 10, 26) // Week 8 Monday
    private val periods = defaultPeriods()

    @Test
    fun consecutiveSectionsMergedIntoSingleContinuousBlock() {
        // e.g. "机器学习" sections 1 and 2 on Tuesday
        val course = ScheduledCourse(
            id = 101L,
            courseName = "机器学习",
            teacher = "刘伟",
            room = "C3敏学楼210",
            color = 0xFF6750A4L,
            meetings = listOf(
                CourseMeeting(
                    id = 1L,
                    day = 2, // Tuesday
                    weeks = setOf(8),
                    sections = setOf(1, 2),
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday, today = LocalDate.of(2026, 10, 30))

        val tuesday = state.days[1] // Index 1 is Tuesday
        assertEquals(1, tuesday.blocks.size)
        val block = tuesday.blocks[0]
        assertEquals(1, block.startSection)
        assertEquals(2, block.endSection)
        assertEquals(0f, block.startRowOffset, 0.001f)
        assertEquals(2f, block.rowSpan, 0.001f)
        assertEquals(0, block.lane)
        assertEquals(1, block.laneCount)
    }

    @Test
    fun threeConsecutiveSectionsMergedIntoSingleContinuousBlock() {
        val course = ScheduledCourse(
            id = 102L,
            courseName = "操作系统实训",
            teacher = "王教授",
            room = "实验楼101",
            color = 0xFF6750A4L,
            meetings = listOf(
                CourseMeeting(
                    id = 2L,
                    day = 1, // Monday
                    weeks = setOf(8),
                    sections = setOf(3, 4, 5),
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        val mondayCol = state.days[0]
        assertEquals(1, mondayCol.blocks.size)
        val block = mondayCol.blocks[0]
        assertEquals(3, block.startSection)
        assertEquals(5, block.endSection)
        assertEquals(2f, block.startRowOffset, 0.001f) // Section 3 is index 2
        assertEquals(3f, block.rowSpan, 0.001f)
    }

    @Test
    fun sparseSectionsSeparatedWithoutBridgingEmptyGap() {
        // Sections 1 and 3 on Monday (Section 2 is free/empty)
        val course = ScheduledCourse(
            id = 103L,
            courseName = "稀疏研讨课",
            teacher = "赵老师",
            room = "研讨室A",
            color = 0xFF6750A4L,
            meetings = listOf(
                CourseMeeting(
                    id = 3L,
                    day = 1, // Monday
                    weeks = setOf(8),
                    sections = setOf(1, 3),
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        val mondayCol = state.days[0]
        assertEquals(2, mondayCol.blocks.size)

        val block1 = mondayCol.blocks[0]
        assertEquals(1, block1.startSection)
        assertEquals(1, block1.endSection)
        assertEquals(0f, block1.startRowOffset, 0.001f)
        assertEquals(1f, block1.rowSpan, 0.001f)

        val block2 = mondayCol.blocks[1]
        assertEquals(3, block2.startSection)
        assertEquals(3, block2.endSection)
        assertEquals(2f, block2.startRowOffset, 0.001f)
        assertEquals(1f, block2.rowSpan, 0.001f)
    }

    @Test
    fun distinctMeetingsWithSameCourseNameAreNotMerged() {
        // Two independent meetings on the same day: meeting 1 is section 1, meeting 2 is section 2
        val course = ScheduledCourse(
            id = 104L,
            courseName = "高等数学",
            teacher = "孙老师",
            room = "一教101",
            color = 0xFF6750A4L,
            meetings = listOf(
                CourseMeeting(id = 10L, day = 3, weeks = setOf(8), sections = setOf(1)),
                CourseMeeting(id = 11L, day = 3, weeks = setOf(8), sections = setOf(2)),
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        val wednesdayCol = state.days[2] // Index 2 is Wednesday
        assertEquals(2, wednesdayCol.blocks.size)
        assertEquals(1, wednesdayCol.blocks[0].startSection)
        assertEquals(1, wednesdayCol.blocks[0].endSection)
        assertEquals(2, wednesdayCol.blocks[1].startSection)
        assertEquals(2, wednesdayCol.blocks[1].endSection)
    }

    @Test
    fun overlappingCoursesOnSameDayAssignedToLanesWithinDay() {
        // Course 1 at sections 1-2, Course 2 at sections 2-3 on Wednesday
        val c1 = ScheduledCourse(
            id = 201L,
            courseName = "重叠课程A",
            teacher = "甲",
            room = "A101",
            color = 0xFF112233L,
            meetings = listOf(CourseMeeting(id = 21L, day = 3, weeks = setOf(8), sections = setOf(1, 2)))
        )
        val c2 = ScheduledCourse(
            id = 202L,
            courseName = "重叠课程B",
            teacher = "乙",
            room = "B102",
            color = 0xFF445566L,
            meetings = listOf(CourseMeeting(id = 22L, day = 3, weeks = setOf(8), sections = setOf(2, 3)))
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(c1, c2))
        val state = projectCompactWeek(snapshot, monday)

        val wednesday = state.days[2]
        assertEquals(2, wednesday.blocks.size)
        assertEquals(2, wednesday.maxLanes)

        val b1 = wednesday.blocks[0]
        val b2 = wednesday.blocks[1]
        assertEquals(2, b1.laneCount)
        assertEquals(2, b2.laneCount)
        assertNotEquals(b1.lane, b2.lane)

        // Other days remain 1 lane
        assertEquals(1, state.days[0].maxLanes)
        assertEquals(1, state.days[1].maxLanes)
        assertEquals(1, state.days[3].maxLanes)
    }

    @Test
    fun customTimeAccuratelyMapped() {
        // Custom time 08:30 - 09:30 spanning across Period 1 and Period 2
        val course = ScheduledCourse(
            id = 301L,
            courseName = "讲座",
            teacher = "专家",
            room = "大礼堂",
            color = 0xFF778899L,
            meetings = listOf(
                CourseMeeting(
                    id = 31L,
                    day = 4, // Thursday
                    weeks = setOf(8),
                    sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 30), LocalTime.of(9, 30))
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        val thursday = state.days[3]
        assertEquals(1, thursday.blocks.size)
        val block = thursday.blocks[0]
        assertTrue(block.isCustomTime)
        assertNotNull(block.customTimeRange)
        assertEquals(LocalTime.of(8, 30), block.customTimeRange?.start)
        assertEquals(LocalTime.of(9, 30), block.customTimeRange?.end)
        assertTrue(block.startRowOffset in 0.5f..0.8f)
        assertTrue(block.rowSpan > 0.5f)
    }

    @Test
    fun shortDurationCourseHasAccurateRowSpanWithoutArtificialMinimum() {
        // Short 10-minute course: 08:10 - 08:20 inside Period 1 (08:00 - 08:45)
        val course = ScheduledCourse(
            id = 302L,
            courseName = "晨读早会",
            teacher = "班主任",
            room = "教室A",
            color = 0xFF334455L,
            meetings = listOf(
                CourseMeeting(
                    id = 32L,
                    day = 1, // Monday
                    weeks = setOf(8),
                    sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 10), LocalTime.of(8, 20))
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        val mondayCol = state.days[0]
        assertEquals(1, mondayCol.blocks.size)
        val block = mondayCol.blocks[0]
        val expectedStart = 10f / 45f // ~0.222f
        val expectedSpan = 10f / 45f  // ~0.222f
        assertEquals(expectedStart, block.startRowOffset, 0.01f)
        assertEquals(expectedSpan, block.rowSpan, 0.01f)
        // Verify it was NOT artificially coerced to 0.5f!
        assertTrue(block.rowSpan < 0.35f)
    }

    @Test
    fun customTimeInBreakIsClassifiedAsOffGridAndNotFakedIntoAdjacentPeriod() {
        // Period 1 is 08:00-08:45, Period 2 is 08:55-09:40.
        // Course takes place entirely during break: 08:46 - 08:50.
        val course = ScheduledCourse(
            id = 303L,
            courseName = "课间眼保健操",
            teacher = "体育组",
            room = "全校",
            color = 0xFF556677L,
            meetings = listOf(
                CourseMeeting(
                    id = 33L,
                    day = 2, // Tuesday
                    weeks = setOf(8),
                    sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 46), LocalTime.of(8, 50))
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        // Tuesday section grid blocks must NOT contain this break course (do not fake it into Period 2!)
        val tuesdayCol = state.days[1]
        assertEquals(0, tuesdayCol.blocks.size)

        // It must be accurately preserved in offGridOccurrences so it is visible and clickable
        assertEquals(1, state.offGridOccurrences.size)
        val offGrid = state.offGridOccurrences[0]
        assertEquals(303L, offGrid.course.id)
        assertEquals(LocalTime.of(8, 46), offGrid.effectiveCustomTime?.start)
        assertEquals(LocalTime.of(8, 50), offGrid.effectiveCustomTime?.end)
    }

    @Test
    fun customTimeOutsideSchoolDayIsOffGridAndReachable() {
        // Before first period: 07:00 - 07:45 (First period starts at 08:00)
        val earlyCourse = ScheduledCourse(
            id = 304L,
            courseName = "晨跑打卡",
            teacher = "教练",
            room = "操场",
            color = 0xFF119922L,
            meetings = listOf(
                CourseMeeting(
                    id = 34L,
                    day = 3, // Wednesday
                    weeks = setOf(8),
                    sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(7, 0), LocalTime.of(7, 45))
                )
            )
        )
        // After last period: 22:45 - 23:30 (Period 12 ends at 22:30)
        val lateCourse = ScheduledCourse(
            id = 305L,
            courseName = "晚自习答疑",
            teacher = "助教",
            room = "自习室",
            color = 0xFF992211L,
            meetings = listOf(
                CourseMeeting(
                    id = 35L,
                    day = 3, // Wednesday
                    weeks = setOf(8),
                    sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(22, 45), LocalTime.of(23, 30))
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(earlyCourse, lateCourse))
        val state = projectCompactWeek(snapshot, monday)

        // Neither course is squeezed into grid row 0 or row periods.size
        assertEquals(0, state.days[2].blocks.size)
        // Both are preserved in offGridOccurrences
        assertEquals(2, state.offGridOccurrences.size)
        val ids = state.offGridOccurrences.map { it.course.id }.toSet()
        assertTrue(ids.contains(304L))
        assertTrue(ids.contains(305L))
    }

    @Test
    fun documentMetadataProvenanceCannotMergeDistinctCustomTimeMeetings() {
        // Same course, contiguous times: 08:00-08:45 and 08:45-09:30.
        // Different teachers & rooms (Teacher 甲/Room A vs Teacher 乙/Room B).
        // Both carry generic "document-metadata" and "document:display-name".
        val genericProv = listOf(
            ScheduleProvenance(
                sourceId = "document-metadata",
                sourceLocator = "document:display-name",
                rawText = "sample.pdf"
            )
        )
        val course = ScheduledCourse(
            id = 306L,
            courseName = "研讨分组",
            teacher = "甲",
            room = "A",
            color = 0xFF445566L,
            meetings = listOf(
                CourseMeeting(
                    id = 361L,
                    day = 4, // Thursday
                    weeks = setOf(8),
                    sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45)),
                    teacher = "甲",
                    room = "A",
                    provenance = genericProv,
                ),
                CourseMeeting(
                    id = 362L,
                    day = 4, // Thursday
                    weeks = setOf(8),
                    sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 45), LocalTime.of(9, 30)),
                    teacher = "乙",
                    room = "B",
                    provenance = genericProv,
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        val thursdayCol = state.days[3]
        // They MUST NOT be merged into a single block because:
        // 1) "document:display-name" is generic file-level metadata;
        // 2) Teachers/rooms differ.
        assertEquals(2, thursdayCol.blocks.size)
        assertEquals("甲", thursdayCol.blocks[0].occurrence.effectiveTeacher)
        assertEquals("乙", thursdayCol.blocks[1].occurrence.effectiveTeacher)
    }

    @Test
    fun consecutiveCustomTimeFragmentsAcrossPeriodBreakWithCellProvenanceAreMerged() {
        // Period 1 is 08:00-08:45, Period 2 is 08:55-09:40 (break 08:45-08:55).
        // A single logical lesson imported as two physical fragments across the break.
        // Both fragments have the same teacher, room, and specific cell locator "table:row:1:col:2".
        val cellProv = listOf(
            ScheduleProvenance(
                sourceId = "sheet1",
                sourceLocator = "table:row:1:col:2",
                rawText = "机器学习"
            )
        )
        val course = ScheduledCourse(
            id = 307L,
            courseName = "机器学习",
            teacher = "刘伟",
            room = "C3敏学楼210",
            color = 0xFF6750A4L,
            meetings = listOf(
                CourseMeeting(
                    id = 371L,
                    day = 5, // Friday
                    weeks = setOf(8),
                    sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45)),
                    teacher = "刘伟",
                    room = "C3敏学楼210",
                    provenance = cellProv,
                ),
                CourseMeeting(
                    id = 372L,
                    day = 5, // Friday
                    weeks = setOf(8),
                    sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 55), LocalTime.of(9, 40)),
                    teacher = "刘伟",
                    room = "C3敏学楼210",
                    provenance = cellProv,
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        val fridayCol = state.days[4]
        // They ARE merged into 1 continuous block spanning 08:00 to 09:40!
        assertEquals(1, fridayCol.blocks.size)
        val merged = fridayCol.blocks[0]
        assertEquals(LocalTime.of(8, 0), merged.customTimeRange?.start)
        assertEquals(LocalTime.of(9, 40), merged.customTimeRange?.end)
        assertEquals("刘伟", merged.occurrence.effectiveTeacher)
        assertEquals("C3敏学楼210", merged.occurrence.effectiveRoom)
    }

    @Test
    fun customTimeFragmentsWithArbitraryGapNotMergedEvenWithSpecificProvenance() {
        // Two fragments separated by an arbitrary 5-hour gap (not a period break):
        // Fragment 1: 08:00 - 08:45, Fragment 2: 14:00 - 14:45.
        // Both carry cell provenance.
        val cellProv = listOf(
            ScheduleProvenance(
                sourceId = "sheet1",
                sourceLocator = "table:row:1:col:2",
                rawText = "机器学习"
            )
        )
        val course = ScheduledCourse(
            id = 308L,
            courseName = "机器学习",
            teacher = "刘伟",
            room = "C3敏学楼210",
            color = 0xFF6750A4L,
            meetings = listOf(
                CourseMeeting(
                    id = 381L,
                    day = 5, // Friday
                    weeks = setOf(8),
                    sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45)),
                    provenance = cellProv,
                ),
                CourseMeeting(
                    id = 382L,
                    day = 5, // Friday
                    weeks = setOf(8),
                    sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(14, 0), LocalTime.of(14, 45)),
                    provenance = cellProv,
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        val fridayCol = state.days[4]
        // Must NOT be merged across the 5-hour gap!
        assertEquals(2, fridayCol.blocks.size)
        assertEquals(LocalTime.of(8, 0), fridayCol.blocks[0].customTimeRange?.start)
        assertEquals(LocalTime.of(14, 0), fridayCol.blocks[1].customTimeRange?.start)
    }

    @Test
    fun differentSourceIdWithSameLocalLocatorAndNullBoundsNotMerged() {
        // Two fragments with identical local locator "table:row:1:col:1", null bounds, but from DIFFERENT source files ("sourceDocA" vs "sourceDocB")
        val provA = listOf(ScheduleProvenance(sourceId = "sourceDocA", sourceLocator = "table:row:1:col:1", rawText = "课程分段1"))
        val provB = listOf(ScheduleProvenance(sourceId = "sourceDocB", sourceLocator = "table:row:1:col:1", rawText = "课程分段2"))
        val course = ScheduledCourse(
            id = 309L,
            courseName = "跨文件同名排课",
            teacher = "刘伟",
            room = "C3-210",
            color = 0xFF6750A4L,
            meetings = listOf(
                CourseMeeting(
                    id = 391L, day = 5, weeks = setOf(8), sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45)),
                    teacher = "刘伟", room = "C3-210", provenance = provA,
                ),
                CourseMeeting(
                    id = 392L, day = 5, weeks = setOf(8), sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 55), LocalTime.of(9, 40)),
                    teacher = "刘伟", room = "C3-210", provenance = provB,
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        val fridayCol = state.days[4]
        // Must NOT be merged because sourceId differs and bounds are both null (null bounds != evidence of identity)
        assertEquals(2, fridayCol.blocks.size)
        assertEquals(LocalTime.of(8, 0), fridayCol.blocks[0].customTimeRange?.start)
        assertEquals(LocalTime.of(8, 55), fridayCol.blocks[1].customTimeRange?.start)
    }

    @Test
    fun differentSourceIdWithSamePageAndBoundsNotMerged() {
        // Same course, contiguous/break-connected customTime, identical metadata,
        // but DIFFERENT sourceId ("fileA.pdf" vs "fileB.pdf"), different locators ("locA" vs "locB"),
        // both on page 1 with identical non-null bounds.
        val sharedBounds = SourceBounds(10f, 20f, 100f, 200f)
        val provA = listOf(
            ScheduleProvenance(
                sourceId = "fileA.pdf",
                sourceLocator = "locA",
                page = 1,
                bounds = sharedBounds,
                rawText = "课程分段A",
            )
        )
        val provB = listOf(
            ScheduleProvenance(
                sourceId = "fileB.pdf",
                sourceLocator = "locB",
                page = 1,
                bounds = sharedBounds,
                rawText = "课程分段B",
            )
        )
        val course = ScheduledCourse(
            id = 312L,
            courseName = "跨文件同页同坐标课程",
            teacher = "刘伟",
            room = "C3-210",
            color = 0xFF6750A4L,
            meetings = listOf(
                CourseMeeting(
                    id = 701L, day = 5, weeks = setOf(8), sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45)),
                    teacher = "刘伟", room = "C3-210", provenance = provA,
                ),
                CourseMeeting(
                    id = 702L, day = 5, weeks = setOf(8), sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 55), LocalTime.of(9, 40)),
                    teacher = "刘伟", room = "C3-210", provenance = provB,
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        val fridayCol = state.days[4]
        // Must NOT be merged because sourceId differs despite matching page & bounds
        assertEquals(2, fridayCol.blocks.size)
        assertEquals(LocalTime.of(8, 0), fridayCol.blocks[0].customTimeRange?.start)
        assertEquals(LocalTime.of(8, 55), fridayCol.blocks[1].customTimeRange?.start)
    }

    @Test
    fun sameSourceIdWithSamePageAndBoundsAreMerged() {
        // Same course, break-connected customTime, identical metadata,
        // SAME sourceId ("fileA.pdf"), different locators ("locA" vs "locB"),
        // both on page 1 with identical non-null bounds.
        val sharedBounds = SourceBounds(10f, 20f, 100f, 200f)
        val provA = listOf(
            ScheduleProvenance(
                sourceId = "fileA.pdf",
                sourceLocator = "locA",
                page = 1,
                bounds = sharedBounds,
                rawText = "课程分段A",
            )
        )
        val provB = listOf(
            ScheduleProvenance(
                sourceId = "fileA.pdf",
                sourceLocator = "locB",
                page = 1,
                bounds = sharedBounds,
                rawText = "课程分段B",
            )
        )
        val course = ScheduledCourse(
            id = 313L,
            courseName = "同文件同页同坐标课程",
            teacher = "刘伟",
            room = "C3-210",
            color = 0xFF6750A4L,
            meetings = listOf(
                CourseMeeting(
                    id = 703L, day = 5, weeks = setOf(8), sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45)),
                    teacher = "刘伟", room = "C3-210", provenance = provA,
                ),
                CourseMeeting(
                    id = 704L, day = 5, weeks = setOf(8), sections = emptySet(),
                    customTime = TimeRange(LocalTime.of(8, 55), LocalTime.of(9, 40)),
                    teacher = "刘伟", room = "C3-210", provenance = provB,
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        val fridayCol = state.days[4]
        // ARE merged because they share the same sourceId, page, and physical bounds
        assertEquals(1, fridayCol.blocks.size)
        val merged = fridayCol.blocks[0]
        assertEquals(LocalTime.of(8, 0), merged.customTimeRange?.start)
        assertEquals(LocalTime.of(9, 40), merged.customTimeRange?.end)
        assertEquals("刘伟", merged.occurrence.effectiveTeacher)
        assertEquals("C3-210", merged.occurrence.effectiveRoom)
    }

    @Test
    fun sameCourseDifferentLogicalBasesNotMerged() {
        // Two separate meetings of the same course tied to different logical base descriptors
        val base1 = com.lingion.sleepy.domain.imports.ImportLogicalBaseDisplay(
            reference = com.lingion.sleepy.domain.imports.LogicalBaseReference.StoredLogicalBase("local", 1L, 310L, "base1", "fp1"),
            title = "高级研讨1",
            original = CourseMeeting(id = 501L, day = 1, weeks = setOf(8), sections = emptySet(), customTime = TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45))),
            timeRanges = listOf(TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45))),
            teacher = "张教授", room = "研讨室1", project = null, group = null,
        )
        val base2 = com.lingion.sleepy.domain.imports.ImportLogicalBaseDisplay(
            reference = com.lingion.sleepy.domain.imports.LogicalBaseReference.StoredLogicalBase("local", 1L, 310L, "base2", "fp2"),
            title = "高级研讨2",
            original = CourseMeeting(id = 502L, day = 1, weeks = setOf(8), sections = emptySet(), customTime = TimeRange(LocalTime.of(8, 45), LocalTime.of(9, 30))),
            timeRanges = listOf(TimeRange(LocalTime.of(8, 45), LocalTime.of(9, 30))),
            teacher = "张教授", room = "研讨室1", project = null, group = null,
        )
        val course = ScheduledCourse(
            id = 310L,
            courseName = "高级研讨",
            teacher = "张教授",
            room = "研讨室1",
            color = 0xFF6750A4L,
            meetings = listOf(
                CourseMeeting(id = 501L, day = 1, weeks = setOf(8), sections = emptySet(), customTime = TimeRange(LocalTime.of(8, 0), LocalTime.of(8, 45)), teacher = "张教授", room = "研讨室1"),
                CourseMeeting(id = 502L, day = 1, weeks = setOf(8), sections = emptySet(), customTime = TimeRange(LocalTime.of(8, 45), LocalTime.of(9, 30)), teacher = "张教授", room = "研讨室1"),
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday, logicalBases = listOf(base1, base2))

        val mondayCol = state.days[0]
        // Because they have different logical bases, they MUST NOT be merged
        assertEquals(2, mondayCol.blocks.size)
    }

    @Test
    fun dateSpecificSameMeetingMultipleDatesBothRetainedInOffGrid() {
        // Single DATE_SPECIFIC meeting occurring on Tuesday (2026-10-27) and Thursday (2026-10-29) in late evening (22:45-23:30)
        val tuesdayDate = LocalDate.of(2026, 10, 27)
        val thursdayDate = LocalDate.of(2026, 10, 29)
        val course = ScheduledCourse(
            id = 311L,
            courseName = "深夜线上辅导",
            teacher = "李助教",
            room = "腾讯会议",
            color = 0xFF884422L,
            meetings = listOf(
                CourseMeeting(
                    id = 601L,
                    kind = MeetingKind.DATE_SPECIFIC,
                    day = 0,
                    weeks = emptySet(),
                    sections = emptySet(),
                    dates = setOf(tuesdayDate, thursdayDate),
                    customTime = TimeRange(LocalTime.of(22, 45), LocalTime.of(23, 30)),
                    teacher = "李助教",
                    room = "腾讯会议",
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        // Both dates must be retained in offGridOccurrences without being lost to deduplication!
        assertEquals(2, state.offGridOccurrences.size)
        val occ1 = state.offGridOccurrences[0]
        val occ2 = state.offGridOccurrences[1]

        assertEquals(tuesdayDate, occ1.date)
        assertEquals(LocalTime.of(22, 45), occ1.effectiveCustomTime?.start)
        assertEquals(LocalTime.of(23, 30), occ1.effectiveCustomTime?.end)
        assertEquals("深夜线上辅导", occ1.course.courseName)
        assertEquals("李助教", occ1.effectiveTeacher)

        assertEquals(thursdayDate, occ2.date)
        assertEquals(LocalTime.of(22, 45), occ2.effectiveCustomTime?.start)
        assertEquals(LocalTime.of(23, 30), occ2.effectiveCustomTime?.end)
        assertEquals("深夜线上辅导", occ2.course.courseName)
        assertEquals("李助教", occ2.effectiveTeacher)
    }

    @Test
    fun missingSectionsNotDroppedAndReported() {
        // Section 99 is not in periods 1..12
        val course = ScheduledCourse(
            id = 401L,
            courseName = "未知作息课",
            teacher = "陈老师",
            room = "未知教室",
            color = 0xFFAABBCCL,
            meetings = listOf(
                CourseMeeting(
                    id = 41L,
                    day = 5, // Friday
                    weeks = setOf(8),
                    sections = setOf(99),
                )
            )
        )
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = listOf(course))
        val state = projectCompactWeek(snapshot, monday)

        assertEquals(1, state.missingSectionOccurrences.size)
        assertEquals(401L, state.missingSectionOccurrences[0].course.id)
    }

    @Test
    fun headerAndTodayHighlightFlaggedAccurately() {
        val fridayDate = LocalDate.of(2026, 10, 30)
        val snapshot = TimetableSnapshot(settings = baseSemester, periods = periods, courses = emptyList())
        val state = projectCompactWeek(snapshot, monday, today = fridayDate)

        assertEquals(10, state.monthNumber)
        assertEquals(7, state.days.size)

        // Monday 26th
        assertEquals(LocalDate.of(2026, 10, 26), state.days[0].header.date)
        assertEquals(1, state.days[0].header.dayOfWeek)
        assertEquals(26, state.days[0].header.dayOfMonth)
        assertFalse(state.days[0].header.isToday)

        // Friday 30th (Today)
        assertEquals(LocalDate.of(2026, 10, 30), state.days[4].header.date)
        assertEquals(5, state.days[4].header.dayOfWeek)
        assertEquals(30, state.days[4].header.dayOfMonth)
        assertTrue(state.days[4].header.isToday)

        // Sunday 1st
        assertEquals(LocalDate.of(2026, 11, 1), state.days[6].header.date)
        assertEquals(7, state.days[6].header.dayOfWeek)
        assertEquals(1, state.days[6].header.dayOfMonth)
        assertFalse(state.days[6].header.isToday)
    }
}
