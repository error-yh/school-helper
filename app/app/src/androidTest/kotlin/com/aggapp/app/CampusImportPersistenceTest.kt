package com.aggapp.app

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aggapp.app.ui.MainActivity
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit seed/verify runs are separated by an external force-stop, never pm clear.
 * The default run checks Activity recreation. Neither route changes semester settings.
 */
@RunWith(AndroidJUnit4::class)
class CampusImportPersistenceTest {
    @Test(timeout=180_000) fun importedCourseIsPersistedAndDisplayed() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val args=InstrumentationRegistry.getArguments()
        val phase=args.getString("persistencePhase")
        require(phase==null || phase in setOf("seed","verify"))
        val name=args.getString("courseName") ?: "Stage3ImportRestart_${SystemClock.uptimeMillis()}"
        require(name.matches(Regex("Stage3ImportRestart_[0-9]+")))
        val ui=CampusUiDriver();ui.launch();ui.waitText("本机课表")
        var activity:MainActivity?=null
        instrumentation.runOnMainSync {
            activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
        }
        val repo=checkNotNull(activity).timetableDependencies.repository
        fun snapshot()=runBlocking {withTimeout(10_000) {repo.observeTimetable().first()}}
        val checkpoint=File(instrumentation.targetContext.filesDir,"stage3-evidence/import-restart-checkpoint.json")
        var ownsCourse=false;var retainForRestart=false
        try {
            if(phase!="verify") {
                check(!checkpoint.exists()) {"An earlier owned persistence checkpoint needs verification first"}
                val before=snapshot()
                check(before.courses.none {it.courseName==name}) {"Synthetic name already exists"}
                ownsCourse=true
                val today=LocalDate.now()
                val json="""{"schemaVersion":1,"courses":[{"key":"restart","name":"$name","meetings":[{"key":"one","kind":"DATE_SPECIFIC","dates":["$today"],"time":{"start":"09:40","end":"10:25"},"teacher":"Synthetic teacher","room":"Synthetic room","group":"","experimentProject":""}]}]}"""
                ui.click("导入课表");ui.enter("粘贴 JSON",json);ui.click("读取内容")
                ui.click("核对并预览");ui.click("继续确认");ui.click("确认追加")
                ui.waitText("导入完成。可关闭此页查看课程。")
                val saved=snapshot();val course=saved.courses.single {it.courseName==name}
                assertEquals(before.revision+1,saved.revision)
                checkpoint.parentFile!!.mkdirs()
                checkpoint.writeText(JSONObject().put("name",name).put("courseId",course.id)
                    .put("meetingId",course.meetings.single().id).put("revision",saved.revision)
                    .put("scope",saved.scopeToken).put("semesterId",saved.settings.id)
                    .put("date",today.toString()).put("startDate",saved.settings.startDate?.toString() ?: "").toString())
                ui.click("查看课表");ui.revealText(name);ui.waitContaining("09:40")
                if(phase=="seed") {retainForRestart=true;return}
                ui.recreateActivity();ui.waitText("本机课表")
            }
            val expected=JSONObject(checkpoint.readText())
            check(expected.getString("name")==name) {"Checkpoint belongs to a different synthetic run"}
            ownsCourse=true
            val actual=snapshot();val course=actual.courses.single {it.courseName==name};val meeting=course.meetings.single()
            assertEquals(expected.getLong("courseId"),course.id)
            assertEquals(expected.getLong("meetingId"),meeting.id)
            assertEquals(expected.getLong("revision"),actual.revision)
            assertTrue("Local profile changed",expected.getString("scope")==actual.scopeToken)
            assertEquals(expected.getLong("semesterId"),actual.settings.id)
            assertTrue("Semester start changed",expected.getString("startDate")==actual.settings.startDate?.toString().orEmpty())
            assertEquals(setOf(LocalDate.parse(expected.getString("date"))),meeting.dates)
            assertEquals(LocalTime.of(9,40),meeting.customTime?.start)
            assertEquals(LocalTime.of(10,25),meeting.customTime?.end)
            ui.revealText(name);ui.waitContaining("09:40")
            ui.click("本周");ui.revealText(name)
        } finally {
            if(ownsCourse && !retainForRestart) {
                snapshot().courses.filter {it.courseName==name}.forEach {runBlocking {repo.deleteCourse(it.id)}}
                if(checkpoint.exists() && runCatching {JSONObject(checkpoint.readText()).getString("name")==name}.getOrDefault(false)) checkpoint.delete()
            }
        }
    }
}
