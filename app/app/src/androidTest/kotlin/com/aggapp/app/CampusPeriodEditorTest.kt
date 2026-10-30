package com.aggapp.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aggapp.app.ui.MainActivity
import com.lingion.sleepy.domain.repository.ScheduleRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.temporal.ChronoUnit

@RunWith(AndroidJUnit4::class)
class CampusPeriodEditorTest {
    private fun repository():ScheduleRepository {
        var active:MainActivity?=null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            active=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
        }
        return checkNotNull(active).timetableDependencies.repository
    }
    @Test(timeout=180_000) fun independentPeriodDraftSavesAndRestoresWithoutChangingCourses() {
        val ui=CampusUiDriver();ui.launch();ui.waitText("本机课表")
        val repo=repository()
        fun snapshot()=runBlocking {withTimeout(10_000) {repo.observeTimetable().first()}}
        val before=snapshot()
        val shortest=before.periods.minOf {ChronoUnit.MINUTES.between(it.start,it.end).toInt()}
        require(shortest>1)
        val duration=minOf(40,shortest-1)
        try {
            ui.selectTab("设置");ui.click("编辑时间表");ui.waitText("编辑时间表")
            // Real independent start/end inputs, followed by discard: no repository injection.
            ui.setSwitch("每节课时长相同",false)
            val first=before.periods.first()
            val format=java.time.format.DateTimeFormatter.ofPattern("HH:mm")
            val editedStart=first.start.plusMinutes(1).format(format)
            val editedEnd=first.end.minusMinutes(1).format(format)
            ui.setClock("作息${first.number}开始",editedStart)
            ui.setClock("作息${first.number}结束",editedEnd)
            assertTrue("Independent edits must remain drafts",before==snapshot())
            ui.recreateActivity();ui.waitText("编辑时间表");ui.waitText(editedStart);ui.waitText(editedEnd)
            ui.click("返回");ui.waitText("学期与作息")
            assertTrue("Discarding independent edits must not write",before==snapshot())
            ui.click("编辑时间表")
            ui.setSwitch("每节课时长相同",true)
            ui.enter("统一课时分钟",duration.toString())
            assertTrue("Draft must not write",before==snapshot())
            ui.recreateActivity();ui.waitText("编辑时间表");ui.waitValue("统一课时分钟",duration.toString())
            ui.click("返回");ui.waitText("学期与作息")
            assertTrue("Discard must not write",before==snapshot())
            ui.click("编辑时间表");ui.setSwitch("每节课时长相同",true)
            ui.enter("统一课时分钟",duration.toString())
            ui.capture("stage3-period-editor-draft")
            ui.click("保存");ui.waitText("学期与作息")
            val saved=snapshot()
            assertTrue("Semester settings changed",before.settings==saved.settings)
            assertTrue("Courses changed",before.courses==saved.courses)
            assertTrue("Profile changed",before.scopeToken==saved.scopeToken)
            assertTrue("Start times changed",before.periods.map {it.start}==saved.periods.map {it.start})
            assertTrue(saved.periods.all {ChronoUnit.MINUTES.between(it.start,it.end).toInt()==duration})
            assertEquals(before.revision+1,saved.revision)
            ui.recreateActivity();ui.waitText("本机课表")
            val reloaded=runBlocking {withTimeout(10_000) {repository().observeTimetable().first()}}
            assertTrue("Saved configuration changed after recreation",saved==reloaded)
        } finally {
            val current=snapshot()
            check(current.scopeToken==before.scopeToken && current.settings==before.settings && current.courses==before.courses) {"Concurrent course/settings change: preserve current data"}
            if(current.periods!=before.periods) runBlocking {repo.updateSettings(before.settings,before.periods)}
            val restored=snapshot()
            assertTrue("Original periods not restored",before.periods==restored.periods)
            assertTrue("Original semester changed",before.settings==restored.settings)
            assertTrue("Original courses changed",before.courses==restored.courses)
            assertTrue("Original profile changed",before.scopeToken==restored.scopeToken)
        }
    }
}
