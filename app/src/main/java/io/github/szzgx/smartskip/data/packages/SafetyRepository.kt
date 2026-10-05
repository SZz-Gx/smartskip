package io.github.szzgx.smartskip.data.packages

import io.github.szzgx.smartskip.data.db.PackageStateDao
import io.github.szzgx.smartskip.data.db.PackageStateEntity
import io.github.szzgx.smartskip.safety.CircuitBreaker
import io.github.szzgx.smartskip.safety.PackageSafety
import io.github.szzgx.smartskip.safety.VerificationResult

/**
 * Per-package safety state, cached for the decision path and persisted for the user.
 *
 * The circuit breaker is the mechanism that keeps SmartSkip's central promise
 * ("宁可漏掉一个广告，也不要替用户误点一次危险按钮"): once it has caused a harmful tap
 * in an app, it stops acting on that app until the user says otherwise. Keeping that
 * state on disk means the promise survives a reboot and a force-stop, rather than
 * being a process-lifetime flag.
 */
class SafetyRepository(
    private val dao: PackageStateDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val cache = HashMap<String, PackageSafety>(32)

    /** Human-readable labels, best-effort, used by the UI. */
    private val labels = HashMap<String, String>(32)

    fun prime(states: List<PackageStateEntity>) {
        cache.clear()
        labels.clear()
        states.forEach { entity ->
            cache[entity.packageName] = entity.toSafety()
            entity.humanLabel?.let { labels[entity.packageName] = it }
        }
    }

    /** Non-suspending read for the decision path. */
    fun safetyOf(packageName: String): PackageSafety =
        cache[packageName] ?: PackageSafety(packageName = packageName)

    /**
     * Records what the verifier concluded about a tap, updating the circuit breaker.
     *
     * @param stopAfterDangerousMisclicks the user's configured tolerance.
     * @param wasSuspendedByUs whether SmartSkip acted automatically; a manual skip by
     *   the user is not attributable to SmartSkip and must not trip the breaker.
     * @return the new state.
     */
    suspend fun recordOutcome(
        packageName: String,
        result: VerificationResult,
        stopAfterDangerousMisclicks: Int,
    ): PackageSafety {
        val previous = safetyOf(packageName)
        val updated = CircuitBreaker.afterOutcome(previous, result, stopAfterDangerousMisclicks)
        cache[packageName] = updated
        persist(packageName, updated)
        return updated
    }

    /** Notes that an app was encountered, so the UI can list it. */
    suspend fun touch(packageName: String, label: String?) {
        val now = clock()
        val existing = cache[packageName]
        if (existing == null) {
            val fresh = PackageSafety(packageName = packageName)
            cache[packageName] = fresh
            label?.let { labels[packageName] = it }
            dao.upsert(
                PackageStateEntity(
                    packageName = packageName,
                    humanLabel = label,
                    dangerousMisclickCount = 0,
                    consecutiveFailureCount = 0,
                    isAutoSkipSuspended = false,
                    suspensionReason = null,
                    suspendedAtMs = null,
                    observationOnly = false,
                    firstSeenMs = now,
                    lastSeenMs = now,
                ),
            )
        } else {
            label?.let { labels[packageName] = it }
            persist(packageName, existing, lastSeenMs = now)
        }
    }

    /**
     * Lifts a suspension at the user's explicit request.
     *
     * The dangerous-mis-click tally is kept by default, so the UI can still show the
     * history that caused the suspension. Forgetting it silently would make the app
     * look like it had never misbehaved.
     */
    suspend fun clearSuspension(packageName: String) {
        val previous = safetyOf(packageName)
        val updated = CircuitBreaker.clearSuspension(previous)
        cache[packageName] = updated
        persist(packageName, updated)
        dao.clearSuspension(packageName)
    }

    fun labelFor(packageName: String): String? = labels[packageName]

    fun cachedStates(): Map<String, PackageSafety> = cache.toMap()

    private suspend fun persist(
        packageName: String,
        safety: PackageSafety,
        lastSeenMs: Long = clock(),
    ) {
        val existing = dao.find(packageName)
        dao.upsert(
            PackageStateEntity(
                packageName = packageName,
                humanLabel = labels[packageName] ?: existing?.humanLabel,
                dangerousMisclickCount = safety.dangerousMisclickCount,
                consecutiveFailureCount = safety.consecutiveFailureCount,
                isAutoSkipSuspended = safety.isSuspended,
                suspensionReason = safety.suspensionReason,
                suspendedAtMs = if (safety.isSuspended) {
                    existing?.suspendedAtMs ?: clock()
                } else {
                    null
                },
                observationOnly = existing?.observationOnly ?: false,
                firstSeenMs = existing?.firstSeenMs ?: clock(),
                lastSeenMs = lastSeenMs,
            ),
        )
    }

    private fun PackageStateEntity.toSafety(): PackageSafety = PackageSafety(
        packageName = packageName,
        dangerousMisclickCount = dangerousMisclickCount,
        consecutiveFailureCount = consecutiveFailureCount,
        isSuspended = isAutoSkipSuspended,
        suspensionReason = suspensionReason,
        needsUserAcknowledgement = isAutoSkipSuspended,
    )
}
