/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lingion.sleepy.importing.ocr.*
import com.lingion.sleepy.importing.formats.FormatParseContext
import com.lingion.sleepy.domain.imports.FrozenImportSource
import com.lingion.sleepy.importing.RichTimetableDraft
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only SAF host: never creates courses or changes the real app/profile.
 * Selection is opt-in. Filename and raw recognition stay out of public test output.
 */
class PrivateDocumentProbeActivity : Activity() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {text="本地课表识别测试"})
    }
    internal fun openDocument() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type="*/*";addCategory(Intent.CATEGORY_OPENABLE);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        },17)
    }
    @Deprecated("Test-only framework activity result")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=17) return
        val uri=data?.data
        if(resultCode!=RESULT_OK || uri==null) { result.complete(null);return }
        scope.launch {
            try {
                val engine=InstrumentationRegistry.getArguments().getString("probeEngine")
                require(engine==null || engine in setOf("rapid","rapid-words","rapid-structure")) {"Unknown private probe engine"}
                if(engine!=null) {
                    val source=withContext(Dispatchers.Default) {
                        val type=Class.forName("com.lingion.sleepy.ui.RapidOcrTrial")
                        type.getMethod("recognize",android.content.Context::class.java,android.net.Uri::class.java)
                            .invoke(null,this@PrivateDocumentProbeActivity,uri) as FrozenImportSource
                    }
                    val label=String(Base64.decode(InstrumentationRegistry.getArguments().getString("probeDisplayNameB64"),Base64.NO_WRAP),Charsets.UTF_8)
                    result.complete(LoadedImportDocument.Parsed(source.withDocumentDisplayName(label)))
                    return@launch
                }
                val loaded=AndroidImportDocumentLoader(this@PrivateDocumentProbeActivity).load(uri,ImportDocumentKind.AUTO,
                    20,LocalDate.of(2026,9,7),LocalDate.of(2026,10,28))
                // Investigation only. The final loader result/assertion below is retained.
                // Reuse its bounded decoder/engine on a line-cleaned 2x raster to test the
                // hypothesis without changing production mode, models or accepted output.
                if(loaded is LoadedImportDocument.Parsed && loaded.source.format.draft?.courses.isNullOrEmpty() &&
                    contentResolver.getType(uri)?.startsWith("image/")==true) {
                    experiment=withContext(Dispatchers.Default) {
                        val resources=RasterResources(OcrWorkBudget())
                        contentResolver.openInputStream(uri)!!.use {input->
                            AndroidImportImageDecoder(this@PrivateDocumentProbeActivity).withDecoded(input,resources) {decoded->
                                val original=decoded.page
                                val lines=TableLineDetector().detect(original,resources)
                                val width=Math.multiplyExact(original.bitmap.width,2)
                                val height=Math.multiplyExact(original.bitmap.height,2)
                                resources.reserveStage(original.bitmap.width,original.bitmap.height)
                                resources.acquireBitmap(original.bitmap.width,original.bitmap.height).use {
                                    val cleaned=original.bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888,true)
                                    try {
                                        val canvas=android.graphics.Canvas(cleaned)
                                        val white=android.graphics.Paint().apply {color=android.graphics.Color.WHITE}
                                        lines.lines.filter {it.thickness<=3}.forEach {line->
                                            val b=line.bounds;canvas.drawRect(b.left,b.top,b.right,b.bottom,white)
                                        }
                                resources.reserveStage(width,height)
                                resources.acquireBitmap(width,height).use {
                                    val bitmap=android.graphics.Bitmap.createScaledBitmap(cleaned,width,height,true)
                                    try {
                                        val page=OcrRasterPage(1,bitmap,original.originalWidth,original.originalHeight,PageAffineTransform(.5,0.0,0.0,.5,0.0,0.0))
                                        val native=TesseractLocalOcrEngine(this@PrivateDocumentProbeActivity).recognize(page,resources.recognitionOptions(false))
                                        val parsed=OcrTimetableParser().parse(OcrLayoutDocument(listOf(OcrLayoutPage(1,
                                            original.originalWidth,original.originalHeight,native.variants,native.recommendedVariantId,lines.lines))),
                                            OcrLayoutContext(FormatParseContext("private-clean2x-investigation",20),confirmedTermMonday=LocalDate.of(2026,9,7)))
                                        FrozenImportSource.fromOcr(parsed)
                                    } finally {bitmap.recycle()}
                                }
                                    } finally {cleaned.recycle()}
                                }
                            }
                        }
                    }
                }
                result.complete(loaded)
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {result.complete(null)}
        }
    }
    override fun onDestroy() {scope.cancel();super.onDestroy()}
    companion object {
        internal var result=CompletableDeferred<LoadedImportDocument?>()
        internal var experiment:FrozenImportSource?=null
    }
}

