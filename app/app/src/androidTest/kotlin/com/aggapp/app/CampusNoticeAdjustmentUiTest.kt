/* SPDX-License-Identifier: GPL-3.0-only
 * UI test for the Adjustment history flow and verification of removed text notice entry points.
 * Uses synthetic courses only; no sensitive personal/timetable data is used.
 * Strictly separates system global accessibility back navigation from internal dispatcher back navigation.
 */
package com.aggapp.app

import android.accessibilityservice.AccessibilityService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aggapp.app.ui.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampusNoticeAdjustmentUiTest {

    @Test(timeout = 180_000)
    fun noticeAdjustmentEntryPointsDoNotExist() {
        val ui = CampusUiDriver()
        ui.launch()
        ui.waitText("本机课表")

        // 验证主界面上不存在“文字通知调课”入口
        assertFalse("课表主页不得存在文字通知调课入口", ui.hasText("文字通知调课"))
        assertTrue("课表主页保留导入课表入口", ui.hasText("导入课表"))
        assertTrue("课表主页保留调整记录入口", ui.hasText("调整记录"))

        // 进入导入课表，验证不存在“通知文字”模式及跳转按钮
        ui.click("导入课表")
        ui.waitText("选择课表文件或图片")
        assertFalse("导入页不得存在通知文字模式", ui.hasText("通知文字"))
        assertFalse("导入页不得存在前往文字通知调课跳转按钮", ui.hasText("前往文字通知调课 →"))
        assertFalse("导入页不得存在纯文字文件选项", ui.hasText("文字文件"))

        // 点击页面右上角关闭按钮回到主课表
        ui.click("关闭")
        ui.waitText("本机课表")
        assertFalse("关闭导入后课表主页不得存在文字通知调课入口", ui.hasText("文字通知调课"))
    }

    @Test(timeout = 180_000)
    fun noticeHistoryScreenNavigationAndEmptyState() {
        val ui = CampusUiDriver()
        ui.launch()
        ui.waitText("本机课表")

        // 验证主界面上有“调整记录”入口并点击进入
        ui.click("调整记录")
        ui.waitText("调整记录与撤销")

        // 点击页面左上角返回按钮回到主课表
        ui.clickDescription("返回")
        ui.waitText("本机课表")
        assertTrue(ui.hasText("调整记录"))
        assertFalse(ui.hasText("文字通知调课"))
    }

    /**
     * 门槛0验证项：通过无障碍系统全局返回（GLOBAL_ACTION_BACK）从调课记录页返回课表主页。
     * 绝不使用 onBackPressedDispatcher 内部调用兜底，分发失败直接断言失败。
     */
    @Test(timeout = 180_000)
    fun noticeHistoryScreenGlobalBackReturnsToTimetable() {
        val ui = CampusUiDriver()
        ui.launch()
        ui.waitText("本机课表")

        ui.click("调整记录")
        ui.waitText("调整记录与撤销")

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val globalBackTriggered = instrumentation.uiAutomation.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_BACK
        )
        assertTrue("System GLOBAL_ACTION_BACK failed to dispatch on device", globalBackTriggered)

        ui.waitText("本机课表")
        assertTrue(ui.hasText("调整记录"))
        assertFalse(ui.hasText("文字通知调课"))
    }

    /**
     * 内部 Dispatcher 返回独立测试：验证调课记录页响应 Activity onBackPressedDispatcher 回调。
     */
    @Test(timeout = 180_000)
    fun noticeHistoryScreenDispatcherBackReturnsToTimetable() {
        val ui = CampusUiDriver()
        ui.launch()
        ui.waitText("本机课表")

        ui.click("调整记录")
        ui.waitText("调整记录与撤销")

        val activity = resumedActivity()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            activity.onBackPressedDispatcher.onBackPressed()
        }

        ui.waitText("本机课表")
        assertTrue(ui.hasText("调整记录"))
        assertFalse(ui.hasText("文字通知调课"))
    }

    private fun resumedActivity(): MainActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var activity: MainActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
        }
        return checkNotNull(activity)
    }
}
