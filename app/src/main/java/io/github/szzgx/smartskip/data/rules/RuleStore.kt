package io.github.szzgx.smartskip.data.rules

import io.github.szzgx.smartskip.analysis.LastOutcome
import io.github.szzgx.smartskip.analysis.history.HistoryEvidence
import io.github.szzgx.smartskip.analysis.text.TextEvidence
import io.github.szzgx.smartskip.analysis.text.TextObservation
import io.github.szzgx.smartskip.data.db.AppRuleDao
import io.github.szzgx.smartskip.data.db.AppRuleEntity

/**
 * The learned-rule layer (design doc §9 and Phase 4).
 *
 * ## Why there is a cache
 *
 * Rule lookup happens on the accessibility event path, once per candidate, and the
 * decision pipeline must not block on disk. So rules are loaded into an in-memory
 * map at startup and kept in step on every write. Room remains the source of truth;
 * the cache is a read-through mirror with the same content.
 *
 * ## Confidence arithmetic
 *
 * The numbers are chosen so that caution dominates:
 *
 *  * a confirmed skip adds [CONFIDENCE_ON_SUCCESS];
 *  * a failed skip multiplies by [CONFIDENCE_ON_FAILURE];
 *  * a *dangerous* mis-click (browser, store, installer, payment) collapses the
 *    rule to [CONFIDENCE_ON_DANGEROUS] in one step and disables it.
 *
 * One bad tap outweighs many good ones by design. The design doc's primary metric
 * is the dangerous mis-click rate, not the skip rate (§15).
 */