@RunWith(AndroidJUnit4::class)
class PrivateDocumentSafTest {
    @Test(timeout=180_000) fun authorizedDocumentUsesFinalLoaderAndYieldsArrangements() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val arguments=InstrumentationRegistry.getArguments()
        val encoded=arguments.getString("probeDisplayNameB64")
        assumeTrue("Private SAF selection is opt-in",!encoded.isNullOrBlank())
        val alias=arguments.getString("probeAlias") ?: error("Missing anonymous sample alias")
        require(alias.matches(Regex("[a-z-]{1,32}")))
        val displayName=String(Base64.decode(encoded,Base64.NO_WRAP),Charsets.UTF_8)
        PrivateDocumentProbeActivity.result=CompletableDeferred()
        PrivateDocumentProbeActivity.experiment=null
        val context=instrumentation.targetContext
        // This device blocks background starts by the self-instrumented library package.
        // Bring AndroidX's existing exported test-only empty activity to the foreground
        // through the shell, as the host UI driver does; no settings/permissions change.
        val bootstrap="${context.packageName}/androidx.test.core.app.InstrumentationActivityInvoker"+'$'+"EmptyActivity"
        // UiAutomation uses Runtime.exec, not a shell parser: quote characters would
        // become part of the component name. This is a constant component, no user input.
        val bootstrapOutput=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am start -W -n $bootstrap"))
            .bufferedReader().use {it.readText()}
        assertTrue("AndroidX foreground bootstrap did not launch: $bootstrapOutput",bootstrapOutput.contains("Status: ok"))
        val scenario=ActivityScenario.launch<PrivateDocumentProbeActivity>(Intent(context,PrivateDocumentProbeActivity::class.java))
        try {
            // Wait for our own resumed activity before launching the external picker.
            scenario.onActivity {it.openDocument()}
            val end=SystemClock.uptimeMillis()+25_000
            var selected=false
            fun find(node:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
                if(node==null) return null
                if(node.packageName?.toString()=="com.android.documentsui" && node.text?.toString()==displayName) return node
                for(index in 0 until node.childCount) find(node.getChild(index))?.let {return it}
                return null
            }
            while(SystemClock.uptimeMillis()<end && !selected) {
                val node=find(instrumentation.uiAutomation.rootInActiveWindow)
                if(node!=null) {
                    var clickable:AccessibilityNodeInfo?=node
                    repeat(5) {if(clickable?.isClickable!=true) clickable=clickable?.parent}
                    selected=clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
                }
                if(!selected) delay(300)
            }
            assertTrue("Authorized sample not visible in system picker; no alternative selected",selected)
            val loaded=withTimeout(120_000) {PrivateDocumentProbeActivity.result.await()}
            assertTrue("Final loader did not return a parsed draft",loaded is LoadedImportDocument.Parsed)
            val source=(loaded as LoadedImportDocument.Parsed).source
            val draft=source.format.draft
            val meetings=draft?.courses.orEmpty().flatMap {it.meetings}
            val fileNameRetained=source.format.sources.any {it.sourceLocator=="document:display-name" && it.rawText==displayName}
            if(meetings.isNotEmpty()) assertTrue("Selected provider display name was not retained as metadata",fileNameRetained)
            // Private diagnosis is deliberately not sent to logcat/test assertion messages.
            val diagnostic=buildJsonObject {
                put("alias",alias);put("profiles",draft?.courses?.size ?: 0);put("meetings",meetings.size)
                put("fileNameRetained",fileNameRetained)
                // Field-level comparison stays private too; a nonempty smoke result alone
                // cannot prove that dates, sparse sections and printed clocks are correct.
                put("courses",courseFields(draft))
                putJsonArray("adapters") {draft?.adapterIds?.forEach {add(it)}}
                putJsonArray("issues") {(source.format.issues+draft?.issues.orEmpty()).forEach {add(it.code)}}
                putJsonArray("pages") {source.ocr?.document?.pages?.forEach {page->add(buildJsonObject {
                    put("number",page.pageNumber);put("width",page.width);put("height",page.height)
                    putJsonArray("lines") {page.lines.forEach {line->add(buildJsonObject {
                        put("vertical",line.vertical);put("thickness",line.thickness)
                        putJsonArray("box") {add(line.bounds.left);add(line.bounds.top);add(line.bounds.right);add(line.bounds.bottom)}
                    })}}
                    putJsonArray("variants") {page.variants.forEach {variant->add(buildJsonObject {
                        put("id",variant.id.name);put("recommended",variant.id==page.recommendedVariantId)
                        putJsonArray("words") {variant.spans.filter {it.level==OcrLevel.WORD}.forEach {span->add(buildJsonObject {
                            put("text",span.rawText);put("confidence",span.confidence)
                            span.position?.bounds?.let {box->putJsonArray("box") {add(box.left);add(box.top);add(box.right);add(box.bottom)}}
                        })}}
                    })}}
                })}}
            }
            val directory=File(context.filesDir,"stage3-private-probe").apply {mkdirs()}
            File(directory,"$alias.json").writeText(diagnostic.toString())
            PrivateDocumentProbeActivity.experiment?.let {experiment->
                val comparison=buildJsonObject {
                    put("method","test-only thin-line-cleanup plus scale2; not the final loader result")
                    put("profiles",experiment.format.draft?.courses?.size ?: 0)
                    put("meetings",experiment.format.draft?.courses.orEmpty().sumOf {it.meetings.size})
                    put("courses",courseFields(experiment.format.draft))
                    putJsonArray("adapters") {experiment.format.draft?.adapterIds?.forEach {add(it)}}
                    putJsonArray("pages") {experiment.ocr?.document?.pages?.forEach {page->add(buildJsonObject {
                        putJsonArray("words") {page.variants.flatMap {it.spans}.filter {it.level==OcrLevel.WORD}.forEach {span->add(buildJsonObject {
                            put("text",span.rawText);put("confidence",span.confidence)
                            span.position?.bounds?.let {b->putJsonArray("box") {add(b.left);add(b.top);add(b.right);add(b.bottom)}}
                        })}}
                    })}}
                }
                File(directory,"$alias-clean2x.json").writeText(comparison.toString())
            }
            assertTrue("Real sample produced no arrangements; anonymous alias=$alias",meetings.isNotEmpty())
            if(alias=="image-grid" && arguments.getString("probeEngine")=="rapid") {
                // Independent count from the authorized source grid; nonempty OCR
                // alone is insufficient evidence that all printed scopes survived.
                assertEquals("Authorized grid recurring arrangement coverage",18,
                    meetings.count {it.kind==com.lingion.sleepy.importing.MeetingKind.RECURRING})
            }
        } finally {
            scenario.close()
            PrivateDocumentProbeActivity.result=CompletableDeferred()
            PrivateDocumentProbeActivity.experiment=null
        }
    }
}

private fun courseFields(draft:RichTimetableDraft?):JsonArray = buildJsonArray {
    draft?.courses?.forEach {course->add(buildJsonObject {
        put("name",course.name);put("code",course.sourceCourseCode);put("classCode",course.sourceClassCode)
        putJsonArray("meetings") {course.meetings.forEach {meeting->add(buildJsonObject {
            put("kind",meeting.kind.name);put("day",meeting.dayOfWeek)
            putJsonArray("weeks") {meeting.weeks.sorted().forEach {add(it)}}
            putJsonArray("sections") {meeting.sections.sorted().forEach {add(it)}}
            putJsonArray("dates") {meeting.dates.sorted().forEach {add(it.toString())}}
            put("start",meeting.customTime?.start?.toString());put("end",meeting.customTime?.end?.toString())
            put("teacher",meeting.teacher);put("room",meeting.room);put("group",meeting.group)
            put("project",meeting.experimentProject);put("experiment",meeting.isExperiment)
            putJsonArray("missing") {meeting.missingFields.forEach {add(it.name)}}
            putJsonArray("warnings") {meeting.warnings.forEach {add(it.code)}}
        })}}
    })}
}
