package io.github.szzgx.smartskip.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import io.github.szzgx.smartskip.SmartSkipApplication
import io.github.szzgx.smartskip.data.db.AppRuleEntity
import io.github.szzgx.smartskip.data.rules.RuleStore
import io.github.szzgx.smartskip.ui.components.KeyValueRow
import io.github.szzgx.smartskip.ui.components.MonoText
import io.github.szzgx.smartskip.ui.components.StatusPill
import io.github.szzgx.smartskip.ui.theme.StatusColors
import kotlinx.coroutines.launch

/**
 * The learned-rule database (design doc §9's "用户自己的开屏广告规则数据库").
 *
 * This screen exists because the app can act on the user's behalf, and anything that can act
 * on your behalf must be inspectable and revocable. Every rule can be confirmed, disabled,
 * rejected or deleted, and rejection is permanent for automatic action.
 */
@Composable
fun RulesScreen() {
    val context = LocalContext.current
    val container = remember(context) { SmartSkipApplication.containerOf(context) }
    val scope = rememberCoroutineScope()

    val rules by remember(container) {
        container.appRuleDao.observeAll()
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    var pendingDelete by remember { mutableStateOf<AppRuleEntity?>(null) }
    var pendingReject by remember { mutableStateOf<AppRuleEntity?>(null) }

    if (rules.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("还没有规则", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "SmartSkip 在识别到“跳过”类控件后会建立规则。" +
                    "规则按“包名 + Activity + 控件结构指纹”索引，因此广告文案变化不会让规则失效。" +
                    "首次遇到的页面只会观察，需要你确认后才会自动跳过。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(text = "${rules.size} 条规则", style = MaterialTheme.typography.titleMedium)
        }
        items(rules, key = { "${it.packageName}|${it.activityName}|${it.structureSignature}" }) { rule ->
            RuleCard(
                rule = rule,
                onConfirm = {
                    scope.launch {
                        container.ruleStore.confirm(
                            rule.packageName,
                            rule.activityName.ifBlank { null },
                            rule.structureSignature,
                        )
                    }
                },
                onToggleEnabled = { enabled ->
                    scope.launch {
                        container.ruleStore.setEnabled(
                            rule.packageName,
                            rule.activityName.ifBlank { null },
                            rule.structureSignature,
                            enabled,
                        )
                    }
                },
                onReject = { pendingReject = rule },
                onDelete = { pendingDelete = rule },
            )
        }
    }

    pendingReject?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingReject = null },
            title = { Text("标记为误操作？") },
            text = {
                Text(
                    "该规则将被停用并永久拒绝自动执行。历史上它造成过 " +
                        "${rule.failureCount} 次失误。你之后仍可手动重新启用。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.ruleStore.reject(
                            rule.packageName,
                            rule.activityName.ifBlank { null },
                            rule.structureSignature,
                        )
                    }
                    pendingReject = null
                }) {
                    Text("标记为误操作")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingReject = null }) { Text("取消") }
            },
        )
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条规则？") },
            text = { Text("删除后 SmartSkip 会重新学习该控件。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.ruleStore.delete(
                            rule.packageName,
                            rule.activityName.ifBlank { null },
                            rule.structureSignature,
                        )
                    }
                    pendingDelete = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RuleCard(
    rule: AppRuleEntity,
    onConfirm: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onReject: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = rule.packageName, style = MaterialTheme.typography.titleSmall)

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    rule.userRejected ->
                        StatusPill("已标记误操作", StatusColors.dangerContainer, StatusColors.danger)

                    rule.userConfirmed ->
                        StatusPill("已确认", StatusColors.actedContainer, StatusColors.acted)

                    rule.dangerousFailureCount > 0 ->
                        StatusPill("曾危险误点", StatusColors.dangerContainer, StatusColors.danger)

                    rule.successCount > 0 ->
                        StatusPill("已验证", StatusColors.actedContainer, StatusColors.acted)

                    else ->
                        StatusPill("观察中", StatusColors.observedContainer, StatusColors.observed)
                }

                if (!rule.isEnabled) {
                    StatusPill("已停用", MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            KeyValueRow("置信度", "${(rule.confidence * 100).toInt()}%")
            KeyValueRow("成功 / 失败", "${rule.successCount} / ${rule.failureCount}")
            if (rule.dangerousFailureCount > 0) {
                KeyValueRow(
                    key = "其中危险误点",
                    value = rule.dangerousFailureCount.toString(),
                    valueColor = StatusColors.danger,
                )
            }
            rule.activityName.takeIf { it.isNotBlank() }?.let {
                KeyValueRow("Activity", it.substringAfterLast('.'))
            }
            KeyValueRow(
                "最近使用",
                DateUtils.getRelativeTimeSpanString(
                    rule.lastUsedMs,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                ).toString(),
            )
            rule.textObservation.takeIf { it.isNotBlank() }?.let {
                KeyValueRow("文本特征", it)
            }

            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "结构指纹",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MonoText(rule.structureSignature)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("启用", style = MaterialTheme.typography.bodySmall)
                    Switch(
                        checked = rule.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Row {
                    if (!rule.userConfirmed) {
                        TextButton(onClick = onConfirm) { Text("确认可用") }
                    }
                    TextButton(onClick = onReject) { Text("误操作") }
                    TextButton(onClick = onDelete) { Text("删除") }
                }
            }
        }
    }
}
