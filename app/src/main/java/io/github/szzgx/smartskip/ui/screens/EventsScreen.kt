package io.github.szzgx.smartskip.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.szzgx.smartskip.SmartSkipApplication
import io.github.szzgx.smartskip.data.db.AdvertisementEventEntity
import io.github.szzgx.smartskip.ui.components.ConfidenceMeter
import io.github.szzgx.smartskip.ui.components.KeyValueRow
import io.github.szzgx.smartskip.ui.components.MonoText
import io.github.szzgx.smartskip.ui.components.StatusPill
import io.github.szzgx.smartskip.ui.theme.StatusColors

/**
 * The observation log.
 *
 * Every row answers the three questions a user actually has: *which app*, *what did SmartSkip
 * decide*, and *why*. The "why" is the point of the whole screen — an automated tapper that
 * cannot explain itself is impossible to trust or to debug.
 */
@Composable
fun EventsScreen() {
    val context = LocalContext.current
    val container = remember(context) { SmartSkipApplication.containerOf(context) }

    val events by remember(container) {
        container.eventDao.observeRecent(limit = EVENT_LIMIT)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    if (events.isEmpty()) {
        EmptyState(
            title = "还没有记录",
            body = "SmartSkip 只记录它评估过的“跳过”类控件。" +
                "打开一个有开屏广告的应用，或者把总开关保持关闭，先观察它会怎么判断。",
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = "最近 $EVENT_LIMIT 条评估记录",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        items(events, key = { it.id }) { event ->
            EventCard(event)
        }
    }
}

@Composable
private fun EventCard(event: AdvertisementEventEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = event.packageName,
                    style = MaterialTheme.typography.titleSmall,
                )
                StatusPill(
                    text = actionLabel(event.action),
                    containerColor = actionContainer(event.action),
                    contentColor = Color.White,
                )
            }

            ConfidenceMeter(score = event.score)

            Text(
                text = "结果：${resultLabel(event.result)}",
                style = MaterialTheme.typography.bodySmall,
                color = resultColor(event.result),
            )

            if (event.vetoReasons.isNotBlank()) {
                Text(
                    text = "安全否决：${vetoLabel(event.vetoReasons)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.vetoed,
                )
            }

            // ------------------------------------------------------------- reasoning
            if (event.topFeatures.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "主要依据",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    featureLines(event.topFeatures).forEach { line ->
                        MonoText(line)
                    }
                }
            }

            // ----------------------------------------------------------------- detail
            event.activityName?.takeIf { it.isNotBlank() }?.let {
                KeyValueRow("Activity", it.substringAfterLast('.'))
            }
            KeyValueRow("控件类型", event.className.substringAfterLast('.'))
            event.resourceId?.takeIf { it.isNotBlank() }?.let { KeyValueRow("resource-id", it) }
            KeyValueRow("位置", "${event.boundsLeft},${event.boundsTop} - ${event.boundsRight},${event.boundsBottom}")
            KeyValueRow("屏幕", "${event.screenWidth} × ${event.screenHeight}")
            KeyValueRow("模式", modeLabel(io.github.szzgx.smartskip.analysis.AutoSkipMode.fromName(event.mode)))
            KeyValueRow("文本特征", if (event.textFeatures.isBlank()) "无" else event.textFeatures)
            KeyValueRow("时间", relativeTime(event.timestampMs))
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Turns the stored `code:points` pairs into readable lines.
 *
 * The descriptions live here rather than in the database so that rewording an explanation does
 * not require a schema migration or invalidate old rows.
 */
private fun featureLines(encoded: String): List<String> = encoded
    .split(',')
    .mapNotNull { entry ->
        val code = entry.substringBefore(':').trim()
        val points = entry.substringAfter(':', "").trim().toIntOrNull() ?: return@mapNotNull null
        val description = FEATURE_LABELS[code] ?: code
        val sign = if (points >= 0) "+" else ""
        "$sign$points　$description"
    }

