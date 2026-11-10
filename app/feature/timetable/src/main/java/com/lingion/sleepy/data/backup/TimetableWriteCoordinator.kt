/* SPDX-License-Identifier: GPL-3.0-only
 * Shared write coordination barrier ensuring mutually exclusive snapshots and safe concurrent writes.
 */
package com.lingion.sleepy.data.backup

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object TimetableWriteCoordinator {
    private val masterMutex = Mutex()

    /**
     * Executes a persistent write operation under the shared write lock.
     * When a snapshot lease is active, normal writes wait until the snapshot lease releases.
     */
    suspend fun <T> withWriteLock(action: suspend () -> T): T {
        return masterMutex.withLock {
            action()
        }
    }

    /**
     * Acquires the exclusive snapshot lease across all profiles and stores.
     * All persistent writes are frozen during this short-lived lease to capture an immutable snapshot.
     */
    suspend fun <T> withSnapshotLease(action: suspend () -> T): T {
        return masterMutex.withLock {
            action()
        }
    }
}
