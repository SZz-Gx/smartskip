package io.github.szzgx.smartskip.analysis

import io.github.szzgx.smartskip.analysis.TestFixtures.assess
import io.github.szzgx.smartskip.analysis.TestFixtures.candidate
import io.github.szzgx.smartskip.analysis.TestFixtures.decide
import io.github.szzgx.smartskip.analysis.TestFixtures.probe
import io.github.szzgx.smartskip.analysis.TestFixtures.safeHistory
import io.github.szzgx.smartskip.analysis.TestFixtures.window
import io.github.szzgx.smartskip.analysis.history.HistoryEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safety-critical tests for the scoring engine and the decision gates.
 *
 * The organising principle of this file is design doc §3.2: *"宁可漏掉，不要误点"* —
 * preferring a missed ad over a wrong tap. So the "must not click" cases are the primary
 * subject, and the "should click" cases exist to prove the engine is not simply refusing
 * everything.
 */
class AssessmentScorerTest {

    // ---------------------------------------------------------------------------
    // The happy path, which must actually work
    // ---------------------------------------------------------------------------

    @Test
    fun `a textbook splash skip button becomes a candidate but is only observed on first sight`() {
        val verdict = decide(candidate())

        assertTrue("a real skip button must at least be a candidate", verdict.score >= 70)
        assertFalse(
            "an app that has never been handled must not be tapped unattended",
            verdict.shouldClick,
        )
        assertTrue(
            "the explanation must say that this is observation-only: ${verdict.reason}",
            verdict.reason.contains("仅观察"),
        )
        assertTrue(
            "the impossible threshold is what enforces observation-only mode",
            verdict.effectiveThreshold > 100,
        )
    }

    @Test
    fun `a proven rule is strong enough to act on`() {
        val verdict = decide(
            candidate = candidate(),
            history = safeHistory(successCount = 4, confidence = 0.9f, isNewPackageOrActivity = false),
        )

        assertTrue("a repeatedly successful rule should be actionable", verdict.shouldClick)
    }

    @Test
    fun `a user-confirmed rule clears the bar even on first sight`() {
        val verdict = decide(
            candidate = candidate(),
            history = safeHistory(userConfirmed = true, isNewPackageOrActivity = true),
        )

        assertTrue(verdict.shouldClick)
    }

    @Test
    fun `no single feature can carry a click on its own`() {
        // Exact skip text is the single strongest text feature, and on its own it must
        // stay below the lowest threshold in any mode.
        val lone = assess(
            candidate = candidate(
                ownText = "跳过",
                // Strip every other positive feature: no clickability history, no size
                // stability, no button-like leaf shape, no launcher activity.
                className = "android.view.ViewGroup",
                sameSizeSamples = 0,
                clickabilityEverObserved = false,
                leavesUnderNode = 40,
                isClickable = false,
                clickableAncestorDepth = null,
            ),
            history = HistoryEvidence.UNKNOWN,
        )

        val aggressiveFloor = decide(
            candidate = candidate(),
            mode = AutoSkipMode.AGGRESSIVE,
        ).effectiveThreshold

        assertTrue(
            "keyword-only evidence scored ${lone.score}; it must not reach $aggressiveFloor",
            lone.score < aggressiveFloor,
        )
    }

    // ---------------------------------------------------------------------------
    // Hard vetoes: positive evidence must not be able to outvote these
    // ---------------------------------------------------------------------------

    @Test
    fun `an editable field is vetoed even with perfect skip wording`() {
        val assessment = assess(candidate(isEditable = true))
        assertTrue(VetoReason.EDITABLE_FIELD in assessment.vetoes)
        assertTrue(assessment.isVetoed)
    }

    @Test
    fun `a payment surface vetoes the click`() {
        val assessment = assess(
            candidate = candidate(),
            probe = probe(hasPaymentContext = true),
        )
        assertTrue(VetoReason.PAYMENT_OR_CREDENTIAL in assessment.vetoes)
    }

    @Test
    fun `dangerous wording on the control itself is a veto`() {
        val assessment = assess(candidate(ownText = "立即下载"))
        assertTrue(VetoReason.STRONG_DANGER_TEXT in assessment.vetoes)
    }