class RuleStore(
    private val dao: AppRuleDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Cache key. A `data class` rather than a concatenated string so that a package
     * name containing the separator character cannot produce a false match.
     */
    private data class Key(val packageName: String, val activityName: String, val signature: String)

    private val cache = HashMap<Key, AppRuleEntity>(64)

    /** Replaces the cache contents. Called at startup and after bulk edits. */
    fun prime(rules: List<AppRuleEntity>) {
        cache.clear()
        rules.forEach { cache[keyOf(it)] = it }
    }

    /**
     * Non-suspending lookup for the decision path.
     *
     * @param activityName the window's class name; `null` is normalised to `""`,
     *   which matches the package-wide rule row.
     */
    fun history(
        packageName: String,
        activityName: String?,
        signature: String,
        isNewPackageOrActivity: Boolean,
    ): HistoryEvidence {
        val rule = cache[Key(packageName, activityName.orEmpty(), signature)]
            ?: cache[Key(packageName, "", signature)]

        if (rule == null) {
            return HistoryEvidence.UNKNOWN.copy(isNewPackageOrActivity = isNewPackageOrActivity)
        }

        return HistoryEvidence(
            knownSignature = rule.isEnabled,
            confidence = rule.confidence,
            successCount = rule.successCount,
            failureCount = rule.failureCount,
            isUserConfirmed = rule.userConfirmed,
            isUserRejected = rule.userRejected,
            isNewPackageOrActivity = isNewPackageOrActivity && !rule.userConfirmed,
        )
    }

    /** The rule matching a signature, if any. Used to attribute click outcomes. */
    fun find(packageName: String, activityName: String?, signature: String): AppRuleEntity? =
        cache[Key(packageName, activityName.orEmpty(), signature)]
            ?: cache[Key(packageName, "", signature)]

    /**
     * Creates the rule for an observed control, if learning is enabled and no rule
     * exists yet.
     *
     * A brand-new rule starts at [INITIAL_CONFIDENCE] and is *not* user confirmed, so
     * the first-sight penalty still applies and SmartSkip will not act on it
     * unattended until it has either been seen enough times or been confirmed.
     *
     * @return true when a new rule was created.
     */
    suspend fun learnIfNew(
        packageName: String,
        activityName: String?,
        signature: String,
        text: TextEvidence,
        learningEnabled: Boolean,
    ): Boolean {
        if (!learningEnabled) return false
        if (!text.hasSkipWording && !text.isSkipId) return false
        if (find(packageName, activityName, signature) != null) return false

        val now = clock()
        val entity = AppRuleEntity(
            packageName = packageName,
            activityName = activityName.orEmpty(),
            structureSignature = signature,
            confidence = INITIAL_CONFIDENCE,
            successCount = 0,
            failureCount = 0,
            dangerousFailureCount = 0,
            userConfirmed = false,
            userRejected = false,
            isEnabled = true,
            textObservation = encode(TextObservation.from(text)),
            firstSeenMs = now,
            lastUsedMs = now,
        )

        dao.insert(entity)
        cache[keyOf(entity)] = entity
        return true
    }

    /**
     * Applies a verification outcome to the responsible rule.
     *
     * @return the updated rule, or `null` when there was no rule (SmartSkip acted on
     *   a first-sight control, which cannot happen in SAFE mode and only happens in
     *   the other modes once the score clears the raised threshold).
     */
    suspend fun recordOutcome(
        packageName: String,
        activityName: String?,
        signature: String,
        outcome: LastOutcome,
        isDangerous: Boolean,
    ): AppRuleEntity? {
        val existing = find(packageName, activityName, signature) ?: return null
        val now = clock()

        val updated = when {
            isDangerous -> existing.copy(
                confidence = CONFIDENCE_ON_DANGEROUS,
                failureCount = existing.failureCount + 1,
                dangerousFailureCount = existing.dangerousFailureCount + 1,
                // A rule that caused a dangerous tap is switched off outright. It can
                // be re-enabled deliberately from the rules screen, but never
                // silently.
                isEnabled = false,
                lastUsedMs = now,
            )

            outcome == LastOutcome.CONFIRMED_SKIPPED -> existing.copy(
                confidence = (existing.confidence + CONFIDENCE_ON_SUCCESS).coerceAtMost(MAX_CONFIDENCE),
                successCount = existing.successCount + 1,
                lastUsedMs = now,
            )

            outcome.isNegative -> existing.copy(
                confidence = (existing.confidence * CONFIDENCE_ON_FAILURE).coerceAtLeast(MIN_CONFIDENCE),
                failureCount = existing.failureCount + 1,
                lastUsedMs = now,
            )

            // INCONCLUSIVE / NO_OBSERVABLE_CHANGE / UNVERIFIED: record that the rule
            // was used, change nothing else. Treating "we could not tell" as a
            // failure would erode good rules, and treating it as a success would
            // inflate unproven ones.
            else -> existing.copy(lastUsedMs = now)
        }

        dao.update(updated)
        cache[keyOf(updated)] = updated

        if (isDangerous) {
            dao.markUserRejected(
                packageName = updated.packageName,
                activityName = updated.activityName,
                signature = updated.structureSignature,
            )
            cache[keyOf(updated)] = updated.copy(userRejected = true)
        }

        return updated
    }

    suspend fun setEnabled(packageName: String, activityName: String?, signature: String, enabled: Boolean) {
        dao.setEnabled(packageName, activityName.orEmpty(), signature, enabled)
        refresh(packageName, activityName, signature)
    }

    suspend fun confirm(packageName: String, activityName: String?, signature: String) {
        dao.markUserConfirmed(packageName, activityName.orEmpty(), signature, USER_CONFIRMED_CONFIDENCE)
        refresh(packageName, activityName, signature)
    }

    suspend fun reject(packageName: String, activityName: String?, signature: String) {
        dao.markUserRejected(packageName, activityName.orEmpty(), signature)
        refresh(packageName, activityName, signature)
    }

    suspend fun delete(packageName: String, activityName: String?, signature: String) {
        dao.delete(packageName, activityName.orEmpty(), signature)
        cache.remove(Key(packageName, activityName.orEmpty(), signature))
    }

    suspend fun deleteAll() {
        dao.deleteAll()
        cache.clear()
    }

    private suspend fun refresh(packageName: String, activityName: String?, signature: String) {
        val fresh = dao.find(packageName, activityName.orEmpty(), signature)
            ?: dao.rulesForPackage(packageName).firstOrNull { it.structureSignature == signature }
        if (fresh != null) {
            cache[keyOf(fresh)] = fresh
        } else {
            cache.remove(Key(packageName, activityName.orEmpty(), signature))
        }
    }

    private fun keyOf(entity: AppRuleEntity): Key =
        Key(entity.packageName, entity.activityName, entity.structureSignature)

    companion object {
        /** A rule starts just below "candidate": seen, but not yet trusted. */
        const val INITIAL_CONFIDENCE = 0.45f

        /** One confirmed skip is meaningful evidence, so the step is not tiny. */
        const val CONFIDENCE_ON_SUCCESS = 0.07f

        /**
         * A failure multiplies rather than subtracts, so a rule that has already
         * been weakened cannot be weakened below usefulness by one bad run.
         */
        const val CONFIDENCE_ON_FAILURE = 0.45f

        /** A dangerous mis-click is not a small correction. */
        const val CONFIDENCE_ON_DANGEROUS = 0.05f

        const val USER_CONFIRMED_CONFIDENCE = 0.95f
        const val MAX_CONFIDENCE = 0.99f
        const val MIN_CONFIDENCE = 0.02f

        /**
         * Encodes the text observation as a compact, privacy-preserving string.
         * Uses codes, never the visible words (design doc §18).
         */
        fun encode(observation: TextObservation): String = buildList {
            if (observation.skipShaped) add("skip")
            if (observation.onAdSurface) add("ad")
            if (observation.skipIdShaped) add("skipid")
        }.joinToString(",")

        fun decode(encoded: String): TextObservation {
            val parts = encoded.split(',').toSet()
            return TextObservation(
                skipShaped = "skip" in parts,
                onAdSurface = "ad" in parts,
                skipIdShaped = "skipid" in parts,
            )
        }
    }
}
