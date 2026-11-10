package com.aggapp.app.widget

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aggapp.app.ui.MainActivity
import com.lingion.sleepy.api.TimetableOccurrenceKey
import com.lingion.sleepy.api.TimetableOpenTarget
import java.time.LocalDate

internal object WidgetLink {
    private const val ACTION = "com.aggapp.app.widget.OPEN_TIMETABLE"
    private const val SCHEME = "school-helper"

    fun intent(context: Context, target: TimetableOpenTarget): PendingIntent {
        val uri = Uri.Builder().scheme(SCHEME).authority(context.packageName)
        val key = target.occurrence
        when {
            key != null -> uri.appendPath("course").appendPath(checkNotNull(target.date).toString())
                .appendPath(key.scope).appendPath(key.courseId.toString()).appendPath(key.meetingId.toString())
                .appendPath(key.exceptionId?.toString() ?: "base")
            target.date != null -> uri.appendPath("date").appendPath(target.date.toString())
            else -> uri.appendPath("today")
        }
        val intent = Intent(context, MainActivity::class.java).setAction(ACTION).setData(uri.build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    fun read(intent: Intent, packageName: String): TimetableOpenTarget? {
        val uri = intent.data ?: return null
        if (intent.action != ACTION || uri.scheme != SCHEME || uri.authority != packageName) return null
        if (uri.toString().length > 512) return TimetableOpenTarget()
        val parts = uri.pathSegments
        val date = parts.getOrNull(1)?.let {
            runCatching { LocalDate.parse(it) }.getOrNull()?.takeIf { day -> day.year in 1..9999 }
        }
        if (parts.size == 2 && parts[0] == "date") return TimetableOpenTarget(date)
        if (parts.size == 6 && parts[0] == "course" && date != null) {
            val scope = parts[2].takeIf { it.length in 1..128 }
            val courseId = parts[3].positiveId()
            val meetingId = parts[4].positiveId()
            val exceptionId = parts[5].positiveId()
            if (scope != null && courseId != null && meetingId != null &&
                (parts[5] == "base" || exceptionId != null)) {
                return TimetableOpenTarget(date, TimetableOccurrenceKey(scope, courseId, meetingId, exceptionId))
            }
        }
        return TimetableOpenTarget()
    }

    private fun String.positiveId(): Long? = toLongOrNull()?.takeIf { it > 0 }
}
