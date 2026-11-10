/* SPDX-License-Identifier: GPL-3.0-only
 * Device-level integration tests for Timetable backup, export, staging, and crash barrier recovery.
 * Fully isolated: operates strictly on an isolated sandbox context and guarantees zero interference with host sandbox.
 */
package com.aggapp.app.migration

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import com.aggapp.app.di.TimetableProfileActivator
import com.lingion.sleepy.data.backup.CourseBackupDto
import com.lingion.sleepy.data.backup.CourseDisplayGroupBackupDto
import com.lingion.sleepy.data.backup.CourseMeetingBackupDto
import com.lingion.sleepy.data.backup.MeetingExceptionBackupDto
import com.lingion.sleepy.data.backup.MeetingSectionBackupDto
import com.lingion.sleepy.data.backup.MeetingWeekBackupDto
import com.lingion.sleepy.data.backup.NoticeAdjustmentEventBackupDto
import com.lingion.sleepy.data.backup.PeriodBackupDto
import com.lingion.sleepy.data.backup.ProfileBackupDto
import com.lingion.sleepy.data.backup.ScheduledCourseBackupDto
import com.lingion.sleepy.data.backup.SemesterBackupDto
import com.lingion.sleepy.data.backup.SqliteSequenceBackupDto
import com.lingion.sleepy.data.backup.TIMETABLE_BACKUP_FORMAT_VERSION
import com.lingion.sleepy.data.backup.TimetableBackupDigest
import com.lingion.sleepy.data.backup.TimetableBackupJsonCodec
import com.lingion.sleepy.data.backup.TimetableBackupPayloadDto
import com.lingion.sleepy.data.backup.TimetableBackupValidator
import com.lingion.sleepy.data.backup.TimetableConfirmationToken
import com.lingion.sleepy.data.backup.TimetableWriteCoordinator
import com.lingion.sleepy.data.local.TimetableDatabase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Isolated ContextWrapper redirecting files, databases, cache, and SharedPreferences
 * to a dedicated disposable directory per test run, guaranteeing zero host pollution.
 */
class IsolatedDeviceTestContext(
    base: Context,
    val testSandboxDir: File,
) : ContextWrapper(base) {
    private val isolatedFilesDir = File(testSandboxDir, "files").apply { mkdirs() }
    private val isolatedCacheDir = File(testSandboxDir, "cache").apply { mkdirs() }
    private val isolatedDatabasesDir = File(testSandboxDir, "databases").apply { mkdirs() }

    private val preferenceNames = mutableSetOf<String>()

    override fun getApplicationContext(): Context = this
    override fun databaseList(): Array<String> = isolatedDatabasesDir.list() ?: emptyArray()
    override fun getFilesDir(): File = isolatedFilesDir
    override fun getCacheDir(): File = isolatedCacheDir
    override fun getDatabasePath(name: String): File {
        val requested = File(name)
        val path = (if (requested.isAbsolute) requested else File(isolatedDatabasesDir, name)).canonicalFile
        check(path.toPath().startsWith(testSandboxDir.canonicalFile.toPath()))
        return path
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val isolatedName = "test_isolated_${testSandboxDir.name}_$name"
        synchronized(preferenceNames) { preferenceNames.add(isolatedName) }
        return super.getSharedPreferences(isolatedName, mode)
    }

    fun clearTestPreferences() {
        synchronized(preferenceNames) {
            for (name in preferenceNames) check(super.deleteSharedPreferences(name))
            preferenceNames.clear()
        }
    }
}

@RunWith(AndroidJUnit4::class)
class TimetableMigrationDeviceTest {

    private lateinit var context: Context
    private lateinit var sandboxDir: File
    private val crashMode get() = InstrumentationRegistry.getArguments().getString("migrationCrashMode")
    private val crashRun get() = InstrumentationRegistry.getArguments().getString("migrationCrashRun")
    private val safRun get() = InstrumentationRegistry.getArguments().getString("migrationSafRun")

    @Before
    fun setUp() {
        TimetableMigrationTestHooks.reset()
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        check(baseContext.packageName == "io.github.schoolhelper.app.migrationtest" ||
            (safRun != null && baseContext.packageName == "io.github.schoolhelper.app.migrationtest.target")) {
            "Migration device tests require -PschoolHelperMigrationSandbox=true"
        }
        val run = crashRun
        require(run == null || run.matches(Regex("[a-f0-9]{12}")))
        sandboxDir = File(baseContext.cacheDir, if (run == null) {
            "s7_isolated_sandbox_${UUID.randomUUID().toString().take(8)}"
        } else {
            "s7_crash_$run"
        })
        sandboxDir.mkdirs()
        context = IsolatedDeviceTestContext(baseContext, sandboxDir)
    }

