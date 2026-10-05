package io.github.szzgx.smartskip.decision

import io.github.szzgx.smartskip.analysis.AutoSkipMode
import io.github.szzgx.smartskip.analysis.TestFixtures.assess
import io.github.szzgx.smartskip.analysis.TestFixtures.candidate
import io.github.szzgx.smartskip.analysis.TestFixtures.safeHistory
import io.github.szzgx.smartskip.analysis.history.HistoryEvidence
import io.github.szzgx.smartskip.analysis.position.PositionAnalyzer
import io.github.szzgx.smartskip.analysis.position.ScreenRegion
import io.github.szzgx.smartskip.safety.ThrottleDecision
import io.github.szzgx.smartskip.analysis.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the threshold arithmetic and the ordering of the gates.
 */
class DecisionEngineTest {

    private val allowed = ThrottleDecision(true, Stage.THROTTLE, "ok")
    private val refused = ThrottleDecision(false, Stage.THROTTLE, "too fast")

    private fun verdict(
        mode: AutoSkipMode = AutoSkipMode.STANDARD,
        history: HistoryEvidence = HistoryEvidence.UNKNOWN,
    ) = DecisionEngine.decide(
        assessment = assess(candidate()),
        mode = mode,
        isKnownRule = history.knownSignature,
        isUserConfirmed = history.isUserConfirmed,
    )

    @Test
    fun `safe mode demands more than standard which demands more than aggressive`() {
        val safe = DecisionEngine.thresholdsFor(AutoSkipMode.SAFE).clickThreshold
        val standard = DecisionEngine.thresholdsFor(AutoSkipMode.STANDARD).clickThreshold
        val aggressive = DecisionEngine.thresholdsFor(AutoSkipMode.AGGRESSIVE).clickThreshold

        assertTrue(safe > standard)
        assertTrue(standard > aggressive)
    }

    @Test
    fun `an unknown app can never be clicked, while a known one can`() {
        val unknown = verdict(history = HistoryEvidence.UNKNOWN)
        val known = verdict(history = safeHistory())

        assertEquals(
            "an app with no rule at all must be gated above the maximum possible score",
            DecisionEngine.FIRST_SIGHT_OBSERVATION_THRESHOLD,
            unknown.effectiveThreshold,
        )
        assertFalse(unknown.shouldClick)

        assertTrue(
            "a known rule must lower the bar to something achievable " +
                "(known=${known.effectiveThreshold})",
            known.effectiveThreshold <= 100,
        )
        assertTrue(
            "a known rule must be actionable, otherwise learning achieves nothing",
            known.shouldClick,
        )
    }

    @Test
    fun `trust lowers the threshold in a strict order`() {
        val unknown = verdict(history = HistoryEvidence.UNKNOWN).effectiveThreshold
        val known = verdict(history = safeHistory()).effectiveThreshold
        val confirmed = verdict(
            history = safeHistory(userConfirmed = true, isNewPackageOrActivity = true),
        ).effectiveThreshold

        assertTrue("a learned rule must be trusted more than nothing ($known vs $unknown)", known < unknown)
        assertTrue(
            "a user-confirmed rule must be trusted most ($confirmed vs $known)",
            confirmed <= known,
        )
    }

    @Test
    fun `safe mode cannot auto-click an unknown surface at all`() {
        val result = verdict(mode = AutoSkipMode.SAFE, history = HistoryEvidence.UNKNOWN)
        assertTrue(
            "the safe-mode first-sight threshold must exceed the maximum possible score",
            result.effectiveThreshold > 100,
        )
        assertFalse(result.shouldClick)
    }

    @Test
    fun `every threshold adjustment is reported`() {
        val firstSight = verdict(history = HistoryEvidence.UNKNOWN)
        assertTrue(
            "the observation-only gate must be explained, not silent",
            firstSight.thresholdAdjustments.any { it.delta > 0 },
        )
        assertTrue(
            "the reason must be readable: ${firstSight.reason}",
            firstSight.reason.contains("仅观察"),
        )

        val known = verdict(history = safeHistory())
        assertTrue(
            "the relief for a known rule must be explained",
            known.thresholdAdjustments.any { it.delta < 0 },
        )
    }

