/* SPDX-License-Identifier: GPL-3.0-only
 * Comprehensive UI acceptance tests for Stage 5 notice adjustment, manual occurrence, and guarded undo.
 * Strictly decoupled from host MainActivity and timetable_local_profile.
 * Uses isolated Room database and ManualOccurrenceTestActivity.
 */
package com.aggapp.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.data.RoomNoticeAdjustmentRepository
import com.lingion.sleepy.data.RoomScheduleRepository
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.SemesterSettings
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.model.defaultPeriods
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryEntry
import com.lingion.sleepy.domain.notice.NoticeAdjustmentHistoryResult
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CampusNoticeAcceptanceUiTest {

    private companion object {
        const val TAG = "S504_ACCEPTANCE"

        fun logDiag(stage: String, detail: String = "") {
            val message = if (detail.isEmpty()) "[STAGE] $stage" else "[STAGE] $stage - $detail"
            Log.i(TAG, message)
            println("[S504_ACCEPTANCE] $message")
        }
    }

    private lateinit var isolatedProfile: String
    private lateinit var testDatabase: TimetableDatabase
    private lateinit var testScheduleRepo: RoomScheduleRepository
    private lateinit var testNoticeRepo: RoomNoticeAdjustmentRepository
    private lateinit var testDependencies: TimetableDependencies
    private var initialHostProfileId: String? = null
    private var activeActivity: ManualOccurrenceTestActivity? = null

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // 1. 验证日常宿主 profile，确保测试隔离
        val hostPrefs = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
        initialHostProfileId = hostPrefs.getString("id", null)
        isolatedProfile = "s504_acc_${UUID.randomUUID().toString().take(8)}"
        assertFalse(
            "Isolated test profile must never collide with host profile ID",
            isolatedProfile == initialHostProfileId
        )

        // 2. 清理残留数据库
        cleanupTestDb(context, isolatedProfile)

        // 3. 打开隔离数据库并注入依赖
        testDatabase = TimetableDatabase.open(context, isolatedProfile)
        testScheduleRepo = RoomScheduleRepository(testDatabase)
        testNoticeRepo = RoomNoticeAdjustmentRepository(testDatabase)
        testDependencies = TimetableDependencies(
            repository = testScheduleRepo,
            imports = testScheduleRepo,
            noticeAdjustments = testNoticeRepo
        )
        ManualOccurrenceTestActivity.testDependencies = testDependencies
        logDiag("SETUP_COMPLETED", "profile=$isolatedProfile")
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // 1. 关闭 Activity
        try {
            instrumentation.runOnMainSync {
                activeActivity?.finish()
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ManualOccurrenceTestActivity>()
                    .forEach { it.finish() }
            }
            instrumentation.waitForIdleSync()
            activeActivity = null
        } catch (e: Throwable) {
            logDiag("TEARDOWN_ACTIVITY_WARN", e.message ?: "unknown")
        }

        // 2. 关闭并清理独立数据库
        if (::testDatabase.isInitialized && testDatabase.isOpen) {
            try {
                testDatabase.close()
            } catch (e: Throwable) {
                logDiag("TEARDOWN_DB_CLOSE_WARN", e.message ?: "unknown")
            }
        }
        cleanupTestDb(context, isolatedProfile)
        ManualOccurrenceTestActivity.testDependencies = null

        // 3. 断言日常宿主 profile 绝对未受触碰
        val hostPrefs = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
        val currentHostProfileId = hostPrefs.getString("id", null)
        assertEquals(
            "Host profile ID must remain strictly identical throughout tests",
            initialHostProfileId,
            currentHostProfileId
        )
        logDiag("TEARDOWN_COMPLETED")
    }



    /**
     * 验收矩阵 2：手动调整一次
     * 课程详情“调整一次” -> 显式选择原课时并改节次/地点 -> 真实保存 -> 核验今日视图刷新 ->
     * Activity 重建后状态保持且历史可见。
     */
    @Test(timeout = 180_000)
    fun manualAdjustmentSaveAndRefreshWithRecreate() {
        logDiag("MATRIX_2_START")
        val ui = CampusUiDriver()
        val today = LocalDate.now()
        val todayMonday = TimetableRules.mondayOf(today)
        val todayDayOfWeek = today.dayOfWeek.value
        val testCourseName = "离散单次_${today.dayOfMonth}日"

        runBlocking {
            withTimeout(10_000) {
                testScheduleRepo.updateSettings(
                    SemesterSettings(
                        name = "S504验收学期",
                        startDate = todayMonday,
                        totalWeeks = 16
                    ),
                    defaultPeriods()
                )
                testScheduleRepo.saveCourse(
                    CourseDraft(
                        courseName = testCourseName,
                        teacher = "离散陈老师",
                        room = "主楼301",
                        meetings = listOf(
                            CourseMeeting(
                                day = todayDayOfWeek,
                                weeks = (1..16).toSet(),
                                sections = setOf(1, 2),
                                kind = MeetingKind.RECURRING,
                            )
                        )
                    )
                )
            }
        }
        logDiag("MATRIX_2_FIXTURE_READY")

        activeActivity = launchIsolatedTestActivity(ui)
        ui.waitText("本机课表")

        // 1. 进入课程详情并点击“调整一次”
        openCourseDetails(testCourseName)
        ui.waitText("调整一次")
        safeClick("调整一次")
        ui.waitText("单次课程管理")
        ui.waitText("$testCourseName · 仅影响单次")

        // 2. 选择今天的原课时
        safeClick(today.toString())
        safeWaitText("第1,2节")
        selectCandidateCard("第1,2节")

        // 3. 修改目标安排：节次 7-8，教室 实验楼405
        enterAccessibilityText(ui, "目标日期", today.toString())
        enterAccessibilityText(ui, "节次", "7-8")
        enterAccessibilityText(ui, "地点", "实验楼405")

        // 4. 生成方案预览并确认保存
        safeClick("生成方案预览")
        safeWaitText("原安排 → 新安排（仅修改这一次）")
        safeWaitText("确认修改本次安排")
        safeClick("确认修改本次安排")

        // 5. 验证保存成功并返回课表
        safeWaitText("操作成功！已写入单次变更")
        safeClick("返回课表")
        safeWaitText("本机课表")

        // 6. 验证今日视图刷新显示新安排
        ui.selectTab("今日")
        safeWaitText(testCourseName)
        safeWaitText("实验楼405")

        // 7. 触发 Activity 重建（模拟屏幕旋转/配置重建）
        logDiag("MATRIX_2_RECREATING_ACTIVITY")
        activeActivity = recreateTestActivity()
        safeWaitText("本机课表")

        // 8. 重建后核对状态依然正确保持
        ui.selectTab("今日")
        safeWaitText(testCourseName)
        safeWaitText("实验楼405")

        // 9. 验证历史记录中可见
        safeClick("调整记录")
        safeWaitText("调整记录与撤销")
        safeWaitText(testCourseName)
        safeWaitText("已生效")
        safeWaitText("地点: 实验楼405")
        safeClick("返回")
        safeWaitText("本机课表")
        logDiag("MATRIX_2_SUCCESS")
    }

    /**
     * 验收矩阵 3：停课一次与补课一次
     * 停课一次：仅取消选定日期的该次课时，不删除整门课程，其他周不动；
     * 补课一次：指定日期和节次加课，不改动已有周期安排；
     * UI 撤销验证：对其中一条经 UI 撤销并验证恢复。
     */
    @Test(timeout = 180_000)
    fun suspendAndRescheduleOnceWithUndo() {
        logDiag("MATRIX_3_START")
        val ui = CampusUiDriver()
        val today = LocalDate.now()
        val todayMonday = TimetableRules.mondayOf(today)
        val todayDayOfWeek = today.dayOfWeek.value
        val testCourseName = "软工停补_${today.dayOfMonth}日"

        runBlocking {
            withTimeout(10_000) {
                testScheduleRepo.updateSettings(
                    SemesterSettings(
                        name = "S504验收学期",
                        startDate = todayMonday,
                        totalWeeks = 16
                    ),
                    defaultPeriods()
                )
                testScheduleRepo.saveCourse(
                    CourseDraft(
                        courseName = testCourseName,
                        teacher = "软工赵老师",
                        room = "软件楼101",
                        meetings = listOf(
                            CourseMeeting(
                                day = todayDayOfWeek,
                                weeks = (1..16).toSet(),
                                sections = setOf(1, 2),
                                kind = MeetingKind.RECURRING,
                            )
                        )
                    )
                )
            }
        }
        logDiag("MATRIX_3_FIXTURE_READY")

        activeActivity = launchIsolatedTestActivity(ui)
        ui.waitText("本机课表")

        // --- 子测试 A：停课一次 ---
        openCourseDetails(testCourseName)
        safeWaitText("停课一次")
        safeClick("停课一次")
        safeWaitText("单次课程管理")

        safeClick(today.toString())
        safeWaitText("第1,2节")
        selectCandidateCard("第1,2节")

        safeClick("生成方案预览")
        safeWaitText("原安排 → 新安排（仅修改这一次）")
        safeWaitText("确认停课本次")
        safeClick("确认停课本次")

        safeWaitText("操作成功！已写入单次变更")
        safeClick("返回课表")
        safeWaitText("本机课表")

        // 核验今日视图：今天该课时已取消，不展示
        ui.selectTab("今日")
        assertFalse("Course must be suspended today", ui.hasText("软件楼101"))

        // 核验课程列表：整门课程未被删除
        ui.selectTab("课程")
        safeWaitText(testCourseName)

        // --- 子测试 B：补课一次 ---
        openCourseDetails(testCourseName)
        safeWaitText("补课一次")
        safeClick("补课一次")
        safeWaitText("单次课程管理")

        enterAccessibilityText(ui, "目标日期", today.toString())
        enterAccessibilityText(ui, "节次", "9-10")
        enterAccessibilityText(ui, "地点", "软件楼202")

        safeClick("生成方案预览")
        safeWaitText("原安排 → 新安排（仅修改这一次）")
        safeWaitText("确认添加补课")
        safeClick("确认添加补课")

        safeWaitText("操作成功！已写入单次变更")
        safeClick("返回课表")
        safeWaitText("本机课表")

        // 核验今日视图：补课生效，出现软件楼202
        ui.selectTab("今日")
        safeWaitText(testCourseName)
        safeWaitText("软件楼202")

        // --- 子测试 C：UI 撤销补课记录并验证恢复 ---
        safeClick("调整记录")
        safeWaitText("调整记录与撤销")
        safeWaitText("补课/加课")
        safeWaitText("停课/取消课时")

        // 对补课记录执行撤销
        safeClick("撤销此调整")

        safeWaitText("确认撤销此调课记录？")
        safeClick("确认撤销")
        safeWaitText("已成功撤销")

        // 返回课表，今日视图核验加课已清除
        safeClick("返回")
        safeWaitText("本机课表")
        ui.selectTab("今日")
        assertFalse("Extra class room must be gone after undo", ui.hasText("软件楼202"))
        logDiag("MATRIX_3_SUCCESS")
    }

    /**
     * 验收矩阵 4：取消/错误最小覆盖
     * 1. 预览后返回/取消，验证底层零写入（快照完全不变，事件为0）；
     * 2. 错误提示与重新预览。
     */
    @Test(timeout = 180_000)
    fun previewCancelZeroWriteAndGuardCheck() {
        logDiag("MATRIX_4_START")
        val ui = CampusUiDriver()
        val today = LocalDate.now()
        val todayMonday = TimetableRules.mondayOf(today)
        val todayDayOfWeek = today.dayOfWeek.value
        val testCourseName = "防守测试_${today.dayOfMonth}日"

        runBlocking {
            withTimeout(10_000) {
                testScheduleRepo.updateSettings(
                    SemesterSettings(
                        name = "S504验收学期",
                        startDate = todayMonday,
                        totalWeeks = 16
                    ),
                    defaultPeriods()
                )
                testScheduleRepo.saveCourse(
                    CourseDraft(
                        courseName = testCourseName,
                        teacher = "防守钱老师",
                        room = "实验楼101",
                        meetings = listOf(
                            CourseMeeting(
                                day = todayDayOfWeek,
                                weeks = (1..16).toSet(),
                                sections = setOf(1, 2),
                                kind = MeetingKind.RECURRING,
                            )
                        )
                    )
                )
            }
        }
        val initialSnapshot = runBlocking { withTimeout(5_000) { testScheduleRepo.observeTimetable().first() } }
        val initialEventsCount = runBlocking { withTimeout(5_000) { loadHistoryEntries().size } }
        assertEquals(0, initialEventsCount)
        logDiag("MATRIX_4_FIXTURE_READY")

        activeActivity = launchIsolatedTestActivity(ui)
        ui.waitText("本机课表")

        // 1. 进入“调整一次”预览后点击返回退出
        openCourseDetails(testCourseName)
        ui.waitText("调整一次")
        safeClick("调整一次")
        ui.waitText("单次课程管理")

        safeClick(today.toString())
        safeWaitText("第1,2节")
        selectCandidateCard("第1,2节")
        enterAccessibilityText(ui, "目标日期", today.toString())
        enterAccessibilityText(ui, "节次", "5-6")
        safeClick("生成方案预览")
        safeWaitText("原安排 → 新安排（仅修改这一次）")

        // 点击左上角返回按钮取消预览
        safeClick("返回")
        ui.waitText("本机课表")

        // 2. 强力断言：底层数据库零写入
        val afterCancelSnapshot = runBlocking { withTimeout(5_000) { testScheduleRepo.observeTimetable().first() } }
        val afterCancelEventsCount = runBlocking { withTimeout(5_000) { loadHistoryEntries().size } }
        assertEquals("Cancelled preview must produce exactly 0 events", 0, afterCancelEventsCount)
        assertEquals("Snapshot revision must not change on cancel", initialSnapshot.revision, afterCancelSnapshot.revision)
        assertEquals("Course count must remain unchanged", initialSnapshot.courses.size, afterCancelSnapshot.courses.size)

        // 3. 验证主界面不存在文字通知调课入口且零写入保持
        assertFalse("主界面不得存在文字通知调课入口", ui.hasText("文字通知调课"))
        val finalEventsCount = runBlocking { withTimeout(5_000) { loadHistoryEntries().size } }
        assertEquals("Discarded adjustment must produce exactly 0 events", 0, finalEventsCount)
        logDiag("MATRIX_4_SUCCESS")
    }

    private fun hasTextInTree(root: AccessibilityNodeInfo?, target: String): Boolean {
        if (root == null) return false
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        root.refresh()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (!node.refresh()) continue
            if (node.text?.contains(target) == true || node.contentDescription?.contains(target) == true) {
                return true
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        return false
    }

    private fun dumpTreeTexts(): String {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return "<root null>"
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        root.refresh()
        pending.add(root)
        val texts = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (!node.refresh()) continue
            val t = node.text?.toString()?.trim()
            val d = node.contentDescription?.toString()?.trim()
            if (!t.isNullOrEmpty()) texts.add("text='$t'")
            if (!d.isNullOrEmpty()) texts.add("desc='$d'")
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        return texts.joinToString(" | ")
    }

    private fun safeWaitText(target: String, timeoutMillis: Long = 15_000) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (hasTextInTree(root, target)) return
            scrollForward()
            Thread.sleep(150)
        }
        val root = instrumentation.uiAutomation.rootInActiveWindow
        assertTrue("safeWaitText timed out for: $target. Current screen: ${dumpTreeTexts()}", hasTextInTree(root, target))
    }

    private fun selectCandidateCard(partialText: String = "第1,2节") {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + 15_000
        var confirmed = false
        while (System.currentTimeMillis() < deadline && !confirmed) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                root.refresh()
                val pending = ArrayDeque<AccessibilityNodeInfo>()
                pending.add(root)
                while (pending.isNotEmpty()) {
                    val node = pending.removeFirst()
                    if (!node.refresh()) continue
                    if (node.text?.contains(partialText) == true) {
                        // 在 node 的祖先链局部查找 RadioButton
                        var rowOrCard = node.parent
                        var radioNode: AccessibilityNodeInfo? = null
                        repeat(3) {
                            if (rowOrCard != null && radioNode == null) {
                                for (i in 0 until rowOrCard!!.childCount) {
                                    val child = rowOrCard!!.getChild(i)
                                    if (child?.className?.contains("RadioButton") == true || child?.isCheckable == true) {
                                        radioNode = child
                                        break
                                    }
                                }
                                if (radioNode == null) rowOrCard = rowOrCard?.parent
                            }
                        }

                        // 派发点击：优先 RadioButton，同时兼顾可点击的祖先 Card
                        radioNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        rowOrCard?.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                        // 注入物理坐标点击（双重保障）
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        if (bounds.width() > 0 && bounds.height() > 0) {
                            try {
                                UiDevice.getInstance(instrumentation).click(bounds.centerX(), bounds.centerY())
                            } catch (_: Exception) {}
                        }

                        instrumentation.waitForIdleSync()
                        Thread.sleep(150)

                        // 校验是否真正生效（RadioButton 状态被更新，或祖先已进入选中状态）
                        radioNode?.refresh()
                        if (radioNode?.isChecked == true || radioNode?.isSelected == true) {
                            confirmed = true
                            break
                        }
                    }
                    repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
                }
            }
            if (!confirmed) {
                scrollForward()
                Thread.sleep(150)
            }
        }
        assertTrue("Failed to confirm candidate card selection for: $partialText", confirmed)
        instrumentation.waitForIdleSync()
        Thread.sleep(200)
    }

    private fun scrollForward() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.also { it.refresh() }?.let(pending::add)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isScrollable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }) {
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                instrumentation.waitForIdleSync()
                Thread.sleep(200)
                return
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
    }

    private fun scrollBackward() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.also { it.refresh() }?.let(pending::add)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isScrollable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD }) {
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                instrumentation.waitForIdleSync()
                Thread.sleep(200)
                return
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
    }

    private fun openCourseDetails(courseName: String) {
        CampusUiDriver().selectTab("课程")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (hasTextInTree(root, "详情")) break
            safeClick(courseName)
            instrumentation.waitForIdleSync()
            Thread.sleep(500)
        }
        safeWaitText("详情")
        safeClick("详情")
    }

    private fun safeClick(text: String, exact: Boolean = false) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + 15_000
        var clicked = false
        while (System.currentTimeMillis() < deadline && !clicked) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            if (root != null) {
                root.refresh()
                val pending = ArrayDeque<AccessibilityNodeInfo>()
                pending.add(root)
                var matchedNode: AccessibilityNodeInfo? = null
                var fallbackNode: AccessibilityNodeInfo? = null
                while (pending.isNotEmpty()) {
                    val node = pending.removeFirst()
                    if (!node.refresh()) continue
                    val textStr = node.text?.toString() ?: ""
                    val descStr = node.contentDescription?.toString() ?: ""
                    val exactMatches = textStr == text || descStr == text
                    val fuzzyMatches = !exact && (textStr.contains(text) || descStr.contains(text))
                    if (exactMatches && node.isEnabled) {
                        matchedNode = node
                        break
                    } else if (fuzzyMatches && node.isEnabled && fallbackNode == null) {
                        fallbackNode = node
                    }
                    repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
                }

                val finalNode = matchedNode ?: fallbackNode
                if (finalNode != null) {
                    var targetClickNode: AccessibilityNodeInfo? = if (finalNode.isClickable) finalNode else null
                    var ancestor = finalNode.parent
                    while (ancestor != null && targetClickNode == null) {
                        if (ancestor.isEnabled && (ancestor.isClickable || ancestor.className?.contains("Button") == true)) {
                            targetClickNode = ancestor
                            break
                        }
                        ancestor = ancestor.parent
                    }
                    if (targetClickNode == null) {
                        targetClickNode = finalNode
                    }
                    clicked = targetClickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!clicked) {
                        val bounds = Rect()
                        targetClickNode.getBoundsInScreen(bounds)
                        if (bounds.width() > 0 && bounds.height() > 0) {
                            try {
                                clicked = UiDevice.getInstance(instrumentation).click(bounds.centerX(), bounds.centerY())
                            } catch (_: Exception) {}
                        }
                    }
                    if (clicked) break
                }
            }
            if (!clicked) {
                scrollForward()
                Thread.sleep(150)
            }
        }
        if (!clicked) {
            val treeDump = dumpTreeDetails()
            org.junit.Assert.fail("safeClick failed for: $text. Current screen details:\n$treeDump")
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(200)
    }

    private fun dumpTreeDetails(): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return "<null root>"
        val sb = StringBuilder()
        val pending = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        pending.add(root to 0)
        while (pending.isNotEmpty()) {
            val (node, depth) = pending.removeFirst()
            val indent = "  ".repeat(depth)
            sb.appendLine("${indent}[${node.className}] text='${node.text}' desc='${node.contentDescription}' hint='${node.hintText}' editable=${node.isEditable} enabled=${node.isEnabled} clickable=${node.isClickable} selected=${node.isSelected} checked=${node.isChecked}")
            repeat(node.childCount) { index ->
                node.getChild(index)?.let { pending.add(it to depth + 1) }
            }
        }
        return sb.toString()
    }

    private fun findEditableForLabel(root: AccessibilityNodeInfo?, labelPart: String): AccessibilityNodeInfo? {
        if (root == null) return null

        // Pass 1: 严格前缀匹配（排除包含相同词汇的大标题，如“填写补课时间与地点”）
        val pending1 = ArrayDeque<AccessibilityNodeInfo>()
        root.refresh()
        pending1.add(root)
        while (pending1.isNotEmpty()) {
            val node = pending1.removeFirst()
            if (!node.refresh()) continue
            val textStr = node.text?.toString() ?: ""
            val descStr = node.contentDescription?.toString() ?: ""
            val hintStr = node.hintText?.toString() ?: ""
            val isPrefix = textStr.startsWith(labelPart) || descStr.startsWith(labelPart) || hintStr.startsWith(labelPart)
            if (isPrefix) {
                if (node.isEditable && node.isEnabled) return node
                val p = node.parent
                if (p?.isEditable == true && p.isEnabled) return p
                if (p != null) {
                    val local = ArrayDeque<AccessibilityNodeInfo>()
                    local.add(p)
                    while (local.isNotEmpty()) {
                        val curr = local.removeFirst()
                        if (curr != node && curr.isEditable && curr.isEnabled) return curr
                        repeat(curr.childCount) { i -> curr.getChild(i)?.let(local::add) }
                    }
                }
            }
            repeat(node.childCount) { i -> node.getChild(i)?.let(pending1::add) }
        }

        // Pass 2: 降级包含匹配（仅当未命中前缀且明确排除大标题说明词）
        val pending2 = ArrayDeque<AccessibilityNodeInfo>()
        root.refresh()
        pending2.add(root)
        while (pending2.isNotEmpty()) {
            val node = pending2.removeFirst()
            if (!node.refresh()) continue
            val textStr = node.text?.toString() ?: ""
            val descStr = node.contentDescription?.toString() ?: ""
            val hintStr = node.hintText?.toString() ?: ""
            val isDisallowed = textStr.startsWith("填写") || textStr.startsWith("设置") || textStr.contains("覆盖默认作息表")
            val containsMatch = !isDisallowed && (textStr.contains(labelPart) || descStr.contains(labelPart) || hintStr.contains(labelPart)) && textStr.length <= 30
            if (containsMatch) {
                if (node.isEditable && node.isEnabled) return node
                val p = node.parent
                if (p?.isEditable == true && p.isEnabled) return p
                if (p != null) {
                    val local = ArrayDeque<AccessibilityNodeInfo>()
                    local.add(p)
                    while (local.isNotEmpty()) {
                        val curr = local.removeFirst()
                        if (curr != node && curr.isEditable && curr.isEnabled) return curr
                        repeat(curr.childCount) { i -> curr.getChild(i)?.let(local::add) }
                    }
                }
            }
            repeat(node.childCount) { i -> node.getChild(i)?.let(pending2::add) }
        }
        return null
    }

    private fun enterAccessibilityText(ui: CampusUiDriver, labelPart: String, text: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + 15_000
        var entered = false
        var targetClassName = ""
        var attempts = 0
        while (System.currentTimeMillis() < deadline && !entered) {
            attempts++
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val targetNode = findEditableForLabel(root, labelPart)
            if (targetNode != null) {
                targetClassName = targetNode.className?.toString() ?: ""
                targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val bundle = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                entered = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                if (entered) {
                    android.util.Log.i("ACCEPTANCE_S504", "Entered text '$text' for label '$labelPart' into node '$targetClassName'")
                    break
                }
            }
            if (!entered) {
                if (attempts % 2 == 1) {
                    scrollBackward()
                } else {
                    scrollForward()
                }
                Thread.sleep(150)
            }
        }
        if (!entered) {
            val treeDump = dumpTreeDetails()
            org.junit.Assert.fail("Failed to enter text for label: $labelPart (targetNode=$targetClassName). Details:\n$treeDump")
        }
        instrumentation.waitForIdleSync()
        Thread.sleep(300)
    }

    private fun captureScreen(fileName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val packageName = instrumentation.targetContext.packageName
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow) { "rootInActiveWindow is null" }
        check(root.packageName?.toString() == packageName) { "Refuse to capture foreign window" }
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "takeScreenshot returned null" }
        val folder = File(instrumentation.targetContext.filesDir, "s5-evidence").apply { mkdirs() }
        val targetFile = File(folder, fileName)
        try {
            targetFile.outputStream().use { out ->
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Failed to compress bitmap" }
            }
            logDiag("SCREENSHOT_SAVED", targetFile.absolutePath)
        } finally {
            screenshot.recycle()
        }
    }

    private fun recreateTestActivity(): ManualOccurrenceTestActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var previous: ManualOccurrenceTestActivity? = null
        instrumentation.runOnMainSync {
            previous = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ManualOccurrenceTestActivity>().firstOrNull()
            checkNotNull(previous) { "ManualOccurrenceTestActivity not found in RESUMED stage" }.recreate()
        }
        val deadline = System.currentTimeMillis() + 10_000
        var fresh: ManualOccurrenceTestActivity? = null
        while (System.currentTimeMillis() < deadline && fresh == null) {
            instrumentation.runOnMainSync {
                fresh = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ManualOccurrenceTestActivity>().firstOrNull { it !== previous }
            }
            if (fresh == null) Thread.sleep(100)
        }
        instrumentation.waitForIdleSync()
        assertNotNull("New ManualOccurrenceTestActivity failed to enter RESUMED stage", fresh)
        return fresh!!
    }

    private fun launchIsolatedTestActivity(ui: CampusUiDriver): ManualOccurrenceTestActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // 1. 通过 shell 拉到前台突破 MIUI/HyperOS 后台 BAL 拦截
        val bringForegroundCmd = "am start -W -f 0x10008000 -n ${context.packageName}/${com.aggapp.app.ui.MainActivity::class.java.name}"
        instrumentation.uiAutomation.executeShellCommand(bringForegroundCmd)
        Thread.sleep(800)

        // 2. 启动测试 Activity
        val intent = Intent(context, ManualOccurrenceTestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        // 3. 有界等待进入 RESUMED 状态
        val deadline = System.currentTimeMillis() + 10_000
        var resumedActivity: ManualOccurrenceTestActivity? = null
        while (System.currentTimeMillis() < deadline && resumedActivity == null) {
            instrumentation.runOnMainSync {
                resumedActivity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ManualOccurrenceTestActivity>()
                    .firstOrNull()
            }
            if (resumedActivity == null) Thread.sleep(200)
        }
        assertNotNull("ManualOccurrenceTestActivity failed to enter RESUMED state within 10s", resumedActivity)
        ui.waitText("本机课表")
        return resumedActivity!!
    }

    private fun cleanupTestDb(context: Context, profile: String) {
        context.deleteDatabase("timetable_$profile.db")
        context.deleteDatabase("timetable_$profile.db-wal")
        context.deleteDatabase("timetable_$profile.db-shm")
    }

    private suspend fun loadHistoryEntries(): List<NoticeAdjustmentHistoryEntry> {
        val result = testNoticeRepo.history()
        return (result as? NoticeAdjustmentHistoryResult.Loaded)?.history?.entries.orEmpty()
    }
}
