package io.github.szzgx.smartskip.analysis.activity

/**
 * Activity-level evidence.
 *
 * The activity name is the single cheapest signal that distinguishes a splash
 * *ad* surface from the app's real UI: splash ads are usually rendered by a
 * dedicated transparent or ad-hosting activity, or by the launcher activity
 * before it has swapped in its real content.
 *
 * @property isLauncherishActivity the activity name looks like an entry point.
 * @property isAdNamedActivity the activity name contains advertising vocabulary.
 * @property isWebViewSurface the window hosts a WebView, a common ad container.
 * @property isKnownSafeActivity the activity previously produced a successful skip.
 * @property isKnownDangerousActivity the activity previously produced a dangerous
 *   mis-click; automatic clicking is suppressed there.
 */
data class ActivityEvidence(
    val isLauncherishActivity: Boolean,
    val isAdNamedActivity: Boolean,
    val isWebViewSurface: Boolean,
    val isKnownSafeActivity: Boolean,
    val isKnownDangerousActivity: Boolean,
)

/**
 * Classifies the current activity.
 */
object ActivityAnalyzer {

    /**
     * Activity-name fragments that indicate an entry point or a splash surface.
     * `SplashActivity` is included deliberately: many apps host the ad in an
     * activity literally named for the splash screen.
     */
    private val LAUNCHERISH_HINTS = listOf(
        "splash", "launch", "welcome", "entry", "start", "main", "loading", "boot",
    )

    /** Activity-name fragments that identify an advertising surface. */
    private val AD_NAME_HINTS = listOf(
        "adactivity", "ad_activity", "adsactivity", "advert", "interstitial",
        "rewardvideo", "reward_video", "splashad", "splash_ad", "adshow",
        "nativead", "adcontainer", "adsdk", "union", "toutiao", "gdt", "ksad",
        "tencentad", "baiduad", "mimo",
    )

    /** Class names that imply an embedded browser. */
    private val WEBVIEW_HINTS = listOf(
        "webview", "chromium", "browser", "x5webview", "tbswebview",
    )

    fun analyze(
        activityName: String?,
        rootClassName: String?,
        isKnownSafeActivity: Boolean,
        isKnownDangerousActivity: Boolean,
    ): ActivityEvidence {
        val activity = activityName?.lowercase().orEmpty()
        val root = rootClassName?.lowercase().orEmpty()

        return ActivityEvidence(
            isLauncherishActivity = LAUNCHERISH_HINTS.any { it in activity },
            isAdNamedActivity = AD_NAME_HINTS.any { it in activity },
            isWebViewSurface = WEBVIEW_HINTS.any { it in activity } || WEBVIEW_HINTS.any { it in root },
            isKnownSafeActivity = isKnownSafeActivity,
            isKnownDangerousActivity = isKnownDangerousActivity,
        )
    }
}
