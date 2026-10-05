package io.github.szzgx.smartskip.testharness

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.szzgx.smartskip.ui.theme.SmartSkipTheme

/**
 * Hosts one adversarial ad scenario in its own Activity.
 *
 * ## Why a separate Activity, and why not a screen in the app
 *
 * The harness deliberately contains a full-screen clickable node and controls whose being tapped
 * would be a *failure*. Those must be the root window's content, not nested inside the app's
 * navigation shell: otherwise the accessibility tree the engine reads would be the shell's, and the
 * geometry under test — which the scoring depends on, down to screen-area fractions — would be
 * wrong.
 *
 * A separate Activity also makes a run reproducible and scriptable: one window, one scenario, and
 * the scenario chosen by the launching intent, so a run is a single adb command.
 *
 * ## How a run works
 *
 * The tap that drives the engine must not come from the engine, so the driver is external:
 *
 * ```
 * adb shell am start -n <pkg>/io.github.szzgx.smartskip.testharness.TestHarnessActivity \
 *   --es scenario bait
 * adb shell input tap <x> <y>          # harmless nudge, makes the window emit tree events
 * adb logcat -d | grep -E 'SmartSkip/(Svc|Harness)'
 * ```
 *
 * `SmartSkip/Svc` reports what the engine decided; `SmartSkip/Harness` reports what the screen
 * actually received. A `FAILURE` line in the second means the run failed, regardless of what the
 * first claims.
 *
 * Debug-only: this file is in the `debug` source set.
 */
class TestHarnessActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyScenarioFrom(intent)

        setContent {
            SmartSkipTheme {
                val scenario by HarnessState.current.collectAsState()
                HarnessScenarioContent(scenario)
            }
        }
    }

    /**
     * Applies a scenario delivered to an already-running instance.
     *
     * This matters a great deal for a run: the obvious way to switch scenarios is to force-stop
     * between them, but force-stopping the app causes **HyperOS to remove SmartSkip's
     * accessibility service from the enabled list, and it is not restored automatically**. A run
     * built on force-stop therefore evaluates nothing from the second scenario onwards and reports
     * zeros that look like detection failures.
     *
     * Handling the new intent instead keeps the process — and the accessibility binding — alive.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyScenarioFrom(intent)
    }

    private fun applyScenarioFrom(intent: Intent?) {
        val key = intent?.getStringExtra(EXTRA_SCENARIO) ?: return
        val selected = HarnessState.selectByKey(key)
        if (selected == null) {
            Log.w(TAG, "unknown scenario '$key'; keeping ${HarnessState.current.value.key}")
        } else {
            Log.i(TAG, "scenario=${selected.key} expected=${selected.expectedOutcome}")
        }
    }

    companion object {
        const val TAG = "SmartSkip/Harness"
        const val EXTRA_SCENARIO = "scenario"
    }
}