    @Test
    fun `a hard veto is decided before the score is considered`() {
        // A vetoed control must refuse even when its score is high.
        val vetoed = DecisionEngine.decide(
            assessment = assess(candidate(isEditable = true)),
            mode = AutoSkipMode.AGGRESSIVE,
            isKnownRule = true,
            isUserConfirmed = true,
        )
        assertFalse(vetoed.shouldClick)
        assertEquals(Stage.VETO, vetoed.stage)
    }

    @Test
    fun `the circuit breaker is applied before the rate limiter`() {
        val base = DecisionEngine.decide(
            assessment = assess(candidate()),
            mode = AutoSkipMode.STANDARD,
            isKnownRule = true,
            isUserConfirmed = true,
        )
        assertTrue("the fixture must be actionable for this test to mean anything", base.shouldClick)

        val blocked = DecisionEngine.applyThrottles(base, circuitBreaker = refused, rateLimiter = allowed)
        assertFalse(blocked.shouldClick)
        assertEquals("too fast", blocked.reason)
    }

    @Test
    fun `throttles cannot turn a refusal into a click`() {
        val refusedVerdict = verdict(history = HistoryEvidence.UNKNOWN)
        assertFalse(refusedVerdict.shouldClick)

        val after = DecisionEngine.applyThrottles(
            refusedVerdict,
            circuitBreaker = allowed,
            rateLimiter = allowed,
        )
        assertFalse(after.shouldClick)
    }

    @Test
    fun `the top contributions are ordered by magnitude`() {
        val assessment = assess(candidate())
        val top = DecisionEngine.topContributions(assessment, limit = 4)
        assertEquals(4, top.size)

        val magnitudes = top.map { kotlin.math.abs(it.points) }
        assertEquals(magnitudes.sortedDescending(), magnitudes)
    }
}

/**
 * Tests for position classification, which is the strongest structural prior in the model.
 */
class PositionAnalyzerTest {

    private fun regionOf(cx: Int, cy: Int): ScreenRegion =
        PositionAnalyzer.analyze(cx.toFloat(), cy.toFloat(), 1080f, 2400f).region

    @Test
    fun `the top-right corner is recognised`() {
        assertEquals(ScreenRegion.TOP_RIGHT, regionOf(1000, 150))
    }

    @Test
    fun `the top-left corner is recognised`() {
        assertEquals(ScreenRegion.TOP_LEFT, regionOf(80, 150))
    }

    @Test
    fun `the centre is recognised`() {
        assertEquals(ScreenRegion.CENTER, regionOf(540, 1200))
    }

    @Test
    fun `the bottom-right corner is recognised`() {
        assertEquals(ScreenRegion.BOTTOM_RIGHT, regionOf(1000, 2300))
    }

    @Test
    fun `corner affinity is near one in a corner and near zero at the centre`() {
        val corner = PositionAnalyzer.analyze(1020f, 120f, 1080f, 2400f)
        val centre = PositionAnalyzer.analyze(540f, 1200f, 1080f, 2400f)

        assertTrue("corner affinity was ${corner.cornerAffinity}", corner.cornerAffinity > 0.6f)
        assertTrue("corner affinity was ${centre.cornerAffinity}", centre.cornerAffinity < 0.2f)
    }

    @Test
    fun `a horizontally centred control is flagged as such`() {
        val centred = PositionAnalyzer.analyze(540f, 400f, 1080f, 2400f)
        assertTrue(centred.isHorizontallyCentered)

        val rightSide = PositionAnalyzer.analyze(1000f, 400f, 1080f, 2400f)
        assertFalse(rightSide.isHorizontallyCentered)
    }

    @Test
    fun `zero-sized windows do not crash the analysis`() {
        val evidence = PositionAnalyzer.analyze(0f, 0f, 0f, 0f)
        assertEquals(ScreenRegion.CENTER, evidence.region)
        assertEquals(0f, evidence.cornerAffinity, 0.0001f)
    }

    @Test
    fun `full-screen is judged by area fraction`() {
        assertFalse(PositionAnalyzer.isFullscreen(0.4f))
        assertTrue(PositionAnalyzer.isFullscreen(0.62f))
        assertTrue(PositionAnalyzer.isFullscreen(1.0f))
    }
}
