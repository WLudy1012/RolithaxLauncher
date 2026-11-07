package com.wludy.rolithax.launcher.ui.instances

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wludy.rolithax.launcher.data.DaemonOption
import com.wludy.rolithax.launcher.data.ManagedServer
import com.wludy.rolithax.launcher.data.ServerRef
import com.wludy.rolithax.launcher.data.isStoppableStatus
import com.wludy.rolithax.launcher.ui.RolithaxCard
import com.wludy.rolithax.launcher.ui.RolithaxEmptyState
import com.wludy.rolithax.launcher.ui.StatusBadge
import com.wludy.rolithax.launcher.ui.WorkspaceBar
import com.wludy.rolithax.launcher.ui.WorkspaceBody
import com.wludy.rolithax.launcher.ui.WorkspaceError

private enum class InstanceStatus(val label: String) {
    ALL("全部"), RUNNING("运行中"), STOPPED("未启动"), TRANSITIONING("切换中");

    fun matches(server: ManagedServer): Boolean = when (this) {
        ALL -> true
        RUNNING -> server.running
        STOPPED -> server.status == 0
        TRANSITIONING -> server.status in listOf(1, 3, 4)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InstancesScreen(
    initialSource: String,
    onOpenConnections: () -> Unit,
    onOpenNewInstance: (String?) -> Unit,
    onOpenServer: (ServerRef) -> Unit,
    onOpenSettings: (ServerRef) -> Unit,
    onOpenFiles: (ServerRef) -> Unit,
    viewModel: InstancesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    var source by rememberSaveable(initialSource) { mutableStateOf(initialSource) }
    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf(InstanceStatus.ALL) }
    var pendingDelete by remember { mutableStateOf<ManagedServer?>(null) }
    var pendingStop by remember { mutableStateOf<List<ManagedServer>>(emptyList()) }

    val daemons = state.daemonOptions
    val sourceName = when (source) {
        "" -> "所有来源"
        ServerRef.LOCAL_DAEMON_ID -> "本机"
        else -> daemons.firstOrNull { it.id == source }?.name ?: "所选 Daemon"
    }
    val visible = remember(state.servers, query, source, status) {
        val keyword = query.trim()
        state.servers.filter { server ->
            (source.isBlank() || server.ref?.daemonId == source) && status.matches(server) &&
                (keyword.isBlank() || listOf(server.name, server.detail, server.sourceLabel, server.ref?.instanceId.orEmpty())
                    .any { it.contains(keyword, ignoreCase = true) })
        }
    }
    val stoppable = visible.filter { isStoppableStatus(it.status) && it.key !in state.busyKeys }
    val failures = state.daemonFailures.filterKeys { source.isBlank() || source == it }

    LaunchedEffect(state.actionMessage) {
        state.actionMessage?.let {
            snackbar.showSnackbar(it)
            viewModel.clearActionMessage()
        }
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh(initial = state.servers.isEmpty())
        onPauseOrDispose { }
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.imePadding(),
        topBar = {
            WorkspaceBar("实例") {
                IconButton(onClick = { viewModel.refresh() }, enabled = !state.refreshing && !state.loading) {
                    Icon(Icons.Filled.Refresh, "刷新实例")
                }
                IconButton(onClick = { onOpenNewInstance(source.ifBlank { null }) }) {
                    Icon(Icons.Filled.Add, "新建实例")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        WorkspaceBody(Modifier.padding(padding)) {
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = { viewModel.refresh() }) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item("filters") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("搜索实例") },
                                leadingIcon = { Icon(Icons.Filled.Search, null) },
                                trailingIcon = {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "清除搜索") }
                                    }
                                },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                            )
                            SourceSelector(sourceName, daemons) { source = it }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                InstanceStatus.entries.forEach { filter ->
                                    FilterChip(
                                        selected = status == filter,
                                        onClick = { status = filter },
                                        label = { Text(filter.label) },
                                    )
                                }
                            }
                        }
                    }
                    failures.forEach { (id, error) ->
                        item("failure_$id") {
                            val name = daemons.firstOrNull { it.id == id }?.name ?: "Daemon"
                            WorkspaceError(
                                "$name 的实例未加载",
                                error,
                                "管理连接",
                                onOpenConnections,
                            )
                        }
                    }
                    state.error?.let { error ->
                        item("load-error") { WorkspaceError("无法加载实例", error, "重试") { viewModel.refresh() } }
                    }
                    when {
                        state.loading && visible.isEmpty() -> item("loading") {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                        visible.isEmpty() && state.error == null && failures.isEmpty() -> item("empty") {
                            val filtersActive = query.isNotBlank() || status != InstanceStatus.ALL || source.isNotBlank()
                            RolithaxEmptyState(
                                icon = Icons.AutoMirrored.Filled.List,
                                title = if (filtersActive) "没有匹配的实例" else "还没有实例",
                                description = if (filtersActive) "可以换个关键词或调整筛选条件。" else "在本机或已连接的 Daemon 上创建实例。",
                                actionLabel = if (filtersActive) "清除筛选" else "新建实例",
                                onAction = {
                                    if (filtersActive) {
                                        source = ""
                                        query = ""
                                        status = InstanceStatus.ALL
                                    } else {
                                        onOpenNewInstance(null)
                                    }
                                },
                            )
                        }
                    }
                    if (visible.isNotEmpty()) {
                        item("result-summary") {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text("${visible.size} 个实例", style = MaterialTheme.typography.titleMedium)
                                if (stoppable.isNotEmpty() || state.stoppingAll) {
                                    TextButton(onClick = { pendingStop = stoppable }, enabled = !state.stoppingAll) {
                                        Text(if (state.stoppingAll) "正在发送停止命令…" else "批量停止")
                                    }
                                }
                            }
                        }
                        items(visible, key = { "instance_${it.key}" }) { server ->
                            val ref = server.ref
                            InstanceRow(
                                server = server,
                                icon = state.icons[server.key],
                                busy = server.key in state.busyKeys,
                                pinned = server.key in state.pinnedKeys,
                                onOpen = { ref?.let(onOpenServer) },
                                onStart = { viewModel.toggle(server, start = true) },
                            ) { close ->
                                if (isStoppableStatus(server.status)) {
                                    DropdownMenuItem(text = { Text("停止") }, onClick = {
                                        close()
                                        pendingStop = listOf(server)
                                    })
                                }
                                if (!server.isLocal) {
                                    DropdownMenuItem(text = { Text("文件管理") }, onClick = {
                                        close()
                                        ref?.let(onOpenFiles)
                                    })
                                }
                                DropdownMenuItem(text = { Text("实例设置") }, onClick = {
                                    close()
                                    ref?.let(onOpenSettings)
                                })
                                DropdownMenuItem(
                                    text = { Text(if (server.key in state.pinnedKeys) "取消置顶" else "置顶") },
                                    onClick = { close(); viewModel.togglePin(server) },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除实例", color = MaterialTheme.colorScheme.error) },
                                    onClick = { close(); pendingDelete = server },
                                    enabled = !isStoppableStatus(server.status) && !state.deleting,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (pendingStop.isNotEmpty()) {
        val targets = pendingStop
        val single = targets.singleOrNull()
        AlertDialog(
            onDismissRequest = { pendingStop = emptyList() },
            title = { Text(if (single == null) "批量停止实例" else "停止实例") },
            text = {
                Text(
                    if (single == null) {
                        "向当前筛选结果中可停止的 ${targets.size} 个实例发送停止命令？"
                    } else {
                        "停止 ${single.sourceLabel} 的「${single.name}」？"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingStop = emptyList()
                    if (single == null) viewModel.stopAll(targets) else viewModel.toggle(single, start = false)
                }) { Text("停止") }
            },
            dismissButton = { TextButton(onClick = { pendingStop = emptyList() }) { Text("取消") } },
        )
    }
    pendingDelete?.let { target ->
        var confirmation by remember(target.key) { mutableStateOf("") }
        var deleteFiles by remember(target.key) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!state.deleting) pendingDelete = null },
            title = { Text("删除实例") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("删除 ${target.sourceLabel} 的「${target.name}」。请输入实例名确认。")
                    if (target.isLocal) Text("此操作会删除实例目录及其中的世界存档。")
                    OutlinedTextField(confirmation, { confirmation = it }, label = { Text("实例名") }, singleLine = true)
                    if (!target.isLocal) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(deleteFiles, { deleteFiles = it })
                            Text("同时删除服务器上的数据文件", modifier = Modifier.weight(1f))
                        }
                    }
                    state.deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.deleting && confirmation == target.name,
                    onClick = { viewModel.delete(target, target.isLocal || deleteFiles) { pendingDelete = null } },
                ) { Text(if (state.deleting) "正在删除…" else "删除") }
            },
            dismissButton = {
                TextButton(enabled = !state.deleting, onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SourceSelector(label: String, daemons: List<DaemonOption>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ArrowDropDown, "按来源筛选")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val options = listOf(DaemonOption("", "所有来源"), DaemonOption(ServerRef.LOCAL_DAEMON_ID, "本机")) + daemons
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onSelect(option.id)
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstanceRow(
    server: ManagedServer,
    icon: Bitmap?,
    busy: Boolean,
    pinned: Boolean,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    RolithaxCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Filled.List, null)
                        }
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        server.sourceLabel + if (pinned) " · 已置顶" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (server.detail.isNotBlank()) {
                        Text(
                            server.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { expanded = true }, enabled = !busy) {
                        Icon(Icons.Filled.MoreVert, "更多实例操作")
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        menu { expanded = false }
                    }
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                StatusBadge(server.status)
                if (busy) {
                    CircularProgressIndicator(Modifier.padding(8.dp).size(20.dp), strokeWidth = 2.dp)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onOpen, enabled = server.ref != null) { Text("打开控制台") }
                if (server.status == 0) {
                    TextButton(onClick = onStart, enabled = !busy) { Text("启动") }
                }
            }
        }
    }
}
