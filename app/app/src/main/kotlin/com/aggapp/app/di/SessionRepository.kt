package com.aggapp.app.di

import android.content.Context
import com.aggapp.core.model.Platform
import com.aggapp.core.model.Credential
import com.aggapp.core.model.PlatformLoginState
import com.aggapp.core.model.PlatformError
import com.aggapp.platform.api.PlatformAdapter
import com.aggapp.core.storage.CredentialStore
import com.aggapp.core.storage.SavedCredential
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** 登录会话：内存 StateFlow + 加密落盘（CredentialStore），App 重启恢复 */
@Singleton
class SessionRepository @Inject constructor(private val store: CredentialStore) {

    private val _credentials = MutableStateFlow<Map<Platform, Credential>>(emptyMap())
    val credentials: StateFlow<Map<Platform, Credential>> = _credentials.asStateFlow()
    private val _loginStates = MutableStateFlow<Map<Platform, PlatformLoginState>>(emptyMap())
    val loginStates: StateFlow<Map<Platform, PlatformLoginState>> = _loginStates.asStateFlow()
    private val verificationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val verificationMutex = Mutex()
    @Volatile private var lastVerifyAt = 0L
    private var restored = false

    init { restore() }

    /** 启动时从加密存储恢复 */
    @Synchronized fun restore() {
        if (restored) return
        val restored = mutableMapOf<Platform, Credential>()
        for (p in Platform.entries) {
            store.load(p.name)?.let { s ->
                restored[p] = Credential(p, s.token, s.extra, s.capturedAt)
            }
        }
        _credentials.value = restored
        this.restored = true
    }

    @Synchronized fun upsert(cred: Credential) {
        _credentials.value = _credentials.value + (cred.platform to cred)
        store.save(
            SavedCredential(cred.platform.name, cred.token, cred.extra, cred.capturedAt),
        )
        _loginStates.value = _loginStates.value - cred.platform
        lastVerifyAt = 0L
    }

    @Synchronized fun clear(platform: Platform) {
        _credentials.value = _credentials.value - platform
        store.clear(platform.name)
        _loginStates.value = _loginStates.value + (platform to PlatformLoginState(platform, loggedIn = false))
        lastVerifyAt = 0L
    }

    /** Shared upstream startup checks; publish only for the exact credential that was probed. */
    fun verifyAll(adapters: Map<Platform, PlatformAdapter>, force: Boolean = false): Job = verificationScope.launch {
        verificationMutex.withLock {
            val now = System.currentTimeMillis()
            if (!force && now >= lastVerifyAt && now - lastVerifyAt < 600_000L) return@withLock
            lastVerifyAt = now
            val snapshot = credentials.value
            for (platform in Platform.activePlatforms) {
                ensureActive()
                val credential = snapshot[platform]
                if (credential == null) { publishLoginState(platform, null, false); continue }
                val adapter = adapters[platform] ?: continue
                val alive = try { adapter.checkAlive(credential) }
                    catch (e: CancellationException) { throw e }
                    catch (_: PlatformError.NeedRelogin) { false }
                    catch (_: Exception) { null }
                if (alive != null) publishLoginState(platform, credential, alive)
                delay(200)
            }
        }
    }

    @Synchronized private fun publishLoginState(platform: Platform, requested: Credential?, alive: Boolean) {
        if (_credentials.value[platform] != requested) return
        _loginStates.value = _loginStates.value + (platform to PlatformLoginState(platform,
            loggedIn = alive, needRelogin = requested != null && !alive, lastCheckAt = System.currentTimeMillis()))
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideCredentialStore(@ApplicationContext context: Context): CredentialStore =
        CredentialStore(context)

    @Provides
    @Singleton
    fun providePddAntiContent(@ApplicationContext context: Context): com.aggapp.platform.pdd.PddAntiContent =
        com.aggapp.platform.pdd.PddAntiContent(context)


}
