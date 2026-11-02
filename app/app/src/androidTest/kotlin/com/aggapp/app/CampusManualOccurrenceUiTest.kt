/* SPDX-License-Identifier: GPL-3.0-only
 * UI tests for manual single-occurrence course adjustments.
 * Uses completely isolated test activity and independent temporary Room database.
 * Strictly decoupled from host MainActivity and timetable_local_profile.
 */
package com.aggapp.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.data.RoomNoticeAdjustmentRepository
import com.lingion.sleepy.data.RoomScheduleRepository
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.domain.model.CourseDraft
import com.lingion.sleepy.domain.model.CourseMeeting
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.TimetableSnapshot
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
class CampusManualOccurrenceUiTest {

    private companion object {
        const val ISOLATED_TEST_PROFILE = "isolated_c2_test"
        const val TEST_COURSE_NAME = "单次测试课_高数"
        const val TAG = "C2_DIAG"

        fun logDiag(stage: String, detail: String = "") {
            val message = if (detail.isEmpty()) "[STAGE] $stage" else "[STAGE] $stage - $detail"
            Log.i(TAG, message)
            println("[C2_DIAG] $message")
        }
    }

    private lateinit var testDatabase: TimetableDatabase
    private lateinit var testScheduleRepo: RoomScheduleRepository
    private lateinit var testNoticeRepo: RoomNoticeAdjustmentRepository
    private lateinit var testDependencies: TimetableDependencies
    private lateinit var baselineSnapshot: TimetableSnapshot
    private var initialHostProfileId: String? = null
    private var activeActivity: ManualOccurrenceTestActivity? = null

    @Before
    fun setUp() {
        logDiag("SETUP_START")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // 1. 验证并保护日常宿主 Profile，严禁测试 profile 与日常 profile 碰撞
        val hostPrefs = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
        initialHostProfileId = hostPrefs.getString("id", null)
        logDiag("HOST_PROFILE_CHECKED", "hostId=$initialHostProfileId")
        assertFalse(
            "Isolated test profile must never collide with host profile ID",
            ISOLATED_TEST_PROFILE == initialHostProfileId
        )

        // 2. 彻底清除可能残留的测试专用独立数据库
        context.deleteDatabase("timetable_$ISOLATED_TEST_PROFILE.db")
        context.deleteDatabase("timetable_$ISOLATED_TEST_PROFILE.db-wal")
        context.deleteDatabase("timetable_$ISOLATED_TEST_PROFILE.db-shm")
        logDiag("TEST_DB_PRE_CLEANED")

        // 3. 打开全新的测试专用 Room 数据库（版本 5），构造真实仓库依赖
        testDatabase = TimetableDatabase.open(context, ISOLATED_TEST_PROFILE)
        testScheduleRepo = RoomScheduleRepository(testDatabase)
        testNoticeRepo = RoomNoticeAdjustmentRepository(testDatabase)
        testDependencies = TimetableDependencies(
            repository = testScheduleRepo,
            imports = testScheduleRepo,
            noticeAdjustments = testNoticeRepo
        )
        logDiag("TEST_DB_OPENED")

        // 4. 仅在测试专有数据库中植入合成测试课程并有界等待
        runBlocking {
            withTimeout(10_000) {
                testScheduleRepo.saveCourse(
                    CourseDraft(
                        courseName = TEST_COURSE_NAME,
                        teacher = "测试张老师",
                        room = "正心楼101",
                        meetings = listOf(
                            CourseMeeting(
                                day = 1,
                                weeks = setOf(1, 2, 3, 4, 5),
                                sections = setOf(1, 2),
                                kind = MeetingKind.RECURRING,
                            )
                        ),
                    )
                )
            }
        }
        baselineSnapshot = runBlocking { withTimeout(5_000) { testScheduleRepo.observeTimetable().first() } }
        logDiag("TEST_COURSE_SEEDED", "coursesCount=${baselineSnapshot.courses.size}")

        // 5. 注入测试专用 Activity
        ManualOccurrenceTestActivity.testDependencies = testDependencies
        logDiag("SETUP_COMPLETED")
    }