private val FEATURE_LABELS: Map<String, String> = mapOf(
    "text_skip_exact" to "文本恰好是“跳过”类完整词",
    "text_skip_content_desc" to "contentDescription 是“跳过”类词",
    "text_skip_view_id" to "resource-id 形如广告 SDK 的 SkipView",
    "text_skip_contains_only" to "文本包含“跳过”但非完整词（可能是诱饵）",
    "text_close" to "文本是关闭类符号/词",
    "text_ad_on_ancestor" to "祖先容器带有“广告”标识",
    "text_ad_in_window" to "当前窗口存在“广告”标识",
    "text_creative" to "文本是广告素材用语",
    "text_inducement" to "附近存在诱导性奖励文案",
    "text_skip_and_countdown" to "“跳过”与倒计时同时出现",
    "pos_top_right" to "位于右上角",
    "pos_top_left" to "位于左上角",
    "pos_bottom_right" to "位于右下角",
    "pos_center" to "位于屏幕正中（通常是素材）",
    "pos_h_centered" to "水平居中（素材特征）",
    "pos_fullscreen" to "几乎铺满屏幕",
    "time_launch_window" to "出现在启动后数秒内",
    "time_countdown_on_node" to "控件自带倒计时",
    "time_countdown_shrinking" to "倒计时确实在递减",
    "time_too_late" to "进入应用已久，此时“跳过”多为正常功能",
    "struct_actionable" to "控件真正可点击",
    "struct_stable" to "多帧中位置尺寸稳定",
    "struct_click_history" to "该签名此前被观察到可点击",
    "struct_leaf" to "叶节点形态，符合按钮特征",
    "struct_container" to "是容器而非按钮",
    "struct_hidden_a11y" to "被标记为对无障碍不重要",
    "struct_not_button" to "控件类型不像按钮",
    "act_launcherish" to "Activity 形如启动页",
    "act_ad_named" to "Activity 名称含广告语义",
    "act_webview" to "当前是 WebView 容器",
    "act_known_safe" to "该 Activity 有过成功跳过记录",
    "hist_known_rule" to "命中本机已学习规则",
    "hist_user_confirmed" to "该规则已由用户确认",
    "hist_success" to "该规则成功跳过多次",
    "hist_failure" to "该规则此前失误",
    "risk_creative_overlap" to "控件位于广告素材范围内",
    "risk_download_context" to "同屏存在下载/应用商店内容",
    "risk_payment_context" to "同屏存在支付/凭据相关内容",
    "risk_nested_interactive" to "控件内部还含可点击子控件",
    "risk_large_center" to "位于中央且体积过大，疑似素材",
    "risk_high_center_of_mass" to "控件占据屏幕主体",
    "risk_suspicious_class" to "控件类型与广告素材常见类型一致",
    "risk_multiple_candidates" to "同屏多个“跳过”样式控件",
    "risk_danger_nearby" to "附近存在危险语义",
    "risk_prior_danger" to "该控件此前造成过疑似误点",
)

private val VETO_LABELS = mapOf(
    "EDITABLE_FIELD" to "输入框/勾选项",
    "PAYMENT_OR_CREDENTIAL" to "支付或凭据页面",
    "STRONG_DANGER_TEXT" to "含下载/支付/授权等危险语义",
    "SKIP_WORDING_BAIT" to "疑似伪跳过诱导按钮",
    "NOT_ACTIONABLE" to "不可点击",
    "NOT_VISIBLE" to "对用户不可见",
    "IMPLAUSIBLE_SIZE" to "体积过大，不符合按钮特征",
)

private fun vetoLabel(encoded: String): String = encoded
    .split(',')
    .filter { it.isNotBlank() }
    .joinToString("；") { VETO_LABELS[it] ?: it }

private fun actionLabel(action: String): String = when (action) {
    "CLICK" -> "已点击"
    "VETO" -> "已否决"
    "SCORE" -> "未达阈值"
    "THROTTLE" -> "已限流"
    else -> action
}

private fun actionContainer(action: String): Color = when (action) {
    "CLICK" -> StatusColors.acted
    "VETO" -> StatusColors.vetoed
    "THROTTLE" -> StatusColors.observed
    else -> Color(0xFF565E71)
}

private fun resultLabel(result: String): String = when (result) {
    "CONFIRMED_SKIPPED" -> "确认跳过成功"
    "SUSPECTED_MISCLICK" -> "疑似误点，已记录并可能暂停该应用"
    "NO_OBSERVABLE_CHANGE" -> "页面无变化"
    "STILL_THERE" -> "广告仍在"
    "INCONCLUSIVE" -> "无法判断"
    else -> "尚未验证"
}

private fun resultColor(result: String): Color = when (result) {
    "CONFIRMED_SKIPPED" -> StatusColors.acted
    "SUSPECTED_MISCLICK" -> StatusColors.danger
    "NO_OBSERVABLE_CHANGE", "STILL_THERE" -> StatusColors.observed
    else -> Color(0xFF565E71)
}

private fun relativeTime(timestampMs: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestampMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
    ).toString()

private const val EVENT_LIMIT = 200
