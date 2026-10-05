package io.github.szzgx.smartskip

import android.app.Application
import android.util.Log

/**
 * Process-wide entry point.
 *
 * Its only job is to build the [ServiceContainer] early enough that the
 * accessibility service never has to construct the dependency graph itself. The
 * system can start an accessibility service before any Activity has run, so doing
 * this lazily inside the service would mean the very first frames — exactly the
 * frames a splash ad occupies — raced against database and DataStore reads.
 */
class SmartSkipApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        container.hydrate()
        Log.i(TAG, "SmartSkip ${BuildConfig.VERSION_NAME} starting (${BuildConfig.APPLICATION_ID})")
    }

    /**
     * The hand-wired object graph. Built on first access, which [onCreate] forces.
     */
    val container: ServiceContainer by lazy { ServiceContainer(this) }

    companion object {
        private const val TAG = "SmartSkip"

        /**
         * Safe to read from the accessibility service, which the system only ever
         * starts after this class has been constructed.
         */
        @Volatile
        lateinit var instance: SmartSkipApplication
            private set

        /** Convenience accessor for components that hold only a Context. */
        fun containerOf(context: android.content.Context): ServiceContainer =
            (context.applicationContext as SmartSkipApplication).container
    }
}
