package com.wludy.rolithax.launcher.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.wludy.rolithax.launcher.shared.DaemonInstance
import com.wludy.rolithax.launcher.shared.DaemonFile
import com.wludy.rolithax.launcher.shared.DaemonUser
import com.wludy.rolithax.launcher.shared.DaemonProfile
import com.wludy.rolithax.launcher.shared.ModrinthProject
import com.wludy.rolithax.launcher.shared.ModrinthVersion
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

fun main() = application {
    var closeRequested by remember { mutableStateOf(false) }
    val initialSize = remember { initialDesktopSize() }
    Window(
        onCloseRequest = { closeRequested = true },
        title = "Rolithax Launcher",
        icon = painterResource("rolithax_app.png"),
        state = rememberWindowState(size = initialSize),
    ) {
        val dark = isSystemInDarkTheme()
        DisposableEffect(window, dark) {
            window.minimumSize = java.awt.Dimension(initialSize.width.value.toInt().coerceAtMost(820), initialSize.height.value.toInt().coerceAtMost(560))
            styleWindowsTitleBar(window, dark)
            onDispose { }
        }
        RolithaxWindowsTheme {
            Surface(Modifier.fillMaxSize()) { DesktopApp(closeRequested, { closeRequested = false }, ::exitApplication) }
        }
    }
}

