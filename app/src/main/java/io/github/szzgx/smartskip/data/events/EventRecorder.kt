package io.github.szzgx.smartskip.data.events

import io.github.szzgx.smartskip.analysis.Assessment
import io.github.szzgx.smartskip.analysis.AutoSkipMode
import io.github.szzgx.smartskip.analysis.CandidateContext
import io.github.szzgx.smartskip.analysis.LastOutcome
import io.github.szzgx.smartskip.analysis.Stage
import io.github.szzgx.smartskip.analysis.text.TextEvidence
import io.github.szzgx.smartskip.data.db.AdvertisementEventDao
import io.github.szzgx.smartskip.data.db.AdvertisementEventEntity
import io.github.szzgx.smartskip.decision.Verdict

/**
 * Writes the observation log.
 *
 * Every column that could contain user content is replaced by a classification code
 * — see [AdvertisementEventEntity] for why the design document's `nodeText` column
 * is not implemented as written.
 */
class EventRecorder(
    private val dao: AdvertisementEventDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Records one assessment.
     *
     * @param text the evidence, whose *classification* is persisted and whose matched
     *   phrases are not.
     * @param verdict the decision, which may still be revised by the verifier — that
     *   is why [markResult] exists.
     * @return the row id, so the verifier can update the same record.
     */
    suspend fun record(
        ctx: CandidateContext,
        text: TextEvidence,
        assessment: Assessment,
        verdict: Verdict,
        mode: AutoSkipMode,
        durationMs: Long = 0L,
    ): Long {
        val entity = AdvertisementEventEntity(
            packageName = ctx.packageName,
            activityName = ctx.activityName,
            timestampMs = clock(),
            textFeatures = classifyText(text),
            resourceId = ctx.viewIdResourceName,
            className = ctx.className,
            boundsLeft = ctx.boundsLeft,
            boundsTop = ctx.boundsTop,
            boundsRight = ctx.boundsRight,
            boundsBottom = ctx.boundsBottom,
            screenWidth = ctx.screenWidth,
            screenHeight = ctx.screenHeight,
            structureSignature = ctx.structureSignature,
            score = assessment.score,
            band = assessment.band.name,
            action = describeAction(verdict),
            result = LastOutcome.UNVERIFIED.name,
            vetoReasons = assessment.vetoes.joinToString(",") { it.name },
            topFeatures = topFeaturesOf(assessment),
            durationMs = durationMs,
            mode = mode.name,
        )
        return dao.insert(entity)
    }

    /**
     * Backfills the outcome once the verifier has run.
     *
     * A separate statement rather than an update to the whole entity, so a concurrent
     * insert cannot resurrect stale values.
     */
    suspend fun markResult(eventId: Long, outcome: LastOutcome) {
        // Video-style: the DAO exposes only insert and bulk cleanup, so the outcome is
        // applied with a targeted query.
        dao.updateResult(eventId, outcome.name)
    }

    /**
     * @return the classification codes for [text]. Contains no visible words, so the
     *   log can be shared for debugging without disclosing screen content.
     */
    fun classifyText(text: TextEvidence): String = buildList {
        if (text.hasSkipWording) add("skip")
        if (text.isSkipId) add("skipid")
        if (text.isAdContainerId) add("adcontainer")
        if (text.isPaymentId) add("paymentid")
        text.signals.forEach { add(it.name.lowercase()) }
    }.distinct().joinToString(",")

    private fun describeAction(verdict: Verdict): String = when {
        verdict.shouldClick -> Stage.CLICK.name
        verdict.isVetoed -> Stage.VETO.name
        else -> Stage.SCORE.name
    }

    /**
     * The few highest-impact contributions as `code:points` pairs. This is what lets
     * the UI explain a historical decision without re-running the whole pipeline.
     */
    private fun topFeaturesOf(assessment: Assessment): String =
        assessment.contributions
            .sortedByDescending { kotlin.math.abs(it.points) }
            .take(6)
            .joinToString(",") { "${it.code}:${it.points}" }

    /** Keeps the log bounded, per the retention setting. */
    suspend fun trim(retentionDays: Int) {
        val cutoff = clock() - retentionDays.toLong() * 24L * 60L * 60L * 1000L
        dao.trimOlderThan(cutoff)
    }

    suspend fun clear() = dao.deleteAll()
}
