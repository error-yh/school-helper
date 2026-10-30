package com.aggapp.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aggapp.app.ui.MainActivity
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Self-contained public fixtures, real SAF and field mapping; no private inputs. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion=29)
class CampusCsvImportTest {
    @Test(timeout=180_000) fun actualCsvSelectionAndReviewedMappingPersistBothArrangements() =
        mappingRoundTrip("CSV","school-helper-stage3-csv-W74.csv","text/csv","1. CSV 表格","CSV导入验收W74")
    private fun mappingRoundTrip(kind:String,assetName:String,mime:String,sheetName:String,name:String) {
        val ui=CampusUiDriver();ui.launch();ui.waitText("本机课表")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        var activity:MainActivity?=null
        instrumentation.runOnMainSync {activity=ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()}
        val repository=checkNotNull(activity).timetableDependencies.repository
        fun snapshot()=runBlocking {withTimeout(10_000) {repository.observeTimetable().first()}}
        val before=snapshot()
        assertFalse("Synthetic name must not replace an existing course",before.courses.any {it.courseName==name})
        val originalIds=before.courses.map {it.id}.toSet()
        val document=OwnedSyntheticDocument.create(assetName,mime)
        try {
            ui.click("导入课表");ui.click(kind);ui.click("选择课表文件或图片")
            ui.chooseSystemDocument(document.displayName)
            ui.click(sheetName);assertEquals(before,snapshot())
            ui.enter("课程名称对应列","2");ui.click("确认工作表和字段映射")
            ui.revealText("同一列不能重复映射多个字段。")
            assertEquals(before,snapshot())
            ui.enter("课程名称对应列","3");ui.click("确认工作表和字段映射")
            ui.revealText(name);ui.click("核对并预览")
            ui.click("继续确认");assertEquals(before,snapshot())
            ui.click("取消");assertEquals(before,snapshot())
            ui.click("继续确认");ui.click("确认追加")
            ui.waitText("导入完成。可关闭此页查看课程。")
            val saved=snapshot();val course=saved.courses.single {it.courseName==name}
            assertEquals(2,course.meetings.size)
            val first=course.meetings.single {LocalDate.of(2026,12,31) in it.dates}
            val second=course.meetings.single {LocalDate.of(2027,1,1) in it.dates}
            assertEquals(LocalTime.of(10,0),first.customTime!!.start)
            assertEquals(LocalTime.of(11,30),first.customTime!!.end)
            assertEquals(LocalTime.of(15,0),second.customTime!!.start)
            assertEquals(LocalTime.of(16,0),second.customTime!!.end)
            assertTrue(course.provenance.plus(course.meetings.flatMap {it.provenance})
                .any {it.rawText==document.displayName})
            assertEquals(before.settings,saved.settings)
            assertEquals(before.courses,saved.courses.filterNot {it.courseName==name})
            ui.click("查看课表");ui.recreateActivity();ui.waitText("本机课表")
            assertEquals(saved,snapshot());ui.click("课程");ui.revealText(name)
        } finally {
            try {
                snapshot().courses.filter {it.courseName==name && it.id !in originalIds}
                    .forEach {runBlocking {repository.deleteCourse(it.id)}}
            } finally {document.close()}
        }
    }
}
