/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.domain.notice

import com.lingion.sleepy.importing.NoticeDateBasis
import com.lingion.sleepy.domain.model.TimeRange
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.json.*

/** Bounded, versioned journal payloads. Unknown keys, duplicate/oversized values and bad enums fail. */
internal object NoticeEventCodec {
    const val VERSION = 1
    const val MAX_BYTES = 65_536
    const val MAX_SOURCE_TEXT = 2_000
    private const val MAX_RANGES = 16
    private const val MAX_SECTIONS = 64
    private const val MAX_EXCEPTIONS = 8
    private const val MAX_PROVENANCE = 16
    private const val MAX_TEXT = 8_192
    private const val MAX_LOCATOR = 512
    private val hash = Regex("[0-9a-f]{64}")
    private val json = Json { isLenient = false; ignoreUnknownKeys = false }

    fun encodeView(view: NoticeOccurrenceView): String {
        require(view.ranges.size <= MAX_RANGES && view.sections.size <= MAX_SECTIONS) { "view too large" }
        return bounded(obj(
            "date" to num(view.date.toEpochDay()),
            "sections" to JsonArray(view.sections.sorted().map { JsonPrimitive(it) }),
            "ranges" to JsonArray(view.ranges.map(::range)),
            "teacher" to str(view.teacher), "room" to str(view.room),
            "project" to nullableStr(view.project), "group" to nullableStr(view.group),
        ).toString())
    }

    fun decodeView(text: String): NoticeOccurrenceView = guarded {
        bounded(text)
        val o = json.parseToJsonElement(text).jsonObject
        findKeyNames(o, setOf("date", "sections", "ranges", "teacher", "room", "project", "group"))
        val date = LocalDate.ofEpochDay(o.long("date"))
        val sections = o.array("sections").map { it.int() }.toSet()
        if (sections.size > MAX_SECTIONS || sections.any { it !in 1..1000 }) invalid()
        val ranges = o.array("ranges").map(::readRange)
        NoticeOccurrenceView(date, sections, ranges, o.text("teacher"), o.text("room"), o.nullText("project"), o.nullText("group"))
    }

    fun encodePayload(payload: NoticeEventPayload): String {
        require(payload.exceptionIds.size <= MAX_EXCEPTIONS && payload.exceptionFingerprints.size == payload.exceptionIds.size) { "payload too large" }
        val previous = payload.previous?.let { p ->
            require(p.sections.size <= MAX_SECTIONS && p.provenance.size <= MAX_PROVENANCE) { "previous too large" }
            obj(
                "kind" to str(p.kind),
                "original" to nullableNum(p.originalEpochDay), "target" to nullableNum(p.targetEpochDay),
                "start" to nullableNum(p.customStartMinute), "end" to nullableNum(p.customEndMinute),
                "hasSections" to JsonPrimitive(p.hasSections),
                "sections" to JsonArray(p.sections.sorted().map { JsonPrimitive(it) }),
                "teacher" to nullableStr(p.teacher), "room" to nullableStr(p.room),
                "project" to nullableStr(p.project), "group" to nullableStr(p.group),
                "provenance" to JsonArray(p.provenance.map { s -> obj(
                    "source" to nullableStr(s.sourceId), "adapter" to nullableStr(s.adapterId), "page" to nullableNum(s.page),
                    "left" to nullableNum(s.left), "top" to nullableNum(s.top), "right" to nullableNum(s.right), "bottom" to nullableNum(s.bottom),
                    "raw" to str(s.rawText.take(MAX_TEXT)), "locator" to nullableStr(s.sourceLocator),
                ) }),
            )
        }
        return bounded(obj(
            "version" to JsonPrimitive(VERSION),
            "mode" to str(payload.mode.name),
            "exceptions" to JsonArray(payload.exceptionIds.map { JsonPrimitive(it) }),
            "fingerprints" to JsonArray(payload.exceptionFingerprints.map(::str)),
            "previous" to (previous ?: JsonNull),
        ).toString())
    }

    fun decodePayload(text: String): NoticeEventPayload = guarded {
        bounded(text)
        val o = json.parseToJsonElement(text).jsonObject
        findKeyNames(o, setOf("version", "mode", "exceptions", "fingerprints", "previous"))
        if (o.int("version") != VERSION) invalid()
        val mode = runCatching { NoticeWriteMode.valueOf(o.text("mode")) }.getOrElse { invalid() }
        val ids = o.array("exceptions").map { it.long() }
        if (ids.size > MAX_EXCEPTIONS || ids.any { it <= 0 } || ids.size != ids.toSet().size) invalid()
        val fingerprints = o.array("fingerprints").map { it.jsonPrimitive.let { p -> if (!p.isString) invalid(); p.content } }
        if (fingerprints.size != ids.size || fingerprints.any { !hash.matches(it) }) invalid()
        val previousElement = o.getValue("previous")
        val previous = if (previousElement == JsonNull) null else {
            val p = previousElement.jsonObject
            val sections = p.array("sections").map { it.int() }
            if (sections.size > MAX_SECTIONS) invalid()
            findKeyNames(p, setOf("kind", "original", "target", "start", "end", "hasSections", "sections", "teacher", "room", "project", "group", "provenance"))
            val provenance = p.array("provenance").map { e ->
                val s = e.jsonObject
                findKeyNames(s, setOf("source", "adapter", "page", "left", "top", "right", "bottom", "raw", "locator"))
                NoticeProvenanceSnapshot(s.nullText("source"), s.nullText("adapter"), s.nullableInt("page"),
                    s.nullableFloat("left"), s.nullableFloat("top"), s.nullableFloat("right"), s.nullableFloat("bottom"),
                    s.text("raw"), s.nullText("locator"))
            }
            if (provenance.size > MAX_PROVENANCE) invalid()
            NoticePreviousException(p.text("kind"), p.nullLong("original"), p.nullLong("target"),
                p.nullableInt("start"), p.nullableInt("end"), p.getValue("hasSections").jsonPrimitive.boolean,
                sections, p.nullText("teacher"), p.nullText("room"), p.nullText("project"), p.nullText("group"), provenance)
        }
        NoticeEventPayload(VERSION, mode, ids, fingerprints, previous)
    }

