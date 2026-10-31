package com.aggapp.app

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.aggapp.app.ui.LicenseDocument
import com.aggapp.app.ui.MainActivity
import com.aggapp.app.update.AppUpdate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阶段4 S4-02 许可集中页定向测试（含首轮审查修复）：
 * 1. 验证全部 17 项许可资产离线完整打包且可读取；
 * 2. 验证端到端小屏滚动、离线弹窗与返回恢复（避免顶底视口矛盾）；
 * 3. 验证从许可集中页切换其他 Tab（课表）再返回我的页面的选中态与返回正常。
 */
@RunWith(AndroidJUnit4::class)
class CampusOpenSourceLicensesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before
    fun launchRealApplication() {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -f 0x10008000 -n ${context.packageName}/${MainActivity::class.java.name}",
        )
        val launch = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        assertTrue("Real launcher succeeds: $launch", launch.contains("Status: ok"))
        waitForText("本机课表")
    }

    @Test(timeout = 60_000)
    fun verifyAllLicenseAssetsAreBundledAndReadable() {
        // 静态资产完整性断言：核对旧资产清单 16 项 + 新增 pdfplumber 1 项全部可从 assets 读取
        var totalAssetPaths = 0
        for (doc in LicenseDocument.entries) {
            for (path in doc.assetPaths) {
                totalAssetPaths++
                val stream = context.assets.open(path)
                assertNotNull("Asset must exist and open: $path", stream)
                val text = stream.bufferedReader().use { it.readText() }
                assertTrue("Asset $path must not be empty", text.isNotBlank())
            }
        }
        // 核对包含所有 17 项实际资产
        assertTrue("License assets total count must be at least 17", totalAssetPaths >= 17)

        // 核心法律与版权关键词核验
        val noticeText = context.assets.open("open_source/NOTICE.txt").bufferedReader().use { it.readText() }
        assertTrue("NOTICE text must contain project title", noticeText.contains("校园助手：来源、修改与许可"))

        val mitText = context.assets.open("open_source/MIT-mashangqu.txt").bufferedReader().use { it.readText() }
        assertTrue("MIT text must retain njpemo copyright", mitText.contains("Copyright (c) 2026 njpemo"))

        val gplText = context.assets.open("open_source/GPL-3.0-sleepy.txt").bufferedReader().use { it.readText() }
        assertTrue("GPL text must contain GNU GPL license", gplText.contains("GNU GENERAL PUBLIC LICENSE"))

        val pdfplumberText = context.assets.open("open_source/MIT-pdfplumber.txt").bufferedReader().use { it.readText() }
        assertTrue("pdfplumber text must retain Jeremy Singer-Vine copyright", pdfplumberText.contains("Jeremy Singer-Vine"))
    }

    @Test(timeout = 90_000)
    fun openSourceLicensesScreenEndToEndInteraction() {
        // 1. 进入我的页面
        click("我的")
        waitForText("平台登录与账号管理")
        assertFalse("Old top about card must not exist", visibleTextContains("码上取 · MIT 许可"))

        // 2. 滚到底部点击入口进入许可页
        clickOpenSourceEntry()
        waitForText("开源许可与说明")
        waitForCondition("App update status visible") {
            visibleTextContains(AppUpdate.statusMessage)
        }

        // 3. 小屏滚动适配：向下滚动定位并点击 pdfplumber 查看全文弹窗
        scrollToAndClick("pdfplumber 提取算法 · MIT 许可")
        waitForCondition("pdfplumber copyright displayed in dialog") {
            visibleTextContains("Jeremy Singer-Vine")
        }
        click("关闭")

        // 4. 小屏滚动适配：向下滚动定位并点击 Sleepy GPL-3.0 查看全文弹窗
        scrollToAndClick("Sleepy 与组合程序 · GPL-3.0")
        waitForCondition("GPL license displayed in dialog") {
            visibleTextContains("GNU GENERAL PUBLIC LICENSE")
        }
        click("关闭")

        // 5. 点击顶栏返回按钮返回我的页面
        clickBack()

        // 6. 稳定页面身份 + 底部入口滚动恢复断言（避免视口顶底矛盾）
        waitForCondition("Returned to mine screen and restored bottom scroll position") {
            findNodeByTag("open-source-screen") == null && visibleTextContains("开源许可与说明")
        }
        assertTrue("Debug log export button is visible in bottom viewport", visibleTextContains("导出调试日志（操作/请求/登录态）"))
        waitForCondition("Bottom tab mine remains selected") {
            isBottomTabSelected("mine")
        }

        // 7. 切回课表，保持环境一致
        click("课表")
        waitForText("本机课表")
    }

    @Test(timeout = 90_000)
    fun openSourceTabSwitchToTimetableAndBackPreservesMineTabSelectionAndBackStack() {
        // 1. 进入我的页面并进入开源许可页
        click("我的")
        waitForText("平台登录与账号管理")
        clickOpenSourceEntry()
        waitForText("开源许可与说明")
        waitForCondition("In open source screen with back button visible") {
            findNodeByTag("open-source-back") != null
        }
        waitForCondition("Mine tab should be selected while inside open-source subpage") {
            isBottomTabSelected("mine")
        }

        // 2. 在许可页点击底栏“课表”Tab，验证切换到课表且课表高亮
        click("课表")
        waitForText("本机课表")
        waitForCondition("Timetable tab should be selected") {
            isBottomTabSelected("timetable")
        }

        // 3. 点击底栏“我的”Tab，验证状态恢复：恢复到许可集中页且“我的”Tab保持高亮
        click("我的")
        waitForCondition("Open source subpage state is preserved after switching back") {
            findNodeByTag("open-source-back") != null && visibleTextContains("开源许可与说明")
        }
        waitForCondition("Mine tab should be selected after switching back") {
            isBottomTabSelected("mine")
        }

        // 4. 按系统返回键，从许可页返回到“我的”主页面，底栏“我的”保持选中且调试日志可见
        UiDevice.getInstance(instrumentation).pressBack()
        waitForCondition("Returned to mine screen and bottom controls visible") {
            findNodeByTag("open-source-back") == null && visibleTextContains("导出调试日志（操作/请求/登录态）")
        }
        waitForCondition("Mine tab should remain selected") {
            isBottomTabSelected("mine")
        }

        // 5. 再次按系统返回键，从“我的”主页返回起始目的地（课表）
        UiDevice.getInstance(instrumentation).pressBack()
        waitForText("本机课表")
        waitForCondition("Timetable tab should be selected at start destination") {
            isBottomTabSelected("timetable")
        }
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

    private fun waitForText(text: String) = waitForCondition("Visible text: $text") {
        nodes(text).any { it.text?.toString() == text }
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

    private fun clickOpenSourceEntry() {
        waitForCondition("Open source entry clicked") {
            val node = findNodeByTag("mine-open-source-entry")
            if (node != null && node.isEnabled) {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target != null && target.isEnabled) {
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@waitForCondition true
                }
            }
            val candidates = nodes("开源许可与说明").filter { it.text?.toString() == "开源许可与说明" }
            for (cand in candidates.reversed()) {
                var target: AccessibilityNodeInfo? = cand
                while (target != null && !target.isClickable) target = target.parent
                if (target != null && target.isEnabled) {
                    if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@waitForCondition true
                }
            }
            val scrollable = findScrollableNode()
            scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            false
        }
    }

    private fun clickBack() {
        waitForCondition("Back navigation button in open-source screen") {
            val node = findNodeByTag("open-source-back")
                ?: nodes("返回").firstOrNull { it.contentDescription?.toString() == "返回" }
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

    private fun isBottomTabSelected(route: String): Boolean {
        val node = findNodeByTag("nav-$route") ?: return false
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(node)
        while (pending.isNotEmpty()) {
            val cur = pending.removeFirst()
            if (cur.isSelected || cur.isChecked) return true
            repeat(cur.childCount) { index -> cur.getChild(index)?.let(pending::add) }
        }
        var parent: AccessibilityNodeInfo? = node.parent
        while (parent != null) {
            if (parent.isSelected || parent.isChecked) return true
            parent = parent.parent
        }
        return false
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
            for (cand in candidates.reversed()) {
                var node: AccessibilityNodeInfo? = cand
                while (node != null && !node.isClickable) node = node.parent
                if (node != null && node.isEnabled) {
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
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
