package com.wludy.rolithax.launcher.ui.settings

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wludy.rolithax.launcher.data.UpdateChannel
import com.wludy.rolithax.launcher.ui.RolithaxCard
import com.wludy.rolithax.launcher.ui.WorkspaceBar
import com.wludy.rolithax.launcher.ui.WorkspaceBody
import com.wludy.rolithax.launcher.ui.RolithaxSectionHeader
import com.wludy.rolithax.launcher.ui.theme.GlassSurface
import com.wludy.rolithax.launcher.ui.update.UpdateViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenUserCenter: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenLocalServerSettings: () -> Unit,
    onOpenConnections: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var backupMode by remember { mutableStateOf<String?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) viewModel.exportBackup(uri, backupPassword)
        backupPassword = ""
        backupMode = null
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importUri = uri
            backupPassword = ""
            backupMode = "import"
        }
    }

    // 手动检查更新：必须与 MainActivity 的 UpdateHost 共用同一个 activity 作用域 ViewModel
    val activity = LocalContext.current.findActivity()
    val updateViewModel: UpdateViewModel = if (activity != null) {
        viewModel(viewModelStoreOwner = activity)
    } else {
        viewModel()
    }
    val updateState by updateViewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        updateViewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }
    val ctx = LocalContext.current
    val versionName = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty()
        }.getOrDefault("")
    }

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = Color.Transparent,
        // Dock 已提升至 NavHost 外层；页面 Scaffold 不再自绘底栏
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { WorkspaceBar("设置") },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        WorkspaceBody(Modifier.padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                // ---- 账号 ----
                SectionTitle("账号")
                RolithaxCard(
                    onClick = onOpenUserCenter,
                    modifier = Modifier.fillMaxWidth(),
                    highlighted = true,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "用户中心",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = "查看头像与名称",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                Spacer(Modifier.size(12.dp))

                // ---- 更新渠道 ----
                SectionTitle("更新渠道")
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        ChannelOption(
                            title = "稳定版",
                            subtitle = "仅接收正式稳定版更新（推荐）",
                            selected = settings.updateChannel == UpdateChannel.STABLE,
                            onClick = { viewModel.setUpdateChannel(UpdateChannel.STABLE) },
                        )
                        ChannelOption(
                            title = "测试版",
                            subtitle = "同时接收 Beta 测试版更新",
                            selected = settings.updateChannel == UpdateChannel.BETA,
                            onClick = { viewModel.setUpdateChannel(UpdateChannel.BETA) },
                        )
                    }
                }

                Spacer(Modifier.size(12.dp))

                // ---- 服务端 ----
                SectionTitle("服务端")
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        EntryRow(
                            icon = { Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.primary) },
                            title = "连接管理",
                            subtitle = "管理远端 Daemon 和本机实例入口",
                            onClick = onOpenConnections,
                        )
                        EntryRow(
                            icon = { Icon(Icons.Filled.Build, null, tint = MaterialTheme.colorScheme.primary) },
                            title = "本机运行时与开服设置",
                            subtitle = "内存、JVM 参数、后台保活、Java 运行时",
                            onClick = onOpenLocalServerSettings,
                        )
                        EntryRow(
                            icon = { Icon(Icons.Filled.Build, null, tint = MaterialTheme.colorScheme.primary) },
                            title = "导出应用数据",
                            subtitle = "加密迁移 Daemon 配置与本机实例",
                            onClick = { backupMode = "export"; backupPassword = "" },
                        )
                        EntryRow(
                            icon = { Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary) },
                            title = "导入应用数据",
                            subtitle = "从加密备份恢复配置与实例文件",
                            onClick = { openBackup.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                        )
                    }
                }

                Spacer(Modifier.size(12.dp))

                // ---- 通用 ----
                SectionTitle("通用")
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        EntryRow(
                            icon = { Icon(Icons.Filled.Star, null, tint = MaterialTheme.colorScheme.primary) },
                            title = "外观",
                            subtitle = "主题颜色与动态取色",
                            onClick = onOpenAppearance,
                        )
                        EntryRow(
                            icon = { Icon(Icons.AutoMirrored.Filled.List, null, tint = MaterialTheme.colorScheme.primary) },
                            title = "运行日志",
                            subtitle = "查看与导出应用日志",
                            onClick = onOpenLogs,
                        )
                        EntryRow(
                            icon = { Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.primary) },
                            title = "检查更新",
                            subtitle = if (versionName.isBlank()) "Rolithax Launcher" else "Rolithax Launcher v$versionName",
                            onClick = { if (!updateState.checking) updateViewModel.checkManually() },
                            trailing = {
                                if (updateState.checking) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                }
                            },
                        )
                        EntryRow(
                            icon = { Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.primary) },
                            title = "关于",
                            subtitle = "版本、更新说明与贡献者",
                            onClick = onOpenAbout,
                        )
                    }
                }

            }
        }
    }

    backupMode?.let { mode ->
        AlertDialog(
            onDismissRequest = { backupMode = null; backupPassword = "" },
            title = { Text(if (mode == "export") "设置备份口令" else "输入备份口令") },
            text = {
                OutlinedTextField(
                    value = backupPassword,
                    onValueChange = { backupPassword = it },
                    label = { Text("口令（至少 8 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = backupPassword.length >= 8,
                    onClick = {
                        if (mode == "export") {
                            createBackup.launch("rolithax-backup.rlxb")
                        } else {
                            importUri?.let { viewModel.importBackup(it, backupPassword) }
                            backupPassword = ""
                            importUri = null
                            backupMode = null
                        }
                    },
                ) { Text(if (mode == "export") "选择保存位置" else "导入") }
            },
            dismissButton = { TextButton(onClick = { backupMode = null; backupPassword = "" }) { Text("取消") } },
        )
    }

}

@Composable
private fun SectionTitle(text: String) {
    RolithaxSectionHeader(
        title = text,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun ChannelOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 整行可点：按钮自身不再接收点击，避免重复触发
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EntryRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

private tailrec fun Context.findActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
