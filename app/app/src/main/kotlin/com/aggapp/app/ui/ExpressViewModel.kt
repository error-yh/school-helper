package com.aggapp.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggapp.app.di.PlatformRegistry
import com.aggapp.app.di.SessionRepository
import com.aggapp.core.model.Credential
import com.aggapp.core.model.ExpressItem
import com.aggapp.core.model.ExpressStatus
import com.aggapp.core.model.PickupCode
import com.aggapp.core.model.Platform
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.aggapp.core.model.ExpressDetail
import com.aggapp.app.sms.SmsCodeExtractor
import java.security.MessageDigest

/** 快递页 UI 状态 */
data class ExpressUiState(
    val loading: Boolean = false,
    val loggedInPlatforms: Set<Platform> = emptySet(),
    val stations: List<StationGroup> = emptyList(),   // 按驿站分组
    val inTransit: List<StationGroup> = emptyList(),
    val picked: List<StationGroup> = emptyList(),
    val tab: ExpressTab = ExpressTab.WAIT,
    val detailFor: ExpressItem? = null,
    val detail: ExpressDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    val smsPickups: List<SmsCodeExtractor.SmsPickup> = emptyList(),
    val smsPickedIds: Set<String> = emptySet(),
    val smsLoading: Boolean = false,
    val smsMessage: String? = null,
    val error: String? = null,
    // 运单号查询
    val searching: Boolean = false,
    val searchNotice: String? = null,                 // 查不到/未接入提示
    val searchResult: PickupCode? = null,             // 查到的取件码
    val needSuffixFor: String? = null,                // 顺丰单号不在列表 → 需输入手机后4位
)

/** 驿站分组 */
data class StationGroup(
    val stationName: String,
    val items: List<ExpressItem>,
)

/**
 * 快递页：
 * - 列表：各平台包裹按驿站分组；已签收/已取出不显示
 * - 跟踪单号：搜索查到的单自动加入跟踪（SharedPreferences 持久化），每次刷新
 *   按来源平台单独查状态；签收/查无自动移除
 */
