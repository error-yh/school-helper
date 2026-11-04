package com.aggapp.app.di

import android.content.Context
import com.aggapp.core.model.Platform
import com.aggapp.core.model.Credential
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
import javax.inject.Inject
import javax.inject.Singleton

/** 登录会话：内存 StateFlow + 加密落盘（CredentialStore），App 重启恢复 */
@Singleton
class SessionRepository @Inject constructor(private val store: CredentialStore) {

    private val _credentials = MutableStateFlow<Map<Platform, Credential>>(emptyMap())
    val credentials: StateFlow<Map<Platform, Credential>> = _credentials.asStateFlow()

    /** 启动时从加密存储恢复 */
    fun restore() {
        val restored = mutableMapOf<Platform, Credential>()
        for (p in Platform.entries) {
            store.load(p.name)?.let { s ->
                restored[p] = Credential(p, s.token, s.extra, s.capturedAt)
            }
        }
        _credentials.value = restored
    }

    fun upsert(cred: Credential) {
        _credentials.value = _credentials.value + (cred.platform to cred)
        store.save(
            SavedCredential(cred.platform.name, cred.token, cred.extra, cred.capturedAt),
        )
    }

    fun clear(platform: Platform) {
        _credentials.value = _credentials.value - platform
        store.clear(platform.name)
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
