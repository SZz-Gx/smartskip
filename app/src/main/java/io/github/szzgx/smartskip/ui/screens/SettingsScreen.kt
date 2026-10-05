package io.github.szzgx.smartskip.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.szzgx.smartskip.BuildConfig
import io.github.szzgx.smartskip.SmartSkipApplication
import io.github.szzgx.smartskip.analysis.AutoSkipMode
import io.github.szzgx.smartskip.ui.components.SectionCard
import io.github.szzgx.smartskip.ui.theme.StatusColors
import kotlinx.coroutines.launch

/**
 * Settings (design doc §10's three modes, plus the safety controls the design implies).
 *
 * Grouped as: behaviour, safety limits, data, and then the privacy statement. The privacy text
 * is on this screen rather than buried in a document because the permission SmartSkip asks for
 * is the most invasive one Android offers, and the user is entitled to read the answer where
 * they granted it.
 */
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = remember(context) { SmartSkipApplication.containerOf(context) }
    val scope = rememberCoroutineScope()

    val prefs by container.preferences.flow.collectAsStateWithLifecycle()

    var pendingClear by remember { mutableStateOf(false) }
    var pendingClearEvents by remember { mutableStateOf(false) }
    var aggressiveWarning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "设置", style = MaterialTheme.typography.headlineSmall)

        // ---------------------------------------------------------------- behaviour
        SectionCard(title = "工作模式", subtitle = modeDescription(prefs.mode)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AutoSkipMode.entries.forEach { mode ->
                    FilterChip(
                        selected = prefs.mode == mode,
                        onClick = {
                            if (mode == AutoSkipMode.AGGRESSIVE && prefs.mode != AutoSkipMode.AGGRESSIVE) {
                                aggressiveWarning = true
                            } else {
                                scope.launch { container.preferences.setMode(mode) }
                            }
                        },
                        label = { Text(modeLabel(mode)) },
                    )
                }
            }
            Text(
                text = "当前自动点击阈值：${
                    io.github.szzgx.smartskip.decision.DecisionEngine
                        .thresholdsFor(prefs.mode).clickThreshold
                } 分。评分上限为 100，因此阈值越高越保守。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(
            title = "学习",
            subtitle = "开启后，SmartSkip 会为识别到的“跳过”控件建立规则，下次可直接使用。",
        ) {
            ToggleRow(
                label = "启用规则学习",
                checked = prefs.learningEnabled,
                onCheckedChange = { enabled ->
                    scope.launch { container.preferences.update { it.copy(learningEnabled = enabled) } }
                },
            )
            ToggleRow(
                label = "记录未达阈值的候选",
                checked = prefs.logObservationEvents,
                onCheckedChange = { enabled ->
                    scope.launch { container.preferences.update { it.copy(logObservationEvents = enabled) } }
                },
            )
            Text(
                text = "开启记录会把所有评过分的候选写入数据库，便于调试，但记录量会显著增加。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ------------------------------------------------------------------- safety
        SectionCard(
            title = "安全限制",
            subtitle = "这些限制独立于评分，无论置信度多高都会生效。",
        ) {
            Text(
                text = "两次点击最小间隔：${prefs.minimumClickIntervalMs} 毫秒",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = prefs.minimumClickIntervalMs.toFloat(),
                onValueChange = { value ->
                    scope.launch {
                        container.preferences.update { it.copy(minimumClickIntervalMs = value.toLong()) }
                    }
                },
                valueRange = 300f..5_000f,
                steps = 46,
            )

            Text(
                text = "每分钟每应用最多自动点击：${prefs.maxClicksPerWindowPerMinute} 次",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = prefs.maxClicksPerWindowPerMinute.toFloat(),
                onValueChange = { value ->
                    scope.launch {
                        container.preferences.update {
                            it.copy(maxClicksPerWindowPerMinute = value.toInt().coerceAtLeast(1))
                        }
                    }
                },
                valueRange = 1f..10f,
                steps = 8,
            )

            Text(
                text = "出现几次危险误点后暂停该应用：${prefs.stopAfterDangerousMisclicks} 次",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = prefs.stopAfterDangerousMisclicks.toFloat(),
                onValueChange = { value ->
                    scope.launch {
                        container.preferences.update {
                            it.copy(stopAfterDangerousMisclicks = value.toInt().coerceAtLeast(1))
                        }
                    }
                },
                valueRange = 1f..5f,
                steps = 3,
            )
            Text(
                text = "危险误点指点击后进入浏览器、应用商店、下载安装、支付页面或跳转到其他应用。" +
                    "达到上限后该应用会被暂停自动操作，需要你手动恢复。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------------------------------------------------------------- packages
        SectionCard(
            title = "仅观察 / 排除的应用",
            subtitle = "“仅观察”的应用只会被评分和记录，永远不会被自动点击。",
        ) {
            if (prefs.watchOnlyPackages.isEmpty() && prefs.excludedPackages.isEmpty()) {
                Text(
                    text = "暂无。可以在“记录”里针对某个应用设置。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            prefs.watchOnlyPackages.forEach { packageName ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = packageName, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        scope.launch { container.preferences.setWatchOnly(packageName, false) }
                    }) {
                        Text("取消仅观察")
                    }
                }
            }
            prefs.excludedPackages.forEach { packageName ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = packageName, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        scope.launch { container.preferences.setExcluded(packageName, false) }
                    }) {
                        Text("不再排除")
                    }
                }
            }
        }

        // -------------------------------------------------------------------- data
        SectionCard(
            title = "数据",
            subtitle = "所有数据都保存在本机数据库，且本应用没有网络权限，无法上传。",
        ) {
            Text(
                text = "记录保留天数：${prefs.eventRetentionDays} 天",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = prefs.eventRetentionDays.toFloat(),
                onValueChange = { value ->
                    scope.launch {
                        container.preferences.update {
                            it.copy(eventRetentionDays = value.toInt().coerceAtLeast(1))
                        }
                    }
                },
                valueRange = 1f..30f,
                steps = 28,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { pendingClearEvents = true }) { Text("清空记录") }
                TextButton(onClick = { pendingClear = true }) { Text("清空全部规则") }
            }
        }

        // ----------------------------------------------------------------- privacy
        SectionCard(title = "隐私说明") {
            Text(
                text = """
                    SmartSkip 需要无障碍权限才能读取其他应用的界面控件结构。这是它能工作的唯一方式，也是它唯一的敏感能力。

                    • 所有识别与判断都在本机完成，没有任何数据被上传。
                    • 发布版本不声明网络权限（连 INTERNET 都没有），因此从系统层面就无法外传数据。调试版本才有网络权限，用于研究 WebView 类广告。
                    • 不会保存密码、验证码、银行卡号或聊天内容：输入框与密码框在候选阶段就被排除，根本不会被评估。
                    • 数据库里不保存屏幕上出现的文字内容，只保存文本的“分类”（是否含“跳过”等）。原始文字只在界面上即时显示，不落盘。
                    • 只有“界面检查器”会在内存里保留屏幕文字，用于调试，可随时清空。
                    • 数据库已排除云备份与设备迁移。
                """.trimIndent(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(title = "关于") {
            Text(
                text = "SmartSkip ${BuildConfig.VERSION_NAME}（${BuildConfig.APPLICATION_ID}）",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "MIT 许可。设计原则：宁可漏掉一个广告，也不要替你误点一次危险按钮。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (aggressiveWarning) {
        AlertDialog(
            onDismissRequest = { aggressiveWarning = false },
            title = { Text("启用激进模式？") },
            text = {
                Text(
                    "激进模式会降低自动跳过阈值，因此可能产生误点击。" +
                        "如果你在支付、购物或输入密码的界面遇到问题，请立刻切回标准或安全模式。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.preferences.setMode(AutoSkipMode.AGGRESSIVE) }
                    aggressiveWarning = false
                }) {
                    Text("我了解风险，启用")
                }
            },
            dismissButton = {
                TextButton(onClick = { aggressiveWarning = false }) { Text("取消") }
            },
        )
    }

    if (pendingClear) {
        AlertDialog(
            onDismissRequest = { pendingClear = false },
            title = { Text("清空全部规则？") },
            text = { Text("删除后所有应用都会回到“首次遇到，仅观察”的状态，需要重新确认。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.ruleStore.deleteAll() }
                    pendingClear = false
                }) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClear = false }) { Text("取消") }
            },
        )
    }

    if (pendingClearEvents) {
        AlertDialog(
            onDismissRequest = { pendingClearEvents = false },
            title = { Text("清空记录？") },
            text = { Text("只删除评估记录，不影响已学习的规则。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { container.eventRecorder.clear() }
                    pendingClearEvents = false
                }) {
                    Text("清空")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingClearEvents = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
