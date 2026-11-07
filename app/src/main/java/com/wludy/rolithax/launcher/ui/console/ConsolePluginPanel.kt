package com.wludy.rolithax.launcher.ui.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wludy.rolithax.launcher.shared.console.ConsolePanelNode

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConsolePluginPanel(
    panel: ConsolePanelUiState,
    actionsEnabled: Boolean,
    importing: Boolean,
    onImport: () -> Unit,
    onRestore: () -> Unit,
    onAction: (String, Map<String, String>, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = panel.snapshot
    val inputs = remember(snapshot.pluginId, snapshot.packageRevision) { mutableStateMapOf<String, String>() }
    var showEvidence by remember(snapshot.pluginId, snapshot.packageRevision) { mutableStateOf(false) }
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(snapshot.title, style = MaterialTheme.typography.titleLarge)
        Text(
            text = if (snapshot.pluginId == "rolithax.paper") "内置 Paper 面板" else snapshot.pluginId,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onImport, enabled = !importing) {
                Text(if (importing) "正在读取…" else "导入扩展 ZIP")
            }
            if (snapshot.pluginId != "rolithax.paper") {
                TextButton(onClick = onRestore, enabled = !importing) { Text("恢复内置面板") }
            }
        }
        if (snapshot.stale) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("有日志未能恢复，面板数据可能不完整。", Modifier.padding(12.dp))
            }
        }
        for (node in snapshot.nodes) key(node.id) {
            PanelNode(node, panel, inputs, actionsEnabled, onAction)
        }
        if (snapshot.evidence.isNotEmpty()) {
            TextButton(onClick = { showEvidence = !showEvidence }) {
                Text(if (showEvidence) "收起来源日志" else "展开来源日志")
            }
            if (showEvidence) {
                val labels = remember(snapshot.nodes) {
                    buildMap { collectPanelLabels(snapshot.nodes, this) }
                }
                SelectionContainer {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for ((field, line) in snapshot.evidence) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(labels[field] ?: field, style = MaterialTheme.typography.labelLarge)
                                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun collectPanelLabels(nodes: List<ConsolePanelNode>, labels: MutableMap<String, String>) {
    for (node in nodes) {
        if (node.kind != "field") node.field?.let { labels[it] = node.label }
        collectPanelLabels(node.children, labels)
    }
}

@Composable
private fun PanelNode(
    node: ConsolePanelNode,
    panel: ConsolePanelUiState,
    inputs: MutableMap<String, String>,
    actionsEnabled: Boolean,
    onAction: (String, Map<String, String>, Long) -> Unit,
) {
    val snapshot = panel.snapshot
    when (node.kind) {
        "section" -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(node.label, style = MaterialTheme.typography.titleMedium)
                for (child in node.children) key(child.id) {
                    PanelNode(child, panel, inputs, actionsEnabled, onAction)
                }
            }
        }
        "field" -> node.field?.let { field ->
            OutlinedTextField(
                value = inputs[field].orEmpty(),
                onValueChange = { inputs[field] = it.take(16) },
                label = { Text(node.label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        "button" -> node.action?.let { action ->
            val command = panel.commands[action]
            val field = command?.substringAfter(' ', "")?.removeSurrounding("{", "}")?.takeIf(String::isNotEmpty)
            val argument = field?.let { inputs[it]?.trim().orEmpty() }
            val valid = argument == null || playerName.matches(argument)
            FilledTonalButton(
                onClick = {
                    val args = if (field != null && argument != null) mapOf(field to argument) else emptyMap()
                    onAction(action, args, snapshot.packageRevision)
                },
                enabled = actionsEnabled && command != null && valid,
            ) {
                Text(node.label)
            }
            if (command == null) {
                Text(
                    "未授权发送命令",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        "text", "status", "list" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val value = node.field?.let(snapshot.values::get)
            Text(node.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val lines = if (node.kind == "list") value.orEmpty().lineSequence().filter(String::isNotBlank).toList() else emptyList()
            if (lines.isNotEmpty()) {
                lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            } else if (node.field != null) {
                Text(
                    value?.takeIf(String::isNotBlank)
                        ?: if (node.field in snapshot.evidence) "无" else "等待日志",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
internal fun ConsolePluginPermissionDialog(
    imported: ConsolePluginImport,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var grants by remember(imported) { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(imported.title) },
        text = {
            Column(
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(imported.id, style = MaterialTheme.typography.labelMedium)
                Text("扩展仅在当前控制台会话内使用。勾选后，面板按钮可向此实例发送对应命令。")
                for ((id, template) in imported.commands) {
                    val checked = id in grants
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(checked, role = Role.Checkbox) { allow ->
                                grants = if (allow) grants + id else grants - id
                            },
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null)
                        Column {
                            Text(template, fontFamily = FontFamily.Monospace)
                            Text(id, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(grants) }) {
                Text(if (grants.isEmpty()) "以只读方式加载" else "授权并加载")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val playerName = Regex("[a-zA-Z0-9_]{1,16}")
