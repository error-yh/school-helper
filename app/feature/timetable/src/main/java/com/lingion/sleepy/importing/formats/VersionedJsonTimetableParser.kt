/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.formats

import com.lingion.sleepy.importing.*
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*

/** Strict schema 1 only. Root proposals never set a profile or create database records. */
class VersionedJsonTimetableParser {
    fun parse(input: InputStream, context: FormatParseContext): FormatParseResult = try {
        val text = BoundedImportInput.readUtf8(input, context)
        JsonLexicalGuard(text, context).validate()
        val root = Json.parseToJsonElement(text).obj(setOf("schemaVersion", "semesterStartDate", "periods", "courses"))
        val version = root.required("schemaVersion").integer()
        if (version != 1) reject("UNSUPPORTED_SCHEMA_VERSION")
        Decoder(context).decode(root)
    } catch (failure: ImportRejected) {
        FormatParseResult(null, issues = listOf(publicIssue(failure.code)))
    } catch (_: SerializationException) {
        FormatParseResult(null, issues = listOf(publicIssue("INVALID_STRUCTURE")))
    }

    private class Decoder(val context: FormatParseContext) {
        val sources = mutableListOf<ImportSourceRef>()
        val courseKeys = linkedMapOf<Int,String>()
        val meetingKeys = linkedMapOf<DraftPath,String>()
        var meetings = 0
        var exceptions = 0

        fun decode(root: JsonObject): FormatParseResult {
            val issues = mutableListOf<ParseIssue>()
            val semester = root["semesterStartDate"]?.date()
            val periods = root["periods"]?.array()?.map { element ->
                context.cancellation.check()
                val p = element.obj(setOf("section", "start", "end"))
                val section = p.required("section").integer().also { if (it !in 1..100) reject() }
                val range = time(p.required("start"), p.required("end"))
                if (range.end <= range.start) issues += semantic("INVALID_TIME_RANGE")
                PeriodProposal(section, range, emptyList())
            }.orEmpty()
            if (periods.map { it.section }.distinct().size != periods.size) issues += semantic("CONFLICTING_PERIOD_PROPOSALS")
            val usedCourseKeys = hashSetOf<String>()
            val courses = root.required("courses").array().mapIndexed { ci, element ->
                context.cancellation.check()
                if (ci >= context.limits.maxCourses) reject("LIMIT_EXCEEDED")
                val course = element.obj(setOf("key", "name", "sourceCourseCode", "sourceClassCode", "meetings"))
                val key = course.required("key").nonempty()
                if (!usedCourseKeys.add(key)) reject()
                courseKeys[ci] = key
                val usedMeetingKeys = hashSetOf<String>()
                val meetingDrafts = course.required("meetings").array().mapIndexed { mi, value ->
                    if (++meetings > context.limits.maxMeetings) reject("LIMIT_EXCEEDED")
                    meeting(value, ci, mi, usedMeetingKeys, semester)
                }
                val name = course.required("name").string()
                RichCourseDraft(name, course.optionalText("sourceCourseCode"), course.optionalText("sourceClassCode"), meetingDrafts,
                    warnings = if (meetingDrafts.isEmpty()) listOf(semantic("MISSING_MEETINGS")) else emptyList(),
                    missingFields = if (name.isBlank()) setOf(ImportField.COURSE_NAME) else emptySet())
            }
            collect(root, "", DraftPath())
            val draft = RichTimetableDraft(courses, periods, issues, adapterIds = setOf("json-schema-1"))
            return FormatParseResult(draft, sources.toList(), issues.toList(), semester, courseKeys.toMap(), meetingKeys.toMap())
        }