    @After
    fun tearDown() {
        logDiag("TEARDOWN_START")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // 1. 关闭测试 Activity
        try {
            instrumentation.runOnMainSync {
                activeActivity?.finish()
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ManualOccurrenceTestActivity>()
                    .forEach { it.finish() }
            }
            instrumentation.waitForIdleSync()
            activeActivity = null
            logDiag("TEARDOWN_ACTIVITY_FINISHED")
        } catch (e: Throwable) {
            logDiag("TEARDOWN_ACTIVITY_FINISH_WARN", e.message ?: "unknown")
        }

        // 2. 关闭并彻底删除测试专用临时数据库
        if (::testDatabase.isInitialized && testDatabase.isOpen) {
            try {
                testDatabase.close()
                logDiag("TEARDOWN_DB_CLOSED")
            } catch (e: Throwable) {
                logDiag("TEARDOWN_DB_CLOSE_WARN", e.message ?: "unknown")
            }
        }
        context.deleteDatabase("timetable_$ISOLATED_TEST_PROFILE.db")
        context.deleteDatabase("timetable_$ISOLATED_TEST_PROFILE.db-wal")
        context.deleteDatabase("timetable_$ISOLATED_TEST_PROFILE.db-shm")
        logDiag("TEARDOWN_DB_FILES_DELETED")

        ManualOccurrenceTestActivity.testDependencies = null

        // 3. 断言日常宿主 Profile 从未被测试触碰或修改
        val hostPrefs = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
        val currentHostProfileId = hostPrefs.getString("id", null)
        assertEquals(
            "Host profile ID must remain strictly identical throughout tests",
            initialHostProfileId,
            currentHostProfileId
        )
        logDiag("TEARDOWN_COMPLETED")
    }

    @Test(timeout = 60_000)
    fun courseDetailsManualOccurrenceEntriesAndNavigation() {
        logDiag("TEST_ENTRIES_AND_NAV_START")
        val ui = CampusUiDriver()
        activeActivity = launchIsolatedTestActivity(ui)

        // 切换到“课程”Tab，展开测试课程组卡片并点击“详情”
        logDiag("NAVIGATING_TO_COURSES_TAB")
        ui.click("课程")
        logDiag("EXPANDING_TEST_COURSE_GROUP")
        ui.click(TEST_COURSE_NAME)
        logDiag("CLICKING_COURSE_DETAILS_BUTTON")
        ui.click("详情")

        // 断言进入课程详情弹窗，且包含全部三项 C2 手动单次管理入口按钮（绝不使用 Assume 跳过）
        logDiag("CHECKING_C2_ENTRY_BUTTONS")
        ui.waitText("调整一次")
        assertTrue("C2 button '调整一次' must exist", ui.hasText("调整一次"))
        assertTrue("C2 button '停课一次' must exist", ui.hasText("停课一次"))
        assertTrue("C2 button '补课一次' must exist", ui.hasText("补课一次"))

        // 点击“调整一次”，真正进入 ManualOccurrenceScreen
        logDiag("CLICKING_ADJUST_ONCE")
        ui.click("调整一次")
        ui.waitText("单次课程管理")
        assertTrue("Manual occurrence screen title must be visible", ui.hasText("单次课程管理"))
        // 修正后的专有标题与课程身份强断言（匹配 "${state.courseName} · 仅影响单次"）
        assertTrue(
            "Course header with single-occurrence suffix must be visible",
            ui.hasText("$TEST_COURSE_NAME · 仅影响单次")
        )
        assertTrue("Step 1 guidance must be visible", ui.hasText("第一步：选择需要操作的原上课日期与课时"))

        // 点击顶部导航栏的“返回”图标按钮
        logDiag("CLICKING_SCREEN_BACK_ICON")
        ui.clickDescription("返回")

        // 验证回到课表管理界面（显示“本机课表”或“新增课程”）
        waitForTimetableScreen(ui)
        assertFalse("Manual occurrence screen must be closed", ui.hasText("单次课程管理"))

        // 验证返回操作零写入：测试专有数据库快照完全不变
        val currentSnapshot = runBlocking { withTimeout(5_000) { testScheduleRepo.observeTimetable().first() } }
        assertEquals("Screen back button must cause zero writes", baselineSnapshot, currentSnapshot)
        logDiag("TEST_ENTRIES_AND_NAV_SUCCESS")
    }

