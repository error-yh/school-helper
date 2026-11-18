package com.aggapp.app.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Upstream pickup-message rules. Reading the inbox is only called by an explicit user action. */
object SmsCodeExtractor {
    data class SmsPickup(val id: String, val code: String, val carrier: String, val station: String,
        val content: String, val receivedAt: Long)
    private const val PREFS = "sms_pickups"
    private const val RETENTION_MS = 3 * 86_400_000L
    private const val MAX_ITEMS = 200
    private val carriers = linkedMapOf("顺丰" to "顺丰速运", "圆通" to "圆通速递", "中通" to "中通快递",
        "申通" to "申通快递", "韵达" to "韵达快递", "百世" to "百世快递", "极兔" to "极兔速递",
        "邮政" to "中国邮政", "EMS" to "EMS", "京东" to "京东物流", "菜鸟" to "菜鸟驿站",
        "丰巢" to "丰巢", "德邦" to "德邦快递", "宅急送" to "宅急送")
    private val codes = listOf(
        Regex("(?:取件码|提货码|凭码|出货码)[^0-9A-Za-z]{0,6}([0-9A-Za-z][0-9A-Za-z\\-]{1,11})"),
        Regex("\\b(\\d{1,2}-\\d{1,3}-\\d{2,6})\\b"),
        Regex("\\b([A-Z]-?\\d{2,3}-?\\d{2,6})\\b"))
    private val stations = Regex("((?:菜鸟驿站|快递驿站|妈妈驿站|丰巢智能柜|[\\u4e00-\\u9fa5]{2,12}(?:驿站|快递点|代收点|便利店|智能柜)))")

    fun hasReadSmsPermission(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun extract(body: String, date: Long): SmsPickup? {
        if (body.length > 500 || listOf("验证码", "校验码", "验证如下", "动态码").any { it in body }) return null
        val text = body.replace('\n', ' ')
        val code = codes.firstNotNullOfOrNull { it.find(text)?.groupValues?.get(1) } ?: return null
        if (codes[0].find(text) == null && !code.contains('-')) return null
        val carrier = carriers.entries.firstOrNull { text.contains(it.key) }?.value.orEmpty()
        val station = stations.find(text)?.groupValues?.get(1).orEmpty()
        val digest = MessageDigest.getInstance("SHA-256").digest("$code|$station|$carrier|$date".toByteArray())
            .joinToString("") { "%02x".format(it) }
        // Keep only pickup fields, never the original SMS body (which may include unrelated private data).
        return SmsPickup(digest, code, carrier, station, listOf(carrier, station, "取件码 $code").filter { it.isNotBlank() }.joinToString(" · "), date)
    }

    suspend fun scanAndMerge(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        if (!hasReadSmsPermission(context)) return@withContext Result.failure(SecurityException("需要主动授权短信读取权限"))
        try {
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            val found = mutableListOf<SmsPickup>()
            context.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("body", "date"),
                "date > ?", arrayOf(cutoff.toString()), "date DESC")?.use { cursor ->
                var inspected = 0
                while (inspected++ < 500 && cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    extract(cursor.getString(0).orEmpty(), cursor.getLong(1))?.let { found += it }
                }
            }
            currentCoroutineContext().ensureActive()
            // Permission may have been revoked while the provider was being queried.
            if (!hasReadSmsPermission(context)) throw SecurityException("短信读取权限已撤销")
            val old = loadAll(context)
            val merged = (old + found).filter { it.receivedAt >= cutoff }.distinctBy { it.id }
                .sortedByDescending { it.receivedAt }.take(MAX_ITEMS)
            save(context, merged)
            Result.success(merged.count { row -> old.none { it.id == row.id } })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }

    fun loadAll(context: Context): List<SmsPickup> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("items_v1", null) ?: return emptyList()
        if (raw.length > 256 * 1024) return emptyList()
        Json.parseToJsonElement(raw).jsonArray.take(MAX_ITEMS).map { e ->
            val o = e.jsonObject
            fun str(key: String) = o.getValue(key).jsonPrimitive.content
            SmsPickup(str("id"), str("code"), str("carrier"), str("station"), str("content"), o.getValue("date").jsonPrimitive.long)
        }.filter { it.receivedAt >= System.currentTimeMillis() - RETENTION_MS }
    }.getOrDefault(emptyList())

    private fun save(context: Context, rows: List<SmsPickup>) {
        val json = buildJsonArray { rows.forEach { r -> add(buildJsonObject {
            put("id", r.id); put("code", r.code); put("carrier", r.carrier); put("station", r.station)
            put("content", r.content); put("date", r.receivedAt)
        }) } }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids = rows.map { it.id }.toSet()
        prefs.edit().putString("items_v1", json.toString())
            .putStringSet("picked_ids", prefs.getStringSet("picked_ids", emptySet()).orEmpty().intersect(ids)).apply()
    }
    fun pickedIds(context: Context): Set<String> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("picked_ids", emptySet()).orEmpty().toSet()
    fun markPicked(context: Context, id: String) {
        if (loadAll(context).none { it.id == id }) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("picked_ids", pickedIds(context) + id).apply()
    }
    fun delete(context: Context, id: String) { save(context, loadAll(context).filterNot { it.id == id }) }
}
