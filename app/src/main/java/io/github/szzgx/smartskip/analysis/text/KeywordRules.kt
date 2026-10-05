package io.github.szzgx.smartskip.analysis.text

/**
 * The vocabulary SmartSkip reasons about, and — just as importantly — the
 * vocabulary it refuses to treat as decisive.
 *
 * Design doc §5.1 is explicit: *"关键词只能作为证据，不能作为最终判断"* — a keyword
 * is evidence, never a verdict. Accordingly nothing in this file triggers a
 * click; it only produces [TextSignal]s that the scoring engine weighs against
 * positional, temporal and structural evidence.
 *
 * Every entry is stored already normalised (lowercase, NFKC-folded, no
 * whitespace runs) so that lookups against [TextNormalizer.normalize] output are
 * a plain set membership test.
 */
object KeywordRules {

    /**
     * Text that means "dismiss this interstitial". Matched as an exact whole
     * string first; see [TextSignal.SKIP_EXACT].
     */
    val SKIP_EXACT: Set<String> = setOf(
        "跳过",
        "跳过广告",
        "跳过此广告",
        "点击跳过",
        "跳过 >",
        "跳过>",
        "skip",
        "skip ad",
        "skip ads",
        "skip advertisement",
        "skip this ad",
        "saltar",
        "überspringen",
        "건너뛰기",
        "スキップ",
    )

    /**
     * Text that *contains* a skip phrase but is not exactly one. This is weaker
     * evidence: `"跳过即可领取奖励"` is a classic dark pattern where the word
     * "跳过" is bait and the real action is a reward claim.
     */
    val SKIP_CONTAINS: List<String> = listOf(
        "跳过", "skip",
    )

    /**
     * Text that counts a splash ad down. On its own this is strong evidence that
     * the current screen is a splash ad, because almost nothing else in a normal
     * app counts itself down while covering the whole screen.
     *
     * The *bare digit* pattern is the dangerous one: it matches like counts, comment
     * counts and version strings all over a normal UI. Its position in this list is
     * published as [BARE_DIGIT_PATTERN_INDEX] so that
     * [TextSignalAnalyzer.parseCountdownSeconds] can hold it to a much smaller numeric
     * range than the labelled forms — a calibration that came from a real capture.
     */
    val COUNTDOWN_PATTERNS: List<Regex> = listOf(
        // "3s", "3 s", "3秒", "3 秒"
        Regex("""^\s*(\d{1,2})\s*(?:s|sec|secs|second|seconds|秒)\s*$"""),
        // "剩余3秒", "还剩 3 秒", "倒计时 3 秒", "广告 3 秒"
        Regex("""^\s*(?:剩余|还剩|剩下|倒计时|倒记时|广告\s*)?\s*(\d{1,2})\s*(?:s|秒)\s*$"""),
        // "跳过 3", "跳过3", "跳过  3", "Skip 3"
        //
        // The separator is an explicit run of *whitespace only*. Two earlier attempts were wrong in
        // ways the tests caught:
        //
        //   * `\D{0,4}` could not match "跳过 5" at all, because `\D` consumed the space and the
        //     following `\d` then had to match another space;
        //   * `[\s\D]{0,4}?` was worse — `\D` also matches digits, so the lazy quantifier ate the
        //     "5" before the capture group could see it, and "跳过5" stopped being a countdown.
        Regex("""^\s*(?:skip|跳过)[\s]{0,4}(\d{1,2})\s*(?:s|秒)?\s*$"""),
        // A bare number in a small corner node. Last on purpose: see BARE_DIGIT_PATTERN_INDEX.
        Regex("""^\s*(\d{1,2})\s*$"""),
    )

    /**
     * Index of the bare-digit pattern within [COUNTDOWN_PATTERNS].
     *
     * Exists so that the analyser can bound the bare pattern differently without duplicating the
     * list. If the order of [COUNTDOWN_PATTERNS] changes, this must change with it.
     */
    const val BARE_DIGIT_PATTERN_INDEX = 3

