/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit private device acceptance. No originals or references are packaged in this APK. */
@RunWith(AndroidJUnit4::class)
class PrivateAndroidPdfAcceptanceTest {
    @Test fun sampleA_matchesAllIndependentSourceRecords() = verify("a",3,12,22,4,listOf(4,3,2,2,2,2,3))
    @Test fun sampleB_matchesAllIndependentSourceRecords() = verify("b",2,9,23,0,listOf(6,2,3,8,3,1,0))
    @Test fun sampleC_matchesAllIndependentSourceRecords() = verify("c",1,14,28,1,listOf(3,3,5,10,5,1,0))

    private fun verify(sample: String,pageCount: Int,profileCount: Int,meetingCount: Int,unscheduledCount: Int,days: List<Int>) {
        val argument=InstrumentationRegistry.getArguments().getString("sampleRoot")
        assumeTrue("Private Android PDF acceptance requires explicit sampleRoot",argument!=null)
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val root=File(argument!!).canonicalFile
        assertTrue("Private sample directory must be the library test sandbox",root==File(context.filesDir,"stage3-private").canonicalFile)
        val pdf=File(root,"pdf-$sample.pdf")
        assertTrue("sample-$sample PDF was not staged",pdf.isFile)
        val extraction=pdf.inputStream().use { AndroidPdfTextExtractor(context).extract(it) }
        assertTrue("sample-$sample Android text extraction was rejected",extraction.document!=null)
        assertTrue("sample-$sample unexpectedly requires OCR",extraction.pagesNeedingOcr.isEmpty())
        assertTrue("sample-$sample extraction reported an issue",extraction.issues.isEmpty())
        val document=extraction.document!!
        assertEquals("sample-$sample page count",pageCount,document.pages.size)
        assertTrue("sample-$sample page orientation",document.pages.all { if(sample=="c") it.width<it.height else it.width>it.height })
        val draft=PdfTimetableParser().parse(document)
        // Detailed failure diagnosis remains exclusively in the private app sandbox.
        File(root,"android-pdf-$sample-parsed-private.txt").writeText(draft.toString())
        val meetings=draft.courses.flatMap { it.meetings }
        assertEquals("sample-$sample profile count",profileCount,draft.courses.size)
        assertEquals("sample-$sample arrangement count",meetingCount,meetings.size)
        assertEquals("sample-$sample unscheduled count",unscheduledCount,meetings.count { it.kind==MeetingKind.UNSCHEDULED })
        assertEquals("sample-$sample weekday distribution",days,(1..7).map { day -> meetings.count { it.kind==MeetingKind.RECURRING && it.dayOfWeek==day } })
        val expected=reference(root,"pdf-$sample-full-reference.tsv")
        val actual=draft.courses.flatMap { c -> c.meetings.filter { it.kind==MeetingKind.RECURRING }.map { m ->
            val fields=mutableListOf(m.dayOfWeek.toString(),m.sections.sorted().joinToString(","),m.weeks.sorted().joinToString(","))
            if(sample=="c") { fields+=time(m.customTime?.start);fields+=time(m.customTime?.end) }
            fields+=listOf(hash(m.teacher),hash(m.room),hash(c.name),hash(c.sourceClassCode),hash(c.sourceCourseCode))
            fields.joinToString("\t")
        } }.sorted()
        assertTrue("sample-$sample complete independent recurring source multiset",expected==actual)
        if(unscheduledCount>0) {
            val expectedUnscheduled=reference(root,"pdf-$sample-unscheduled-reference.tsv")
            val actualUnscheduled=draft.courses.flatMap { c -> c.meetings.filter { it.kind==MeetingKind.UNSCHEDULED }.map { m ->
                assertTrue("sample-$sample invented unscheduled time/day/date/sections",m.dayOfWeek==null && m.customTime==null && m.sections.isEmpty() && m.dates.isEmpty())
                listOf(hash(c.name),hash(c.sourceClassCode),hash(c.sourceCourseCode),m.weeks.sorted().joinToString(","),hash(m.teacher),hash(m.room)).joinToString("\t")
            } }.sorted()
            assertTrue("sample-$sample complete independent unscheduled source multiset",expectedUnscheduled==actualUnscheduled)
        }
        assertTrue("sample-$sample source issues require investigation",draft.issues.isEmpty() && draft.unparsed.isEmpty())
        assertTrue("sample-$sample semester must remain explicitly unconfirmed",draft.requiresConfirmation && ImportField.SEMESTER_START_DATE in draft.missingFields)
        println("sample-$sample android pages=$pageCount profiles=$profileCount arrangements=$meetingCount unscheduled=$unscheduledCount unparsed=${draft.unparsed.size}")
    }

    private fun reference(root: File,name: String): List<String> {
        val file=File(root,name)
        assertTrue("Independent private reference was not staged",file.isFile)
        return file.readLines().filter { it.isNotBlank() }.sorted()
    }
    private fun time(value: LocalTime?): String = value?.format(DateTimeFormatter.ofPattern("HH:mm")).orEmpty()
    private fun hash(value: String?): String = MessageDigest.getInstance("SHA-256")
        .digest(PdfTimetableParser.normalize(value.orEmpty()).replace(Regex("\\s+"),"").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
