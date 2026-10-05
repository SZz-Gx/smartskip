package io.github.szzgx.smartskip.accessibility

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Placeholder service so the manifest resolves while the analysis layer is being
 * verified. The real implementation replaces this in the accessibility milestone.
 */
class SmartSkipAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty.
    }

    override fun onInterrupt() {
        // Intentionally empty.
    }

    private companion object {
        const val TAG = "SmartSkip/Svc"
    }
}