    fun encodeBasis(basis: NoticeDateBasis): String = bounded(obj(
        "reference" to nullableNum(basis.referenceDate?.toEpochDay()),
        "term" to nullableNum(basis.termMonday?.toEpochDay()),
        "weeks" to nullableNum(basis.totalWeeks),
        "year" to nullableNum(basis.confirmedYear),
    ).toString())

    fun decodeBasis(text: String): NoticeDateBasis = guarded {
        bounded(text)
        val o = json.parseToJsonElement(text).jsonObject
        findKeyNames(o, setOf("reference", "term", "weeks", "year"))
        NoticeDateBasis(
            referenceDate = o.nullLong("reference")?.let(LocalDate::ofEpochDay),
            termMonday = o.nullLong("term")?.let(LocalDate::ofEpochDay),
            totalWeeks = o.nullableInt("weeks"),
            confirmedYear = o.nullableInt("year"),
        )
    }

    private fun range(r: TimeRange) = JsonArray(listOf(str(r.start.toString()), str(r.end.toString())))
    private fun readRange(e: JsonElement): TimeRange {
        val a = e.jsonArray
        if (a.size != 2) invalid()
        val start = runCatching { LocalTime.parse(a[0].jsonPrimitive.content) }.getOrElse { invalid() }
        val end = runCatching { LocalTime.parse(a[1].jsonPrimitive.content) }.getOrElse { invalid() }
        if (end <= start) invalid()
        return TimeRange(start, end)
    }

    private fun bounded(text: String): String {
        if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) invalid()
        var depth = 0; var quoted = false; var escaped = false
        for (c in text) {
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '[', '{' -> if (++depth > 24) invalid(); ']', '}' -> if (--depth < 0) invalid() }
        }
        if (depth != 0 || quoted) invalid()
        return text
    }

    private fun findKeyNames(o: JsonObject, allowed: Set<String>) {
        if (o.keys != allowed) invalid()
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (_: Exception) {
        invalid()
    }

    private fun obj(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*fields))
    private fun str(s: String): JsonElement = JsonPrimitive(s)
    private fun num(n: Number): JsonElement = JsonPrimitive(n)
    private fun nullableStr(s: String?): JsonElement = s?.let(::str) ?: JsonNull
    private fun nullableNum(n: Number?): JsonElement = n?.let(::num) ?: JsonNull
    private fun JsonObject.text(k: String): String = getValue(k).jsonPrimitive.let { if (!it.isString) invalid(); it.content }
    private fun JsonObject.nullText(k: String): String? = getValue(k).takeUnless { it == JsonNull }?.jsonPrimitive?.let { if (!it.isString) invalid(); it.content }
    private fun JsonObject.int(k: String): Int = long(k).let { if (it !in Int.MIN_VALUE..Int.MAX_VALUE) invalid(); it.toInt() }
    private fun JsonObject.long(k: String): Long = getValue(k).jsonPrimitive.let { if (it.isString) invalid(); it.long }
    private fun JsonObject.nullLong(k: String): Long? = getValue(k).takeUnless { it == JsonNull }?.jsonPrimitive?.let { if (it.isString) invalid(); it.long }
    private fun JsonObject.nullableInt(k: String): Int? = nullLong(k)?.let { if (it !in Int.MIN_VALUE..Int.MAX_VALUE) invalid(); it.toInt() }
    private fun JsonObject.nullableFloat(k: String): Float? = getValue(k).takeUnless { it == JsonNull }?.jsonPrimitive?.let { if (it.isString) invalid(); it.float }
    private fun JsonObject.array(k: String): JsonArray = getValue(k).jsonArray.also { if (it.size > MAX_RANGES * 8) invalid() }
    private fun JsonElement.int(): Int = jsonPrimitive.let { if (it.isString) invalid(); it.int }
    private fun JsonElement.long(): Long = jsonPrimitive.let { if (it.isString) invalid(); it.long }

    private fun invalid(): Nothing = throw IllegalArgumentException("INVALID_NOTICE_EVENT")
}

internal enum class NoticeWriteMode { MANUAL, NORMALIZED }

internal data class NoticeEventPayload(
    val version: Int = NoticeEventCodec.VERSION,
    val mode: NoticeWriteMode,
    val exceptionIds: List<Long>,
    val exceptionFingerprints: List<String>,
    val previous: NoticePreviousException?,
)

internal data class NoticePreviousException(
    val kind: String,
    val originalEpochDay: Long?,
    val targetEpochDay: Long?,
    val customStartMinute: Int?,
    val customEndMinute: Int?,
    val hasSections: Boolean,
    val sections: List<Int>,
    val teacher: String?,
    val room: String?,
    val project: String?,
    val group: String?,
    val provenance: List<NoticeProvenanceSnapshot>,
)

internal data class NoticeProvenanceSnapshot(
    val sourceId: String?,
    val adapterId: String?,
    val page: Int?,
    val left: Float?,
    val top: Float?,
    val right: Float?,
    val bottom: Float?,
    val rawText: String,
    val sourceLocator: String?,
)