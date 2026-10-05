package io.github.szzgx.smartskip.safety

import io.github.szzgx.smartskip.analysis.LastOutcome
import io.github.szzgx.smartskip.analysis.risk.DangerousOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the post-click verifier.
 *
 * Design doc §8 insists that a successful tap and a successful skip are different things.
 * These tests pin down the ordering that makes that true in practice: hazard detection
 * runs *before* any notion of success, so a tap that both dismissed the ad and opened a
 * payment page is recorded as a mis-click.
 */
class ClickVerifierTest {

    private val before = PreClickSnapshot(
        packageName = "com.example.app",
        activityName = "com.example.app.SplashActivity",
        windowSig = 111,
        nodeSignature = "skipview|textview|1,2,3,4|d6",
    )

    private fun after(
        packageName: String? = "com.example.app",
        activityName: String? = "com.example.app.MainActivity",
        windowSig: Int = 222,
        viewIds: List<String> = emptyList(),
        packageChanged: Boolean = false,
        elapsedMs: Long = ClickVerifier.SETTLE_DELAY_MS,
    ) = PostClickSnapshot(
        packageName = packageName,
        activityName = activityName,
        windowSig = windowSig,
        viewIdOrClassNames = viewIds,
        elapsedMs = elapsedMs,
        packageChanged = packageChanged,
    )

    // ---------------------------------------------------------------------------
    // Danger is detected before success
    // ---------------------------------------------------------------------------

    @Test
    fun `opening chrome is a mis-click even though the ad disappeared`() {
        // Both "the window changed" and "we left the app" are true here. The dangerous
        // branch must win, otherwise a paid mis-click would be logged as a success.
        val result = ClickVerifier.verify(
            before,
            after(
                packageName = "com.android.chrome",
                activityName = "org.chromium.chrome.browser.ChromeTabbedActivity",
                packageChanged = true,
            ),
        )

        assertEquals(LastOutcome.SUSPECTED_MISCLICK, result.outcome)
        assertEquals(DangerousOutcome.OPENED_BROWSER, result.dangerousOutcome)
        assertTrue(result.isDangerous)
    }

    @Test
    fun `opening an app store is a mis-click`() {
        val result = ClickVerifier.verify(
            before,
            after(packageName = "com.android.vending", packageChanged = true),
        )
        assertEquals(DangerousOutcome.OPENED_APP_STORE, result.dangerousOutcome)
    }

    @Test
    fun `opening an installer is a mis-click`() {
        val result = ClickVerifier.verify(
            before,
            after(packageName = "com.android.packageinstaller", packageChanged = true),
        )
        assertEquals(DangerousOutcome.OPENED_DOWNLOAD, result.dangerousOutcome)
    }

    @Test
    fun `a payment activity inside the same app is a mis-click`() {
        val result = ClickVerifier.verify(
            before,
            after(activityName = "com.example.app.pay.CashierActivity", packageChanged = false),
        )
        assertEquals(DangerousOutcome.OPENED_PAYMENT, result.dangerousOutcome)
    }

    @Test
    fun `payment-shaped views are a mis-click even without a telltale activity name`() {
        val result = ClickVerifier.verify(
            before,
            after(viewIds = listOf("com.example.app:id/pay_password_input")),
        )
        assertEquals(LastOutcome.SUSPECTED_MISCLICK, result.outcome)
        assertEquals(DangerousOutcome.OPENED_PAYMENT, result.dangerousOutcome)
    }

    @Test
    fun `a permission dialog is a mis-click`() {
        val result = ClickVerifier.verify(
            before,
            after(packageName = "com.android.permissioncontroller", packageChanged = true),
        )
        assertEquals(DangerousOutcome.OPENED_PERMISSION_DIALOG, result.dangerousOutcome)
    }

    @Test
    fun `being taken to an unrelated app is a mis-click`() {
        val result = ClickVerifier.verify(
            before,
            after(packageName = "com.some.other.app", packageChanged = true),
        )
        assertEquals(LastOutcome.SUSPECTED_MISCLICK, result.outcome)
        assertEquals(DangerousOutcome.OPENED_FOREIGN_APP, result.dangerousOutcome)
    }

    // ---------------------------------------------------------------------------
    // Genuine successes
    // ---------------------------------------------------------------------------

    @Test
    fun `an activity change with different content is a confirmed skip`() {
        val result = ClickVerifier.verify(before, after())
        assertEquals(LastOutcome.CONFIRMED_SKIPPED, result.outcome)
        assertNull(result.dangerousOutcome)
        assertTrue(result.outcome.isPositive)
    }

    @Test
    fun `a content change inside the same activity is a confirmed skip`() {
        // Common for splash ads hosted in the launcher activity.
        val result = ClickVerifier.verify(
            before,
            after(activityName = before.activityName, windowSig = 999),
        )
        assertEquals(LastOutcome.CONFIRMED_SKIPPED, result.outcome)
    }

    // ---------------------------------------------------------------------------
    // Nothing happened
    // ---------------------------------------------------------------------------

    @Test
    fun `no change after the settle delay is reported as no observable change`() {
        val result = ClickVerifier.verify(
            before,
            after(activityName = before.activityName, windowSig = before.windowSig),
        )
        assertEquals(LastOutcome.NO_OBSERVABLE_CHANGE, result.outcome)
        assertFalse(result.isDangerous)
    }

    @Test
    fun `sampling too early is inconclusive rather than a failure`() {
        // Reporting a failure here would erode a good rule for no reason.
        val result = ClickVerifier.verify(
            before,
            after(
                activityName = before.activityName,
                windowSig = before.windowSig,
                elapsedMs = ClickVerifier.SETTLE_DELAY_MS - 1,
            ),
        )
        assertEquals(LastOutcome.INCONCLUSIVE, result.outcome)
        assertFalse(result.outcome.isNegative)
    }

    // ---------------------------------------------------------------------------
    // Outcome semantics
    // ---------------------------------------------------------------------------

    @Test
    fun `only dangerous and stuck outcomes count as negative`() {
        assertTrue(LastOutcome.SUSPECTED_MISCLICK.isNegative)
        assertTrue(LastOutcome.STILL_THERE.isNegative)
        assertFalse("inconclusive must not erode a rule", LastOutcome.INCONCLUSIVE.isNegative)
        assertFalse(LastOutcome.NO_OBSERVABLE_CHANGE.isNegative)
        assertFalse(LastOutcome.UNVERIFIED.isNegative)
    }

    @Test
    fun `outcome classification is gated on a real transition`() {
        // Documenting the gate: classifying a destination only makes sense when a tap
        // caused it. Chrome being in the foreground is otherwise meaningless.
        assertNull(OutcomeClassifier.classify("com.example.app", "com.example.app.MainActivity"))
        assertEquals(
            DangerousOutcome.OPENED_BROWSER,
            OutcomeClassifier.classify("com.android.chrome", null),
        )
    }

    @Test
    fun `wechat alone is not classified as dangerous`() {
        // Regression guard: treating WeChat or Alipay as inherently dangerous would fire
        // constantly, since users legitimately have them in the foreground.
        assertNull(OutcomeClassifier.classify("com.tencent.mm", null))
        assertNull(OutcomeClassifier.classify("com.eg.android.AlipayGphone", null))
    }
}