@HiltViewModel
class ExpressViewModel @Inject constructor(
    registry: PlatformRegistry,
    private val sessionRepo: SessionRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val appContext = context
    private var detailGeneration = 0L
    private var detailJob: Job? = null
    private var smsJob: Job? = null
    private val detailCache = ExpressDetailCache()
    @Volatile private var displayedCredentials: Map<Platform, Credential> = emptyMap()
    @Volatile private var refreshRequestCredentials: Map<Platform, Credential> = emptyMap()
    @Volatile private var searchRequestCredentials: Map<Platform, Credential> = emptyMap()
    @Volatile private var detailRequestCredential: Credential? = null
    private val adapters = registry.adapters
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tracked = MutableStateFlow(loadTracked())

    private val _ui = MutableStateFlow(ExpressUiState())
    val ui: StateFlow<ExpressUiState> = _ui.asStateFlow()

    /** 列表刷新任务与代次（独立于查询，避免互相干扰） */
    private var refreshGeneration = 0L
    private var refreshJob: Job? = null

    /** 单号/尾号查询任务与代次（独立于刷新，避免互相干扰） */
    private var searchGeneration = 0L
    private var searchJob: Job? = null

    /** 会话凭据监听 Job */
    private var credentialsJob: Job? = null

    private data class ProvenOwnership(
        val waybill: String,
        val platform: Platform,
        val credential: Credential,
    )

    /** 内存中已证明归属有效会话/凭据的单号证明集合（绑定单号、平台与当前凭据） */
    private val provenOwnerships = mutableSetOf<ProvenOwnership>()

    private fun isProvenForCurrentSession(no: String, platform: Platform): Boolean {
        val cred = sessionRepo.credentials.value[platform] ?: return false
        return provenOwnerships.contains(ProvenOwnership(no, platform, cred))
    }

    init {
        sessionRepo.verifyAll(adapters)
        credentialsJob = viewModelScope.launch {
            var previousCreds = sessionRepo.credentials.value
            sessionRepo.credentials.collect { newCreds ->
                if (previousCreds != newCreds) {
                    onCredentialsUpdated(previousCreds, newCreds)
                    previousCreds = newCreds
                }
            }
        }
        loadSmsPickups()
        refresh(force = true)
    }

    public override fun onCleared() {
        detailJob?.cancel()
        smsJob?.cancel()
        credentialsJob?.cancel()
        refreshJob?.cancel()
        searchJob?.cancel()
        super.onCleared()
    }

    /**
     * 凭据变更回调：
     * 登出或换号时：
     * 1. 立即取消刷新和查询任务，代次自增使旧异步结果彻底失效；
     * 2. 立即明确复位 loading 与 searching 状态，不遗留 busy；
     * 3. 立即清除受影响平台的已展示包裹与错误；
     * 4. 派生来源清除：对于主平台保留但向已失效平台借码的包裹，立即清除其派生码；
     * 5. 清除归属证明集合中属于失效平台的单号记录；
     * 6. 清除旧查询展示与输入状态。
     */
    private fun onCredentialsUpdated(
        oldCreds: Map<Platform, Credential>,
        newCreds: Map<Platform, Credential>,
    ) {
        val changedPlatforms = mutableSetOf<Platform>()
        for (p in Platform.entries) {
            val oldC = oldCreds[p]
            val newC = newCreds[p]
            if (oldC != newC) {
                changedPlatforms.add(p)
            }
        }
        if (changedPlatforms.isEmpty()) return
        val stalePlatforms = changedPlatforms.filter { displayedCredentials[it] != newCreds[it] }.toSet()
        val cancelRefresh = changedPlatforms.any { refreshRequestCredentials[it] != newCreds[it] }
        val cancelSearch = changedPlatforms.any { searchRequestCredentials[it] != newCreds[it] }
        val cancelDetail = detailRequestCredential?.let { newCreds[it.platform] != it } == true
        if (cancelDetail) { detailJob?.cancel(); detailGeneration++; detailRequestCredential = null }
        detailCache.clear(changedPlatforms)
        displayedCredentials = displayedCredentials - stalePlatforms

        // 1. 取消正在执行的刷新和查询任务，使旧代次彻底失效
        if (cancelRefresh) { refreshJob?.cancel(); refreshGeneration++ }
        if (cancelSearch) { searchJob?.cancel(); searchGeneration++ }

        // 清除凭据发生变动或注销平台的所有归属证明
        provenOwnerships.removeAll { it.platform in changedPlatforms && it.credential != newCreds[it.platform] }

        // 2. 状态所有权清理与派生来源清除
        _ui.update { current ->
            val loggedIn = newCreds.keys.filter {
                it == Platform.SF || it == Platform.TAOBAO || it == Platform.PDD
            }.toSet()

            // 清除属于变动平台的包裹，并对借码来源属于变动平台的包裹清除派生码
            fun sanitize(groups: List<StationGroup>) = groups.mapNotNull { group ->
                val remainingItems = group.items.mapNotNull { item ->
                    if (item.platform in stalePlatforms) {
                        null // 主平台凭据已变/登出：彻底移除该包裹
                    } else if (item.pickupCodeSource in stalePlatforms) {
                        // 主平台保留，但取件码借自变动平台：保留包裹但安全清除派生取件码
                        item.copy(pickupCode = null, pickupCodeSource = null)
                    } else {
                        item
                    }
                }
                if (remainingItems.isEmpty()) null else group.copy(items = remainingItems)
            }

            current.copy(
                loading = if (cancelRefresh) false else current.loading,
                searching = if (cancelSearch) false else current.searching,
                loggedInPlatforms = loggedIn,
                stations = sanitize(current.stations),
                inTransit = sanitize(current.inTransit),
                picked = sanitize(current.picked),
                detailFor = if (cancelDetail) null else current.detailFor,
                detail = if (cancelDetail) null else current.detail,
                detailLoading = if (cancelDetail) false else current.detailLoading,
                detailError = if (cancelDetail) null else current.detailError,
                searchResult = if (cancelSearch) null else current.searchResult,
                searchNotice = if (cancelSearch) null else current.searchNotice,
                needSuffixFor = if (cancelSearch) null else current.needSuffixFor,
                error = if (loggedIn.isEmpty()) "请先在「我的」页登录顺丰/淘宝（短信验证码）/拼多多" else null,
            )
        }
    }

    /** 上次刷新时间，进入页面自动刷新用 30s 节流 */
    private var lastRefreshAt = 0L

    /** 清除查询结果展示（点空白处/刷新时调用；结果单号已加入跟踪列表，不受影响） */
    fun clearSearchResult() {
        _ui.update { it.copy(searchResult = null, searchNotice = null, needSuffixFor = null) }
    }

    /**
     * 拉取各平台包裹列表 + 跟踪单状态合并（顺丰+淘宝+拼多多，各自独立容错）
     * @param force 用户主动刷新用 true：强制执行并显示 loading；
     *              进入页面自动刷新用默认 false：30s 节流，已有数据时静默刷新（不闪 loading，
     *              全部失败保留旧列表不弹错）
     */
    fun refresh(force: Boolean = false) {
        // Serialize job ownership with credential collection and result publication on Main.
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            viewModelScope.launch { refresh(force) }
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAt < 30_000) return
        lastRefreshAt = now

        val silent = !force && (_ui.value.stations + _ui.value.inTransit + _ui.value.picked).isNotEmpty()
        val creds = sessionRepo.credentials.value
        val loggedIn = creds.keys.filter {
            it == Platform.SF || it == Platform.TAOBAO || it == Platform.PDD
        }.toSet()
        if (loggedIn.isEmpty()) {
            _ui.update {
                it.copy(
                    loading = false,
                    error = "请先在「我的」页登录顺丰/淘宝（短信验证码）/拼多多",
                    loggedInPlatforms = emptySet(),
                    stations = emptyList(), inTransit = emptyList(), picked = emptyList(),
                )
            }
            return
        }

        refreshRequestCredentials = creds
        val currentGen = ++refreshGeneration
        refreshJob?.cancel()

        _ui.update {
            it.copy(
                loading = !silent,
                error = null,
                loggedInPlatforms = loggedIn,
                searchResult = null,
                searchNotice = null,
                needSuffixFor = null,
            )
        }

        refreshJob = viewModelScope.launch {
            try {
                currentCoroutineContext().ensureActive()
                val errors = mutableListOf<Pair<Platform, String>>()
                val allItems = mutableListOf<ExpressItem>()
                val byNo = mutableMapOf<Pair<Platform, String>, ExpressItem>()
                val platformFetchedItems = mutableMapOf<Platform, List<ExpressItem>>()

                coroutineScope { for (platform in listOf(Platform.SF, Platform.TAOBAO, Platform.PDD)) { launch {
                    currentCoroutineContext().ensureActive()
                    val cred = creds[platform] ?: return@launch
                    try {
                        val items = mutableListOf<ExpressItem>()
                        for (page in 1..MAX_PAGES) {
                            currentCoroutineContext().ensureActive()
                            val batch = adapters.getValue(platform).fetchExpressList(cred, page)
                            items += batch.filter { it.platform == platform }
                            if (batch.size < PAGE_SIZE) break
                        }
                        for (item in items) {
                            val no = item.trackingNo
                            if (no != null) {
                                byNo[platform to no] = item // 全量索引（含签收，供跟踪状态判断）
                            }
                        }
                        allItems += items
                        platformFetchedItems[platform] = items
                        if (refreshGeneration == currentGen && sessionRepo.credentials.value == creds) {
                            val partial = listOf(Platform.SF, Platform.TAOBAO, Platform.PDD).flatMap { p -> platformFetchedItems[p].orEmpty() }
                            _ui.update { current ->
                                val retained = retainForSession((current.stations + current.inTransit + current.picked)
                                    .flatMap { it.items }.filter { it.platform !in platformFetchedItems }, creds)
                                present(current, ExpressListRules.deduplicate(partial + retained), creds)
                                    .copy(loading = !silent && partial.isEmpty() && retained.isEmpty())
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        errors += (platform to "${platformLabel(platform)}：暂时无法获取包裹，请稍后重试")
                    }
                } } }

                currentCoroutineContext().ensureActive()

                // ① 跨平台运单号去重：同一单多平台各有一份 → 合并为一条（有码优先、字段互补）
                val deduped = dedupeByTrackingNo(listOf(Platform.SF, Platform.TAOBAO, Platform.PDD).flatMap { platformFetchedItems[it].orEmpty() })

                // ② 无码待取件：互补补码（内部在持久化前做代次核验）
                val completed = backfillPickupCodes(deduped, creds, currentGen)

                // ③ 按驿站分组
                val groups = linkedMapOf<String, MutableList<ExpressItem>>()
                fun add(item: ExpressItem) {
                    val name = item.station.ifBlank { "未知驿站" }
                    groups.getOrPut(name) { mutableListOf() } += item
                }
                for (item in completed) add(item)

                currentCoroutineContext().ensureActive()

                // ④ 跟踪单维护（差量收集，绝不用旧快照整体覆盖并发新增）：
                // 关键保护：
                // 1. 旧非活动平台（如 RYLL）条目绝不参与活动包裹比对与网络拉取，写回时100%保留原文；
                // 2. 属于活动平台但无当前账号有效证明的单号，保守挂起且保留原文，不盲目发网络请求。
                val pickedTrackedToRemove = mutableSetOf<String>()
                var pddListCache: List<ExpressItem>? = null
                val currentTrackedSnapshot = _tracked.value
                for ((no, plat) in currentTrackedSnapshot) {
                    currentCoroutineContext().ensureActive()

                    // 非活动平台（如 RYLL）追踪单：直接保留，不比对 byNo，不删除，不发网络请求
                    if (!Platform.activePlatforms.contains(plat)) {
                        continue
                    }

                    val inList = byNo[plat to no]
                    if (inList != null && inList.status == ExpressStatus.PICKED) {
                        pickedTrackedToRemove.add(no)
                        continue
                    }
                    if (inList != null) continue // 列表已有且未签收

                    val cred = creds[plat] ?: continue // 来源平台未登录：保留跟踪不误删

                    // 未知归属保护：必须经当前有效会话证明归属该平台才发起查询
                    if (!isProvenForCurrentSession(no, plat)) {
                        continue
                    }

                    try {
                        when (plat) {
                            Platform.PDD -> {
                                val list = pddListCache
                                    ?: adapters.getValue(Platform.PDD).fetchExpressList(cred, 1).also { pddListCache = it }
                                val it2 = list.firstOrNull { it.trackingNo == no }
                                when {
                                    it2 == null || it2.status == ExpressStatus.PICKED -> pickedTrackedToRemove.add(no)
                                    else -> add(it2)
                                }
                            }
                            else -> {
                                val code = adapters.getValue(plat).fetchPickupCode(cred, no) ?: continue
                                add(
                                    ExpressItem(
                                        id = no,
                                        platform = plat,
                                        station = "跟踪单号",
                                        stationAddress = null,
                                        trackingNo = no,
                                        pickupCode = code.code,
                                        summary = null,
                                        status = ExpressStatus.WAIT_PICKUP,
                                        arrivedAt = null,
                                        fetchDeadline = null,
                                        raw = null,
                                    ),
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 单次状态查询失败：保留跟踪，下次刷新再查
                    }
                }

                // 副作用提交前严格核对代次与凭据有效性
                val currentCreds = sessionRepo.credentials.value
                val isStillValid = refreshGeneration == currentGen && currentCreds == creds

                // 归属证明登记：仅在当前请求通过代次与凭据核验后，才向当前有效会话登记归属证明
                if (isStillValid) {
                    for ((platform, items) in platformFetchedItems) {
                        val curCred = currentCreds[platform] ?: continue
                        for (item in items) {
                            val no = item.trackingNo ?: continue
                            provenOwnerships.add(ProvenOwnership(no, platform, curCred))
                        }
                    }
                }

                // 原子差量移除已签收单据：仅从最新 _tracked.value 中移除签收单，绝不覆盖并发新增的追踪单！
                if (isStillValid && pickedTrackedToRemove.isNotEmpty()) {
                    val latestTracked = _tracked.value
                    val updatedTracked = latestTracked.filterKeys { !pickedTrackedToRemove.contains(it) }
                    if (updatedTracked != latestTracked) {
                        _tracked.value = updatedTracked
                        saveTracked(updatedTracked)
                    }
                }

                _ui.update { current ->
                    // 若当前代次已变（被更新的刷新或登出事件替代），直接丢弃该旧结果
                    if (refreshGeneration != currentGen) {
                        return@update current
                    }

                    // 过滤包裹：只有发起凭据严格等于当前有效凭据的平台包裹才可保留
                    val validGroups = groups.mapNotNull { (name, list) ->
                        val filtered = list.filter { item ->
                            val reqCred = creds[item.platform]
                            val curCred = currentCreds[item.platform]
                            val isMainPlatformValid = reqCred != null && curCred != null && reqCred == curCred
                            // 检查派生来源：若借码来源凭据已变或失效，清除派生码
                            if (isMainPlatformValid && item.pickupCodeSource != null) {
                                val srcReq = creds[item.pickupCodeSource]
                                val srcCur = currentCreds[item.pickupCodeSource]
                                if (srcReq == null || srcCur == null || srcReq != srcCur) {
                                    return@filter false // 派生来源已失效的单从借码展示中剔除
                                }
                            }
                            isMainPlatformValid
                        }
                        if (filtered.isEmpty()) null else StationGroup(name, filtered)
                    }

                    // 过滤错误：已登出或换号平台的旧错误不得展示
                    val validErrors = errors.filter { (p, _) ->
                        val reqCred = creds[p]
                        val curCred = currentCreds[p]
                        reqCred != null && curCred != null && reqCred == curCred
                    }.map { it.second }

                    val finalStations = if (silent && validGroups.isEmpty() && validErrors.isNotEmpty()) {
                        grouped(retainForSession((current.stations + current.inTransit + current.picked).flatMap { it.items }, currentCreds))
                    } else {
                        validGroups
                    }

                    val finalError = if (silent) {
                        null
                    } else if (validGroups.isEmpty() && validErrors.isNotEmpty()) {
                        validErrors.joinToString("\n")
                    } else {
                        null
                    }

                    present(current, finalStations.flatMap { it.items }, creds).copy(loading = false, error = finalError)
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                // 取消或异常退出时，仅当属于当前代次才复位 loading 为 false
                if (refreshGeneration == currentGen) {
                    _ui.update { it.copy(loading = false) }
                }
            }
        }
    }

    /** 跨平台运单号去重：同号多平台仅保留一条（保持首次出现顺序） */
    private fun dedupeByTrackingNo(items: List<ExpressItem>): List<ExpressItem> = ExpressListRules.deduplicate(items)

    /**
     * 无码待取件互补查询：
     * 副作用保护：仅当代次与凭据在提交前严格有效时才持久化 channels。
     */
    private suspend fun backfillPickupCodes(
        items: List<ExpressItem>,
        creds: Map<Platform, Credential>,
        currentGen: Long,
    ): List<ExpressItem> {
        val channels = loadPickupChannels()
        var channelsDirty = false
        val out = mutableListOf<ExpressItem>()
        for (item in items) {
            currentCoroutineContext().ensureActive()
            val no = item.trackingNo
            if (item.status != ExpressStatus.WAIT_PICKUP || no == null || !item.pickupCode.isNullOrBlank()) {
                out += item
                continue
            }
            val recorded = channels[no]
            val candidates = buildList {
                if (recorded != null && recorded != item.platform && creds.containsKey(recorded)) add(recorded)
                for (p in FALLBACK_ORDER) {
                    if (p != item.platform && p != recorded && creds.containsKey(p)) add(p)
                }
            }
            var filled: ExpressItem? = null
            for (plat in candidates) {
                currentCoroutineContext().ensureActive()
                val code = try {
                    adapters.getValue(plat).fetchPickupCode(creds.getValue(plat), no)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (code != null) {
                    filled = item.copy(pickupCode = code.code, pickupCodeSource = plat)
                    if (channels[no] != plat) {
                        channels[no] = plat
                        channelsDirty = true
                    }
                    break
                }
            }
            out += filled ?: item
        }
        // 副作用校验：仅在代次与凭据严格等于发起状态时才允许写入持久化存储
        val currentCreds = sessionRepo.credentials.value
        if (channelsDirty && refreshGeneration == currentGen && currentCreds == creds) {
            savePickupChannels(channels)
        }
        return out
    }

    /** 搜索查到的单加入跟踪（已签收的不加） */
    private fun addTracked(no: String, plat: Platform) {
        val cred = sessionRepo.credentials.value[plat]
        if (cred != null) {
            provenOwnerships.add(ProvenOwnership(no, plat, cred))
        }
        val current = _tracked.value
        if (current[no] == plat) return
        val m = current + (no to plat)
        _tracked.value = m
        saveTracked(m)
    }

    private fun loadTracked(): Map<String, Platform> =
        prefs.getStringSet(KEY_TRACKED, emptySet()).orEmpty().mapNotNull { s ->
            val i = s.lastIndexOf('|')
            if (i <= 0) return@mapNotNull null
            val plat = runCatching { Platform.valueOf(s.substring(i + 1)) }.getOrNull() ?: return@mapNotNull null
            s.substring(0, i) to plat
        }.toMap()

    private fun saveTracked(m: Map<String, Platform>) {
        prefs.edit().putStringSet(KEY_TRACKED, m.map { "${it.key}|${it.value.name}" }.toSet()).apply()
    }

    /** 取件码来源渠道（互补查询命中时记录，`no|PLATFORM` StringSet），下次补码优先走该渠道 */
    private fun loadPickupChannels(): MutableMap<String, Platform> =
        prefs.getStringSet(KEY_PICKUP_CHANNELS, emptySet()).orEmpty().mapNotNull { s ->
            val i = s.lastIndexOf('|')
            if (i <= 0) return@mapNotNull null
            val plat = runCatching { Platform.valueOf(s.substring(i + 1)) }.getOrNull() ?: return@mapNotNull null
            s.substring(0, i) to plat
        }.toMap().toMutableMap()

    private fun savePickupChannels(m: Map<String, Platform>) {
        prefs.edit().putStringSet(KEY_PICKUP_CHANNELS, m.map { "${it.key}|${it.value.name}" }.toSet()).apply()
    }

    /**
     * 运单号查询：
     * 独立 searchGeneration 控制，任务所有权明确，互不误伤 refreshGeneration。
     */
    fun lookupByTrackingNo(waybill: String) {
        val no = waybill.trim().uppercase()
        if (no.isEmpty()) return

        val creds = sessionRepo.credentials.value
        searchRequestCredentials = creds
        val currentGen = ++searchGeneration
        searchJob?.cancel()

        _ui.update { it.copy(searching = true, searchResult = null, searchNotice = null, needSuffixFor = null) }

        searchJob = viewModelScope.launch {
            try {
                currentCoroutineContext().ensureActive()
                // 1) 顺丰单号
                if (SF_WAYBILL_REGEX.matches(no)) {
                    val cred = creds[Platform.SF]
                    if (cred == null) {
                        if (searchGeneration == currentGen) {
                            _ui.update { it.copy(searching = false, searchNotice = "请先在「我的」页登录顺丰") }
                        }
                        return@launch
                    }
                    val code = adapters.getValue(Platform.SF).fetchPickupCode(cred, no)
                    currentCoroutineContext().ensureActive()
                    val curCred = sessionRepo.credentials.value[Platform.SF]
                    if (searchGeneration == currentGen && curCred != null && curCred == cred) {
                        if (code != null) {
                            addTracked(no, Platform.SF)
                            _ui.update { it.copy(searching = false, searchResult = code) }
                        } else {
                            _ui.update {
                                it.copy(
                                    searching = false,
                                    needSuffixFor = no,
                                    searchNotice = "该运单不在列表中，需验证收件手机后 4 位查询",
                                )
                            }
                        }
                    }
                    return@launch
                }

                // 2) 其他单号 → 淘宝（菜鸟代收）→ 拼多多（多多驿站）兜底
                val tbCred = creds[Platform.TAOBAO]
                if (tbCred != null) {
                    val code = try {
                        adapters.getValue(Platform.TAOBAO).fetchPickupCode(tbCred, no)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    currentCoroutineContext().ensureActive()
                    val curTb = sessionRepo.credentials.value[Platform.TAOBAO]
                    if (searchGeneration == currentGen && curTb == tbCred && code != null) {
                        addTracked(no, Platform.TAOBAO)
                        _ui.update { it.copy(searching = false, searchResult = code) }
                        return@launch
                    }
                }

                val pddCred = creds[Platform.PDD]
                if (pddCred != null) {
                    val code = try {
                        adapters.getValue(Platform.PDD).fetchPickupCode(pddCred, no)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    currentCoroutineContext().ensureActive()
                    val curPdd = sessionRepo.credentials.value[Platform.PDD]
                    if (searchGeneration == currentGen && curPdd == pddCred && code != null) {
                        addTracked(no, Platform.PDD)
                        _ui.update { it.copy(searching = false, searchResult = code) }
                        return@launch
                    }
                }

                if (searchGeneration == currentGen) {
                    _ui.update {
                        it.copy(
                            searching = false,
                            searchNotice = "未查到 $no 的取件码（已查淘宝/拼多多，未登录或查不到）",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (searchGeneration == currentGen) {
                    _ui.update { it.copy(searching = false, searchNotice = "查询失败：${e.message}") }
                }
            } finally {
                // 仅当属于当前查询代次时才允许复位 searching
                if (searchGeneration == currentGen) {
                    _ui.update { it.copy(searching = false) }
                }
            }
        }
    }

    /**
     * 单号 + 手机后4位验证查询（顺丰 v2/getWaybillPickupCode）。
     */
    fun lookupWithSuffix(suffix: String) {
        val no = _ui.value.needSuffixFor ?: return
        val s = suffix.trim()
        if (s.length != 4 || !s.all { it.isDigit() }) {
            _ui.update { it.copy(searchNotice = "请输入 4 位数字手机尾号") }
            return
        }
        val plat = Platform.SF
        val cred = sessionRepo.credentials.value[plat] ?: run {
            _ui.update { it.copy(searchNotice = "请先在「我的」页登录顺丰") }
            return
        }

        searchRequestCredentials = sessionRepo.credentials.value
        val currentGen = ++searchGeneration
        searchJob?.cancel()

        _ui.update { it.copy(searching = true, searchNotice = null) }

        searchJob = viewModelScope.launch {
            try {
                currentCoroutineContext().ensureActive()
                val code = (adapters.getValue(Platform.SF) as com.aggapp.platform.sf.SfAdapter)
                    .fetchPickupCodeWithSuffix(cred, no, s)
                currentCoroutineContext().ensureActive()

                val curCred = sessionRepo.credentials.value[plat]
                if (searchGeneration == currentGen && curCred != null && curCred == cred) {
                    if (code != null) addTracked(no, plat)
                    _ui.update {
                        if (code != null) {
                            it.copy(searching = false, searchResult = code, needSuffixFor = null)
                        } else {
                            it.copy(
                                searching = false,
                                searchNotice = "尾号 $s 未查到取件码（尾号错误/已取走/运单号有误）",
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (searchGeneration == currentGen) {
                    _ui.update { it.copy(searching = false, searchNotice = "查询失败：${e.message}") }
                }
            } finally {
                if (searchGeneration == currentGen) {
                    _ui.update { it.copy(searching = false) }
                }
            }
        }
    }

    /** 收回尾号验证引导（点关闭/空白处） */
    fun dismissSuffix() {
        _ui.update { it.copy(needSuffixFor = null, searchNotice = null) }
    }

    fun clearSearch() {
        _ui.update { it.copy(searchResult = null, searchNotice = null, needSuffixFor = null) }
    }

    private fun platformLabel(p: Platform) = when (p) {
        Platform.SF -> "顺丰"
        Platform.TAOBAO -> "淘宝"
        Platform.JD -> "京东"
        Platform.PDD -> "拼多多"
        Platform.RYLL -> "如意邻里"
    }


    fun setTab(tab: ExpressTab) { _ui.update { it.copy(tab = tab) } }

    private fun grouped(items: List<ExpressItem>): List<StationGroup> = items.groupBy { it.station.ifBlank { "未知驿站" } }
        .map { (station, rows) -> StationGroup(station, rows) }

    private fun overrideKey(item: ExpressItem): String? {
        val credential = sessionRepo.credentials.value[item.platform] ?: return null
        val identity = buildString {
            append(credential.platform.name); append('\u0000'); append(credential.token)
            credential.extra.toSortedMap().forEach { (k, v) -> append('\u0000'); append(k.length); append(':'); append(k); append(v.length); append(':'); append(v) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
        return "picked_overrides_$digest"
    }

    private fun present(
        current: ExpressUiState,
        rows: List<ExpressItem>,
        sourceCredentials: Map<Platform, Credential> = displayedCredentials,
    ): ExpressUiState {
        val active = sessionRepo.credentials.value
        // A local action cannot promote rows or borrowed codes from an earlier session.
        val safeRows = retainForSession(rows, active, sourceCredentials)
        displayedCredentials = active
        val marked = safeRows.map { item ->
            val key = overrideKey(item)
            val picked = key?.let { prefs.getStringSet(it, emptySet()).orEmpty() }.orEmpty()
            val no = item.trackingNo ?: item.id
            if (picked.any { it.substringBeforeLast('|') == no }) item.copy(status = ExpressStatus.PICKED) else item
        }
        val split = ExpressListRules.partition(marked)
        return current.copy(stations = grouped(split.waiting), inTransit = grouped(split.transit), picked = grouped(split.picked))
    }

    private fun retainForSession(
        rows: List<ExpressItem>,
        current: Map<Platform, Credential>,
        displayed: Map<Platform, Credential> = displayedCredentials,
    ): List<ExpressItem> {
        return rows.mapNotNull { item ->
            if (current[item.platform] == null || displayed[item.platform] != current[item.platform]) null
            else if (item.pickupCodeSource != null && displayed[item.pickupCodeSource] != current[item.pickupCodeSource])
                item.copy(pickupCode = null, pickupCodeSource = null)
            else item
        }
    }

    fun markPicked(item: ExpressItem) {
        if (displayedCredentials[item.platform] != sessionRepo.credentials.value[item.platform]) return
        val current = _ui.value
        val rows = (current.stations + current.inTransit + current.picked).flatMap { it.items }
        if (rows.none { it == item }) return
        val key = overrideKey(item) ?: return
        val no = item.trackingNo ?: item.id
        val records = prefs.getStringSet(key, emptySet()).orEmpty().filterNot { it.substringBeforeLast('|') == no }
        val updated = (records + "$no|${System.currentTimeMillis()}").sortedByDescending { it.substringAfterLast('|').toLongOrNull() ?: 0L }.take(200).toSet()
        prefs.edit().putStringSet(key, updated).apply()
        _ui.update { present(it, (it.stations + it.inTransit + it.picked).flatMap { group -> group.items }) }
    }

    fun loadDetail(item: ExpressItem) {
        val current = _ui.value
        if ((current.stations + current.inTransit + current.picked).flatMap { it.items }.none { it == item }) return
        val credential = sessionRepo.credentials.value[item.platform] ?: return
        if (displayedCredentials[item.platform] != credential) return
        detailRequestCredential = credential
        val generation = ++detailGeneration
        detailJob?.cancel()
        _ui.update { it.copy(detailFor = item, detail = null, detailLoading = true, detailError = null) }
        detailJob = viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val detail = detailCache.get(credential, item, now) ?: adapters.getValue(item.platform).fetchDetail(credential, item)
                currentCoroutineContext().ensureActive()
                if (generation != detailGeneration || sessionRepo.credentials.value[item.platform] != credential || _ui.value.detailFor != item) return@launch
                if (detail != null && item.trackingNo != null && detail.trackingNo != item.trackingNo) {
                    _ui.update { it.copy(detailLoading = false, detailError = "详情与所选包裹不匹配，请重新查询") }
                    return@launch
                }
                if (detail != null) detailCache.put(credential, item, detail, now)
                _ui.update { it.copy(detailLoading = false, detail = detail, detailError = if (detail == null) "暂无物流详情，可稍后重试" else null) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (generation == detailGeneration && sessionRepo.credentials.value[item.platform] == credential) {
                    _ui.update { it.copy(detailLoading = false, detailError = "物流详情查询失败，请稍后重试") }
                }
            }
        }
    }

    fun dismissDetail() {
        detailRequestCredential = null
        detailGeneration++
        detailJob?.cancel()
        _ui.update { it.copy(detailFor = null, detail = null, detailLoading = false, detailError = null) }
    }

    fun loadSmsPickups() {
        viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) { SmsCodeExtractor.loadAll(appContext) }
            _ui.update { it.copy(smsPickups = rows, smsPickedIds = SmsCodeExtractor.pickedIds(appContext)) }
        }
    }

    /** No caller in refresh/init: only the explicit SMS button reaches the inbox scanner. */
    fun scanSms() {
        if (smsJob?.isActive == true) return
        _ui.update { it.copy(smsLoading = true, smsMessage = null) }
        smsJob = viewModelScope.launch {
            try {
                val result = SmsCodeExtractor.scanAndMerge(appContext)
                currentCoroutineContext().ensureActive()
                _ui.update { it.copy(smsMessage = result.fold({ count -> "已读取近三天短信，新增 $count 条取件信息" }, { "无法读取短信，请检查权限后重试" })) }
                loadSmsPickups()
            } finally { _ui.update { it.copy(smsLoading = false) } }
        }
    }

    fun dismissSmsMessage() { _ui.update { it.copy(smsMessage = null) } }
    fun markSmsPicked(id: String) { SmsCodeExtractor.markPicked(appContext, id); loadSmsPickups() }
    fun deleteSmsPickup(id: String) { SmsCodeExtractor.delete(appContext, id); loadSmsPickups() }

    companion object {
        const val PREFS_NAME = "express_tracked"
        const val KEY_TRACKED = "tracked_nos"
        const val KEY_PICKUP_CHANNELS = "pickup_channels"

        private const val MAX_PAGES = 3
        private const val PAGE_SIZE = 10

        /** 无码待取件互补查询顺序：淘宝优先（multiOneSearch 轻量）、PDD 较重（54001 限频）靠后 */
        val FALLBACK_ORDER = listOf(Platform.TAOBAO, Platform.PDD, Platform.SF)
        val SF_WAYBILL_REGEX = Regex("^SF\\d{12,15}$")
    }
}
