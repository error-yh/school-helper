package com.aggapp.app.ui

import org.junit.Assert.*
import org.junit.Test

class TaobaoRiskRulesTest {
    @Test fun onlyOfficialHttpsPagesCanCompleteRiskCheck() {
        assertTrue(TaobaoRiskRules.isCompletionPage("https://www.taobao.com/"))
        assertFalse(TaobaoRiskRules.isCompletionPage("https://taobao.com.example.org/"))
        assertFalse(TaobaoRiskRules.isCompletionPage("https://iv.m.taobao.com/iv/verify"))
        assertFalse(TaobaoRiskRules.isCompletionPage("https://login.taobao.com/member/login.jhtml"))
        assertFalse(TaobaoRiskRules.isCompletionPage("http://www.taobao.com/"))
    }
    @Test fun unchangedCookiesNeedTrustedCompletedNavigationForSecondVerification() {
        val cookies = "cookie2=synthetic; unb=synthetic-user"
        assertFalse(TaobaoRiskRules.canFinish(cookies, cookies, false))
        assertTrue(TaobaoRiskRules.canFinish(cookies, cookies, true))
        assertTrue(TaobaoRiskRules.canFinish(cookies, "cookie2=changed; unb=synthetic-user", false))
        assertFalse(TaobaoRiskRules.canFinish(cookies, "cookie2=", true))
    }
}
