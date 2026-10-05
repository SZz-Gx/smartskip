package io.github.szzgx.smartskip

import android.app.Application
import android.util.Log

/**
 * Process-wide entry point.
 *
 * Phase 0-3 keeps this deliberately thin: it exists so that the accessibility
 * service, the database and the preference store can share a single container
 * once the dependency graph stops being trivial. Manual wiring is used instead of
 * a DI framework because the graph is small, and a smaller graph is a smaller
 * thing to audit for a component that can read every screen on the device.
 */
class SmartSkipApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "SmartSkip ${BuildConfig.VERSION_NAME} starting (${BuildConfig.APPLICATION_ID})")
    }

    companion object {
        private const val TAG = "SmartSkip"

        /**
         * Set in [onCreate]. Safe to read from the accessibility service, which the
         * system only ever starts after the process (and therefore this class) is
         * already alive.
         */
        @Volatile
        lateinit var instance: SmartSkipApplication
            private set
    }
}