    @Test
    fun `skip wording combined with inducement wording is treated as bait`() {
        // The signature dark pattern: "跳过" that actually claims a reward.
        val assessment = assess(candidate(ownText = "跳过", siblingText = "免费领取红包"))
        assertTrue(VetoReason.SKIP_WORDING_BAIT in assessment.vetoes)
        assertFalse(decide(candidate(ownText = "跳过", siblingText = "免费领取红包")).shouldClick)
    }

    @Test
    fun `a bait label combining skip and inducement in one string is vetoed, not merely outscored`() {
        /*
         * Regression test for a real defect.
         *
         * The adversarial harness on a device presented a control labelled "跳过领取". It was
         * refused, but only because it scored 22 — the inducement check required the whole label to
         * equal an entry in INDUCEMENT, and "跳过领取" does not equal "领取". So the veto never
         * fired and the refusal was a coincidence of arithmetic.
         *
         * That is not good enough for this project's central threat: a bait button scoring a little
         * higher would have passed, and the first-sight threshold that also stood in the way is
         * precisely what rule learning is designed to lower.
         */
        val bait = candidate(ownText = "跳过领取")
        val assessment = assess(bait)

        assertTrue(
            "a label carrying both skip wording and an inducement must be vetoed, not just scored low",
            VetoReason.SKIP_WORDING_BAIT in assessment.vetoes,
        )
        assertTrue(assessment.isVetoed)

        // And it must stay refused even with a proven, user-confirmed rule behind it.
        val verdict = decide(
            candidate = bait,
            history = safeHistory(userConfirmed = true, successCount = 9),
        )
        assertFalse("no amount of history may authorise tapping bait", verdict.shouldClick)
    }

    @Test
    fun `inducement on a neighbouring node also vetoes, because that is the usual bait layout`() {
        /*
         * Found on a device with the adversarial harness: a fixture with own text "跳过" and a
         * sibling "领取红包" produced `[SKIP_EXACT, INDUCEMENT]` yet no veto, because the inducement
         * lived on the sibling and the bait test required both signals to be on the node itself.
         *
         * A skip button sitting next to a "免费领取" banner is the most common bait layout there is,
         * so the inducement is read from the whole evidence, not from the node's own label.
         */
        val assessment = assess(candidate(ownText = "跳过", siblingText = "领取红包"))
        assertTrue(VetoReason.SKIP_WORDING_BAIT in assessment.vetoes)

        // Still refused even when a proven, user-confirmed rule backs it.
        assertFalse(
            decide(
                candidate = candidate(ownText = "跳过", siblingText = "领取红包"),
                history = safeHistory(userConfirmed = true, successCount = 9),
            ).shouldClick,
        )
    }

    @Test
    fun `a bait label is vetoed whether it arrives as text or as a contentDescription`() {
        /*
         * Regression test for a real defect found on a device.
         *
         * An icon-only skip button carries its label in `contentDescription` and nowhere else, which
         * is precisely where a fake one would put "跳过领取". The bait check had been written for the
         * `text` branch only, so such a control produced `[SKIP_IN_CONTENT_DESC]` alone and no veto —
         * the label was visible to the engine and simply not examined.
         */
        val asText = assess(candidate(ownText = "跳过领取"))
        val asDescription = assess(candidate(ownText = "", ownContentDescription = "跳过领取"))

        assertTrue(
            "bait as text must be vetoed",
            VetoReason.SKIP_WORDING_BAIT in asText.vetoes,
        )
        assertTrue(
            "bait as contentDescription must be vetoed too",
            VetoReason.SKIP_WORDING_BAIT in asDescription.vetoes,
        )
    }

    @Test
    fun `a plain skip label is not mistaken for bait`() {
        // The guard above must not fire on a genuine skip button.
        assertFalse(VetoReason.SKIP_WORDING_BAIT in assess(candidate(ownText = "跳过")).vetoes)
        assertFalse(VetoReason.SKIP_WORDING_BAIT in assess(candidate(ownText = "跳过广告")).vetoes)
        assertFalse(VetoReason.SKIP_WORDING_BAIT in assess(candidate(ownText = "跳过此广告")).vetoes)
    }

