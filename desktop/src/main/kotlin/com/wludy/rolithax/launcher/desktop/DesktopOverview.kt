package com.wludy.rolithax.launcher.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource

@Composable
internal fun DesktopOverview(
    state: DesktopState,
    onInstances: () -> Unit,
    onConnect: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connection = state.connections.firstOrNull { it.id == state.selectedId }
    val connected = connection != null
    val instanceCount = overviewInstanceCount(state, connected, runningOnly = false)
    val runningCount = overviewInstanceCount(state, connected, runningOnly = true)
    val stats = state.systemStats.takeIf { connected }
    val dataState = if (!connected) "尚未连接节点" else if (state.monitorConnected) "等待监控数据" else "监控连接中"
    val cpu = stats?.cpu?.takeIf { it.isFinite() }?.let { "%.1f%%".format(it) } ?: dataState
    val memory = stats?.memUsage?.takeIf { it.isFinite() }?.let { "%.1f%%".format(it) } ?: dataState

    BoxWithConstraints(modifier) {
        val narrow = maxWidth < 620.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource("rolithax_app.png"), null, Modifier.size(64.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("欢迎使用 Rolithax", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (connected) "正在管理 ${connection?.name}" else "连接 Daemon 后即可管理远端实例。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                }
            }
            OverviewSection("节点概况", if (connected) "当前连接" else "未连接") {
                if (narrow) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverviewMetric("节点", connection?.name ?: "未连接", Modifier.fillMaxWidth())
                        OverviewMetric("实例总数", instanceCount, Modifier.fillMaxWidth())
                        OverviewMetric("运行中", runningCount, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OverviewMetric("节点", connection?.name ?: "未连接", Modifier.weight(1f))
                        OverviewMetric("实例总数", instanceCount, Modifier.weight(1f))
                        OverviewMetric("运行中", runningCount, Modifier.weight(1f))
                    }
                }
            }
            OverviewSection("资源监控", if (!connected) "未连接" else if (state.monitorConnected) "实时监控" else "监控连接中") {
                if (narrow) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OverviewMetric("CPU 使用率", cpu, Modifier.fillMaxWidth(), stats?.cpu)
                        OverviewMetric("内存使用率", memory, Modifier.fillMaxWidth(), stats?.memUsage)
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OverviewMetric("CPU 使用率", cpu, Modifier.weight(1f), stats?.cpu)
                        OverviewMetric("内存使用率", memory, Modifier.weight(1f), stats?.memUsage)
                    }
                }
                if (!state.monitorConnected && stats != null && connected) {
                    Text("显示上次收到的数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OverviewSection("快捷操作", "") {
                if (narrow) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WindowsButton(onClick = onInstances, modifier = Modifier.fillMaxWidth(), enabled = connected && !state.busy) { Text("查看实例") }
                        WindowsOutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth(), enabled = connected && !state.busy) { Text("创建实例") }
                        WindowsOutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("添加连接") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WindowsButton(onClick = onInstances, enabled = connected && !state.busy) { Text("查看实例") }
                        WindowsOutlinedButton(onClick = onCreate, enabled = connected && !state.busy) { Text("创建实例") }
                        WindowsOutlinedButton(onClick = onConnect, enabled = !state.busy) { Text("添加连接") }
                    }
                }
            }
        }
    }
}

internal fun overviewInstanceCount(state: DesktopState, connected: Boolean, runningOnly: Boolean): String = when {
    !connected -> "—"
    state.instancesLoaded -> if (runningOnly) state.instances.count { it.status == 2 }.toString() else state.instances.size.toString()
    state.busy -> "加载中"
    else -> "未知"
}

@Composable
private fun OverviewSection(title: String, detail: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun OverviewMetric(label: String, value: String, modifier: Modifier = Modifier, usage: Double? = null) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (usage != null && usage.isFinite()) LinearProgressIndicator(progress = { (usage / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp))
        }
    }
}
