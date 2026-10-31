package com.aggapp.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggapp.app.di.PlatformRegistry
import com.aggapp.app.di.SessionRepository
import com.aggapp.core.model.Credential
import com.aggapp.core.model.IdentityCode
import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import com.aggapp.platform.api.PlatformAdapter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IdCodeUiState(
    /** 各平台身份码 */
    val codes: Map<Platform, IdentityCode> = emptyMap(),
    /** 该平台身份码是真实拉取（true）还是 Mock 演示（false） */
    val isReal: Map<Platform, Boolean> = emptyMap(),
    val selected: Platform = Platform.SF,
    val countdownSec: Long? = null,
    val loading: Boolean = false,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    registry: PlatformRegistry,
    private val session: SessionRepository,
) : ViewModel() {

    private val adapters: Map<Platform, PlatformAdapter> = registry.adapters

    /** 有真实登录态返回凭据，否则 null（UI 提示未登录，不做 Mock 兜底） */
    private fun credentialFor(p: Platform): Credential? = session.credentials.value[p]

    /** 上次全量刷新时间，ON_RESUME 自动刷新用 30s 节流 */
    private var lastRefreshAt = 0L

    private class PlatformBatchResult(
        val requestCred: Credential,
        val code: IdentityCode? = null,
        val isReal: Boolean = false,
        val needRelogin: Boolean = false,
    )

    /**
     * 全量刷新。force=true（下拉/单击刷新）强制执行并显示 loading；
     * 默认（ON_RESUME 自动刷新）30s 内节流跳过，已有缓存时静默刷新不闪 loading，
     * 避免切页转场期的重组风暴造成卡顿。
     */
    fun refreshAll(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAt < 30_000) return
        lastRefreshAt = now
        val silent = !force && _state.value.codes.isNotEmpty()
        viewModelScope.launch {
            if (!silent) _state.update { it.copy(loading = true) }
            val batchResults = mutableMapOf<Platform, PlatformBatchResult>()
            for ((p, adapter) in adapters) {
                val cred = credentialFor(p)
                if (cred == null) {
                    continue
                }
                try {
                    val code = adapter.fetchIdentityCode(cred)
                    batchResults[p] = PlatformBatchResult(
                        requestCred = cred,
                        code = code,
                        isReal = true,
                        needRelogin = false,
                    )
                } catch (e: PlatformError.NeedRelogin) {
                    batchResults[p] = PlatformBatchResult(
                        requestCred = cred,
                        code = null,
                        isReal = false,
                        needRelogin = true,
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 批次最终写入 state 前，重新核对各平台结果对应的发起凭据/会话版本。
            // 旧结果绝不能回填已退出账号，也不能覆盖新账号身份码；旧失败结果同样不能删除新账号码或标记其失效。
            _state.update { current ->
                val currentCreds = session.credentials.value
                val newCodes = current.codes.toMutableMap()
                val newReal = current.isReal.toMutableMap()

                // 1. 对于当前会话中未登录的所有平台，清空身份码并标记 isReal 为 false
                for (p in Platform.entries) {
                    if (!currentCreds.containsKey(p)) {
                        newCodes.remove(p)
                        newReal[p] = false
                    }
                }

                // 2. 逐一核验批次执行结果：当前凭据必须存在且严格等于发起请求时的凭据
                for ((p, res) in batchResults) {
                    val activeCred = currentCreds[p]
                    if (activeCred != null && activeCred == res.requestCred) {
                        if (res.isReal && res.code != null) {
                            newCodes[p] = res.code
                            newReal[p] = true
                        } else if (res.needRelogin) {
                            newCodes.remove(p)
                            newReal[p] = false
                        }
                    }
                    // activeCred != res.requestCred 时（已注销或已换号）：
                    // 忽略该旧结果，保留 current 中由换号/新凭据所产生的最新状态
                }

                current.copy(
                    codes = newCodes,
                    isReal = newReal,
                    loading = false,
                    selected = current.selected,
                )
            }
        }
    }

    private val _state = MutableStateFlow(IdCodeUiState())
    val state: StateFlow<IdCodeUiState> = _state.asStateFlow()

    private val credentialsJob = viewModelScope.launch {
        var previousCreds = session.credentials.value
        session.credentials.collect { newCreds ->
            if (previousCreds != newCreds) {
                onCredentialsUpdated(previousCreds, newCreds)
                previousCreds = newCreds
            }
        }
    }

    private val tickJob = viewModelScope.launch {
        while (true) {
            delay(1000)
            tick()
        }
    }

    public override fun onCleared() {
        credentialsJob.cancel()
        tickJob.cancel()
        super.onCleared()
    }

    init {
        session.restore()
        refreshAll(force = true)
    }

    private fun onCredentialsUpdated(
        oldCreds: Map<Platform, Credential>,
        newCreds: Map<Platform, Credential>,
    ) {
        for (p in Platform.entries) {
            val oldCred = oldCreds[p]
            val newCred = newCreds[p]
            if (oldCred != newCred) {
                if (newCred == null) {
                    // 平台退出登录：立即清除旧身份码与真实标记
                    _state.update { current ->
                        current.copy(
                            codes = current.codes - p,
                            isReal = current.isReal + (p to false),
                        )
                    }
                } else {
                    // 新登录或凭据变更：先清除旧码，并立即拉取新凭据身份码
                    _state.update { current ->
                        current.copy(
                            codes = current.codes - p,
                            isReal = current.isReal + (p to false),
                        )
                    }
                    refreshOne(p)
                }
            }
        }
    }

    fun select(platform: Platform) {
        _state.update { it.copy(selected = platform) }
    }

    private fun tick() {
        val s = _state.value
        val selected = s.codes[s.selected] ?: return
        val remain = selected.expireAt?.let { (it - System.currentTimeMillis()) / 1000 }
        _state.update { it.copy(countdownSec = remain?.coerceAtLeast(0)) }
        if (remain != null && remain <= 0) {
            refreshOne(s.selected)
        }
    }

    private fun refreshOne(platform: Platform) {
        viewModelScope.launch {
            val adapter = adapters[platform] ?: return@launch
            val cred = credentialFor(platform) ?: run {
                _state.update { current ->
                    current.copy(
                        codes = current.codes - platform,
                        isReal = current.isReal + (platform to false),
                    )
                }
                return@launch
            }
            try {
                val code = adapter.fetchIdentityCode(cred)
                // 版本保护：若网络返回时凭据已变更或已退出，丢弃该响应，不得回填旧码
                _state.update { current ->
                    if (credentialFor(platform) == cred) {
                        current.copy(
                            codes = current.codes + (platform to code),
                            isReal = current.isReal + (platform to true),
                        )
                    } else {
                        current
                    }
                }
            } catch (e: PlatformError.NeedRelogin) {
                _state.update { current ->
                    if (credentialFor(platform) == cred) {
                        current.copy(
                            codes = current.codes - platform,
                            isReal = current.isReal + (platform to false),
                        )
                    } else {
                        current
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
