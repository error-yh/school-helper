/* SPDX-License-Identifier: GPL-3.0-only
 * Device evidence for the real document loader. Private sample bytes are staged by an
 * explicitly authorized test-only transfer and never enter this source tree.
 */
package com.lingion.sleepy.ui

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lingion.sleepy.importing.MeetingKind
import java.io.File
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportDocumentLoaderAndroidTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun stage(name: String, bytes: ByteArray): File {
        val directory = File(context.filesDir, "pre5fix01-synthetic").apply { mkdirs() }
        return File(directory, name).also { it.writeBytes(bytes) }
    }

    private fun sha256(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun privateDirectory() = File(context.filesDir, "pre5fix01-private-probe").apply { mkdirs() }

    private fun arguments() = InstrumentationRegistry.getArguments()

    /**
     * The authorized sample keeps no image extension and is served with a null provider MIME,
     * so the image branch can only come from its bytes. All arrangements and source fields are
     * written to the test package private directory; this method never prints the sample.
     */
    @Test(timeout = 600_000) fun authorizedImageSampleUsesTheFinalLoaderAndKeepsEveryArrangement() = runBlocking {
        val root = arguments().getString("sampleRoot") ?: error("Missing private sample root")
        val name = arguments().getString("sampleFile") ?: error("Missing private sample file")
        require(name.matches(Regex("[A-Za-z0-9._-]{1,80}")))
        val alias = arguments().getString("probeAlias") ?: error("Missing anonymous sample alias")
        require(alias.matches(Regex("[a-z-]{1,32}")))
        val expectedSha = arguments().getString("sampleSha256")?.lowercase()
        val file = File(root, name)
        assertTrue("Staged private sample missing", file.isFile)
        val digest = sha256(file)
        if (expectedSha != null) assertEquals("Staged sample bytes changed", expectedSha, digest)
        val loaded = AndroidImportDocumentLoader(context).load(Uri.fromFile(file), ImportDocumentKind.IMAGE, 20,
            LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 28))
        val result = JSONObject().put("alias", alias).put("bytes", file.length()).put("sha256", digest)
            .put("loaded", loaded::class.simpleName)
        if (loaded is LoadedImportDocument.Rejected) result.put("message", loaded.message)
        if (loaded is LoadedImportDocument.Parsed) {
            val draft = loaded.source.format.draft
            val meetings = draft?.courses.orEmpty().flatMap { it.meetings }
            result.put("courses", draft?.courses?.size ?: 0).put("meetings", meetings.size)
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
            result.put("meetingFields", fields)
            result.put("adapterIds", JSONArray(draft?.adapterIds.orEmpty()))
            result.put("issues", JSONArray((loaded.source.format.issues + draft?.issues.orEmpty()).map { it.code }))
        }
        File(privateDirectory(), "loader-$alias.json").writeText(result.toString())

        // The private per-read diagnosis must never carry a path, URI or file name.
        val diagnosticsText = File(context.noBackupFilesDir, "import-read-diagnostics/last-read.json").readText()
        val diagnostic = JSONObject(diagnosticsText)
        for (forbidden in listOf("name", "path", "uri", "displayName", "fileName")) assertFalse("Unexpected diagnostic key $forbidden", diagnostic.has(forbidden))
        assertFalse("Diagnosis must not contain the sample file name", diagnosticsText.contains(name))
        assertFalse("Diagnosis must not contain a path or URI", diagnosticsText.contains("/data/") || diagnosticsText.contains("file://"))
        assertEquals("file", diagnostic.getString("uriScheme"))
        assertEquals(file.length(), diagnostic.getLong("bytes"))
        assertEquals("IMAGE", diagnostic.getString("branch"))
        assertEquals("OK", diagnostic.getString("stage"))
        assertTrue(diagnostic.getString("content").startsWith("JPEG"))
        assertTrue(diagnostic.getString("magic").startsWith("FF D8 FF"))
        assertEquals("IMAGE", diagnostic.getString("requestedKind"))

        assertTrue("Authorized private sample did not reach the parsed branch", loaded is LoadedImportDocument.Parsed)
        val draft = (loaded as LoadedImportDocument.Parsed).source.format.draft
        val meetings = draft?.courses.orEmpty().flatMap { it.meetings }
        assertEquals("Every printed arrangement must survive", 8, meetings.size)
        assertTrue("All rows are explicit dated arrangements", meetings.all { it.kind == MeetingKind.DATE_SPECIFIC })
        assertEquals("Seven rows share sections 1-2", 7, meetings.count { it.sections.sorted() == listOf(1, 2) })
        assertEquals("The last row keeps sections 5-6", 1, meetings.count { it.sections.sorted() == listOf(5, 6) })
        meetings.filter { it.sections.sorted() == listOf(1, 2) }.forEach {
            assertEquals(LocalTime.of(8, 0), it.customTime?.start)
            assertEquals(LocalTime.of(9, 40), it.customTime?.end)
        }
        meetings.filter { it.sections.sorted() == listOf(5, 6) }.forEach {
            assertEquals(LocalTime.of(13, 45), it.customTime?.start)
            assertEquals(LocalTime.of(15, 25), it.customTime?.end)
        }
        assertTrue("Every arrangement keeps a date", meetings.all { it.dates.size == 1 })
        assertTrue("Every date is a Monday in the confirmed term", meetings.all { it.dates.single().dayOfWeek == DayOfWeek.MONDAY })
        assertEquals("Eight distinct teaching weeks", 8, meetings.flatMap { it.weeks }.distinct().size)
        assertTrue("Each meeting keeps its original source excerpt", meetings.all { it.sources.isNotEmpty() })
        assertTrue("Course identity is retained for every arrangement", draft != null && draft.courses.all { it.name.isNotBlank() })
    }

    /** Synthetic containers prove which branch each content family takes; no real data involved. */
    @Test(timeout = 600_000) fun unsupportedContainersAreNamedAndNeverSilentlyTreatedAsAnImage() = runBlocking {
        val loader = AndroidImportDocumentLoader(context)
        suspend fun message(name: String, bytes: ByteArray): String {
            val result = loader.load(Uri.fromFile(stage(name, bytes)), ImportDocumentKind.IMAGE, 20, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 28))
            assertTrue("Expected a rejection for $name", result is LoadedImportDocument.Rejected)
            return (result as LoadedImportDocument.Rejected).message
        }
        assertTrue(message("empty.bin", ByteArray(0)).contains("为空"))
        assertTrue(message("random.bin", byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)).contains("无法识别"))
        assertTrue(message("gif.bin", "GIF89a".toByteArray() + ByteArray(32)).contains("GIF"))
        assertTrue(message("heif.bin", byteArrayOf(0, 0, 0, 24) + "ftypheic".toByteArray() + ByteArray(4) + "mif1".toByteArray() + ByteArray(4)).contains("HEIF"))
        assertTrue(message("bmp.bin", bmpHeader()).contains("BMP"))
        // A URI that no longer resolves reports unreadable storage, not an unsupported format.
        val missing = loader.load(Uri.fromFile(File(arguments().getString("sampleRoot") ?: error("Missing private sample root"), "missing-sample.bin")),
            ImportDocumentKind.IMAGE, 20, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 28))
        assertTrue(missing is LoadedImportDocument.Rejected && (missing as LoadedImportDocument.Rejected).message.contains("无法读取"))
        val webp = bitmapBytes("webp")
        val webpResult = loader.load(Uri.fromFile(stage("synthetic.webp", webp)), ImportDocumentKind.IMAGE, 20,
            LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 28))
        val webpDiagnostic = File(context.noBackupFilesDir, "import-read-diagnostics/last-read.json").readText()
        File(privateDirectory(), "synthetic-webp.json").writeText(JSONObject()
            .put("loaded", webpResult::class.simpleName)
            .put("message", (webpResult as? LoadedImportDocument.Rejected)?.message)
            .put("diagnostic", webpDiagnostic).toString())
        assertTrue("A device-encoded static WebP must reach the image branch", webpDiagnostic.contains("\"content\":\"WEBP\""))
        assertTrue("The WebP image branch is recorded", webpDiagnostic.contains("\"branch\":\"IMAGE\""))
        val webpRejection = (webpResult as? LoadedImportDocument.Rejected)?.message
        assertTrue("Static WebP must not be rejected as an unsupported container",
            webpRejection == null || !webpRejection.contains("暂不支持该图片"))
        // Automatic mode routes by the same bytes even when no format chip was chosen.
        loader.load(Uri.fromFile(stage("synthetic-auto.webp", webp)), ImportDocumentKind.AUTO, 20,
            LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 28))
        assertTrue("AUTO must select the image branch from WebP bytes",
            File(context.noBackupFilesDir, "import-read-diagnostics/last-read.json").readText().contains("\"branch\":\"IMAGE\""))
        // Animated WebP is refused before any frame is decoded.
        assertTrue(message("animated.webp", animatedWebp()).contains("动画"))
        // A malformed WebP is reported as damaged content, never as a silently decoded image.
        assertTrue(message("broken.webp", webp.copyOf(webp.size / 2)).contains("损坏"))

        // A real PDF still reaches the PDF branch through the same loader.
        val pdf = File(arguments().getString("sampleRoot") ?: error("Missing private sample root"), "pdf-a.pdf")
        org.junit.Assume.assumeTrue("Private PDF fixture is not staged", pdf.isFile)
        val loadedPdf = loader.load(Uri.fromFile(pdf), ImportDocumentKind.PDF, 20, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 10, 28))
        assertTrue("Real PDF must not be rejected by content routing", loadedPdf is LoadedImportDocument.Parsed)
        assertTrue((loadedPdf as LoadedImportDocument.Parsed).source.format.draft?.courses.orEmpty().isNotEmpty())
    }

    private fun bmpHeader(): ByteArray = ByteArray(54).also { bytes ->
        bytes[0] = 'B'.code.toByte(); bytes[1] = 'M'.code.toByte()
        bytes[2] = 54; bytes[10] = 54; bytes[14] = 40
    }

    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte())

    private fun riffChunk(name: String, data: ByteArray): ByteArray {
        val padded = if (data.size % 2 == 0) data else data + 0
        return name.toByteArray() + le32(padded.size) + padded
    }

    private fun animatedWebp(): ByteArray {
        val vp8x = ByteArray(10).also { it[0] = 0x02 }
        val body = riffChunk("VP8X", vp8x) + riffChunk("ANIM", ByteArray(6)) + riffChunk("VP8 ", byteArrayOf(1, 2, 3, 4))
        return "RIFF".toByteArray() + le32(body.size + 4) + "WEBP".toByteArray() + body
    }

    private fun bitmapBytes(format: String): ByteArray {
        val bitmap = android.graphics.Bitmap.createBitmap(120, 80, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            android.graphics.Canvas(bitmap).drawColor(android.graphics.Color.WHITE)
            val out = java.io.ByteArrayOutputStream()
            val compress = when (format) {
                "webp" -> android.graphics.Bitmap.CompressFormat.WEBP_LOSSY
                else -> android.graphics.Bitmap.CompressFormat.PNG
            }
            bitmap.compress(compress, 100, out)
            return out.toByteArray()
        } finally { bitmap.recycle() }
    }
}