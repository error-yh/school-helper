/* SPDX-License-Identifier: GPL-3.0-only
 * Display-only course grouping configuration for the course management tab.
 * It is stored apart from Room and never mutates courses, meetings or import ownership.
 */
package com.lingion.sleepy.data

import android.content.Context
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal const val COURSE_DISPLAY_GROUP_NAME_MAX = 60
internal const val COURSE_DISPLAY_GROUP_MEMBERS_MAX = 100
private const val COURSE_DISPLAY_GROUP_ID_MAX = 80
private const val COURSE_DISPLAY_GROUP_SIGNATURE_MAX = 400
private const val COURSE_DISPLAY_GROUPS_MAX = 200

/**
 * Isolates one profile (database scope token) and one semester of the display configuration.
 * It deliberately validates nothing: this key is built on the UI path from snapshot values, and a
 * malformed scope must never crash the course tab — it only selects a separate preferences entry.
 */
internal data class CourseDisplayGroupKey(val scopeToken: String, val semesterId: Long) {
    /** One preferences entry per scope/semester, so a late write of an old scope cannot clobber a new one. */
    val storageKey: String get() = "v1|scope=$scopeToken|semester=$semesterId"
}

/** Binds one persisted course record to a manual display group; the signature detects id reuse. */
internal data class CourseGroupMember(val courseId: Long, val signature: String) {
    init {
        require(courseId > 0) { "课程展示分组课程标识无效" }
        require(signature.isNotBlank() && signature.length <= COURSE_DISPLAY_GROUP_SIGNATURE_MAX) { "课程展示分组签名无效" }
    }
}

/** A user-confirmed display group. It references persisted records only; it is not a course. */
internal data class CourseDisplayGroup(val id: String, val name: String, val members: List<CourseGroupMember>) {
    init {
        require(id.isNotBlank() && id.length <= COURSE_DISPLAY_GROUP_ID_MAX) { "课程展示分组标识无效" }
        require(name.isNotBlank() && name.length <= COURSE_DISPLAY_GROUP_NAME_MAX) { "课程展示分组名称无效" }
        require(members.size in 2..COURSE_DISPLAY_GROUP_MEMBERS_MAX) { "课程展示分组至少需要两条课程记录" }
        require(members.map { it.courseId }.distinct().size == members.size) { "同一课程不能重复加入展示分组" }
    }
}

internal sealed interface CourseDisplayGroupRead {
    data class Loaded(val groups: List<CourseDisplayGroup>) : CourseDisplayGroupRead
    data object Empty : CourseDisplayGroupRead
    data class Failed(val message: String) : CourseDisplayGroupRead
}

internal sealed interface CourseDisplayGroupWrite {
    data object Saved : CourseDisplayGroupWrite
    data class Failed(val message: String) : CourseDisplayGroupWrite
}

/** Display-only persistence boundary; implementations never touch courses or schedules. */
internal interface CourseDisplayGroupStore {
    /** Callers run this on an IO dispatcher; implementations must not block the main thread themselves. */
    suspend fun read(key: CourseDisplayGroupKey): CourseDisplayGroupRead
    suspend fun write(key: CourseDisplayGroupKey, groups: List<CourseDisplayGroup>): CourseDisplayGroupWrite
}

/**
 * Strict, versioned JSON for the display grouping only. Unknown fields, invalid counts or a broken
 * signature make the whole value unreadable, so a corrupt file falls back to the automatic list.
 */
internal object CourseDisplayGroupCodec {
    private const val VERSION = 1
    private val rootKeys = setOf("version", "groups")
    private val groupKeys = setOf("id", "name", "members")
    private val memberKeys = setOf("courseId", "signature")

    fun encode(groups: List<CourseDisplayGroup>): String {
        require(groups.size <= COURSE_DISPLAY_GROUPS_MAX) { "展示分组数量过多" }
        return buildJsonObject {
            put("version", VERSION)
            putJsonArray("groups") {
                groups.forEach { group ->
                    addJsonObject {
                        put("id", group.id)
                        put("name", group.name)
                        putJsonArray("members") {
                            group.members.forEach { member ->
                                addJsonObject {
                                    put("courseId", member.courseId)
                                    put("signature", member.signature)
                                }
                            }
                        }
                    }
                }
            }
        }.toString()
    }