    @Test(timeout = 60_000)
    fun manualOccurrenceScreenGlobalBackNavigation() {
        logDiag("TEST_GLOBAL_BACK_START")
        val ui = CampusUiDriver()
        activeActivity = launchIsolatedTestActivity(ui)

        ui.click("课程")
        ui.click(TEST_COURSE_NAME)
        ui.click("详情")

        ui.waitText("调整一次")
        ui.click("调整一次")
        ui.waitText("单次课程管理")
        assertTrue(
            "Course header with single-occurrence suffix must be visible",
            ui.hasText("$TEST_COURSE_NAME · 仅影响单次")
        )

        // 独立分发系统全局返回事件 GLOBAL_ACTION_BACK（无 fallback，真实系统返回）
        logDiag("DISPATCHING_GLOBAL_ACTION_BACK")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val globalBackTriggered = instrumentation.uiAutomation.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_BACK
        )
        assertTrue("System GLOBAL_ACTION_BACK failed to dispatch on device", globalBackTriggered)

        // 验证由 BackHandler 消费并回到课表界面
        waitForTimetableScreen(ui)
        assertFalse("Manual occurrence screen must be closed", ui.hasText("单次课程管理"))

        // 验证全局返回零写入
        val currentSnapshot = runBlocking { withTimeout(5_000) { testScheduleRepo.observeTimetable().first() } }
        assertEquals("Global back must cause zero writes", baselineSnapshot, currentSnapshot)
        logDiag("TEST_GLOBAL_BACK_SUCCESS")
    }

    @Test(timeout = 60_000)
    fun manualOccurrenceScreenDispatcherBackNavigation() {
        logDiag("TEST_DISPATCHER_BACK_START")
        val ui = CampusUiDriver()
        val activity = launchIsolatedTestActivity(ui).also { activeActivity = it }

        ui.click("课程")
        ui.click(TEST_COURSE_NAME)
        ui.click("详情")

        // 点击“停课一次”进入停课模式
        ui.waitText("停课一次")
        ui.click("停课一次")
        ui.waitText("单次课程管理")
        assertTrue(
            "Course header with single-occurrence suffix must be visible",
            ui.hasText("$TEST_COURSE_NAME · 仅影响单次")
        )

        // 独立在主线程触发 Activity 内部 onBackPressedDispatcher
        logDiag("DISPATCHING_ON_BACK_PRESSED_DISPATCHER")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            activity.onBackPressedDispatcher.onBackPressed()
        }

        // 验证回到课表界面
        waitForTimetableScreen(ui)
        assertFalse("Manual occurrence screen must be closed", ui.hasText("单次课程管理"))

        // 验证 Dispatcher 返回零写入
        val currentSnapshot = runBlocking { withTimeout(5_000) { testScheduleRepo.observeTimetable().first() } }
        assertEquals("Dispatcher back must cause zero writes", baselineSnapshot, currentSnapshot)
        logDiag("TEST_DISPATCHER_BACK_SUCCESS")
    }

    private fun waitForTimetableScreen(ui: CampusUiDriver) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (ui.hasText("新增课程") || ui.hasText("本机课表") || ui.hasText(TEST_COURSE_NAME)) {
                return
            }
            Thread.sleep(100)
        }
        assertTrue("Failed to return to timetable screen within 10s", ui.hasText("新增课程") || ui.hasText("本机课表") || ui.hasText(TEST_COURSE_NAME))
    }

    private fun launchIsolatedTestActivity(ui: CampusUiDriver): ManualOccurrenceTestActivity {
        logDiag("LAUNCH_ISOLATED_START")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // 1. 先通过 shell 将宿主拉到前台，突破 MIUI/HyperOS 后台启动 Activity 拦截 (BAL restriction)
        logDiag("BRING_HOST_TO_FOREGROUND")
        val bringForegroundCmd = "am start -W -f 0x10008000 -n ${context.packageName}/${com.aggapp.app.ui.MainActivity::class.java.name}"
        instrumentation.uiAutomation.executeShellCommand(bringForegroundCmd)
        Thread.sleep(800)

        // 2. 在宿主已处于前台的状态下，直接启动测试专用 Activity
        logDiag("START_TEST_ACTIVITY")
        val intent = Intent(context, ManualOccurrenceTestActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)

        // 3. 有界等待 ManualOccurrenceTestActivity 恢复至 RESUMED 状态（最多 10 秒，绝不在 UI 线程挂起）
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
        logDiag("LAUNCH_ISOLATED_SUCCESS")
        return resumedActivity!!
    }
}