        fun meeting(element: JsonElement, ci: Int, mi: Int, keys: MutableSet<String>, semester: LocalDate?): RichMeetingDraft {
            context.cancellation.check()
            val m = element.obj(setOf("key", "kind", "dayOfWeek", "weeks", "sections", "dates", "time", "teacher", "room", "group", "experimentProject", "isExperiment", "exceptions"))
            val key = m.required("key").nonempty()
            if (!keys.add(key)) reject()
            meetingKeys[DraftPath(ci, mi)] = key
            val kind = enumValue<MeetingKind>(m.required("kind"))
            val day = m["dayOfWeek"]?.integer()?.also { if (it !in 1..7) reject() }
            val weeks = m["weeks"]?.integers(context.maxWeek).orEmpty()
            val sections = m["sections"]?.integers(100).orEmpty()
            val dates = m["dates"]?.array()?.map { it.date() }.orEmpty().also { if (it.distinct().size != it.size) reject() }.toSortedSet()
            val time = m["time"]?.range()
            if (kind == MeetingKind.RECURRING && dates.isNotEmpty()) reject()
            if (kind == MeetingKind.UNSCHEDULED && (dates.isNotEmpty() || day != null)) reject()
            val warnings = mutableListOf<ParseIssue>()
            if (time != null && time.end <= time.start) warnings += semantic("INVALID_TIME_RANGE")
            if (day != null && dates.any { it.dayOfWeek.value != day }) warnings += semantic("DATE_WEEKDAY_CONFLICT")
            if (kind == MeetingKind.DATE_SPECIFIC && semester != null && weeks.isNotEmpty() && dates.any { date ->
                val teachingWeek = Math.floorDiv(ChronoUnit.DAYS.between(semester, date), 7L) + 1
                weeks.none { it.toLong() == teachingWeek }
            }) warnings += semantic("DATE_DECLARED_WEEKS_CONFLICT")
            val missing = mutableSetOf<ImportField>()
            if (kind == MeetingKind.RECURRING) {
                if (weeks.isEmpty()) missing += ImportField.WEEKS
                if (day == null) missing += ImportField.DAY_OF_WEEK
            }
            if (kind == MeetingKind.DATE_SPECIFIC && dates.isEmpty()) warnings += semantic("MISSING_DATES")
            if (sections.isEmpty() && time == null) missing += ImportField.TIME
            val exceptionDrafts = m["exceptions"]?.array()?.map { value ->
                if (++exceptions > context.limits.maxExceptions) reject("LIMIT_EXCEEDED")
                exception(value)
            }.orEmpty()
            if (kind == MeetingKind.UNSCHEDULED && exceptionDrafts.isNotEmpty()) reject()
            return RichMeetingDraft(kind, day, weeks, sections, time, dates, m.optionalText("teacher"), m.optionalText("room"),
                m.optionalText("group"), m.optionalText("experimentProject"), m["isExperiment"]?.boolean() ?: false,
                warnings = warnings, missingFields = missing, exceptions = exceptionDrafts)
        }

        fun exception(element: JsonElement): RichExceptionDraft {
            context.cancellation.check()
            val e = element.obj(setOf("kind", "originalDate", "targetDate", "time", "sections", "teacher", "room", "group", "experimentProject"))
            val kind = enumValue<RichExceptionKind>(e.required("kind"))
            val original = e["originalDate"]?.date()
            val target = e["targetDate"]?.date()
            when (kind) {
                RichExceptionKind.CANCEL -> if (original == null || target != null || e.keys.any { it !in setOf("kind", "originalDate") }) reject()
                RichExceptionKind.RESCHEDULE -> if (original == null || target == null) reject()
                RichExceptionKind.EXTRA -> if (original != null || target == null) reject()
            }
            val sections = e["sections"]?.integers(100)?.also { if (it.isEmpty()) reject() }
            val time = e["time"]?.range()
            return RichExceptionDraft(kind, original, target, time, sections, e.optionalText("teacher"), e.optionalText("room"),
                e.optionalText("experimentProject"), e.optionalText("group"), warnings =
                    if (time != null && time.end <= time.start) listOf(semantic("INVALID_TIME_RANGE")) else emptyList())
        }

        /** Retain each literal exactly once; containers would multiply private text and memory. */
        fun collect(value: JsonElement, locator: String, path: DraftPath) {
            context.cancellation.check()
            when (value) {
                is JsonObject -> value.forEach { (field, item) -> collect(item, "$locator/$field", path.copy(field = field)) }
                is JsonArray -> value.forEachIndexed { index, item ->
                    val next = when (path.field) {
                        "courses" -> DraftPath(courseIndex=index)
                        "meetings" -> path.copy(meetingIndex=index, field=null)
                        "exceptions" -> path.copy(exceptionIndex=index, field=null)
                        else -> path
                    }
                    collect(item, "$locator/$index", next)
                }
                is JsonPrimitive -> sources += ImportSourceRef(context.sourceId, locator, if (value.isString) value.content else value.toString(), path)
            }
        }
    }
}