@Composable
private fun DesktopApp(closeRequested: Boolean, onCancelClose: () -> Unit, onExit: () -> Unit) {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { DesktopController(scope) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller) { controller.loadSaved() }
    val state = controller.state
    val unsaved = state.editingPath != null && state.fileContent != state.originalFileContent || state.settingsInstance != null && state.settingsText != state.originalSettingsText
    LaunchedEffect(closeRequested, unsaved, state.transferring) {
        if (closeRequested && !unsaved && !state.transferring) onExit()
    }
    if (closeRequested && (unsaved || state.transferring)) AlertDialog(
        onDismissRequest = onCancelClose,
        title = { Text("退出 Rolithax？") },
        text = { Text(if (state.transferring) "文件传输仍在进行，退出会中断传输。未保存的编辑也会丢失。" else "存在未保存的文件或实例配置，退出会丢失这些修改。") },
        confirmButton = { WindowsTextButton(onClick = onExit) { Text("退出") } },
        dismissButton = { WindowsTextButton(onClick = onCancelClose) { Text("继续管理") } },
    )
    DesktopContent(controller.state, controller::connect, controller::select, controller::remove, controller::refresh, controller::action, controller::browseFiles, controller::openFile, controller::editContent, controller::saveFile, controller::closeFiles, controller::closeEditor, controller::openConsole, controller::sendCommand, controller::closeConsole, controller::openDetails, controller::closeDetails, controller::deleteInstance, controller::openSettings, controller::editSettings, controller::saveSettings, controller::closeSettings, controller::openUsers, controller::createUser, controller::updateUser, controller::deleteUser, controller::closeUsers, controller::createInstance, controller::openPlugins, controller::changePlugin, controller::closePlugins, controller::openResources, controller::searchResources, controller::selectResource, controller::inspectResource, controller::refreshResourceTask, controller::closeResources, controller::cancelCreation, controller::closeCreation, controller::createAdvanced, controller::agreeEulaAndStart, controller::dismissEula, controller::installResourcePlan, controller::dismissResourcePlan, controller::cancelResourceTask, controller::uploadFile, controller::downloadFile, controller::cancelTransfer, controller::connectSaved, controller::removeSaved, controller::openCoreChooser, controller::chooseCore, controller::chooseCoreVersion, controller::chooseCoreBuild, controller::createOnline, controller::closeCoreChooser, controller::chooseCoreJava, controller::cleanupOrphan, controller::openProfile, controller::createDirectory, controller::renameRemoteFile, controller::deleteRemoteFile)
    controller.state.profile?.let { profile ->
        ProfileDialog(profile, controller.state.busy, controller.state.error, controller::saveProfile, controller::closeProfile)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopContent(
    state: DesktopState,
    onConnect: (String, String, String, Boolean, String) -> Unit,
    onSelect: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onRefresh: () -> Unit,
    onAction: (DaemonInstance, String) -> Unit,
    onBrowseFiles: (DaemonInstance, String) -> Unit,
    onOpenFile: (DaemonFile) -> Unit,
    onEditContent: (String) -> Unit,
    onSaveFile: () -> Unit,
    onCloseFiles: () -> Unit,
    onCloseEditor: () -> Unit,
    onOpenConsole: (DaemonInstance) -> Unit,
    onSendCommand: (String) -> Unit,
    onCloseConsole: () -> Unit,
    onOpenDetails: (DaemonInstance) -> Unit,
    onCloseDetails: () -> Unit,
    onDeleteInstance: (DaemonInstance, Boolean) -> Unit,
    onOpenSettings: (DaemonInstance) -> Unit,
    onEditSettings: (String) -> Unit,
    onSaveSettings: () -> Unit,
    onCloseSettings: () -> Unit,
    onOpenUsers: () -> Unit,
    onCreateUser: (String, String, String, String, List<String>) -> Unit,
    onUpdateUser: (DaemonUser, String, String, String, List<String>, Boolean) -> Unit,
    onDeleteUser: (DaemonUser) -> Unit,
    onCloseUsers: () -> Unit,
    onCreateInstance: (String, String, String, String, String, String, String) -> Unit,
    onOpenPlugins: (DaemonInstance, String) -> Unit,
    onChangePlugin: (String, String) -> Unit,
    onClosePlugins: () -> Unit,
    onOpenResources: (DaemonInstance) -> Unit,
    onSearchResources: (String, String, String, String) -> Unit,
    onSelectResource: (ModrinthProject, String, String) -> Unit,
    onInstallResource: (ModrinthVersion, String, String, String) -> Unit,
    onRefreshResourceTask: () -> Unit,
    onCloseResources: () -> Unit,
    onCancelCreation: () -> Unit,
    onCloseCreation: () -> Unit,
    onCreateAdvanced: (String) -> Unit,
    onAgreeEula: () -> Unit,
    onDismissEula: () -> Unit,
    onConfirmResourcePlan: () -> Unit,
    onDismissResourcePlan: () -> Unit,
    onCancelResourceTask: () -> Unit,
    onUploadFile: (File) -> Unit,
    onDownloadFile: (DaemonFile, File) -> Unit,
    onCancelTransfer: () -> Unit,
    onConnectSaved: (SavedDaemon) -> Unit,
    onRemoveSaved: (SavedDaemon) -> Unit,
    onOpenCoreChooser: () -> Unit,
    onChooseCore: (String) -> Unit,
    onChooseCoreVersion: (String) -> Unit,
    onChooseCoreBuild: (String) -> Unit,
    onCreateOnline: (String, String, String) -> Unit,
    onCloseCoreChooser: () -> Unit,
    onChooseCoreJava: (String) -> Unit,
    onCleanupOrphan: () -> Unit,
    onOpenProfile: () -> Unit,
    onCreateDirectory: (String) -> Unit,
    onRenameFile: (DaemonFile, String) -> Unit,
    onDeleteFile: (DaemonFile) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var allowHttp by remember { mutableStateOf(false) }
    var fingerprint by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<Pair<DaemonInstance, String>?>(null) }
    val editing = state.editingPath != null || state.settingsInstance != null
    var confirmCancelCreation by remember { mutableStateOf(false) }
    var showAdvancedCreation by remember { mutableStateOf(false) }
    var showCreateInstance by remember { mutableStateOf(false) }
    var deletingInstance by remember { mutableStateOf<DaemonInstance?>(null) }
    var deleteFiles by remember { mutableStateOf(false) }
    var discardSettings by remember { mutableStateOf(false) }
    var showConnect by remember { mutableStateOf(false) }
    var section by remember { mutableStateOf("overview") }
    LaunchedEffect(state.selectedId) { if (state.selectedId != null) section = "overview" }
    var settingsForm by remember(state.settingsInstance?.id) { mutableStateOf(true) }
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    val activeInstance = state.resourceInstance ?: state.pluginsInstance ?: state.settingsInstance ?: state.consoleInstance ?: state.fileInstance ?: state.detailInstance
    val dirty = state.editingPath != null && state.fileContent != state.originalFileContent || state.settingsInstance != null && state.settingsText != state.originalSettingsText
    fun closePage() {
        onCloseCoreChooser(); onCloseUsers(); onCloseResources(); onClosePlugins()
        onCloseSettings(); onCloseConsole(); onCloseFiles(); onCloseDetails()
    }
    fun navigate(action: () -> Unit) {
        if (state.busy || state.transferring) return
        if (dirty) pendingNavigation = { closePage(); action() } else { closePage(); action() }
    }
    fun openInstance(instance: DaemonInstance, page: String) = navigate {
        section = "instances"
        when (page) {
            "console" -> onOpenConsole(instance)
            "files" -> onBrowseFiles(instance, "")
            "settings" -> onOpenSettings(instance)
            "plugins" -> onOpenPlugins(instance, "plugins")
            "resources" -> onOpenResources(instance)
            else -> onOpenDetails(instance)
        }
    }
    val selectedTab = when {
        state.consoleInstance != null -> "console"
        state.fileInstance != null -> "files"
        state.settingsInstance != null -> "settings"
        state.pluginsInstance != null -> "plugins"
        state.resourceInstance != null -> "resources"
        else -> "details"
    }
    val title = when {
        activeInstance != null -> activeInstance.name ?: "实例 ${activeInstance.id}"
        state.coreChooser -> "在线创建"
        state.managingUsers -> "用户管理"
        section == "overview" -> "概览"
        section == "connections" -> "Daemon 连接"
        else -> "实例管理"
    }
    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.width(224.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Image(painterResource("rolithax_app.png"), null, Modifier.size(64.dp))
                Text("Rolithax", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("远端管理中心", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WorkspaceNav("概览", WorkspaceIcon.Dashboard, activeInstance == null && !state.coreChooser && !state.managingUsers && section == "overview", !state.busy) { navigate { section = "overview" } }
                WorkspaceNav("实例管理", WorkspaceIcon.Server, activeInstance != null || section == "instances" && !state.coreChooser && !state.managingUsers, state.selectedId != null && !state.busy) { navigate { section = "instances" } }
                WorkspaceNav("新建实例", WorkspaceIcon.Add, state.coreChooser, state.selectedId != null && !state.busy) { navigate(onOpenCoreChooser) }
                WorkspaceNav("用户管理", WorkspaceIcon.Users, state.managingUsers, state.selectedId != null && !state.busy) { navigate(onOpenUsers) }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                WorkspaceNav("Daemon 连接", WorkspaceIcon.Server, section == "connections" && activeInstance == null && !state.coreChooser && !state.managingUsers, !state.busy) { navigate { section = "connections" } }
                WorkspaceNav("添加连接", WorkspaceIcon.Add, enabled = !state.busy && !state.transferring) { navigate { showConnect = true } }
            }
            state.connections.firstOrNull { it.id == state.selectedId }?.let { connection ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f), shape = RoundedCornerShape(10.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("当前节点", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(connection.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text("会话 #${connection.id}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        WindowsTextButton(onClick = { navigate { section = "connections" } }, enabled = !state.busy) { Text("切换节点") }
                    }
                }
            }
            WorkspaceNav("我的账户", WorkspaceIcon.Users, enabled = state.selectedId != null && !state.busy) { onOpenProfile() }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.connections.firstOrNull { it.id == state.selectedId }?.name ?: "未连接 Daemon", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (activeInstance != null) {
                    WorkspaceInstancePicker(state.instances, !state.busy && !state.transferring) { openInstance(it, selectedTab) }
                    WindowsTextButton(onClick = { navigate { section = "instances" } }, enabled = !state.busy) { Text("返回列表") }
                }
                else WindowsOutlinedButton(onClick = onRefresh, enabled = !state.busy && state.selectedId != null && !state.managingUsers && !state.coreChooser && section != "connections") { Text("刷新") }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
            state.error?.let { WorkspaceMessage(it, true) }
            state.notice?.let { WorkspaceMessage(it, false) }
            state.orphanCredentialId?.let { id ->
                WorkspaceMessage("密钥保存异常，待清理标识：$id", true)
                WindowsTextButton(onClick = onCleanupOrphan, enabled = !state.busy) { Text("清理残留密钥") }
            }
            if (state.creationId != null) {
                Text("创建进度  ${state.creationProgress}%  ·  ${state.creationMessage}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WindowsTextButton(onClick = { confirmCancelCreation = true }, enabled = !state.busy) { Text("取消任务") }
                    WindowsTextButton(onClick = onCloseCreation, enabled = !state.busy) { Text("关闭进度") }
                }
            }
            Surface(modifier = Modifier.weight(1f).fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = if (activeInstance != null || state.managingUsers || state.coreChooser) MaterialTheme.colorScheme.surface else Color.Transparent) {
            Column(Modifier.fillMaxSize().padding(if (activeInstance != null || state.managingUsers || state.coreChooser) 18.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (activeInstance != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusLabel(activeInstance)
                    Spacer(Modifier.weight(1f))
                    WindowsTextButton(onClick = { onAction(activeInstance, "start") }, enabled = !state.busy && activeInstance.status == 0) { Text("启动") }
                    WindowsTextButton(onClick = { confirmation = activeInstance to "stop" }, enabled = !state.busy && activeInstance.status == 2) { Text("停止") }
                    WindowsTextButton(onClick = { confirmation = activeInstance to "restart" }, enabled = !state.busy) { Text("重启") }
                }
                WorkspaceTabs(selectedTab, !state.busy && !state.transferring, { page -> if (page != selectedTab) openInstance(activeInstance, page) })
            }
            if (state.coreChooser) {
                CoreChooserContent(state, onChooseCore, onChooseCoreVersion, onChooseCoreBuild, onCreateOnline, onOpenCoreChooser, onChooseCoreJava)
            } else if (state.managingUsers) {
                UserContent(state, onOpenUsers, onCreateUser, onUpdateUser, onDeleteUser)
            } else if (state.resourceInstance != null) {
                ResourceContent(state, onSearchResources, onSelectResource, onInstallResource, onRefreshResourceTask, onConfirmResourcePlan, onDismissResourcePlan, onCancelResourceTask)
            } else if (state.pluginsInstance != null) {
                PluginsContent(state, onOpenPlugins, onChangePlugin)
            } else if (state.settingsInstance != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WindowsTextButton(onClick = { settingsForm = true }) { Text(if (settingsForm) "表单编辑（当前）" else "表单编辑") }
                    WindowsTextButton(onClick = { settingsForm = false }) { Text(if (!settingsForm) "JSON（当前）" else "JSON") }
                }
                if (settingsForm) InstanceSettingsEditor(state.settingsText, onEditSettings, !state.busy, Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()))
                else OutlinedTextField(state.settingsText, onEditSettings, modifier = Modifier.fillMaxWidth().weight(1f), enabled = !state.busy, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = desktopMonospaceFont))
                WindowsButton(onClick = onSaveSettings, enabled = !state.busy && state.settingsText != state.originalSettingsText) { Text("保存配置") }
            } else if (state.detailInstance != null) {
                InstanceOverview(state, Modifier.weight(1f))
                if (!state.busy && state.instanceInfo == null) WindowsOutlinedButton(onClick = { onOpenDetails(state.detailInstance) }) { Text("重新读取") }
            } else if (state.consoleInstance != null) {
                ConsoleContent(state, onSendCommand)
            } else if (state.fileInstance != null) {
                FileContent(state, onBrowseFiles, onOpenFile, onEditContent, onSaveFile, onCloseEditor, onUploadFile, onDownloadFile, onCancelTransfer, onCreateDirectory, onRenameFile, onDeleteFile)
            } else if (section == "connections") {
                DaemonConnectionsPage(state, { id -> navigate { onSelect(id); section = "overview" } }, { id -> if (id == state.selectedId) navigate { onRemove(id) } else onRemove(id) }, { saved -> navigate { onConnectSaved(saved) } }, onRemoveSaved, { navigate { showConnect = true } }, Modifier.weight(1f))
            } else if (section == "overview") {
                DesktopOverview(state, { navigate { section = "instances" } }, { navigate { showConnect = true } }, { navigate(onOpenCoreChooser) }, Modifier.weight(1f))
            } else if (state.selectedId == null) {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    WorkspaceGlyph(WorkspaceIcon.Server, Modifier.size(36.dp), MaterialTheme.colorScheme.primary)
                    Text("连接你的 Daemon", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("从左侧选择已保存的连接，或添加一台远端服务器。连接后可管理实例、查看实时日志、编辑配置和传输文件。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WindowsButton(onClick = { showConnect = true }, enabled = !state.busy) { Text("添加连接") }
                }
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WindowsButton(onClick = { showCreateInstance = true }, enabled = !state.busy) { Text("新建实例") }
                    WindowsOutlinedButton(onClick = { navigate(onOpenCoreChooser) }, enabled = !state.busy) { Text("在线创建") }
                    WindowsTextButton(onClick = { showAdvancedCreation = true }, enabled = !state.busy) { Text("高级创建") }
                }
                InstanceBrowser(state, ::openInstance, { instance, action -> if (action == "start") onAction(instance, action) else confirmation = instance to action }, { deletingInstance = it; deleteFiles = false }, Modifier.weight(1f))
            }
            }
            }
        }
    }
    pendingNavigation?.let { action -> AlertDialog(
        onDismissRequest = { pendingNavigation = null }, title = { Text("放弃未保存的修改？") },
        text = { Text("切换页面将丢弃尚未保存的文件或配置修改。") },
        confirmButton = { WindowsTextButton(onClick = { pendingNavigation = null; action() }) { Text("放弃并继续") } },
        dismissButton = { WindowsTextButton(onClick = { pendingNavigation = null }) { Text("继续编辑") } },
    ) }
    if (showConnect) AlertDialog(
        onDismissRequest = { showConnect = false; key = "" },
        title = { Text("添加 Daemon 连接") },
        text = {
            Column(Modifier.width(440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("连接保存在本机，API Key 存入系统密钥库。", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("名称（可选）") }, singleLine = true, enabled = !state.busy)
                OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth(), label = { Text("地址") }, singleLine = true, enabled = !state.busy)
                OutlinedTextField(key, { key = it }, modifier = Modifier.fillMaxWidth(), label = { Text("API Key") }, singleLine = true, enabled = !state.busy, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(fingerprint, { fingerprint = it }, modifier = Modifier.fillMaxWidth(), label = { Text("证书 SHA-256 指纹（可选）") }, singleLine = true, enabled = !state.busy)
                Text("自签证书请填写通过可信渠道取得的服务器证书指纹。", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(allowHttp, { allowHttp = it }, enabled = !state.busy)
                    Text("允许 HTTP 明文连接")
                }
                if (allowHttp) Text("HTTP 会明文传输 API Key，仅适用于可信网络。", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { WindowsTextButton(onClick = { showConnect = false; onConnect(name, address, key, allowHttp, fingerprint); key = "" }, enabled = !state.busy && !editing) { Text("连接") } },
        dismissButton = { WindowsTextButton(onClick = { showConnect = false; key = "" }) { Text("取消") } },
    )
    if (confirmCancelCreation) AlertDialog(
        onDismissRequest = { confirmCancelCreation = false },
        title = { Text("取消创建任务？") },
        text = { Text("将请求 Daemon 取消创建，已创建的文件会保留。") },
        confirmButton = { WindowsTextButton(onClick = { confirmCancelCreation = false; onCancelCreation() }) { Text("取消创建") } },
        dismissButton = { WindowsTextButton(onClick = { confirmCancelCreation = false }) { Text("返回") } },
    )
    if (showAdvancedCreation) AdvancedCreationDialog(onDismiss = { showAdvancedCreation = false }, onCreate = { onCreateAdvanced(it); showAdvancedCreation = false })
    if (showCreateInstance) CreateInstanceDialog(onDismiss = { showCreateInstance = false }, onCreate = { name, core, min, max, java, args, path ->
        onCreateInstance(name, core, min, max, java, args, path); showCreateInstance = false
    })
    if (discardSettings) AlertDialog(
        onDismissRequest = { discardSettings = false },
        title = { Text("放弃未保存的设置？") },
        confirmButton = { WindowsTextButton(onClick = { discardSettings = false; onCloseSettings() }) { Text("放弃") } },
        dismissButton = { WindowsTextButton(onClick = { discardSettings = false }) { Text("取消") } },
    )
    deletingInstance?.let { instance ->
        AlertDialog(
            onDismissRequest = { deletingInstance = null },
            title = { Text("删除实例") },
            text = { Column {
                Text("确定删除 " + (instance.name ?: "实例 " + instance.id) + "？")
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(deleteFiles, { deleteFiles = it }); Text("同时删除实例文件（不可撤销）") }
            } },
            confirmButton = { WindowsTextButton(onClick = { deletingInstance = null; onDeleteInstance(instance, deleteFiles) }) { Text("删除") } },
            dismissButton = { WindowsTextButton(onClick = { deletingInstance = null }) { Text("取消") } },
        )
    }
    if (state.eulaRequired && state.consoleInstance != null) AlertDialog(
        onDismissRequest = onDismissEula,
        title = { Text("需要同意 Minecraft EULA") },
        text = { Text("服务器要求同意 Minecraft EULA 才能启动。确认同意后将再次提交启动请求。") },
        confirmButton = { WindowsTextButton(onClick = onAgreeEula, enabled = !state.busy) { Text("同意并启动") } },
        dismissButton = { WindowsTextButton(onClick = onDismissEula) { Text("取消") } },
    )
    confirmation?.let { (instance, action) ->
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(when (action) { "stop" -> "停止实例"; "forceExit" -> "强制结束实例"; "backup" -> "备份实例"; else -> "重启实例" }) },
            text = { Text(if (action == "forceExit") "强制结束会立即终止服务器进程，可能导致数据丢失。确定继续吗？" else "确定对 ${instance.name ?: "实例 ${instance.id}"} 执行此操作吗？") },
            confirmButton = { WindowsTextButton(onClick = { confirmation = null; onAction(instance, action) }) { Text("确定") } },
            dismissButton = { WindowsTextButton(onClick = { confirmation = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.FileContent(
    state: DesktopState,
    onBrowseFiles: (DaemonInstance, String) -> Unit,
    onOpenFile: (DaemonFile) -> Unit,
    onEditContent: (String) -> Unit,
    onSaveFile: () -> Unit,
    onCloseEditor: () -> Unit,
    onUploadFile: (File) -> Unit,
    onDownloadFile: (DaemonFile, File) -> Unit,
    onCancelTransfer: () -> Unit,
    onCreateDirectory: (String) -> Unit,
    onRenameFile: (DaemonFile, String) -> Unit,
    onDeleteFile: (DaemonFile) -> Unit,
) {
    val instance = state.fileInstance ?: return
    val editingPath = state.editingPath
    var confirmDiscard by remember(editingPath) { mutableStateOf(false) }
    var structured by remember(editingPath) { mutableStateOf(editingPath?.substringAfterLast('/')?.equals("server.properties", ignoreCase = true) == true) }
    var pendingUpload by remember { mutableStateOf<File?>(null) }
    var query by remember(state.filePath) { mutableStateOf("") }
    var creatingDirectory by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<DaemonFile?>(null) }
    var deleting by remember { mutableStateOf<DaemonFile?>(null) }
    var newName by remember { mutableStateOf("") }
    if (editingPath != null) {
        Text(editingPath, style = MaterialTheme.typography.titleMedium)
        if (editingPath.substringAfterLast('/').equals("server.properties", ignoreCase = true)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (structured) WindowsButton(onClick = { structured = true }) { Text("结构化") }
                else WindowsOutlinedButton(onClick = { structured = true }) { Text("结构化") }
                if (!structured) WindowsButton(onClick = { structured = false }) { Text("文本") }
                else WindowsOutlinedButton(onClick = { structured = false }) { Text("文本") }
            }
        }
        if (structured) {
            ServerPropertiesEditor(state.fileContent, !state.busy, onEditContent, Modifier.fillMaxWidth().weight(1f))
        } else {
            OutlinedTextField(
                value = state.fileContent,
                onValueChange = onEditContent,
                modifier = Modifier.fillMaxWidth().weight(1f),
                enabled = !state.busy,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WindowsButton(onClick = onSaveFile, enabled = !state.busy) { Text("保存") }
            WindowsOutlinedButton(onClick = { if (state.fileContent != state.originalFileContent) confirmDiscard = true else onCloseEditor() }, enabled = !state.busy) { Text("关闭编辑器") }
        }
        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text("放弃未保存的修改？") },
                confirmButton = { WindowsTextButton(onClick = { confirmDiscard = false; onCloseEditor() }) { Text("放弃") } },
                dismissButton = { WindowsTextButton(onClick = { confirmDiscard = false }) { Text("取消") } },
            )
        }
    } else {
        Text("/" + state.filePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WindowsOutlinedButton(onClick = { onBrowseFiles(instance, state.filePath.substringBeforeLast('/', "")) }, enabled = !state.busy && state.filePath.isNotEmpty()) { Text("上一级") }
            WindowsOutlinedButton(onClick = { onBrowseFiles(instance, state.filePath) }, enabled = !state.busy) { Text("刷新") }
            WindowsOutlinedButton(onClick = { pendingUpload = selectLocalFile(false) }, enabled = !state.busy) { Text("上传") }
            WindowsOutlinedButton(onClick = { newName = ""; creatingDirectory = true }, enabled = !state.busy && !state.transferring) { Text("新建文件夹") }
        }
        WindowsSearchField(query, { query = it }, "搜索当前目录", Modifier.fillMaxWidth())
        if (state.transferring) WindowsOutlinedButton(onClick = onCancelTransfer) { Text("取消传输") }
        if (!state.busy && state.files.isEmpty() && state.error == null) Text("此目录为空。")
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(state.files.filter { it.name.contains(query, true) }.sortedWith(compareBy<DaemonFile> { it.type != "folder" }.thenBy { it.name.lowercase() }), key = { it.name }) { file ->
                RemoteFileRow(file, !state.busy && !state.transferring, { onOpenFile(file) },
                    { selectLocalFile(true, file.name)?.let { onDownloadFile(file, it) } },
                    { renaming = file; newName = file.name }, { deleting = file })
            }
        }
    }
    if (creatingDirectory || renaming != null) AlertDialog(
        onDismissRequest = { creatingDirectory = false; renaming = null },
        title = { Text(if (creatingDirectory) "新建文件夹" else "重命名") },
        text = { OutlinedTextField(newName, { newName = it }, label = { Text("名称") }, singleLine = true) },
        confirmButton = { WindowsTextButton(onClick = {
            if (creatingDirectory) onCreateDirectory(newName) else renaming?.let { onRenameFile(it, newName) }
            creatingDirectory = false; renaming = null
        }, enabled = newName.isNotBlank() && !state.busy) { Text("确定") } },
        dismissButton = { WindowsTextButton(onClick = { creatingDirectory = false; renaming = null }) { Text("取消") } },
    )
    deleting?.let { file -> AlertDialog(
        onDismissRequest = { deleting = null }, title = { Text("删除“${file.name}”？") },
        text = { Text(if (file.type == "folder") "文件夹及其全部内容将被递归删除，此操作不可撤销。" else "文件将从远端服务器永久删除，此操作不可撤销。") },
        confirmButton = { WindowsTextButton(onClick = { deleting = null; onDeleteFile(file) }, enabled = !state.busy) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { WindowsTextButton(onClick = { deleting = null }) { Text("取消") } },
    ) }
    pendingUpload?.let { file -> AlertDialog(
        onDismissRequest = { pendingUpload = null },
        title = { Text("上传文件") },
        text = { Text("将上传 " + file.name + "。应用会检查同名文件，但 Daemon 未提供并发覆盖保护，其他客户端同时创建同名文件时仍可能被覆盖。") },
        confirmButton = { WindowsTextButton(onClick = { pendingUpload = null; onUploadFile(file) }) { Text("确认上传") } },
        dismissButton = { WindowsTextButton(onClick = { pendingUpload = null }) { Text("取消") } },
    ) }
}

@Composable
internal fun ColumnScope.ConsoleContent(state: DesktopState, onSendCommand: (String) -> Unit) {
    var command by remember(state.consoleInstance?.id) { mutableStateOf("") }
    var query by remember(state.consoleInstance?.id) { mutableStateOf("") }
    var follow by remember { mutableStateOf(true) }
    var history by remember(state.consoleInstance?.id) { mutableStateOf(emptyList<String>()) }
    var historyIndex by remember(state.consoleInstance?.id) { mutableStateOf(0) }
    val clipboard = LocalClipboardManager.current
    val lines = remember(state.consoleLines, query) { if (query.isBlank()) state.consoleLines else state.consoleLines.filter { it.contains(query, true) } }
    val scroll = rememberLazyListState()
    fun send() {
        if (command.isBlank() || !state.consoleConnected) return
        onSendCommand(command)
        history = (history + command).takeLast(50)
        historyIndex = history.size
        command = ""
    }
    LaunchedEffect(lines, follow, query) {
        if (follow && lines.isNotEmpty()) scroll.scrollToItem(lines.lastIndex)
    }
    Surface(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("日志输出", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    if (state.consoleConnected) "● 已连接" else "● 未连接",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.consoleConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                WindowsSearchField(query, { query = it }, "搜索日志", Modifier.width(200.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(follow, { follow = it })
                    Text("跟随输出", style = MaterialTheme.typography.bodyMedium)
                }
                WindowsTextButton(onClick = { clipboard.setText(AnnotatedString(lines.joinToString("\n"))) }, enabled = lines.isNotEmpty()) { Text("复制日志") }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectionContainer(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().background(Color(0xFF14161A)).padding(horizontal = 14.dp, vertical = 10.dp), state = scroll) {
                    if (lines.isEmpty()) item { Text(if (query.isNotBlank()) "没有匹配的日志" else "等待服务器输出…", color = Color(0xFF9AA6B4), style = MaterialTheme.typography.bodyMedium) }
                    items(lines) { line -> Text(line, color = Color(0xFFD8DEE9), style = MaterialTheme.typography.bodyMedium, fontFamily = desktopMonospaceFont) }
                }
            }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(command, { command = it }, label = { Text("服务器命令") }, placeholder = { Text("Enter 发送 · ↑↓ 浏览历史") }, singleLine = true, modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { send(); true }
                        Key.DirectionUp -> if (history.isNotEmpty()) { historyIndex = (historyIndex - 1).coerceAtLeast(0); command = history[historyIndex]; true } else false
                        Key.DirectionDown -> if (history.isNotEmpty()) { historyIndex = (historyIndex + 1).coerceAtMost(history.size); command = history.getOrNull(historyIndex).orEmpty(); true } else false
                        else -> false
                    }
                }, enabled = state.consoleConnected)
                WindowsButton(onClick = ::send, enabled = state.consoleConnected && command.isNotBlank()) { Text("发送") }
            }
        }
    }
}

