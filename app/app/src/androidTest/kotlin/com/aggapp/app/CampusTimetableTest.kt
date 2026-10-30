package com.aggapp.app

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aggapp.app.ui.MainActivity
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real text entry and clicks against the installed app. No mock repository or data clearing. */
@RunWith(AndroidJUnit4::class)
class CampusTimetableTest {
    @Test(timeout = 180_000)
    fun manualCourseIsManagedInTodayAndDifferentRealWeeks() {
        val ui = CampusUiDriver()
        ui.launch()
        ui.waitText("本机课表")
        val arguments = InstrumentationRegistry.getArguments()
        val persistencePhase = arguments.getString("persistencePhase")
        val suppliedName = arguments.getString("courseName")
        if (persistencePhase == "verify") {
            val editedName = requireNotNull(suppliedName) + " 已编辑"
            ui.waitText("回到今天")
            ui.waitText(editedName)
            ui.waitContaining("教学楼 B202")
            ui.click("本周")
            ui.waitText(editedName)
            ui.waitDescription("课程块 $editedName 节次1")
            ui.waitDescription("课程块 $editedName 节次3")
            ui.click("课程")
            ui.click(editedName)
            ui.click("删除课程")
            ui.click("确认删除")
            ui.waitText("课程已删除")
            ui.click("今日")
            assertFalse(ui.hasText(editedName))
            return
        }
        // This action is the initial Stage 1 -> Stage 2 behavioral RED checkpoint.
        ui.click("课程")
        ui.click("设置")
        val today = LocalDate.now()
        ui.enter("学期名称", "设备验收学期")
        ui.enter("开学日期", today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString())
        // Preserve the migrated user's valid longer-term courses; do not shorten their weeks.
        ui.enter("学期周数", ui.existingTermWeeks().coerceAtLeast(10).toString())
        ui.click("编辑时间表");ui.setClock("作息1开始", "07:50")
        ui.recreateActivity()
        ui.waitText("07:50")
        ui.setClock("作息1开始", "08:00");ui.click("保存");ui.waitText("学期与作息")
        ui.waitValue("开学日期", today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString())
        ui.click("保存设置")
        ui.waitText("设置已保存")
        ui.click("课程")
        ui.click("新增课程")
        val name = suppliedName ?: "线性代数验收${SystemClock.uptimeMillis()}"
        ui.enter("课程名称", name)
        ui.enter("教师", "王老师")
        ui.enter("地点", "教学楼 A101")
        ui.enter("安排1星期", today.dayOfWeek.value.toString())
        ui.enter("安排1周次", "1,3,5,8,10")
        ui.enter("安排1节次", "1,3")
        ui.click("保存课程")
        ui.waitText(name)
        ui.click("今日")
        ui.waitText(name)
        ui.waitContaining("08:00")
        ui.waitContaining("10:00")
        ui.click(name)
        ui.waitText("课程详情")
        ui.waitContaining("1,3,5,8,10")
        ui.waitContaining("王老师")
        ui.waitContaining("教学楼 A101")
        ui.click("关闭详情")
        ui.click("本周")
        ui.waitText(name)
        ui.waitDescription("课程块 $name 节次1")
        ui.waitDescription("课程块 $name 节次3")
        assertFalse(ui.hasDescription("课程块 $name 节次2"))
        ui.click("下一周")
        ui.waitContaining("第 2 周")
        assertFalse(ui.hasText(name))
        ui.click("上一周")
        ui.waitText(name)
        ui.click("课程")
        ui.click(name)
        ui.click("编辑课程")
        val editedName = "$name 已编辑"
        ui.enter("课程名称", editedName)
        ui.enter("地点", "教学楼 B202")
        // Recreate with UNSAVED edits after an earlier save: old events must not close/reset it.
        ui.recreateActivity()
        ui.waitText("编辑课程")
        ui.waitContaining(editedName)
        ui.waitContaining("教学楼 B202")
        ui.click("保存课程")
        ui.click("今日")
        ui.waitText(editedName)
        ui.click("本周")
        ui.waitText(editedName)
        ui.waitDescription("课程块 $editedName 节次1")
        ui.capture("week")
        ui.click("今日")
        ui.waitText("回到今天")
        ui.waitContaining("教学楼 B202")
        ui.capture("today")
        ui.recreateActivity()
        ui.waitText("本机课表")
        ui.waitText("回到今天")
        ui.waitText(editedName)
        // An external adb force-stop/relaunch separates seed and verify instrumentations.
        // The ordinary suite continues through deletion; no test is skipped.
        if (persistencePhase == "seed") return
        ui.click("课程")
        ui.click(editedName)
        ui.click("删除课程")
        ui.click("确认删除")
        ui.waitText("课程已删除")
        ui.click("今日")
        assertFalse(ui.hasText(editedName))
        ui.click("本周")
        assertFalse(ui.hasText(editedName))
    }
}