    @Test
    fun `a control that is not actionable is vetoed`() {
        val assessment = assess(
            candidate(isClickable = false, clickableAncestorDepth = null),
        )
        assertTrue(VetoReason.NOT_ACTIONABLE in assessment.vetoes)
    }

    @Test
    fun `an invisible control is vetoed`() {
        val assessment = assess(candidate(isVisibleToUser = false))
        assertTrue(VetoReason.NOT_VISIBLE in assessment.vetoes)
    }

    @Test
    fun `a full-screen node claiming to be skip is vetoed`() {
        val assessment = assess(
            candidate(left = 0, top = 0, right = 1080, bottom = 2400),
        )
        assertTrue(VetoReason.IMPLAUSIBLE_SIZE in assessment.vetoes)
    }

    @Test
    fun `a prior dangerous outcome suppresses the control permanently`() {
        val assessment = assess(
            candidate = candidate(),
            dangerous = setOf(io.github.szzgx.smartskip.analysis.risk.DangerousOutcome.OPENED_PAYMENT),
        )
        assertTrue(assessment.isVetoed)
        assertFalse(
            decide(
                candidate = candidate(),
                history = safeHistory(),
                dangerous = setOf(
                    io.github.szzgx.smartskip.analysis.risk.DangerousOutcome.OPENED_PAYMENT,
                ),
            ).shouldClick,
        )
    }

    @Test
    fun `a user-rejected rule is never clicked again`() {
        val verdict = decide(
            candidate = candidate(),
            history = safeHistory(userRejected = true),
        )
        assertFalse(verdict.shouldClick)
    }

    // ---------------------------------------------------------------------------
    // Ad creative must not be mistaken for chrome
    // ---------------------------------------------------------------------------

    @Test
    fun `a node inside the ad creative scores below a node on the chrome`() {
        val onChrome = assess(candidate(), probe = probe(hasAdCreativeOverlap = false))
        val insideCreative = assess(candidate(), probe = probe(hasAdCreativeOverlap = true))

        assertTrue(
            "creative overlap must reduce confidence (chrome=${onChrome.score}, creative=${insideCreative.score})",
            insideCreative.score < onChrome.score,
        )
        assertTrue(insideCreative.contributions.any { it.code == "risk_creative_overlap" })
    }

    @Test
    fun `a centred giant blob is not mistaken for a skip button`() {
        val central = candidate(
            left = 100,
            top = 700,
            right = 980,
            bottom = 1600,
            ownText = "跳过",
        )
        val onChrome = assess(candidate(
            left = 900, top = 120, right = 1020, bottom = 180,
        ))
        val blob = assess(central, probe = probe(candidateIsCentered = true))

        assertTrue(
            "a large centred node must score well below a corner control " +
                "(blob=${blob.score}, chrome=${onChrome.score})",
            blob.score < onChrome.score,
        )
        assertTrue(
            "the centre placement must be reported as a negative risk contribution",
            blob.contributions.any { it.code == "risk_large_center" && it.points < 0 },
        )
    }

    @Test
    fun `a centred control just over the area boundary is penalised and one under it is not`() {
        // Pins the calibration that a 30%-of-screen centred block must be treated as
        // creative. The boundary is deliberately low: a real skip chip occupies a few
        // percent of the screen.
        //
        // 1080x2400 screen, so 15% of the area is 388_800 px.
        val smallCentred = candidate(
            left = 400, top = 1100, right = 680, bottom = 1250, // 280 x 150 = 42_000 (1.6%)
        )
        val largeCentred = candidate(
            left = 100, top = 700, right = 980, bottom = 1600, // 880 x 900 = 792_000 (30.5%)
        )

        val small = assess(smallCentred, probe = probe(candidateIsCentered = true))
        val large = assess(largeCentred, probe = probe(candidateIsCentered = true))

        assertFalse(
            "a small centred control must not be called creative",
            small.contributions.any { it.code == "risk_large_center" },
        )
        assertTrue(
            "a 30%-of-screen centred control must be called creative",
            large.contributions.any { it.code == "risk_large_center" },
        )
        assertTrue(large.score < small.score)
    }

