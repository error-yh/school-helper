/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.ui

import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.imports.ImportLogicalBaseDisplay
import com.lingion.sleepy.domain.model.*
import java.time.LocalDate
import java.time.LocalTime

/**
 * A projected display block on the compact weekly grid.
 *
 * Represents either:
 * - A continuous span of standard periods (startSection..endSection inclusive).
 * - A custom-time block placed continuously along the period timeline.
 */
data class CompactBlock(
    val occurrence: CourseOccurrence,
    val startSection: Int?,
    val endSection: Int?,
    val customTimeRange: TimeRange? = null,
    val startRowOffset: Float,
    val rowSpan: Float,
    val lane: Int = 0,
    val laneCount: Int = 1,
) {
    val isCustomTime: Boolean get() = customTimeRange != null
}

data class CompactDayHeader(
    val date: LocalDate,
    val dayOfWeek: Int, // 1..7 (Monday to Sunday)
    val dayOfMonth: Int,
    val isToday: Boolean,
)

data class CompactDayColumn(
    val header: CompactDayHeader,
    val blocks: List<CompactBlock>,
    val maxLanes: Int = 1,
)

data class CompactWeekGridState(
    val monthNumber: Int,
    val days: List<CompactDayColumn>,
    val periods: List<Period>,
    val missingSectionOccurrences: List<CourseOccurrence>,
    val offGridOccurrences: List<CourseOccurrence> = emptyList(),
)

internal fun findConsecutiveGroups(sections: List<Int>): List<List<Int>> {
    if (sections.isEmpty()) return emptyList()
    val sorted = sections.distinct().sorted()
    val groups = mutableListOf<MutableList<Int>>()
    var current = mutableListOf(sorted.first())
    for (i in 1 until sorted.size) {
        if (sorted[i] == sorted[i - 1] + 1) {
            current.add(sorted[i])
        } else {
            groups.add(current)
            current = mutableListOf(sorted[i])
        }
    }
    groups.add(current)
    return groups
}

/**
 * Determines whether a custom time range cannot be naturally mapped onto standard period rows.
 * This includes:
 * - Entirely before the first period.
 * - Entirely after the last period.
 * - Entirely within a break between two periods.
 */
internal fun isOffGridCustomTime(customTime: TimeRange, periods: List<Period>): Boolean {
    if (periods.isEmpty()) return true
    val firstStart = periods.first().start
    val lastEnd = periods.last().end
    if (customTime.end <= firstStart || customTime.start >= lastEnd) return true
    for (i in 0 until periods.size - 1) {
        val breakStart = periods[i].end
        val breakEnd = periods[i + 1].start
        if (customTime.start >= breakStart && customTime.end <= breakEnd) {
            return true
        }
    }
    return false
}

internal fun timeToRowOffset(time: LocalTime, periods: List<Period>): Float {
    if (periods.isEmpty()) return 0f
    val sorted = periods.sortedBy { it.start }
    if (time <= sorted.first().start) return 0f
    if (time >= sorted.last().end) return sorted.size.toFloat()
    for (i in sorted.indices) {
        val p = sorted[i]
        if (time >= p.start && time <= p.end) {
            val dur = (p.end.toSecondOfDay() - p.start.toSecondOfDay()).coerceAtLeast(60)
            val elapsed = (time.toSecondOfDay() - p.start.toSecondOfDay()).coerceAtLeast(0)
            return i + (elapsed.toFloat() / dur).coerceIn(0f, 1f)
        }
        if (i < sorted.size - 1) {
            val next = sorted[i + 1]
            if (time > p.end && time < next.start) {
                // Break boundary maps to row i + 1
                return (i + 1).toFloat()
            }
        }
    }
    return sorted.size.toFloat()
}

