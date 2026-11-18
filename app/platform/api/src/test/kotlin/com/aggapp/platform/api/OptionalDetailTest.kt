package com.aggapp.platform.api

import com.aggapp.core.model.Platform
import com.aggapp.core.model.PlatformError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OptionalDetailTest {
    @Test fun transientFailuresCanDegradeButCancellationAndExpiredSessionNeverBecomeSuccess() = runBlocking {
        assertNull(optionalPlatformDetail<String> { throw java.io.IOException("synthetic failure") })
        assertThrows(CancellationException::class.java) { runBlocking { optionalPlatformDetail<String> { throw CancellationException() } } }
        assertThrows(PlatformError.NeedRelogin::class.java) { runBlocking { optionalPlatformDetail<String> { throw PlatformError.NeedRelogin(Platform.PDD) } } }
        Unit
    }
}
