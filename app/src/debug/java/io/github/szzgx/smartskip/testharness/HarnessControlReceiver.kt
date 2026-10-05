package io.github.szzgx.smartskip.testharness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.szzgx.smartskip.SmartSkipApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only control channel for automating a harness run.
 *
 * ## Why this exists
 *
 * Two things have to be changed from outside the app to exercise the click path, and doing either
 * by hand makes a run unrepeatable:
 *
 *  1. **the auto-skip master switch**, which is stored in DataStore and has no adb-reachable
 *     surface; and
 *  2. **rule confirmation**, because a surface SmartSkip has never decided on is deliberately
 *     never clicked (the first-sight gate in `DecisionEngine`). Without confirmation the click
 *     path cannot be reached at all — which is the whole reason the path went untested.
 *
 * Both are legitimate user actions; this just performs them from adb so that a run is one command
 * instead of a sequence of taps on the right screens.
 *
 * Debug-only: this file is in the `debug` source set, and the receiver is declared in the debug
 * manifest. Nothing here is reachable in a build that ships.
 */
class HarnessControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = SmartSkipApplication.containerOf(context)
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                when (intent.action) {
                    ACTION_SET_AUTOSKIP -> {
                        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
                        container.preferences.setAutoSkipEnabled(enabled)
                        Log.i(TAG, "auto-skip master switch -> $enabled")
                    }

                    ACTION_CONFIRM_RULES -> {
                        val target = intent.getStringExtra(EXTRA_PACKAGE) ?: context.packageName
                        val rules = container.appRuleDao.rulesForPackage(target)
                        rules.forEach { rule ->
                            container.ruleStore.confirm(
                                packageName = rule.packageName,
                                activityName = rule.activityName.ifBlank { null },
                                signature = rule.structureSignature,
                            )
                        }
                        Log.i(TAG, "confirmed ${rules.size} rule(s) for $target")
                    }

                    ACTION_RESET -> {
                        /*
                         * Clears learned rules only, and says so. Also clearing the per-package
                         * safety state would silently lift any mis-click suspension, which would
                         * make a run look cleaner than the engine actually is — the opposite of
                         * what a test harness is for.
                         */
                        container.ruleStore.deleteAll()
                        Log.i(TAG, "cleared all learned rules (safety state left intact)")
                    }

                    else -> Log.w(TAG, "unknown action ${intent.action}")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "control action failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "SmartSkip/Harness"

        const val ACTION_SET_AUTOSKIP = "io.github.szzgx.smartskip.action.SET_AUTOSKIP"
        const val ACTION_CONFIRM_RULES = "io.github.szzgx.smartskip.action.CONFIRM_RULES"
        const val ACTION_RESET = "io.github.szzgx.smartskip.action.RESET_RULES"

        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_PACKAGE = "package"
    }
}