    fun decode(raw: String): List<CourseDisplayGroup> = try {
        parse(raw)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("展示分组配置格式无效")
    } catch (_: SerializationException) {
        throw IllegalArgumentException("展示分组配置格式无效")
    }

    private fun parse(raw: String): List<CourseDisplayGroup> {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: invalid()
        if (root.keys != rootKeys) invalid()
        val version = root.getValue("version").jsonPrimitive
        if (version.isString || version.long != VERSION.toLong()) invalid()
        val groups = root.getValue("groups") as? JsonArray ?: invalid()
        if (groups.size > COURSE_DISPLAY_GROUPS_MAX) invalid()
        return groups.map { element ->
            val group = element as? JsonObject ?: invalid()
            if (group.keys != groupKeys) invalid()
            val members = group.getValue("members") as? JsonArray ?: invalid()
            CourseDisplayGroup(
                id = group.getValue("id").text(),
                name = group.getValue("name").text(),
                members = members.map { member ->
                    val fields = member as? JsonObject ?: invalid()
                    if (fields.keys != memberKeys) invalid()
                    CourseGroupMember(
                        courseId = fields.getValue("courseId").longValue(),
                        signature = fields.getValue("signature").text(),
                    )
                },
            )
        }
    }

    private fun kotlinx.serialization.json.JsonElement.text(): String =
        jsonPrimitive.let { if (it.isString) it.content else invalid() }

    private fun kotlinx.serialization.json.JsonElement.longValue(): Long =
        jsonPrimitive.let { if (it.isString) invalid() else it.long }

    private fun invalid(): Nothing = throw IllegalArgumentException("展示分组配置格式无效")
}

/**
 * Per-scope SharedPreferences backed store. A single key is rewritten with [android.content.SharedPreferences.Editor.commit]
 * so the value is replaced atomically and a failed commit is reported instead of claimed as saved.
 */
internal class SharedPreferencesCourseDisplayGroupStore(
    context: Context,
    fileName: String = "course_display_groups",
) : CourseDisplayGroupStore {
    private val preferences = context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    override suspend fun read(key: CourseDisplayGroupKey): CourseDisplayGroupRead {
        val raw = try {
            preferences.getString(key.storageKey, null)
        } catch (_: ClassCastException) {
            return CourseDisplayGroupRead.Failed("展示分组配置无法读取，已按自动分组显示。")
        } ?: return CourseDisplayGroupRead.Empty
        return try {
            CourseDisplayGroupRead.Loaded(CourseDisplayGroupCodec.decode(raw))
        } catch (_: IllegalArgumentException) {
            CourseDisplayGroupRead.Failed("展示分组配置已损坏，已按自动分组显示。可重新整理后保存。")
        }
    }

    override suspend fun write(key: CourseDisplayGroupKey, groups: List<CourseDisplayGroup>): CourseDisplayGroupWrite {
        val encoded = try {
            if (groups.isEmpty()) null else CourseDisplayGroupCodec.encode(groups)
        } catch (_: IllegalArgumentException) {
            return CourseDisplayGroupWrite.Failed("展示分组配置无法生成，未保存。")
        }
        return try {
            // A value this build cannot read back is never written or reported as saved.
            encoded?.let { CourseDisplayGroupCodec.decode(it) }
            val editor = if (encoded == null) preferences.edit().remove(key.storageKey) else preferences.edit().putString(key.storageKey, encoded)
            if (editor.commit()) CourseDisplayGroupWrite.Saved else CourseDisplayGroupWrite.Failed("展示分组未保存成功，请重试。")
        } catch (_: IllegalArgumentException) {
            CourseDisplayGroupWrite.Failed("展示分组配置无法生成，未保存。")
        }
    }
}