    /**
     * Text that a control is a "close" affordance. Ambiguous: legitimate dialogs
     * use it too, so it is only weak-to-moderate evidence and always combined
     * with position.
     */
    val CLOSE_EXACT: Set<String> = setOf(
        "关闭", "关闭广告", "关闭此广告", "close", "关闭×", "×", "x", "✕", "✖", "⨯", "╳",
    )

    /**
     * Explicit advertising disclosure. Strong evidence that the current window
     * or an ancestor container is an advertisement — not that any particular
     * child is a skip button.
     */
    val AD_DISCLOSURE: Set<String> = setOf(
        "广告", "广告位", "赞助", "赞助内容", "推广", "推广内容",
        "ad", "ads", "advert", "advertisement", "sponsored", "promoted",
    )

    /**
     * Words that describe the ad's *offer*. Their presence means the node is
     * probably creative content (the ad image/CTA) rather than chrome (the skip
     * button), which is a strong negative signal.
     */
    val CREATIVE_HINTS: Set<String> = setOf(
        "立即下载", "下载", "安装", "立即安装", "立即体验", "立即购买", "立即抢购",
        "立即领取", "立即查看", "立即打开", "打开", "查看详情", "了解更多", "点击了解",
        "点击查看", "查看", "领取", "领取奖励", "免费领取", "红包", "福利", "抽奖",
        "install", "download", "open", "learn more", "shop now", "buy now",
    )

    /**
     * Words whose mis-click has real-world consequences: money, installation,
     * external navigation, or a permission grant. These feed both a score
     * penalty and, in combination with skip wording, a hard veto.
     */
    val STRONG_DANGER: Set<String> = setOf(
        "立即下载", "下载安装", "下载并安装", "立即安装", "安装",
        "立即购买", "立即付款", "立即支付", "确认支付", "去支付", "去付款", "立即充值",
        "充值", "开通会员", "开通", "订阅", "续费", "支付",
        "同意并继续", "同意并", "允许", "授权", "获取权限", "开启通知",
        "download now", "install now", "buy now", "pay now", "subscribe", "purchase",
    )

    /**
     * "Inducement" wording — a reward is dangled in exchange for a tap. Combined
     * with skip wording this is the signature of a fake skip button.
     */
    val INDUCEMENT: Set<String> = setOf(
        "领取", "立即领取", "免费", "免费领取", "红包", "奖励", "金币", "提现", "抽奖",
        "免费领", "0元", "一元", "特价", "限时", "抢购", "秒杀",
        "free", "claim", "reward", "gift",
    )

    /**
     * Resource-id / view-id tokens that ad SDKs commonly use for their skip
     * control. Matched as substrings against the lowercased id, because fully
     * qualified ids vary (`com.qq.e.ads.nativ.widget.SkipView`,
     * `skip_btn`, `ad_skip_text`, `btn_skip`).
     */
    val SKIP_ID_HINTS: List<String> = listOf(
        "skip", "jump", "close_ad", "ad_close", "adskip", "ad_skip",
        "btn_skip", "skip_btn", "skipview", "skip_view", "skip_text",
        "tt_splash_skip", "splash_skip", "gdt_skip",
    )

    /** Resource-id fragments that identify an ad container. */
    val AD_CONTAINER_ID_HINTS: List<String> = listOf(
        "splash", "adcontainer", "ad_container", "adview", "ad_view",
        "nativead", "native_ad", "banner", "interstitial", "advert",
        "tt_splash", "gdt_", "ksad", "bd_ad", "mimo_", "ad_",
    )

    /** Resource-id fragments that identify payment or purchase flows. */
    val PAYMENT_ID_HINTS: List<String> = listOf(
        "pay", "payment", "purchase", "buy", "order", "checkout", "wallet",
        "password", "captcha", "verify", "otp", "sms_code",
    )

    /** All exact-match phrases worth indexing, for fast lookup. */
    val ALL_EXACT: Set<String> = SKIP_EXACT + CLOSE_EXACT + AD_DISCLOSURE + CREATIVE_HINTS + STRONG_DANGER + INDUCEMENT
}
