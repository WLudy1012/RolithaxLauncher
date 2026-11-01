package com.wludy.rolithax.launcher.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.wludy.rolithax.launcher.shared.DaemonInstance
import com.wludy.rolithax.launcher.shared.DaemonFile

internal enum class WorkspaceIcon { Server, Console, Folder, Settings, Users, Package, Add, Arrow, Search, Close, Dashboard }

@Composable
internal fun WorkspaceGlyph(icon: WorkspaceIcon, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier.size(20.dp)) {
        val unit = size.width / 20f
        fun line(x: Float, y: Float, x2: Float, y2: Float) = drawLine(color, Offset(x * unit, y * unit), Offset(x2 * unit, y2 * unit), 1.6f * unit)
        fun rect(x: Float, y: Float, width: Float, height: Float) = drawRect(color, Offset(x * unit, y * unit), androidx.compose.ui.geometry.Size(width * unit, height * unit), style = Stroke(1.6f * unit))
        when (icon) {
            WorkspaceIcon.Server -> { rect(3f, 3f, 14f, 6f); rect(3f, 11f, 14f, 6f); line(5f, 6f, 7f, 6f); line(5f, 14f, 7f, 14f) }
            WorkspaceIcon.Console -> { rect(2f, 3f, 16f, 14f); line(5f, 7f, 8f, 10f); line(8f, 10f, 5f, 13f); line(10f, 13f, 15f, 13f) }
            WorkspaceIcon.Folder -> { line(2f, 5f, 8f, 5f); line(8f, 5f, 10f, 7f); line(10f, 7f, 18f, 7f); line(18f, 7f, 18f, 16f); line(18f, 16f, 2f, 16f); line(2f, 16f, 2f, 5f) }
            WorkspaceIcon.Settings -> { line(3f, 5f, 17f, 5f); line(3f, 10f, 17f, 10f); line(3f, 15f, 17f, 15f); rect(6f, 3f, 3f, 4f); rect(12f, 8f, 3f, 4f); rect(7f, 13f, 3f, 4f) }
            WorkspaceIcon.Users -> { drawCircle(color, 3f * unit, Offset(10f * unit, 6f * unit), style = Stroke(1.6f * unit)); line(4f, 17f, 4f, 14f); line(4f, 14f, 7f, 11f); line(7f, 11f, 13f, 11f); line(13f, 11f, 16f, 14f); line(16f, 14f, 16f, 17f) }
            WorkspaceIcon.Package -> { rect(3f, 4f, 14f, 13f); line(3f, 8f, 17f, 8f); line(8f, 4f, 8f, 8f); line(12f, 4f, 12f, 8f) }
            WorkspaceIcon.Add -> { line(10f, 3f, 10f, 17f); line(3f, 10f, 17f, 10f) }
            WorkspaceIcon.Arrow -> { line(7f, 5f, 12f, 10f); line(12f, 10f, 7f, 15f) }
            WorkspaceIcon.Search -> { drawCircle(color, 5.5f * unit, Offset(8.5f * unit, 8.5f * unit), style = Stroke(1.6f * unit)); line(13f, 13f, 18f, 18f) }
            WorkspaceIcon.Dashboard -> { rect(3f, 3f, 6f, 6f); rect(11f, 3f, 6f, 6f); rect(3f, 11f, 6f, 6f); rect(11f, 11f, 6f, 6f) }
            WorkspaceIcon.Close -> { line(5f, 5f, 15f, 15f); line(5f, 15f, 15f, 5f) }
        }
    }
}

@Composable
internal fun WorkspaceNav(label: String, icon: WorkspaceIcon, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(5.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            WorkspaceGlyph(icon, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

@Composable
internal fun WorkspaceConnection(name: String, selected: Boolean, connected: Boolean, enabled: Boolean, onConnect: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(onClick = onConnect, enabled = enabled, shape = RoundedCornerShape(5.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, modifier = Modifier.weight(1f)) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (selected) "当前连接" else if (connected) "已连接 · 点击切换" else "已保存 · 点击连接", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            WindowsTextButton(onClick = { menu = true }, enabled = enabled, contentPadding = PaddingValues(4.dp), modifier = Modifier.width(30.dp).semantics { contentDescription = "连接操作" }) { Text("…") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(if (connected) "断开连接" else "删除保存的连接") }, onClick = { menu = false; confirmRemove = true })
            }
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false }, title = { Text(if (connected) "断开连接？" else "删除保存的连接？") },
        text = { Text(if (connected) "将断开 $name 的当前会话，远端服务器继续运行。" else "将从本机移除 $name 及其保存的凭据。") },
        confirmButton = { WindowsTextButton(onClick = { confirmRemove = false; onRemove() }) { Text("确定") } },
        dismissButton = { WindowsTextButton(onClick = { confirmRemove = false }) { Text("取消") } },
    )
}

