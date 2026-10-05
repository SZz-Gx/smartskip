package io.github.szzgx.smartskip.analysis.history

/**
 * Evidence derived from what SmartSkip has already learned about this exact
 * control on this device.
 *
 * Design doc §9 and Phase 4 describe the progression "first time: observe,
 * second time: assist, third time: automatic". This type is where that
 * progression is expressed numerically.
 *
 * @property knownSignature whether a stored rule matches this control's structure
 *   signature inside this package and activity.
 * @property confidence the stored confidence of the matching rule, `0f..1f`.
 * @property successCount confirmed successful skips for this signature.
 * @property failureCount confirmed wrong taps for this signature.
 * @property isUserConfirmed the user explicitly confirmed this rule.
 * @property isUserRejected the user explicitly marked this rule as wrong, which
 *   must suppress future automatic clicks even if the geometric score is high.
 * @property isNewPackageOrActivity this package/activity has never been seen, so
 *   SmartSkip is in cautious observation mode for it.
 */
data class HistoryEvidence(
    val knownSignature: Boolean,
    val confidence: Float,
    val successCount: Int,
    val failureCount: Int,
    val isUserConfirmed: Boolean,
    val isUserRejected: Boolean,
    val isNewPackageOrActivity: Boolean,
) {
    companion object {
        /** No history at all: a control never seen on this device. */
        val UNKNOWN = HistoryEvidence(
            knownSignature = false,
            confidence = 0f,
            successCount = 0,
            failureCount = 0,
            isUserConfirmed = false,
            isUserRejected = false,
            isNewPackageOrActivity = true,
        )
    }
}
