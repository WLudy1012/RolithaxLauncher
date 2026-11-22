package com.wludy.rolithax.launcher.ui.panels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wludy.rolithax.launcher.shared.panels.PanelKind
import com.wludy.rolithax.launcher.shared.panels.PanelPower
import com.wludy.rolithax.launcher.shared.panels.PanelProfile
import com.wludy.rolithax.launcher.shared.panels.PanelServer
import com.wludy.rolithax.launcher.ui.RolithaxCard
import com.wludy.rolithax.launcher.ui.RolithaxEmptyState
import com.wludy.rolithax.launcher.ui.RolithaxStatusPill
import com.wludy.rolithax.launcher.ui.WorkspaceBar
import com.wludy.rolithax.launcher.ui.WorkspaceBody
import com.wludy.rolithax.launcher.ui.WorkspaceError
import java.util.UUID

@Composable
fun PanelScreen(onBack: () -> Unit, model: PanelViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val activeProfile = state.profiles.firstOrNull { it.id == state.profileId }
    val activeServer = state.server
    var editing by remember { mutableStateOf<PanelProfile?>(null) }
    var removing by remember { mutableStateOf<PanelProfile?>(null) }
    var powerConfirmation by remember(state.profileId, activeServer?.id, activeServer?.nodeId) { mutableStateOf<PanelPower?>(null) }
    var pendingSaveVersion by remember { mutableStateOf<Int?>(null) }

    fun back() {
        when {
            activeServer != null -> model.selectServer(null)
            state.profileId != null -> model.closeProfile()
            else -> onBack()
        }
    }

    fun edit(profile: PanelProfile) {
        model.clearNotice()
        pendingSaveVersion = null
        editing = profile
    }

    fun add() = edit(PanelProfile(UUID.randomUUID().toString(), "", PanelKind.MCSL, "", ""))

    BackHandler(enabled = state.profileId != null || activeServer != null, onBack = ::back)
    LifecycleResumeEffect(model) {
        model.setVisible(true)
        onPauseOrDispose { model.setVisible(false) }
    }
    LaunchedEffect(state.saveVersion) {
        if (pendingSaveVersion?.let { state.saveVersion != it } == true) {
            editing = null
            pendingSaveVersion = null
        }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            model.consumeMessage(it)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        modifier = Modifier.imePadding(),
        topBar = {
            WorkspaceBar(
                title = activeServer?.name ?: activeProfile?.name ?: "其他面板",
                navigationIcon = {
                    IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
            ) {
                if (state.profileId != null) {
                    IconButton(onClick = model::refresh, enabled = !state.busy) { Icon(Icons.Filled.Refresh, "刷新实例状态") }
                } else {
                    IconButton(onClick = ::add, enabled = state.ready && !state.storageError && !state.busy) {
                        Icon(Icons.Filled.Add, "添加面板")
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        WorkspaceBody(Modifier.padding(padding)) {
            when {
                activeServer != null -> PanelConsole(
                    server = activeServer,
                    powers = state.powers,
                    busy = state.busy,
                    error = state.error,
                    logText = state.logText,
                    logError = state.logError,
                    logConnecting = state.logConnecting,
                    onPower = { powerConfirmation = it },
                    onCommand = model::command,
                    onReconnectLogs = model::reconnectLogs,
                    onDismissError = model::clearNotice,
                )
                state.profileId != null -> PanelInstances(
                    state.servers, state.busy, state.error, model::refresh, model::selectServer,
                )
                else -> PanelProfiles(
                    state.profiles, state.ready, state.storageError, state.busy, state.error.takeIf { editing == null },
                    ::add, model::selectProfile, ::edit, { removing = it }, model::clearNotice,
                )
            }
        }
    }

    editing?.let { profile ->
        PanelEditor(
            profile = profile,
            isNew = state.profiles.none { it.id == profile.id },
            busy = state.busy,
            error = state.error,
            onSave = {
                pendingSaveVersion = state.saveVersion
                model.save(it)
            },
            onClose = {
                model.clearNotice()
                editing = null
                pendingSaveVersion = null
            },
        )
    }
    removing?.let { profile ->
        PanelConfirmDialog("移除连接", "移除「${profile.name}」的本机连接记录？面板上的实例会保留。", "移除", true,
            onConfirm = { model.remove(profile.id); removing = null }, onClose = { removing = null })
    }
    powerConfirmation?.let { action ->
        if (activeServer != null) {
            PanelConfirmDialog(action.title, "${action.title}「${activeServer.name}」？", action.title, action == PanelPower.KILL,
                onConfirm = { model.power(action); powerConfirmation = null }, onClose = { powerConfirmation = null })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelProfiles(
    profiles: List<PanelProfile>,
    ready: Boolean,
    storageError: Boolean,
    busy: Boolean,
    error: String?,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
    onEdit: (PanelProfile) -> Unit,
    onRemove: (PanelProfile) -> Unit,
    onClear: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!ready || busy) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (storageError) item("storage") { WorkspaceError("无法读取面板连接", "请确认本机存储可用。") }
        error?.let { item("error") { WorkspaceError("操作失败", it, "关闭", onClear) } }
        if (ready && profiles.isEmpty() && !storageError) item("empty") {
            RolithaxEmptyState(Icons.Filled.Share, "还没有面板连接", "连接 MCSL Future、MCSManager 10 或 Pterodactyl。",
                "添加面板", onAdd)
        }
        items(profiles, key = PanelProfile::id) { profile ->
            RolithaxCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(profile.kind.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(profile.baseUrl, style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(onClick = { onOpen(profile.id) }, enabled = !busy) { Text("查看实例") }
                        TextButton(onClick = { onEdit(profile) }, enabled = !busy) { Text("编辑连接") }
                        TextButton(onClick = { onRemove(profile) }, enabled = !busy) { Text("移除") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelInstances(
    servers: List<PanelServer>,
    busy: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onOpen: (PanelServer) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { item("error") { WorkspaceError("读取面板失败", it, "重试", onRetry) } }
        if (servers.isEmpty() && !busy && error == null) item("empty") {
            RolithaxEmptyState(Icons.Filled.Share, "没有可管理的实例", "确认当前凭据在面板上有对应实例的访问权限。",
                "刷新", onRetry)
        }
        items(servers, key = { "server:${it.nodeId}:${it.id}" }) { server ->
            RolithaxCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PanelInstanceHeading(server)
                    if (server.description.isNotBlank()) Text(server.description, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onOpen(server) }, enabled = !busy) { Text("打开控制台") }
                }
            }
        }
    }
}

@Composable
private fun PanelInstanceHeading(server: PanelServer) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(server.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        RolithaxStatusPill(server.state, when (server.state) {
            "运行中" -> MaterialTheme.colorScheme.primary
            "已崩溃" -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelConsole(
    server: PanelServer,
    powers: Set<PanelPower>,
    busy: Boolean,
    error: String?,
    logText: String,
    logError: String?,
    logConnecting: Boolean,
    onPower: (PanelPower) -> Unit,
    onCommand: (String) -> Unit,
    onReconnectLogs: () -> Unit,
    onDismissError: () -> Unit,
) {
    var command by remember(server.id, server.nodeId) { mutableStateOf("") }
    var followLatest by remember(server.id, server.nodeId) { mutableStateOf(true) }
    val logScroll = rememberScrollState()
    val validCommand = '\r' !in command && '\n' !in command && command.length <= 8192
    val canSend = !busy && command.isNotBlank() && validCommand
    LaunchedEffect(logScroll.maxValue, followLatest) {
        if (followLatest && logScroll.maxValue != Int.MAX_VALUE) logScroll.scrollTo(logScroll.maxValue)
    }
    fun send() {
        if (canSend) {
            onCommand(command.trim())
            command = ""
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RolithaxCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PanelInstanceHeading(server)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PanelPower.entries.filter(powers::contains).forEach { action ->
                        TextButton(onClick = { onPower(action) }, enabled = !busy) {
                            if (action == PanelPower.KILL) Text(action.title, color = MaterialTheme.colorScheme.error)
                            else Text(action.title)
                        }
                    }
                }
            }
        }
        if (busy || logConnecting) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { WorkspaceError("操作失败", it, "关闭", onDismissError) }
        logError?.let { WorkspaceError("日志连接中断", it, "重新连接", onReconnectLogs) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("控制台日志", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            FilterChip(selected = followLatest, onClick = { followLatest = !followLatest }, label = { Text("跟随最新") })
        }
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
        ) {
            SelectionContainer(Modifier.verticalScroll(logScroll).padding(12.dp)) {
                Text(logText.ifBlank { if (logConnecting) "正在连接日志…" else "暂无日志" },
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.weight(1f),
                label = { Text("控制台命令") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                enabled = !busy,
                isError = !validCommand,
                supportingText = if (!validCommand) ({ Text("请输入一行命令，不超过 8192 个字符") }) else null,
            )
            Button(onClick = ::send, enabled = canSend) { Text("发送") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PanelEditor(
    profile: PanelProfile,
    isNew: Boolean,
    busy: Boolean,
    error: String?,
    onSave: (PanelProfile) -> Unit,
    onClose: () -> Unit,
    embedded: Boolean = false,
) {
    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var kind by remember(profile.id) { mutableStateOf(profile.kind) }
    var address by remember(profile.id) { mutableStateOf(profile.baseUrl) }
    var token by remember(profile.id) { mutableStateOf(profile.token) }
    var nodeId by remember(profile.id) { mutableStateOf(profile.nodeId) }
    var allowHttp by remember(profile.id) { mutableStateOf(profile.allowHttp) }

    val fields: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!embedded) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PanelKind.entries.forEach { option ->
                        FilterChip(selected = option == kind, enabled = !busy,
                            onClick = {
                                if (kind != option) {
                                    kind = option
                                    token = ""
                                    allowHttp = false
                                }
                            }, label = { Text(option.title) })
                    }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text("连接名称") }, singleLine = true)
                OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text(if (kind == PanelKind.MCSL) "MCSL Future 地址" else "面板地址") },
                    placeholder = { Text(if (kind == PanelKind.MCSL) "wss://example.com/api/v2" else "https://panel.example.com") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
                OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), enabled = !busy,
                    label = { Text(when (kind) {
                        PanelKind.MCSL -> "MCSL Token"
                        PanelKind.MCSM -> "MCSManager API Key"
                        PanelKind.PTERODACTYL -> "客户端 API Key"
                    }) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
                if (kind == PanelKind.MCSM) {
                    OutlinedTextField(nodeId, { nodeId = it }, Modifier.fillMaxWidth(), enabled = !busy,
                        label = { Text("节点 ID（可选）") }, singleLine = true)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(allowHttp, enabled = !busy, role = Role.Checkbox) { allowHttp = it },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Checkbox(checked = allowHttp, onCheckedChange = null, enabled = !busy)
                    Text("允许明文 HTTP / WS", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (allowHttp) Text("仅适用于可信内网。明文连接会暴露接口凭据。", color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    val save: @Composable () -> Unit = {
            TextButton(
                enabled = !busy && address.isNotBlank() && token.isNotBlank(),
                onClick = { onSave(profile.copy(name = name, kind = kind, baseUrl = address.trim(), token = token.trim(),
                    allowHttp = allowHttp, nodeId = if (kind == PanelKind.MCSM) nodeId.trim() else "")) },
            ) { Text(if (busy) "正在验证…" else "验证并保存") }
        }
    if (embedded) Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        fields()
        save()
    } else AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text(if (isNew) "添加面板" else "编辑面板") },
        text = fields,
        confirmButton = save,
        dismissButton = { TextButton(onClick = onClose, enabled = !busy) { Text("取消") } },
    )
}

@Composable
private fun PanelConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                if (destructive) Text(confirm, color = MaterialTheme.colorScheme.error) else Text(confirm)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}
