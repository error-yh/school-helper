package com.aggapp.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.aggapp.app.di.TimetableActivatorEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

class TodayWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in refreshActions) return
        if (intent.action == TodayWidget.ALARM_ACTION && TodayWidget.isDebug(context)) {
            Log.d("TodayWidget", "Alarm delivered at ${Instant.now()}")
        }
        val result = goAsync()
        TodayWidget.refresh(context) { result.finish() }
    }

    private companion object {
        val refreshActions = setOf(
            AppWidgetManager.ACTION_APPWIDGET_UPDATE,
            AppWidgetManager.ACTION_APPWIDGET_ENABLED,
            AppWidgetManager.ACTION_APPWIDGET_DELETED,
            AppWidgetManager.ACTION_APPWIDGET_DISABLED,
            AppWidgetManager.ACTION_APPWIDGET_OPTIONS_CHANGED,
            AppWidgetManager.ACTION_APPWIDGET_RESTORED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            TodayWidget.ALARM_ACTION,
        )
    }
}

internal object TodayWidget {
    const val ALARM_ACTION = "com.aggapp.app.widget.REFRESH"
    private const val TAG = "TodayWidget"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serial = Mutex()
    private var observer: Job? = null

    fun isDebug(context: Context): Boolean =
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun refresh(context: Context, done: () -> Unit = {}) {
        val app = context.applicationContext
        scope.launch {
            try {
                withTimeout(8_000) { serial.withLock { refreshLocked(app) } }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                Log.w(TAG, "Refresh interrupted: ${error.javaClass.simpleName}")
            } finally {
                done()
            }
        }
    }

    private suspend fun refreshLocked(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java))
        val alarms = context.getSystemService(AlarmManager::class.java)
        val refreshIntent = Intent(context, TodayWidgetProvider::class.java).setAction(ALARM_ACTION)
        if (ids.isEmpty()) {
            observer?.cancel()
            observer = null
            PendingIntent.getBroadcast(context, 0, refreshIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                alarms.cancel(it)
                it.cancel()
            }
            return
        }
        val alarm = PendingIntent.getBroadcast(context, 0, refreshIntent, PendingIntent.FLAG_IMMUTABLE)

        val snapshots = EntryPointAccessors.fromApplication(context, TimetableActivatorEntryPoint::class.java)
            .timetableProfileActivator().observeTimetableDynamic(readOnly = true)
        // Register the observer before taking the snapshot. The initial emission also requests a
        // refresh, covering a write in between this one-shot read and observer registration.
        if (observer?.isActive != true) {
            observer = scope.launch {
                try {
                    snapshots.collect { refresh(context) }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    Log.w(TAG, "Observation ended: ${error.javaClass.simpleName}")
                }
            }
        }

        // Profile cleanup can wait for a concurrent restore. Bound the broadcast's wait
        // independently of that cleanup, and never publish from the read coroutine.
        val read = scope.async { snapshots.first() }
        val snapshot = try {
            withTimeout(5_000) { read.await() }
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            Log.w(TAG, "Timetable unavailable: ${error.javaClass.simpleName}")
            null
        } finally {
            read.cancel()
        }
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val schedule = snapshot?.let { widgetSchedule(it, now, zone) }
        val next = schedule?.nextRefresh ?: now.atZone(zone).toLocalDate()
            .plusDays(1).atStartOfDay(zone).toInstant()

        var updated = 0
        for (id in ids) {
            try {
                manager.updateAppWidget(id, renderTodayWidget(context, manager.getAppWidgetOptions(id), snapshot, schedule))
                updated++
            } catch (error: RuntimeException) {
                Log.w(TAG, "Widget update failed: ${error.javaClass.simpleName}")
            }
        }
        // A single PendingIntent replaces the previously planned alarm for all instances.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.toEpochMilli(), alarm)
        if (isDebug(context)) {
            Log.d(TAG, "Published $updated/${ids.size} at ${Instant.now()}, evaluated at $now, next target $next")
        }
    }
}
