/* SPDX-License-Identifier: GPL-3.0-only
 * UI test verifying text notices are rejected in import, zero writes are enforced,
 * and valid JSON import remains functional.
 */
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

@RunWith(AndroidJUnit4::class)
class CampusNoticeImportTest {

    @Test(timeout = 180_000)
    fun plainTextNoticeIsRejectedWithZeroWritesAndJsonRemainsAvailable() {
        val ui = CampusUiDriver()
        ui.launch()
        ui.waitText("本机课表")

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var activity: MainActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
        }
        val repository = checkNotNull(activity).timetableDependencies.repository
        fun snapshot() = runBlocking { withTimeout(10_000) { repository.observeTimetable().first() } }
        val before = snapshot()

        // 1. 进入导入课表，验证已无通知文字入口
        ui.click("导入课表")
        ui.waitText("选择课表文件或图片")
        assertFalse("不得存在通知文字模式", ui.hasText("通知文字"))
        assertFalse("不得存在相对日期基准输入框", ui.hasText("相对日期基准（年-月-日）"))

        // 2. 粘贴普通通知文本进行读取，验证被立即拒绝且报错，底层零写入
        val noticeText = "明日 离散数学测试 10:00-11:30 在测试室 加课"
        ui.enter("粘贴 JSON", noticeText)
        ui.click("读取内容")
        ui.waitText("来源含待核对信息，请对照原文、安排和归属后重新检查。")
        assertEquals("普通通知文本识别失败必须零写入", before, snapshot())

        // 3. 关闭并重新进入导入课表，验证合法 JSON 格式正常读取，预览取消后保持零写入
        ui.click("关闭")
        ui.waitText("本机课表")
        ui.click("导入课表")
        ui.waitText("选择课表文件或图片")

        val name = "结构化导入${SystemClock.uptimeMillis()}"
        val validJson = """{"schemaVersion":1,"courses":[{"key":"c1","name":"$name","meetings":[{"key":"m1","kind":"RECURRING","dayOfWeek":1,"weeks":[1],"sections":[1]}]}]}"""
        ui.enter("粘贴 JSON", validJson)
        ui.waitValue("粘贴 JSON", validJson)
        ui.click("读取内容")
        ui.revealText("核对并预览")
        ui.click("核对并预览")
        ui.waitText("导入预览")
        assertEquals("预览阶段必须零写入", before, snapshot())

        // 取消预览并关闭
        ui.click("返回修改")
        ui.waitText("读取内容")
        ui.click("关闭")
        ui.waitText("本机课表")
        assertEquals("取消后必须保持零写入", before, snapshot())
    }
}
