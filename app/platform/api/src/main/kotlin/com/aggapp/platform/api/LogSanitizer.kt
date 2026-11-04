package com.aggapp.platform.api

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 日志与调试导出脱敏工具（S6-02-R3 深度隐私安全关闭与完整遮蔽）：
 * - URL Query 脱敏（保留 host/path，敏感 query 参数掩码）
 * - Form 表单参数脱敏（敏感参数键名保留、值掩码；历史表单参数默认全量掩码安全关闭）
 * - Set-Cookie 响应头脱敏（仅保留 cookie 键名及安全属性，绝不输出 value）
 * - 响应 Body 脱敏（安全退化，仅限数值/布尔与已知枚举常量，严禁任意 uppercase 字符串放行；普通文本安全省略原文）
 * - 全局通用文本脱敏（手机号、JWT、各种带引号/含空格的键值对完整遮蔽）
 * - 凭据导出安全遮蔽（明确白名单 + 未知字段默认掩码安全关闭）
 * - 历史日志导出过滤（未知/多行/不可可靠分类的历史行默认长度摘要，禁止通用正则原样放行）
 */
object LogSanitizer {

    private val SENSITIVE_PARAM_KEYS = setOf(
        "token", "accesstoken", "access_token", "jwt", "auth", "authorization",
        "cookie", "set-cookie", "cookie2", "unb", "skt", "_m_h5_tk", "_m_h5_tk_enc",
        "pt_key", "pt_pin", "pin", "staticseg", "static_seg",
        "password", "secret", "secret_key", "secretkey", "sign", "signature",
        "code", "smscode", "sms_code", "smstoken", "sms_token", "vcode", "validcode", "captcha", "captcha_output",
        "pass_token", "lot_number", "ticket", "vat", "salt", "gsalt",
        "phone", "mobile", "receivername", "sendername", "address", "pickupcode", "pick_code", "takecode",
        "csrf", "_csrf", "session", "sessionid", "credential", "key", "secret_token",
    )

    // 已知安全的顶层非敏感结构化诊断键名白名单
    private val SAFE_DIAGNOSTIC_KEYS = setOf(
        "code", "status", "success", "error_code", "versioncode", "duration", "cost", "total_len",
    )

    // 有限已知可信状态枚举常量（绝不接受任意大写字符串当做枚举）
    private val SAFE_STATUS_CONSTANTS = setOf(
        "SUCCESS", "FAIL", "FAILED", "OK", "ERROR", "PENDING", "WAITING",
        "0", "1", "200", "true", "false",
    )

    private val PHONE_REGEX = Regex("""(?<!\d)(1[3-9]\d)\d{4}(\d{4})(?!\d)""")
    private val JWT_REGEX = Regex("""eyJ[A-Za-z0-9-_]+\.eyJ[A-Za-z0-9-_]+\.[A-Za-z0-9-_]+""")

    // 匹配带引号包裹的值（JSON等格式，值允许包含空格，直至闭合引号，实现完整遮罩）
    private val QUOTED_KV_REGEX = Regex(
        """(?i)(["']?([A-Za-z0-9_\-]+)["']?\s*[:=]\s*)(["'])([^"'\r\n]+)(["'])"""
    )

    // 匹配未带引号的键值对（URL query或普通键值，值排除空格和分隔符）
    private val UNQUOTED_KV_REGEX = Regex(
        """(?i)(\b([A-Za-z0-9_\-]+)\s*[:=]\s*)([^\s"'\r\n,;&=]+)"""
    )

    fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().replace("\"", "").replace("'", "").replace("_", "").replace("-", "")
        return SENSITIVE_PARAM_KEYS.any {
            val target = it.replace("_", "").replace("-", "")
            normalized == target || normalized.contains(target)
        }
    }

    private fun isSafeDiagnosticKey(key: String): Boolean {
        val normalized = key.lowercase().replace("\"", "").replace("'", "").replace("_", "").replace("-", "")
        return SAFE_DIAGNOSTIC_KEYS.any {
            val target = it.replace("_", "").replace("-", "")
            normalized == target
        }
    }

    /** 对 URL 脱敏：保留协议、域名、路径，对包含敏感信息的 query 参数值进行打码 */
    fun sanitizeUrl(rawUrl: String): String {
        val httpUrl = rawUrl.toHttpUrlOrNull() ?: return sanitizeGeneralText(rawUrl)
        val queryNames = httpUrl.queryParameterNames
        if (queryNames.isEmpty()) return httpUrl.newBuilder().query(null).build().toString()

        val builder = httpUrl.newBuilder()
        for (name in queryNames) {
            val values = httpUrl.queryParameterValues(name)
            builder.removeAllQueryParameters(name)
            if (isSensitiveKey(name)) {
                builder.addQueryParameter(name, "[MASKED]")
            } else {
                for (v in values) {
                    builder.addQueryParameter(name, if (v != null) sanitizeGeneralText(v) else "")
                }
            }
        }
        return builder.build().toString()
    }

    /** 对单个表单字段脱敏：敏感键值替换为 [MASKED] */
    fun sanitizeFormField(name: String, value: String): String {
        return if (isSensitiveKey(name)) {
            "$name=[MASKED]"
        } else {
            "$name=${sanitizeGeneralText(value)}"
        }
    }

    /**
     * 对 Set-Cookie 头脱敏：
     * 绝不输出 Cookie 明文值，仅输出 Cookie 键名列表以及非敏感属性（Path, Domain, HttpOnly 等）。
     */
    fun sanitizeSetCookies(setCookies: List<String>): String {
        if (setCookies.isEmpty()) return ""
        val sanitized = setCookies.map { cookieHeader ->
            val parts = cookieHeader.split(";").map { it.trim() }
            if (parts.isEmpty()) return@map "[EMPTY]"
            val first = parts[0]
            val eqIdx = first.indexOf('=')
            val key = if (eqIdx > 0) first.substring(0, eqIdx).trim() else first
            val attrs = parts.drop(1).map { attr ->
                val attrEq = attr.indexOf('=')
                val attrKey = if (attrEq > 0) attr.substring(0, attrEq).trim().lowercase() else attr.lowercase()
                when (attrKey) {
                    "path", "domain", "expires", "max-age", "samesite", "secure", "httponly" -> attr
                    else -> "$attrKey=[ATTR]"
                }
            }
            if (attrs.isEmpty()) "$key=[MASKED]" else "$key=[MASKED]; ${attrs.joinToString("; ")}"
        }
        return sanitized.joinToString(" | ")
    }

    /**
     * 对响应 body 脱敏（安全退化）：
     * 不全量 dump，仅提取类型受限的确定性数值/布尔安全元数据（如数字 code、布尔 success、数字 error_code）；
     * 绝不原样输出任意 message 字符串，严禁任意 uppercase 字符串伪充状态枚举，普通文本一律退化为 [TEXT len=$len]。
     */
    fun sanitizeResponseBody(bodySnippet: String, contentType: String? = null): String {
        if (bodySnippet.isBlank()) return "[EMPTY BODY]"
        val trimmed = bodySnippet.trim()
        val len = bodySnippet.length

        if (trimmed.startsWith("<html") || trimmed.startsWith("<!DOCTYPE") || trimmed.startsWith("<?xml")) {
            return "[HTML/XML Page len=$len]"
        }

        // JSON 响应：仅提取严格类型受限的已知安全元数据
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return extractSafeJsonMetadata(trimmed, len)
        }

        // 普通纯文本响应：安全省略原文，仅保留长度
        return "[TEXT len=$len, sensitive body omitted]"
    }

    private fun extractSafeJsonMetadata(jsonStr: String, originalLen: Int): String {
        val safeFields = mutableListOf<String>()

        // 1. 严格受限的数字/布尔状态字段
        // code: 数字
        val codeMatch = Regex("\"code\"\\s*:\\s*(-?\\d+)").find(jsonStr)
        if (codeMatch != null) safeFields.add("code=${codeMatch.groupValues[1]}")

        // status: 仅限数字，或白名单内已知有限状态枚举常量；绝不放行任意大写字符串
        val statusMatch = Regex("\"status\"\\s*:\\s*(-?\\d+|\"([^\"]+)\")").find(jsonStr)
        if (statusMatch != null) {
            val raw = (statusMatch.groupValues[2].takeIf { it.isNotEmpty() } ?: statusMatch.groupValues[1]).replace("\"", "")
            if (raw.matches(Regex("-?\\d+")) || SAFE_STATUS_CONSTANTS.contains(raw.uppercase())) {
                safeFields.add("status=$raw")
            } else {
                safeFields.add("status=[STATUS len=${raw.length}]")
            }
        }

        // error_code: 数字
        val errCodeMatch = Regex("\"error_code\"\\s*:\\s*(-?\\d+)").find(jsonStr)
        if (errCodeMatch != null) safeFields.add("error_code=${errCodeMatch.groupValues[1]}")

        // success: 布尔值
        val successMatch = Regex("\"success\"\\s*:\\s*(true|false)").find(jsonStr)
        if (successMatch != null) safeFields.add("success=${successMatch.groupValues[1]}")

        // ret: 仅当明确匹配到 "SUCCESS" 开头时输出安全 ret=SUCCESS，任何其他任意大写/小写字符串绝不输出其内容
        val retMatch = Regex("\"ret\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").find(jsonStr)
        if (retMatch != null) {
            val retVal = retMatch.groupValues[1]
            if (retVal.startsWith("SUCCESS", ignoreCase = true)) {
                safeFields.add("ret=SUCCESS")
            } else {
                safeFields.add("ret=[RET len=${retVal.length}]")
            }
        }

        // 2. message / msg 等文本字段：绝不输出原文，仅输出存在性与长度摘要
        val msgMatch = Regex("\"(?:msg|message|error_msg)\"\\s*:\\s*\"(.*?)(?<!\\\\)\"").find(jsonStr)
        if (msgMatch != null) {
            val msgLen = msgMatch.groupValues[1].length
            safeFields.add("msg=[TEXT len=$msgLen]")
        }

        return if (safeFields.isNotEmpty()) {
            "{${safeFields.joinToString(", ")}, [METADATA ONLY, total_len=$originalLen]}"
        } else {
            "[JSON Object len=$originalLen, sensitive data filtered]"
        }
    }

    /**
     * 全局通用文本脱敏：
     * 1. 手机号：138****1234
     * 2. JWT：[JWT MASKED]
     * 3. 键值对脱敏：敏感键的值完整替换为 [MASKED]（含带引号与空格的值）
     */
    fun sanitizeGeneralText(input: String): String {
        if (input.isBlank()) return input
        var res = input
        res = PHONE_REGEX.replace(res) { m -> "${m.groupValues[1]}****${m.groupValues[2]}" }
        res = JWT_REGEX.replace(res, "[JWT MASKED]")

        // 1. 先匹配并完整遮蔽带引号的字符串键值（支持包含空格的值）
        res = QUOTED_KV_REGEX.replace(res) { m ->
            val prefix = m.groupValues[1]
            val keyName = m.groupValues[2]
            val openQuote = m.groupValues[3]
            val closeQuote = m.groupValues[5]

            if (isSensitiveKey(keyName)) {
                "$prefix$openQuote[MASKED]$closeQuote"
            } else {
                m.value
            }
        }

        // 2. 再匹配无引号的普通键值对
        res = UNQUOTED_KV_REGEX.replace(res) { m ->
            val prefix = m.groupValues[1]
            val keyName = m.groupValues[2]

            if (isSensitiveKey(keyName)) {
                "$prefix[MASKED]"
            } else {
                m.value
            }
        }

        return res
    }

    /**
     * 表单片段脱敏（默认安全关闭）：
     * 对历史表单参数中的所有业务值一律掩码为 [MASKED]，绝不因未知键放行明文；
     * 仅当键名非敏感且值为确定性纯数字或布尔时才允许保留数值。
     */
    private fun sanitizeFormSnippet(formSnippet: String): String {
        if (formSnippet.isBlank()) return formSnippet
        val tokens = formSnippet.split(Regex("""(?<=[&;\s])|(?=[&;\s])"""))
        return tokens.joinToString("") { token ->
            val eqIdx = token.indexOf('=')
            if (eqIdx > 0) {
                val k = token.substring(0, eqIdx).trim()
                val v = token.substring(eqIdx + 1).trim()
                val isSafeNumericOrBool = (v.matches(Regex("""-?\d+""")) || v == "true" || v == "false")
                if (!isSensitiveKey(k) && isSafeNumericOrBool) {
                    "$k=$v"
                } else {
                    "$k=[MASKED]"
                }
            } else {
                sanitizeGeneralText(token)
            }
        }
    }

    /**
     * 历史日志行脱敏（用于 aggapp.log 导出）：
     * 逐行处理，未知/多行/不可可靠分类的历史行默认省略或遮蔽，禁止继续用通用正则原样兜底。
     */
    fun sanitizeLogLine(line: String): String {
        if (line.isBlank()) return line
        val trimmed = line.trim()

        // 1. 整行 JSON 对象或数组：安全退化为元数据
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return sanitizeResponseBody(trimmed)
        }

        // 2. 包含 body 标记的行
        val bodyMarkerIdx = line.indexOf("body:")
        if (bodyMarkerIdx >= 0) {
            val prefix = line.substring(0, bodyMarkerIdx + 5)
            val bodyPart = line.substring(bodyMarkerIdx + 5).trim()
            return sanitizeGeneralText(prefix) + " " + sanitizeResponseBody(bodyPart)
        }

        // 3. 包含 form 标记的行（如 '2026-11-02 10:00:00.123 --> form: ...'）
        val formMarkerIdx = line.indexOf("form:")
        if (formMarkerIdx >= 0) {
            val prefix = line.substring(0, formMarkerIdx + 5)
            val formPart = line.substring(formMarkerIdx + 5).trim()
            return sanitizeGeneralText(prefix) + " " + sanitizeFormSnippet(formPart)
        }

        // 4. 多行 continuation 行检测（如 JSON 缩进独立行: '  "remark": "xxx",' 或未知多行业务字段）
        if (line.startsWith("  ") || line.startsWith("\t") || trimmed.startsWith("\"")) {
            val kvMatch = Regex("""^\s*["']?([A-Za-z0-9_\-]+)["']?\s*[:=]\s*(.+)$""").find(line)
            if (kvMatch != null) {
                val key = kvMatch.groupValues[1]
                val rawVal = kvMatch.groupValues[2].trim().removeSuffix(",")
                val indent = line.takeWhile { it.isWhitespace() }
                // 仅当键名非敏感且值为确定性纯数字或布尔时才允许保留数值；任何动态文本一律掩码
                val isSafeNumericOrBool = (rawVal.matches(Regex("-?\\d+")) || rawVal == "true" || rawVal == "false")
                return if (!isSensitiveKey(key) && isSafeDiagnosticKey(key) && isSafeNumericOrBool) {
                    "$indent\"$key\": $rawVal,"
                } else {
                    "$indent\"$key\": [MASKED]"
                }
            }
            if (trimmed == "{" || trimmed == "}" || trimmed == "[" || trimmed == "]" || trimmed == "},") {
                return line
            }
            return "[OMITTED CONTINUATION LINE, len=${line.length}]"
        }

        // 5. 常见 HTTP 请求/响应结构行
        val httpReqMatch = Regex("""(?i)^(.*?-->\s*(?:GET|POST|PUT|DELETE)\s+)(\S+)(.*)$""").find(line)
        if (httpReqMatch != null) {
            val prefix = httpReqMatch.groupValues[1]
            val url = httpReqMatch.groupValues[2]
            val suffix = httpReqMatch.groupValues[3]
            return prefix + sanitizeUrl(url) + sanitizeGeneralText(suffix)
        }
        val httpRespMatch = Regex("""(?i)^(.*?<--\s*\d{3}\s+.*)$""").find(line)
        if (httpRespMatch != null) {
            return sanitizeGeneralText(line)
        }

        // 6. 普通带时间戳的单行未知文本：提取时间戳/TAG，对内部私密自由文本做长度摘要，杜绝任意文本原样放行
        val timestampMatch = Regex("""^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:\s+\[[^\]]+\])?\s*)(.*)$""").find(line)
        if (timestampMatch != null) {
            val tsPrefix = timestampMatch.groupValues[1]
            val textPart = timestampMatch.groupValues[2]
            return "$tsPrefix[TEXT len=${textPart.length}, sensitive details omitted]"
        }

        // 7. 纯未知单行日志：安全省略
        return "[LOG LINE len=${line.length}, sensitive details omitted]"
    }

    /** 导出凭据时的 token 掩码（完整掩码加长度，绝不暴露首尾字符） */
    fun maskToken(token: String): String {
        if (token.isBlank()) return "[EMPTY]"
        return "[MASKED, len=${token.length}]"
    }

    /**
     * 导出凭据时的 extra 字典掩码（安全白名单机制）：
     * 仅白名单内的已知非敏感/诊断字段保留脱敏摘要；
     * 所有未知字段、凭据字段、敏感字段一律默认掩码安全关闭（[MASKED, len=...]）。
     */
    fun maskExtra(extra: Map<String, String>): Map<String, String> {
        return extra.mapValues { (k, v) ->
            val keyLower = k.lowercase()
            when {
                keyLower == "mobile" || keyLower == "phone" -> {
                    PHONE_REGEX.replace(v) { m -> "${m.groupValues[1]}****${m.groupValues[2]}" }
                }
                keyLower == "pdd_user_id" || keyLower == "user_id" -> {
                    "[USER_ID len=${v.length}]"
                }
                keyLower == "pdd_uin" -> {
                    "[UIN len=${v.length}]"
                }
                keyLower == "nick" || keyLower == "tracknick" -> {
                    "[NICK len=${v.length}]"
                }
                else -> "[MASKED, len=${v.length}]"
            }
        }
    }
}
