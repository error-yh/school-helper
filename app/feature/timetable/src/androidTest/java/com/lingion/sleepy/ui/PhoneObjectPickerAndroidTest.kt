/* SPDX-License-Identifier: GPL-3.0-only
 * Authorized reproduction with the phone's own file: the user selects the object once in the
 * system picker, then the real loader reads that exact content URI. Nothing is copied off the
 * device; only a private diagnosis and the parsed arrangement fields are written to the
 * isolated test package.
 */
package com.lingion.sleepy.ui

import android.app.Activity
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lingion.sleepy.importing.MeetingKind
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneObjectPickerAndroidTest {
    @Test(timeout = 900_000) fun authorizedPhoneObjectThroughSafKeepsEveryArrangement() = runBlocking {
        // Manual-only test: it opens the system picker and waits for a person to select the
        // failing original file. Without this explicit opt-in it is skipped, so a plain
        // connectedDebugAndroidTest/CI run never waits seven minutes for an unattended picker.
        // Run manually (operator ready to tap the phone) with:
        //   adb shell am instrument -w -r -e allowPhoneObjectPicker true \
        //     -e class com.lingion.sleepy.ui.PhoneObjectPickerAndroidTest \
        //     com.lingion.sleepy.test/androidx.test.runner.AndroidJUnitRunner
        assumeTrue("Phone object picker is opt-in: pass -e allowPhoneObjectPicker true",
            InstrumentationRegistry.getArguments().getString("allowPhoneObjectPicker") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        PrivateDocumentProbeActivity.result = CompletableDeferred()
        PrivateDocumentProbeActivity.experiment = null
        // The self-instrumented library package cannot start the picker from the background;
        // bring AndroidX's exported test-only empty activity to the foreground first.
        val bootstrap = "${context.packageName}/androidx.test.core.app.InstrumentationActivityInvoker" + '$' + "EmptyActivity"
        val started = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am start -W -n $bootstrap"))
            .bufferedReader().use { it.readText() }
        assertTrue("Foreground bootstrap failed: $started", started.contains("Status: ok"))
        val scenario = ActivityScenario.launch<PrivateDocumentProbeActivity>(Intent(context, PrivateDocumentProbeActivity::class.java))
        try {
            scenario.onActivity { it.openDocument() }
            // The operator picks the failing file in the system picker within this window.
            val loaded = withTimeout(420_000) { PrivateDocumentProbeActivity.result.await() }
            val diagnostics = File(context.noBackupFilesDir, "import-read-diagnostics/last-read.json").let {
                if (it.isFile) it.readText() else "{}"
            }
            val record = JSONObject().put("loaded", loaded?.let { it::class.simpleName })
            if (loaded is LoadedImportDocument.Rejected) record.put("message", loaded.message)
            record.put("diagnostic", diagnostics)
            val directory = File(context.filesDir, "pre5fix01-private-probe").apply { mkdirs() }
            if (loaded is LoadedImportDocument.Parsed) {
                val draft = loaded.source.format.draft
                val meetings = draft?.courses.orEmpty().flatMap { it.meetings }
                record.put("courses", draft?.courses?.size ?: 0).put("meetings", meetings.size)
                val fields = JSONArray()
                meetings.forEach { meeting ->
                    fields.put(JSONObject().put("kind", meeting.kind.name).put("day", meeting.dayOfWeek)
                        .put("weeks", JSONArray(meeting.weeks.sorted())).put("sections", JSONArray(meeting.sections.sorted()))
                        .put("dates", JSONArray(meeting.dates.sorted().map { it.toString() }))
                        .put("start", meeting.customTime?.start?.toString()).put("end", meeting.customTime?.end?.toString())
                        .put("room", meeting.room).put("teacher", meeting.teacher).put("group", meeting.group)
                        .put("project", meeting.experimentProject).put("missing", JSONArray(meeting.missingFields.map { it.name }))
                        .put("sources", meeting.sources.size))
                }
                record.put("meetingFields", fields)
                record.put("adapterIds", JSONArray(draft?.adapterIds.orEmpty()))
                record.put("issues", JSONArray((loaded.source.format.issues + draft?.issues.orEmpty()).map { it.code }))
            }
            File(directory, "phone-picked-object.json").writeText(record.toString())

            assertTrue("The phone's own object must reach the parsed branch", loaded is LoadedImportDocument.Parsed)
            val probe = JSONObject(diagnostics)
            assertEquals("The phone object is the WebP the loader must now accept", "WEBP", probe.getString("content"))
            assertEquals("IMAGE", probe.getString("branch"))
            assertEquals("OK", probe.getString("stage"))
            assertFalse("Diagnosis must not carry the file name", diagnostics.contains("1080_503Q50") || diagnostics.contains("xuexitong"))

            val draft = (loaded as LoadedImportDocument.Parsed).source.format.draft
            val meetings = draft?.courses.orEmpty().flatMap { it.meetings }
            assertEquals(8, meetings.size)
            assertTrue(meetings.all { it.kind == MeetingKind.DATE_SPECIFIC })
            assertEquals(7, meetings.count { it.sections.sorted() == listOf(1, 2) })
            assertEquals(1, meetings.count { it.sections.sorted() == listOf(5, 6) })
            meetings.filter { it.sections.sorted() == listOf(1, 2) }.forEach {
                assertEquals(LocalTime.of(8, 0), it.customTime?.start)
                assertEquals(LocalTime.of(9, 40), it.customTime?.end)
            }
            meetings.filter { it.sections.sorted() == listOf(5, 6) }.forEach {
                assertEquals(LocalTime.of(13, 45), it.customTime?.start)
                assertEquals(LocalTime.of(15, 25), it.customTime?.end)
            }
            assertTrue(meetings.all { it.dates.size == 1 && it.dates.single().dayOfWeek == DayOfWeek.MONDAY })
            assertEquals(8, meetings.flatMap { it.weeks }.distinct().size)
            assertTrue(meetings.all { it.sources.isNotEmpty() })
        } finally {
            scenario.close()
            PrivateDocumentProbeActivity.result = CompletableDeferred()
            PrivateDocumentProbeActivity.experiment = null
        }
    }
}