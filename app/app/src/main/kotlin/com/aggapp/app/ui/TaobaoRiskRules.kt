package com.aggapp.app.ui

import java.net.URI

object TaobaoRiskRules {
    fun isTrustedPage(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase().orEmpty()
        uri.scheme == "https" && uri.rawUserInfo == null && uri.port in setOf(-1, 443) &&
            listOf("taobao.com", "tmall.com", "cainiao.com").any { host == it || host.endsWith(".$it") }
    }.getOrDefault(false)

    fun isCompletionPage(url: String): Boolean {
        if (!isTrustedPage(url)) return false
        val uri = URI(url)
        val host = uri.host.lowercase()
        val path = uri.path.orEmpty().lowercase()
        return listOf("iv.", "login.", "passport.", "safe.").none { host.startsWith(it) } &&
            listOf("/iv", "/verify", "/login", "/security").none { path.startsWith(it) }
    }

    fun canFinish(initial: String, current: String, navigatedToTrustedCompletion: Boolean): Boolean {
        fun cookies(header: String) = header.split(';').mapNotNull { pair ->
            val index = pair.indexOf('=')
            if (index <= 0) null else pair.substring(0, index).trim() to pair.substring(index + 1).trim()
        }.toMap()
        val before = cookies(initial)
        val after = cookies(current)
        if (after["cookie2"].isNullOrBlank() || after["unb"].isNullOrBlank()) return false
        return navigatedToTrustedCompletion || before["cookie2"] != after["cookie2"] || before["unb"] != after["unb"]
    }
}
