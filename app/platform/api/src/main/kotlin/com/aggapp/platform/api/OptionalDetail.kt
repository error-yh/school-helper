package com.aggapp.platform.api

import com.aggapp.core.model.PlatformError
import kotlinx.coroutines.CancellationException

/** Optional metadata can degrade; cancellation and invalid credentials are never cached as success. */
suspend fun <T> optionalPlatformDetail(block: suspend () -> T): T? = try { block() }
catch (e: CancellationException) { throw e }
catch (e: PlatformError.NeedRelogin) { throw e }
catch (_: Exception) { null }
