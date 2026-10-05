package io.github.szzgx.smartskip.accessibility

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Lets the UI ask the accessibility service for a fully scored capture of whatever is on
 * screen right now.
 *
 * ## Why a broadcast
 *
 * The inspector is a debugging tool, and the interesting screen is usually a splash ad in
 * *another* app — so the user cannot be looking at SmartSkip at the time. The service is the
 * only component that can read the current window, and the UI holds no reference to it (the
 * system owns its lifetime). A package-scoped broadcast is the lightest mechanism that reaches
 * a running service without binding to it.
 *
 * The broadcast is not exported and is protected by a signature-level permission, so only
 * SmartSkip itself can trigger a capture. That matters: an exported trigger would let any app
 * on the device cause SmartSkip to read the current screen.
 */
object InspectorCommand {

    /**
     * The action string. Namespaced by applicationId at runtime so the release and debug
     * builds — which have different applicationIds — cannot collide.
     */
    const val ACTION_REQUEST_SCORED_DUMP = "io.github.szzgx.smartskip.action.REQUEST_SCORED_DUMP"

    /**
     * Signature-level permission guarding the receiver. Declared in the manifest and required
     * by the receiver, so a third-party app cannot send the broadcast even if it guesses the
     * action.
     */
    const val PERMISSION = "io.github.szzgx.smartskip.permission.INSPECTOR"

    /**
     * Set by the running service, cleared when it goes away.
     *
     * Holding a lambda rather than the service instance keeps the reference from outliving the
     * service in an obvious way, and makes the intent of the indirection explicit.
     */
    @Volatile
    private var requestScoredDump: (() -> Unit)? = null

    internal fun register(handler: () -> Unit) {
        requestScoredDump = handler
    }

    internal fun unregister() {
        requestScoredDump = null
    }

    /** @return true when a service was available to service the request. */
    fun requestFromUi(): Boolean {
        val handler = requestScoredDump ?: return false
        handler()
        return true
    }
}

/**
 * Receives [InspectorCommand.ACTION_REQUEST_SCORED_DUMP].
 *
 * The UI normally calls [InspectorCommand.requestFromUi] directly, since the Activity and the
 * service share a process. This receiver exists so that a capture can also be triggered from
 * outside the UI — `adb shell am broadcast` during development, or a future quick-settings
 * tile — which is the situation the inspector is most useful in.
 *
 * It is guarded by a signature-level permission, because an unguarded trigger would let any app
 * on the device cause SmartSkip to read the current screen.
 */
class InspectorCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != InspectorCommand.ACTION_REQUEST_SCORED_DUMP) return

        // `goAsync` keeps the process alive for the short capture; the system kills the
        // receiver otherwise, and the dump would silently never happen.
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            try {
                InspectorCommand.requestFromUi()
            } catch (t: Throwable) {
                Log.w(TAG, "scored dump failed", t)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "SmartSkip/Inspector"
    }
}