    @After
    fun tearDown() {
        TimetableMigrationTestHooks.reset()
        if (::context.isInitialized) (context as IsolatedDeviceTestContext).clearTestPreferences()
        if (::sandboxDir.isInitialized) check(sandboxDir.deleteRecursively())
    }

    private fun buildSamplePayload(
        profileId: String,
        courseName: String = "量子计算导论",
        colorArgb: Long = 0xFF123456L,
    ): TimetableBackupPayloadDto {
        val testMonday = LocalDate.of(2026, 9, 7)
        val semId = 1L
        val courseId = 5001L
        val meetingId = 6001L
        val exceptionId = 7001L
        val eventId = 8001L

        // Strictly valid production NoticeEvent JSON matching NoticeEventCodec schema:
        // Keys: exceptions, fingerprints (NOT exceptionIds/exceptionFingerprints), strictly without unknown keys
        val beforeJson = """{"date":${testMonday.plusDays(1).toEpochDay()},"sections":[1],"ranges":[["08:00","08:45"]],"teacher":"王教授","room":"理学院101","project":null,"group":null}"""
        val afterJson = """{"date":${testMonday.plusDays(2).toEpochDay()},"sections":[],"ranges":[["09:00","10:30"]],"teacher":"王教授","room":"理学院202","project":null,"group":null}"""
        val payloadJson = """{"version":1,"mode":"MANUAL","exceptions":[$exceptionId],"fingerprints":["0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"],"previous":null}"""
        val dateBasisJson = """{"reference":${testMonday.toEpochDay()},"term":${testMonday.toEpochDay()},"weeks":20,"year":2026}"""

        val sigCourse = ScheduledCourseBackupDto(
            id = courseId,
            semesterId = semId,
            courseName = courseName,
            teacher = "王教授",
            room = "理学院101",
            color = colorArgb,
            sourceCourseCode = "QC101",
            sourceClassCode = "C1",
        )
        val otherCourse = sigCourse.copy(id = courseId + 1, courseName = "量子计算实验")
        val sig = TimetableBackupValidator.computeCourseSignature(sigCourse)
        val otherSig = TimetableBackupValidator.computeCourseSignature(otherCourse)

        val displayGroupJson = """{"version":1,"groups":[{"id":"g1","name":"物理组","members":[{"courseId":$courseId,"signature":"$sig"},{"courseId":${otherCourse.id},"signature":"$otherSig"}]}]}"""
        val storageKey = "v1|scope=$profileId|semester=$semId"

        val profileDto = ProfileBackupDto(
            profileId = profileId,
            semesters = listOf(
                SemesterBackupDto(id = semId, name = "2026秋", startEpochDay = testMonday.toEpochDay(), totalWeeks = 20, revision = 10L)
            ),
            periods = listOf(
                PeriodBackupDto(semesterId = semId, number = 1, startMinute = 480, endMinute = 525)
            ),
            courses = listOf(
                CourseBackupDto(id = 101L, courseName = courseName, day = 1, startNode = 1, step = 2, startWeek = 1, endWeek = 16, type = 0)
            ),
            scheduledCourses = listOf(sigCourse, otherCourse),
            courseMeetings = listOf(
                CourseMeetingBackupDto(id = meetingId, courseId = courseId, day = 2, customStartMinute = null, customEndMinute = null, teacher = "王教授", room = "理学院101", kind = "RECURRING", experimentProject = null, group = null, isExperiment = false)
            ),
            meetingWeeks = listOf(MeetingWeekBackupDto(meetingId = meetingId, week = 1)),
            meetingSections = listOf(MeetingSectionBackupDto(meetingId = meetingId, section = 1)),
            meetingDates = emptyList(),
            meetingDeclaredWeeks = emptyList(),
            meetingExceptions = listOf(
                MeetingExceptionBackupDto(
                    id = exceptionId,
                    meetingId = meetingId,
                    kind = "RESCHEDULE",
                    originalEpochDay = testMonday.plusDays(1).toEpochDay(),
                    targetEpochDay = testMonday.plusDays(2).toEpochDay(),
                    customStartMinute = 540,
                    customEndMinute = 630,
                    hasSectionsOverride = false,
                    teacher = "王教授",
                    room = "理学院202",
                    experimentProject = null,
                    group = null,
                )
            ),
            exceptionSections = emptyList(),
            scheduleProvenance = emptyList(),
            importNormalizationState = emptyList(),
            noticeAdjustmentEvents = listOf(
                NoticeAdjustmentEventBackupDto(
                    id = eventId,
                    requestKey = "req_$profileId",
                    semanticKey = "sem_$profileId",
                    confirmDigest = "conf_$profileId",
                    scopeToken = profileId,
                    semesterId = semId,
                    kind = "RESCHEDULE_TIME",
                    courseId = courseId,
                    courseTitle = courseName,
                    logicalBaseId = "base_1",
                    structureFingerprint = "fp_1",
                    meetingId = meetingId,
                    originalEpochDay = testMonday.plusDays(1).toEpochDay(),
                    targetEpochDay = testMonday.plusDays(2).toEpochDay(),
                    beforeJson = beforeJson,
                    afterJson = afterJson,
                    afterFingerprint = "afp_$profileId",
                    payloadJson = payloadJson,
                    sourceText = "调课通知",
                    sourceDigest = "sdig_$profileId",
                    dateBasisJson = dateBasisJson,
                    appliedRevision = 10L,
                    undoneRevision = null,
                    state = "APPLIED",
                    activeKey = "active_$profileId",
                )
            ),
            sequences = listOf(
                SqliteSequenceBackupDto(name = "courses", seq = 101L),
                SqliteSequenceBackupDto(name = "scheduled_courses", seq = otherCourse.id),
                SqliteSequenceBackupDto(name = "course_meetings", seq = meetingId),
                SqliteSequenceBackupDto(name = "meeting_exceptions", seq = exceptionId),
                SqliteSequenceBackupDto(name = "notice_adjustment_events", seq = eventId),
            ),
            displayGroups = listOf(
                CourseDisplayGroupBackupDto(storageKey = storageKey, rawJson = displayGroupJson)
            ),
        )

        val payload = TimetableBackupPayloadDto(
            formatVersion = TIMETABLE_BACKUP_FORMAT_VERSION,
            sourceAppId = "com.aggapp.app",
            sourceVersionCode = 2,
            createdAt = System.currentTimeMillis(),
            activeProfileId = profileId,
            contentDigest = "",
            profiles = listOf(profileDto),
        )
        val digest = TimetableBackupDigest.calculateDigest(payload)
        return payload.copy(contentDigest = digest)
    }

