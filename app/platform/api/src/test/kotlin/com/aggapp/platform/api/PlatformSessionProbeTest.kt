package com.aggapp.platform.api

import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlatformSessionProbeTest {
    @Test fun onlyConfirmedExpiredCredentialsBecomeFalse() = runBlocking {
        assertTrue(platformSessionAlive { true })
        assertFalse(platformSessionAlive { throw PlatformError.NeedRelogin(Platform.PDD) })
        assertThrows(PlatformError.NetworkError::class.java) { runBlocking {
            platformSessionAlive { throw PlatformError.NetworkError(Platform.PDD, java.io.IOException("synthetic offline")) }
        } }
        assertThrows(PlatformError.ParseFailed::class.java) { runBlocking {
            platformSessionAlive { throw PlatformError.ParseFailed(Platform.PDD, "synthetic rate limit 54001") }
        } }
        assertThrows(CancellationException::class.java) { runBlocking { platformSessionAlive { throw CancellationException() } } }
        Unit
    }
}
