package io.github.szzgx.smartskip.testharness

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The scenarios the debug test harness can present.
 *
 * ## Why this exists
 *
 * The project's most safety-critical path — decide to tap, confirm, tap, then verify what the tap
 * did — could not be exercised on a real device at all. Eight cold launches of ad-bearing apps
 * produced exactly one real splash ad, and its accessibility tree contained **no skip control**:
 * just an offer, an auto-dismiss countdown, and a "claim" button. That is a capability boundary
 * and worth knowing, but it means real ads cannot be used to test the click path, and they
 * certainly cannot be used to test the *fake* skip buttons that this project exists to defend
 * against — a real ad never presents one, because hiding the control entirely is easier than
 * faking it.
 *
 * So the adversary is reproduced deliberately. Each entry below is a shape that has to be handled
 * correctly, and [expectedOutcome] states in advance what correct looks like, so a run can fail.
 *
 * @property key stable identifier, used in logs and by the adb trigger.
 * @property title short label shown on screen.
 * @property expectedOutcome what the engine must do. Written before running, not after.
 */
enum class TestScenario(
    val key: String,
    val title: String,
    val expectedOutcome: String,
) {
    /** Step 1. A textbook skip chip in the top-right corner. */
    PLAIN_SKIP(
        key = "plain_skip",
        title = "1 标准跳过按钮",
        expectedOutcome = "首次：仅观察。确认规则后：自动点击并验证成功。",
    ),

    /**
     * Step 2, and the most important case in the whole harness.
     *
     * Skip wording and an inducement on the same control is the signature dark pattern: the word
     * "跳过" is bait and the real action is claiming a reward. If this is ever clicked, the
     * project's central promise has failed.
     */
    BAIT_BUTTON(
        key = "bait",
        title = "2 伪跳过（诱导领取）",
        expectedOutcome = "必须永不点击：该控件同时含“跳过”与“领取”，属硬性否决。",
    ),

    /** Skip text drawn *inside* the ad creative rather than on chrome. */
    AD_CREATIVE(
        key = "creative",
        title = "3 素材内的“跳过”文字",
        expectedOutcome = "不点击：控件位于广告素材范围且居中过大。",
    ),

    /** A video surface carrying skip wording: creative, not a button. */
    VIDEO_LABELLED_SKIP(
        key = "video",
        title = "4 VideoView 上的“跳过”",
        expectedOutcome = "评分应明显低于同位置的普通按钮。",
    ),

    /** "跳过 5" — skip wording plus a real countdown. */
    COUNTDOWN_SKIP(
        key = "countdown",
        title = "5 带倒计时的跳过",
        expectedOutcome = "首次：仅观察。确认规则后：自动点击。倒计时应被识别为 5 秒。",
    ),

    /** A payment-shaped container holding a skip-shaped child. */
    PAYMENT_SURFACE(
        key = "payment",
        title = "6 支付页面里的跳过",
        expectedOutcome = "必须永不点击：同屏存在支付/凭据相关内容。",
    ),

    /** An editable field whose text says "跳过". */
    EDITABLE_SKIP(
        key = "editable",
        title = "7 输入框写着“跳过”",
        expectedOutcome = "必须永不点击：可编辑控件在候选阶段就被排除。",
    ),

    /** A password field. Must not even become a candidate. */
    PASSWORD_SKIP(
        key = "password",
        title = "8 密码框（含跳过字样）",
        expectedOutcome = "必须永不点击：密码框根本不进入评估流程。",
    ),

    /** Three skip-shaped controls at once: usually means creative text is imitating chrome. */
    MULTIPLE_CANDIDATES(
        key = "multiple",
        title = "9 同屏多个“跳过”",
        expectedOutcome = "不点击：多个候选应显著降低置信度。",
    ),

    /** A full-screen clickable node claiming to be skip. */
    FULLSCREEN_CONTAINER(
        key = "fullscreen",
        title = "10 铺满屏幕的“跳过”容器",
        expectedOutcome = "必须永不点击：体积不符合按钮特征。",
    ),

    /** Skip wording on a control that carries no click action. */
    NON_CLICKABLE_SKIP(
        key = "nonclick",
        title = "11 不可点击的“跳过”",
        expectedOutcome = "必须永不点击：不可点击。",
    ),

    /** A close glyph rather than the word "跳过". */
    CLOSE_BUTTON(
        key = "close",
        title = "12 “×”关闭按钮",
        expectedOutcome = "应被识别为候选，评分低于完整“跳过”措辞。",
    ),

    /** Serious-action wording: the ad's call to action, not its chrome. */
    CTA_NO_SKIP(
        key = "cta",
        title = "13 “立即下载”（无跳过字样）",
        expectedOutcome = "不点击：控件自身含危险语义，属硬性否决。",
    ),

    /** A genuinely skippable-shaped control with a countdown sibling elsewhere. */
    COUNTDOWN_NEIGHBOUR(
        key = "countdown_neighbour",
        title = "14 相邻倒计时 + 跳过",
        expectedOutcome = "首次：仅观察。确认规则后：自动点击。",
    ),
    ;

    companion object {
        fun fromKey(key: String?): TestScenario? = entries.firstOrNull { it.key == key }

        val DEFAULT = PLAIN_SKIP
    }
}

/**
 * Which scenario the harness is currently showing.
 *
 * A process-wide singleton so that the adb trigger and the Compose UI can agree without a
 * database round trip. Debug-only by construction: this file lives in the debug source set.
 */
object HarnessState {

    private val _current = MutableStateFlow(TestScenario.DEFAULT)
    private val _lastClicked = MutableStateFlow<String?>(null)

    val current: StateFlow<TestScenario> = _current.asStateFlow()

    /** The label of the last control the harness itself saw tapped, for ground truth. */
    val lastClicked: StateFlow<String?> = _lastClicked.asStateFlow()

    fun select(scenario: TestScenario) {
        _current.value = scenario
        _lastClicked.value = null
    }

    fun selectByKey(key: String?): TestScenario? {
        val scenario = TestScenario.fromKey(key) ?: return null
        select(scenario)
        return scenario
    }

    fun recordClick(label: String) {
        _lastClicked.value = label
    }
}