@Composable
internal fun WorkspaceMessage(message: String, error: Boolean) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(message, Modifier.padding(12.dp), color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun WorkspaceInstancePicker(instances: List<DaemonInstance>, enabled: Boolean, onSelect: (DaemonInstance) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        WindowsOutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text("切换实例") }
        DropdownMenu(expanded, { expanded = false }) {
            instances.forEach { instance ->
                DropdownMenuItem(text = { Text(instance.name ?: "实例 ${instance.id}") }, onClick = { expanded = false; onSelect(instance) })
            }
        }
    }
}

@Composable
internal fun RemoteFileRow(file: DaemonFile, enabled: Boolean, onOpen: () -> Unit, onDownload: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember(file.name) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(onClick = onOpen, enabled = enabled, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(4.dp), modifier = Modifier.weight(1f)) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                WorkspaceGlyph(if (file.type == "folder") WorkspaceIcon.Folder else WorkspaceIcon.Settings)
                Column(Modifier.weight(1f)) {
                    Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (file.type == "folder") "文件夹" else if (file.size < 1024) "${file.size} B" else if (file.size < 1024 * 1024) "%.1f KB".format(file.size / 1024.0) else "%.1f MB".format(file.size / 1048576.0), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Box {
            WindowsTextButton(onClick = { menu = true }, enabled = enabled) { Text("操作") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text(if (file.type == "folder") "打开" else "编辑文本") }, onClick = { menu = false; onOpen() })
                if (file.type != "folder") DropdownMenuItem(text = { Text("下载") }, onClick = { menu = false; onDownload() })
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
internal fun InstanceOverview(state: DesktopState, modifier: Modifier = Modifier) {
    val info = state.instanceInfo
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        if (info != null) {
            listOf("实例 ID" to info.id.toString(), "状态" to (info.statusText ?: info.status.toString()), "运行时间" to (info.uptime ?: "未提供"), "在线玩家" to info.onlinePlayers.toString(), "Java" to (info.java ?: "未提供"), "核心" to (info.core ?: "未提供")).forEach { (label, value) ->
                item {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(label, Modifier.width(90.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, Modifier.weight(1f))
                    }
                }
            }
        } else item { Text(if (state.busy) "正在读取实例信息…" else "未能读取详情，可重试。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun StatusLabel(instance: DaemonInstance) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = when (instance.status) {
        2 -> if (dark) Color(0xFF74D99F) else Color(0xFF147548)
        1, 4 -> MaterialTheme.colorScheme.primary
        3 -> if (dark) Color(0xFFF0C477) else Color(0xFF916000)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.1f)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(6.dp)) { drawCircle(color) }
            Text(instance.statusText ?: when (instance.status) { 0 -> "已停止"; 1 -> "启动中"; 2 -> "运行中"; 3 -> "停止中"; 4 -> "重启中"; else -> "未知状态" }, color = color, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InstanceBrowser(
    state: DesktopState,
    onOpen: (DaemonInstance, String) -> Unit,
    onAction: (DaemonInstance, String) -> Unit,
    onDelete: (DaemonInstance) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember(state.selectedId) { mutableStateOf("") }
    var filter by remember(state.selectedId) { mutableStateOf("全部") }
    var sortByName by remember { mutableStateOf(true) }
    val instances = remember(state.instances, query, filter, sortByName) {
        state.instances.filter { instance ->
            (query.isBlank() || (instance.name.orEmpty() + " " + instance.id + " " + instance.core.orEmpty()).contains(query.trim(), true)) &&
                (filter == "全部" || filter == "运行中" && instance.status == 2 || filter == "已停止" && instance.status == 0)
        }.let { values -> if (sortByName) values.sortedBy { it.name.orEmpty().lowercase() } else values.sortedBy { it.id } }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WindowsSearchField(query, { query = it }, "搜索名称、ID 或核心", Modifier.weight(1f))
            WindowsTextButton(onClick = { sortByName = !sortByName }) { Text(if (sortByName) "按名称排序" else "按 ID 排序") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "运行中", "已停止").forEach { label ->
                    FilterChip(selected = filter == label, onClick = { filter = label }, label = { Text(label) })
                }
            }
            Spacer(Modifier.weight(1f))
            Text("${instances.size} 个实例", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            if (instances.isEmpty()) item {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (state.busy) "正在读取实例…" else "没有匹配的实例", style = MaterialTheme.typography.titleMedium)
                    Text(if (query.isNotBlank() || filter != "全部") "调整搜索词或筛选条件。" else "通过新建实例开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(instances, key = { it.id }) { instance ->
                InstanceSummaryCard(instance, state.busy, onOpen, onAction, onDelete)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstanceSummaryCard(instance: DaemonInstance, busy: Boolean, onOpen: (DaemonInstance, String) -> Unit, onAction: (DaemonInstance, String) -> Unit, onDelete: (DaemonInstance) -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))) {
        BoxWithConstraints(Modifier.padding(20.dp)) {
            val compact = maxWidth < 700.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    InstanceCardInformation(instance, Modifier.fillMaxWidth())
                    InstanceCardActions(instance, busy, onOpen, onAction, onDelete)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    InstanceCardInformation(instance, Modifier.weight(1f))
                    InstanceCardActions(instance, busy, onOpen, onAction, onDelete)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstanceCardInformation(instance: DaemonInstance, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(56.dp)) {
            Box(contentAlignment = Alignment.Center) { WorkspaceGlyph(WorkspaceIcon.Server, Modifier.size(28.dp), MaterialTheme.colorScheme.primary) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("#${instance.id}  ${instance.name ?: "未命名实例"}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusLabel(instance)
            }
            Text("目录：${instance.basePath ?: "未提供"}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("核心：${instance.core ?: "未提供"}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstanceCardActions(instance: DaemonInstance, busy: Boolean, onOpen: (DaemonInstance, String) -> Unit, onAction: (DaemonInstance, String) -> Unit, onDelete: (DaemonInstance) -> Unit) {
    var more by remember(instance.id) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WindowsButton(onClick = { onOpen(instance, "console") }, enabled = !busy) { Text("控制台") }
            WindowsOutlinedButton(onClick = { onAction(instance, if (instance.status == 0) "start" else "stop") }, enabled = !busy && instance.status in setOf(0, 2)) { Text(if (instance.status == 0) "启动实例" else "停止实例") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WindowsTextButton(onClick = { onOpen(instance, "files") }, enabled = !busy) { Text("文件管理") }
            WindowsTextButton(onClick = { onOpen(instance, "settings") }, enabled = !busy) { Text("设置") }
            Box {
                WindowsTextButton(onClick = { more = true }, enabled = !busy) { Text("更多") }
                DropdownMenu(more, { more = false }) {
                    listOf("重启实例" to "restart", "创建备份" to "backup", "强制结束" to "forceExit").forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { more = false; onAction(instance, action) }, enabled = action != "backup" || instance.status == 2)
                    }
                    DropdownMenuItem(text = { Text("插件与模组") }, onClick = { more = false; onOpen(instance, "plugins") })
                    DropdownMenuItem(text = { Text("资源中心") }, onClick = { more = false; onOpen(instance, "resources") })
                    DropdownMenuItem(text = { Text("实例详情") }, onClick = { more = false; onOpen(instance, "details") })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("删除实例", color = MaterialTheme.colorScheme.error) }, onClick = { more = false; onDelete(instance) })
                }
            }
        }
    }
}

@Composable
internal fun WorkspaceTabs(selectedPage: String, enabled: Boolean, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("概览" to "details", "控制台" to "console", "文件管理" to "files", "配置" to "settings", "插件与模组" to "plugins", "资源" to "resources").forEach { (label, page) ->
                val selected = selectedPage == page
                Column(Modifier.selectable(selected, enabled = enabled, role = Role.Tab, onClick = { onSelect(page) }).padding(horizontal = 10.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, modifier = Modifier.padding(vertical = 8.dp), fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Box(Modifier.width(28.dp).height(3.dp).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(2.dp)))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
