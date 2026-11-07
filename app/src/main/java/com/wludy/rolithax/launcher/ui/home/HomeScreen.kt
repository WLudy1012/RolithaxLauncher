package com.wludy.rolithax.launcher.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.clickable
import com.wludy.rolithax.launcher.data.ServerRef
import com.wludy.rolithax.launcher.ui.RolithaxCard
import com.wludy.rolithax.launcher.ui.RolithaxSectionHeader
import com.wludy.rolithax.launcher.ui.StatusBadge
import com.wludy.rolithax.launcher.ui.WorkspaceBar
import com.wludy.rolithax.launcher.ui.WorkspaceBody
import com.wludy.rolithax.launcher.ui.WorkspaceError
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    onOpenInstances: (String?) -> Unit,
    onOpenConnections: () -> Unit,
    onCreate: () -> Unit,
    onEditDaemon: (String) -> Unit,
    onOpenServer: (ServerRef) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selectedDaemon = state.pages.firstOrNull { it.daemonId == state.selectedDaemonId }
    val featured = remember(state.servers) { state.servers.sortedByDescending { it.running }.take(4) }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            WorkspaceBar("概览") {
                IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                    Icon(Icons.Filled.Refresh, "刷新概览")
                }
            }
        },
    ) { innerPadding ->
        WorkspaceBody(Modifier.padding(innerPadding)) {
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item("overview") {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = MaterialTheme.shapes.extraLarge,
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Text("Rolithax Launcher", style = MaterialTheme.typography.labelLarge)
                                Text("服务器工作台", style = MaterialTheme.typography.headlineMedium)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val countsKnown = !state.loading && state.error == null
                                    val qualified = state.daemonFailures.isNotEmpty()
                                    OverviewMetric(
                                        if (countsKnown) "${state.runningServers}" else "—",
                                        if (qualified) "已知运行中" else "运行中",
                                        Modifier.weight(1f),
                                    )
                                    OverviewMetric(
                                        if (countsKnown) "${state.servers.size}" else "—",
                                        if (qualified) "已加载实例" else "实例",
                                        Modifier.weight(1f),
                                    )
                                    OverviewMetric(
                                        if (state.loading) "—" else "${state.pages.count { it.online == true }}/${state.pages.size}",
                                        "在线节点",
                                        Modifier.weight(1f),
                                    )
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = onCreate) {
                                        Icon(Icons.Filled.Add, null, Modifier.padding(end = 6.dp))
                                        Text("新建实例")
                                    }
                                    TextButton(onClick = onOpenConnections) { Text("管理连接") }
                                }
                            }
                        }
                    }
                    state.error?.let { error ->
                        item("catalog-error") { WorkspaceError("实例列表加载失败", error, "重试", viewModel::refresh) }
                    }
                    if (state.daemonFailures.isNotEmpty()) {
                        item("node-errors") {
                            WorkspaceError(
                                "部分实例未加载",
                                "${state.daemonFailures.size} 个 Daemon 的实例列表暂不可用。",
                                "查看实例",
                                { onOpenInstances(null) },
                            )
                        }
                    }
                    item("monitor") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            RolithaxSectionHeader(
                                title = "远端监控",
                                actionLabel = if (state.pages.isNotEmpty()) "管理连接" else null,
                                onAction = onOpenConnections,
                            )
                            if (selectedDaemon != null) {
                                DaemonMonitor(
                                    page = selectedDaemon,
                                    pages = state.pages,
                                    failure = state.daemonFailures[selectedDaemon.daemonId],
                                    onSelect = viewModel::selectDaemon,
                                    onReconnect = { viewModel.reconnect(selectedDaemon.daemonId) },
                                    onEdit = { onEditDaemon(selectedDaemon.daemonId) },
                                    onOpenInstances = { onOpenInstances(selectedDaemon.daemonId) },
                                )
                            } else if (state.loading) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else {
                                RolithaxCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text("添加 MSLX Daemon 后，可在这里查看负载和实例状态。")
                                        TextButton(onClick = onOpenConnections) { Text("打开连接") }
                                    }
                                }
                            }
                        }
                    }
                    item("instance-heading") {
                        RolithaxSectionHeader(
                            title = "实例",
                            actionLabel = "查看全部",
                            onAction = { onOpenInstances(null) },
                        )
                    }
                    if (featured.isEmpty() && !state.loading && !state.refreshing && state.error == null &&
                        state.daemonFailures.isEmpty()
                    ) {
                        item("empty-instances") {
                            Text("暂无实例。可以在本机或已连接的 Daemon 上创建。")
                        }
                    }
                    items(featured, key = { "instance_${it.key}" }) { server ->
                        val ref = server.ref
                        RolithaxCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = ref?.let { { onOpenServer(it) } },
                        ) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                headlineContent = { Text(server.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(server.sourceLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = { Icon(Icons.AutoMirrored.Filled.List, null) },
                                trailingContent = { StatusBadge(server.status) },
                            )
                        }
                    }

                    item("activity") {
                        RolithaxSectionHeader(
                            title = "开服动态",
                            actionLabel = if (state.notifications.isNotEmpty()) "清空" else null,
                            onAction = viewModel::clearNotifications,
                        )
                    }
                    if (state.notifications.isEmpty()) {
                        item("empty-activity") {
                            Text("暂无启停记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    items(state.notifications, key = { "activity_${it.ref.catalogKey}_${it.time}" }) { notification ->
                        val timestamp = remember(notification.time) {
                            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(notification.time))
                        }
                        ListItem(
                            modifier = Modifier.clickable { onOpenServer(notification.ref) },
                            headlineContent = {
                                Text("${notification.instanceName} · ${if (notification.isOpened) "已启动" else "已停止"}")
                            },
                            supportingContent = { Text("${notification.sourceLabel} · $timestamp") },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverviewMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DaemonMonitor(
    page: DaemonPage,
    pages: List<DaemonPage>,
    failure: String?,
    onSelect: (String) -> Unit,
    onReconnect: () -> Unit,
    onEdit: () -> Unit,
    onOpenInstances: () -> Unit,
) {
    var selecting by remember { mutableStateOf(false) }
    RolithaxCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box {
                OutlinedButton(onClick = { selecting = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = page.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.ArrowDropDown, "选择监控节点")
                }
                DropdownMenu(expanded = selecting, onDismissRequest = { selecting = false }) {
                    pages.forEach { daemon ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    daemon.name + if (daemon.isDefault) " · 默认" else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            onClick = {
                                selecting = false
                                onSelect(daemon.daemonId)
                            },
                        )
                    }
                }
            }
            Text(
                listOfNotNull(
                    when (page.online) {
                        true -> "在线"
                        false -> "离线"
                        null -> "检测中"
                    },
                    page.latencyMs?.let { "$it ms" },
                    page.protocol.takeIf(String::isNotBlank),
                    page.osText.takeIf(String::isNotBlank),
                    page.version.takeIf(String::isNotBlank)?.let { "v$it" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = if (page.online == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(page.baseUrl, style = MaterialTheme.typography.bodySmall)
            val warning = failure ?: page.message
            if (!warning.isNullOrBlank()) {
                WorkspaceError("节点数据不可用", warning, "重试", onReconnect)
            } else if (page.online == true) {
                Text(
                    "${page.instanceRunning}/${page.instanceTotal} 个实例运行中 · ${page.onlinePlayers} 名在线玩家",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            MetricBar("CPU", page.cpu, page.cpu?.let { "${it.metric()}%" } ?: "暂无数据")
            MetricBar(
                "内存",
                page.memoryPercent,
                if (page.memoryUsedGb != null && page.memoryTotalGb != null) {
                    "${page.memoryUsedGb.metric()} / ${page.memoryTotalGb.metric()} GB"
                } else {
                    page.memoryPercent?.let { "${it.metric()}%" } ?: "暂无数据"
                },
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenInstances) { Text("查看此节点实例") }
                TextButton(onClick = onEdit) { Text("编辑连接") }
            }
        }
    }
}

@Composable
private fun MetricBar(label: String, percent: Double?, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.bodySmall)
        }
        if (percent != null && percent.isFinite()) {
            LinearProgressIndicator(
                progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun Double.metric(): String = String.format(Locale.US, "%.1f", this)