@Composable
private fun UserContent(
    state: DesktopState,
    onRefresh: () -> Unit,
    onCreate: (String, String, String, String, List<String>) -> Unit,
    onUpdate: (DaemonUser, String, String, String, List<String>, Boolean) -> Unit,
    onDelete: (DaemonUser) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var editingUser by remember { mutableStateOf<DaemonUser?>(null) }
    var deletingUser by remember { mutableStateOf<DaemonUser?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WindowsButton(onClick = { showCreate = true }, enabled = !state.busy) { Text("新建用户") }
        WindowsOutlinedButton(onClick = onRefresh, enabled = !state.busy) { Text("刷新") }
    }
    if (!state.busy && state.users.isEmpty() && state.error == null) Text("没有用户。")
    LazyColumn {
        items(state.users) { user ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((user.name ?: user.username ?: "未知用户") + "  ·  " + (user.role ?: "未知角色"), modifier = Modifier.weight(1f))
                WindowsTextButton(onClick = { editingUser = user }, enabled = !state.busy && user.id != null && user.role?.lowercase() in setOf("user", "admin") && !user.username.orEmpty().equals("MSLX Manger", true) && !user.username.orEmpty().equals("MSLX Manager", true)) { Text("编辑") }
                WindowsTextButton(onClick = { deletingUser = user }, enabled = !state.busy && user.id != null && user.role != "system") { Text("删除") }
            }
        }
    }
    if (showCreate) UserEditor(null, state.userResources, onDismiss = { showCreate = false }, onSave = { username, password, name, role, resources, _ ->
        if (username.isNotBlank() && password.isNotBlank()) { onCreate(username, password, name, role, resources); showCreate = false }
    })
    editingUser?.let { user -> UserEditor(user, state.userResources, onDismiss = { editingUser = null }, onSave = { _, password, name, role, resources, reset ->
        onUpdate(user, name, password, role, resources, reset); editingUser = null
    }) }
    deletingUser?.let { user -> AlertDialog(
        onDismissRequest = { deletingUser = null },
        title = { Text("删除用户") },
        text = { Text("确定删除 " + (user.username ?: user.name ?: "该用户") + "？") },
        confirmButton = { WindowsTextButton(onClick = { deletingUser = null; onDelete(user) }) { Text("删除") } },
        dismissButton = { WindowsTextButton(onClick = { deletingUser = null }) { Text("取消") } },
    ) }
}

