package com.wludy.rolithax.launcher.ui.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wludy.rolithax.launcher.shared.console.ConsolePanelNode

@Composable
internal fun ConsolePluginPanel(
    panel: ConsolePanelUiState,
    actionsEnabled: Boolean,
    importing: Boolean,
    onImport: () -> Unit,
    onOpenSources: () -> Unit,
    onRestore: () -> Unit,
    onAction: (String, Map<String, String>, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snapshot = panel.snapshot
    val inputs = remember(snapshot.pluginId, snapshot.packageRevision) { mutableStateMapOf<String, String>() }
    var showEvidence by remember(snapshot.pluginId, snapshot.packageRevision) { mutableStateOf(false) }
    var showManage by remember { mutableStateOf(false) }
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(onClick = { showManage = true }, enabled = !importing, modifier = Modifier.fillMaxWidth()) {
                        Text(if (importing) "正在读取…" else snapshot.title, modifier = Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.KeyboardArrowDown, "管理 Schema 插件", Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = showManage, onDismissRequest = { showManage = false }) {
                        DropdownMenuItem(text = { Text("导入本地 ZIP") }, onClick = { showManage = false; onImport() })
                        if (snapshot.pluginId != "rolithax.paper") {
                            DropdownMenuItem(text = { Text("恢复内置 Schema") }, onClick = { showManage = false; onRestore() })
                        }
                    }
                }
                OutlinedButton(onClick = onOpenSources, enabled = !importing) {
                    Icon(Icons.Default.Settings, null, Modifier.size(18.dp))
                    Text("插件源", Modifier.padding(start = 6.dp))
                }
            }
            Text(
                if (snapshot.pluginId == "rolithax.paper") "Rolithax Schema · 内置"
                else "${snapshot.pluginId} · ${if (panel.commands.isEmpty()) "只读" else "已授权 ${panel.commands.size} 项操作"}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (snapshot.stale || panel.runtimeError != null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(panel.runtimeError ?: "部分日志缺失，以下结果可能不完整。可展开日志依据核对。")
                    if (panel.runtimeError != null) TextButton(onClick = onRestore) { Text("恢复内置 Schema") }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 720.dp && snapshot.nodes.all { it.kind == "section" }) 2 else 1
            Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                for (group in snapshot.nodes.chunked(columns)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        for (node in group) key(node.id) {
                            PanelNode(node, panel, inputs, actionsEnabled, onAction, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        if (snapshot.evidence.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TextButton(onClick = { showEvidence = !showEvidence }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showEvidence) "收起日志依据" else "日志依据 · ${snapshot.evidence.size} 项", modifier = Modifier.weight(1f))
                Icon(Icons.Default.KeyboardArrowDown, null)
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
    modifier: Modifier = Modifier,
) {
    val snapshot = panel.snapshot
    val value = node.field?.let(snapshot.values::get)?.takeIf(String::isNotBlank)
    val display = value ?: if (node.field in snapshot.evidence) "无" else "等待日志"
    val tone = schemaTone(node.tone)
    when (node.kind) {
        "section" -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(node.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            SchemaDescription(node.description)
            for (child in node.children) key(child.id) {
                PanelNode(child, panel, inputs, actionsEnabled, onAction)
            }
        }
        "row" -> BoxWithConstraints(modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 480.dp) node.children.size.coerceIn(1, 3) else 1
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (group in node.children.chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (child in group) key(child.id) {
                        PanelNode(child, panel, inputs, actionsEnabled, onAction, Modifier.weight(1f))
                    }
                }
            }
        }
        "field" -> node.field?.let { field ->
            OutlinedTextField(
                value = inputs[field].orEmpty(),
                onValueChange = { inputs[field] = it.take(128) },
                label = { Text(node.label) },
                placeholder = node.placeholder?.let { { Text(it) } },
                supportingText = node.description?.let { { Text(it) } },
                singleLine = true,
                modifier = modifier.fillMaxWidth(),
            )
        }
        "button" -> node.action?.let { action ->
            val command = panel.commands[action]
            val fields = remember(command) { schemaParameter.findAll(command.orEmpty()).map { it.groupValues[1] }.distinct().toList() }
            val arguments = fields.associateWith { inputs[it].orEmpty().trim() }
            val valid = arguments.values.all { it.isNotEmpty() && it.length <= 128 && it.none(Char::isWhitespace) }
            Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { onAction(action, arguments, snapshot.packageRevision) },
                    enabled = actionsEnabled && command != null && valid,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = tone, contentColor = schemaOnTone(node.tone)),
                ) { Text(node.label) }
                SchemaDescription(
                    if (command == null) "此操作尚未授权，重新加载插件可更改权限。" else node.description,
                )
            }
        }
        "metric" -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(node.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(display, style = if (value == null) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.headlineMedium,
                    color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else tone,
                    modifier = Modifier.weight(1f, fill = false))
                if (value != null) node.unit?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            SchemaDescription(node.description)
        }
        "progress" -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val progress = value?.removeSuffix("%")?.trim()?.toFloatOrNull()?.takeIf(Float::isFinite)?.coerceIn(0f, 100f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(node.label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(if (progress == null) "等待日志" else "${progress.toString().removeSuffix(".0")}${node.unit ?: "%"}",
                    style = MaterialTheme.typography.labelLarge, color = tone)
            }
            if (progress != null) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth(), color = tone)
            SchemaDescription(node.description)
        }
        "badge", "status" -> Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(node.label, style = MaterialTheme.typography.bodyMedium)
                SchemaDescription(node.description)
            }
            Surface(color = tone.copy(alpha = 0.12f), contentColor = tone, shape = MaterialTheme.shapes.small,
                modifier = Modifier.widthIn(max = 220.dp)) {
                Text(display, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
        "list" -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(node.label, style = MaterialTheme.typography.titleSmall)
            SchemaDescription(node.description)
            val lines = value.orEmpty().lineSequence().filter(String::isNotBlank).toList()
            if (lines.isEmpty()) Text(display, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else lines.forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        "divider" -> HorizontalDivider(modifier = modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
        "text" -> Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (node.field == null) Text(node.label, style = MaterialTheme.typography.bodyMedium)
            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(node.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f))
                Text(display + if (value == null) "" else node.unit?.let { " $it" }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            SchemaDescription(node.description)
        }
    }
}

@Composable
private fun SchemaDescription(text: String?) {
    if (!text.isNullOrBlank()) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun schemaTone(tone: String?): Color = when (tone) {
    "danger", "error" -> MaterialTheme.colorScheme.error
    "warning" -> MaterialTheme.colorScheme.tertiary
    "neutral" -> MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.primary
}

@Composable
private fun schemaOnTone(tone: String?): Color = when (tone) {
    "danger", "error" -> MaterialTheme.colorScheme.onError
    "warning" -> MaterialTheme.colorScheme.onTertiary
    "neutral" -> MaterialTheme.colorScheme.onSecondary
    else -> MaterialTheme.colorScheme.onPrimary
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

private val schemaParameter = Regex("\\{([a-z][a-z0-9-]{0,63})\\}")