private fun semantic(code: String) = ParseIssue(code, "Source declaration requires review")
private fun JsonElement.obj(allowed: Set<String>): JsonObject = (this as? JsonObject ?: reject()).also { if (it.keys.any { key -> key !in allowed }) reject() }
private fun JsonObject.required(key: String): JsonElement = this[key] ?: reject()
private fun JsonElement.array(): JsonArray = this as? JsonArray ?: reject()
private fun JsonElement.string(): String = (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: reject()
private fun JsonElement.nonempty(): String = string().also { if (it.isBlank()) reject() }
private fun JsonObject.optionalText(key: String): String? = this[key]?.takeUnless { it == JsonNull }?.string()
private fun JsonElement.integer(): Int {
    val p = this as? JsonPrimitive ?: reject()
    if (p.isString || !Regex("-?(0|[1-9][0-9]*)").matches(p.content)) reject()
    return p.content.toIntOrNull() ?: reject()
}
private fun JsonElement.boolean(): Boolean {
    val p = this as? JsonPrimitive ?: reject()
    if (p.isString) reject()
    return when (p.content) { "true" -> true; "false" -> false; else -> reject() }
}
private fun JsonElement.integers(max: Int): Set<Int> {
    val values = array().map { it.integer().also { n -> if (n !in 1..max) reject() } }
    if (values.distinct().size != values.size) reject()
    // Reuse the existing bounded grammar to keep the explicit week/section domain aligned.
    if (values.isEmpty()) return emptySet()
    // Individual array entries are already exact; chunk to the grammar's 2048-unit input bound.
    return values.chunked(100).flatMap { chunk ->
        val result = WeekExpressionParser.parse(chunk.joinToString(","), max)
        if (result.errors.isNotEmpty()) reject()
        result.weeks
    }.toSortedSet()
}
private fun JsonElement.date(): LocalDate {
    val text = string()
    if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(text)) reject()
    return try { LocalDate.parse(text) } catch (_: DateTimeParseException) { reject() }
}
private fun clock(value: JsonElement): LocalTime {
    val text = value.string()
    if (!Regex("[0-9]{2}:[0-9]{2}").matches(text)) reject()
    return try { LocalTime.parse(text) } catch (_: DateTimeParseException) { reject() }
}
private fun time(start: JsonElement, end: JsonElement) = DraftTimeRange(clock(start), clock(end))
private fun JsonElement.range(): DraftTimeRange { val o=obj(setOf("start","end")); return time(o.required("start"),o.required("end")) }
private inline fun <reified T: Enum<T>> enumValue(value: JsonElement): T = enumValues<T>().firstOrNull { it.name == value.string() } ?: reject()

/** Tree maps overwrite repeated keys. Validate tokens, decoded key identity, and limits first. */
private class JsonLexicalGuard(val text: String, val context: FormatParseContext) {
    var cursor=0
    var elements=0
    var retained=0
    fun validate() { value(0); whitespace(); if (cursor != text.length) reject() }
    fun whitespace() { while (cursor < text.length && text[cursor] in " \t\r\n") cursor++ }
    fun take(c: Char): Boolean { whitespace(); return if (cursor < text.length && text[cursor] == c) { cursor++; true } else false }
    fun expect(c: Char) { if (!take(c)) reject() }
    fun value(depth: Int) {
        context.cancellation.check()
        if (++elements > context.limits.maxJsonElements) reject("LIMIT_EXCEEDED")
        whitespace()
        if (cursor >= text.length) reject()
        when (text[cursor]) {
            '{' -> {
                if (depth >= context.limits.maxJsonDepth) reject("LIMIT_EXCEEDED")
                cursor++; val keys=hashSetOf<String>()
                if (!take('}')) {
                    do { whitespace(); val key=quoted(); if (!keys.add(key)) reject(); expect(':'); value(depth+1) } while (take(','))
                    expect('}')
                }
            }
            '[' -> {
                if (depth >= context.limits.maxJsonDepth) reject("LIMIT_EXCEEDED")
                cursor++
                if (!take(']')) { do { value(depth+1) } while (take(',')); expect(']') }
            }
            '"' -> { quoted() }
            else -> {
                val start=cursor
                while (cursor < text.length && text[cursor] !in ",]} \t\r\n") cursor++
                val token=text.substring(start,cursor)
                if (token !in setOf("true","false","null") && !Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(token)) reject()
                count(token.length)
            }
        }
    }
    fun quoted(): String {
        if (cursor >= text.length || text[cursor] != '"') reject()
        val start=cursor++
        var escaped=false
        while (cursor < text.length) {
            context.cancellation.check()
            val c=text[cursor++]
            if (c < ' ') reject()
            if (!escaped && c == '"') {
                val value=Json.parseToJsonElement(text.substring(start,cursor)).jsonPrimitive.content
                if (value.length > context.limits.maxFieldUnits) reject("LIMIT_EXCEEDED")
                count(value.length); return value
            }
            if (cursor-start > context.limits.maxFieldUnits*6+2) reject("LIMIT_EXCEEDED")
            escaped=if (escaped) false else c == '\\'
        }
        reject()
    }
    fun count(units: Int) { if (units > context.limits.maxFieldUnits || units > context.limits.maxTextUnits-retained) reject("LIMIT_EXCEEDED"); retained+=units }
}
