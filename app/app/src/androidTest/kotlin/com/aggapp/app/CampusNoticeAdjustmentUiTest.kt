/* SPDX-License-Identifier: GPL-3.0-only
 * UI test for the Notice Adjustment confirmation and history flow.
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
    fun noticeAdjustmentScreenNavigationAndReturn() {
        val ui = CampusUiDriver()
        ui.launch()
        ui.waitText("本机课表")

        // 验证主界面上有“文字通知调课”入口并点击进入
        ui.click("文字通知调课")
        ui.waitText("粘贴教务通知或调课文字")
        ui.waitText("日期依据与学期基准")
        ui.waitText("填入今天")

        // 点击页面左上角返回按钮回到主课表
        ui.clickDescription("返回")
        ui.waitText("本机课表")
        assertTrue(ui.hasText("文字通知调课"))
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
    }

    @Test(timeout = 180_000)
    fun importNoticeSwitchToNoticeAdjustment() {
        val ui = CampusUiDriver()
        ui.launch()
        ui.waitText("本机课表")

        // 进入导入课表
        ui.click("导入课表")
        ui.waitText("选择课表文件或图片")

        // 选择通知文字模式
        ui.click("通知文字")
        ui.waitText("前往文字通知调课 →")

        // 点击切换按钮，直接跳转至文字通知调课界面
        ui.click("前往文字通知调课 →")
        ui.waitText("文字通知调课")
        ui.waitText("粘贴教务通知或调课文字")

        // 返回
        ui.clickDescription("返回")
        ui.waitText("本机课表")
    }

    /**
     * 门槛0验证项1：通过无障碍系统全局返回（GLOBAL_ACTION_BACK）从文字通知调课页返回课表主页。
     * 绝不使用 onBackPressedDispatcher 内部调用兜底，分发失败直接断言失败。
     */
    @Test(timeout = 180_000)
    fun noticeAdjustmentScreenGlobalBackReturnsToTimetable() {
        val ui = CampusUiDriver()
        ui.launch()
        ui.waitText("本机课表")

        ui.click("文字通知调课")
        ui.waitText("粘贴教务通知或调课文字")

        // 纯净触发真实系统无障碍全局返回键
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val globalBackTriggered = instrumentation.uiAutomation.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_BACK
        )
        assertTrue("System GLOBAL_ACTION_BACK failed to dispatch on device", globalBackTriggered)

        ui.waitText("本机课表")
        assertTrue(ui.hasText("文字通知调课"))
    }

    /**
     * 门槛0验证项2：通过无障碍系统全局返回（GLOBAL_ACTION_BACK）从调课记录页返回课表主页。
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
    }

    /**
     * 内部 Dispatcher 返回独立测试：验证文字通知调课页响应 Activity onBackPressedDispatcher 回调。
     */
    @Test(timeout = 180_000)
    fun noticeAdjustmentScreenDispatcherBackReturnsToTimetable() {
        val ui = CampusUiDriver()
        ui.launch()
        ui.waitText("本机课表")

        ui.click("文字通知调课")
        ui.waitText("粘贴教务通知或调课文字")

        val activity = resumedActivity()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            activity.onBackPressedDispatcher.onBackPressed()
        }

        ui.waitText("本机课表")
        assertTrue(ui.hasText("文字通知调课"))
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
