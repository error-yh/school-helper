package com.aggapp.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aggapp.app.CampusUiDriver
import com.aggapp.app.R
import com.aggapp.app.di.TimetableActivatorEntryPoint
import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.model.TimetableSnapshot
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayWidgetDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun rendersCompactStandardDarkAndPreviewUsingPlatformRemoteViews() {
        val zone = ZoneId.systemDefault()
        val date = LocalDate.of(2026, 11, 10)
        val meeting = CourseMeeting(5, 0, emptySet(), emptySet(),
            customTime = TimeRange(LocalTime.of(8, 30), LocalTime.of(10, 5)),
            kind = MeetingKind.DATE_SPECIFIC, dates = setOf(date, date.plusDays(1)))
        val course = ScheduledCourse(3, "很长的合成实验课程名称".repeat(8), "测试教师", "测试地点",
            0xFF6750A4, listOf(meeting))
        val snapshot = TimetableSnapshot(courses = listOf(course))
        val running = widgetSchedule(snapshot, date.atTime(9, 0).atZone(zone).toInstant(), zone)
        val ended = widgetSchedule(snapshot, date.atTime(10, 5).atZone(zone).toInstant(), zone)

        fun inflate(height: Int, night: Boolean, schedule: WidgetSchedule): View {
            val themed = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            })
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 240)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 240)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            }
            return renderTodayWidget(themed, options, snapshot, schedule).apply(themed, FrameLayout(themed)).also {
                val density = themed.resources.displayMetrics.density
                it.measure(
                    View.MeasureSpec.makeMeasureSpec((240 * density).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY),
                )
                it.layout(0, 0, it.measuredWidth, it.measuredHeight)
            }
        }

        instrumentation.runOnMainSync {
            val compact = inflate(104, false, running)
            val standard = inflate(166, true, running)
            fun View.label(id: Int) = findViewById<TextView>(id)
            assertEquals("测试地点", compact.label(R.id.widget_course_meta).text.toString())
            assertEquals("测试地点 · 测试教师", standard.label(R.id.widget_course_meta).text.toString())
            assertEquals("08:30\n10:05", standard.label(R.id.widget_course_time).text.toString())
            assertEquals("进行中", compact.label(R.id.widget_course_status).text.toString())
            assertNotEquals(compact.label(R.id.widget_date).currentTextColor, standard.label(R.id.widget_date).currentTextColor)
            val name = compact.label(R.id.widget_course_name)
            val time = compact.label(R.id.widget_course_time)
            assertTrue(name.width > 0 && time.width > 0 && name.parent is View)
            val preview = inflate(104, false, ended)
            assertEquals(View.GONE, preview.findViewById<View>(R.id.widget_rows).visibility)
            assertTrue(preview.label(R.id.widget_preview).text.startsWith("明日第一门 · 08:30"))
        }
    }

    /** Opt-in launcher fixture. Never writes to a regular debug or production package. */
    @Test fun prepareLauncherFixture() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("widgetFixture") == "true")
        check(context.packageName == "io.github.schoolhelper.app.widgettest")
        val repo = EntryPointAccessors.fromApplication(context, TimetableActivatorEntryPoint::class.java)
            .timetableProfileActivator().scheduleRepository
        val now = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)
        val date = now.toLocalDate()
        val start = now.minusMinutes(2).toLocalTime()
        val firstEnd = now.toLocalTime().plusMinutes(5)
        val nextStart = firstEnd.plusMinutes(4)
        check(now.minusMinutes(2).toLocalDate() == date && now.plusMinutes(70).toLocalDate() == date) {
            "临近午夜，请改用独立日期夹具"
        }
        runBlocking {
            val previous = repo.observeTimetable().first()
            check(previous.courses.all { it.courseName.startsWith("组件验收 · ") })
            previous.courses.forEach { repo.deleteCourse(it.id) }
            val names = listOf("实验 A 组", "课程 B", "课程 C", "明日课程")
            val times = listOf(start to firstEnd, nextStart to nextStart.plusMinutes(25),
                nextStart.plusMinutes(30) to nextStart.plusMinutes(60), LocalTime.of(9, 12) to LocalTime.of(10, 23))
            times.forEachIndexed { index, (from, to) ->
                val day = if (index == 3) date.plusDays(1) else date
                repo.saveCourse(CourseDraft("组件验收 · ${names[index]}", teacher = "合成教师",
                    room = "合成教室 ${index + 1}", color = listOf(0xFF6750A4, 0xFF336B75, 0xFFAB5937, 0xFF355A3F)[index],
                    meetings = listOf(CourseMeeting(day = 0, weeks = emptySet(), sections = emptySet(),
                        customTime = TimeRange(from, to), kind = MeetingKind.DATE_SPECIFIC, dates = setOf(day)))))
            }
        }
        android.util.Log.i("WidgetFixture", "First planned end ${date.atTime(firstEnd)}")
        if (args.getString("pinWidget") == "true") {
            val ui = CampusUiDriver()
            ui.launch()
            ui.waitText("本机课表")
            val manager = AppWidgetManager.getInstance(context)
            check(manager.isRequestPinAppWidgetSupported) { "Launcher does not support programmatic pinning" }
            check(manager.requestPinAppWidget(ComponentName(context, TodayWidgetProvider::class.java), null, null))
        }
    }
}
