package io.github.szzgx.smartskip.accessibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the UI needs to know about the accessibility service, without binding to it.
 *
 * The UI cannot hold a reference to an `AccessibilityService`: the system owns its lifetime
 * and can destroy it at any moment. So the service publishes what matters here and the UI
 * observes.
 *
 * Enabling the service is done through the system's own settings screen, never through an
 * in-app toggle. That is the only mechanism Android permits, and it is also the right one:
 * the platform's screen is where the user is told what the permission grants.
 *
 * All writes go through [MutableStateFlow.update], which is a compare-and-set loop, so the
 * service can publish from its background dispatcher without a lock.
 */
object ServiceStatus {

    private val _state = MutableStateFlow(ServiceStatusSnapshot())

    /** Observable status, for Compose. */
    val state: StateFlow<ServiceStatusSnapshot> = _state.asStateFlow()

    /** Called by the service when it connects. */
    fun markConnected() = _state.update { it.copy(serviceConnected = true) }

    /** Called when the service is destroyed or unbound. */
    fun markDisconnected() = _state.update { it.copy(serviceConnected = false) }

    /** Called after an automatic skip was dispatched. */
    fun recordAutoSkip(packageName: String, score: Int, explanation: String) = _state.update {
        it.copy(
            autoSkipCount = it.autoSkipCount + 1,
            lastPackageName = packageName,
            lastScore = score,
            lastExplanation = explanation,
            lastEventAtMs = System.currentTimeMillis(),
        )
    }

    /** Called when a candidate was assessed but not acted on. */
    fun recordObservation(packageName: String, score: Int, explanation: String) = _state.update {
        it.copy(
            lastPackageName = packageName,
            lastScore = score,
            lastExplanation = explanation,
            lastEventAtMs = System.currentTimeMillis(),
        )
    }

    /** Called when a dangerous mis-click was recorded, so the UI can surface it. */
    fun recordDangerousMisclick(packageName: String, explanation: String) = _state.update {
        it.copy(
            dangerousMisclickCount = it.dangerousMisclickCount + 1,
            lastPackageName = packageName,
            lastExplanation = explanation,
            lastEventAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * @return true when SmartSkip is currently listed among the device's enabled
     *   accessibility services.
     *
     * Queried from the platform rather than read from [state], so the answer is correct even
     * before the service has ever started — which is exactly the situation the dashboard has
     * to describe.
     */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, SmartSkipAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false

        return enabled.split(':').any { entry ->
            ComponentName.unflattenFromString(entry) == expected
        }
    }

    /**
     * Opens the accessibility settings screen.
     *
     * Android exposes no public intent that deep-links to a single accessibility service —
     * `Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS` is a hidden framework action and is not
     * part of the SDK, so using it would be an unsupported dependency on an internal name.
     * This opens the general accessibility list instead, where SmartSkip appears under its
     * label, and the user taps it.
     */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}

/**
 * An immutable view of the service, for Compose to render.
 *
 * @property serviceConnected true once the accessibility service has reported a connection
 *   in this process. Distinct from the platform's own "is it enabled" answer, because the
 *   service is only started once the system decides to bind it.
 * @property autoSkipCount automatic skips dispatched since the process started.
 * @property dangerousMisclickCount dangerous mis-clicks observed since the process started.
 */
data class ServiceStatusSnapshot(
    val serviceConnected: Boolean = false,
    val lastPackageName: String? = null,
    val lastExplanation: String? = null,
    val lastScore: Int? = null,
    val autoSkipCount: Int = 0,
    val dangerousMisclickCount: Int = 0,
    val lastEventAtMs: Long = 0L,
)
