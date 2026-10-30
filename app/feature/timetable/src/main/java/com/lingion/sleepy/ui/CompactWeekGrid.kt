/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lingion.sleepy.domain.model.CourseOccurrence
import com.lingion.sleepy.domain.model.ScheduledCourse
import kotlin.math.abs

private val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

private val COMPACT_CARD_PALETTE = listOf(
    Color(0xFFEE7981), // Soft coral / pink
    Color(0xFF5CBDA2), // Soft mint / teal
    Color(0xFF67A4E8), // Soft sky blue
    Color(0xFF9E86E2), // Soft lavender / purple
    Color(0xFFEAA25B), // Soft amber / warm orange
    Color(0xFFD6735A), // Soft rust / terra cotta
    Color(0xFF5B98D4), // Soft cornflower blue
    Color(0xFF7CB88B), // Soft sage green
)

internal fun compactCardColor(course: ScheduledCourse): Color {
    if (course.color != 0xFF6750A4L && course.color != 0L) {
        return Color(course.color.toInt())
    }
    val hash = course.courseName.hashCode().toLong() xor (course.id * 37L)
    val index = (abs(hash) % COMPACT_CARD_PALETTE.size).toInt()
    return COMPACT_CARD_PALETTE[index]
}

@Composable
fun CompactWeekGrid(
    state: CompactWeekGridState,
    onCourse: (ScheduledCourse) -> Unit,
    modifier: Modifier = Modifier,
    timeAxisWidth: Dp = 34.dp,
    periodHeight: Dp = 60.dp,
) {
    val maxBlockOffset = state.days.flatMap { it.blocks }.maxOfOrNull { it.startRowOffset + it.rowSpan } ?: state.periods.size.toFloat()
    val totalGridRows = maxOf(state.periods.size.toFloat(), maxBlockOffset)
    val totalHeight = (totalGridRows * periodHeight.value).dp
    val gridLineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

    Column(modifier = modifier.fillMaxWidth()) {
        // Pinned Days Header Row (Top)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Month Cell (Top-left corner)
            Box(
                modifier = Modifier
                    .width(timeAxisWidth)
                    .height(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${state.monthNumber}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "月",
                        fontSize = 9.sp,
                        lineHeight = 10.sp,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // 7 Day Headers (weight 1 each)
            state.days.forEachIndexed { index, day ->
                val weekdayName = WEEKDAY_NAMES.getOrElse(index) { "" }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (day.header.isToday) {
                        // Highlighted pill for today matching target visual
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF212529), RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = weekdayName,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 13.sp,
                                    color = Color.White,
                                )
                                Text(
                                    text = "${day.header.dayOfMonth}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    lineHeight = 12.sp,
                                    color = Color.White,
                                )
                            }
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = weekdayName,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${day.header.dayOfMonth}",
                                fontSize = 10.sp,
                                lineHeight = 12.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
        }

        // Scrollable Grid Area (Period axis + 7 day columns + Special Time + Missing Section)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalHeight)
                    .drawBehind {
                        // Draw horizontal dashed lines across the whole width
                        for (i in 1..state.periods.size) {
                            val y = i * periodHeight.toPx()
                            drawLine(
                                color = gridLineColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1f,
                                pathEffect = dashEffect,
                            )
                        }
                    },
            ) {
                // Left Period Column
                Column(
                    modifier = Modifier
                        .width(timeAxisWidth)
                        .height(totalHeight),
                ) {
                    state.periods.forEach { period ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(periodHeight),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "${period.number}",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "%02d:%02d".format(period.start.hour, period.start.minute),
                                fontSize = 8.sp,
                                lineHeight = 10.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            Text(
                                text = "%02d:%02d".format(period.end.hour, period.end.minute),
                                fontSize = 8.sp,
                                lineHeight = 10.sp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }

                // 7 Days Columns
                state.days.forEach { dayColumn ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(totalHeight)
                            .drawBehind {
                                // Draw vertical dashed line on the right border of this column
                                drawLine(
                                    color = gridLineColor,
                                    start = Offset(size.width, 0f),
                                    end = Offset(size.width, size.height),
                                    strokeWidth = 1f,
                                    pathEffect = dashEffect,
                                )
                            },
                    ) {
                        dayColumn.blocks.forEach { block ->
                            CompactCourseCard(
                                block = block,
                                periodHeight = periodHeight,
                                onCourse = onCourse,
                            )
                        }
                    }
                }
            }

            // Missing Section Warning Banner (if any course has unmapped sections)
        if (state.missingSectionOccurrences.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text(
                        text = "未定位节次课程（${state.missingSectionOccurrences.size} 门）：缺少作息节次设置",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    state.missingSectionOccurrences.forEach { occ ->
                        Text(
                            text = "• ${occ.course.courseName} (${occ.date} 节次 ${occ.missingSections.sorted().joinToString()})",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.clickable { onCourse(occ.course) },
                        )
                    }
                }
            }
        }

        // Off-grid / Special Time Courses Section (break times, out of school day)
        if (state.offGridOccurrences.isNotEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "特殊时段 / 课间课程（${state.offGridOccurrences.size} 门）",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    state.offGridOccurrences.forEach { occ ->
                        val timeStr = occ.effectiveCustomTime?.let { "${it.start}–${it.end}" } ?: "自定义时间"
                        val dayName = WEEKDAY_NAMES.getOrElse(occ.date.dayOfWeek.value - 1) { "" }
                        Card(
                            onClick = { onCourse(occ.course) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = compactCardColor(occ.course),
                                contentColor = Color.White,
                            ),
                            shape = RoundedCornerShape(6.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = occ.course.courseName,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    val details = listOfNotNull(
                                        "${occ.date} · $dayName",
                                        occ.effectiveRoom.takeIf { it.isNotBlank() }?.let { if (it.startsWith("@")) it else "@$it" },
                                        occ.effectiveTeacher.takeIf { it.isNotBlank() },
                                    ).joinToString(" · ")
                                    Text(
                                        text = details,
                                        fontSize = 9.sp,
                                        color = Color.White.copy(alpha = 0.92f),
                                    )
                                }
                                Text(
                                    text = timeStr,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
}

@Composable
private fun BoxScope.CompactCourseCard(
    block: CompactBlock,
    periodHeight: Dp,
    onCourse: (ScheduledCourse) -> Unit,
) {
    val topDp = (block.startRowOffset * periodHeight.value).dp
    val heightDp = (block.rowSpan * periodHeight.value).dp
    val visualHeight = maxOf(heightDp, 22.dp)
    val course = block.occurrence.course
    val bg = compactCardColor(course)
    val fg = if (bg.luminance() < 0.55f) Color.White else Color(0xFF1F1F1F)

    val room = block.occurrence.effectiveRoom.trim()
    val teacher = block.occurrence.effectiveTeacher.trim()
    val sectionText = if (block.startSection != null) {
        if (block.startSection == block.endSection) "节次${block.startSection}"
        else "节次${block.startSection}-${block.endSection}"
    } else {
        "自定义时间"
    }
    val desc = "课程块 ${course.courseName} $sectionText"

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = topDp)
            .height(visualHeight),
    ) {
        val totalWidth = maxWidth
        val laneCount = block.laneCount.coerceAtLeast(1)
        val laneWidth = totalWidth / laneCount
        val xOffset = laneWidth * block.lane

        Card(
            onClick = { onCourse(course) },
            modifier = Modifier
                .offset(x = xOffset)
                .width(laneWidth)
                .fillMaxHeight()
                .padding(horizontal = 1.dp, vertical = 1.5.dp)
                .semantics { contentDescription = desc },
            shape = RoundedCornerShape(5.dp),
            colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 2.5.dp, vertical = 3.dp),
                verticalArrangement = Arrangement.spacedBy(1.5.dp),
            ) {
                Text(
                    text = course.courseName,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 12.sp,
                    maxLines = if (block.rowSpan >= 2f) 3 else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = fg,
                )
                if (room.isNotBlank()) {
                    val formattedRoom = if (room.startsWith("@")) room else "@$room"
                    Text(
                        text = formattedRoom,
                        fontSize = 8.5.sp,
                        lineHeight = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = fg.copy(alpha = 0.95f),
                    )
                }
                if (teacher.isNotBlank() && block.rowSpan >= 1.5f) {
                    Text(
                        text = teacher,
                        fontSize = 8.5.sp,
                        lineHeight = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = fg.copy(alpha = 0.90f),
                    )
                }
                if (block.customTimeRange != null) {
                    Text(
                        text = "${block.customTimeRange.start}-${block.customTimeRange.end}",
                        fontSize = 8.sp,
                        lineHeight = 9.sp,
                        maxLines = 1,
                        color = fg.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}
