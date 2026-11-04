package com.aggapp.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aggapp.app.di.PlatformRegistry
import com.aggapp.app.di.SessionRepository
import com.aggapp.app.ui.ExpressViewModel
import com.aggapp.core.model.CodeType
import com.aggapp.core.model.Credential
import com.aggapp.core.model.ExpressItem
import com.aggapp.core.model.ExpressStatus
import com.aggapp.core.model.IdentityCode
import com.aggapp.core.model.PickupCode
import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import com.aggapp.core.storage.CredentialStore
import com.aggapp.platform.api.CapturedRequest
import com.aggapp.platform.api.LoginMode
import com.aggapp.platform.api.PlatformAdapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
 * 快递会话保护与确定性门闩隔离测试（S6-02-R2 增量加固）：
 * 包含 14 个确定性 @Test 方法：
 * 1. testLogoutWithoutNewRequestExitsLoadingImmediately：登出且不启动新请求，loading 立即退出；
 * 2. testLogoutWhileRefreshPendingDoesNotRefillOldItems：旧请求挂起时登出，旧结果不回填已登出账号；
 * 3. testSwitchAccountWhileRefreshPendingDoesNotOverwriteNewAccountData：换号迟到保护，A 迟到请求绝不覆盖 B 账号数据；
 * 4. testConcurrentSearchDuringRefreshPreservesIndependentGenerations：代次解耦，列表刷新与单号查询代次独立；
 * 5. testConsecutiveSearchDiscardsLateOldResult：连续查单代次保护，旧查单迟到不覆盖最新查单结果；
 * 6. testCancellationExceptionPropagatedAndExitsCleanly：CancellationException 正确重抛与安全退出；
 * 7. testCrossPlatformBorrowedPickupCodeClearedWhenSourcePlatformLogsOut：来源注销时派生码清除；
 * 8. testLateRefreshDoesNotPollutePreferences：迟到请求不污染 SharedPreferences；
 * 9. testInactivePlatformTrackedItemsPreservedAcrossRefresh：非活动平台（RYLL）与未知单号跨刷新完整保留；
 * 10. testThirtySecondThrottlePreservedForSameSessionButBypassedOnForceRefresh：30 秒节流与 force 绕过；
 * 11. testSwitchAccountDoesNotQueryOldProvenTrackedItem：A 换号至 B 后，顺丰刷新绝不查询 A 账号旧单号；
 * 12. testDifferentPlatformSameWaybillDoesNotShareProof：不同平台同单号绝不共用归属证明；
 * 13. testLateRefreshCannotRegisterExpiredProof：迟到响应绝不能登记过期证明；
 * 14. testConcurrentSearchDuringRefreshPreservesTrackedItemAcrossCompletion：刷新持有旧快照期间并发搜索成功新增 Y，刷新结束内存与 prefs 均完好保留 Y。
 */
@RunWith(AndroidJUnit4::class)
class ExpressSessionIsolationTest {