internal class CampusUiDriver {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val packageName get() = instrumentation.targetContext.packageName

    fun launch() {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -f 0x10008000 -n $packageName/${MainActivity::class.java.name}",
        )
        val result = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        assertTrue("Real launcher must succeed", result.contains("Status: ok"))
    }

    fun recreateActivity() {
        var previous: MainActivity? = null
        instrumentation.runOnMainSync {
            previous = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
            checkNotNull(previous).recreate()
        }
        await("New Activity is resumed") {
            var fresh = false
            instrumentation.runOnMainSync {
                fresh = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().any { it !== previous }
            }
            fresh
        }
        instrumentation.waitForIdleSync()
    }

    fun existingTermWeeks(): Int {
        var activity: MainActivity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single()
        }
        return runBlocking { withTimeout(5_000) {
            checkNotNull(activity).timetableDependencies.repository.observeTimetable().first().settings.totalWeeks
        } }
    }

    fun capture(label: String) {
        require(label in setOf("today", "week", "stage3-period-editor-draft"))
        val root = checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        check(root.packageName?.toString() == packageName) { "Refuse to capture a foreign window" }
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder = java.io.File(instrumentation.targetContext.filesDir, "stage2-evidence").apply { mkdirs() }
        try {
            java.io.File(folder, "$label.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { screenshot.recycle() }
    }
    fun captureImportFailure() {
        val root=checkNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        check(root.packageName?.toString()==packageName)
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder=java.io.File(instrumentation.targetContext.filesDir,"stage3-evidence").apply { mkdirs() }
        try { java.io.File(folder,"import-failure.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { bitmap.recycle() }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.also {it.refresh()}?.let(pending::add)
        val result = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        while (pending.isNotEmpty()) {
            check(++visited <= 4_096) { "Unexpected accessibility tree size" }
            val node = pending.removeFirst()
            // Compose reuses accessibility IDs across tabs and input components.
            // Refresh descendants too: refreshing only the root leaves their
            // descriptions, parents and focus/checked state cached from the old UI.
            if(!node.refresh()) continue
            if (node.packageName?.toString() == packageName && node.isVisibleToUser) result += node
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        return result
    }

    fun hasText(value: String) = nodes().any { it.text?.toString() == value }
    fun hasDescription(value: String) = nodes().any { it.contentDescription?.toString() == value }
    fun revealPrivateText(value:String) = seek("private course is visible") {hasText(value)}
    fun choosePdfFormat() = chooseDocumentFormat("PDF")
    fun chooseImageFormat() = chooseDocumentFormat("图片")
    private fun chooseDocumentFormat(format:String) {
        repeat(12) {
            val label=device.findObjects(By.pkg(packageName).text(format)).firstOrNull {!it.visibleBounds.isEmpty}
            if(label!=null) {
                var target:androidx.test.uiautomator.UiObject2=label
                while(!target.isClickable && !target.isSelected && !target.isChecked) target=target.parent ?: throw AssertionError("PDF format control unavailable")
                if(!target.isSelected && !target.isChecked) target.click()
                device.waitForIdle(1000);return
            }
            val row=device.findObject(By.pkg(packageName).clazz("android.widget.HorizontalScrollView").scrollable(true)) ?: return@repeat
            row.scroll(Direction.LEFT,0.7f);device.waitForIdle(1000)
        }
        throw AssertionError("PDF format is not reachable in the actual format selector")
    }
    fun waitPrivateDocumentSource() {
        // The screen intentionally focuses the first course when parsing finishes.
        // Wait without scrolling its format selector, then reveal the summary above it.
        await("private document source is rendered",120_000) {
            device.hasObject(By.pkg(packageName).descContains("课程1使用名称")) || device.hasObject(By.pkg(packageName).text("来源内容"))
        }
        device.waitForIdle(1000)
        repeat(3) {
            val page=device.findObject(By.pkg(packageName).clazz("android.widget.ScrollView").scrollable(true)) ?: return@repeat
            val r=page.visibleBounds;page.setGestureMargins(8,32,(r.width()-40).coerceAtLeast(8),32)
            page.scroll(Direction.UP,0.8f);device.waitForIdle(1000)
        }
        waitText("来源内容")
    }
    /** Long correction forms require explicit upward scrolling, not a blind bidirectional search. */
    fun scrollImportToTop() {
        repeat(100) {
            if(device.findObjects(By.pkg(packageName).text("收起修改")).any {!it.visibleBounds.isEmpty}) return
            val page=device.findObject(By.pkg(packageName).clazz("android.widget.ScrollView")) ?: throw AssertionError("Import page scroll container unavailable")
            val r=page.visibleBounds
            // UiObject2.scroll's end-event boolean is unreliable on this Compose
            // form. Swipe inside the observed page and stop on the actual header.
            device.swipe(r.centerX(),r.top+r.height()/4,r.centerX(),r.bottom-r.height()/6,25)
            device.waitForIdle(500)
        }
        waitText("收起修改")
    }
    fun revealCourseNameField(index:Int) {
        repeat(100) {
            if(device.findObjects(By.pkg(packageName).descContains("课程${index}使用名称")).any {!it.visibleBounds.isEmpty}) return
            val page=device.findObject(By.pkg(packageName).clazz("android.widget.ScrollView")) ?: throw AssertionError("Import page scroll container unavailable")
            val r=page.visibleBounds
            device.swipe(r.centerX(),r.bottom-r.height()/5,r.centerX(),r.top+r.height()/3,25)
            device.waitForIdle(500)
        }
        throw AssertionError("Named course field is not reachable")
    }
    fun waitPrivateWeek(monday:LocalDate) {
        val range="$monday 至 ${monday.plusDays(6)}"
        await("private week range is visible") {device.hasObject(By.pkg(packageName).text(range))}
    }
    /** The real weekly grid has independent horizontal and vertical viewports. */
    fun revealPrivateWeeklyCourse(name:String,date:LocalDate) {
        val card=By.pkg(packageName).descStartsWith("课程块 $name ")
        // Compose omits fully clipped course semantics. Align its weekday before
        // querying the course block; absence before scrolling is not a data error.
        fun visible(text:String)=device.findObjects(By.pkg(packageName).text(text)).any {!it.visibleBounds.isEmpty}
        var attempts=0
        while(!visible("时间") && attempts++<12) {
            val row=device.findObject(By.pkg(packageName).clazz("android.widget.HorizontalScrollView").scrollable(true)) ?: break
            row.scroll(Direction.LEFT,0.8f);device.waitForIdle(1000)
        }
        val weekday="周"+listOf("一","二","三","四","五","六","日")[date.dayOfWeek.value-1]
        attempts=0
        while(!visible(weekday) && attempts++<12) {
            val row=device.findObject(By.pkg(packageName).clazz("android.widget.HorizontalScrollView").scrollable(true)) ?: break
            row.scroll(Direction.RIGHT,0.55f);device.waitForIdle(1000)
        }
        assertTrue("Actual course weekday must be reachable in the weekly grid",visible(weekday))
        repeat(24) {
            if(device.findObjects(card).any {!it.visibleBounds.isEmpty}) return
            val column=device.findObject(By.pkg(packageName).clazz("android.widget.ScrollView").scrollable(true)) ?: return@repeat
            val r=column.visibleBounds
            column.setGestureMargins(8,32,(r.width()-40).coerceAtLeast(8),32)
            column.scroll(Direction.DOWN,0.6f);device.waitForIdle(1000)
        }
        throw AssertionError("Private course block is not visible in the actual weekly grid")
    }
    /** Reuses the real DocumentsUI selection used by the private document probe.
     * No replacement URI/provider and no unrelated file is selected. */
    fun chooseSystemDocument(displayName:String,preferImages:Boolean=false) {
        val deadline=SystemClock.uptimeMillis()+25_000
        var rootsOpened=false;var downloadsOpened=false;var qqOpened=false
        var imagesOpened=false
        fun activate(node:androidx.test.uiautomator.UiObject2?):Boolean {
            return try {
                // Tap the exact visible label. Climbing to a recycler/drawer
                // ancestor can hit a different card or storage root at its centre.
                val target=node?.takeIf {it.isEnabled && !it.visibleBounds.isEmpty} ?: return false
                target.click();true
            } catch(_:androidx.test.uiautomator.StaleObjectException) {false}
        }
        fun text(value:String)=device.findObjects(By.pkg("com.android.documentsui").text(value)).firstOrNull {!it.visibleBounds.isEmpty}
        fun roots()=device.findObjects(By.pkg("com.android.documentsui").desc("显示根目录")).firstOrNull()
            ?: device.findObject(By.pkg("com.android.documentsui").desc("Show roots"))
        while(SystemClock.uptimeMillis()<deadline) {
            if(activate(text(displayName))) {
                if(device.wait(androidx.test.uiautomator.Until.gone(By.pkg("com.android.documentsui")),5_000) && device.currentPackageName==packageName) return
            }
            if(preferImages) {
                // User-confirmed location: Images -> QQ. Downloads/QQ contains PDFs.
                val rootList=device.findObject(By.res("com.android.documentsui","roots_list"))
                if(!rootsOpened && activate(roots())) rootsOpened=true
                else if(rootsOpened && !imagesOpened && activate(rootList?.findObject(By.text("图片")) ?: rootList?.findObject(By.text("Images")))) imagesOpened=true
                else if(imagesOpened && !qqOpened && activate(text("QQ"))) qqOpened=true
                else if(qqOpened) device.findObject(By.res("com.android.documentsui","dir_list"))?.scroll(Direction.DOWN,0.6f)
                SystemClock.sleep(250)
                continue
            }
            // The user's authorized originals are also stored in Download/QQ.
            // Open that visible child folder once; still select only the exact name.
            if(!qqOpened && activate(text("QQ"))) qqOpened=true
            else if(!downloadsOpened && !qqOpened && activate(text("下载") ?: text("Downloads"))) downloadsOpened=true
            else if(!rootsOpened && activate(roots())) rootsOpened=true
            SystemClock.sleep(250)
        }
        throw AssertionError("Synthetic document not visible in system picker; no fallback selected")
    }
    fun waitText(value: String) = await("text: $value") { hasText(value) }
    fun revealText(value: String) = seek("visible text after scrolling: $value") { hasText(value) }
    fun waitContaining(value: String) = await("content: $value") { nodes().any { it.text?.contains(value) == true } }
    fun waitDescription(value: String) = await("description: $value") { hasDescription(value) }
    fun waitValue(description: String, value: String) = seek("retained value: $description") {
        editable(description)?.text?.contains(value) == true
    }

    fun click(value: String) {
        seek("enabled control: $value") {
            var node=nodes().firstOrNull {it.text?.toString()==value}
            while(node!=null && !node.isClickable) {node=node.parent;node?.refresh()}
            if(node?.isEnabled==true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) true
            else {
                val label=device.findObjects(By.pkg(packageName).text(value)).firstOrNull {!it.visibleBounds.isEmpty}
                var target=label
                while(target!=null && !target.isClickable) target=target.parent
                if(target?.isEnabled==true) {target.click();true} else false
            }
        }
        settle()
    }

    /** Only for idempotent editor-open actions, never apply/save/confirmation. */
    fun openDialog(control:String,title:String) {
        repeat(3) {
            if(device.hasObject(By.pkg(packageName).text(title))) return
            click(control)
            if(device.wait(androidx.test.uiautomator.Until.hasObject(By.pkg(packageName).text(title)),3_000)) return
        }
        waitText(title)
    }

    /** Selected Compose tabs expose selected=true and deliberately omit ACTION_CLICK. */
    fun selectTab(value:String) {
        seek("selected tab: $value") {
            var node=nodes().firstOrNull {it.text?.toString()==value}
            var selected=false
            while(node!=null && !node.isClickable && !node.isSelected) node=node.parent
            if(node?.isEnabled==true) {
                selected=node.isSelected || node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            selected
        }
        settle()
    }

    private fun settle() {
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.waitForIdle(100, 3_000)
    }

    fun clickDescription(description:String) {
        seek("enabled named control") {
            var node=nodes().firstOrNull {it.contentDescription?.toString()==description}
            repeat(5) {if(node?.isClickable!=true) node=node?.parent}
            node?.takeIf {it.isEnabled}?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
        }
        settle()
    }
    fun setSwitch(description:String,checked:Boolean) {
        fun control():AccessibilityNodeInfo? {
            var node=nodes().firstOrNull {it.contentDescription?.toString()==description}
            repeat(6) {if(node?.isCheckable!=true) node=node?.parent}
            node?.refresh() // Parent accessibility state may be cached after a Compose toggle.
            return node?.takeIf {it.isCheckable}
        }
        await("switch is available") {control()!=null}
        if(control()?.isChecked!=checked) clickDescription(description)
        await("switch state") {control()?.isChecked==checked}
    }
    fun setClock(description:String,value:String) {
        clickDescription(description)
        await("platform time picker") {device.hasObject(By.res("android","timePicker"))}
        // Reuse the platform dialog's own text-input mode and native fields.
        val toggle=device.findObject(By.res("android","toggle_mode"))
        if(device.findObject(By.res("android","input_hour"))==null) {
            checkNotNull(toggle) {"Platform time input toggle unavailable"}.click()
        }
        await("native hour and minute inputs") {
            device.findObject(By.res("android","input_hour"))!=null &&
                device.findObject(By.res("android","input_minute"))!=null
        }
        val parts=value.split(":")
        checkNotNull(device.findObject(By.res("android","input_hour"))).text=parts[0]
        checkNotNull(device.findObject(By.res("android","input_minute"))).text=parts[1]
        checkNotNull(device.findObject(By.res("android","button1"))).click()
        waitText(value)
    }
    fun enter(description: String, value: String) {
        seek("editable field: $description") {
            val target = editable(description)
            target?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }) == true
        }
        settle()
    }

    private fun editable(description: String): AccessibilityNodeInfo? {
        // Compose may merge the decoration's label with additional descriptions or put the
        // real editing action on a descendant. Resolve the named field, then its editable node.
        val anchors = nodes().filter {
            it.contentDescription?.contains(description) == true || it.hintText?.contains(description) == true
        }
        for (anchor in anchors.asReversed()) {
            var ancestor = anchor.parent
            repeat(4) {
                val candidate = ancestor
                if (candidate != null && candidate.isVisibleToUser && candidate.isEnabled && candidate.isEditable &&
                    candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return candidate
                ancestor = candidate?.parent
            }
            val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(anchor) }
            var visited = 0
            while (pending.isNotEmpty() && visited++ < 64) {
                val node = pending.removeFirst()
                if (node.isVisibleToUser && node.isEnabled && node.isEditable &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return node
                repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
            }
        }
        return null
    }

    /** Search real scroll containers, then return upward once; never click hidden nodes. */
    private fun seek(description: String, action: () -> Boolean) {
        var attempts = 0
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var lastScroll = 0L
        await(description) {
            if (action()) true else {
                val now = SystemClock.uptimeMillis()
                if (now - lastScroll >= 250 && attempts < 24) {
                    // Prefer the page over nested horizontal format selectors.
                    val scrolled = nodes().sortedBy { if(it.className?.contains("Horizontal")==true) 1 else 0 }.any { node ->
                        node.isScrollable && node.actionList.any { it.id == direction } && node.performAction(direction)
                    }
                    attempts++
                    if (!scrolled || attempts == 12) direction = if(direction==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    lastScroll = now
                }
                false
            }
        }
    }

    private fun await(description: String, timeoutMillis:Long=15_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(50)
        }
        val diagnostic = org.json.JSONArray()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.also {it.refresh()}?.let(pending::add)
        var visited = 0
        val safeDescriptions = setOf("学期名称", "开学日期", "学期周数", "作息1开始", "课程名称", "教师", "地点", "粘贴 JSON")
        val safeTexts = setOf("今日", "本周", "课程", "设置", "本机课表", "学期与作息", "重新读取", "正在读取本机课表…",
            "导入课表", "JSON", "通知文字", "来源内容", "读取内容", "核对并预览", "来源无法读取", "Invalid import structure", "无法读取导入来源，请检查后重试。",
            "继续确认", "确认此日期", "已确认日期", "确认作为新课程", "请核对相对日期的基准和实际日期。",
            "来源含待核对信息，请对照原文、安排和归属后重新检查。", "请明确停课、调课或加课对应的原安排与日期。",
            "采用来源时间", "采用本机作息", "确认上述课程归属", "课程归属已确认", "确认该安排暂不排期",
            "确认保留来源中未知的可选信息", "请核对各条安排的课程归属。", "尚有未解析原文，请打开证据页核对。",
            "请核对原文时间及本机作息，明确选择使用哪一组时间。")
        while (pending.isNotEmpty() && visited++ < 4096) {
            val node = pending.removeFirst()
            if (node.packageName?.toString() == packageName) diagnostic.put(org.json.JSONObject().apply {
                put("class", node.className?.toString()); put("visible", node.isVisibleToUser)
                put("editable", node.isEditable); put("enabled", node.isEnabled)
                put("checkable",node.isCheckable);put("checked",node.isChecked)
                put("scrollable",node.isScrollable)
                put("description", node.contentDescription?.toString()?.takeIf { it in safeDescriptions })
                put("descriptionLabelMatches", org.json.JSONArray(safeDescriptions.filter { node.contentDescription?.contains(it) == true }))
                put("hintLabelMatches", org.json.JSONArray(safeDescriptions.filter { node.hintText?.contains(it) == true }))
                put("staticText", node.text?.toString()?.takeIf { it in safeTexts })
                if(node.isEditable && node.contentDescription?.contains("粘贴 JSON")==true) {
                    put("inputLength",node.text?.length ?: 0)
                    put("validJsonObject",runCatching { org.json.JSONObject(node.text?.toString().orEmpty());true }.getOrDefault(false))
                }
                put("actions", org.json.JSONArray(node.actionList.map { it.id }))
                val tokens=Regex("导入验收[0-9]+").findAll(node.text?.toString().orEmpty()).map { it.value }.distinct().toList()
                if(tokens.isNotEmpty()) {
                    put("syntheticTokens",org.json.JSONArray(tokens))
                    put("textLength",node.text?.length ?: 0)
                    val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds);put("bounds",bounds.toShortString())
                }
                if(node.isEditable) {
                    put("inputLength",node.text?.length ?: 0)
                    put("validJsonObject",runCatching { org.json.JSONObject(node.text?.toString().orEmpty());true }.getOrDefault(false))
                }
            })
            repeat(node.childCount) { node.getChild(it)?.let(pending::add) }
        }
        val folder = java.io.File(instrumentation.targetContext.filesDir, "stage2-evidence").apply { mkdirs() }
        java.io.File(folder, "input-diagnostic.json").writeText(diagnostic.toString(2))
        throw AssertionError("Timed out: $description")
    }
}