/**
 * Projects a raw TimetableSnapshot for a given Monday into a compact 7-day display model.
 *
 * Rules strictly followed:
 * 1. 7 days are always created (Monday through Sunday).
 * 2. Consecutive sections belonging to the same lesson are merged into a single continuous block.
 * 3. Non-consecutive sections (e.g. 1 and 3) remain separate blocks without bridging empty gaps.
 * 4. Separate meetings or courses are never merged merely by course name.
 * 5. Physical customTime fragments are only merged when an explicit common logical source is proven,
 *    excluding generic document-level metadata, and with identical secondary metadata.
 * 6. Off-grid / break / out-of-bounds custom times are safely preserved in [CompactWeekGridState.offGridOccurrences].
 * 7. Overlapping courses are arranged in side-by-side lanes inside the day column, never widening the week.
 * 8. Courses with unmapped sections are preserved in [CompactWeekGridState.missingSectionOccurrences].
 */
fun projectCompactWeek(
    snapshot: TimetableSnapshot,
    monday: LocalDate,
    today: LocalDate = LocalDate.now(),
    logicalBases: List<ImportLogicalBaseDisplay> = emptyList(),
): CompactWeekGridState {
    val periods = if (snapshot.periods.isNotEmpty()) {
        snapshot.periods.sortedBy { it.number }
    } else {
        defaultPeriods()
    }
    val periodNumbers = periods.map { it.number }.toSet()
    val periodIndexByNumber = periods.mapIndexed { index, p -> p.number to index }.toMap()

    val dates = (0..6).map { monday.plusDays(it.toLong()) }
    val allMissing = mutableListOf<CourseOccurrence>()
    val allOffGrid = mutableListOf<CourseOccurrence>()

    val dayColumns = dates.map { date ->
        val occurrences = TimetableRules.occurrencesOn(snapshot, date)
        val rawBlocks = mutableListOf<CompactBlock>()

        for (occ in occurrences) {
            if (occ.missingSections.isNotEmpty()) {
                allMissing.add(occ)
            }

            val customTime = occ.effectiveCustomTime
            if (customTime != null) {
                if (isOffGridCustomTime(customTime, periods)) {
                    // Cannot be mapped onto standard period rows without falsifying adjacent periods.
                    // Preserve in explicit offGrid list.
                    allOffGrid.add(occ)
                } else {
                    val startOffset = timeToRowOffset(customTime.start, periods)
                    val endOffset = timeToRowOffset(customTime.end, periods)
                    val span = maxOf(endOffset - startOffset, 0.05f)
                    rawBlocks.add(
                        CompactBlock(
                            occurrence = occ,
                            startSection = null,
                            endSection = null,
                            customTimeRange = customTime,
                            startRowOffset = startOffset,
                            rowSpan = span,
                        )
                    )
                }
            } else {
                val validSections = occ.effectiveSections.filter { it in periodNumbers }.sorted()
                val groups = findConsecutiveGroups(validSections)
                for (group in groups) {
                    val startSec = group.first()
                    val endSec = group.last()
                    val startIndex = periodIndexByNumber[startSec] ?: 0
                    val endIndex = periodIndexByNumber[endSec] ?: startIndex
                    val span = (endIndex - startIndex + 1).toFloat()
                    rawBlocks.add(
                        CompactBlock(
                            occurrence = occ,
                            startSection = startSec,
                            endSection = endSec,
                            customTimeRange = null,
                            startRowOffset = startIndex.toFloat(),
                            rowSpan = span,
                        )
                    )
                }
            }
        }

        // Merge contiguous customTime fragments only if they share an explicit proven logical source
        val mergedBlocks = mergeProvenLogicalFragments(rawBlocks, periods, logicalBases)

        // Assign lanes for overlapping blocks within this day column
        val placedBlocks = assignDayLanes(mergedBlocks)
        val maxLanes = placedBlocks.maxOfOrNull { it.laneCount } ?: 1

        val header = CompactDayHeader(
            date = date,
            dayOfWeek = date.dayOfWeek.value,
            dayOfMonth = date.dayOfMonth,
            isToday = (date == today),
        )

        CompactDayColumn(
            header = header,
            blocks = placedBlocks,
            maxLanes = maxLanes,
        )
    }

    return CompactWeekGridState(
        monthNumber = monday.monthValue,
        days = dayColumns,
        periods = periods,
        missingSectionOccurrences = allMissing.distinctBy {
            Triple(it.date, Pair(it.course.id, it.meeting.id), it.missingSections)
        }.sortedWith(compareBy<CourseOccurrence> { it.date }.thenBy { it.course.id }),
        offGridOccurrences = allOffGrid.distinctBy {
            Triple(it.date, Pair(it.course.id, it.meeting.id), it.effectiveCustomTime to it.exception?.id)
        }.sortedWith(compareBy<CourseOccurrence> { it.date }.thenBy { it.effectiveCustomTime?.start ?: LocalTime.MIN }),
    )
}

