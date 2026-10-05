package io.github.szzgx.smartskip.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.szzgx.smartskip.analysis.AutoSkipMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The user's settings, in the exact shape design doc §12 describes as
 * `UserPreference`, extended with the safety controls the rest of the design
 * requires.
 *
 * @property autoSkipMasterSwitch the global on/off for automatic clicking. When
 *   false SmartSkip still observes and scores, but never taps.
 * @property mode safe / standard / aggressive (design doc §10).
 * @property learningEnabled whether new rules may be created from observations.
 * @property showNotifications whether a notification accompanies an auto-skip.
 * @property watchOnlyPackages packages the user asked to observe but never act on.
 * @property excludedPackages packages SmartSkip must ignore entirely.
 * @property minimumClickIntervalMs floor on the gap between two automatic taps,
 *   globally. Guards against a screen that re-renders a skip button every frame.
 * @property maxClicksPerWindowPerMinute per-package burst limit.
 * @property stopAfterDangerousMisclicks how many dangerous mis-clicks in one
 *   package before automatic action is suspended there.
 * @property eventRetentionDays how long observations are kept.
 * @property logObservationEvents whether non-actionable candidates are recorded.
 *   Off by default: it produces a lot of rows for little benefit.
 */
data class UserPreferences(
    val autoSkipMasterSwitch: Boolean = false,
    val mode: AutoSkipMode = AutoSkipMode.DEFAULT,
    val learningEnabled: Boolean = true,
    val showNotifications: Boolean = true,
    val watchOnlyPackages: Set<String> = emptySet(),
    val excludedPackages: Set<String> = emptySet(),
    val minimumClickIntervalMs: Long = 1_200L,
    val maxClicksPerWindowPerMinute: Int = 3,
    val stopAfterDangerousMisclicks: Int = 2,
    val eventRetentionDays: Int = 7,
    val logObservationEvents: Boolean = false,
)

/**
 * Persists [UserPreferences] in a small DataStore file.
 *
 * ## Why there is a cache
 *
 * The decision pipeline must never block on disk I/O, because it runs inside the
 * accessibility event stream and a stalled frame is a visibly janky device. So the
 * current value is held in memory, refreshed once at startup and kept in step by
 * [update]. [flow] remains the source of truth for the UI.
 */
class PreferenceStore(private val context: Context, private val scope: CoroutineScope) {

    @Volatile
    private var cached: UserPreferences = UserPreferences()

    private val state = MutableStateFlow(cached)

    /** Stream of preference changes, for Compose. */
    val flow: StateFlow<UserPreferences> = state.asStateFlow()

    /**
     * The last known value. Cheap and non-suspending, safe to call from the
     * accessibility event path.
     */
    fun snapshot(): UserPreferences = cached

    /**
     * Starts mirroring DataStore into memory. Call once, from a long-lived scope.
     */
    fun start() {
        scope.launch {
            context.dataStore.data
                .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
                .collect { prefs ->
                    val loaded = prefs.toUserPreferences()
                    cached = loaded
                    state.value = loaded
                }
        }
    }

    /** Reads the persisted value directly, bypassing the cache. */
    suspend fun current(): UserPreferences = context.dataStore.data.first().toUserPreferences()

    suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        context.dataStore.edit { prefs ->
            val updated = transform(prefs.toUserPreferences())
            prefs[KEYS.autoSkipMasterSwitch] = updated.autoSkipMasterSwitch
            prefs[KEYS.mode] = updated.mode.name
            prefs[KEYS.learningEnabled] = updated.learningEnabled
            prefs[KEYS.showNotifications] = updated.showNotifications
            prefs[KEYS.watchOnlyPackages] = encodeSet(updated.watchOnlyPackages)
            prefs[KEYS.excludedPackages] = encodeSet(updated.excludedPackages)
            prefs[KEYS.minimumClickIntervalMs] = updated.minimumClickIntervalMs.toInt()
            prefs[KEYS.maxClicksPerWindowPerMinute] = updated.maxClicksPerWindowPerMinute
            prefs[KEYS.stopAfterDangerousMisclicks] = updated.stopAfterDangerousMisclicks
            prefs[KEYS.eventRetentionDays] = updated.eventRetentionDays
            prefs[KEYS.logObservationEvents] = updated.logObservationEvents

            // Keep the in-memory copy in step immediately, so the next accessibility
            // frame sees the new setting rather than waiting for the Flow to emit.
            cached = updated
            state.value = updated
        }
    }

    suspend fun setAutoSkipEnabled(enabled: Boolean) = update { it.copy(autoSkipMasterSwitch = enabled) }

    suspend fun setMode(mode: AutoSkipMode) = update { it.copy(mode = mode) }

    suspend fun setWatchOnly(packageName: String, watchOnly: Boolean) = update {
        val next = it.watchOnlyPackages.toMutableSet()
        if (watchOnly) next += packageName else next -= packageName
        it.copy(watchOnlyPackages = next)
    }

    suspend fun setExcluded(packageName: String, excluded: Boolean) = update {
        val next = it.excludedPackages.toMutableSet()
        if (excluded) next += packageName else next -= packageName
        it.copy(excludedPackages = next)
    }

    private fun encodeSet(values: Set<String>): String =
        values.filter { it.isNotBlank() }.sorted().joinToString(SET_SEPARATOR)

    private fun decodeSet(value: String?): Set<String> =
        value?.split(SET_SEPARATOR)?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    private fun Preferences.toUserPreferences(): UserPreferences = UserPreferences(
        autoSkipMasterSwitch = this[KEYS.autoSkipMasterSwitch] ?: false,
        mode = AutoSkipMode.fromName(this[KEYS.mode]),
        learningEnabled = this[KEYS.learningEnabled] ?: true,
        showNotifications = this[KEYS.showNotifications] ?: true,
        watchOnlyPackages = decodeSet(this[KEYS.watchOnlyPackages]),
        excludedPackages = decodeSet(this[KEYS.excludedPackages]),
        minimumClickIntervalMs = (this[KEYS.minimumClickIntervalMs] ?: 1_200).toLong(),
        maxClicksPerWindowPerMinute = this[KEYS.maxClicksPerWindowPerMinute] ?: 3,
        stopAfterDangerousMisclicks = this[KEYS.stopAfterDangerousMisclicks] ?: 2,
        eventRetentionDays = this[KEYS.eventRetentionDays] ?: 7,
        logObservationEvents = this[KEYS.logObservationEvents] ?: false,
    )

    private object KEYS {
        val autoSkipMasterSwitch = booleanPreferencesKey("auto_skip_master_switch")
        val mode = stringPreferencesKey("mode")
        val learningEnabled = booleanPreferencesKey("learning_enabled")
        val showNotifications = booleanPreferencesKey("show_notifications")
        val watchOnlyPackages = stringPreferencesKey("watch_only_packages")
        val excludedPackages = stringPreferencesKey("excluded_packages")
        val minimumClickIntervalMs = intPreferencesKey("minimum_click_interval_ms")
        val maxClicksPerWindowPerMinute = intPreferencesKey("max_clicks_per_window_per_minute")
        val stopAfterDangerousMisclicks = intPreferencesKey("stop_after_dangerous_misclicks")
        val eventRetentionDays = intPreferencesKey("event_retention_days")
        val logObservationEvents = booleanPreferencesKey("log_observation_events")
    }

    private companion object {
        /** Unit separator: cannot appear in an Android package name. */
        const val SET_SEPARATOR = "\u001F"
    }
}

/**
 * One DataStore per process, tied to the application context.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "smartskip_preferences",
)
