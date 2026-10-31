package com.aggapp.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aggapp.app.di.PlatformRegistry
import com.aggapp.app.di.SessionRepository
import com.aggapp.app.ui.MainViewModel
import com.aggapp.core.model.CodeType
import com.aggapp.core.model.Credential
import com.aggapp.core.model.ExpressItem
import com.aggapp.core.model.IdentityCode
import com.aggapp.core.model.PickupCode
import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import com.aggapp.core.storage.CredentialStore
import com.aggapp.platform.api.CapturedRequest
import com.aggapp.platform.api.LoginMode
import com.aggapp.platform.api.PlatformAdapter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 身份码会话状态、凭据版本保护与导航状态受控测试。
 * 使用隔离的测试 Preference 存储与 Fake 平台适配器，保护真实用户数据，不访问线上网络。
 */
@RunWith(AndroidJUnit4::class)
class IdCodeSessionNavigationTest {

    companion object {
        private var initialized = false
        private lateinit var isolatedStore: CredentialStore
        private lateinit var session: SessionRepository

        private fun ensureInitialized(baseContext: Context) {
            if (!initialized) {
                val testContext = object : ContextWrapper(baseContext) {
                    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                        return super.getSharedPreferences("test_isolated_s4_$name", mode)
                    }
                }
                isolatedStore = CredentialStore(testContext)
                session = SessionRepository(isolatedStore)
                initialized = true
            }
        }
    }

    private val fakeAdapters = mutableMapOf<Platform, FakePlatformAdapter>()
    private val createdVms = mutableListOf<MainViewModel>()

    private fun createViewModel(registry: PlatformRegistry = PlatformRegistry(fakeAdapters)): MainViewModel {
        val vm = MainViewModel(registry, session)
        createdVms += vm
        return vm
    }

    @Before
    fun setUp() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        ensureInitialized(baseContext)
        Platform.entries.forEach { p ->
            isolatedStore.clear(p.name)
            session.clear(p)
            fakeAdapters[p] = FakePlatformAdapter(p)
        }
    }

    @After
    fun tearDown() = runBlocking {
        createdVms.forEach { it.onCleared() }
        createdVms.clear()
        Platform.entries.forEach { p ->
            isolatedStore.clear(p.name)
            session.clear(p)
        }
        delay(150)
    }

    @Test(timeout = 10_000)
    fun unauthenticatedStateShowsNoCodesAndFalseRealFlag() = runBlocking {
        val vm = createViewModel()

        waitForStateCondition(vm) { !it.loading && it.isReal.isNotEmpty() }
        val state = vm.state.value
        assertTrue("未登录状态下无有效身份码", state.codes.isEmpty())
        fakeAdapters.keys.forEach { p ->
            assertEquals("未登录标记为 false", false, state.isReal[p])
        }
    }

    @Test(timeout = 10_000)
    fun loginEmitsCredentialsAndUpdatesIdCodePromptly() = runBlocking {
        val vm = createViewModel()
        waitForStateCondition(vm) { !it.loading }

        // 模拟登录顺丰平台
        val sfCred = Credential(Platform.SF, "sf_token_001", emptyMap(), System.currentTimeMillis())
        session.upsert(sfCred)

        // 验证凭据变更后即时触发拉码并更新
        waitForStateCondition(vm) { it.codes[Platform.SF]?.content == "SF-CODE-sf_token_001" }
        val state = vm.state.value
        assertEquals("SF-CODE-sf_token_001", state.codes[Platform.SF]?.content)
        assertEquals(true, state.isReal[Platform.SF])
    }

    @Test(timeout = 10_000)
    fun logoutImmediatelyClearsIdCodeAndResetsRealFlag() = runBlocking {
        val vm = createViewModel()
        waitForStateCondition(vm) { !it.loading }

        // 先登录
        val sfCred = Credential(Platform.SF, "sf_token_002", emptyMap(), System.currentTimeMillis())
        session.upsert(sfCred)
        waitForStateCondition(vm) { it.codes[Platform.SF] != null }

        // 登出顺丰
        session.clear(Platform.SF)

        // 验证登出后立即清除，不残留旧码
        waitForStateCondition(vm) { it.codes[Platform.SF] == null }
        val state = vm.state.value
        assertNull("已登出平台的身份码必须清除", state.codes[Platform.SF])
        assertEquals("已登出平台的 isReal 必须为 false", false, state.isReal[Platform.SF])
    }

    @Test(timeout = 10_000)
    fun staleNetworkResponseAfterLogoutDoesNotRefillIdentityCode() = runBlocking {
        // 设置 300ms 网络延迟
        fakeAdapters[Platform.JD]?.fetchDelayMs = 300L
        val vm = createViewModel()
        waitForStateCondition(vm) { !it.loading }

        // 发起登录（触发后台拉码）
        val jdCred = Credential(Platform.JD, "jd_token_stale", emptyMap(), System.currentTimeMillis())
        session.upsert(jdCred)

        // 100ms 后用户登出（此时拉码仍在进行中）
        delay(100)
        session.clear(Platform.JD)

        // 再等待 400ms 直至延迟网络请求返回
        delay(400)

        val state = vm.state.value
        assertNull("网络请求晚返回绝不得回填已登出账号的旧码", state.codes[Platform.JD])
        assertEquals(false, state.isReal[Platform.JD])
    }

    @Test(timeout = 10_000)
    fun switchingAccountOverwritesOldCodeWithNewCode() = runBlocking {
        val vm = createViewModel()
        waitForStateCondition(vm) { !it.loading }

        val cred1 = Credential(Platform.PDD, "user_account_A", emptyMap(), System.currentTimeMillis())
        session.upsert(cred1)
        waitForStateCondition(vm) { it.codes[Platform.PDD]?.content == "PDD-CODE-user_account_A" }

        // 登出账号 A 并切换到新账号 B
        session.clear(Platform.PDD)
        waitForStateCondition(vm) { it.codes[Platform.PDD] == null }

        val cred2 = Credential(Platform.PDD, "user_account_B", emptyMap(), System.currentTimeMillis() + 1000)
        session.upsert(cred2)
        waitForStateCondition(vm) { it.codes[Platform.PDD]?.content == "PDD-CODE-user_account_B" }

        val state = vm.state.value
        assertEquals("PDD-CODE-user_account_B", state.codes[Platform.PDD]?.content)
    }

    @Test(timeout = 10_000)
    fun thirtySecondThrottlePreservedForSameSessionButBypassedOnForceRefresh() = runBlocking {
        val vm = createViewModel()
        val adapter = checkNotNull(fakeAdapters[Platform.TAOBAO])

        val cred = Credential(Platform.TAOBAO, "tb_throttle_test", emptyMap(), System.currentTimeMillis())
        session.upsert(cred)

        // 初始强制刷新
        vm.refreshAll(force = true)
        delay(300)
        val initialCount = adapter.fetchCount
        assertTrue("初始强制刷新应至少触发1次拉取", initialCount >= 1)

        // 30s 内普通自动刷新（force=false），被节流跳过，拉取次数绝不增加
        vm.refreshAll(force = false)
        delay(150)
        assertEquals("同一会话 30 秒内被节流跳过，拉取次数不增加", initialCount, adapter.fetchCount)

        // 强制刷新（force=true，如用户下拉或点击刷新按钮），绕过节流
        vm.refreshAll(force = true)
        delay(300)
        assertTrue("force=true 绕过节流立即拉取", adapter.fetchCount > initialCount)
    }

    @Test(timeout = 10_000)
    fun platformSelectUpdatesSelectedPlatform() {
        val vm = createViewModel()

        vm.select(Platform.JD)
        assertEquals(Platform.JD, vm.state.value.selected)

        vm.select(Platform.TAOBAO)
        assertEquals(Platform.TAOBAO, vm.state.value.selected)
    }

    @Test(timeout = 10_000)
    fun needReloginErrorClearsCodeAndMarksRealFalse() = runBlocking {
        val vm = createViewModel()
        waitForStateCondition(vm) { !it.loading }

        // 1. 先正常登录并拉到有效码
        val cred = Credential(Platform.RYLL, "ryll_valid", emptyMap(), System.currentTimeMillis())
        session.upsert(cred)
        waitForStateCondition(vm) { !it.loading && it.codes[Platform.RYLL] != null && it.isReal[Platform.RYLL] == true }

        // 2. 模拟凭据过期，后续拉取抛出 NeedRelogin
        delay(200)
        fakeAdapters[Platform.RYLL]?.shouldFailWithRelogin = true
        vm.refreshAll(force = true)

        // 3. 验证 NeedRelogin 后码被清空且 isReal 变为 false
        waitForStateCondition(vm) { !it.loading && it.codes[Platform.RYLL] == null && it.isReal[Platform.RYLL] == false }
        val state = vm.state.value
        assertNull("凭据失效（NeedRelogin）时不展示码", state.codes[Platform.RYLL])
        assertEquals("凭据失效标记 isReal 为 false", false, state.isReal[Platform.RYLL])
    }

    /**
     * 确定性批次会话隔离测试 1：
     * 两个平台按 A(PDD) -> B(JD) 顺序执行；
     * A 请求完成已返回并进入批次缓存，B 正被门闩挂起等待；
     * 此时用户登出 A；
     * 释放 B 的门闩使批次结束；
     * 验证批次完成后 A 的旧码绝不得回填，A 的 isReal 必须为 false，B 正常写入。
     */
    @Test(timeout = 10_000)
    fun batchRefreshLogoutWhileSubsequentPlatformPendingDoesNotRefillOldCode() = runBlocking {
        val aReturnedLatch = CompletableDeferred<Unit>()
        val bReleaseLatch = CompletableDeferred<Unit>()

        val adapterA = FakePlatformAdapter(
            platform = Platform.PDD,
            onFetchedCallback = { cred ->
                if (cred.token == "token_pdd_old") {
                    aReturnedLatch.complete(Unit)
                }
            }
        )
        val adapterB = FakePlatformAdapter(
            platform = Platform.JD,
            beforeFetchLatch = bReleaseLatch,
        )

        val registry = PlatformRegistry(
            linkedMapOf(
                Platform.PDD to adapterA,
                Platform.JD to adapterB,
            )
        )

        // 初始两平台均已登录
        val credA = Credential(Platform.PDD, "token_pdd_old", emptyMap(), System.currentTimeMillis())
        val credB = Credential(Platform.JD, "token_jd_valid", emptyMap(), System.currentTimeMillis())
        session.upsert(credA)
        session.upsert(credB)

        // 创建 ViewModel 并发起全量刷新
        val vm = createViewModel(registry)
        vm.refreshAll(force = true)

        // 确定性门闩：等待 A 已完成 fetchIdentityCode 返回旧码，此时循环正挂起在 B 的 beforeFetchLatch
        aReturnedLatch.await()

        // 此时用户退出 A 平台
        session.clear(Platform.PDD)

        // 释放 B 门闩，让整个批次执行完毕
        bReleaseLatch.complete(Unit)

        // 等待批次写入完成
        waitForStateCondition(vm) { !it.loading && it.codes[Platform.JD] != null }

        val state = vm.state.value
        assertNull("已登出的 A 平台旧码绝不得在批次完成时回填", state.codes[Platform.PDD])
        assertEquals("已登出平台 A 的 isReal 必须为 false", false, state.isReal[Platform.PDD])
        assertEquals("未受影响的平台 B 码必须正常写入", "JD-CODE-token_jd_valid", state.codes[Platform.JD]?.content)
        assertEquals("平台 B 的 isReal 必须为 true", true, state.isReal[Platform.JD])
    }

    /**
     * 确定性批次会话隔离测试 2：
     * 两个平台按 A(PDD) -> B(JD) 顺序执行；
     * A 请求完成已返回旧码进入批次缓存，B 正被门闩挂起等待；
     * 此时用户将 A 平台切换为新账号并成功取得新码；
     * 释放 B 的门闩使批次结束；
     * 验证批次完成后 A 的新码绝不得被旧批次中的旧码覆盖。
     */
    @Test(timeout = 10_000)
    fun batchRefreshSwitchAccountWhileSubsequentPlatformPendingDoesNotOverwriteNewCode() = runBlocking {
        val aReturnedLatch = CompletableDeferred<Unit>()
        val bReleaseLatch = CompletableDeferred<Unit>()

        val adapterA = FakePlatformAdapter(
            platform = Platform.PDD,
            onFetchedCallback = { cred ->
                if (cred.token == "token_pdd_old") {
                    aReturnedLatch.complete(Unit)
                }
            }
        )
        val adapterB = FakePlatformAdapter(
            platform = Platform.JD,
            beforeFetchLatch = bReleaseLatch,
        )

        val registry = PlatformRegistry(
            linkedMapOf(
                Platform.PDD to adapterA,
                Platform.JD to adapterB,
            )
        )

        // 初始 A(旧凭据) 与 B 均登录
        val credAOld = Credential(Platform.PDD, "token_pdd_old", emptyMap(), System.currentTimeMillis())
        val credB = Credential(Platform.JD, "token_jd_valid", emptyMap(), System.currentTimeMillis())
        session.upsert(credAOld)
        session.upsert(credB)

        // 创建 ViewModel 并发起全量刷新（旧批次）
        val vm = createViewModel(registry)
        vm.refreshAll(force = true)

        // 确定性门闩：等待旧批次中 A 已完成返回旧码，B 正在挂起
        aReturnedLatch.await()

        // 此时切换 A 账号为新凭据
        val credANew = Credential(Platform.PDD, "token_pdd_new", emptyMap(), System.currentTimeMillis() + 1000)
        session.upsert(credANew)

        // 确保新账号 A 的新码已通过 refreshOne 成功上屏
        waitForStateCondition(vm) { it.codes[Platform.PDD]?.content == "PDD-CODE-token_pdd_new" }

        // 释放 B 的门闩，让旧批次完成最终写入
        bReleaseLatch.complete(Unit)

        // 等待旧批次写入完成
        waitForStateCondition(vm) { !it.loading && it.codes[Platform.JD] != null }

        val state = vm.state.value
        assertEquals("旧批次完成绝不得将 A 的新码覆盖回旧码", "PDD-CODE-token_pdd_new", state.codes[Platform.PDD]?.content)
        assertEquals("新账号 A 的 isReal 必须保持为 true", true, state.isReal[Platform.PDD])
        assertEquals("平台 B 码正常上屏", "JD-CODE-token_jd_valid", state.codes[Platform.JD]?.content)
        assertEquals("平台 B 的 isReal 必须为 true", true, state.isReal[Platform.JD])
    }

    private suspend fun waitForStateCondition(
        vm: MainViewModel,
        timeoutMs: Long = 5000L,
        condition: (com.aggapp.app.ui.IdCodeUiState) -> Boolean,
    ) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition(vm.state.value)) return
            delay(50)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition(vm.state.value))
    }

    private class FakePlatformAdapter(
        override val platform: Platform,
        var fetchDelayMs: Long = 0L,
        var shouldFailWithRelogin: Boolean = false,
        var fetchCount: Int = 0,
        var beforeFetchLatch: CompletableDeferred<Unit>? = null,
        var onFetchedCallback: ((Credential) -> Unit)? = null,
    ) : PlatformAdapter {
        override val loginMode = LoginMode.REMOTE_SERVICE
        override fun loginUrl(): String = ""
        override fun detectLoginSuccess(req: CapturedRequest, cookies: Map<String, String>): Credential? = null
        override suspend fun checkAlive(cred: Credential): Boolean = true
        override suspend fun fetchExpressList(cred: Credential, page: Int): List<ExpressItem> = emptyList()
        override suspend fun fetchPickupCode(cred: Credential, expressId: String): PickupCode? = null
        override suspend fun sendSmsCode(phone: String) {}
        override suspend fun loginWithSmsCode(phone: String, smsCode: String): Credential =
            Credential(platform, "sms_$smsCode", emptyMap(), System.currentTimeMillis())

        override suspend fun fetchIdentityCode(cred: Credential): IdentityCode {
            fetchCount++
            beforeFetchLatch?.await()
            if (fetchDelayMs > 0) delay(fetchDelayMs)
            if (shouldFailWithRelogin) throw PlatformError.NeedRelogin(platform)
            val code = IdentityCode(platform, CodeType.BARCODE, "${platform.name}-CODE-${cred.token}")
            onFetchedCallback?.invoke(cred)
            return code
        }
    }
}
