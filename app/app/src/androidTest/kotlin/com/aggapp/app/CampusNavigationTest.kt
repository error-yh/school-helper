package com.aggapp.app

import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import com.aggapp.app.ui.MainActivity
import com.aggapp.app.update.AppUpdate
import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real launcher, Hilt graph, accessible Compose UI and Room; no mocked Activity or storage. */
@RunWith(AndroidJUnit4::class)
class CampusNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun launchRealApplication() {
        // The device rejects background Context.startActivity during instrumentation (START_ABORTED).
        // Use the same shell launcher as a real adb launch; this changes no device security settings.
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -f 0x10008000 -n ${context.packageName}/${MainActivity::class.java.name}",
        )
        val launch = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        assertTrue("Real launcher succeeds: $launch", launch.contains("Status: ok"))
        waitForText("本机课表")
    }

    @Test(timeout = 90_000) fun timetableExpressIdentityAndMineAreReachable() {
        click("快递")
        waitForExpressPage()
        // 快递顶部重复“身份码”已清除，express-page 内部不应包含“身份码”控件
        assertNoIdentityCodeInExpressPage()

        // 底栏一级“身份码”Tab 独立可达并正确定位
        clickBottomTab("idcode")
        waitForIdCodeScreen()

        click("我的")
        waitForText("平台登录与账号管理")
        // 我的页面顶部大许可卡片已移除
        assertFalse("Top about card must be removed from mine screen", visibleTextContains("码上取 · MIT 许可"))

        // 滚到底部点击简洁入口进入独立的开源许可与说明页
        scrollToAndClick("开源许可与说明")
        waitForText("开源许可与说明")
        waitForCondition("App update status is visible") {
            visibleTextContains(AppUpdate.statusMessage)
        }

        // 小屏适配：滚动定位并点击许可项，验证全文弹窗离线展示
        scrollToAndClick("码上取 · MIT 许可")
        waitForText("码上取 · MIT 许可")
        waitForCondition("MIT copyright is bundled and displayed") {
            visibleTextContains("Copyright (c) 2026 njpemo")
        }
        click("关闭")

        // 验证系统返回键回到我的页面：断言页面身份与原底部滚动位置恢复（避免顶底视口矛盾）
        UiDevice.getInstance(instrumentation).pressBack()
        waitForCondition("Returned to mine screen and restored bottom scroll position") {
            findNodeByTag("open-source-screen") == null && visibleTextContains("开源许可与说明")
        }
        assertTrue("Debug log export button is visible in bottom viewport", visibleTextContains("导出调试日志（操作/请求/登录态）"))

        // 切回身份码与课表
        clickBottomTab("idcode")
        waitForIdCodeScreen()
        click("课表")
        waitForText("本机课表")
    }

    @Test(timeout = 90_000) fun fourBottomNavigationTabsCanBeSwitchedRepeatedlyWithoutBreakingState() {
        waitForText("本机课表")
        click("快递")
        waitForExpressPage()
        assertNoIdentityCodeInExpressPage()
        clickBottomTab("idcode")
        waitForIdCodeScreen()
        click("我的")
        waitForText("平台登录与账号管理")
        click("课表")
        waitForText("本机课表")

        // 再次完整循环切换，验证重复切页与状态保存恢复稳健
        click("快递")
        waitForExpressPage()
        assertNoIdentityCodeInExpressPage()
        clickBottomTab("idcode")
        waitForIdCodeScreen()
        click("我的")
        waitForText("平台登录与账号管理")
        click("课表")
        waitForText("本机课表")
    }

    @Test(timeout = 90_000) fun systemBackFromNonStartTabReturnsToTimetableStartDestination() {
        waitForText("本机课表")
        clickBottomTab("idcode")
        waitForIdCodeScreen()
        // 一级 Tab 按系统返回键，默认回到起始目的地（课表）
        UiDevice.getInstance(instrumentation).pressBack()
        waitForText("本机课表")
    }

    @Test(timeout = 90_000) fun realInjectedStorageSurvivesActivityRecreationWithIndependentIdentity() {
        assertEquals("io.github.schoolhelper.app.debug", context.packageName)
        assertFalse(AppUpdate.enabled)
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
        val repository = resumedActivity().timetableDependencies.repository
        val before = runBlocking { withTimeout(5_000) { repository.observeTimetable().first().courses.size } }
        val name = "依赖注入验收${SystemClock.uptimeMillis()}"
        val id = runBlocking { repository.saveCourse(CourseDraft(
            courseName = name,
            meetings = listOf(CourseMeeting(day = 1, weeks = setOf(1), sections = setOf(1))),
        )) }
        try {
            click("课程")
            waitForText(name)
            val previous = resumedActivity()
            instrumentation.runOnMainSync { previous.recreate() }
            waitForCondition("Activity recreation completes") {
                runCatching { resumedActivity() !== previous }.getOrDefault(false)
            }
            // The selected course tab is restored. Verify its data directly after recreation.
            waitForText(name)
            assertEquals(before + 1, runBlocking {
                withTimeout(5_000) { resumedActivity().timetableDependencies.repository.observeTimetable().first().courses.size }
            })
        } finally {
            // Remove only this synthetic row; preserve the user's courses and profile.
            runBlocking { repository.deleteCourse(id) }
        }
    }

    private fun resumedActivity(): MainActivity {
        var result: MainActivity? = null
        instrumentation.runOnMainSync {
            result = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()
        }
        return checkNotNull(result) { "MainActivity is not RESUMED" }
    }

    private fun nodes(text: String): List<AccessibilityNodeInfo> {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add)
        val matches = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        while (pending.isNotEmpty()) {
            check(++visited <= 2_048) { "Unexpectedly large accessibility tree" }
            val node = pending.removeFirst()
            if (node.packageName?.toString() == context.packageName && node.isVisibleToUser &&
                node.text?.contains(text) == true) matches += node
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        return matches
    }

    private fun visibleTextContains(text: String) = nodes(text).any { it.text?.contains(text) == true }

    private fun waitForIdCodeScreen() = waitForCondition("Identity code screen loaded") {
        visibleTextContains("全部平台码") || visibleTextContains("暂无身份码")
    }

    private fun waitForText(text: String) = waitForCondition("Visible text: $text") {
        nodes(text).any { it.text?.toString() == text }
    }

    private fun waitForExpressPage() = waitForCondition("Express page container is present") {
        findNodeByTag("express-page") != null
    }

    private fun assertNoIdentityCodeInExpressPage() {
        val root = checkNotNull(findNodeByTag("express-page")) { "express-page node not found" }
        assertFalse(
            "express-page subtree must not contain duplicate identity code button",
            hasTextWithinSubtree(root, "身份码"),
        )
    }

    private fun findNodeByTag(tag: String): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add)
        var visited = 0
        while (pending.isNotEmpty()) {
            check(++visited <= 2_048) { "Unexpectedly large accessibility tree" }
            val node = pending.removeFirst()
            val id = node.viewIdResourceName
            if (id == tag || id?.endsWith(":id/$tag") == true) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        return null
    }

    private fun hasTextWithinSubtree(root: AccessibilityNodeInfo, text: String): Boolean {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.text?.toString() == text) return true
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        return false
    }

    private fun clickBottomTab(route: String) {
        val tag = "nav-$route"
        val label = when (route) {
            "timetable" -> "课表"
            "express" -> "快递"
            "idcode" -> "身份码"
            "mine" -> "我的"
            else -> route
        }
        waitForCondition("Bottom navigation tab: $tag or text $label") {
            val node = findNodeByTag(tag) ?: nodes(label).firstOrNull()
            if (node != null && node.isEnabled) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target != null && target.isEnabled) {
                    return@waitForCondition target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            false
        }
    }

    private fun click(text: String) {
        waitForCondition("Fresh clickable control: $text") {
            val candidates = nodes(text).filter { it.text?.toString() == text }
            for (cand in candidates.reversed()) {
                var node: AccessibilityNodeInfo? = cand
                while (node != null && !node.isClickable) node = node.parent
                if (node != null && node.isEnabled) {
                    return@waitForCondition node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            }
            false
        }
    }

    private fun scrollToAndClick(text: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val candidates = nodes(text).filter { it.text?.toString() == text }
            if (candidates.isNotEmpty()) {
                for (cand in candidates.reversed()) {
                    var node: AccessibilityNodeInfo? = cand
                    while (node != null && !node.isClickable) node = node.parent
                    if (node != null && node.isEnabled) {
                        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                    }
                }
            }
            val scrollable = findScrollableNode()
            if (scrollable != null) {
                scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            SystemClock.sleep(200)
        }
        throw AssertionError("Timed out scrolling to and clicking: $text")
    }

    private fun findScrollableNode(): AccessibilityNodeInfo? {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isScrollable) return node
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        return null
    }

    private fun waitForCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timed out: $description")
    }
}
