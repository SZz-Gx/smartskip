package io.github.szzgx.smartskip.testharness

import android.util.Log
import android.text.InputType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.Text

/**
 * Renders one adversarial ad shape.
 *
 * ## The contract this file has to keep
 *
 * Every control whose being tapped would be a *failure* logs a line containing `FAILURE`. Every
 * control that is legitimately tappable logs `HARNESS-CLICKED`. That gives a run an independent
 * ground truth: the accessibility service reports what it decided, and this screen reports what it
 * actually received. Comparing the two is the only way to catch an engine that is wrong about
 * itself.
 *
 * Sizes are given in dp at exact offsets because several scoring features are area- and
 * position-based; a control that is merely "in the corner" would not exercise the 15%
 * centred-creative threshold or the 62% implausible-size veto.
 */
private const val TAG = "SmartSkip/Harness"

/** Records that the harness believes a control was tapped. */
private fun harnessClick(label: String) {
    Log.i(TAG, "HARNESS-CLICKED $label")
    HarnessState.recordClick(label)
}

/**
 * Renders the scenario. The caller supplies the root sized to the whole window, so the geometry
 * the engine measures is the geometry under test.
 */
@Composable
fun HarnessScenarioContent(scenario: TestScenario) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101418)),
    ) {
        when (scenario) {
            TestScenario.PLAIN_SKIP -> PlainSkip()
            TestScenario.BAIT_BUTTON -> BaitButton()
            TestScenario.AD_CREATIVE -> AdCreative()
            TestScenario.VIDEO_LABELLED_SKIP -> VideoLabelledSkip()
            TestScenario.COUNTDOWN_SKIP -> CountdownSkip()
            TestScenario.PAYMENT_SURFACE -> PaymentSurface()
            TestScenario.EDITABLE_SKIP -> EditableSkip()
            TestScenario.PASSWORD_SKIP -> PasswordSkip()
            TestScenario.MULTIPLE_CANDIDATES -> MultipleCandidates()
            TestScenario.FULLSCREEN_CONTAINER -> FullscreenContainer()
            TestScenario.NON_CLICKABLE_SKIP -> NonClickableSkip()
            TestScenario.CLOSE_BUTTON -> CloseButton()
            TestScenario.CTA_NO_SKIP -> CtaNoSkip()
            TestScenario.COUNTDOWN_NEIGHBOUR -> CountdownNeighbour()
        }
    }
}

/**
 * A control at an exact dp offset and size.
 *
 * ## Why the label is put on the clickable node, not only on a child
 *
 * A first version applied `clickable` to a `Box` and drew a `Text` inside it. On a real device the
 * accessibility tree then exposed an *unclickable* `TextView` carrying the label, with the
 * clickable parent as a separate node — so the engine scored the text node, `performAction` on it
 * would have done nothing, and the scenario did not test what it claimed to.
 *
 * The label is therefore attached to the node that actually owns the click action, via
 * `contentDescription`. That is also how a real icon-only skip button presents itself, so this is
 * the more faithful fixture.
 *
 * @param onClick `null` renders a control with no click action, which is itself a scenario
 *   (scenario 11): skip wording on something the user cannot press.
 */
@Composable
private fun Control(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    label: String,
    onClick: (() -> Unit)? = null,
    background: Color = Color(0xFF2A3138),
    textColor: Color = Color(0xFFE5E7EB),
    fontSize: Int = 13,
) {
    var modifier = Modifier
        .padding(start = x.dp, top = y.dp)
        .size(width = width.dp, height = height.dp)
        .background(background)
        // The label lives on this node, so the clickable node is the one carrying the wording.
        .semantics { contentDescription = label }

    if (onClick != null) {
        modifier = modifier.clickable(onClick = onClick)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Decorative only: the wording is on the parent, so this must not add a second candidate.
        Text(
            text = label,
            color = textColor,
            fontSize = fontSize.sp,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

// --- 1 ---------------------------------------------------------------------
// 180x120 dp ≈ 585x390 px: about 7% of a 1200x2670 screen, small enough to read as chrome.
@Composable
private fun PlainSkip() {
    Box(Modifier.fillMaxSize()) {
        Control(x = 300, y = 32, width = 180, height = 120, label = "跳过",
            onClick = { harnessClick("plain_skip/skip") })
    }
}

// --- 2 ---------------------------------------------------------------------
// The fake skip button: skip wording AND inducement wording on one control.
@Composable
private fun BaitButton() {
    Box(Modifier.fillMaxSize()) {
        Control(x = 150, y = 200, width = 320, height = 150, label = "跳过领取",
            onClick = { harnessClick("bait/skip FAILURE") },
            background = Color(0xFF7F1D1D))
    }
}

// --- 3 ---------------------------------------------------------------------
// A creative node marked as an ad container, with "跳过" painted in its middle.
@Composable
private fun AdCreative() {
    Box(Modifier.fillMaxSize().testTag("ad_container").background(Color(0xFF1F2937))) {
        Text(text = "广告 · 立即下载体验", color = Color(0xFF9CA3AF), fontSize = 15.sp,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp))
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Control(x = 0, y = 0, width = 300, height = 140, label = "跳过",
                onClick = { harnessClick("creative/skip FAILURE") },
                background = Color(0xFF374151))
        }
    }
}

// --- 4 ---------------------------------------------------------------------
// Skip wording on a video surface. A VideoView is never a button.
@Composable
private fun VideoLabelledSkip() {
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context -> android.widget.VideoView(context) },
            modifier = Modifier
                .padding(start = 300.dp, top = 32.dp)
                .size(width = 180.dp, height = 120.dp),
        )
        // The "跳过" glyph sits on top of the video surface, as an ad's own overlay would.
        Box(
            modifier = Modifier
                .padding(start = 300.dp, top = 32.dp)
                .size(width = 180.dp, height = 120.dp)
                .clickable { harnessClick("video/skip") },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "跳过", color = Color(0xFFE5E7EB), fontSize = 13.sp)
        }
    }
}