/**
 * Filter out generic document-level provenance that cannot serve as evidence of the same logical meeting.
 */
internal fun isGenericProvenance(p: ScheduleProvenance): Boolean {
    if (p.sourceId == "document-metadata") return true
    if (p.sourceLocator == "document:display-name") return true
    if (p.sourceLocator.isNullOrBlank()) return true
    return false
}

/**
 * Determines whether two custom-time fragments can be merged into a single continuous block.
 */
internal fun canMergeCustomTimeFragments(
    a: CompactBlock,
    b: CompactBlock,
    periods: List<Period>,
    logicalBases: List<ImportLogicalBaseDisplay> = emptyList(),
): Boolean {
    val occA = a.occurrence
    val occB = b.occurrence
    val rA = a.customTimeRange ?: return false
    val rB = b.customTimeRange ?: return false

    // 1. Must be the same course
    if (occA.course.id != occB.course.id) return false

    // 2. Secondary attributes must match strictly (prevent losing independent arrangements)
    if (occA.effectiveTeacher != occB.effectiveTeacher) return false
    if (occA.effectiveRoom != occB.effectiveRoom) return false
    if (occA.effectiveProject != occB.effectiveProject) return false
    if (occA.effectiveGroup != occB.effectiveGroup) return false
    if (occA.exception != occB.exception) return false

    // 3. Time connection: either contiguous or separated strictly by standard period break
    val (earlier, later) = if (rA.start < rB.start) Pair(rA, rB) else Pair(rB, rA)
    val isTimeConnected = if (earlier.end == later.start) {
        true
    } else {
        periods.indices.any { i ->
            i < periods.size - 1 &&
                earlier.end == periods[i].end &&
                later.start == periods[i + 1].start
        }
    }
    if (!isTimeConnected) return false

    // 4. Must possess explicit logical proof beyond document-level metadata
    return hasExplicitCommonLogicalProof(occA, occB, logicalBases)
}

internal fun hasExplicitCommonLogicalProof(
    a: CourseOccurrence,
    b: CourseOccurrence,
    logicalBases: List<ImportLogicalBaseDisplay> = emptyList(),
): Boolean {
    // If explicitly derived from the same meeting ID
    if (a.meeting.id != 0L && a.meeting.id == b.meeting.id) return true

    // Check logicalBaseDetails strictly by meeting/original ID if available (never coarsely by courseId)
    if (logicalBases.isNotEmpty()) {
        val baseA = logicalBases.firstOrNull { it.original.id != 0L && it.original.id == a.meeting.id }
        val baseB = logicalBases.firstOrNull { it.original.id != 0L && it.original.id == b.meeting.id }
        if (baseA != null && baseB != null && baseA.reference == baseB.reference) {
            return true
        }
    }

    // Check specific (non-generic) provenance (e.g. table cell, row/col, or OCR bounds)
    val provA = a.meeting.provenance.filterNot(::isGenericProvenance)
    val provB = b.meeting.provenance.filterNot(::isGenericProvenance)
    if (provA.isEmpty() || provB.isEmpty()) return false

    return provA.any { pA ->
        provB.any { pB ->
            val sameSourceContext = !pA.sourceId.isNullOrBlank() && pA.sourceId == pB.sourceId

            // 1. Same non-blank sourceId AND same non-blank specific locator
            val sameSourceAndLocator = sameSourceContext &&
                !pA.sourceLocator.isNullOrBlank() &&
                pA.sourceLocator == pB.sourceLocator

            // 2. Or same non-null physical bounding box on same source context AND matching page
            val samePhysicalBounds = sameSourceContext &&
                pA.bounds != null &&
                pB.bounds != null &&
                pA.bounds == pB.bounds &&
                pA.page == pB.page

            sameSourceAndLocator || samePhysicalBounds
        }
    }
}