    private fun createEncryptedBackupFile(payload: TimetableBackupPayloadDto, passwordChars: CharArray): File {
        TimetableBackupValidator.validate(payload)
        val jsonString = TimetableBackupJsonCodec.encode(payload)
        val containerBytes = TimetableEncryption.encrypt(jsonString.toByteArray(Charsets.UTF_8), passwordChars)
        val file = File(context.cacheDir, "test_backup_${UUID.randomUUID()}.agt")
        file.outputStream().use { it.write(containerBytes) }
        return file
    }

    @Test
    fun prepareExternalProcessDeath(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(crashRun != null)
        require(crashMode == "uncommitted" || crashMode == "committed")
        val profile = "crash_destination"
        val prefs = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
        check(prefs.edit().putString("id", "crash_original").commit())
        val password = "SyntheticCrashTest2026".toCharArray()
        val file = createEncryptedBackupFile(buildSamplePayload(profile), password)
        check(file.renameTo(File(context.cacheDir, "crash-backup.agt")))
        val uri = Uri.fromFile(File(context.cacheDir, "crash-backup.agt"))
        val preview = TimetableMigrationService.inspect(context, uri, password.copyOf()) as ImportPreviewResult.Success
        val pause: suspend () -> Unit = {
            val journal = TimetableMigrationJournal.readJournal(context) as JournalReadResult.Present
            val expected = if (crashMode == "committed") MigrationStage.COMMITTED else MigrationStage.PUBLISHING
            assertEquals(expected.name, journal.state.stage)
            assertTrue(context.getDatabasePath("timetable_$profile.db").isFile)
            assertTrue(TimetableMigrationJournal.readReceipt(context) is ReceiptReadResult.Absent)
            File(sandboxDir, "ready").writeText("${android.os.Process.myPid()}:${journal.state.stage}")
            kotlinx.coroutines.awaitCancellation()
        }
        if (crashMode == "committed") {
            TimetableMigrationTestHooks.afterCommitBeforeReceiptHook = pause
        } else {
            TimetableMigrationTestHooks.afterPublishBeforeCommitHook = pause
        }
        kotlinx.coroutines.withTimeout(120_000) {
            TimetableMigrationService.restore(context, uri, password.copyOf(), preview.token)
        }
        error("Restore did not pause at the requested crash boundary")
    }

