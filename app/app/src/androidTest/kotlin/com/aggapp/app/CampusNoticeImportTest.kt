package com.aggapp.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aggapp.app.ui.MainActivity
import com.lingion.sleepy.domain.model.MeetingKind
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic notice, real UI and Room. Only this test's course is cleaned up. */
@RunWith(AndroidJUnit4::class)
class CampusNoticeImportTest {
    @Test(timeout=180_000) fun relativeNoticeNeedsDateReviewThenPersistsAsIndependentExtra() {
        val ui=CampusUiDriver();ui.launch();ui.waitText("本机课表")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        var activity:MainActivity?=null
        instrumentation.runOnMainSync {
            activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
        }
        val repository=checkNotNull(activity).timetableDependencies.repository
        fun snapshot()=runBlocking {withTimeout(10_000) {repository.observeTimetable().first()}}
        val before=snapshot()
        val name="通知验收${SystemClock.uptimeMillis()}"
        try {
            ui.click("导入课表");ui.click("通知文字")
            ui.enter("粘贴通知","明日 $name 10:00-11:30 在测试室 加课")
            ui.enter("相对日期基准（年-月-日）","2026-12-30")
            ui.click("读取内容")
            ui.enter("作为新课程时的名称",name);ui.click("确认作为新课程")
            // The source-level and pending-record warnings are independent reviews.
            // Select actual unchecked controls, never toggle the first one twice.
            ui.waitContaining("2026-12-31");ui.click("核对并预览")
            ui.waitText("导入预览")
            assertEquals("Whole-import date review and preview must not write",before,snapshot())

            ui.click("继续确认");assertEquals(before,snapshot())
            ui.click("取消");assertEquals(before,snapshot())
            ui.click("继续确认");ui.click("确认追加")
            ui.waitText("导入完成。可关闭此页查看课程。")
            val saved=snapshot()
            val course=saved.courses.single {it.courseName==name}
            val meeting=course.meetings.single()
            assertEquals(MeetingKind.DATE_SPECIFIC,meeting.kind)
            assertEquals(setOf(LocalDate.of(2026,12,31)),meeting.dates)
            assertEquals(LocalTime.of(10,0),meeting.customTime!!.start)
            assertEquals(LocalTime.of(11,30),meeting.customTime!!.end)
            assertEquals("测试室",meeting.room)
            assertEquals(before.settings,saved.settings)
            assertEquals(before.courses.size+1,saved.courses.size)
            ui.click("查看课表");ui.recreateActivity();ui.waitText("本机课表")
            assertEquals(saved,snapshot())
            ui.click("课程");ui.revealText(name)
        } finally {
            snapshot().courses.filter {it.courseName==name}.forEach {runBlocking {repository.deleteCourse(it.id)}}
        }
    }
}
