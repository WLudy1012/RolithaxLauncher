package com.wludy.rolithax.launcher.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wludy.rolithax.launcher.data.DaemonConfig
import com.wludy.rolithax.launcher.data.DaemonState
import com.wludy.rolithax.launcher.data.DaemonStatus
import com.wludy.rolithax.launcher.data.ServerRef
import com.wludy.rolithax.launcher.ui.RolithaxCard
import com.wludy.rolithax.launcher.ui.RolithaxEmptyState
import com.wludy.rolithax.launcher.ui.RolithaxSectionHeader
import com.wludy.rolithax.launcher.ui.RolithaxStatusPill
import com.wludy.rolithax.launcher.ui.WorkspaceBar
import com.wludy.rolithax.launcher.ui.WorkspaceBody
import com.wludy.rolithax.launcher.ui.WorkspaceError

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ConnectionsScreen(
    onAddDaemon: () -> Unit,
    onEditDaemon: (String) -> Unit,
    onOpenInstances: (String) -> Unit,
    onCreate: (String) -> Unit,
    onOpenLocalServerSettings: () -> Unit,
    onOpenUserCenter: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val statuses by viewModel.daemonStatuses.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshingConnections.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var removing by remember { mutableStateOf<DaemonConfig?>(null) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshConnections()
        onPauseOrDispose { }
    }
    LaunchedEffect(viewModel) {
        viewModel.message.collect { snackbar.showSnackbar(it) }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            WorkspaceBar("连接") {
                IconButton(onClick = viewModel::refreshConnections, enabled = !refreshing) {
                    Icon(Icons.Filled.Refresh, "检测连接")
                }
                IconButton(onClick = onAddDaemon) { Icon(Icons.Filled.Add, "添加 Daemon") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        WorkspaceBody(Modifier.padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item("local") {
                    RolithaxCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("本机", style = MaterialTheme.typography.titleLarge)
                            Text("使用此设备运行服务器。", style = MaterialTheme.typography.bodyMedium)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { onCreate(ServerRef.LOCAL_DAEMON_ID) }) { Text("新建本机实例") }
                                TextButton(onClick = { onOpenInstances(ServerRef.LOCAL_DAEMON_ID) }) { Text("查看实例") }
                                TextButton(onClick = onOpenLocalServerSettings) { Text("运行时设置") }
                            }
                        }
                    }
                }
                item("remote-heading") {
                    RolithaxSectionHeader(
                        title = "远端 Daemon",
                        subtitle = "默认连接用于预选新建实例的目标。",
                        actionLabel = if (settings.daemons.isNotEmpty()) "用户管理" else null,
                        onAction = onOpenUserCenter,
                    )
                }
                if (refreshing) {
                    item("refreshing") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
                if (settings.daemonDecodeFailed) {
                    item("config-error") {
                        WorkspaceError("连接配置无法读取", "请重新添加 Daemon。")
                    }
                }
                if (settings.daemons.isEmpty() && !refreshing) {
                    item("empty") {
                        RolithaxEmptyState(
                            icon = Icons.Filled.Share,
                            title = "还没有远端连接",
                            description = "使用 Daemon 地址与 API 密钥，或扫描配对二维码。",
                            actionLabel = "添加 Daemon",
                            onAction = onAddDaemon,
                        )
                    }
                }
                items(settings.daemons, key = { "daemon_${it.id}" }) { daemon ->
                    val isDefault = daemon.id == settings.activeDaemonId
                    ConnectionCard(
                        daemon = daemon,
                        status = statuses[daemon.id],
                        isDefault = isDefault,
                        onViewInstances = { onOpenInstances(daemon.id) },
                        onCreate = { onCreate(daemon.id) },
                        onEdit = { onEditDaemon(daemon.id) },
                        onSetDefault = { viewModel.setActiveDaemon(daemon.id) },
                        onRemove = { removing = daemon },
                    )
                }
            }
        }
    }

    removing?.let { target ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除连接") },
            text = { Text("移除本设备保存的「${target.name.ifBlank { target.baseUrl }}」连接？远端实例会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeDaemon(target.id)
                    removing = null
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionCard(
    daemon: DaemonConfig,
    status: DaemonStatus?,
    isDefault: Boolean,
    onViewInstances: () -> Unit,
    onCreate: () -> Unit,
    onEdit: () -> Unit,
    onSetDefault: () -> Unit,
    onRemove: () -> Unit,
) {
    RolithaxCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    daemon.name.ifBlank { daemon.baseUrl },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                RolithaxStatusPill(
                    label = status?.stateText ?: "未检测",
                    color = when (status?.state) {
                        DaemonState.ONLINE -> MaterialTheme.colorScheme.primary
                        DaemonState.OFFLINE -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(daemon.baseUrl, style = MaterialTheme.typography.bodyMedium)
            val detail = listOfNotNull(
                "默认连接".takeIf { isDefault },
                status?.version?.let { "v$it" },
                status?.latencyMs?.let { "$it ms" },
                status?.instanceCount?.let { "$it 个实例" },
                "备用地址 ${daemon.endpoints.size} 个".takeIf { daemon.endpoints.isNotEmpty() },
            ).joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status?.state == DaemonState.OFFLINE && status.message.isNotBlank()) {
                Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onViewInstances) { Text("查看实例") }
                TextButton(onClick = onCreate) { Text("新建实例") }
                TextButton(onClick = onEdit) { Text("编辑连接") }
                if (!isDefault) {
                    TextButton(onClick = onSetDefault) { Text("设为默认") }
                }
                TextButton(onClick = onRemove) { Text("移除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
