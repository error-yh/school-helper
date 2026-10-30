/* SPDX-License-Identifier: GPL-3.0-only */
package com.lingion.sleepy.importing.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** All native calls, including callbacks/stop, iterator deletion and recycle, belong to this worker. */
internal interface NativeOcrSession : AutoCloseable {
    fun initialize()
    fun setImage()
    fun recognize()
    fun readSpans(checkCancellation: () -> Unit): List<NativeOcrSpan>
}
internal class SerializedOcrWorker {
    suspend fun run(create: (() -> Boolean) -> NativeOcrSession): List<NativeOcrSpan> {
        // Capture the real requesting Job before entering NonCancellable. Its cancellation
        // remains observable even when JNI resets its own flag at getHOCRText entry.
        val parentJob = currentCoroutineContext()[Job]
        val checkCancellation = { parentJob?.ensureActive(); Unit }
        return nativeMutex.withLock {
            checkCancellation()
            withContext(Dispatchers.IO + NonCancellable) {
                checkCancellation()
                val session = create { parentJob?.isActive == false }
                try {
                    session.initialize()
                    checkCancellation()
                    session.setImage()
                    checkCancellation()
                    session.recognize()
                    checkCancellation()
                    session.readSpans(checkCancellation).also { checkCancellation() }
                } finally {
                    // Blocking native work must have returned before any cleanup.
                    session.close()
                }
            }
        }
    }
    companion object { private val nativeMutex = Mutex() }
}