// --- 5 ---------------------------------------------------------------------
@Composable
private fun CountdownSkip() {
    Box(Modifier.fillMaxSize()) {
        Control(x = 300, y = 32, width = 180, height = 120, label = "跳过 5",
            onClick = { harnessClick("countdown/skip") })
    }
}

// --- 6 ---------------------------------------------------------------------
// A payment screen containing a skip-shaped control. Must never be tapped.
@Composable
private fun PaymentSurface() {
    Box(Modifier.fillMaxSize().testTag("pay_cashier_root").background(Color(0xFF1F2937))) {
        Text(text = "确认订单　¥ 128.00", color = Color(0xFFE5E7EB), fontSize = 17.sp,
            modifier = Modifier.padding(start = 16.dp, top = 40.dp))
        Control(x = 300, y = 32, width = 180, height = 120, label = "跳过",
            onClick = { harnessClick("payment/skip FAILURE") })
        BasicTextField(
            value = "",
            onValueChange = {},
            modifier = Modifier
                .padding(start = 16.dp, top = 300.dp)
                .size(width = 340.dp, height = 50.dp)
                .background(Color(0xFF374151)),
            textStyle = TextStyle(color = Color(0xFFE5E7EB), fontSize = 13.sp),
        )
    }
}

// --- 7 ---------------------------------------------------------------------
// An editable field whose text says "跳过". Vetoed at the candidate stage.
@Composable
private fun EditableSkip() {
    Box(Modifier.fillMaxSize()) {
        BasicTextField(
            value = "跳过",
            onValueChange = {},
            modifier = Modifier
                .padding(start = 60.dp, top = 120.dp)
                .size(width = 240.dp, height = 80.dp)
                .background(Color(0xFF374151)),
            textStyle = TextStyle(color = Color(0xFFE5E7EB), fontSize = 13.sp),
        )
    }
}

// --- 8 ---------------------------------------------------------------------
// A real EditText with a password input type. Must not even become a candidate.
@Composable
private fun PasswordSkip() {
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                android.widget.EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setText("跳过")
                    contentDescription = "password_field"
                }
            },
            modifier = Modifier
                .padding(start = 60.dp, top = 120.dp)
                .size(width = 240.dp, height = 80.dp),
        )
    }
}

// --- 9 ---------------------------------------------------------------------
// Three skip-shaped controls. Multiple candidates usually means creative imitating chrome.
@Composable
private fun MultipleCandidates() {
    Box(Modifier.fillMaxSize()) {
        Control(x = 300, y = 32, width = 150, height = 90, label = "跳过",
            onClick = { harnessClick("multiple/a") })
        Control(x = 40, y = 400, width = 150, height = 90, label = "跳过广告",
            onClick = { harnessClick("multiple/b FAILURE") })
        Control(x = 260, y = 700, width = 150, height = 90, label = "Skip",
            onClick = { harnessClick("multiple/c FAILURE") })
    }
}

// --- 10 --------------------------------------------------------------------
// A full-screen clickable node claiming to be skip. Implausible as a button.
@Composable
private fun FullscreenContainer() {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF1F2937))
                .clickable { harnessClick("fullscreen FAILURE") },
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "跳过", color = Color(0xFFE5E7EB), fontSize = 15.sp)
        }
    }
}

// --- 11 --------------------------------------------------------------------
@Composable
private fun NonClickableSkip() {
    Box(Modifier.fillMaxSize()) {
        Control(x = 300, y = 32, width = 180, height = 120, label = "跳过", onClick = null)
    }
}

// --- 12 --------------------------------------------------------------------
@Composable
private fun CloseButton() {
    Box(Modifier.fillMaxSize()) {
        Control(x = 320, y = 40, width = 90, height = 90, label = "×",
            onClick = { harnessClick("close") }, fontSize = 22)
    }
}

// --- 13 --------------------------------------------------------------------
// The ad's call to action, with no skip wording. Must be vetoed on dangerous wording.
@Composable
private fun CtaNoSkip() {
    Box(Modifier.fillMaxSize().testTag("ad_container_root").background(Color(0xFF1F2937))) {
        Control(x = 110, y = 600, width = 360, height = 150, label = "立即下载",
            onClick = { harnessClick("cta/download FAILURE") },
            background = Color(0xFF7F1D1D))
    }
}

// --- 14 --------------------------------------------------------------------
// Skip plus a separate small countdown chip, the way ad SDKs actually render it.
@Composable
private fun CountdownNeighbour() {
    Box(Modifier.fillMaxSize()) {
        Control(x = 300, y = 32, width = 180, height = 120, label = "跳过",
            onClick = { harnessClick("countdown_neighbour/skip") })
        Control(x = 240, y = 40, width = 40, height = 90, label = "8", onClick = null,
            background = Color(0xFF374151))
    }
}