    @Test
    fun verifyExternalProcessDeathRecovery() = runBlocking {
        org.junit.Assume.assumeTrue(crashRun != null)
        require(crashMode == "uncommitted" || crashMode == "committed")
        val marker = File(sandboxDir, "ready").readText().split(':')
        assertTrue(marker[0].toInt() != android.os.Process.myPid())
        val expected = if (crashMode == "committed") MigrationStage.COMMITTED else MigrationStage.PUBLISHING
        assertEquals(expected.name, marker[1])
        val activator = TimetableProfileActivator(context)
        val active = activator.activeProfileId()
        val target = context.getDatabasePath("timetable_crash_destination.db")
        val uri = Uri.fromFile(File(context.cacheDir, "crash-backup.agt"))
        val password = "SyntheticCrashTest2026".toCharArray()
        assertTrue(TimetableMigrationJournal.readJournal(context) is JournalReadResult.Absent)
        if (crashMode == "committed") {
            assertEquals("crash_destination", active)
            assertTrue(target.isFile)
            assertTrue(TimetableMigrationJournal.readReceipt(context) is ReceiptReadResult.Present)
            val snapshot = activator.scheduleRepository.observeTimetable().first()
            assertEquals(2, snapshot.courses.size)
        } else {
            assertEquals("crash_original", active)
            assertFalse(target.exists())
            assertTrue(TimetableMigrationJournal.readReceipt(context) is ReceiptReadResult.Absent)
        }
        val preview = TimetableMigrationService.inspect(context, uri, password.copyOf()) as ImportPreviewResult.Success
        val result = activator.restoreBackup(uri, password, preview.token)
        if (crashMode == "committed") assertEquals(RestoreResult.AlreadyImported, result)
        else assertTrue("Retry must succeed: $result", result is RestoreResult.Success)
    }

