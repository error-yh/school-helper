package com.aggapp.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aggapp.app.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime

/** UI input -> reviewed preview -> explicit confirmation -> real Room; no private files. */
@RunWith(AndroidJUnit4::class)
class CampusImportTest {
    @Test(timeout=180_000) fun correctedSourceTimeRetainsOriginalAndNeedsFreshConfirmation() {
        sourceCorrectionRoundTrip(invalidSource=false)
    }
    @Test(timeout=180_000) fun malformedSourceTimeCannotSaveUntilCorrectedAndReviewed() {
        sourceCorrectionRoundTrip(invalidSource=true)
    }
    private fun sourceCorrectionRoundTrip(invalidSource:Boolean) {
        val ui=CampusUiDriver();ui.launch();ui.waitText("本机课表")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        var activity:MainActivity?=null
        instrumentation.runOnMainSync {
            activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
        }
        val repo=checkNotNull(activity).timetableDependencies.repository
        val name="纠错验收${SystemClock.uptimeMillis()}"
        val today=LocalDate.now().toString()
        val originalStart=if(invalidSource) "10:05" else "09:20"
        val originalEnd=if(invalidSource) "09:20" else "10:05"
        val json="""{"schemaVersion":1,"courses":[{"key":"correction","name":"$name","meetings":[{"key":"one","kind":"DATE_SPECIFIC","dates":["$today"],"time":{"start":"$originalStart","end":"$originalEnd"},"teacher":"测试教师","room":"测试教室","group":"","experimentProject":""}]}]}"""
        fun snapshot()=runBlocking {withTimeout(10_000) {repo.observeTimetable().first()}}
        val before=snapshot()
        try {
            ui.click("导入课表");ui.enter("粘贴 JSON",json);ui.click("读取内容")
            if(invalidSource) {
                // Acknowledging the original warning cannot authorize a malformed clock.
                ui.click("核对并预览")
                ui.waitContaining("来源的日期、周次或时间待修正，请编辑对应安排后重新检查。")
                assertEquals(before,snapshot())
            }
            ui.click("修改识别结果");ui.openDialog("修正安排 1-1","修正识别安排")
            ui.enter("修正开始时间","09:35");ui.enter("修正结束时间","10:20")
            assertEquals(before,snapshot())
            ui.click("应用修正");ui.revealText(name)
            ui.click("查看原文");ui.waitContaining("已人工修正");ui.waitContaining("09:20")
            ui.click("关闭原文")
            if(invalidSource) {
                // Correcting the value resets reviews, including the retained source warning.
            }
            ui.click("核对并预览");ui.click("继续确认")
            assertEquals(before,snapshot())
            ui.click("取消");assertEquals(before,snapshot())
            ui.click("继续确认");ui.click("确认追加");ui.waitText("导入完成。可关闭此页查看课程。")
            val saved=snapshot();val meeting=saved.courses.single {it.courseName==name}.meetings.single()
            assertEquals(LocalTime.of(9,35),meeting.customTime?.start)
            assertEquals(LocalTime.of(10,20),meeting.customTime?.end)
            assertEquals(before.revision+1,saved.revision)
            ui.click("查看课表");ui.revealText(name);ui.waitContaining("09:35")
            ui.recreateActivity();ui.waitText("本机课表");ui.revealText(name);ui.waitContaining("09:35")
            assertEquals(saved,snapshot())
        } catch(failure:Throwable) {
            runCatching {ui.captureImportFailure()}
            val persisted=snapshot().courses.filter {it.courseName==name}.flatMap {it.meetings}
                .any {it.customTime?.start==LocalTime.of(9,35) && it.customTime?.end==LocalTime.of(10,20)}
            throw AssertionError("Correction UI failure; corrected time persisted=$persisted",failure)
        } finally {
            snapshot().courses.filter {it.courseName==name}.forEach {course->runBlocking {repo.deleteCourse(course.id)}}
        }
    }
    @Test(timeout=180_000) fun dateCourseImportIsVisibleAndRepeatedImportWritesNothing() {
        val ui=CampusUiDriver();ui.launch();ui.waitText("本机课表")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        var activity:MainActivity?=null
        instrumentation.runOnMainSync {
            activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
        }
        val repo=checkNotNull(activity).timetableDependencies.repository
        val name="导入验收${SystemClock.uptimeMillis()}"
        val today=LocalDate.now().toString()
        val json="""{"schemaVersion":1,"courses":[{"key":"synthetic","name":"$name","meetings":[{"key":"one","kind":"DATE_SPECIFIC","dates":["$today"],"time":{"start":"09:20","end":"10:05"},"teacher":"测试教师","room":"测试教室","group":"","experimentProject":""}]}]}"""
        fun snapshot()=runBlocking { withTimeout(10_000) { repo.observeTimetable().first() } }
        InstrumentationRegistry.getArguments().getString("confirmedStartDate")?.let { value ->
            val confirmed=LocalDate.parse(value)
            require(confirmed.year in 1..9999)
            if(snapshot().settings.startDate!=confirmed) {
                ui.click("设置");ui.enter("开学日期",value);ui.click("保存设置");ui.waitText("设置已保存");ui.click("今日")
            }
            assertEquals(confirmed,snapshot().settings.startDate)
        }
        val before=snapshot()
        try {
            ui.click("导入课表");ui.enter("粘贴 JSON",json)
            ui.click("读取内容");ui.revealText("核对并预览")
            ui.click("核对并预览");ui.click("继续确认")
            // Opening confirmation alone must not write anything.
            assertEquals(before,snapshot())
            ui.click("取消");assertEquals(before,snapshot())
            ui.click("继续确认");ui.click("确认追加");ui.waitText("导入完成。可关闭此页查看课程。")
            val saved=snapshot();assertEquals(1,saved.courses.count { it.courseName==name })
            assertEquals(before.revision+1,saved.revision)
            ui.click("查看课表");ui.revealText(name);ui.waitContaining("09:20")
            ui.click("本周");ui.revealText(name)
            ui.click("导入课表");ui.click("读取内容");ui.revealText("核对并预览")
            ui.click("核对并预览");ui.waitText("所选内容已存在，确认后不会重复写入。")
            ui.click("继续确认");ui.click("确认追加");ui.waitText("导入完成。可关闭此页查看课程。")
            assertEquals(saved,snapshot())
            ui.click("查看课表")
        } catch(failure:Throwable) {
            runCatching { ui.captureImportFailure() }
            throw failure
        } finally {
            snapshot().courses.filter { it.courseName==name }.forEach { course -> runBlocking { repo.deleteCourse(course.id) } }
        }
    }
}
