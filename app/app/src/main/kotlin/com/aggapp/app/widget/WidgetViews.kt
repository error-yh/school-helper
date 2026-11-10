package com.aggapp.app.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.aggapp.app.R
import com.lingion.sleepy.api.TimetableOccurrenceKey
import com.lingion.sleepy.api.TimetableOpenTarget
import com.lingion.sleepy.domain.model.TimetableSnapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("MM.dd", Locale.CHINA)

internal fun renderTodayWidget(
    context: Context,
    options: Bundle,
    snapshot: TimetableSnapshot?,
    schedule: WidgetSchedule?,
): RemoteViews {
    val portrait = render(context, snapshot, schedule,
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH),
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT))
    val landscape = render(context, snapshot, schedule,
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH),
        options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT))
    return RemoteViews(landscape, portrait)
}

private fun render(
    context: Context,
    snapshot: TimetableSnapshot?,
    schedule: WidgetSchedule?,
    width: Int,
    height: Int,
): RemoteViews = RemoteViews(context.packageName, R.layout.today_widget).apply {
    val date = schedule?.date ?: LocalDate.now()
    val week = schedule?.week?.let { context.getString(R.string.widget_week, it) }
    setTextViewText(R.id.widget_date, listOfNotNull(date.format(dateFormat), week,
        date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.CHINA)).joinToString(" "))
    setOnClickPendingIntent(R.id.widget_header, WidgetLink.intent(context, TimetableOpenTarget()))
    val rows = schedule?.rows.orEmpty()
    setViewVisibility(R.id.widget_rows, if (rows.isEmpty()) View.GONE else View.VISIBLE)
    setViewVisibility(R.id.widget_empty, if (rows.isEmpty()) View.VISIBLE else View.GONE)
    removeAllViews(R.id.widget_rows)

    if (rows.isEmpty()) {
        setTextViewText(R.id.widget_message, context.getString(when (schedule?.day) {
            WidgetDay.EMPTY -> R.string.widget_empty
            WidgetDay.ENDED -> R.string.widget_ended
            WidgetDay.INCOMPLETE -> R.string.widget_incomplete
            else -> R.string.widget_unavailable
        }))
        val tomorrow = schedule?.tomorrow
        setViewVisibility(R.id.widget_preview, if (tomorrow == null) View.GONE else View.VISIBLE)
        if (tomorrow != null) {
            setTextViewText(R.id.widget_preview, context.getString(R.string.widget_tomorrow,
                tomorrow.timeRanges.minOf { it.start }, tomorrow.course.courseName.take(180)))
            setOnClickPendingIntent(R.id.widget_preview, WidgetLink.intent(context, TimetableOpenTarget(tomorrow.date)))
        }
    } else {
        val spacious = height >= 155 && width >= 210
        for (row in rows) {
            addView(R.id.widget_rows, renderRow(context, row, checkNotNull(snapshot).scopeToken, spacious))
        }
    }
}

private fun renderRow(
    context: Context,
    row: VisibleWidgetRow,
    scope: String,
    spacious: Boolean,
): RemoteViews = RemoteViews(context.packageName, R.layout.today_widget_row).apply {
    val occurrence = row.occurrence
    setInt(R.id.widget_course_color, "setBackgroundColor", occurrence.course.color.toInt() or 0xFF000000.toInt())
    setTextViewText(R.id.widget_course_name, occurrence.course.courseName.take(180))
    val room = occurrence.effectiveRoom.trim().takeIf { it.isNotEmpty() }
    val teacher = occurrence.effectiveTeacher.trim().takeIf { it.isNotEmpty() && spacious }
    val meta = listOfNotNull(room, teacher).joinToString(" · ").take(180)
    setTextViewText(R.id.widget_course_meta, meta)
    setViewVisibility(R.id.widget_course_meta, if (meta.isEmpty()) View.GONE else View.VISIBLE)
    setTextViewText(R.id.widget_course_time,
        "${occurrence.timeRanges.minOf { it.start }}\n${occurrence.timeRanges.maxOf { it.end }}")
    val isSoon = row.status == WidgetStatus.SOON
    setViewVisibility(R.id.widget_course_status, if (row.status == WidgetStatus.UPCOMING) View.GONE else View.VISIBLE)
    if (row.status != WidgetStatus.UPCOMING) {
        setTextViewText(R.id.widget_course_status, context.getString(if (isSoon) R.string.widget_soon else R.string.widget_running))
        setTextColor(R.id.widget_course_status, context.getColor(if (isSoon) R.color.widget_soon_text else R.color.widget_running_text))
        setInt(R.id.widget_course_status, "setBackgroundColor",
            context.getColor(if (isSoon) R.color.widget_soon_surface else R.color.widget_running_surface))
    }
    if (spacious) {
        val padding = (5 * context.resources.displayMetrics.density).toInt()
        setViewPadding(R.id.widget_course_content, padding, padding, padding, padding)
        if (Build.VERSION.SDK_INT >= 31) {
            setInt(R.id.widget_course_name, "setMaxLines", 2)
            setViewLayoutHeight(R.id.widget_course_name, 32f, TypedValue.COMPLEX_UNIT_DIP)
        }
    }
    val key = TimetableOccurrenceKey(scope, occurrence.course.id, occurrence.meeting.id, occurrence.exception?.id)
    setOnClickPendingIntent(R.id.widget_course_row, WidgetLink.intent(context, TimetableOpenTarget(occurrence.date, key)))
}
