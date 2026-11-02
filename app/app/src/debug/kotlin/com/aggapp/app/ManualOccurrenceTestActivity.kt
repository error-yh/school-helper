/* SPDX-License-Identifier: GPL-3.0-only
 * Test support activity packaged exclusively inside debug builds.
 * Strictly decoupled from release builds, zero entry in release APK.
 */
package com.aggapp.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.aggapp.app.ui.theme.AggTheme
import com.lingion.sleepy.api.TimetableDependencies
import com.lingion.sleepy.ui.TimetableRoute

class ManualOccurrenceTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dependencies = testDependencies
            ?: error("ManualOccurrenceTestActivity requires testDependencies to be configured before launch")
        setContent {
            AggTheme {
                TimetableRoute(dependencies = dependencies)
            }
        }
    }

    companion object {
        @Volatile
        var testDependencies: TimetableDependencies? = null
    }
}