    @Test
    fun `multiple skip-shaped candidates raise suspicion`() {        val single = assess(candidate(), probe = probe(distinctClickableCandidates = 1))
        val many = assess(candidate(), probe = probe(distinctClickableCandidates = 3))
        assertTrue(many.score < single.score)
    }

    @Test
    fun `a video surface labelled skip is penalised`() {
        val video = assess(candidate(className = "android.widget.VideoView"))
        val textView = assess(candidate(className = "android.widget.TextView"))
        assertTrue(video.score < textView.score)
    }

    @Test
    fun `not being hidden from accessibility helps`() {
        val visible = assess(candidate(isImportantForAccessibility = true))
        val hidden = assess(candidate(isImportantForAccessibility = false))
        assertTrue(hidden.score < visible.score)
    }

    // ---------------------------------------------------------------------------
    // Explanation quality: every score must be auditable
    // ---------------------------------------------------------------------------

    @Test
    fun `score equals the sum of its contributions clamped to 0-100`() {
        val assessment = assess(candidate())
        val sum = assessment.contributions.sumOf { it.points }
        assertEquals(sum.coerceIn(0, 100), assessment.score)
        assertTrue(assessment.contributions.isNotEmpty())
    }

    @Test
    fun `every contribution carries a non-empty explanation`() {
        val assessment = assess(candidate())
        assessment.contributions.forEach { contribution ->
            assertTrue(contribution.code.isNotBlank())
            assertTrue(
                "contribution ${contribution.code} has no label",
                contribution.label.isNotBlank(),
            )
        }
    }

    @Test
    fun `scoring is deterministic`() {
        val first = assess(candidate())
        val second = assess(candidate())
        assertEquals(first.score, second.score)
        assertEquals(first.contributions, second.contributions)
        assertEquals(first.vetoes, second.vetoes)
    }

    @Test
    fun `bands follow the documented boundaries`() {
        assertEquals(ConfidenceBand.IGNORE, ConfidenceBand.of(0))
        assertEquals(ConfidenceBand.IGNORE, ConfidenceBand.of(29))
        assertEquals(ConfidenceBand.OBSERVE, ConfidenceBand.of(30))
        assertEquals(ConfidenceBand.OBSERVE, ConfidenceBand.of(69))
        assertEquals(ConfidenceBand.CANDIDATE, ConfidenceBand.of(70))
        assertEquals(ConfidenceBand.CANDIDATE, ConfidenceBand.of(89))
        assertEquals(ConfidenceBand.HIGH, ConfidenceBand.of(90))
        assertEquals(ConfidenceBand.HIGH, ConfidenceBand.of(100))
    }

    @Test
    fun `a late skip control is penalised as probably not an ad`() {
        val early = assess(candidate(), window = window(nowMs = 10_000, windowAppearedAtMs = 9_000))
        val late = assess(
            candidate(),
            window = window(
                nowMs = 60_000,
                windowAppearedAtMs = 5_000,
                foregroundSinceMs = 5_000,
            ),
        )
        assertTrue(
            "a control seen a minute in should score lower than one at launch",
            late.score < early.score,
        )
    }

    @Test
    fun `the effective threshold is always reported even when the click is refused`() {
        val verdict = decide(candidate())
        assertFalse(verdict.shouldClick)
        assertEquals(
            "the refusal must still report the threshold it was measured against",
            io.github.szzgx.smartskip.decision.DecisionEngine.FIRST_SIGHT_OBSERVATION_THRESHOLD,
            verdict.effectiveThreshold,
        )
        assertTrue(verdict.reason.isNotBlank())
    }

    @Test
    fun `a known rule scores high enough to clear the standard threshold`() {
        // Guards against weights drifting so low that a learned rule can no longer act.
        val assessment = assess(
            candidate = candidate(),
            history = safeHistory(successCount = 3, confidence = 0.8f),
        )
        val standard = io.github.szzgx.smartskip.decision.DecisionEngine
            .thresholdsFor(AutoSkipMode.STANDARD)
            .clickThreshold

        assertTrue(
            "a proven rule scored ${assessment.score}, below the standard threshold $standard",
            assessment.score >= standard,
        )
    }
}
