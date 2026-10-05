package io.github.szzgx.smartskip.safety

import io.github.szzgx.smartskip.analysis.risk.DangerousOutcome

/**
 * Conservative knowledge about where a tap can take you.
 *
 * Every entry here exists to answer one question: *if the screen changed to this,
 * was the tap a mistake?* The lists deliberately err towards calling something
 * dangerous, because the cost of a false "dangerous" is one missed ad skip, while
 * the cost of a false "safe" is a tap in someone's banking app.
 *
 * Package names are matched as prefixes so that regional and staged builds
 * (`com.android.chrome`, `com.android.chrome.beta`) all resolve.
 */
object OutcomeClassifier {

    /** Packages that mean "an external browser was opened". */
    private val BROWSER_PACKAGES = listOf(
        "com.android.chrome",
        "com.android.browser",
        "com.chrome.beta",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.quark.browser",
        "com.UCMobile",
        "com.tencent.mtt",
        "com.baidu.searchbox",
        "com.heytap.browser",
        "com.vivo.browser",
        "com.miui.browser",
        "com.huawei.browser",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.kiwibrowser.browser",
        "com.brave.browser",
        "mark.via",
    )

    /** Packages that mean "an app store listing was opened". */
    private val APP_STORE_PACKAGES = listOf(
        "com.android.vending",
        "com.google.android.finsky",
        "com.xiaomi.market",
        "com.heytap.market",
        "com.oppo.market",
        "com.bbk.appstore",
        "com.huawei.appmarket",
        "com.sec.android.app.samsungapps",
        "com.tencent.android.qqdownloader",
        "com.qihoo.appstore",
        "com.baidu.appsearch",
        "com.taobao.appcenter",
        "com.lenovo.leos.appstore",
        "com.coolapk.market",
        "com.wandoujia.phoenix2",
    )

    /** Packages or activities that mean "an installer / download flow was opened". */
    private val DOWNLOAD_PACKAGES = listOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.providers.downloads",
        "com.android.documentsui",
        "com.miui.packageinstaller",
        "com.samsung.android.packageinstaller",
    )

    /**
     * Activities that mean "a payment page was opened".
     *
     * Ordered longest-first so that each pattern is reachable: the check is a plain
     * substring test, so listing `"pay"` before `"_pay"` would make the narrower
     * pattern dead code.
     */
    private val PAYMENT_ACTIVITY_HINTS = listOf(
        "confirm_order", "order_confirm", "orderconfirm",
        "payment", "cashier", "checkout", "purchase", "billing",
        "payactivity", "pay_activity", "_pay", "pay_",
        "alipaysdk", "alipay", "wxapi", "tenpay", "unionpay", "wallet",
    )

    /**
     * System dialogs that mean a permission prompt or a call intent appeared.
     */
    private val PERMISSION_DIALOG_PACKAGES = listOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller.permission",
    )

    private val DIALER_PACKAGES = listOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.server.telecom",
        "com.samsung.android.dialer",
    )

    /**
     * Views whose presence indicates a payment or purchase flow, matched against
     * the new window's node ids and classes.
     */
    private val PAYMENT_VIEW_HINTS = listOf(
        "pay", "payment", "cashier", "checkout", "order_confirm", "confirm_order",
        "password", "captcha", "verify_code", "smscode", "sms_code",
    )

    /** Activities that indicate an installer prompt. */
    private val DOWNLOAD_ACTIVITY_HINTS = listOf(
        "install", "download", "packageinstaller", "appdetail",
    )

    /**
     * Classifies a package/activity pair that SmartSkip did *not* initiate.
     *
     * ## This method is only meaningful for a destination SmartSkip caused
     *
     * It answers "if a tap landed here, was that a mistake?" — so it must only be
     * consulted when the foreground surface actually *changed as a result of* a tap.
     * Chrome appearing is a mis-click only because the user was in some other app a
     * moment earlier; if the user launched Chrome themselves, the same call returns
     * [DangerousOutcome.OPENED_BROWSER] and that answer is meaningless.
     *
     * Callers must therefore gate this on an observed package/window transition.
     * [ClickVerifier.verify] is the intended caller and does exactly that.
     *
     * @return the dangerous outcome this destination implies, or `null` when it
     *   looks like ordinary in-app navigation.
     */
    fun classify(packageName: String?, activityName: String?): DangerousOutcome? {
        val pkg = packageName.orEmpty()
        val act = activityName.orEmpty().lowercase()

        if (pkg.isEmpty()) return null

        if (APP_STORE_PACKAGES.any { pkg.equals(it, ignoreCase = true) || pkg.startsWith("$it.") }) {
            return DangerousOutcome.OPENED_APP_STORE
        }
        if (BROWSER_PACKAGES.any { pkg.equals(it, ignoreCase = true) || pkg.startsWith("$it.") }) {
            return DangerousOutcome.OPENED_BROWSER
        }
        if (DOWNLOAD_PACKAGES.any { pkg.equals(it, ignoreCase = true) || pkg.startsWith("$it.") }) {
            return DangerousOutcome.OPENED_DOWNLOAD
        }
        if (PERMISSION_DIALOG_PACKAGES.any { pkg.startsWith(it) }) {
            return DangerousOutcome.OPENED_PERMISSION_DIALOG
        }
        if (DIALER_PACKAGES.any { pkg.equals(it, ignoreCase = true) || pkg.startsWith("$it.") }) {
            return DangerousOutcome.OPENED_DIALER
        }

        if (PAYMENT_ACTIVITY_HINTS.any { it in act }) {
            return DangerousOutcome.OPENED_PAYMENT
        }
        if (DOWNLOAD_ACTIVITY_HINTS.any { it in act }) {
            return DangerousOutcome.OPENED_DOWNLOAD
        }

        // A package in [PAYMENT_PACKAGES] is deliberately NOT classified as dangerous
        // here. WeChat and Alipay are places users legitimately are, and treating
        // "the foreground app is WeChat" as a mis-click would fire constantly. The
        // payment hazard is detected from the *activity* and from payment-shaped view
        // ids instead, which is both narrower and more accurate.
        return null
    }

    /**
     * @return true when the new window looks like a payment or credential surface,
     *   judged from view ids and class names rather than from the package alone.
     */
    fun looksLikePaymentSurface(viewIdOrClassNames: Collection<String>): Boolean =
        viewIdOrClassNames.any { name ->
            val lower = name.lowercase()
            PAYMENT_VIEW_HINTS.any { it in lower }
        }
}
