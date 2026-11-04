package com.aggapp.platform.api

import okhttp3.FormBody
import okhttp3.Interceptor

/**
 * Debug 构建专用的全量请求日志拦截器（纯 OkHttp，各平台模块 OkHttpClient 统一挂载）。
 *
 * - [enabled] 总开关：仅 debug 包（Application 按 FLAG_DEBUGGABLE 打开）；
 * - [withBody] 请求/响应体摘要（经 LogSanitizer 脱敏处理，绝不泄露凭据/Cookie/Token/手机号/个人信息）；
 * - 日志统一走 [AppLog]（内存环 + logcat + aggapp.log 落盘），导出调试日志时一并带出。
 */
object NetDebug {

    @Volatile
    var enabled: Boolean = false

    @Volatile
    var withBody: Boolean = false

    val interceptor = Interceptor { chain ->
        val req = chain.request()
        if (!enabled) return@Interceptor chain.proceed(req)

        val t0 = System.currentTimeMillis()
        val sanitizedUrl = LogSanitizer.sanitizeUrl(req.url.toString())
        AppLog.d("HTTP", "==> ${req.method} $sanitizedUrl")
        if (withBody) {
            val body = req.body
            when (body) {
                is FormBody -> {
                    val fields = (0 until body.size).joinToString("&") { i ->
                        LogSanitizer.sanitizeFormField(body.encodedName(i), body.encodedValue(i))
                    }
                    AppLog.d("HTTP", "--> form: $fields")
                }
                else -> body?.let { AppLog.d("HTTP", "--> body: ${it.contentType()} (${it.contentLength()}B)") }
            }
        }
        try {
            val resp = chain.proceed(req)
            val ms = System.currentTimeMillis() - t0
            AppLog.d("HTTP", "<== ${resp.code} ${req.method} $sanitizedUrl (${ms}ms)")
            if (withBody) {
                val setCookie = resp.headers.values("Set-Cookie")
                if (setCookie.isNotEmpty()) {
                    AppLog.d("HTTP", "<-- set-cookie: ${LogSanitizer.sanitizeSetCookies(setCookie)}")
                }
                val peek = runCatching { resp.peekBody((64 * 1024).toLong()).string() }.getOrNull()
                if (!peek.isNullOrEmpty()) {
                    AppLog.d("HTTP", "<-- body: ${LogSanitizer.sanitizeResponseBody(peek)}")
                }
            }
            resp
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - t0
            AppLog.e("HTTP", "<!! ${req.method} $sanitizedUrl (${ms}ms) ${e.javaClass.simpleName}: ${LogSanitizer.sanitizeGeneralText(e.message ?: "")}")
            throw e
        }
    }
}
