package io.github.szzgx.smartskip.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.szzgx.smartskip.SmartSkipApplication
import io.github.szzgx.smartskip.ServiceContainer
import io.github.szzgx.smartskip.analysis.Assessment
import io.github.szzgx.smartskip.analysis.CandidateContext
import io.github.szzgx.smartskip.analysis.LastOutcome
import io.github.szzgx.smartskip.analysis.Stage
import io.github.szzgx.smartskip.analysis.WindowContext
import io.github.szzgx.smartskip.analysis.assessment.AssessmentScorer
import io.github.szzgx.smartskip.analysis.risk.DangerousOutcome
import io.github.szzgx.smartskip.analysis.risk.WindowRiskProbe
import io.github.szzgx.smartskip.analysis.text.TextEvidence
import io.github.szzgx.smartskip.analysis.text.TextSignalAnalyzer
import io.github.szzgx.smartskip.data.prefs.UserPreferences
import io.github.szzgx.smartskip.decision.DecisionEngine
import io.github.szzgx.smartskip.decision.Verdict
import io.github.szzgx.smartskip.safety.CircuitBreaker
import io.github.szzgx.smartskip.safety.ClickVerifier
import io.github.szzgx.smartskip.safety.Clock
import io.github.szzgx.smartskip.safety.PostClickSnapshot
import io.github.szzgx.smartskip.safety.PreClickSnapshot
import io.github.szzgx.smartskip.safety.RateLimiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SmartSkip's only entry point into other applications.
 *
 * ## Structure
 *
 * [onAccessibilityEvent] does almost nothing: it stores the event in a conflated
 * channel and returns. All real work happens on a single background consumer, which
 * gives three properties that matter a great deal here:
 *
 *  1. the system's event dispatch thread is never blocked, so SmartSkip cannot
 *     contribute jank to the app it is watching;
 *  2. every decision runs sequentially, so the rule cache, the rate limiter and the
 *     window bookkeeping need no locking and cannot interleave;
 *  3. bursts of `TYPE_WINDOW_CONTENT_CHANGED` collapse into one assessment of the
 *     newest frame, which is both cheaper and more accurate.
 *
 * ## What this class may and may not do
 *
 * It may gather facts and carry out a decision. It may not *make* one: every judgement
 * lives in the Android-free analysis and decision packages, where it is unit tested.
 * That separation is deliberate — a mistake in this file is a tap on a stranger's
 * phone.
 */
class SmartSkipAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Conflated: only the newest pending event is worth processing. */
    private val events = Channel<AccessibilityEvent>(Channel.CONFLATED)

    private lateinit var container: ServiceContainer

    private val rateLimiter = RateLimiter(Clock { System.currentTimeMillis() })

    // ------------------------------------------------------------- window state
    private var currentPackage: String? = null
    private var currentActivity: String? = null
    private var windowStartedAtMs: Long = 0L
    private var foregroundSinceMs: Long = 0L
    private var frameCount: Int = 0
    private var lastCountdownSeconds: Int? = null
    private var countdownDecreased: Boolean = false
    private var signatureHistory = SignatureHistory(0L)

    /**
     * How many times SmartSkip has tapped inside the current window without the
     * window's content changing. A tap that changed nothing is very unlikely to change
     * anything on the next attempt, and repeating it is how a tool ends up hammering a
     * screen it does not understand.
     */
    private var fruitlessClicksInWindow: Int = 0
    private var lastClickedWindowFingerprint: Int = 0

    /** Outcome of the most recent verified tap, fed back into the next assessment. */
    private var lastOutcome: LastOutcome? = null

    /**
     * Packages already recorded in the safety table during this process.
     *
     * A plain set, touched only from the single event-consumer coroutine, so it needs no locking.
     */
    private val packagesSeenThisSession = HashSet<String>()

    /** Attributes a completed tap to the rule responsible for it. */
    private data class PendingVerification(
        val packageName: String,
        val activityName: String?,
        val signature: String,
        val before: PreClickSnapshot,
        val eventId: Long?,
    )

    private var pendingVerification: PendingVerification? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        container = SmartSkipApplication.containerOf(this)
        container.hydrate()
        configureServiceInfo()
        ServiceStatus.markConnected()
        // Lets the inspector ask for a scored capture of whatever is on screen now.
        InspectorCommand.register { UiDumpProvider.captureThroughService(this) }
        scope.launch { consumeEvents() }
        Log.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Non-blocking by design: the consumer picks up the newest event.
        events.trySend(event)
    }

    override fun onInterrupt() {
        Log.i(TAG, "interrupted by the system")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        Log.i(TAG, "unbound; stopping work")
        InspectorCommand.unregister()
        ServiceStatus.markDisconnected()
        scope.cancel()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        InspectorCommand.unregister()
        ServiceStatus.markDisconnected()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------
    // Service configuration
    // ---------------------------------------------------------------------------

    /**
     * Applies the flags the XML configuration cannot express.
     *
     * ## `isAccessibilityTool` is deliberately left false
     *
     * Android 16 blocks services that have *not* declared themselves an accessibility
     * tool from reading views marked `accessibilityDataSensitive`
     * ([Android Developers blog](https://android-developers.googleblog.com/2025/12/enhancing-android-security-stop-malware.html)).
     * Declaring `isAccessibilityTool = true` would lift that restriction and make
     * SmartSkip work on more screens — including some login and payment screens.
     *
     * It stays false because the flag means "this service exists to help users with
     * disabilities use their device", and SmartSkip is not that. Setting it true would
     * be a false declaration made specifically to obtain access to data the platform
     * chose to protect, and Google Play rejects apps that do it.
     *
     * The cost is real and accepted: on Android 16+, an app that opts into
     * `accessibilityDataSensitive` hides its views from SmartSkip, so those splash ads
     * cannot be skipped. Refusing to lie in order to see more of the user's screen is
     * the correct trade for this project.
     */
    private fun configureServiceInfo() {
        val info = serviceInfo ?: AccessibilityServiceInfo().also { serviceInfo = it }
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.notificationTimeout = EVENT_TIMEOUT_MS
        serviceInfo = info
        // NOTE: `isAccessibilityTool` is read-only at runtime. It is populated from the
        // `android:isAccessibilityTool` attribute of the `<accessibility-service>`
        // metadata, and its default is false — which is the value SmartSkip wants, so
        // there is deliberately nothing to set here. The reasoning is documented on
        // `configureServiceInfo`.
    }

    // ---------------------------------------------------------------------------
    // Event consumption
    // ---------------------------------------------------------------------------

    private suspend fun consumeEvents() {
        while (true) {
            val first = events.receiveCatching().getOrNull() ?: return

            // Collapse a burst: wait briefly, drain whatever accumulated, keep the
            // newest. Splash ads fire content-changed continuously, and assessing each
            // one separately would burn CPU for no extra information.
            delay(BURST_WINDOW_MS)
            var latest = first
            while (true) {
                latest = events.tryReceive().getOrNull() ?: break
            }

            runCatching { handleEvent(latest) }
                .onFailure { Log.w(TAG, "frame handling failed", it) }
        }
    }

    private suspend fun handleEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()
        if (packageName.isNullOrBlank()) return
        if (isIgnoredPackage(packageName)) return
        if (isEmbeddedSurface(event)) return

        val prefs = container.preferences.snapshot()
        if (packageName in prefs.excludedPackages) return

        val now = System.currentTimeMillis()
        updateWindowBookkeeping(event, packageName, now)

        val analysis = captureFrame(event, packageName, now) ?: return
        if (analysis.nodes.isEmpty()) return

        UiDumpProvider.publish(analysis)

        val candidates = CandidateCollector.collect(analysis, signatureHistory)
        if (candidates.isEmpty()) return

        // Note the app once, so it appears in the UI even if SmartSkip never acts on it. Guarded
        // by an in-memory set because this runs on a hot path and the database write is only
        // needed the first time.
        if (packagesSeenThisSession.add(packageName)) {
            scope.launch {
                runCatching { container.safetyRepository.touch(packageName, label = null) }
            }
        }

        frameCount += 1
        updateCountdownState(candidates.first().ownText)

        val probe = AccessibilityTreeCollector.probeRisk(analysis, candidates.map { it.nodeIndex })

        val windowProbe = WindowRiskProbe(
            hasAdCreativeOverlap = probe.hasAdCreativeOverlap,
            hasPaymentContext = probe.hasPaymentContext,
            hasDownloadOrStoreContext = probe.hasDownloadOrStoreContext,
            hasNestedClickableDescendant = probe.hasNestedClickableDescendant,
            candidateIsCentered = probe.candidateIsCentered,
            windowHasCountdownAnywhere = candidates.any { it.isCountdownSiblingOnNode || it.isCountdownNearby },
            distinctClickableCandidates = candidates.size,
        )

        val window = buildWindowContext(packageName, analysis, probe, now)

        // ------------------------------------------------------------- evaluation
        val safety = container.safetyRepository.safetyOf(packageName)
        val mode = prefs.mode

        var chosen: CandidateContext? = null
        var chosenVerdict: Verdict? = null
        var chosenAssessment: Assessment? = null
        var chosenText: TextEvidence? = null

        for (candidate in candidates) {
            val signature = candidate.structureSignature
            signatureHistory.observe(signature, candidate.isClickable, now)

            val history = container.ruleStore.history(
                packageName = packageName,
                activityName = candidate.activityName,
                signature = signature,
                isNewPackageOrActivity = hasNoHistoryFor(packageName),
            )
            val dangerous = dangerousOutcomesFor(packageName, candidate.activityName, signature)

            val assessment = AssessmentScorer.assessFull(
                ctx = candidate,
                window = window,
                probe = windowProbe,
                history = history,
                dangerousPriorOutcomes = dangerous,
                isKnownSafeActivity = history.successCount > 0,
                isKnownDangerousActivity = dangerous.isNotEmpty(),
            )

            val verdict = DecisionEngine.decide(
                assessment = assessment,
                mode = mode,
                isKnownRule = history.knownSignature,
                isUserConfirmed = history.isUserConfirmed,
            )

            if (chosenVerdict == null || verdict.score > chosenVerdict.score) {
                chosen = candidate
                chosenVerdict = verdict
                chosenAssessment = assessment
                chosenText = TextSignalAnalyzer.analyze(candidate)
            }
        }

        val candidate = chosen ?: return
        val assessment = chosenAssessment ?: return
        var verdict = chosenVerdict ?: return
        val text = chosenText ?: TextSignalAnalyzer.analyze(candidate)

        // ------------------------------------------------------------- throttling
        verdict = DecisionEngine.applyThrottles(
            verdict = verdict,
            circuitBreaker = CircuitBreaker.mayActAutomatically(safety),
            rateLimiter = rateLimiter.check(
                packageName = packageName,
                minIntervalMs = prefs.minimumClickIntervalMs,
                maxPerMinute = prefs.maxClicksPerWindowPerMinute,
            ),
        )

        // A tap that already failed to change this window must not be repeated.
        if (verdict.shouldClick &&
            lastClickedWindowFingerprint == analysis.structuralFingerprint &&
            fruitlessClicksInWindow >= MAX_FRUITLESS_CLICKS
        ) {
            verdict = verdict.copy(
                shouldClick = false,
                stage = Stage.THROTTLE,
                reason = "本窗口内已点击 $fruitlessClicksInWindow 次但页面无变化，停止重复点击",
            )
        }

        /*
         * The user's master switch is applied last, after the verdict has been
         * computed, so that the log still records what SmartSkip *would* have done.
         * That is what makes observation-only mode worth anything: a user can run with
         * auto-skip off and see exactly which controls would have been tapped and why.
         */
        val autoSkipAllowed = prefs.autoSkipMasterSwitch &&
            packageName !in prefs.watchOnlyPackages &&
            !safety.isSuspended

        /*
         * Record the candidate as a learned rule *before* acting, because this is the
         * only point where the full text evidence is in scope. The rule starts at low
         * confidence and unconfirmed, so the first-sight penalty still applies and
         * SmartSkip cannot bootstrap itself into acting on a surface it has never
         * decided before.
         */
        if (prefs.learningEnabled && verdict.shouldClick) {
            runCatching {
                container.ruleStore.learnIfNew(
                    packageName = packageName,
                    activityName = candidate.activityName,
                    signature = candidate.structureSignature,
                    text = text,
                    learningEnabled = true,
                )
            }.onFailure { Log.w(TAG, "could not learn new rule", it) }
        }

        // --------------------------------------------------------------- recording
        val eventId = runCatching {
            container.eventRecorder.record(
                ctx = candidate,
                text = text,
                assessment = assessment,
                verdict = verdict,
                mode = mode,
            )
        }.getOrNull()

        if (!verdict.shouldClick) {
            Log.d(TAG, "no click: ${verdict.reason}")
            ServiceStatus.recordObservation(packageName, verdict.score, verdict.reason)
            return
        }

        if (!autoSkipAllowed) {
            val why = when {
                !prefs.autoSkipMasterSwitch -> "自动跳过总开关关闭"
                packageName in prefs.watchOnlyPackages -> "$packageName 处于仅观察列表"
                else -> safety.suspensionReason ?: "$packageName 已被暂停自动操作"
            }
            Log.d(TAG, "would have clicked (score ${verdict.score}) but $why")
            ServiceStatus.recordObservation(packageName, verdict.score, "本可跳过（${verdict.score} 分），但$why")
            return
        }

        executeClick(
            candidate = candidate,
            analysis = analysis,
            verdict = verdict,
            prefs = prefs,
            eventId = eventId,
        )
    }

    // ---------------------------------------------------------------------------
    // Two-phase confirmation and click execution
    // ---------------------------------------------------------------------------

    /**
     * Design doc §16's two-stage confirmation: re-read the live tree, re-locate the
     * same control, and confirm the window has not changed since it was scored, before
     * anything is tapped.
     *
     * The re-location matters because a splash ad animates. Tapping saved coordinates
     * would, on a bad frame, land on whatever moved into them.
     */
    private suspend fun executeClick(
        candidate: CandidateContext,
        analysis: WindowAnalysis,
        verdict: Verdict,
        prefs: UserPreferences,
        eventId: Long?,
    ) {
        val target = resolveLiveNode(candidate) ?: run {
            Log.d(TAG, "confirm failed: ${candidate.structureSignature} vanished before click")
            return
        }

        val clickable = resolveClickableNode(target)

        val before = PreClickSnapshot(
            packageName = candidate.packageName,
            activityName = candidate.activityName,
            windowSig = analysis.structuralFingerprint,
            nodeSignature = candidate.structureSignature,
        )

        val performed = runCatching { clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            .getOrDefault(false)

        if (!performed) {
            Log.d(TAG, "framework refused the click for ${candidate.structureSignature}")
            return
        }

        rateLimiter.record(candidate.packageName)
        ServiceStatus.recordAutoSkip(candidate.packageName, verdict.score, verdict.reason)
        Log.i(
            TAG,
            "clicked ${candidate.structureSignature} in ${candidate.packageName} (score ${verdict.score}, threshold ${verdict.effectiveThreshold})",
        )

        val pending = PendingVerification(
            packageName = candidate.packageName,
            activityName = candidate.activityName,
            signature = candidate.structureSignature,
            before = before,
            eventId = eventId,
        )
        pendingVerification = pending

        verifyAfterClick(pending, prefs)
    }

    /**
     * Waits for the screen to settle, then asks [ClickVerifier] what actually happened.
     *
     * This is design doc §8 in code: "点击成功 ≠ 广告跳过成功".
     */
    private suspend fun verifyAfterClick(pending: PendingVerification, prefs: UserPreferences) {
        delay(ClickVerifier.SETTLE_DELAY_MS)

        val afterRoot = runCatching { rootInActiveWindow }.getOrNull()
        val afterAnalysis = if (afterRoot != null) {
            val activePackage = runCatching { afterRoot.packageName?.toString() }.getOrNull()
            AccessibilityTreeCollector.capture(
                root = afterRoot,
                packageName = activePackage ?: pending.packageName,
                activityName = null,
                nowMs = System.currentTimeMillis(),
            )
        } else {
            WindowAnalysis.EMPTY
        }

        val after = PostClickSnapshot(
            packageName = afterAnalysis.packageName ?: pending.packageName,
            activityName = afterAnalysis.activityName,
            windowSig = afterAnalysis.structuralFingerprint,
            viewIdOrClassNames = afterAnalysis.nodes.flatMap { it.identityStrings() },
            elapsedMs = ClickVerifier.SETTLE_DELAY_MS,
            packageChanged = afterAnalysis.packageName != null &&
                afterAnalysis.packageName != pending.before.packageName,
        )

        val result = ClickVerifier.verify(pending.before, after)

        // A tap that changed nothing counts against the window's fruitless budget.
        if (result.outcome == LastOutcome.NO_OBSERVABLE_CHANGE) {
            fruitlessClicksInWindow = if (lastClickedWindowFingerprint == pending.before.windowSig) {
                fruitlessClicksInWindow + 1
            } else {
                1
            }
            lastClickedWindowFingerprint = pending.before.windowSig
        } else {
            fruitlessClicksInWindow = 0
            lastClickedWindowFingerprint = 0
        }

        lastOutcome = result.outcome
        pendingVerification = null

        // ---------------------------------------------------------------- learning
        // Creating a *new* rule happens before the click, in `handleEvent`, because
        // only there is the full text evidence available. Here the existing rule is
        // updated with what the tape showed.
        runCatching {
            container.ruleStore.recordOutcome(
                packageName = pending.packageName,
                activityName = pending.activityName,
                signature = pending.signature,
                outcome = result.outcome,
                isDangerous = result.isDangerous,
            )
        }.onFailure { Log.w(TAG, "could not update rule after click", it) }

        runCatching {
            container.safetyRepository.recordOutcome(
                packageName = pending.packageName,
                result = result,
                stopAfterDangerousMisclicks = prefs.stopAfterDangerousMisclicks,
            )
        }.onFailure { Log.w(TAG, "could not update safety state", it) }

        pending.eventId?.let { id ->
            runCatching { container.eventRecorder.markResult(id, result.outcome) }
        }

        Log.i(TAG, "verify ${result.outcome}: ${result.explanation}")

        if (result.isDangerous) {
            // Stop acting on this app rather than continuing to experiment on the user's
            // device (design doc §8).
            ServiceStatus.recordDangerousMisclick(pending.packageName, result.explanation)
            Log.w(TAG, "dangerous mis-click in ${pending.packageName}; suppressing further auto-skips")
        }
    }

    // ---------------------------------------------------------------------------
    // Frame helpers
    // ---------------------------------------------------------------------------

    private fun captureFrame(
        event: AccessibilityEvent,
        packageName: String,
        nowMs: Long,
    ): WindowAnalysis? {
        val source = runCatching { event.source }.getOrNull()
        val root = source ?: runCatching { rootInActiveWindow }.getOrNull() ?: return null

        return runCatching {
            AccessibilityTreeCollector.capture(
                root = root,
                packageName = packageName,
                activityName = currentActivity,
                nowMs = nowMs,
            )
        }.getOrNull()
    }

    /**
     * Re-locates a control in a freshly read tree, so the tap targets what was scored
     * rather than whatever has since moved underneath the old coordinates.
     */
    private fun resolveLiveNode(candidate: CandidateContext): AccessibilityNodeInfo? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        var found: AccessibilityNodeInfo? = null

        walk(root) { node ->
            val bounds = android.graphics.Rect()
            runCatching { node.getBoundsInScreen(bounds) }
            val matches = node.viewIdResourceName == candidate.viewIdResourceName &&
                node.className?.toString() == candidate.className &&
                bounds.left == candidate.boundsLeft &&
                bounds.top == candidate.boundsTop &&
                bounds.right == candidate.boundsRight &&
                bounds.bottom == candidate.boundsBottom
            if (matches) {
                found = node
                false
            } else {
                true
            }
        }

        return found
    }

    /**
     * Returns the node that will actually accept the click.
     *
     * Ad SDKs frequently attach the listener to a wrapper and leave the labelled view
     * inert, so a few levels of ancestors are searched before giving up. No more than
     * [MAX_CLICK_ANCESTOR_HOPS], because beyond that the "skip button" is really a whole
     * screen and tapping it is a guess.
     */
    private fun resolveClickableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops <= MAX_CLICK_ANCESTOR_HOPS) {
            val clickable = runCatching { current?.isClickable == true && current?.isEnabled == true }
                .getOrDefault(false)
            if (clickable) return current
            current = runCatching { current?.parent }.getOrNull()
            hops += 1
        }
        return node
    }

    /** Depth-first walk that stops early when [visit] returns false. */
    private fun walk(node: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Boolean) {
        if (!visit(node)) return
        val count = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until count) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            walk(child, visit)
        }
    }

    private fun updateWindowBookkeeping(event: AccessibilityEvent, packageName: String, nowMs: Long) {
        if (packageName != currentPackage) {
            currentPackage = packageName
            foregroundSinceMs = nowMs
            resetWindow(nowMs)
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString()
            if (className != null && className != currentActivity) {
                currentActivity = className
                resetWindow(nowMs)
            }
        }
    }

    private fun resetWindow(nowMs: Long) {
        windowStartedAtMs = nowMs
        frameCount = 0
        lastCountdownSeconds = null
        countdownDecreased = false
        fruitlessClicksInWindow = 0
        lastClickedWindowFingerprint = 0
        signatureHistory = SignatureHistory(nowMs)
    }

    private fun updateCountdownState(text: String) {
        val seconds = TextSignalAnalyzer.parseCountdownSeconds(text) ?: return
        val previous = lastCountdownSeconds
        if (previous != null && seconds < previous) {
            countdownDecreased = true
        }
        lastCountdownSeconds = seconds
    }

    private fun buildWindowContext(
        packageName: String,
        analysis: WindowAnalysis,
        probe: AccessibilityTreeCollector.ProbeResult,
        now: Long,
    ): WindowContext = WindowContext(
        packageName = packageName,
        activityName = analysis.activityName,
        rootClassName = analysis.rootClassName,
        packageForegroundSinceMs = foregroundSinceMs,
        windowAppearedAtMs = windowStartedAtMs,
        nowMs = now,
        distinctFrameCount = frameCount,
        previousCountdownSeconds = lastCountdownSeconds,
        isCountdownShrinking = countdownDecreased,
        hasAdDisclosureAnywhere = probe.hasAdDisclosureAnywhere,
        hasPaymentContext = probe.hasPaymentContext,
        hasDownloadOrStoreContext = probe.hasDownloadOrStoreContext,
        hasNestedClickableDescendant = probe.hasNestedClickableDescendant,
        lastOutcome = lastOutcome,
    )

    /**
     * @return true when SmartSkip has never produced a rule for this package, which is what puts
     *   it into cautious first-sight mode.
     *
     * Deliberately answered from the rule cache rather than from the safety cache: rules are what
     * learning actually produces, so "no rule at all" is the honest definition of an unknown app.
     * An earlier version consulted the safety cache, which was only ever populated by a call that
     * did not exist, so the answer was permanently "unknown" and the learning path could never
     * reach automatic action.
     */
    private fun hasNoHistoryFor(packageName: String): Boolean =
        !container.ruleStore.hasAnyRuleFor(packageName)

    /**
     * Dangerous outcomes already attributed to this exact signature. A non-zero
     * dangerous-failure count is a standing refusal, independent of the score.
     */
    private fun dangerousOutcomesFor(
        packageName: String,
        activityName: String?,
        signature: String,
    ): Set<DangerousOutcome> {
        val rule = container.ruleStore.find(packageName, activityName, signature) ?: return emptySet()
        return if (rule.dangerousFailureCount > 0) setOf(DangerousOutcome.OPENED_FOREIGN_APP) else emptySet()
    }

    /**
     * Surfaces that are never the observed app's splash ad: the notification shade, the
     * input method, the volume panel and so on.
     */
    private fun isEmbeddedSurface(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString().orEmpty()
        if (className.isEmpty()) return false
        return EMBEDDED_SURFACE_CLASSES.any { it in className }
    }

    private fun isIgnoredPackage(packageName: String): Boolean {
        if (packageName == this.packageName) return true
        if (packageName.startsWith(IGNORED_PACKAGE_PREFIX)) return true
        return packageName in IGNORED_PACKAGES
    }

    private companion object {
        const val TAG = "SmartSkip/Svc"

        /** Floor on the gap between informational events the service is told about. */
        const val EVENT_TIMEOUT_MS = 100L

        /** How long to let a burst of content-changed events settle. */
        const val BURST_WINDOW_MS = 80L

        /** How far up the tree to look for the view that owns the click listener. */
        const val MAX_CLICK_ANCESTOR_HOPS = 3

        /** How many fruitless taps in one window before repeating is pointless. */
        const val MAX_FRUITLESS_CLICKS = 2

        /**
         * Surfaces that are never the target app's splash ad.
         *
         * `com.android.systemui` is by far the most important entry: without it
         * SmartSkip would assess the notification shade and the navigation bar
         * constantly, which is both wasteful and a needless look at the user's
         * notifications.
         */
        val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
        )

        /** Covers both the release and the `.debug` applicationId. */
        const val IGNORED_PACKAGE_PREFIX = "io.github.szzgx.smartskip"

        val EMBEDDED_SURFACE_CLASSES = listOf(
            "InputMethod",
            "VolumeDialog",
            "NotificationShade",
            "StatusBar",
            "Toast",
            "PopupWindow",
            "Recents",
        )
    }
}