    private fun openBackupUi(base: Context): UiDevice {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val launch = checkNotNull(base.packageManager.getLaunchIntentForPackage(base.packageName))
        val component = checkNotNull(launch.component).flattenToShortString()
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("am start -W -n $component").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText()
            }
        device.clickLabel("我的")
        if (!device.hasObject(By.text("课表数据备份与恢复"))) {
            UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("课表数据备份与恢复")
        }
        return device
    }

    private fun UiDevice.clickLabel(label: String) {
        val view = checkNotNull(wait(Until.findObject(By.text(label)), 10_000)) {
            "未找到界面控件：$label"
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val roots = listOfNotNull(automation.rootInActiveWindow) +
            automation.windows.mapNotNull { it.root }
        val clicked = roots.any { root ->
            root.findAccessibilityNodeInfosByText(label).any { node ->
                node.text?.toString() == label && accessibilityClick(node)
            }
        }
        if (!clicked) view.click()
    }

    private fun UiDevice.setEditor(index: Int, value: String) {
        wait(Until.hasObject(By.clazz("android.widget.EditText")), 8_000)
        val root = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        val fields = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
        fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
            if (node.isEditable) fields.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        visit(root)
        val args = android.os.Bundle().apply {
            putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        val set = fields.getOrNull(index)?.performAction(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, args,
        ) == true
        if (!set) {
            checkNotNull(findObjects(By.clazz("android.widget.EditText")).getOrNull(index)) {
                "未找到输入框 $index"
            }.text = value
        }
    }

    private fun accessibilityClick(start: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
        var node = start
        while (node != null) {
            if (node.isClickable && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            node = node.parent
        }
        return false
    }

    @Test
    fun exportThroughSystemPicker() = runBlocking {
        val run = safRun
        org.junit.Assume.assumeTrue(run != null)
        require(checkNotNull(run).matches(Regex("[a-f0-9]{12}")))
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName == "io.github.schoolhelper.app.migrationtest")
        val file = createEncryptedBackupFile(buildSamplePayload("saf_$run"), "SyntheticSafTest2026".toCharArray())
        val uri = Uri.fromFile(file)
        val password = "SyntheticSafTest2026"
        val preview = TimetableMigrationService.inspect(base, uri, password.toCharArray()) as ImportPreviewResult.Success
        val seeded = TimetableMigrationService.restore(base, uri, password.toCharArray(), preview.token)
        assertTrue("Seed restore failed: $seeded", seeded is RestoreResult.Success)
        val device = openBackupUi(base)
        device.clickLabel("导出备份")
        device.setEditor(0, password)
        device.setEditor(1, password)
        device.clickLabel("选择保存位置")
        check(device.wait(Until.hasObject(By.res("com.android.documentsui:id/filename")), 8000) ||
            device.hasObject(By.clazz("android.widget.EditText"))) { "未打开系统文件选择器" }
        device.setEditor(0, "school-helper-saf-$run.agt")
        val save = device.findObject(By.text("保存")) ?: device.findObject(By.text("Save"))
            ?: device.findObject(By.text("SAVE"))
        checkNotNull(save) { "找不到系统保存按钮" }.click()
        assertTrue("Export success message was not observed",
            device.wait(Until.hasObject(By.textContains("课表导出成功")), 30_000))
    }

    @Test
    fun importThroughSystemPicker() = runBlocking {
        val run = safRun
        org.junit.Assume.assumeTrue(run != null)
        require(checkNotNull(run).matches(Regex("[a-f0-9]{12}")))
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName == "io.github.schoolhelper.app.migrationtest.target")
        val device = openBackupUi(base)
        device.clickLabel("恢复备份")
        check(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
        ))
        assertTrue(device.wait(Until.hasObject(By.text("恢复备份")), 10_000))
        assertTrue(TimetableMigrationJournal.readReceipt(base) is ReceiptReadResult.Absent)
        device.clickLabel("恢复备份")
        val fileName = InstrumentationRegistry.getArguments().getString("migrationSafFilename")
            ?: "school-helper-saf-$run.agt"
        require(fileName == "timetable_backup.agt" || fileName == "school-helper-saf-$run.agt")
        device.clickLabel(fileName)
        device.setEditor(0, "SyntheticSafTest2026")
        device.clickLabel("预检备份")
        assertTrue(device.wait(Until.hasObject(By.textContains("来源应用: io.github.schoolhelper.app.migrationtest")), 30_000))
        device.clickLabel("确认恢复")
        kotlinx.coroutines.withTimeout(35_000) {
            while (TimetableMigrationJournal.readReceipt(base) !is ReceiptReadResult.Present) delay(250)
        }
        assertRestoredSafData(base, run)
    }

    @Test
    fun verifySafTargetAfterRestart() = runBlocking {
        val run = safRun
        org.junit.Assume.assumeTrue(run != null)
        require(checkNotNull(run).matches(Regex("[a-f0-9]{12}")))
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        check(base.packageName == "io.github.schoolhelper.app.migrationtest.target")
        assertRestoredSafData(base, run)
    }

    private suspend fun assertRestoredSafData(base: Context, run: String) {
        val profile = "saf_$run"
        val activator = TimetableProfileActivator(base)
        assertEquals(profile, activator.activeProfileId())
        val snapshot = activator.scheduleRepository.observeTimetable().first()
        assertEquals(setOf("量子计算导论", "量子计算实验"), snapshot.courses.map { it.courseName }.toSet())
        val group = base.getSharedPreferences("course_display_groups", Context.MODE_PRIVATE)
            .getString("v1|scope=$profile|semester=1", null)
        assertTrue(group?.contains("物理组") == true)
        val db = TimetableDatabase.open(base, profile)
        try {
            assertEquals("RESCHEDULE_TIME", db.noticeAdjustmentDao().eventById(8001L)?.kind)
        } finally {
            db.close()
        }
    }

    @Test
    fun testEndToEndExportAndRestoreRoundTripWithNoticeEvents() = runBlocking {
        val emptyTarget = TimetableProfileActivator(context)
        assertTrue(emptyTarget.scheduleRepository.observeTimetable().first().courses.isEmpty())
        val testProfileId = "s7test_p1_${UUID.randomUUID().toString().take(8)}"
        val validPayload = buildSamplePayload(testProfileId, colorArgb = 0xFF336699L)
        TimetableBackupValidator.validate(validPayload)

        val testPassword = "DeviceTestPassword@2026".toCharArray()
        val backupFile = createEncryptedBackupFile(validPayload, testPassword)
        val backupUri = Uri.fromFile(backupFile)

        // 1. Inspect
        val inspectRes = TimetableMigrationService.inspect(context, backupUri, testPassword.copyOf())
        assertTrue("Inspect should succeed", inspectRes is ImportPreviewResult.Success)
        val preview = (inspectRes as ImportPreviewResult.Success).preview
        assertEquals(1, preview.profileSummaries.size)
        assertEquals(2, preview.totalCourses)
        assertEquals(1, preview.totalAdjustmentEvents)

        // 2. Restore
        val restoreRes = TimetableMigrationService.restore(
            context = context,
            sourceUri = backupUri,
            passphrase = testPassword.copyOf(),
            token = inspectRes.token,
        )
        assertTrue("Restore should succeed: $restoreRes", restoreRes is RestoreResult.Success)
        val success = restoreRes as RestoreResult.Success
        assertEquals(testProfileId, success.activeProfileId)

        // 3. Verify Database file exists and Room can read with Long ARGB color preserved
        val restoredDbFile = context.getDatabasePath("timetable_$testProfileId.db")
        assertTrue(restoredDbFile.exists())
        assertTrue(restoredDbFile.length() > 0L)

        val roomDb = TimetableDatabase.open(context, testProfileId)
        try {
            val sem = roomDb.scheduleDao().semester(1L)
            assertNotNull(sem)
            assertEquals("2026秋", sem?.name)

            val courses = roomDb.scheduleDao().courses(1L)
            assertEquals(2, courses.size)
            assertEquals("量子计算导论", courses[0].courseName)
            assertEquals(0xFF336699L, courses[0].color) // High-order Long ARGB preserved!

            val event = roomDb.noticeAdjustmentDao().eventById(8001L)
            assertNotNull(event)
            assertEquals("APPLIED", event?.state)
            assertEquals("RESCHEDULE_TIME", event?.kind)
        } finally {
            roomDb.close()
        }

        // 4. Test idempotency
        val secondRestore = TimetableMigrationService.restore(
            context = context,
            sourceUri = backupUri,
            passphrase = testPassword.copyOf(),
            token = inspectRes.token,
        )
        assertEquals(RestoreResult.AlreadyImported, secondRestore)
    }

    @Test
    fun testDifferentBackupWithStaleConfirmationIsRejected() = runBlocking {
        kotlinx.coroutines.withTimeout(120_000) { verifyConcurrentRestore(sameBackup = false) }
    }

    @Test
    fun testConcurrentSameBackupIsIdempotent() = runBlocking {
        kotlinx.coroutines.withTimeout(120_000) { verifyConcurrentRestore(sameBackup = true) }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.verifyConcurrentRestore(sameBackup: Boolean) {
        val p1 = "s7test_concurrent_1_${UUID.randomUUID().toString().take(6)}"
        val p2 = "s7test_concurrent_2_${UUID.randomUUID().toString().take(6)}"
        val payload1 = buildSamplePayload(p1, courseName = "并发课程一")
        val payload2 = buildSamplePayload(p2, courseName = "并发课程二")
        val pwd = "ConcurrentPass@2026".toCharArray()

        val file1 = createEncryptedBackupFile(payload1, pwd)
        val file2 = if (sameBackup) file1 else createEncryptedBackupFile(payload2, pwd)

        val ins1 = (TimetableMigrationService.inspect(context, Uri.fromFile(file1), pwd.copyOf()) as ImportPreviewResult.Success).token
        val ins2 = (TimetableMigrationService.inspect(context, Uri.fromFile(file2), pwd.copyOf()) as ImportPreviewResult.Success).token

        val restore1Started = CompletableDeferred<Unit>()
        val restore2Attempted = CompletableDeferred<Unit>()
        val unblockRestore1 = CompletableDeferred<Unit>()

        TimetableMigrationTestHooks.beforePublishHook = {
            restore1Started.complete(Unit)
            unblockRestore1.await()
        }

        val job1 = async(Dispatchers.IO) {
            TimetableMigrationService.restore(context, Uri.fromFile(file1), pwd.copyOf(), ins1)
        }

        kotlinx.coroutines.selects.select<Unit> {
            restore1Started.onAwait { }
            job1.onAwait { error("First restore exited before publication: $it") }
        }

        val job2 = async(Dispatchers.IO) {
            restore2Attempted.complete(Unit)
            TimetableMigrationService.restore(context, Uri.fromFile(file2), pwd.copyOf(), ins2)
        }

        restore2Attempted.await()
        delay(100) // Give job2 time to enter write coordinator queue

        // Release job1
        unblockRestore1.complete(Unit)
        val res1 = job1.await()
        assertTrue("Job 1 should succeed: $res1", res1 is RestoreResult.Success)

        // Clear hook so job2 can proceed without deadlock
        TimetableMigrationTestHooks.beforePublishHook = null

        val res2 = job2.await()
        if (sameBackup) {
            assertEquals(RestoreResult.AlreadyImported, res2)
        } else {
            assertTrue("Stale confirmation must be rejected: $res2", res2 is RestoreResult.Rejected || res2 is RestoreResult.Failed)
            assertFalse(context.getDatabasePath("timetable_$p2.db").exists())
        }

        // A different backup with a stale token cannot replace the first restore.
        val profilePrefs = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
        assertEquals(p1, profilePrefs.getString("id", null))
    }

    @Test
    fun testReceiptWriteFailureDoesNotRollbackCommittedDatabase() = runBlocking {
        val testProfileId = "s7test_receipt_fail_${UUID.randomUUID().toString().take(6)}"
        val payload = buildSamplePayload(testProfileId)
        val pwd = "ReceiptFailPass@2026".toCharArray()
        val file = createEncryptedBackupFile(payload, pwd)
        val token = (TimetableMigrationService.inspect(context, Uri.fromFile(file), pwd.copyOf()) as ImportPreviewResult.Success).token

        // Inject simulated IOException when writing receipt
        TimetableMigrationTestHooks.writeReceiptHook = {
            throw IOException("Simulated disk error on receipt atomic write")
        }

        val res = TimetableMigrationService.restore(context, Uri.fromFile(file), pwd.copyOf(), token)
        assertTrue("Should still succeed because COMMITTED state was reached", res is RestoreResult.Success)

        // Database MUST NOT be rolled back or deleted!
        val dbFile = context.getDatabasePath("timetable_$testProfileId.db")
        assertTrue("Committed database must exist despite receipt write failure", dbFile.exists())
        assertTrue(dbFile.length() > 0L)

        assertTrue(TimetableMigrationJournal.readJournal(context) is JournalReadResult.Present)
        // Startup recovery barrier should detect COMMITTED journal and self-heal missing receipt
        val barrierRes = TimetableRecoveryBarrier.recoverBeforeOpen(context)
        assertTrue("Barrier should self-heal and return RestoredCommitted: $barrierRes", barrierRes is RecoveryResult.RestoredCommitted)

        // Now receipt should exist
        val receiptRes = TimetableMigrationJournal.readReceipt(context)
        assertTrue("Receipt was reconstructed and persisted", receiptRes is ReceiptReadResult.Present)
        assertEquals(RestoreResult.AlreadyImported, TimetableMigrationService.restore(context, Uri.fromFile(file), pwd.copyOf(), token))
    }

    @Test
    fun testCommitJournalWriteFailureRollsBackPublishedDatabase() = runBlocking {
        val profile = "s7test_commit_fail"
        val password = "CommitFailureTest".toCharArray()
        val file = createEncryptedBackupFile(buildSamplePayload(profile), password)
        val uri = Uri.fromFile(file)
        val preview = TimetableMigrationService.inspect(context, uri, password.copyOf()) as ImportPreviewResult.Success
        var reachedCommit = false
        TimetableMigrationTestHooks.afterPublishBeforeCommitHook = {
            reachedCommit = true
            val journal = TimetableMigrationJournal.journalFile(context)
            check(File(journal.path + ".new").mkdir())
        }

        val result = TimetableMigrationService.restore(context, uri, password.copyOf(), preview.token)

        assertTrue("Restore must reach the commit fault: $result", reachedCommit)
        assertTrue("Failed commit must not be reported as success: $result", result is RestoreResult.Failed)
        assertFalse(context.getDatabasePath("timetable_$profile.db").exists())
        assertTrue(TimetableMigrationJournal.readReceipt(context) is ReceiptReadResult.Absent)
    }

    @Test
    fun testCancellationDuringPublishRollsBackSafely() = runBlocking {
        val testProfileId = "s7test_cancel_${UUID.randomUUID().toString().take(6)}"
        val payload = buildSamplePayload(testProfileId)
        val pwd = "CancelPass@2026".toCharArray()
        val file = createEncryptedBackupFile(payload, pwd)
        val token = (TimetableMigrationService.inspect(context, Uri.fromFile(file), pwd.copyOf()) as ImportPreviewResult.Success).token

        // Inject CancellationException right before publishing
        TimetableMigrationTestHooks.beforePublishHook = {
            throw CancellationException("Simulated coroutine cancellation during restore")
        }

        try {
            TimetableMigrationService.restore(context, Uri.fromFile(file), pwd.copyOf(), token)
            org.junit.Assert.fail("Expected CancellationException")
        } catch (e: CancellationException) {
            // Expected
        }

        // Database must NOT exist, rollback cleaned uncommitted state under NonCancellable
        val dbFile = context.getDatabasePath("timetable_$testProfileId.db")
        assertFalse("Uncommitted database must be rolled back", dbFile.exists())
        val journalRes = TimetableMigrationJournal.readJournal(context)
        assertTrue("Uncommitted journal was deleted by rollback", journalRes is JournalReadResult.Absent)
    }

    @Test
    fun testPostCommitCleanupFailureDoesNotRollback() = runBlocking {
        val testProfileId = "s7test_clean_fail_${UUID.randomUUID().toString().take(6)}"
        val payload = buildSamplePayload(testProfileId)
        val pwd = "CleanFailPass@2026".toCharArray()
        val file = createEncryptedBackupFile(payload, pwd)
        val token = (TimetableMigrationService.inspect(context, Uri.fromFile(file), pwd.copyOf()) as ImportPreviewResult.Success).token

        // Inject exception during post-commit cleanup
        TimetableMigrationTestHooks.cleanupHook = {
            throw IOException("Simulated file lock on staging directory cleanup")
        }

        val res = TimetableMigrationService.restore(context, Uri.fromFile(file), pwd.copyOf(), token)
        assertTrue("Should succeed despite cleanup failure", res is RestoreResult.Success)

        val dbFile = context.getDatabasePath("timetable_$testProfileId.db")
        assertTrue("Database exists", dbFile.exists())
    }

    @Test
    fun testOwnershipMarkerProtocolIdentifiesOrphanFiles() {
        val testToken = UUID.randomUUID().toString()
        val orphanDbName = "timetable_orphan_${UUID.randomUUID().toString().take(6)}.db"
        val preexistingDbName = "timetable_preexisting_${UUID.randomUUID().toString().take(6)}.db"

        val orphanDb = context.getDatabasePath(orphanDbName)
        val preexistingDb = context.getDatabasePath(preexistingDbName)
        orphanDb.parentFile?.mkdirs()

        val staged = File(context.cacheDir, "orphan-stage.db").apply { writeText("session bytes") }
        MigrationPublication.publish(staged, orphanDb, testToken)
        preexistingDb.writeText("preexisting user db content")
        val proof = File(orphanDb.parentFile, "${orphanDb.name}.identity_$testToken")
        val competingPart = File(preexistingDb.parentFile, "${preexistingDb.name}.part_$testToken")
        competingPart.writeText("unpublished session bytes")

        // Journal in PUBLISHING stage with this sessionToken, but createdDatabaseFiles was empty (crashed before writing journal)
        val journal = MigrationJournalState(
            stage = MigrationStage.PUBLISHING.name,
            contentDigest = "digest_orphan_test",
            targetActiveProfileId = "orphan_target",
            originalActiveProfileId = null,
            createdDatabaseFiles = emptyList(), // Not yet in list!
            createdStagingDir = null,
            groupBackups = emptyMap(),
            sessionToken = testToken,
        )
        TimetableMigrationJournal.writeJournal(context, journal)

        // Run recovery barrier
        val res = TimetableRecoveryBarrier.recoverBeforeOpen(context)
        assertTrue("Recovery should roll back successfully: $res", res is RecoveryResult.RolledBack)

        // Orphan DB recognized by marker and safely deleted!
        assertFalse("Orphan DB recognized and deleted", orphanDb.exists())
        assertFalse("Session proof removed", proof.exists())
        assertFalse("Unpublished part removed", competingPart.exists())

        // Preexisting DB untouched!
        assertTrue("Preexisting DB preserved", preexistingDb.exists())
        assertEquals("preexisting user db content", preexistingDb.readText())
    }

    @Test
    fun testProfileActivatorLifecycleLockCoordination() = runBlocking {
        val p1 = "s7test_act_1_${UUID.randomUUID().toString().take(6)}"
        val p2 = "s7test_act_2_${UUID.randomUUID().toString().take(6)}"

        val profilePrefs = context.getSharedPreferences("timetable_local_profile", Context.MODE_PRIVATE)
        profilePrefs.edit().putString("id", p1).commit()

        val activator = TimetableProfileActivator(context)
        assertEquals(p1, activator.activeProfileId())

        // Save a course in p1
        val semMonday = LocalDate.of(2026, 9, 7)
        val db1 = TimetableDatabase.open(context, p1)
        db1.scheduleDao().insertSemester(com.lingion.sleepy.data.local.SemesterEntity(id = 1L, name = "P1", startEpochDay = semMonday.toEpochDay(), totalWeeks = 20, revision = 1L))
        db1.scheduleDao().insertCourse(com.lingion.sleepy.data.local.ScheduledCourseEntity(id = 1001L, semesterId = 1L, courseName = "P1课程", teacher = "T1", room = "R1", color = 0xFF111111L))
        db1.close()

        val db2 = TimetableDatabase.open(context, p2)
        db2.scheduleDao().insertSemester(com.lingion.sleepy.data.local.SemesterEntity(id = 1L, name = "P2", startEpochDay = semMonday.toEpochDay(), totalWeeks = 20, revision = 1L))
        db2.scheduleDao().insertCourse(com.lingion.sleepy.data.local.ScheduledCourseEntity(id = 2001L, semesterId = 1L, courseName = "P2课程", teacher = "T2", room = "R2", color = 0xFF222222L))
        db2.close()

        // Suspend inside a repository operation, then resume on a different dispatcher.
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val reader = async(Dispatchers.Default) {
            activator.withScheduleRepo { repo ->
                entered.complete(Unit)
                kotlinx.coroutines.withContext(Dispatchers.IO) { resume.await(); repo.readImportTarget(1) }
            }
        }
        kotlinx.coroutines.withTimeout(10_000) { entered.await() }
        val switched = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            activator.activateProfile(p2)
        }
        assertFalse("Activation must wait for the in-flight operation", switched.isCompleted)
        resume.complete(Unit)
        kotlinx.coroutines.withTimeout(10_000) {
            reader.await()
            switched.await()
        }
        assertEquals(p2, activator.activeProfileId())

        val snapshot = activator.scheduleRepository.observeTimetable().first()
        assertEquals(1, snapshot.courses.size)
        assertEquals("P2课程", snapshot.courses[0].courseName)
    }
}
