package io.github.szzgx.smartskip.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.szzgx.smartskip.accessibility.InspectorCommand
import io.github.szzgx.smartskip.accessibility.InspectorNode
import io.github.szzgx.smartskip.accessibility.UiDumpProvider
import io.github.szzgx.smartskip.ui.components.SectionCard
import io.github.szzgx.smartskip.ui.components.StatusPill
import io.github.szzgx.smartskip.ui.theme.StatusColors

/**
 * Phase 0's deliverable: a UI Inspector (design doc §14).
 *
 * ## Why this screen matters more than it looks
 *
 * Almost everything SmartSkip knows about an ad comes from the accessibility tree, and no two
 * ad SDKs expose themselves the same way. Without a way to *see* the tree, every decision rule
 * is guesswork, and a user reporting "it clicked the wrong thing" cannot be investigated at all.
 *
 * The capture shown here also includes the full scoring for every skip-shaped node, so it
 * answers the question that actually matters: *what would SmartSkip decide about this screen,
 * and why?*
 */
@Composable
fun InspectorScreen() {
    val context = LocalContext.current
    val snapshot by UiDumpProvider.snapshot.collectAsStateWithLifecycle()

    var serviceAvailable by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "界面检查器", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "这里显示当前前台应用的控件树，以及 SmartSkip 对每个“跳过”类控件的完整评分。" +
                    "想看某个应用的开屏广告：先让它出现在屏幕上，再回到这里点“重新抓取”。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = {
                    serviceAvailable = InspectorCommand.requestFromUi()
                }) {
                    Text("重新抓取（含评分）")
                }
                TextButton(onClick = { UiDumpProvider.clear() }) {
                    Text("清空")
                }
            }

            if (!serviceAvailable) {
                Text(
                    text = "无障碍服务当前没有运行，无法抓取其他应用的界面。请先在“状态”页开启权限。",
                    style = MaterialTheme.typography.bodySmall,
                    color = StatusColors.danger,
                )
            }
        }

        if (snapshot.nodes.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "还没有抓取到内容",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "服务在评估每一帧时会自动填充这里的原始树；" +
                        "点击上面的按钮可以得到带完整评分的一次抓取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item {
                SectionCard(
                    title = snapshot.packageName.ifBlank { "未知包名" },
                    subtitle = buildString {
                        append("Activity: ")
                        append(snapshot.activityName?.substringAfterLast('.') ?: "未知")
                        append("　屏幕 ")
                        append(snapshot.screenWidth)
                        append(" × ")
                        append(snapshot.screenHeight)
                    },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusPill(
                            text = "节点 ${snapshot.nodes.size}",
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        StatusPill(
                            text = "候选 ${snapshot.candidateCount}",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (snapshot.adContextAvailable) {
                            StatusPill("发现广告标识", StatusColors.observedContainer, StatusColors.observed)
                        }
                        if (snapshot.paymentContextAvailable) {
                            StatusPill("发现支付相关", StatusColors.dangerContainer, StatusColors.danger)
                        }
                        if (snapshot.truncated) {
                            StatusPill("树被截断", StatusColors.vetoedContainer, StatusColors.vetoed)
                        }
                    }
                    if (!snapshot.scored) {
                        Text(
                            text = "这是未评分的原始树（服务自动填充）。点“重新抓取”以获得完整评分。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(snapshot.nodes, key = { it.index }) { node ->
                InspectorNodeRow(node)
            }
        }
    }
}

@Composable
private fun InspectorNodeRow(node: InspectorNode) {
    // Only nodes with something to say are given a card; the rest are one compact line, because
    // a real screen has hundreds of nodes and scanning them must stay possible.
    val interesting = node.isCandidate || node.textSignals.isNotEmpty()

    if (!interesting) {
        Text(
            text = "${node.indent}${node.className.substringAfterLast('.')}" +
                if (node.viewId != null) "  #${node.viewId.substringAfterLast('/')}" else "",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (node.isCandidate) {
                MaterialTheme.colorScheme.surfaceVariant
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = node.className.substringAfterLast('.'),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (node.isCandidate) {
                        StatusPill(
                            text = "候选",
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    node.band?.let {
                        StatusPill(
                            text = "${node.score} ${bandLabel(it)}",
                            containerColor = if (node.wouldClick) {
                                StatusColors.actedContainer
                            } else {
                                StatusColors.observedContainer
                            },
                            contentColor = if (node.wouldClick) StatusColors.acted else StatusColors.observed,
                        )
                    }
                }
            }

            if (node.text.isNotEmpty()) {
                Text(text = "text: ${node.text}", style = MaterialTheme.typography.bodySmall)
            }
            if (node.contentDescription.isNotEmpty()) {
                Text(
                    text = "desc: ${node.contentDescription}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            node.viewId?.let {
                Text(
                    text = "id:   $it",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "bounds: ${node.bounds}　clickable=${node.clickable}　editable=${node.editable}　" +
                    "visible=${node.visibleToUser}　children=${node.childCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (node.textSignals.isNotEmpty()) {
                Text(
                    text = "文本证据: ${node.textSignals.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (node.effectiveThreshold != null) {
                Text(
                    text = if (node.wouldClick) {
                        "结论：会点击（阈值 ${node.effectiveThreshold}）"
                    } else {
                        "结论：不点击（阈值 ${node.effectiveThreshold}）"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (node.wouldClick) StatusColors.acted else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (node.vetoes.isNotEmpty()) {
                Text(
                    text = "安全否决: ${node.vetoes.joinToString("；")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = StatusColors.vetoed,
                )
            }

            if (node.contributions.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    node.contributions.forEach { entry ->
                        val code = entry.substringBefore('=')
                        val points = entry.substringAfter('=', "")
                        val color: Color = if (points.startsWith("-")) {
                            StatusColors.danger
                        } else {
                            StatusColors.acted
                        }
                        Text(
                            text = "$points  $code",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = color,
                        )
                    }
                }
            }
        }
    }
}

private fun bandLabel(band: String): String = when (band) {
    "IGNORE" -> "不处理"
    "OBSERVE" -> "观察"
    "CANDIDATE" -> "候选"
    "HIGH" -> "高置信"
    else -> band
}