@Composable
private fun UserEditor(user: DaemonUser?, options: List<UserResource>, onDismiss: () -> Unit, onSave: (String, String, String, String, List<String>, Boolean) -> Unit) {
    var username by remember(user?.id) { mutableStateOf(user?.username.orEmpty()) }
    var name by remember(user?.id) { mutableStateOf(user?.name.orEmpty()) }
    var password by remember(user?.id) { mutableStateOf("") }
    var admin by remember(user?.id) { mutableStateOf(user?.role.equals("admin", true)) }
    var selected by remember(user?.id) { mutableStateOf(user?.resources.orEmpty()) }
    var resetKey by remember(user?.id) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (user == null) "新建用户" else "编辑用户") },
        text = { Column(Modifier.height(380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, enabled = user == null)
            OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, singleLine = true)
            OutlinedTextField(password, { password = it }, label = { Text(if (user == null) "密码" else "新密码（留空不修改）") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(admin, { admin = it }); Text("管理员") }
            if (user != null) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(resetKey, { resetKey = it }); Text("重置 API Key（旧 Key 将失效）") }
            if (!admin) {
                Text("资源权限")
                options.forEach { option -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(option.value in selected, { checked -> selected = if (checked) selected + option.value else selected - option.value })
                    Text(option.label)
                } }
            }
        } },
        confirmButton = { WindowsTextButton(onClick = { onSave(username, password, name, if (admin) "admin" else "user", selected.distinct(), resetKey) }, enabled = user != null || username.isNotBlank() && password.isNotBlank()) { Text("保存") } },
        dismissButton = { WindowsTextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun CreateInstanceDialog(onDismiss: () -> Unit, onCreate: (String, String, String, String, String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var core by remember { mutableStateOf("server.jar") }
    var min by remember { mutableStateOf("1024") }
    var max by remember { mutableStateOf("4096") }
    var java by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    val valid = name.isNotBlank() && core.isNotBlank() && (min.toIntOrNull() ?: 0) > 0 && (max.toIntOrNull() ?: 0) >= (min.toIntOrNull() ?: Int.MAX_VALUE)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建远端实例") },
        text = { Column(Modifier.height(440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("使用 Daemon 主机上已有的服务端核心。创建任务提交后请刷新列表查看结果。")
            OutlinedTextField(name, { name = it }, label = { Text("实例名称") }, singleLine = true)
            OutlinedTextField(core, { core = it }, label = { Text("核心文件名") }, singleLine = true)
            OutlinedTextField(min, { min = it }, label = { Text("最小内存 MB") }, singleLine = true)
            OutlinedTextField(max, { max = it }, label = { Text("最大内存 MB") }, singleLine = true)
            OutlinedTextField(java, { java = it }, label = { Text("Java 路径（可选）") }, singleLine = true)
            OutlinedTextField(args, { args = it }, label = { Text("启动参数（可选）") }, singleLine = true)
            OutlinedTextField(path, { path = it }, label = { Text("远端实例路径（可选）") }, singleLine = true)
        } },
        confirmButton = { WindowsTextButton(onClick = { onCreate(name, core, min, max, java, args, path) }, enabled = valid) { Text("创建") } },
        dismissButton = { WindowsTextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PluginsContent(state: DesktopState, onOpen: (DaemonInstance, String) -> Unit, onChange: (String, String) -> Unit) {
    val instance = state.pluginsInstance ?: return
    var deleting by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WindowsOutlinedButton(onClick = { onOpen(instance, "plugins") }, enabled = !state.busy) { Text(if (state.pluginsMode == "plugins") "● 插件" else "插件") }
        WindowsOutlinedButton(onClick = { onOpen(instance, "mods") }, enabled = !state.busy) { Text(if (state.pluginsMode == "mods") "● 模组" else "模组") }
        WindowsOutlinedButton(onClick = { onOpen(instance, state.pluginsMode) }, enabled = !state.busy) { Text("刷新") }
    }
    val data = state.pluginsData
    if (data != null) {
        val entries = data.jarFiles.map { it to false } + data.clientJarFiles.map { it to false } + data.disableJarFiles.map { it to true }
        if (entries.isEmpty()) Text("没有插件或模组。")
        LazyColumn {
            items(entries) { (name, disabled) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name + if (disabled) "（已禁用）" else "", modifier = Modifier.weight(1f))
                    WindowsTextButton(onClick = { onChange(name, if (disabled) "enable" else "disable") }, enabled = !state.busy) { Text(if (disabled) "启用" else "禁用") }
                    WindowsTextButton(onClick = { deleting = name }, enabled = !state.busy) { Text("删除") }
                }
            }
        }
    }
    deleting?.let { target -> AlertDialog(
        onDismissRequest = { deleting = null },
        title = { Text("删除插件或模组") },
        text = { Text("确定删除 " + target + "？") },
        confirmButton = { WindowsTextButton(onClick = { deleting = null; onChange(target, "delete") }) { Text("删除") } },
        dismissButton = { WindowsTextButton(onClick = { deleting = null }) { Text("取消") } },
    ) }
}

@Composable
private fun ResourceContent(
    state: DesktopState,
    onSearch: (String, String, String, String) -> Unit,
    onSelect: (ModrinthProject, String, String) -> Unit,
    onInstall: (ModrinthVersion, String, String, String) -> Unit,
    onRefreshTask: () -> Unit,
    onConfirmPlan: () -> Unit,
    onDismissPlan: () -> Unit,
    onCancelTask: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var gameVersion by remember { mutableStateOf("") }
    var loader by remember { mutableStateOf("") }
    var plugin by remember { mutableStateOf(false) }
    val type = if (plugin) "plugin" else "mod"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(plugin, { plugin = it }, enabled = !state.busy && state.resourceProject == null)
        Text(if (plugin) "插件" else "模组")
        OutlinedTextField(query, { query = it }, label = { Text("搜索 Modrinth") }, singleLine = true, enabled = !state.busy, modifier = Modifier.weight(1f))
        WindowsButton(onClick = { onSearch(query, type, gameVersion, loader) }, enabled = !state.busy) { Text("搜索") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(gameVersion, { gameVersion = it }, label = { Text("游戏版本") }, singleLine = true, enabled = !state.busy && state.resourceProject == null, modifier = Modifier.weight(1f))
        OutlinedTextField(loader, { loader = it }, label = { Text("加载器") }, singleLine = true, enabled = !state.busy && state.resourceProject == null, modifier = Modifier.weight(1f))
    }
    Text("安装前会检查依赖、所选版本间的冲突和现有文件名。已安装资源的跨项目冲突仍需人工确认。", style = MaterialTheme.typography.bodySmall)
    state.resourceTask?.let { task ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("安装：" + task.status + " " + task.progress + "% " + task.message, modifier = Modifier.weight(1f))
            WindowsOutlinedButton(onClick = onRefreshTask, enabled = !state.busy) { Text("刷新状态") }
            WindowsOutlinedButton(onClick = onCancelTask, enabled = !state.busy && task.status.lowercase() !in setOf("completed", "failed", "cancelled")) { Text("取消安装") }
        }
    }
    LazyColumn {
        if (state.resourceProject == null) {
            items(state.resourceProjects) { project ->
                WindowsTextButton(onClick = { onSelect(project, gameVersion, loader) }, enabled = !state.busy) {
                    Column { Text(project.title.ifBlank { project.id }); Text(project.description, style = MaterialTheme.typography.bodySmall) }
                }
            }
        } else {
            item { Text("版本：" + state.resourceProject.title.ifBlank { state.resourceProject.id }) }
            if (state.resourceVersions.isEmpty() && !state.busy && state.error == null) item { Text("没有可安装的版本。") }
            items(state.resourceVersions) { version ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(version.name.ifBlank { version.number }, modifier = Modifier.weight(1f))
                    WindowsTextButton(onClick = { onInstall(version, type, gameVersion, loader) }, enabled = !state.busy && gameVersion.isNotBlank() && loader.isNotBlank()) { Text("检查依赖") }
                }
            }
        }
    }
    state.resourcePlan?.let { plan ->
        AlertDialog(
            onDismissRequest = onDismissPlan,
            title = { Text("确认安装清单") },
            text = { Column {
                Text("以下资源及必需依赖将安装到当前实例：")
                plan.entries.forEach { Text("• " + it.project.title.ifBlank { it.project.id } + "  " + it.version.number) }
                Text("已有资源的跨项目冲突无法自动识别，请确认服务器环境兼容。", color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { WindowsTextButton(onClick = onConfirmPlan, enabled = !state.busy) { Text("确认安装") } },
            dismissButton = { WindowsTextButton(onClick = onDismissPlan) { Text("取消") } },
        )
    }
}

@Composable
private fun AdvancedCreationDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var content by remember { mutableStateOf("""{
  "name": "",
  "core": "server.jar",
  "minM": 1024,
  "maxM": 4096,
  "dockerImage": "MSLX://DockerImage/Java/25",
  "dockerPorts": "25565:25565",
  "mcdr": false,
  "mcdrPython": "python",
  "mcdrHandler": null,
  "coreUrl": null,
  "coreSha256": null,
  "coreMirrors": [],
  "coreFileKey": null,
  "packageUrl": null,
  "packageSha256": null,
  "packageLocalPath": null,
  "packageFileKey": null
}""") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("高级创建参数") },
        text = { Column { Text("支持 Docker、MCDR、远端核心与整合包。下载 URL 必须同时提供完整 SHA-256。")
            OutlinedTextField(content, { content = it }, modifier = Modifier.fillMaxWidth().height(400.dp))
        } },
        confirmButton = { WindowsTextButton(onClick = { onCreate(content) }) { Text("提交") } },
        dismissButton = { WindowsTextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun selectLocalFile(save: Boolean, suggestedName: String = ""): File? {
    val dialog = FileDialog(null as Frame?, if (save) "下载文件" else "选择上传文件", if (save) FileDialog.SAVE else FileDialog.LOAD)
    return try {
        if (save) dialog.file = suggestedName
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val directory = dialog.directory ?: return null
        File(directory, name)
    } finally { dialog.dispose() }
}

@Composable
private fun CoreChooserContent(
    state: DesktopState,
    onCore: (String) -> Unit,
    onVersion: (String) -> Unit,
    onBuild: (String) -> Unit,
    onCreate: (String, String, String) -> Unit,
    onRestart: () -> Unit,
    onChooseJava: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var min by remember { mutableStateOf("1024") }
    var max by remember { mutableStateOf("4096") }
    var confirmJava by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Image(painterResource("msl_logo.png"), contentDescription = "MSL Logo", modifier = Modifier.size(32.dp))
        Text("MSLAPI 下载服务由 MSL 开服器提供")
        WindowsTextButton(onClick = { uriHandler.openUri("https://mslmc.cn") }) { Text("MSL 官网") }
    }
    WindowsTextButton(onClick = onRestart, enabled = !state.busy) { Text("重新选择核心") }
    if (state.coreDownload != null) {
        Text("核心：" + state.selectedCore + " 版本：" + state.selectedCoreVersion)
        Text("已取得完整 SHA-256；下载由 Daemon 进行。")
        if (!state.coreIsBedrock) {
            state.coreJavaRecommendation?.let { Text("建议使用 Java " + it) }
            Text("Java 环境：" + state.coreJavaPath.ifBlank { "Daemon 默认" }, style = MaterialTheme.typography.bodySmall)
            if (state.coreJavaOptions.isEmpty()) Text("未获取到 Java 环境，将使用 Daemon 默认设置。", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().height(95.dp)) {
                LazyColumn {
                    item { WindowsTextButton(onClick = { onChooseJava("") }, enabled = !state.busy) { Text("Daemon 默认") } }
                    items(state.coreJavaOptions) { java ->
                        WindowsTextButton(onClick = { onChooseJava(java.path) }, enabled = !state.busy && java.path.isNotBlank()) { Text(java.version + "  " + java.path) }
                    }
                }
            }
        }
        OutlinedTextField(name, { name = it }, label = { Text("实例名称") }, singleLine = true, enabled = !state.busy)
        OutlinedTextField(min, { min = it }, label = { Text("最小内存 MB") }, singleLine = true, enabled = !state.busy)
        OutlinedTextField(max, { max = it }, label = { Text("最大内存 MB") }, singleLine = true, enabled = !state.busy)
        val selectedMajor = state.coreJavaOptions.firstOrNull { it.path == state.coreJavaPath }?.let { javaMajor(it.version) }
        val mismatch = !state.coreIsBedrock && selectedMajor != null && state.coreJavaRecommendation != null && selectedMajor != state.coreJavaRecommendation
        WindowsButton(onClick = { if (mismatch) confirmJava = true else onCreate(name, min, max) }, enabled = !state.busy && name.isNotBlank()) { Text("创建实例") }
        if (confirmJava) AlertDialog(
            onDismissRequest = { confirmJava = false },
            title = { Text("Java 版本与建议不同") },
            text = { Text("建议 Java " + state.coreJavaRecommendation + "，当前选择 Java " + selectedMajor + "。仍要继续吗？") },
            confirmButton = { WindowsTextButton(onClick = { confirmJava = false; onCreate(name, min, max) }) { Text("继续") } },
            dismissButton = { WindowsTextButton(onClick = { confirmJava = false }) { Text("取消") } },
        )
    } else if (state.selectedCoreVersion != null) {
        Text("选择构建版本")
        LazyColumn { items(state.coreBuilds.ifEmpty { listOf("latest") }) { build -> WindowsTextButton(onClick = { onBuild(build) }, enabled = !state.busy) { Text(build) } } }
    } else if (state.selectedCore != null) {
        Text("选择游戏版本")
        LazyColumn { items(state.coreVersions) { version -> WindowsTextButton(onClick = { onVersion(version) }, enabled = !state.busy) { Text(version) } } }
    } else {
        Text("选择 Java 或基岩版服务端核心")
        LazyColumn { items(state.coreNames) { core -> WindowsTextButton(onClick = { onCore(core) }, enabled = !state.busy) { Text(core) } } }
    }
}
