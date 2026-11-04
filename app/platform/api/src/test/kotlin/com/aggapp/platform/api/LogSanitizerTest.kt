package com.aggapp.platform.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LogSanitizerTest {

    private val SENSITIVE_TOKEN_MARKER = "SECRET_TOKEN_VALUE_XYZ_987"
    private val SENSITIVE_COOKIE_MARKER = "COOKIE2_SECRET_UNB_776655"
    private val SENSITIVE_PHONE_MARKER = "13800138000"
    private val SENSITIVE_CODE_MARKER = "889900"
    private val SENSITIVE_ADDR_MARKER = "北京市海淀区中关村南大街1号"
    private val SENSITIVE_PRIVATE_MSG_MARKER = "PRIVATE_VERIFY_FAILED_DETAIL_SECRET"
    private val SENSITIVE_CSRF_MARKER = "CSRF_TOKEN_SYNTHETIC_MARKER_99"

    @Before
    fun setUp() {
        AppLog.clear()
    }

    @Test
    fun testUrlSanitizationMasksSensitiveQueryParams() {
        val rawUrl = "https://api.example.com/v1/auth/callback?token=$SENSITIVE_TOKEN_MARKER&code=$SENSITIVE_CODE_MARKER&phone=$SENSITIVE_PHONE_MARKER&safe_id=12345"
        val sanitized = LogSanitizer.sanitizeUrl(rawUrl)

        assertFalse("Token 敏感 Marker 不得泄露在脱敏 URL 中", sanitized.contains(SENSITIVE_TOKEN_MARKER))
        assertFalse("Code 验证码 Marker 不得泄露在脱敏 URL 中", sanitized.contains(SENSITIVE_CODE_MARKER))
        assertFalse("手机号 Marker 不得完整泄露在脱敏 URL 中", sanitized.contains(SENSITIVE_PHONE_MARKER))
        assertTrue("非敏感路径与参数安全保留", sanitized.contains("https://api.example.com/v1/auth/callback"))
        assertTrue("非敏感 safe_id 参数值保留", sanitized.contains("safe_id=12345"))
        assertTrue("敏感参数被遮蔽为 [MASKED]", sanitized.contains("token=%5BMASKED%5D") || sanitized.contains("token=[MASKED]"))
    }

    @Test
    fun testFormFieldSanitization() {
        val fieldToken = LogSanitizer.sanitizeFormField("smsToken", SENSITIVE_TOKEN_MARKER)
        val fieldCode = LogSanitizer.sanitizeFormField("smsCode", SENSITIVE_CODE_MARKER)
        val fieldSafe = LogSanitizer.sanitizeFormField("pageIndex", "2")

        assertFalse("表单 Token 必须脱敏", fieldToken.contains(SENSITIVE_TOKEN_MARKER))
        assertTrue("表单 Token 键名保留并掩码", fieldToken == "smsToken=[MASKED]")
        assertFalse("表单短信验证码必须脱敏", fieldCode.contains(SENSITIVE_CODE_MARKER))
        assertTrue("非敏感表单参数保留", fieldSafe == "pageIndex=2")
    }

    @Test
    fun testSetCookieSanitizationPreservesKeysAndMasksValues() {
        val cookies = listOf(
            "cookie2=$SENSITIVE_COOKIE_MARKER; Path=/; Domain=.taobao.com; HttpOnly",
            "unb=123456789; Path=/",
            "_m_h5_tk=987654321_123; Path=/; Secure",
        )
        val sanitized = LogSanitizer.sanitizeSetCookies(cookies)

        assertFalse("Set-Cookie 中不得出现敏感 Cookie 明文值", sanitized.contains(SENSITIVE_COOKIE_MARKER))
        assertFalse("Set-Cookie 中不得出现 unb 明文值", sanitized.contains("123456789"))
        assertTrue("保留 Cookie 键名 cookie2", sanitized.contains("cookie2=[MASKED]"))
        assertTrue("保留 Cookie 键名 unb", sanitized.contains("unb=[MASKED]"))
        assertTrue("保留 Cookie 属性 HttpOnly", sanitized.contains("HttpOnly") || sanitized.contains("httponly"))
    }

    @Test
    fun testResponseBodySanitizationExtractsMetadataAndFiltersBody() {
        // 反例 1：包含敏感 token 与地址的 JSON
        val sensitiveJsonBody = """
            {
                "code": 0,
                "msg": "$SENSITIVE_PRIVATE_MSG_MARKER",
                "data": {
                    "token": "$SENSITIVE_TOKEN_MARKER",
                    "receiverAddress": "$SENSITIVE_ADDR_MARKER",
                    "receiverPhone": "$SENSITIVE_PHONE_MARKER"
                }
            }
        """.trimIndent()

        val sanitized = LogSanitizer.sanitizeResponseBody(sensitiveJsonBody)

        assertFalse("响应体中的 Token Marker 绝不得泄露", sanitized.contains(SENSITIVE_TOKEN_MARKER))
        assertFalse("响应体中的收件地址绝不得泄露", sanitized.contains(SENSITIVE_ADDR_MARKER))
        assertFalse("响应体中的收件手机号绝不得泄露", sanitized.contains(SENSITIVE_PHONE_MARKER))
        // 反例 2：msg / message 中的私密内容绝不得原样输出
        assertFalse("响应体中的私密 message 原文绝不得泄露", sanitized.contains(SENSITIVE_PRIVATE_MSG_MARKER))
        assertTrue("顶层安全受限数值元数据 code 保留", sanitized.contains("code=0"))
        assertTrue("msg 文本安全退化为长度元数据", sanitized.contains("msg=[TEXT len="))
        assertTrue("标注仅元数据与总长度", sanitized.contains("METADATA ONLY"))

        // 反例 3：普通纯文本响应绝不截取原文输出
        val plainTextResp = "User verify failed: internal private reason token=$SENSITIVE_TOKEN_MARKER"
        val sanitizedPlain = LogSanitizer.sanitizeResponseBody(plainTextResp)
        assertFalse("普通文本响应不得泄露私密原文", sanitizedPlain.contains(SENSITIVE_TOKEN_MARKER))
        assertFalse("普通文本响应不得输出原文开头", sanitizedPlain.contains("User verify failed"))
        assertTrue("普通文本响应退化为长度元数据", sanitizedPlain.startsWith("[TEXT len="))
    }

    @Test
    fun testAppLogJsonQuotesMaskingProtectsAllSinks() {
        var logcatOutput = ""
        var fileOutput = ""
        AppLog.logcatSink = { _, _, line -> logcatOutput = line }
        AppLog.fileSink = { line -> fileOutput = line }

        // 反例 4：带双引号的 JSON 格式 {"token":"<合成标记>"}
        val jsonPayload = """{"token":"$SENSITIVE_TOKEN_MARKER","phone":"$SENSITIVE_PHONE_MARKER","secret":"$SENSITIVE_CODE_MARKER"}"""
        AppLog.d("TEST_JSON", jsonPayload)

        val dumped = AppLog.dump()

        // 验证内存环 dump
        assertFalse("AppLog 内存环不得泄露 JSON 引号包裹的 Token Marker", dumped.contains(SENSITIVE_TOKEN_MARKER))
        assertFalse("AppLog 内存环不得泄露完整手机号", dumped.contains(SENSITIVE_PHONE_MARKER))
        assertFalse("AppLog 内存环不得泄露 secret", dumped.contains(SENSITIVE_CODE_MARKER))
        assertTrue("带引号的 token 键值对被成功掩码", dumped.contains("\"token\":\"[MASKED]\"") || dumped.contains("\"token\": \"[MASKED]\""))

        // 验证 logcatSink 与 fileSink
        assertFalse("logcatSink 不得泄露 Token", logcatOutput.contains(SENSITIVE_TOKEN_MARKER))
        assertFalse("fileSink 不得泄露 Token", fileOutput.contains(SENSITIVE_TOKEN_MARKER))
    }

    @Test
    fun testCredentialExportMaskingUnknownExtraDefaultsToMasked() {
        val maskedToken = LogSanitizer.maskToken(SENSITIVE_TOKEN_MARKER)
        assertFalse("导出 Token 掩码后不得包含明文", maskedToken.contains(SENSITIVE_TOKEN_MARKER))
        assertEquals("[MASKED, len=${SENSITIVE_TOKEN_MARKER.length}]", maskedToken)

        // 反例 5：未知凭据 extra（如 csrf 等）必须安全关闭掩码，绝不默认保留
        val extra = mapOf(
            "mobile" to SENSITIVE_PHONE_MARKER,
            "csrf" to SENSITIVE_CSRF_MARKER,
            "_csrf" to "secret_csrf_value",
            "pdd_user_id" to "1234567890",
            "unknown_custom_key" to "untrusted_payload_99",
        )
        val maskedExtra = LogSanitizer.maskExtra(extra)
        assertEquals("138****8000", maskedExtra["mobile"])
        assertFalse("csrf 不得原样输出", maskedExtra["csrf"]!!.contains(SENSITIVE_CSRF_MARKER))
        assertTrue("csrf 必须掩码", maskedExtra["csrf"]!!.startsWith("[MASKED"))
        assertFalse("未知 key 绝不得默认保留原样", maskedExtra["unknown_custom_key"]!!.contains("untrusted_payload_99"))
        assertTrue("未知 key 必须默认安全掩码", maskedExtra["unknown_custom_key"]!!.startsWith("[MASKED"))
        assertTrue("已知诊断标识保留安全摘要", maskedExtra["pdd_user_id"]!!.contains("[USER_ID"))
    }

    @Test
    fun testLegacyLogLineSanitizationProtectsHistoricalLogs() {
        // 反例 6：旧 aggapp.log 中的历史未脱敏日志行与响应 body
        val legacyHttpLog = """2026-10-22 10:00:00 [OkHttp] <-- 200 OK body: {"token":"$SENSITIVE_TOKEN_MARKER","msg":"$SENSITIVE_PRIVATE_MSG_MARKER"}"""
        val sanitizedLine = LogSanitizer.sanitizeLogLine(legacyHttpLog)

        assertFalse("历史日志行导出不得包含 Token 明文", sanitizedLine.contains(SENSITIVE_TOKEN_MARKER))
        assertFalse("历史日志行导出不得包含私密 message 明文", sanitizedLine.contains(SENSITIVE_PRIVATE_MSG_MARKER))
        assertTrue("历史日志行正确退化为安全元数据", sanitizedLine.contains("METADATA ONLY") || sanitizedLine.contains("[MASKED]"))
    }

    // ==================== S6-02-R2 追加复审泄露反例测试 ====================

    @Test
    fun testLegacyTimestampFormLineMasksSensitiveSmsToken() {
        // R2 反例 1：带时间戳与前缀的旧 form 行，smsToken 绝不泄露
        val legacyFormLine = "2026-11-02 10:00:00.123 [OkHttp] --> form: smsToken=$SENSITIVE_TOKEN_MARKER&safe_id=8888"
        val sanitized = LogSanitizer.sanitizeLogLine(legacyFormLine)

        assertFalse("带时间戳前缀的旧 form 行中的 smsToken 标记绝不得泄露", sanitized.contains(SENSITIVE_TOKEN_MARKER))
        assertTrue("smsToken 必须被成功掩码", sanitized.contains("smsToken=[MASKED]"))
        assertTrue("非敏感 safe_id 参数保留", sanitized.contains("safe_id=8888"))
    }

    @Test
    fun testLegacyMultilineContinuationUnknownRemarkMasked() {
        // R2 反例 2：多行 JSON 或独立行中的未知业务字段（如 remark），绝不得原样放行
        val continuationLine = "  \"remark\": \"$SENSITIVE_PRIVATE_MSG_MARKER\","
        val sanitized = LogSanitizer.sanitizeLogLine(continuationLine)

        assertFalse("多行响应 continuation 中的未知 remark 标记绝不得原样输出", sanitized.contains(SENSITIVE_PRIVATE_MSG_MARKER))
        assertTrue("未知业务字段必须安全掩码", sanitized.contains("\"remark\": [MASKED]"))
    }

    @Test
    fun testArbitraryUppercaseStatusAndRetNeverLeaked() {
        // R2 反例 3：JSON status 与 ret 中出现的任意大写标记绝不得原样放行
        val arbitraryUppercaseJson = """
            {
                "code": 200,
                "status": "TOKEN_SECRET_XYZ_999",
                "ret": ["ARBITRARY_UPPERCASE_MARKER"],
                "success": true
            }
        """.trimIndent()

        val sanitized = LogSanitizer.sanitizeResponseBody(arbitraryUppercaseJson)

        assertFalse("status 中的任意大写合成标记绝不得原样输出", sanitized.contains("TOKEN_SECRET_XYZ_999"))
        assertFalse("ret 中的任意大写合成标记绝不得原样输出", sanitized.contains("ARBITRARY_UPPERCASE_MARKER"))
        assertTrue("顶层安全数值与布尔保留", sanitized.contains("code=200") && sanitized.contains("success=true"))
        assertTrue("status 退化为受控元数据", sanitized.contains("status=[STATUS len=20]"))
        assertTrue("ret 退化为受控元数据", sanitized.contains("ret=[RET len=26]"))
    }

    // ==================== S6-02-R3 追加复审泄露反例测试 ====================

    @Test
    fun testR3TimestampArbitrarySecretLineReducesToLengthSummary() {
        // R3 反例 1：带时间戳前缀的任意未知私密文本整行安全退化为长度摘要，标记绝不残留
        val secretLine = "2026-11-02 12:00:00.000 [PrivateModule] $SENSITIVE_PRIVATE_MSG_MARKER with random details"
        val sanitized = LogSanitizer.sanitizeLogLine(secretLine)

        assertFalse("时间戳未知行中的私密标记绝不得残留", sanitized.contains(SENSITIVE_PRIVATE_MSG_MARKER))
        assertTrue("未知私密行必须退化为带时间戳的长度摘要", sanitized.contains("[TEXT len="))
    }

    @Test
    fun testR3MultilineContinuationTagFieldStringValueMasked() {
        // R3 反例 2：多行 continuation 中非敏感键（如 tag）但其值为私密字符串时，必须掩码为 [MASKED]
        val continuationTagLine = "  \"tag\": \"$SENSITIVE_PRIVATE_MSG_MARKER\","
        val sanitized = LogSanitizer.sanitizeLogLine(continuationTagLine)

        assertFalse("多行 continuation 中 tag 字段的私密标记绝不得残留", sanitized.contains(SENSITIVE_PRIVATE_MSG_MARKER))
        assertTrue("tag 字符串值必须被掩码为 [MASKED]", sanitized.contains("\"tag\": [MASKED]"))
    }

    @Test
    fun testR3TimestampFormRemarkMasked() {
        // R3 反例 3：时间戳表单行中出现的 remark 等业务字段，默认安全关闭，必须掩码为 remark=[MASKED]
        val formRemarkLine = "2026-11-02 12:00:00.123 [OkHttp] --> form: remark=$SENSITIVE_PRIVATE_MSG_MARKER&safe_index=1"
        val sanitized = LogSanitizer.sanitizeLogLine(formRemarkLine)

        assertFalse("时间戳表单行中的 remark 标记绝不得残留", sanitized.contains(SENSITIVE_PRIVATE_MSG_MARKER))
        assertTrue("remark 字段必须被掩码为 remark=[MASKED]", sanitized.contains("remark=[MASKED]"))
        assertTrue("非敏感 safe_index 保留", sanitized.contains("safe_index=1"))
    }

    @Test
    fun testR3JsonSecretWithSpacesMasksEntireValueAndLeavesNoSuffix() {
        // R3 反例 4：带双引号且内部包含空格的 secret 值 {"secret":"prefix <合成标记>"}
        // 必须完整遮罩，不得留有后缀，并核对通用 sanitizeGeneralText 与 AppLog 通用输出链路
        val payloadWithSpaces = """{"secret":"prefix $SENSITIVE_CODE_MARKER"}"""
        val sanitizedGeneral = LogSanitizer.sanitizeGeneralText(payloadWithSpaces)

        assertFalse("含空格的 secret 值中的合成标记绝不得残留", sanitizedGeneral.contains(SENSITIVE_CODE_MARKER))
        assertFalse("含空格的 secret 值前缀与标记均不得留存", sanitizedGeneral.contains("prefix"))
        assertTrue("带引号含空格的键值对完整掩码为 \"[MASKED]\"", sanitizedGeneral.contains("\"secret\":\"[MASKED]\"") || sanitizedGeneral.contains("\"secret\": \"[MASKED]\""))

        // 同时验证 sanitizeLogLine 对整行 JSON 安全退化为元数据，标记同样绝不残留
        val sanitizedLine = LogSanitizer.sanitizeLogLine(payloadWithSpaces)
        assertFalse("sanitizeLogLine 绝不残留标记", sanitizedLine.contains(SENSITIVE_CODE_MARKER))
        assertFalse("sanitizeLogLine 绝不残留前缀", sanitizedLine.contains("prefix"))

        // 核对通用 AppLog 内存环输出
        AppLog.d("TEST_SPACE_JSON", payloadWithSpaces)
        val dumped = AppLog.dump()
        assertFalse("AppLog 内存环中不得残留含空格 secret 的标记", dumped.contains(SENSITIVE_CODE_MARKER))
        assertFalse("AppLog 内存环中不得残留含空格 secret 的前缀", dumped.contains("prefix"))
        assertTrue("AppLog 内存环中 secret 完整掩码", dumped.contains("\"secret\": \"[MASKED]\"") || dumped.contains("\"secret\":\"[MASKED]\""))
    }
}
