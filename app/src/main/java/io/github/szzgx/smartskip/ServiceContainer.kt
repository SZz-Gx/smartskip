package io.github.szzgx.smartskip

import android.content.Context
import io.github.szzgx.smartskip.data.db.AdvertisementEventDao
import io.github.szzgx.smartskip.data.db.SmartSkipDatabase
import io.github.szzgx.smartskip.data.events.EventRecorder
import io.github.szzgx.smartskip.data.packages.SafetyRepository
import io.github.szzgx.smartskip.data.prefs.PreferenceStore
import io.github.szzgx.smartskip.data.rules.RuleStore
import io.github.szzgx.smartskip.safety.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The application's object graph, wired by hand.
 *
 * A DI framework would be more machinery than this graph needs, and every moving part
 * in this particular app is a part that can read the user's screen. Keeping the wiring
 * as plain code makes it reviewable in one sitting.
 *
 * Everything the decision path needs is created eagerly, because the system can start
 * the accessibility service without any Activity ever having run.
 */
class ServiceContainer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Long-lived scope for background work that must outlive a single frame.
     *
     * [SupervisorJob] so that one failed database write cannot tear down the scope the
     * accessibility service depends on.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val database: SmartSkipDatabase by lazy { SmartSkipDatabase.get(appContext) }

    val preferences = PreferenceStore(appContext, appScope)

    val ruleStore: RuleStore by lazy { RuleStore(database.appRuleDao()) }

    val safetyRepository: SafetyRepository by lazy { SafetyRepository(database.packageStateDao()) }

    val eventRecorder: EventRecorder by lazy { EventRecorder(database.advertisementEventDao()) }

    val eventDao: AdvertisementEventDao get() = database.advertisementEventDao()

    val appRuleDao get() = database.appRuleDao()

    val packageStateDao get() = database.packageStateDao()

    val clock: Clock = Clock.SYSTEM

    @Volatile
    private var hydrated = false

    /**
     * Loads the caches the decision path reads.
     *
     * Idempotent, and safe to call from both the Application and the service: the
     * service can be started before any Activity exists.
     */
    fun hydrate() {
        if (hydrated) return
        synchronized(this) {
            if (hydrated) return
            preferences.start()
            appScope.launch {
                runCatching { ruleStore.prime(appRuleDao.allRules()) }
                    .onFailure { android.util.Log.w(TAG, "could not prime rule cache", it) }
                runCatching { safetyRepository.prime(packageStateDao.allStates()) }
                    .onFailure { android.util.Log.w(TAG, "could not prime safety cache", it) }
                hydrated = true
            }
        }
    }

    private companion object {
        const val TAG = "SmartSkip"
    }
}
