package com.aggapp.platform.api

import com.aggapp.core.model.PlatformError

/** False means confirmed expiration. Offline, throttling and cancellation remain distinguishable. */
suspend fun platformSessionAlive(probe: suspend () -> Boolean): Boolean = try { probe() }
catch (_: PlatformError.NeedRelogin) { false }
