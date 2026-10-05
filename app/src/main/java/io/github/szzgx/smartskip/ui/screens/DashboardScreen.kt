package io.github.szzgx.smartskip.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.github.szzgx.smartskip.SmartSkipApplication
import io.github.szzgx.smartskip.accessibility.ServiceStatus
import io.github.szzgx.smartskip.analysis.AutoSkipMode
import io.github.szzgx.smartskip.data.db.AppRuleEntity
import io.github.szzgx.smartskip.ui.Destination
import io.github.szzgx.smartskip.ui.components.KeyValueRow
import io.github.szzgx.smartskip.ui.components.SectionCard
import io.github.szzgx.smartskip.ui.components.StatusPill
import io.github.szzgx.smartskip.ui.theme.StatusColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The home screen: is it working, and what has it been doing?
 *
 * Ordered by what a user actually needs to know first — whether the permission is granted,
 * then whether automatic skipping is armed, then the evidence that it is doing something.
 */
@Composable
fun DashboardScreen(navController: NavController) {
    val context = LocalContext.current
    val container = remember(context) { SmartSkipApplication.containerOf(context) }
    val scope = rememberCoroutineScope()

    val prefs by container.preferences.flow.collectAsStateWithLifecycle(
        initialValue = container.preferences.snapshot(),
    )
    val serviceState by ServiceStatus.state.collectAsStateWithLifecycle()

    /*
     * The platform's own answer is re-read whenever this screen resumes, because the user
     * grants the permission on a *different* screen and comes back. A DisposableEffect keyed
     * on the lifecycle is the simplest correct way to notice that.
     */
    var serviceEnabled by remember { mutableStateOf(ServiceStatus.isServiceEnabled(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                serviceEnabled = ServiceStatus.isServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val recentEvents by remember(container) {
        container.eventDao.observeRecent(limit = 3)
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val ruleCount by remember(container) {
        container.appRuleDao.observeAll()
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val suspendedPackages = remember(serviceState, container) {
        container.safetyRepository.cachedStates().values.filter { it.isSuspended }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "SmartSkip",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "无 Root 智能开屏广告跳过",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ------------------------------------------------------------ permission
        SectionCard(
            title = "无障碍权限",
            subtitle = "SmartSkip 只通过官方无障碍服务读取屏幕控件结构，不 Root、不修改系统。",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (serviceEnabled) {
                    StatusPill("已开启", StatusColors.actedContainer, StatusColors.acted)
                } else {
                    StatusPill("未开启", StatusColors.dangerContainer, StatusColors.danger)
                }
                Button(onClick = { openAccessibilitySettings(context) }) {
                    Text(if (serviceEnabled) "打开系统设置" else "去开启")
                }
            }
            if (serviceEnabled && !serviceState.serviceConnected) {
                Text(
                    text = "权限已授予，服务尚未连接。打开任意应用出现开屏广告后即会生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ------------------------------------------------------------ master switch
        SectionCard(
            title = "自动跳过",
            subtitle = "关闭时 SmartSkip 仍会观察并评分，但绝不点击。可在“记录”里看到它本来会做什么。",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (prefs.autoSkipMasterSwitch) "已启用" else "已关闭（仅观察）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = prefs.autoSkipMasterSwitch,
                    onCheckedChange = { enabled ->
                        scope.launch { container.preferences.setAutoSkipEnabled(enabled) }
                    },
                )
            }
            if (prefs.autoSkipMasterSwitch && serviceEnabled && serviceState.serviceConnected) {
                Text(
                    text = "正在自动工作。",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.acted,
                )
            }
        }

        // ------------------------------------------------------------------ mode
        SectionCard(
            title = "工作模式",
            subtitle = modeDescription(prefs.mode),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(
                    text = modeLabel(prefs.mode),
                    containerColor = if (prefs.mode == AutoSkipMode.AGGRESSIVE) {
                        StatusColors.observedContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = if (prefs.mode == AutoSkipMode.AGGRESSIVE) {
                        StatusColors.observed
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                )
                TextButton(onClick = { navController.navigate(Destination.Settings.route) }) {
                    Text("修改")
                }
            }
        }

        // ------------------------------------------------------------- this session
        SectionCard(title = "本次运行") {
            KeyValueRow("自动跳过次数", serviceState.autoSkipCount.toString())
            KeyValueRow(
                key = "危险误点次数",
                value = serviceState.dangerousMisclickCount.toString(),
                valueColor = if (serviceState.dangerousMisclickCount > 0) {
                    StatusColors.danger
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            KeyValueRow("已学习规则", "${ruleCount.size} 条")
            if (suspendedPackages.isNotEmpty()) {
                Text(
                    text = "已暂停自动操作的应用：${suspendedPackages.joinToString("、") { it.packageName }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.danger,
                )
            }
        }

        // ------------------------------------------------------------ last decision
        if (serviceState.lastExplanation != null) {
            SectionCard(
                title = "最近一次判断",
                subtitle = serviceState.lastPackageName,
            ) {
                if (serviceState.lastScore != null) {
                    io.github.szzgx.smartskip.ui.components.ConfidenceMeter(
                        score = serviceState.lastScore ?: 0,
                    )
                }
                Text(
                    text = serviceState.lastExplanation.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        // ------------------------------------------------------------------ recent
        SectionCard(
            title = "最近记录",
            subtitle = if (recentEvents.isEmpty()) "还没有记录。打开一个有开屏广告的应用试试。" else null,
        ) {
            recentEvents.forEach { event ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = event.packageName,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "评分 ${event.score}　${event.action}" +
                            if (event.result != "UNVERIFIED") "　${event.result}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (recentEvents.isNotEmpty()) {
                TextButton(onClick = { navController.navigate(Destination.Events.route) }) {
                    Text("查看全部")
                }
            }
        }

        SectionCard(
            title = "出问题了吗？",
            subtitle = "如果 SmartSkip 点错了按钮，请立刻关闭上面的总开关，并在“规则”里把对应规则标记为误操作。",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { navController.navigate(Destination.Events.route) }) {
                    Text("查看记录")
                }
                TextButton(onClick = { navController.navigate(Destination.Rules.route) }) {
                    Text("管理规则")
                }
            }
        }
    }
}

/**
 * Opens the system accessibility settings screen.
 *
 * No in-app toggle exists because Android provides no API to enable an accessibility service
 * programmatically, which is a deliberate platform protection: the user must go through the
 * system's own screen and see what they are granting.
 */
private fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(ServiceStatus.accessibilitySettingsIntent())
    } catch (_: ActivityNotFoundException) {
        // Every modern device has this screen; if it somehow does not, open the app's own
        // settings page rather than crashing.
        runCatching {
            context.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_SETTINGS,
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

internal fun modeLabel(mode: AutoSkipMode): String = when (mode) {
    AutoSkipMode.SAFE -> "安全模式"
    AutoSkipMode.STANDARD -> "标准模式"
    AutoSkipMode.AGGRESSIVE -> "激进模式"
}

internal fun modeDescription(mode: AutoSkipMode): String = when (mode) {
    AutoSkipMode.SAFE ->
        "只自动执行置信度极高、且已经确认过的规则，适合刚安装时使用。"

    AutoSkipMode.STANDARD ->
        "规则 + UI 特征 + 历史行为，默认模式。"

    AutoSkipMode.AGGRESSIVE ->
        "允许更低的自动跳过阈值。可能产生误点击，请自行承担风险。"
}