    companion object {
        private var initialized = false
        private lateinit var isolatedStore: CredentialStore
        private lateinit var session: SessionRepository
        private lateinit var testContext: Context

        private fun ensureInitialized(baseContext: Context) {
            if (!initialized) {
                testContext = object : ContextWrapper(baseContext) {
                    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                        return super.getSharedPreferences("test_isolated_s6_express_$name", mode)
                    }
                }
                isolatedStore = CredentialStore(testContext)
                session = SessionRepository(isolatedStore)
                initialized = true
            }
        }
    }

    private val fakeAdapters = mutableMapOf<Platform, FakeExpressAdapter>()
    private val createdVms = mutableListOf<ExpressViewModel>()

    private fun createViewModel(registry: PlatformRegistry = PlatformRegistry(fakeAdapters)): ExpressViewModel {
        val vm = ExpressViewModel(registry, session, testContext)
        createdVms += vm
        return vm
    }

    private fun clearPreferences() {
        // 修复测试 Prefs 双前缀清理 Bug：直接向 testContext 传递原始名字
        testContext.getSharedPreferences("express_tracked", Context.MODE_PRIVATE)
            .edit().clear().apply()
        testContext.getSharedPreferences("express_pickup_channels", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    private fun getTrackedInMemory(vm: ExpressViewModel): Map<String, Platform> {
        val field = ExpressViewModel::class.java.getDeclaredField("_tracked")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(vm) as kotlinx.coroutines.flow.StateFlow<Map<String, Platform>>
        return flow.value
    }

    @Before
    fun setUp() {
        val baseContext = InstrumentationRegistry.getInstrumentation().targetContext
        ensureInitialized(baseContext)
        Platform.entries.forEach { p ->
            isolatedStore.clear(p.name)
            session.clear(p)
            fakeAdapters[p] = FakeExpressAdapter(p)
        }
        clearPreferences()
    }

    @After
    fun tearDown() = runBlocking {
        createdVms.forEach { it.onCleared() }
        createdVms.clear()
        Platform.entries.forEach { p ->
            isolatedStore.clear(p.name)
            session.clear(p)
        }
        clearPreferences()
        delay(150)
    }

    /**
     * 门闩反例 1：登出且不启动新请求（R1-02 审查阻断项，S6-02-T1 夹具时序修正）。
     * 验证当旧请求在后台挂起或被取消时，用户登出平台且不发起任何新请求，
     * loading 必须在旧网络门闩仍关闭的情况下有界退出为 false，不产生永久死锁且不依赖网络返回。
     */
    @Test(timeout = 10_000)
    fun testLogoutWithoutNewRequestExitsLoadingImmediately() = runBlocking {
        val fetchStartedLatch = CompletableDeferred<Unit>()
        val releaseFetchLatch = CompletableDeferred<Unit>()

        val adapter = checkNotNull(fakeAdapters[Platform.SF])
        adapter.beforeFetchLatch = releaseFetchLatch
        adapter.onFetchStarted = { fetchStartedLatch.complete(Unit) }

        val cred = Credential(Platform.SF, "token_sf", emptyMap(), System.currentTimeMillis())
        session.upsert(cred)

        val vm = createViewModel()
        vm.refresh(force = true)

        fetchStartedLatch.await()
        assertTrue("刷新进行中应处于 loading", vm.ui.value.loading)

        // 用户登出顺丰，且不启动任何新请求
        session.clear(Platform.SF)

        // 登出后通过 onCredentialsUpdated 触发取消/复位；
        // 关键时序：此时 releaseFetchLatch 门闩仍然关闭（旧网络请求仍被挂起），
        // 有界等待 loading 退出为 false，充分证明 loading 退出绝不依赖网络结束，不可留死锁
        waitForStateCondition(vm) { !it.loading }
        assertFalse("旧网络门闩未释放时，登出已使 loading 退出为 false", vm.ui.value.loading)

        // 释放门闩让后台旧请求自然完成/取消
        releaseFetchLatch.complete(Unit)
        waitForStateCondition(vm) { !it.loading && it.stations.isEmpty() }

        assertFalse("旧请求返回后 loading 仍为 false", vm.ui.value.loading)
        assertTrue("列表无残留", vm.ui.value.stations.isEmpty())
    }

    /**
     * 门闩反例 2：旧请求挂起待返回时用户登出（S6-02-T1 夹具时序修正）。
     * 验证旧请求在门闩挂起时登出立即退出 loading；旧请求随后返回带有包裹时，旧账号包裹绝不回填，UI 保持清空并提示登录。
     */
    @Test(timeout = 10_000)
    fun testLogoutWhileRefreshPendingDoesNotRefillOldItems() = runBlocking {
        val fetchStartedLatch = CompletableDeferred<Unit>()
        val releaseFetchLatch = CompletableDeferred<Unit>()

        val adapter = checkNotNull(fakeAdapters[Platform.SF])
        adapter.beforeFetchLatch = releaseFetchLatch
        adapter.onFetchStarted = { fetchStartedLatch.complete(Unit) }
        adapter.expressItemsToReturn = listOf(
            ExpressItem(
                id = "SF10001",
                platform = Platform.SF,
                station = "顺丰速运点",
                stationAddress = "测试路1号",
                trackingNo = "SF10001",
                pickupCode = "1-1-1001",
                summary = "文件",
                status = ExpressStatus.WAIT_PICKUP,
                arrivedAt = System.currentTimeMillis(),
                fetchDeadline = null,
                raw = null,
            )
        )

        val cred = Credential(Platform.SF, "token_user_old", emptyMap(), System.currentTimeMillis())
        session.upsert(cred)

        val vm = createViewModel()
        vm.refresh(force = true)

        fetchStartedLatch.await()
        assertTrue("请求进行中应处于 loading", vm.ui.value.loading)

        // 用户在返回前登出顺丰
        session.clear(Platform.SF)

        // 关键时序：此时 releaseFetchLatch 门闩未释放，有界等待 loading 退出
        waitForStateCondition(vm) { !it.loading }
        assertFalse("旧网络门闩未释放时，登出已使 loading 退出为 false", vm.ui.value.loading)

        // 释放门闩让旧请求返回
        releaseFetchLatch.complete(Unit)
        waitForStateCondition(vm) { !it.loading && it.stations.isEmpty() && it.error?.contains("请先在「我的」页登录") == true }

        val state = vm.ui.value
        assertFalse("请求结束后 loading 必须保持退出", state.loading)
        assertTrue("已登出平台的旧包裹绝不得回填展示", state.stations.isEmpty())
        assertTrue("未登录任何支持平台时提示登录", state.error?.contains("请先在「我的」页登录") == true)
    }

    /**
     * 门闩反例 3：A 账号发起刷新挂起中，用户切换至 B 账号。
     * 验证 A 账号迟到返回的包裹绝不覆盖 B 账号的新数据。
     */
    @Test(timeout = 10_000)
    fun testSwitchAccountWhileRefreshPendingDoesNotOverwriteNewAccountData() = runBlocking {
        val aStartedLatch = CompletableDeferred<Unit>()
        val aReleaseLatch = CompletableDeferred<Unit>()

        val adapter = checkNotNull(fakeAdapters[Platform.SF])
        adapter.customFetchHandler = { cred ->
            if (cred.token == "token_A") {
                aStartedLatch.complete(Unit)
                aReleaseLatch.await()
                listOf(
                    ExpressItem(
                        id = "SF_OLD_A",
                        platform = Platform.SF,
                        station = "顺丰速运点A",
                        stationAddress = null,
                        trackingNo = "SF_OLD_A",
                        pickupCode = "A-101",
                        summary = "A的包裹",
                        status = ExpressStatus.WAIT_PICKUP,
                        arrivedAt = System.currentTimeMillis(),
                        fetchDeadline = null,
                        raw = null,
                    )
                )
            } else {
                listOf(
                    ExpressItem(
                        id = "SF_NEW_B",
                        platform = Platform.SF,
                        station = "顺丰速运点B",
                        stationAddress = null,
                        trackingNo = "SF_NEW_B",
                        pickupCode = "B-202",
                        summary = "B的新包裹",
                        status = ExpressStatus.WAIT_PICKUP,
                        arrivedAt = System.currentTimeMillis(),
                        fetchDeadline = null,
                        raw = null,
                    )
                )
            }
        }

        val credA = Credential(Platform.SF, "token_A", emptyMap(), System.currentTimeMillis())
        session.upsert(credA)

        val vm = createViewModel()
        vm.refresh(force = true)

        aStartedLatch.await()

        val credB = Credential(Platform.SF, "token_B", emptyMap(), System.currentTimeMillis() + 1000)
        session.upsert(credB)
        vm.refresh(force = true)

        waitForStateCondition(vm) { state ->
            state.stations.any { g -> g.items.any { it.trackingNo == "SF_NEW_B" } }
        }

        aReleaseLatch.complete(Unit)
        delay(150)

        val state = vm.ui.value
        assertFalse("已完成时不处于 loading", state.loading)
        val allTrackings = state.stations.flatMap { it.items }.map { it.trackingNo }
        assertTrue("B 账号包裹正常存在", allTrackings.contains("SF_NEW_B"))
        assertFalse("旧账号 A 的迟到包裹绝不得覆盖或回填入列表", allTrackings.contains("SF_OLD_A"))
    }

    /**
     * 门闩反例 4：列表刷新中并发查单（R1-02 审查阻断项：代次解耦）。
     * 验证 refreshGeneration 与 searchGeneration 独立，互不覆盖对方状态。
     */
    @Test(timeout = 10_000)
    fun testConcurrentSearchDuringRefreshPreservesIndependentGenerations() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshRelease = CompletableDeferred<Unit>()
        val searchStarted = CompletableDeferred<Unit>()
        val searchRelease = CompletableDeferred<Unit>()

        val sfAdapter = checkNotNull(fakeAdapters[Platform.SF])
        sfAdapter.onFetchStarted = { refreshStarted.complete(Unit) }
        sfAdapter.beforeFetchLatch = refreshRelease
        sfAdapter.expressItemsToReturn = listOf(
            ExpressItem(
                id = "SF_ITEM_1",
                platform = Platform.SF,
                station = "顺丰点",
                stationAddress = null,
                trackingNo = "SF_ITEM_1",
                pickupCode = "SF-CODE",
                summary = "文件",
                status = ExpressStatus.WAIT_PICKUP,
                arrivedAt = System.currentTimeMillis(),
                fetchDeadline = null,
                raw = null,
            )
        )

        val tbAdapter = checkNotNull(fakeAdapters[Platform.TAOBAO])
        tbAdapter.onPickupStarted = { searchStarted.complete(Unit) }
        tbAdapter.beforePickupLatch = searchRelease
        tbAdapter.pickupCodeToReturn = PickupCode(
            expressId = "YT1234567890",
            platform = Platform.TAOBAO,
            code = "TB-SEARCH-99",
        )

        session.upsert(Credential(Platform.SF, "sf_cred", emptyMap(), System.currentTimeMillis()))
        session.upsert(Credential(Platform.TAOBAO, "tb_cred", emptyMap(), System.currentTimeMillis()))

        val vm = createViewModel()
        // 1. 发起列表刷新
        vm.refresh(force = true)
        refreshStarted.await()
        assertTrue("刷新中 loading 应当为 true", vm.ui.value.loading)
        assertFalse("查单尚未发起 searching 应为 false", vm.ui.value.searching)

        // 2. 刷新进行中发起查单
        vm.lookupByTrackingNo("YT1234567890")
        searchStarted.await()
        assertTrue("刷新 loading 仍保持 true", vm.ui.value.loading)
        assertTrue("查单 searching 应为 true", vm.ui.value.searching)

        // 3. 查单先返回
        searchRelease.complete(Unit)
        waitForStateCondition(vm) { !it.searching }
        assertFalse("查单完成 searching 为 false", vm.ui.value.searching)
        assertNotNull("查单结果正常获取", vm.ui.value.searchResult)
        assertEquals("TB-SEARCH-99", vm.ui.value.searchResult?.code)
        assertTrue("查单完成不应误将刷新 loading 关闭", vm.ui.value.loading)

        // 4. 刷新随后返回
        refreshRelease.complete(Unit)
        waitForStateCondition(vm) { !it.loading }
        assertFalse("刷新完成 loading 退出", vm.ui.value.loading)
        val allTrackings = vm.ui.value.stations.flatMap { it.items }.map { it.trackingNo }
        assertTrue("刷新结果正常呈现", allTrackings.contains("SF_ITEM_1"))
        assertNotNull("查单结果未被冲刷", vm.ui.value.searchResult)
    }

    /**
     * 门闩反例 5：连续查单旧请求迟到（R1-02 审查阻断项：查单代次保护）。
     * 查询 1 挂起，查询 2 立即发起并先完成，查询 1 释放后被安全丢弃。
     */
    @Test(timeout = 10_000)
    fun testConsecutiveSearchDiscardsLateOldResult() = runBlocking {
        val search1Started = CompletableDeferred<Unit>()
        val search1Release = CompletableDeferred<Unit>()

        var callCount = 0
        val tbAdapter = checkNotNull(fakeAdapters[Platform.TAOBAO])
        tbAdapter.customPickupHandler = { _, no ->
            callCount++
            if (callCount == 1) {
                search1Started.complete(Unit)
                search1Release.await()
                PickupCode(
                    expressId = no,
                    platform = Platform.TAOBAO,
                    code = "OLD-SEARCH-1",
                )
            } else {
                PickupCode(
                    expressId = no,
                    platform = Platform.TAOBAO,
                    code = "NEW-SEARCH-2",
                )
            }
        }

        session.upsert(Credential(Platform.TAOBAO, "tb_cred", emptyMap(), System.currentTimeMillis()))
        val vm = createViewModel()

        // 发起查询 1
        vm.lookupByTrackingNo("ZT1111111111")
        search1Started.await()
        assertTrue("查询 1 进行中", vm.ui.value.searching)

        // 立即发起查询 2
        vm.lookupByTrackingNo("YT2222222222")
        waitForStateCondition(vm) { it.searchResult?.code == "NEW-SEARCH-2" }
        assertFalse("查询 2 完成", vm.ui.value.searching)

        // 释放查询 1
        search1Release.complete(Unit)
        delay(150)

        // 验证最新查询结果未被覆盖
        assertEquals("NEW-SEARCH-2", vm.ui.value.searchResult?.code)
        assertFalse("searching 保持 false", vm.ui.value.searching)
    }

    /**
     * 门闩反例 6：协程异常/取消安全保障（R1-02 审查阻断项：CancellationException 传播与退出）。
     */
    @Test(timeout = 10_000)
    fun testCancellationExceptionPropagatedAndExitsCleanly() = runBlocking {
        val adapter = checkNotNull(fakeAdapters[Platform.SF])
        val cancelStarted = CompletableDeferred<Unit>()
        adapter.customFetchHandler = {
            cancelStarted.complete(Unit)
            throw CancellationException("显式测试协程取消")
        }

        val sfCred = Credential(Platform.SF, "sf_cancel_test", emptyMap(), System.currentTimeMillis())
        session.upsert(sfCred)

        val vm = createViewModel()
        vm.refresh(force = true)

        cancelStarted.await()
        waitForStateCondition(vm) { !it.loading }

        assertFalse("协程取消时 loading 必须安全退出", vm.ui.value.loading)
        assertNull("CancellationException 不得被当作业务错误发布在 UI", vm.ui.value.error)
    }

    /**
     * 门闩反例 7：派生取件码来源保护（R1-03 审查阻断项，S6-02-T1 定位修正）。
     * 顺丰包裹自身无取件码，通过跨平台补码借用了淘宝查到的取件码（pickupCodeSource == TAOBAO）；
     * 按 platform=SF 与原始 id 定位顺丰包裹，验证借用前提与来源标记；
     * 当来源平台淘宝注销时，顺丰包裹依然存在，但其派生取件码及来源标记必须被复位为 null。
     */
    @Test(timeout = 10_000)
    fun testCrossPlatformBorrowedPickupCodeClearedWhenSourcePlatformLogsOut() = runBlocking {
        val sfItem = ExpressItem(
            id = "SF_BORROW_TEST",
            platform = Platform.SF,
            station = "丰巢快递柜",
            stationAddress = "东门",
            trackingNo = "SF_SHARED_999",
            pickupCode = null, // 顺丰自身无取件码
            summary = "书籍",
            status = ExpressStatus.WAIT_PICKUP,
            arrivedAt = System.currentTimeMillis(),
            fetchDeadline = null,
            raw = null,
        )

        val tbAdapter = checkNotNull(fakeAdapters[Platform.TAOBAO])
        tbAdapter.expressItemsToReturn = emptyList() // 淘宝列表无此单，需通过跨平台网络查码借用
        tbAdapter.pickupCodeToReturn = PickupCode(
            expressId = "SF_SHARED_999",
            platform = Platform.TAOBAO,
            code = "TB-BORROW-CODE",
        )

        fakeAdapters[Platform.SF]?.expressItemsToReturn = listOf(sfItem)

        session.upsert(Credential(Platform.SF, "sf_token", emptyMap(), System.currentTimeMillis()))
        session.upsert(Credential(Platform.TAOBAO, "tb_token", emptyMap(), System.currentTimeMillis()))

        val vm = createViewModel()
        vm.refresh(force = true)

        // 有界等待顺丰包裹完成借码：顺丰项借到了淘宝的码
        waitForStateCondition(vm) { state ->
            state.stations.any { g ->
                g.items.any { it.platform == Platform.SF && it.id == "SF_BORROW_TEST" && it.pickupCode == "TB-BORROW-CODE" }
            }
        }

        // 准确定位顺丰派生包裹（按 platform=SF 与原始 id）
        val sfItemBefore = vm.ui.value.stations.flatMap { it.items }.first { it.platform == Platform.SF && it.id == "SF_BORROW_TEST" }
        assertEquals("TB-BORROW-CODE", sfItemBefore.pickupCode)
        assertEquals(Platform.TAOBAO, sfItemBefore.pickupCodeSource)

        // 用户注销了码源平台淘宝
        session.clear(Platform.TAOBAO)

        // 有界等待淘宝注销处理完成：顺丰包裹保留但借用码复位为 null
        waitForStateCondition(vm) { state ->
            val item = state.stations.flatMap { it.items }.firstOrNull { it.platform == Platform.SF && it.id == "SF_BORROW_TEST" }
            item != null && item.pickupCode == null
        }

        // 验证顺丰包裹保留，但借用的取件码与来源平台标记均被清除
        val sfItemAfter = vm.ui.value.stations.flatMap { it.items }.firstOrNull { it.platform == Platform.SF && it.id == "SF_BORROW_TEST" }
        assertNotNull("顺丰平台仍然在线，顺丰包裹保留", sfItemAfter)
        assertNull("来源平台淘宝注销后，派生取件码必须清除为 null", sfItemAfter?.pickupCode)
        assertNull("派生来源平台标记必须清除为 null", sfItemAfter?.pickupCodeSource)
    }

    /**
     * 门闩反例 8：迟到补码/追踪写入防污染（R1-03 审查阻断项：过期副作用校验）。
     * 刷新挂起中发生换号，旧代次迟到返回不得将数据写入 SharedPreferences。
     */
    @Test(timeout = 10_000)
    fun testLateRefreshDoesNotPollutePreferences() = runBlocking {
        val sfStarted = CompletableDeferred<Unit>()
        val sfRelease = CompletableDeferred<Unit>()

        val adapter = checkNotNull(fakeAdapters[Platform.SF])
        adapter.customFetchHandler = { cred ->
            if (cred.token == "token_old") {
                sfStarted.complete(Unit)
                sfRelease.await()
                listOf(
                    ExpressItem(
                        id = "SF_OLD_LATE",
                        platform = Platform.SF,
                        station = "驿站",
                        stationAddress = null,
                        trackingNo = "SF_OLD_LATE_NO",
                        pickupCode = "OLD-CODE",
                        summary = "旧单",
                        status = ExpressStatus.WAIT_PICKUP,
                        arrivedAt = System.currentTimeMillis(),
                        fetchDeadline = null,
                        raw = null,
                    )
                )
            } else {
                emptyList()
            }
        }

        session.upsert(Credential(Platform.SF, "token_old", emptyMap(), System.currentTimeMillis()))
        val vm = createViewModel()
        vm.refresh(force = true)

        sfStarted.await()

        // 用户切换为新 token
        session.upsert(Credential(Platform.SF, "token_new", emptyMap(), System.currentTimeMillis() + 1000))
        vm.refresh(force = true)

        // 释放旧请求让其迟到返回
        sfRelease.complete(Unit)
        delay(150)

        val prefs = testContext.getSharedPreferences("express_pickup_channels", Context.MODE_PRIVATE)
        val channels = prefs.getStringSet("pickup_channels", emptySet()).orEmpty()
        assertFalse("旧请求迟到返回绝不得将渠道信息写入 SharedPreferences", channels.any { it.contains("SF_OLD_LATE_NO") })
    }

    /**
     * 门闩反例 9：旧非活动平台（如旧 RYLL）追踪单与未知归属单保留（R1-03 审查阻断项）。
     */
    @Test(timeout = 10_000)
    fun testInactivePlatformTrackedItemsPreservedAcrossRefresh() = runBlocking {
        // 预置一条历史 RYLL 平台的追踪单及未知平台单，必须在 createViewModel 之前写入生产键 tracked_nos
        val prefs = testContext.getSharedPreferences("express_tracked", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("tracked_nos", setOf("RYLL88888888|RYLL", "UNKNOWN999999|SF")).apply()

        session.upsert(Credential(Platform.SF, "sf_token", emptyMap(), System.currentTimeMillis()))
        val sfAdapter = checkNotNull(fakeAdapters[Platform.SF])
        sfAdapter.expressItemsToReturn = listOf(
            ExpressItem(
                id = "SF_ACTIVE",
                platform = Platform.SF,
                station = "顺丰点",
                stationAddress = null,
                trackingNo = "SF_ACTIVE_123",
                pickupCode = "1-1-1",
                summary = "新单",
                status = ExpressStatus.WAIT_PICKUP,
                arrivedAt = System.currentTimeMillis(),
                fetchDeadline = null,
                raw = null,
            )
        )

        val vm = createViewModel()
        vm.refresh(force = true)
        waitForStateCondition(vm) { !it.loading }

        val trackedAfter = prefs.getStringSet("tracked_nos", emptySet()).orEmpty()
        assertTrue("非活动平台 RYLL 追踪记录必须 100% 保持原文保留", trackedAfter.contains("RYLL88888888|RYLL"))
        assertTrue("未在当前账号列表中证明归属的追踪单必须安全保留", trackedAfter.contains("UNKNOWN999999|SF"))
        val memoryTracked = getTrackedInMemory(vm)
        assertTrue("内存 _tracked 同样包含 RYLL", memoryTracked.containsKey("RYLL88888888"))
        assertTrue("内存 _tracked 同样包含 UNKNOWN", memoryTracked.containsKey("UNKNOWN999999"))
    }

    // ==================== S6-02-R2 追加复审确定性门闩测试 ====================

    /**
     * R2 门闩反例 1：A 换号至 B 后，顺丰刷新绝不查询 A 账号旧单号。
     * 正向对比：A 账号在有效证明下，不在包裹列表中时会触发 fetchPickupCode；
     * 负向反例：切换到 B 账号后，旧证明注销，B 账号刷新绝不向网络发起查询。
     */
    @Test(timeout = 10_000)
    fun testSwitchAccountDoesNotQueryOldProvenTrackedItem() = runBlocking {
        val sfAdapter = checkNotNull(fakeAdapters[Platform.SF])
        val prefs = testContext.getSharedPreferences("express_tracked", Context.MODE_PRIVATE)

        // 预置跟踪单必须在 VM 初始化前写入生产键 tracked_nos
        prefs.edit().putStringSet("tracked_nos", setOf("SF_A_PROVEN|SF")).apply()

        // 1. 账号 A 登录并拉取包裹，登记 SF_A_PROVEN 证明
        val credA = Credential(Platform.SF, "token_A_sf", emptyMap(), System.currentTimeMillis())
        session.upsert(credA)
        sfAdapter.expressItemsToReturn = listOf(
            ExpressItem(
                id = "SF_A_PROVEN",
                platform = Platform.SF,
                station = "顺丰点",
                stationAddress = null,
                trackingNo = "SF_A_PROVEN",
                pickupCode = "1-1-1",
                summary = "A的件",
                status = ExpressStatus.WAIT_PICKUP,
                arrivedAt = System.currentTimeMillis(),
                fetchDeadline = null,
                raw = null,
            )
        )
        val vm = createViewModel()
        vm.refresh(force = true)
        waitForStateCondition(vm) { !it.loading }

        // 2. 正向验证：同一账号 A 下，当新一轮刷新列表中没有 SF_A_PROVEN 时，证明有效，会发起 fetchPickupCode
        sfAdapter.expressItemsToReturn = emptyList()
        sfAdapter.fetchPickupCodeCalls.clear()
        vm.refresh(force = true)
        waitForStateCondition(vm) { !it.loading }
        assertTrue(
            "同一账号 A 下拥有有效证明，列表无此单时会向网络发起 fetchPickupCode",
            sfAdapter.fetchPickupCodeCalls.contains("SF_A_PROVEN"),
        )

        // 3. 切换至账号 B，账号 B 列表中无该单
        val credB = Credential(Platform.SF, "token_B_sf", emptyMap(), System.currentTimeMillis() + 1000)
        sfAdapter.fetchPickupCodeCalls.clear()
        session.upsert(credB)
        vm.refresh(force = true)
        waitForStateCondition(vm) { !it.loading }

        // 4. 负向验证：由于 A 账号证明已在换号时注销，B 账号刷新绝不得向网络查询 SF_A_PROVEN
        assertFalse(
            "换号为 B 后，旧证明已失效，B 刷新绝不得使用 B 凭据向网络发起查询 A 账号旧单号",
            sfAdapter.fetchPickupCodeCalls.contains("SF_A_PROVEN"),
        )
    }

    /**
     * R2 门闩反例 2：不同平台相同单号绝不共用归属证明。
     * 先在顺丰列表中证明同号，再使顺丰新列表不包含它；
     * 配合明确 TAOBAO 追踪条目，验证淘宝绝不会借用顺丰证明发起网络查询。
     */
    @Test(timeout = 10_000)
    fun testDifferentPlatformSameWaybillDoesNotShareProof() = runBlocking {
        val sfAdapter = checkNotNull(fakeAdapters[Platform.SF])
        val tbAdapter = checkNotNull(fakeAdapters[Platform.TAOBAO])
        val prefs = testContext.getSharedPreferences("express_tracked", Context.MODE_PRIVATE)

        // 预置跟踪单必须在 VM 初始化前写入生产键 tracked_nos
        // 跟踪单明确属于淘宝：COMMON_SHARED_999|TAOBAO
        prefs.edit().putStringSet("tracked_nos", setOf("COMMON_SHARED_999|TAOBAO")).apply()

        // 顺丰与淘宝均登录
        session.upsert(Credential(Platform.SF, "token_sf", emptyMap(), System.currentTimeMillis()))
        session.upsert(Credential(Platform.TAOBAO, "token_tb", emptyMap(), System.currentTimeMillis()))

        // 1. 第一轮刷新：顺丰列表中返回 COMMON_SHARED_999 包裹，顺丰平台建立对该单号的证明
        sfAdapter.expressItemsToReturn = listOf(
            ExpressItem(
                id = "COMMON_SHARED_999",
                platform = Platform.SF,
                station = "顺丰点",
                stationAddress = null,
                trackingNo = "COMMON_SHARED_999",
                pickupCode = "SF-CODE",
                summary = "顺丰包裹",
                status = ExpressStatus.WAIT_PICKUP,
                arrivedAt = System.currentTimeMillis(),
                fetchDeadline = null,
                raw = null,
            )
        )
        tbAdapter.expressItemsToReturn = emptyList()

        val vm = createViewModel()
        vm.refresh(force = true)
        waitForStateCondition(vm) { !it.loading }

        // 2. 第二轮刷新：顺丰列表清空（使顺丰同号不再存在于 byNo 中，不提前 continue），淘宝列表仍为空
        sfAdapter.expressItemsToReturn = emptyList()
        tbAdapter.fetchPickupCodeCalls.clear()

        vm.refresh(force = true)
        waitForStateCondition(vm) { !it.loading }

        // 3. 验证：由于 COMMON_SHARED_999 仅属于顺丰证明，淘宝绝无此证明，淘宝绝不得发起网络查询
        assertFalse(
            "不同平台同号绝不共用证明，淘宝绝不得借用顺丰的证明向网络发起查询 COMMON_SHARED_999",
            tbAdapter.fetchPickupCodeCalls.contains("COMMON_SHARED_999"),
        )
    }

    /**
     * R2/R4 门闩反例 3：迟到响应绝不能登记过期证明。
     * 在 Fake 适配器中使用局部 NonCancellable 模拟非协作/不可取消的网络挂起与迟到返回；
     * try/finally 完整覆盖挂起等待与返回逻辑，确保换号时完成信号可靠到达，测试不发生死等超时；
     * 验证即便旧请求在换号后迟到返回旧包裹，新会话凭据检查与代次核验也会丢弃该响应，绝不向当前有效会话登记过期证明。
     */
    @Test(timeout = 10_000)
    fun testLateRefreshCannotRegisterExpiredProof() = runBlocking {
        val sfStarted = CompletableDeferred<Unit>()
        val sfRelease = CompletableDeferred<Unit>()
        val sfCompleted = CompletableDeferred<Unit>()
        val sfAdapter = checkNotNull(fakeAdapters[Platform.SF])
        val prefs = testContext.getSharedPreferences("express_tracked", Context.MODE_PRIVATE)

        // 预置跟踪单必须在 VM 初始化前写入生产键 tracked_nos
        prefs.edit().putStringSet("tracked_nos", setOf("LATE_EXPIRED_NO|SF")).apply()

        sfAdapter.customFetchHandler = { cred ->
            if (cred.token == "token_old_sf") {
                try {
                    sfStarted.complete(Unit)
                    // 局部 NonCancellable 模拟底层不可取消的网络挂起等待，确保完成信号稳定发送且真实模拟迟到响应
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        sfRelease.await()
                    }
                    listOf(
                        ExpressItem(
                            id = "LATE_EXPIRED_NO",
                            platform = Platform.SF,
                            station = "顺丰点",
                            stationAddress = null,
                            trackingNo = "LATE_EXPIRED_NO",
                            pickupCode = "CODE",
                            summary = "迟到包裹",
                            status = ExpressStatus.WAIT_PICKUP,
                            arrivedAt = System.currentTimeMillis(),
                            fetchDeadline = null,
                            raw = null,
                        )
                    )
                } finally {
                    sfCompleted.complete(Unit)
                }
            } else {
                emptyList()
            }
        }

        session.upsert(Credential(Platform.SF, "token_old_sf", emptyMap(), System.currentTimeMillis()))
        val vm = createViewModel()
        vm.refresh(force = true)
        sfStarted.await()

        // 换号为新 token
        session.upsert(Credential(Platform.SF, "token_new_sf", emptyMap(), System.currentTimeMillis() + 1000))

        // 释放旧请求让其执行完成，并等待旧请求确定性完成信号
        sfRelease.complete(Unit)
        sfCompleted.await()

        sfAdapter.fetchPickupCodeCalls.clear()

        // 新会话发起刷新（此时顺丰返回空列表，但 tracked 中有 LATE_EXPIRED_NO|SF）
        vm.refresh(force = true)
        waitForStateCondition(vm) { !it.loading }

        // 验证：旧代次迟到返回因凭据不匹配被丢弃，绝未登记证明，新会话刷新不会网络查询该单
        assertFalse(
            "迟到响应绝不得登记过期证明，新会话刷新绝不会向网络查询该旧单",
            sfAdapter.fetchPickupCodeCalls.contains("LATE_EXPIRED_NO"),
        )
    }

    /**
     * R2 门闩反例 4：刷新持有旧快照期间并发搜索成功新增 Y，刷新结束内存与 prefs 均完好保留 Y。
     * 关键时序：
     * 1. 预置已证明的追踪单 X（"SF_TRACKED_X|SF"）；
     * 2. 第一轮刷新使顺丰证明 X；
     * 3. 第二轮刷新使顺丰列表不含 X，进入追踪单网络补码查询 fetchPickupCode("SF_TRACKED_X")；
     * 4. 门闩精准设在 fetchPickupCode("SF_TRACKED_X")，此时生产已读取 currentTrackedSnapshot（只含 X，不含 Y）；
     * 5. 挂起期间，用户并发 lookupByTrackingNo("YT_CONCURRENT_Y")，新增 Y 写入内存 _tracked 与 prefs；
     * 6. 释放门闩让刷新完成；
     * 7. 差量更新机制生效，旧刷新绝不抹杀并发新增的 Y。
     */
    @Test(timeout = 10_000)
    fun testConcurrentSearchDuringRefreshPreservesTrackedItemAcrossCompletion() = runBlocking {
        val sfTrackedXStarted = CompletableDeferred<Unit>()
        val sfTrackedXRelease = CompletableDeferred<Unit>()
        val sfAdapter = checkNotNull(fakeAdapters[Platform.SF])
        val tbAdapter = checkNotNull(fakeAdapters[Platform.TAOBAO])
        val prefs = testContext.getSharedPreferences("express_tracked", Context.MODE_PRIVATE)

        // 1. 在 VM 创建前预置 X
        prefs.edit().putStringSet("tracked_nos", setOf("SF_TRACKED_X|SF")).apply()

        tbAdapter.pickupCodeToReturn = PickupCode(
            expressId = "YT_CONCURRENT_Y",
            platform = Platform.TAOBAO,
            code = "TB-PICKUP-Y",
        )

        session.upsert(Credential(Platform.SF, "sf_cred", emptyMap(), System.currentTimeMillis()))
        session.upsert(Credential(Platform.TAOBAO, "tb_cred", emptyMap(), System.currentTimeMillis()))

        val vm = createViewModel()

        // 2. 第一轮刷新：顺丰返回 SF_TRACKED_X 包裹，建立顺丰对 X 的归属证明
        sfAdapter.expressItemsToReturn = listOf(
            ExpressItem(
                id = "SF_TRACKED_X",
                platform = Platform.SF,
                station = "顺丰点",
                stationAddress = null,
                trackingNo = "SF_TRACKED_X",
                pickupCode = "SF-CODE-X",
                summary = "X包裹",
                status = ExpressStatus.WAIT_PICKUP,
                arrivedAt = System.currentTimeMillis(),
                fetchDeadline = null,
                raw = null,
            )
        )
        vm.refresh(force = true)
        waitForStateCondition(vm) { !it.loading }

        // 3. 第二轮刷新：顺丰包裹列表为空，门闩挂在 fetchPickupCode("SF_TRACKED_X")
        // 此时顺丰刷新已走过第 286 行读取了追踪快照 currentTrackedSnapshot（此时仅含 X）！
        sfAdapter.expressItemsToReturn = emptyList()
        sfAdapter.customPickupHandler = { _, expressId ->
            if (expressId == "SF_TRACKED_X") {
                sfTrackedXStarted.complete(Unit)
                sfTrackedXRelease.await()
                PickupCode(expressId, Platform.SF, "SF-CODE-X")
            } else {
                null
            }
        }

        vm.refresh(force = true)
        sfTrackedXStarted.await()
        assertTrue("顺丰刷新持有只含 X 的快照并在查询 X 期间挂起", vm.ui.value.loading)

        // 4. 刷新持有旧快照挂起期间，用户并发执行查单搜索 YT_CONCURRENT_Y
        vm.lookupByTrackingNo("YT_CONCURRENT_Y")
        waitForStateCondition(vm) { it.searchResult?.code == "TB-PICKUP-Y" }

        // 验证查单已原子写入内存与 prefs
        val trackedWhileRefresh = prefs.getStringSet("tracked_nos", emptySet()).orEmpty()
        assertTrue("查单成功后 prefs 立即包含 YT_CONCURRENT_Y", trackedWhileRefresh.contains("YT_CONCURRENT_Y|TAOBAO"))
        assertTrue("查单成功后内存 _tracked 包含 YT_CONCURRENT_Y", getTrackedInMemory(vm).containsKey("YT_CONCURRENT_Y"))

        // 5. 释放顺丰查询门闩，让持有旧快照的旧刷新继续执行完成
        sfTrackedXRelease.complete(Unit)
        waitForStateCondition(vm) { !it.loading }

        // 6. 验证：差量更新机制生效，旧刷新结束绝不因快照覆盖抹杀并发新增的 YT_CONCURRENT_Y
        val trackedAfterRefresh = prefs.getStringSet("tracked_nos", emptySet()).orEmpty()
        assertTrue(
            "旧刷新完成写回后，SharedPreferences 中必须完好保留并发新增的 YT_CONCURRENT_Y",
            trackedAfterRefresh.contains("YT_CONCURRENT_Y|TAOBAO"),
        )
        assertTrue(
            "旧刷新完成写回后，内存 _tracked 中必须完好保留并发新增的 YT_CONCURRENT_Y",
            getTrackedInMemory(vm).containsKey("YT_CONCURRENT_Y"),
        )
    }

    /**
     * 30 秒节流与 force 绕过。
     */
    @Test(timeout = 10_000)
    fun testThirtySecondThrottlePreservedForSameSessionButBypassedOnForceRefresh() = runBlocking {
        val adapter = checkNotNull(fakeAdapters[Platform.SF])
        val sfCred = Credential(Platform.SF, "sf_throttle_test", emptyMap(), System.currentTimeMillis())
        session.upsert(sfCred)

        val vm = createViewModel()
        vm.refresh(force = true)
        waitForStateCondition(vm) { !it.loading }
        val countAfterFirst = adapter.fetchCount
        assertTrue("初始刷新至少调用 1 次", countAfterFirst >= 1)

        // 30 秒内非 force 刷新，被节流
        vm.refresh(force = false)
        delay(100)
        assertEquals("30秒内 force=false 被节流，调用次数不增加", countAfterFirst, adapter.fetchCount)

        // 30 秒内 force=true 刷新，绕过节流立即拉取
        vm.refresh(force = true)
        waitForStateCondition(vm) { !it.loading && adapter.fetchCount > countAfterFirst }
        assertTrue("force=true 绕过节流立即发起拉取", adapter.fetchCount > countAfterFirst)
    }

    private suspend fun waitForStateCondition(
        vm: ExpressViewModel,
        timeoutMs: Long = 5000L,
        condition: (com.aggapp.app.ui.ExpressUiState) -> Boolean,
    ) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (condition(vm.ui.value)) return
            delay(50)
        }
        assertTrue("Condition not met within ${timeoutMs}ms", condition(vm.ui.value))
    }

    private class FakeExpressAdapter(
        override val platform: Platform,
        var beforeFetchLatch: CompletableDeferred<Unit>? = null,
        var onFetchStarted: (() -> Unit)? = null,
        var expressItemsToReturn: List<ExpressItem> = emptyList(),
        var customFetchHandler: (suspend (Credential) -> List<ExpressItem>)? = null,
        var shouldThrow: Exception? = null,
        var fetchCount: Int = 0,
        var beforePickupLatch: CompletableDeferred<Unit>? = null,
        var onPickupStarted: (() -> Unit)? = null,
        var pickupCodeToReturn: PickupCode? = null,
        var customPickupHandler: (suspend (Credential, String) -> PickupCode?)? = null,
        val fetchPickupCodeCalls: MutableList<String> = mutableListOf(),
    ) : PlatformAdapter {
        override val loginMode = LoginMode.REMOTE_SERVICE
        override fun loginUrl(): String = ""
        override fun detectLoginSuccess(req: CapturedRequest, cookies: Map<String, String>): Credential? = null
        override suspend fun checkAlive(cred: Credential): Boolean = true

        override suspend fun fetchExpressList(cred: Credential, page: Int): List<ExpressItem> {
            fetchCount++
            onFetchStarted?.invoke()
            beforeFetchLatch?.await()
            shouldThrow?.let { throw it }
            customFetchHandler?.let { return it.invoke(cred) }
            return expressItemsToReturn
        }

        override suspend fun fetchPickupCode(cred: Credential, expressId: String): PickupCode? {
            fetchPickupCodeCalls.add(expressId)
            onPickupStarted?.invoke()
            beforePickupLatch?.await()
            customPickupHandler?.let { return it.invoke(cred, expressId) }
            return pickupCodeToReturn
        }

        override suspend fun sendSmsCode(phone: String) {}
        override suspend fun loginWithSmsCode(phone: String, smsCode: String): Credential =
            Credential(platform, "sms_$smsCode", emptyMap(), System.currentTimeMillis())

        override suspend fun fetchIdentityCode(cred: Credential): IdentityCode =
            IdentityCode(platform, CodeType.BARCODE, "${platform.name}-CODE")
    }
}