/**
 * Merge contiguous/break-connected customTime fragments only if they share an explicit verified logical source.
 */
private fun mergeProvenLogicalFragments(
    blocks: List<CompactBlock>,
    periods: List<Period>,
    logicalBases: List<ImportLogicalBaseDisplay>,
): List<CompactBlock> {
    if (blocks.size <= 1) return blocks
    val result = mutableListOf<CompactBlock>()
    val visited = BooleanArray(blocks.size)

    for (i in blocks.indices) {
        if (visited[i]) continue
        var current = blocks[i]
        visited[i] = true

        if (current.customTimeRange != null) {
            for (j in i + 1 until blocks.size) {
                if (visited[j]) continue
                val candidate = blocks[j]
                if (canMergeCustomTimeFragments(current, candidate, periods, logicalBases)) {
                    val cRange = current.customTimeRange!!
                    val kRange = candidate.customTimeRange!!
                    val newStart = minOf(cRange.start, kRange.start)
                    val newEnd = maxOf(cRange.end, kRange.end)
                    val newStartOffset = minOf(current.startRowOffset, candidate.startRowOffset)
                    val newEndOffset = maxOf(
                        current.startRowOffset + current.rowSpan,
                        candidate.startRowOffset + candidate.rowSpan
                    )
                    current = current.copy(
                        customTimeRange = TimeRange(newStart, newEnd),
                        startRowOffset = newStartOffset,
                        rowSpan = newEndOffset - newStartOffset,
                    )
                    visited[j] = true
                }
            }
        }
        result.add(current)
    }
    return result
}

/**
 * Graph coloring / lane assignment for intervals within a single day column.
 */
private fun assignDayLanes(blocks: List<CompactBlock>): List<CompactBlock> {
    if (blocks.isEmpty()) return emptyList()

    val sorted = blocks.sortedWith(
        compareBy<CompactBlock> { it.startRowOffset }
            .thenBy { it.startRowOffset + it.rowSpan }
            .thenBy { it.occurrence.course.id }
    )

    // Greedy lane assignment
    val laneEnds = mutableListOf<Float>()
    val lanes = IntArray(sorted.size)

    for (i in sorted.indices) {
        val b = sorted[i]
        val start = b.startRowOffset
        val end = b.startRowOffset + b.rowSpan
        var assigned = -1
        for (l in laneEnds.indices) {
            if (laneEnds[l] <= start + 0.001f) {
                assigned = l
                laneEnds[l] = end
                break
            }
        }
        if (assigned == -1) {
            assigned = laneEnds.size
            laneEnds.add(end)
        }
        lanes[i] = assigned
    }

    // Find connected components (overlapping clusters)
    val clusterIds = IntArray(sorted.size) { it }
    for (i in sorted.indices) {
        val startI = sorted[i].startRowOffset
        val endI = startI + sorted[i].rowSpan
        for (j in i + 1 until sorted.size) {
            val startJ = sorted[j].startRowOffset
            val endJ = startJ + sorted[j].rowSpan
            if (maxOf(startI, startJ) < minOf(endI, endJ) - 0.001f) {
                val rootI = findRoot(clusterIds, i)
                val rootJ = findRoot(clusterIds, j)
                if (rootI != rootJ) {
                    clusterIds[rootJ] = rootI
                }
            }
        }
    }

    // Determine max lane in each connected component
    val clusterMaxLane = mutableMapOf<Int, Int>()
    for (i in sorted.indices) {
        val root = findRoot(clusterIds, i)
        val currentMax = clusterMaxLane[root] ?: 0
        clusterMaxLane[root] = maxOf(currentMax, lanes[i])
    }

    return sorted.mapIndexed { i, b ->
        val root = findRoot(clusterIds, i)
        val maxLane = (clusterMaxLane[root] ?: 0) + 1
        b.copy(
            lane = lanes[i],
            laneCount = maxLane,
        )
    }
}

private fun findRoot(parent: IntArray, i: Int): Int {
    var curr = i
    while (curr != parent[curr]) {
        parent[curr] = parent[parent[curr]]
        curr = parent[curr]
    }
    return curr
}
