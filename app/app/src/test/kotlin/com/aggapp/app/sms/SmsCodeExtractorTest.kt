package com.aggapp.app.sms

import org.junit.Assert.*
import org.junit.Test

class SmsCodeExtractorTest {
    @Test fun extractsExplicitPickupCodeAndIgnoresVerificationCodes() {
        val sms = SmsCodeExtractor.extract("【菜鸟驿站】包裹已到测试驿站，取件码A12-3456，请及时领取", 100000000L)
        assertEquals("A12-3456", sms?.code)
        assertNull(SmsCodeExtractor.extract("【快递】验证码123456，切勿透露，取件码A-111", 1L))
    }
    @Test fun neverInterpretsPlainOrderNumbersAsPickupCodes() {
        assertNull(SmsCodeExtractor.extract("订单123456789已发货，联系电话12345678901", 1L))
    }
    @Test fun rejectedMessagesAreBounded() {
        assertNull(SmsCodeExtractor.extract("取件码1234" + "x".repeat(501), 1L))
    }
}
