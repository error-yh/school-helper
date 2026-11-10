/* SPDX-License-Identifier: GPL-3.0-only
 * Test injection hooks for timetable data migration failure mode verification.
 */
package com.aggapp.app.migration

object TimetableMigrationTestHooks {
    @Volatile
    var beforePublishHook: (suspend () -> Unit)? = null

    @Volatile
    var afterPublishBeforeCommitHook: (suspend () -> Unit)? = null

    @Volatile
    var afterCommitBeforeReceiptHook: (suspend () -> Unit)? = null

    @Volatile
    var writeReceiptHook: (() -> Unit)? = null

    @Volatile
    var cleanupHook: (() -> Unit)? = null

    fun reset() {
        beforePublishHook = null
        afterPublishBeforeCommitHook = null
        afterCommitBeforeReceiptHook = null
        writeReceiptHook = null
        cleanupHook = null
    }
}
