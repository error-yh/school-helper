package com.aggapp.app

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.aggapp.app.ui.MainActivity
import com.lingion.sleepy.data.local.TimetableDatabase
import com.lingion.sleepy.domain.TimetableRules
import com.lingion.sleepy.domain.model.MeetingKind
import com.lingion.sleepy.domain.model.ScheduledCourse
import com.lingion.sleepy.domain.model.TimetableSnapshot
import com.lingion.sleepy.domain.model.TimeRange
import com.lingion.sleepy.domain.imports.ImportLogicalBaseDisplay
import java.time.LocalTime
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real PDF -> real host UI -> Room -> external force-stop -> verify/cleanup.
 * No account storage, hidden review decisions, fixture URI, or raw assertion output.
 */
@RunWith(AndroidJUnit4::class)
class CampusPrivatePdfImportTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun hash(value:Any?):String=MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray())
        .joinToString("") {"%02x".format(it)}
    private fun ids(array:JSONArray)= (0 until array.length()).map {array.getLong(it)}.toSet()
    private fun counts(snapshot:TimetableSnapshot):List<Int> {
        val db=TimetableDatabase.open(context,snapshot.scopeToken)
        return try {listOf("schedule_provenance","import_normalization_state").map {table ->
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {cursor ->check(cursor.moveToFirst());cursor.getInt(0)}
        }} finally {db.close()}
    }
    @Test(timeout=600_000) fun actualPdfSaveAndExternalRestart() {
        val args=InstrumentationRegistry.getArguments()
        val phase=args.getString("persistencePhase")
        assumeTrue("Private PDF host verification requires an explicit seed or verify phase",phase in setOf("seed","verify"))
        val alias=checkNotNull(args.getString("courseName"))
        require(alias in setOf("pdf-a","pdf-b","pdf-c","image-list","image-grid","image-weeks"))
        val folder=File(context.filesDir,"stage3-private-probe").apply {mkdirs()}
        fun phase(value:String) {File(folder,"$alias-host-phase.txt").writeText(value)}
        val checkpoint=File(folder,"$alias-host-checkpoint.json")
        val ui=CampusUiDriver()
        try {
            phase("launching");ui.launch();ui.waitText("本机课表");phase("host-ready")
            var activity:MainActivity?=null
            instrumentation.runOnMainSync {activity=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()}
            val repository=checkNotNull(activity).timetableDependencies.repository
            val imports=checkNotNull(checkNotNull(activity).timetableDependencies.imports)
            fun snapshot()=runBlocking {withTimeout(10_000) {repository.observeTimetable().first()}}
            if(phase=="seed") {
                check(!checkpoint.exists()) {"An earlier private PDF checkpoint needs verification first"}
                val name=String(Base64.decode(checkNotNull(args.getString("probeDisplayNameB64")),Base64.NO_WRAP),Charsets.UTF_8)
                val expected=mapOf("pdf-a" to (12 to 22),"pdf-b" to (9 to 23),"pdf-c" to (14 to 28),"image-list" to (12 to 12),"image-grid" to (20 to 20),"image-weeks" to (74 to 74)).getValue(alias)
                val before=snapshot();val originalIds=before.courses.map {it.id}.toSet();val beforeCounts=counts(before)
                var retained=false
                try {
                    assertTrue("Confirmed semester is required",before.settings.startDate==LocalDate.of(2026,9,7))
                    ui.click("导入课表")
                    if(alias.startsWith("image-")) ui.chooseImageFormat() else ui.choosePdfFormat()
                    phase("format-selected");ui.click("选择课表文件或图片");phase("picker-opened")
                    ui.chooseSystemDocument(name,preferImages=alias.startsWith("image-"));phase("file-selected")
                    ui.waitPrivateDocumentSource()
                    phase("source-loaded")
                    ui.revealText("来源课程条目：${expected.first} · 安排：${expected.second}")
                    if(alias in setOf("image-grid","image-weeks")) {
                        // Explicit USER edits from the original image, never supplied to OCR.
                        // Genuine recognition typos are distinct from automatic parser repairs.
                        val titles=capturePrivateReviewState(checkNotNull(activity),File(folder,"$alias-host-review-private.json"))
                        ui.click("修改识别结果")
                        val corrections=if(alias=="image-grid") mapOf("数据仓库与数据挖据" to "数据仓库与数据挖掘","采统思维训练" to "系统思维训练") else mapOf(
                            "△药品质量(制" to "△药品质量(制药)","△药品质量(药" to "△药品质量(药剂)",
                            "代中医药研发与职业技能综合实训" to "现代中医药研发与职业技能综合实训","品质量" to "药品质量","制" to "制剂/制药")
                        val edits=titles.mapIndexedNotNull {i,title->corrections[title]?.let {(i+1) to it}}
                        for((index,title) in edits) {
                            ui.revealCourseNameField(index)
                            ui.enter("课程${index}使用名称",title)
                            ui.clickDescription("应用课程${index}名称")
                        }
                        ui.scrollImportToTop();ui.click("收起修改")
                        phase("user-title-corrections-${edits.size}")
                    }
                    ui.revealText("核对并预览")
                    assertTrue("Source review must not write",snapshot()==before)
                    ui.click("核对并预览")
                    phase("review-clicked")
                    capturePrivateReviewState(checkNotNull(activity),File(folder,"$alias-host-review-private.json"))
                    ui.waitText("导入预览")
                    // This first-import acceptance must never update/delete an
                    // existing user course, even if a source identity collides.
                    ui.waitContaining("更新 0，替换移除 0。")
                    ui.waitDescription("冲突条目 0")

                    ui.click("继续确认")
                    assertTrue("Opening confirmation must not write",snapshot()==before)
                    ui.click("取消");assertTrue("Cancel must not write",snapshot()==before)
                    ui.click("继续确认");ui.click("确认追加");ui.revealText("导入完成。可关闭此页查看课程。")
                    phase("saved")
                    val saved=snapshot();val added=saved.courses.filter {it.id !in originalIds}
                    if(alias=="image-grid") verifyOriginalGridScopesAndCorrectedTitles(runBlocking {imports.readImportTarget(saved.settings.id)}.logicalBaseDetails.filter {it.reference.courseId in added.map {c->c.id}})
                    if(alias=="image-weeks") verifyOriginalMatrixScopesAndCorrectedTitles(runBlocking {imports.readImportTarget(saved.settings.id)}.logicalBaseDetails.filter {it.reference.courseId in added.map {c->c.id}})
                    assertTrue("Private source must create courses in this first-import scenario",added.isNotEmpty())
                    assertTrue("Existing courses must remain unchanged",saved.courses.filter {it.id in originalIds}==before.courses)
                    assertTrue("Semester and period settings must remain unchanged",saved.settings==before.settings && saved.periods==before.periods)
                    assertTrue("One import must be atomic",saved.revision==before.revision+1)
                    assertTrue("Selected document name must be retained",added.all {c ->(c.provenance+c.meetings.flatMap {it.provenance}).any {it.sourceLocator=="document:display-name" && it.rawText==name}})
                    checkpoint.writeText(JSONObject().put("alias",alias).put("originalIds",JSONArray(originalIds.toList()))
                        .put("addedIds",JSONArray(added.map {it.id})).put("originalHash",hash(before.courses))
                        .put("addedHash",hash(added)).put("settingsHash",hash(before.settings)).put("periodsHash",hash(before.periods))
                        .put("scopeHash",hash(before.scopeToken)).put("revision",saved.revision)
                        .put("originalProvenance",beforeCounts[0]).put("originalNormalization",beforeCounts[1])
                        .put("sourceArchives",expected.first).put("sourceArrangements",expected.second).toString())
                    ui.click("查看课表");retained=true
                } finally {
                    if(!retained) {
                        snapshot().courses.filter {c ->c.id !in originalIds && (c.provenance+c.meetings.flatMap {it.provenance}).any {it.sourceLocator=="document:display-name" && it.rawText==name}}
                            .forEach {runBlocking {repository.deleteCourse(it.id)}}
                        if(checkpoint.exists()) checkpoint.delete()
                    }
                }
                return
            }
            val expected=JSONObject(checkpoint.readText());check(expected.getString("alias")==alias)
            val originalIds=ids(expected.getJSONArray("originalIds"));val addedIds=ids(expected.getJSONArray("addedIds"))
            val restored=snapshot();val added=restored.courses.filter {it.id in addedIds}
            assertTrue("Local profile changed",hash(restored.scopeToken)==expected.getString("scopeHash"))
            assertTrue("Real PDF data changed after process restart",hash(added)==expected.getString("addedHash"))
            try {
                if(alias=="image-grid") verifyOriginalGridScopesAndCorrectedTitles(runBlocking {imports.readImportTarget(restored.settings.id)}.logicalBaseDetails.filter {it.reference.courseId in addedIds})
                if(alias=="image-weeks") verifyOriginalMatrixScopesAndCorrectedTitles(runBlocking {imports.readImportTarget(restored.settings.id)}.logicalBaseDetails.filter {it.reference.courseId in addedIds})
                assertTrue("Revision changed across restart",restored.revision==expected.getLong("revision"))
                assertTrue("Original courses changed",hash(restored.courses.filter {it.id in originalIds})==expected.getString("originalHash"))
                assertTrue("Original settings changed",hash(restored.settings)==expected.getString("settingsHash") && hash(restored.periods)==expected.getString("periodsHash"))
                val c=added.first {course ->course.meetings.any {it.kind!=MeetingKind.UNSCHEDULED}}
                val m=c.meetings.first {it.kind!=MeetingKind.UNSCHEDULED}
                val date=if(m.kind==MeetingKind.DATE_SPECIFIC) m.dates.minOrNull()!! else checkNotNull(TimetableRules.dateOfWeek(restored.settings,m.weeks.minOrNull()!!,m.day))
                assertTrue("Saved source course has no occurrence on its actual date",TimetableRules.occurrencesOn(restored,date).any {it.course.id==c.id})
                ui.selectTab("今日");ui.click("选择日期");ui.enter("浏览日期",date.toString());ui.click("查看");ui.revealPrivateText(c.courseName)
                ui.selectTab("本周");ui.click("回本周")
                var shownMonday=TimetableRules.mondayOf(LocalDate.now())
                ui.waitPrivateWeek(shownMonday)
                val delta=ChronoUnit.WEEKS.between(shownMonday,TimetableRules.mondayOf(date)).toInt()
                require(kotlin.math.abs(delta)<=60)
                repeat(kotlin.math.abs(delta)) {
                    shownMonday=shownMonday.plusWeeks(if(delta<0) -1 else 1)
                    ui.click(if(delta<0) "上一周" else "下一周");ui.waitPrivateWeek(shownMonday)
                }
                ui.revealPrivateWeeklyCourse(c.courseName,date)
                File(folder,"$alias-host-result.json").writeText(JSONObject().put("alias",alias).put("sourceArchives",expected.getInt("sourceArchives"))
                    .put("sourceArrangements",expected.getInt("sourceArrangements")).put("newCourses",added.size)
                    .put("externalProcessRestartMatched",true).put("dailyAndWeeklyVisible",true).toString())
            } finally {
                // IDs and complete unchanged-content hash authorize only these test imports.
                val current=snapshot();val own=current.courses.filter {it.id in addedIds}
                check(hash(own)==expected.getString("addedHash")) {"Imported data was edited externally; preserve it"}
                own.forEach {runBlocking {repository.deleteCourse(it.id)}}
                val cleaned=snapshot();val afterCounts=counts(cleaned)
                assertTrue("Original course data must survive cleanup",hash(cleaned.courses)==expected.getString("originalHash"))
                assertTrue("Imported provenance must be removed",afterCounts[0]==expected.getInt("originalProvenance"))
                assertTrue("Imported normalization payloads must be removed",afterCounts[1]==expected.getInt("originalNormalization"))
                check(checkpoint.delete())
            }
        } catch(failure:Throwable) {
            File(folder,"$alias-host-error.txt").writeText(failure.stackTraceToString())
            runCatching {
                var active:MainActivity?=null
                instrumentation.runOnMainSync {active=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().singleOrNull()}
                active?.let {capturePrivateReviewState(it,File(folder,"$alias-host-review-private.json"))}
            }
            // This diagnostic contains private source text and must never enter test output.
            val pending=ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add)
            val tree=JSONArray();var visited=0
            while(pending.isNotEmpty() && visited++<4096) {
                val node=pending.removeFirst()
                if(node.packageName?.toString()==context.packageName) tree.put(JSONObject()
                    .put("text",node.text?.toString()).put("description",node.contentDescription?.toString())
                    .put("class",node.className?.toString()).put("checked",node.isChecked).put("selected",node.isSelected)
                    .put("checkable",node.isCheckable).put("clickable",node.isClickable).put("enabled",node.isEnabled))
                repeat(node.childCount) {node.getChild(it)?.let(pending::add)}
            }
            File(folder,"$alias-host-ui-private.json").writeText(tree.toString())
            throw AssertionError("Private PDF host flow failed; detailed evidence remains in the app-private test directory")
        }
    }

    /** Independently transcribed from the supplied grid, excluding two explicitly untimed practices. */
    private fun verifyOriginalGridScopesAndCorrectedTitles(bases:List<ImportLogicalBaseDisplay>) {
        fun key(day:Int,weeks:Set<Int>,sections:Set<Int>)="$day|${weeks.sorted()}|${sections.sorted()}"
        val expected=listOf(
            Triple(key(1,(1..12).toSet(),setOf(1,2)),"机器学习及应用",Unit),
            Triple(key(3,(1..16).toSet(),setOf(1,2)),"数据仓库与数据挖掘",Unit),
            Triple(key(4,(1..12).toSet(),setOf(1,2)),"机器学习及应用",Unit),
            Triple(key(1,(1..4).toSet(),setOf(3,4)),"计算机网络技术",Unit),
            Triple(key(2,(9..16).toSet(),setOf(3,4)),"数据仓库与数据挖掘",Unit),
            Triple(key(3,(1..12).toSet(),setOf(3,4)),"计算机网络技术",Unit),
            Triple(key(5,setOf(1,2),setOf(3,4)),"学科前沿",Unit),
            Triple(key(2,(4..15).toSet(),setOf(10)),"体育(V)篮球",Unit),
            Triple(key(5,setOf(3,4),setOf(3,4)),"学科前沿",Unit),
            Triple(key(5,(5..8).toSet(),setOf(3,4)),"学科前沿",Unit),
            Triple(key(1,(5..12).toSet(),setOf(7,8)),"机器学习及应用",Unit),
            Triple(key(4,(1..16).toSet(),setOf(7,8)),"工程项目管理与经济决策",Unit),
            Triple(key(1,(5..12).toSet(),setOf(9,10)),"计算机网络技术",Unit),
            Triple(key(4,(1..8).toSet(),setOf(12,13,14)),"创新教育基础与实践",Unit),
            Triple(key(2,setOf(11),setOf(12,13)),"系统思维训练",Unit),
            Triple(key(5,setOf(14),setOf(12,13)),"创业实践",Unit),
            Triple(key(2,setOf(9,10),setOf(12,13,14)),"系统思维训练",Unit),
            Triple(key(5,setOf(12,13),setOf(12,13,14)),"创业实践",Unit),
        ).map {it.first to it.second}.sortedBy {it.first}
        val actual=bases.filter {it.original.kind==MeetingKind.RECURRING}.map {b->key(b.original.day,b.original.weeks,b.original.sections) to b.title}.sortedBy {it.first}
        if(expected!=actual) File(context.filesDir,"stage3-private-probe/image-grid-host-saved-fields-private.json").writeText(JSONObject()
            .put("expected",JSONArray(expected.map {JSONArray(listOf(it.first,it.second))}))
            .put("actual",JSONArray(actual.map {JSONArray(listOf(it.first,it.second))})).toString())
        assertTrue("Original grid scopes and explicitly corrected titles must match all 18 arrangements",expected==actual)
        assertTrue("Two source practices must remain untimed",bases.count {it.original.kind==MeetingKind.UNSCHEDULED}==2)
        // Independent transcription of the actual image clock axis, not host defaults.
        val sourceClocks=listOf("08:00-08:45","08:55-09:40","10:05-10:50","11:00-11:45","12:00-12:45","12:55-13:40","14:00-14:45","14:55-15:40","16:05-16:50","17:00-17:45","17:55-18:35","18:45-19:30","19:40-20:25","20:35-21:20")
            .map {v->val p=v.split('-');TimeRange(LocalTime.parse(p[0]),LocalTime.parse(p[1]))}
        bases.filter {it.original.kind==MeetingKind.RECURRING}.forEach {b->
            assertTrue("Printed clock intervals including breaks must survive",b.original.sections.sorted().map {sourceClocks[it-1]}==b.timeRanges.sortedBy {it.start})
        }
    }

    /** Independent transcription of the source matrix. No dates/times are invented for weekend notes. */
    private fun verifyOriginalMatrixScopesAndCorrectedTitles(bases:List<ImportLogicalBaseDisplay>) {
        fun key(w:Int,d:Int,s:Set<Int>)="$w|$d|${s.sorted()}"
        fun title(v:String)=v.replace(Regex("[△▲Δ]"),"").trim()
        val expected=mutableListOf<Pair<String,String>>()
        fun add(w:Int,d:Int,first:Int,last:Int,name:String) {expected+=key(w,d,(first..last).toSet()) to name}
        fun monday(w:Int) {add(w,1,1,3,"制剂/制药");add(w,1,6,8,"药管法")}
        fun tuesday(w:Int) {add(w,2,1,3,"生药学");add(w,2,6,8,"综合实训")}
        fun normal(w:Int) {monday(w);tuesday(w);add(w,3,6,8,"药管法");add(w,4,6,8,"综合实训");add(w,5,1,3,"药品质量")}
        listOf(1,2,7).forEach(::normal)
        monday(3);tuesday(3);add(3,3,1,10,"生药学(药剂1)");add(3,4,6,8,"综合实训")
        monday(4);add(4,2,1,10,"生药学(制药2)");add(4,3,1,10,"生药学(制药1)")
        add(5,4,1,10,"生药学(药剂1)");add(5,5,1,3,"药品质量")
        monday(6);add(6,2,1,10,"生药学(制药1)");add(6,4,1,10,"生药学(制药2)");add(6,5,1,3,"药品质量")
        for(w in listOf(8,9)) {
            monday(w);tuesday(w);add(w,3,6,8,"药管法")
            add(w,4,1,5,if(w==8) "生药学(药剂1)" else "生药学(药剂2)")
            add(w,4,6,10,if(w==8) "生药学(制药1)" else "生药学(制药2)")
            add(w,5,1,3,"药品质量");add(w,5,6,10,if(w==8) "药品质量(制药)" else "药品质量(药剂)")
        }
        add(10,1,1,10,"现代中医药研发与职业技能综合实训");add(10,2,1,10,"现代中医药研发与职业技能综合实训")
        monday(12);add(12,2,1,3,"生药学");add(12,3,6,8,"药管法");add(12,4,1,5,"生药学(制药)");add(12,4,6,10,"生药学(药剂)");add(12,5,1,3,"药品质量")
        monday(13);add(13,2,1,3,"生药学");add(13,5,1,2,"药品质量")
        add(14,1,1,2,"制剂/制药");add(14,1,6,8,"药管法");add(14,2,1,4,"生药学")
        assertTrue("Independent source matrix must contain 72 scheduled cells",expected.size==72)
        val actual=bases.filter {it.original.kind==MeetingKind.RECURRING}.flatMap {b->b.original.weeks.map {w->key(w,b.original.day,b.original.sections) to title(b.title)}}
        if(expected.sortedBy {it.first}!=actual.sortedBy {it.first}) File(context.filesDir,"stage3-private-probe/image-weeks-host-saved-fields-private.json").writeText(JSONObject()
            .put("expected",JSONArray(expected.sortedBy {it.first}.map {JSONArray(listOf(it.first,it.second))}))
            .put("actual",JSONArray(actual.sortedBy {it.first}.map {JSONArray(listOf(it.first,it.second))})).toString())
        assertTrue("All source matrix week/day/section scopes and corrected titles must match",expected.sortedBy {it.first}==actual.sortedBy {it.first})
        val unknown=bases.filter {it.original.kind==MeetingKind.UNSCHEDULED}
        assertTrue("Two weekend notes must remain untimed",unknown.size==2 && unknown.all {it.original.sections.isEmpty() && it.original.dates.isEmpty() && it.original.customTime==null})
        assertTrue("Original special-date text must survive",listOf("09.20","10.10").all {date->unknown.any {b->b.original.provenance.any {it.rawText.contains(date)}}})
    }

    /** Read-only failure diagnosis of this app's existing ViewModels; never supplies decisions. */
    private fun capturePrivateReviewState(activity:MainActivity,file:File):List<String> {
        var titles=emptyList<String>()
        instrumentation.runOnMainSync {
            val pending=ArrayDeque<Any>().apply {add(activity.viewModelStore)}
            val seen=java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any,Boolean>())
            while(pending.isNotEmpty() && seen.size<128) {
                val value=pending.removeFirst();if(!seen.add(value)) continue
                val name=value.javaClass.name
                if(name=="com.lingion.sleepy.ui.ImportFlowViewModel") {
                    val flow=value.javaClass.getMethod("getState").invoke(value) as kotlinx.coroutines.flow.StateFlow<*>
                    val state=checkNotNull(flow.value)
                    val result=JSONObject()
                    listOf("Problems","TimeChoices","AcceptedUnscheduled","AcceptedOptional").forEach {key ->
                        result.put(key,state.javaClass.getMethod("get$key").invoke(state).toString())
                    }
                    val session=state.javaClass.getMethod("getSession").invoke(state) as? com.lingion.sleepy.domain.imports.ImportReviewSession
                    titles=session?.source?.format?.draft?.courses.orEmpty().map {it.name}
                    result.put("source",session?.source?.format?.toString())
                    file.writeText(result.toString());return@runOnMainSync
                }
                if(value is Map<*,*>) value.values.filterNotNull().forEach(pending::add)
                else if(name=="androidx.lifecycle.ViewModelStore" || name=="androidx.navigation.NavControllerViewModel") {
                    value.javaClass.declaredFields.filter {Map::class.java.isAssignableFrom(it.type)}.forEach {field ->
                        field.isAccessible=true;field.get(value)?.let(pending::add)
                    }
                }
            }
        }
        return titles
    }
}
