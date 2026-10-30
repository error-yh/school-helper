/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import com.lingion.sleepy.importing.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class StructuredFormatsTest {
    private val context = FormatParseContext("synthetic", 30)
    private fun parse(text: String) = VersionedJsonTimetableParser().parse(text.byteInputStream(), context)
    private fun document(meeting: String = """{"key":"m","kind":"RECURRING","dayOfWeek":1,"weeks":[1,3,8],"sections":[1,3]}""") =
        """{"schemaVersion":1,"courses":[{"key":"c","name":"示例课","meetings":[$meeting]}]}"""

    @Test fun `sparse sets codes proposals and nested exceptions survive with non geometric paths`() {
        val result = parse("""{"schemaVersion":1,"semesterStartDate":"2026-09-07","periods":[{"section":1,"start":"08:00","end":"08:45"}],"courses":[{"key":"c","name":"示例课","sourceCourseCode":"CODE","sourceClassCode":"CLASS","meetings":[{"key":"m","kind":"RECURRING","dayOfWeek":1,"weeks":[1,3,8],"sections":[1,3],"teacher":"","isExperiment":true,"exceptions":[{"kind":"CANCEL","originalDate":"2026-09-21"},{"kind":"RESCHEDULE","originalDate":"2026-10-05","targetDate":"2026-10-06","room":"","sections":[2,4],"time":{"start":"10:00","end":"11:30"}},{"kind":"EXTRA","targetDate":"2026-10-31"}]}]}]}""")
        val course = result.draft!!.courses.single()
        assertEquals("CODE", course.sourceCourseCode)
        assertEquals("CLASS", course.sourceClassCode)
        val meeting = course.meetings.single()
        assertEquals(setOf(1,3,8), meeting.weeks)
        assertEquals(setOf(1,3), meeting.sections)
        assertEquals(3, meeting.exceptions.size)
        assertEquals("", meeting.exceptions[1].room)
        assertNull(meeting.exceptions[0].sections)
        assertEquals(setOf(2,4), meeting.exceptions[1].sections)
        assertEquals(LocalDate.of(2026,9,7), result.semesterStartDateProposal)
        assertEquals(1, result.draft!!.periodProposals.size)
        assertEquals("c", result.courseKeys[0])
        assertEquals("m", result.meetingKeys[DraftPath(0,0)])
        assertTrue(result.sources.any { it.sourceLocator == "/courses/0/meetings/0/exceptions/1/room" && it.path == DraftPath(0,0,1,"room") })
        assertTrue(meeting.sources.isEmpty())
    }

    @Test fun `structural errors never expose a partial draft`() {
        val bad = listOf(
            document().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            document().replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            document().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
            document().replace("\"name\":", "\"unknown\":0,\"name\":"),
            document().replace("[1,3,8]", "[1,1,8]"),
            document().replace("[1,3,8]", "[1,3.0,8]"),
            document().replace("[1,3,8]", "[1,31]"),
            document().replace("\"key\":\"m\"", "\"key\":\"m\",\"k\\u0065y\":\"x\""),
            document().dropLast(1), document().replace("\"dayOfWeek\":1", "\"dayOfWeek\":true"),
            document().replace("\"sections\":[1,3]", "\"time\":{\"start\":\"10:00:01\",\"end\":\"11:00\"}"),
        )
        bad.forEach { val result = parse(it); assertNull(result.draft); assertTrue(result.issues.isNotEmpty()); assertTrue(result.sources.isEmpty()) }
    }

    @Test fun `exact dates declarations and semantic uncertainty remain reviewable`() {
        val result = parse(document("""{"key":"m","kind":"DATE_SPECIFIC","dayOfWeek":1,"weeks":[3,8],"dates":["2026-12-30","2027-01-06"],"time":{"start":"14:00","end":"16:00"},"group":"甲","experimentProject":"项目","isExperiment":true}"""))
        val meeting = result.draft!!.courses.single().meetings.single()
        assertEquals(setOf(LocalDate.of(2026,12,30),LocalDate.of(2027,1,6)), meeting.dates)
        assertEquals(setOf(3,8), meeting.weeks)
        assertTrue(meeting.warnings.isNotEmpty())
        assertTrue(result.draft!!.requiresConfirmation)
        assertEquals("甲", meeting.group)
    }

    @Test fun `identity collisions and exception shape are rejected`() {
        listOf(
            document().replace("}]}", "}, {\"key\":\"c\",\"name\":\"另课\",\"meetings\":[]}]}") ,
            document("""{"key":"m","kind":"UNSCHEDULED","exceptions":[{"kind":"EXTRA","targetDate":"2026-10-01"}]}"""),
            document("""{"key":"m","kind":"RECURRING","exceptions":[{"kind":"CANCEL","targetDate":"2026-10-01"}]}"""),
            document("""{"key":"m","kind":"RECURRING","exceptions":[{"kind":"EXTRA","targetDate":"2026-10-01","sections":[]}]}"""),
        ).forEach { assertNull(parse(it).draft) }
    }

    @Test fun `csv preserves leading trailing empty cells quoted whitespace and multiline BOM`() {
        val csv = "\uFEFF,a,b,\r\n,\" a,\"\"b\"\"\r\nc \",=literal,\r\n"
        val result = Utf8CsvReader().read(csv.byteInputStream(), context)
        assertTrue(result.issues.isEmpty())
        val rows = result.table!!.rows
        assertEquals(listOf("", "a", "b", ""), rows[0].cells.map { it.rawText })
        assertEquals(listOf("", " a,\"b\"\r\nc ", "=literal", ""), rows[1].cells.map { it.rawText })
        assertEquals("csv:record:2!column:2", rows[1].cells[1].sourceLocator)
    }

    @Test fun `csv malformed utf8 quotes ragged and truncation fail atomically and stream remains open`() {
        val samples = listOf("a,b\n1", "a,b\n\"x", "a,b\nx\"y,z", "a,b\n\"x\" ,z", "a,b\n1,2\r3,4")
        samples.forEach { val result=Utf8CsvReader().read(it.byteInputStream(), context); assertNull(result.table); assertTrue(result.issues.isNotEmpty()) }
        var closed=false
        val input=object: ByteArrayInputStream(byteArrayOf(0xc3.toByte(),0x28)) { override fun close() { closed=true } }
        assertEquals("INVALID_UTF8", Utf8CsvReader().read(input,context).issues.single().code)
        assertFalse(closed)
    }

    @Test fun `limits and cancellation apply before yielding data`() {
        val small = context.copy(limits=ImportLimits(maxInputBytes=4))
        assertNull(Utf8CsvReader().read("a,b\n1,2".byteInputStream(),small).table)
        assertNull(VersionedJsonTimetableParser().parse(document().byteInputStream(),small).draft)
        assertNull(VersionedJsonTimetableParser().parse(document().byteInputStream(),context.copy(limits=ImportLimits(maxJsonDepth=2))).draft)
        assertNull(Utf8CsvReader().read("abcde".byteInputStream(),context.copy(limits=ImportLimits(maxFieldUnits=4))).table)
        try { Utf8CsvReader().read("a".byteInputStream(),context.copy(cancellation=ImportCancellation { throw CancellationException() })); fail() } catch (_: CancellationException) { }
        try { VersionedJsonTimetableParser().parse(document().byteInputStream(),context.copy(cancellation=ImportCancellation { throw CancellationException() })); fail() } catch (_: CancellationException) { }
    }

    @Test fun `A positional constructor remains compatible and exceptions participate in confirmation`() {
        val old = RichMeetingDraft(MeetingKind.UNSCHEDULED,null,emptySet(),emptySet(),null,emptySet(),null,null,null,null,false,emptyList(),emptyList(),emptySet())
        assertTrue(old.exceptions.isEmpty())
        val exception = RichExceptionDraft(RichExceptionKind.EXTRA, targetDate=LocalDate.of(2026,10,1),missingFields=setOf(ImportField.TIME))
        assertTrue(RichTimetableDraft(courses=listOf(RichCourseDraft("示例",meetings=listOf(old.copy(exceptions=listOf(exception))))),missingFields=emptySet()).requiresConfirmation)
    }

    @Test fun `declared weeks conflicting with exact dates retain both and require review`() {
        val json = document("""{"key":"m","kind":"DATE_SPECIFIC","weeks":[1,3],"dates":["2026-12-30"],"sections":[1]}""")
            .replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"semesterStartDate\":\"2026-09-07\"")
        val meeting = parse(json).draft!!.courses.single().meetings.single()
        assertEquals(setOf(1,3), meeting.weeks)
        assertEquals(setOf(LocalDate.of(2026,12,30)), meeting.dates)
        assertTrue(meeting.warnings.any { it.code == "DATE_DECLARED_WEEKS_CONFLICT" })
        val matching = parse(json.replace("[1,3]", "[17]")).draft!!.courses.single().meetings.single()
        assertFalse(matching.warnings.any { it.code == "DATE_DECLARED_WEEKS_CONFLICT" })
    }

    @Test fun `nested metadata null inheritance and empty clearing survive independently`() {
        val result = parse(document("""{"key":"m","kind":"RECURRING","weeks":[8,1,3],"dayOfWeek":1,"sections":[3,1],"teacher":null,"room":"","exceptions":[{"kind":"RESCHEDULE","originalDate":"2026-09-07","targetDate":"2026-09-08","teacher":null,"room":"","group":"组","experimentProject":"项目"}]}"""))
        val meeting = result.draft!!.courses.single().meetings.single()
        assertEquals(listOf(1,3,8), meeting.weeks.toList())
        assertEquals(listOf(1,3), meeting.sections.toList())
        assertNull(meeting.teacher)
        assertEquals("", meeting.room)
        val exception = meeting.exceptions.single()
        assertNull(exception.teacher)
        assertNull(exception.sections)
        assertEquals("", exception.room)
        assertEquals("组", exception.group)
        assertEquals("项目", exception.experimentProject)
        assertEquals("null", result.sources.single { it.sourceLocator == "/courses/0/meetings/0/exceptions/0/teacher" }.rawText)
    }

    @Test fun `json lexical and primitive forms are strict at every level`() {
        val meeting = """{"key":"m","kind":"DATE_SPECIFIC","dates":["2026-12-30"],"time":{"start":"10:00","end":"11:00"}}"""
        val cases = listOf(
            document(meeting).replace("\"start\":\"10:00\"", "\"start\":\"10:00\",\"st\\u0061rt\":\"10:00\""),
            document(meeting).replace("\"start\":\"10:00\"", "\"start\":\"10:00:00\""),
            document(meeting).replace("\"start\":\"10:00\"", "\"start\":\"9:00\""),
            document(meeting).replace("2026-12-30", "2026-02-30"),
            document(meeting).replace("[\"2026-12-30\"]", "[\"2026-12-30\",\"2026-12-30\"]"),
            document().replace("\"sections\":[1,3]", "\"sections\":[0]"),
            document().replace("\"sections\":[1,3]", "\"isExperiment\":\"true\""),
            document().replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            document().replace("\"weeks\":[1,3,8]", "\"weeks\":[NaN]"),
            document().replace("\"key\":\"m\"", "\"key\":null"),
            document().replace("\"meetings\":[", "\"meetings\":{},\"other\":["),
            document() + " true",
            document().replace("\"schemaVersion\":1", "\"schemaVersion\":1,"),
            document().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"periods\":[{\"section\":1,\"start\":\"08:00:00\",\"end\":\"09:00\"}]"),
        )
        cases.forEachIndexed { index, json ->
            val result = parse(json)
            assertNull("synthetic case $index", result.draft)
            assertTrue(result.sources.isEmpty())
            assertEquals("INVALID_STRUCTURE", result.issues.single().code)
        }
    }

    @Test fun `each retained collection and text budget is bounded atomically`() {
        fun jsonLimit(limits: ImportLimits, text: String = document()) {
            val result = VersionedJsonTimetableParser().parse(text.byteInputStream(), context.copy(limits=limits))
            assertNull(result.draft)
            assertEquals("LIMIT_EXCEEDED", result.issues.single().code)
            assertTrue(result.sources.isEmpty())
        }
        jsonLimit(ImportLimits(maxJsonElements=2))
        jsonLimit(ImportLimits(maxTextUnits=20))
        jsonLimit(ImportLimits(maxFieldUnits=4))
        jsonLimit(ImportLimits(maxCourses=1), """{"schemaVersion":1,"courses":[{"key":"a","name":"甲","meetings":[]},{"key":"b","name":"乙","meetings":[]}]}""")
        jsonLimit(ImportLimits(maxMeetings=1), document("""{"key":"m","kind":"UNSCHEDULED"},{"key":"n","kind":"UNSCHEDULED"}"""))
        jsonLimit(ImportLimits(maxExceptions=1), document("""{"key":"m","kind":"RECURRING","exceptions":[{"kind":"EXTRA","targetDate":"2026-10-01"},{"kind":"EXTRA","targetDate":"2026-10-02"}]}"""))
        listOf(ImportLimits(maxCells=3), ImportLimits(maxRows=1), ImportLimits(maxColumns=1), ImportLimits(maxTextUnits=3)).forEach { limits ->
            val result = Utf8CsvReader().read("a,b\nc,d".byteInputStream(),context.copy(limits=limits))
            assertNull(result.table)
            assertEquals("LIMIT_EXCEEDED",result.issues.single().code)
        }
    }

    @Test fun `unreadable input never leaks exception details and caller retains ownership`() {
        var closed = false
        val broken = object: InputStream() {
            override fun read(): Int = throw IOException("synthetic private row and path")
            override fun close() { closed = true }
        }
        val result = VersionedJsonTimetableParser().parse(broken,context)
        assertNull(result.draft)
        assertEquals("IMPORT_UNREADABLE",result.issues.single().code)
        assertFalse(result.issues.single().message.contains("private"))
        assertFalse(closed)
        val valid = object: ByteArrayInputStream(document().toByteArray()) { override fun close() { closed = true } }
        assertNotNull(VersionedJsonTimetableParser().parse(valid,context).draft)
        assertFalse(closed)
    }

    @Test fun `byte bound accepts exact size zero reads and cancellation during a read`() {
        val bytes = "a,b".toByteArray()
        val zeroRead = object: ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertNotNull(Utf8CsvReader().read(zeroRead,context.copy(limits=ImportLimits(maxInputBytes=3))).table)
        var checks = 0
        var closed = false
        val interrupted = object: ByteArrayInputStream(ByteArray(20_000) { 'a'.code.toByte() }) { override fun close() { closed = true } }
        try {
            Utf8CsvReader().read(interrupted,context.copy(cancellation=ImportCancellation { if (++checks == 2) throw CancellationException() }))
            fail()
        } catch (_: CancellationException) { }
        assertEquals(2,checks)
        assertFalse(closed)
    }

    @Test fun `blank csv fields stay present and formula prefixes stay literal`() {
        val result = Utf8CsvReader().read(",,\n,,\n=SUM(A1),+x,-x\n@x,\"\",last".byteInputStream(),context)
        assertEquals(listOf("","",""),result.table!!.rows[1].cells.map { it.rawText })
        assertEquals(listOf("=SUM(A1)","+x","-x"),result.table!!.rows[2].cells.map { it.rawText })
        assertEquals(listOf("@x","","last"),result.table!!.rows[3].cells.map { it.rawText })
        assertEquals("EMPTY_TABLE",Utf8CsvReader().read("\uFEFF".byteInputStream(),context).issues.single().code)
    }

    @Test fun `published synthetic fixtures have exact structured counts`() {
        val examples = File("../../../docs/stage-3/import-examples")
        val result = File(examples,"schema-1.json").inputStream().use { VersionedJsonTimetableParser().parse(it,context) }
        assertTrue(result.issues.isEmpty())
        val courses = result.draft!!.courses
        assertEquals(2,courses.size)
        assertEquals(2,courses.sumOf { it.meetings.size })
        assertEquals(3,courses.sumOf { c -> c.meetings.sumOf { it.exceptions.size } })
        assertEquals(setOf(1,3,5,8),courses[0].meetings.single().weeks)
        assertEquals(setOf(17,18),courses[1].meetings.single().weeks)
        assertTrue(courses[1].meetings.single().warnings.isEmpty())
        val table = File(examples,"meetings.csv").inputStream().use { Utf8CsvReader().read(it,context) }.table!!
        assertEquals(4,table.rows.size)
        assertTrue(table.rows.all { it.cells.size == 18 })
        assertEquals("CANCEL",table.rows[2].cells[15].rawText)
    }
}
