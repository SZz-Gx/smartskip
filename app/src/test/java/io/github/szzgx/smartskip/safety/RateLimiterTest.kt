package io.github.szzgx.smartskip.safety

import io.github.szzgx.smartskip.analysis.LastOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the tap rate limiter and the per-package circuit breaker.
 *
 * Both use an injected [Clock] so that time-dependent safety behaviour is asserted rather
 * than assumed. A throttle that has only been reasoned about is not a throttle.
 */
class RateLimiterTest {

    /** A clock the test moves by hand. */
    private class FakeClock(var now: Long = 1_000_000L) : Clock {
        override fun nowMs(): Long = now

        fun advance(ms: Long) {
            now += ms
        }
    }

    private val minInterval = 1_200L
    private val maxPerMinute = 3

    @Test
    fun `the first tap is allowed`() {
        val limiter = RateLimiter(FakeClock())
        assertTrue(limiter.check("com.example.app", minInterval, maxPerMinute).allowed)
    }

    @Test
    fun `a second tap inside the minimum interval is refused`() {
        val clock = FakeClock()
        val limiter = RateLimiter(clock)
        limiter.record("com.example.app")

        clock.advance(minInterval - 1)
        val decision = limiter.check("com.example.app", minInterval, maxPerMinute)
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("最小间隔"))
    }

    @Test
    fun `a second tap after the minimum interval is allowed`() {
        val clock = FakeClock()
        val limiter = RateLimiter(clock)
        limiter.record("com.example.app")

        clock.advance(minInterval)
        assertTrue(limiter.check("com.example.app", minInterval, maxPerMinute).allowed)
    }

    @Test
    fun `the per-package burst ceiling is enforced`() {
        val clock = FakeClock()
        val limiter = RateLimiter(clock)

        repeat(maxPerMinute) {
            assertTrue(limiter.check("com.example.app", minInterval, maxPerMinute).allowed)
            limiter.record("com.example.app")
            clock.advance(minInterval)
        }

        val decision = limiter.check("com.example.app", minInterval, maxPerMinute)
        assertFalse("the ${maxPerMinute}rd tap in a minute must be refused", decision.allowed)
    }

    @Test
    fun `the burst ceiling expires with the sliding window`() {
        val clock = FakeClock()
        val limiter = RateLimiter(clock)
        repeat(maxPerMinute) {
            limiter.record("com.example.app")
            clock.advance(minInterval)
        }

        // Move past the one-minute window.
        clock.advance(61_000)
        assertTrue(limiter.check("com.example.app", minInterval, maxPerMinute).allowed)
    }

    @Test
    fun `the burst ceiling is per package`() {
        val clock = FakeClock()
        val limiter = RateLimiter(clock)
        repeat(maxPerMinute) {
            limiter.record("com.example.app")
            clock.advance(minInterval)
        }

        assertTrue(
            "a different app must have its own budget",
            limiter.check("com.other.app", minInterval, maxPerMinute).allowed,
        )
    }

    @Test
    fun `reset clears all history`() {
        val clock = FakeClock()
        val limiter = RateLimiter(clock)
        limiter.record("com.example.app")
        limiter.reset()
        assertTrue(limiter.check("com.example.app", minInterval, maxPerMinute).allowed)
    }
}

/**
 * Tests for the circuit breaker, which is where SmartSkip's central promise is enforced.
 */
class CircuitBreakerTest {

    private fun safe(packageName: String = "com.example.app") = PackageSafety(packageName = packageName)

    private fun dangerous() = VerificationResult(
        outcome = LastOutcome.SUSPECTED_MISCLICK,
        dangerousOutcome = io.github.szzgx.smartskip.analysis.risk.DangerousOutcome.OPENED_PAYMENT,
        explanation = "payment page",
    )

    private fun failed() = VerificationResult(
        outcome = LastOutcome.STILL_THERE,
        dangerousOutcome = null,
        explanation = "ad still on screen",
    )

    private fun skipped() = VerificationResult(
        outcome = LastOutcome.CONFIRMED_SKIPPED,
        dangerousOutcome = null,
        explanation = "skipped",
    )

    private fun inconclusive() = VerificationResult(
        outcome = LastOutcome.INCONCLUSIVE,
        dangerousOutcome = null,
        explanation = "too early",
    )

    @Test
    fun `a fresh package may act automatically`() {
        assertTrue(CircuitBreaker.mayActAutomatically(safe()).allowed)
    }

    @Test
    fun `a dangerous mis-click suspends the package at the configured tolerance`() {
        val once = CircuitBreaker.afterOutcome(safe(), dangerous(), stopAfterDangerousMisclicks = 1)
        assertTrue(once.isSuspended)
        assertTrue(once.needsUserAcknowledgement)
        assertFalse(CircuitBreaker.mayActAutomatically(once).allowed)
    }

    @Test
    fun `a higher tolerance requires repeated dangerous mis-clicks`() {
        val once = CircuitBreaker.afterOutcome(safe(), dangerous(), stopAfterDangerousMisclicks = 2)
        assertFalse("one strike should not suspend at tolerance 2", once.isSuspended)
        assertEquals(1, once.dangerousMisclickCount)

        val twice = CircuitBreaker.afterOutcome(once, dangerous(), stopAfterDangerousMisclicks = 2)
        assertTrue("the second strike must suspend", twice.isSuspended)
    }

    @Test
    fun `a success resets consecutive failures`() {
        val failedOnce = CircuitBreaker.afterOutcome(safe(), failed(), 2)
        assertEquals(1, failedOnce.consecutiveFailureCount)
        val recovered = CircuitBreaker.afterOutcome(failedOnce, skipped(), 2)
        assertEquals(0, recovered.consecutiveFailureCount)
    }

    @Test
    fun `an inconclusive outcome changes nothing`() {
        val before = safe()
        val after = CircuitBreaker.afterOutcome(before, inconclusive(), 2)
        assertEquals(before.consecutiveFailureCount, after.consecutiveFailureCount)
        assertEquals(before.dangerousMisclickCount, after.dangerousMisclickCount)
        assertFalse(after.isSuspended)
    }

    @Test
    fun `a suspension outlives an unrelated success`() {
        val suspended = CircuitBreaker.afterOutcome(safe(), dangerous(), 1)
        val afterSuccess = CircuitBreaker.afterOutcome(suspended, skipped(), 1)
        assertTrue(
            "a later successful tap must not silently lift a suspension",
            afterSuccess.isSuspended,
        )
    }

    @Test
    fun `clearing a suspension keeps the dangerous history by default`() {
        val suspended = CircuitBreaker.afterOutcome(safe(), dangerous(), 1)
        val cleared = CircuitBreaker.clearSuspension(suspended)

        assertFalse(cleared.isSuspended)
        assertTrue("the UI must still be able to explain why it happened", cleared.dangerousMisclickCount > 0)
    }

    @Test
    fun `clearing a suspension can also forget the history explicitly`() {
        val suspended = CircuitBreaker.afterOutcome(safe(), dangerous(), 1)
        val cleared = CircuitBreaker.clearSuspension(suspended, keepDangerousHistory = false)
        assertEquals(0, cleared.dangerousMisclickCount)
    }
}
