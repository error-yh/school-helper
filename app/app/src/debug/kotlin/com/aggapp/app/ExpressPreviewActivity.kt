package com.aggapp.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.aggapp.app.di.PlatformRegistry
import com.aggapp.app.di.SessionRepository
import com.aggapp.app.ui.ExpressScreen
import com.aggapp.app.ui.ExpressViewModel
import com.aggapp.app.ui.theme.AggTheme
import com.aggapp.core.model.*
import com.aggapp.core.storage.CredentialStore
import com.aggapp.platform.api.*

/** Debug-only synthetic UI fixture. Never installed in the release package or allowed to read SMS. */
class ExpressPreviewActivity : ComponentActivity() {
    private var preview: ExpressViewModel? = null
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!packageName.endsWith(".expresstest")) { finish(); return }
        val isolated = object : ContextWrapper(this) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                super.getSharedPreferences("express_ui_fixture_$name", mode)
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
                if (permission == android.Manifest.permission.READ_SMS) PackageManager.PERMISSION_DENIED
                else super.checkPermission(permission, pid, uid)
        }
        isolated.getSharedPreferences("express_tracked", Context.MODE_PRIVATE).edit().clear().commit()
        isolated.getSharedPreferences("sms_pickups", Context.MODE_PRIVATE).edit().clear().commit()
        val session = SessionRepository(CredentialStore(isolated))
        Platform.activePlatforms.forEach { session.clear(it) }
        session.upsert(Credential(Platform.SF, "synthetic-ui-only", capturedAt = 1L))
        val adapter = object : PlatformAdapter {
            override val platform = Platform.SF
            override fun loginUrl() = ""
            override fun detectLoginSuccess(req: CapturedRequest, cookies: Map<String, String>): Credential? = null
            override suspend fun checkAlive(cred: Credential) = true
            override suspend fun fetchExpressList(cred: Credential, page: Int): List<ExpressItem> = listOf(
                ExpressItem("UI-WAIT", platform, "合成测试驿站", trackingNo = "TEST-SF-001", pickupCode = "1-2-3456", summary = "合成测试包裹"),
                ExpressItem("UI-TRANSIT", platform, "合成物流中心", trackingNo = "TEST-SF-002", summary = "运输中测试包裹", status = ExpressStatus.IN_TRANSIT),
                ExpressItem("UI-PICKED", platform, "合成测试驿站", trackingNo = "TEST-SF-003", summary = "已签收测试包裹", status = ExpressStatus.PICKED))
            override suspend fun fetchDetail(cred: Credential, item: ExpressItem) = ExpressDetail(item.trackingNo.orEmpty(),
                statusDesc = "待取件", courier = "顺丰速运", goodsName = item.summary,
                tracks = listOf(TrackPoint("已到达合成驿站", "2026-11-17 15:00", "合成轨迹，仅用于界面验收"),
                    TrackPoint("运输中", "2026-11-17 12:00", "包裹正在运输")))
            override suspend fun fetchPickupCode(cred: Credential, expressId: String): PickupCode? = null
            override suspend fun fetchIdentityCode(cred: Credential) = IdentityCode(platform, CodeType.BARCODE, "TEST123456")
            override suspend fun sendSmsCode(phone: String) = error("Synthetic fixture has no login service")
            override suspend fun loginWithSmsCode(phone: String, smsCode: String): Credential = error("Synthetic fixture has no login service")
        }
        val vm = ExpressViewModel(PlatformRegistry(mapOf(Platform.SF to adapter)), session, isolated)
        preview = vm
        setContent { AggTheme { Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) { ExpressScreen(vm) } } }
    }
    override fun onDestroy() { preview?.onCleared(); super.onDestroy() }
}
