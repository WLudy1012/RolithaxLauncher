package com.wludy.rolithax.launcher.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wludy.rolithax.launcher.data.ServerRef
import com.wludy.rolithax.launcher.ui.RolithaxCard
import com.wludy.rolithax.launcher.ui.RolithaxSectionHeader
import com.wludy.rolithax.launcher.ui.theme.GlassSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceSettingsScreen(
    ref: ServerRef,
    onBack: () -> Unit,
    onOpenPluginsMods: () -> Unit,
    onOpenServerProps: () -> Unit,
    onOpenFileManager: () -> Unit,
) {
    val viewModel: InstanceSettingsViewModel = viewModel(
        key = "instance_settings_${ref.catalogKey}",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                InstanceSettingsViewModel(app, ref)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("实例设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.settings != null) {
                        TextButton(
                            onClick = viewModel::save,
                            enabled = !state.saving,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            if (state.saving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(2.dp).size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Text("保存", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        when {
            state.loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            state.error != null && state.settings == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        text = state.error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = viewModel::load, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("重试")
                    }
                }
            }

            state.settings == null -> Unit

            else -> {
                val s = state.settings!!
                val javaType = parseJavaType(s.java, s.dockerImage, s.args, state.localJavas)
                val isDocker = javaType == JavaType.DOCKER_JAVA || javaType == JavaType.DOCKER_CUSTOM ||
                    javaType == JavaType.MCDR_DOCKER_JAVA || javaType == JavaType.MCDR_DOCKER_CUSTOM
                val memoryOrderInvalid = s.minM != null && s.maxM != null && s.maxM < s.minM
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    state.updateProgress?.let { progress ->
                        item {
                            UpdateBanner(
                                message = state.updateMessage ?: "正在更新配置",
                                progress = progress,
                                isError = state.updateError,
                            )
                        }
                    }

                    // 快捷入口
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                EntryCard("插件 / 模组", Icons.Filled.Build, Modifier.weight(1f)) {
                                    onOpenPluginsMods()
                                }
                                EntryCard("服务器设置", Icons.Filled.Settings, Modifier.weight(1f)) {
                                    onOpenServerProps()
                                }
                            }
                            EntryCard("文件管理（查看/编辑 eula.txt 等）", Icons.AutoMirrored.Filled.List, Modifier.fillMaxWidth()) {
                                onOpenFileManager()
                            }
                        }
                    }

                    // 基本信息
                    item {
                        SettingSection("基本信息") {
                            TextFieldSetting("实例名称", s.name) { v -> viewModel.update { it.copy(name = v) } }
                            TextFieldSetting("基础路径", s.base) { v -> viewModel.update { it.copy(base = v) } }
                            TextFieldSetting("过期时间", s.expireTime, placeholder = "yyyy-MM-dd HH:mm:ss") { v ->
                                viewModel.update { it.copy(expireTime = v.ifBlank { null }) }
                            }
                        }
                    }

                    // 启动配置
                    item {
                        SettingSection("启动配置") {
                            JavaEnvironmentSelector(
                                java = s.java,
                                dockerImage = s.dockerImage,
                                args = s.args,
                                onlineVersions = state.onlineJavaVersions,
                                localJavas = state.localJavas,
                                onJavaChanged = { v -> viewModel.update { it.copy(java = v) } },
                                onDockerImageChanged = { v -> viewModel.update { it.copy(dockerImage = v) } },
                            )
                            TextFieldSetting("核心文件名", s.core) { v -> viewModel.update { it.copy(core = v) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NumberFieldSetting(
                                    label = "最小内存",
                                    value = s.minM,
                                    modifier = Modifier.weight(1f),
                                    suffix = "MB",
                                ) { v -> viewModel.update { it.copy(minM = v) } }
                                NumberFieldSetting(
                                    label = "最大内存",
                                    value = s.maxM,
                                    modifier = Modifier.weight(1f),
                                    suffix = "MB",
                                    isError = memoryOrderInvalid,
                                    supportingText = if (memoryOrderInvalid) "需不小于最小内存" else null,
                                ) { v -> viewModel.update { it.copy(maxM = v) } }
                            }
                            TextFieldSetting("启动参数", s.args, placeholder = "额外 JVM 参数") { v ->
                                viewModel.update { it.copy(args = v) }
                            }
                        }
                    }

                    // 运行行为
                    item {
                        SettingSection("运行行为") {
                            SwitchSetting("崩溃自动重启", s.autoRestart == true) { v ->
                                viewModel.update { it.copy(autoRestart = v) }
                            }
                            SwitchSetting("强制自动重启", s.forceAutoRestart == true) { v ->
                                viewModel.update { it.copy(forceAutoRestart = v) }
                            }
                            SwitchSetting("守护程序启动时自启", s.runOnStartup == true) { v ->
                                viewModel.update { it.copy(runOnStartup = v) }
                            }
                            SwitchSetting("忽略 EULA", s.ignoreEula == true) { v ->
                                viewModel.update { it.copy(ignoreEula = v) }
                            }
                            SwitchSetting("强制 JVM UTF-8", s.forceJvmUTF8 == true) { v ->
                                viewModel.update { it.copy(forceJvmUTF8 = v) }
                            }
                            SwitchSetting("允许原始 ASCII 颜色", s.allowOriginASCIIColors == true) { v ->
                                viewModel.update { it.copy(allowOriginASCIIColors = v) }
                            }
                            SwitchSetting("监控玩家", s.monitorPlayers == true) { v ->
                                viewModel.update { it.copy(monitorPlayers = v) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NumberFieldSetting(
                                    label = "强制退出延迟",
                                    value = s.forceExitDelay,
                                    modifier = Modifier.weight(1f),
                                    suffix = "秒",
                                ) { v -> viewModel.update { it.copy(forceExitDelay = v) } }
                                TextFieldSetting("停止命令", s.stopCommand, Modifier.weight(1f)) { v ->
                                    viewModel.update { it.copy(stopCommand = v) }
                                }
                            }
                            TextFieldSetting("外置登录地址(Yggdrasil)", s.yggdrasilApiAddr, placeholder = "留空使用正版/内置") { v ->
                                viewModel.update { it.copy(yggdrasilApiAddr = v) }
                            }
                        }
                    }

                    // 编码
                    item {
                        SettingSection("编码") {
                            DropdownSetting("输入编码", listOf("utf-8", "gbk"), s.inputEncoding) { v ->
                                viewModel.update { it.copy(inputEncoding = v) }
                            }
                            DropdownSetting("输出编码", listOf("utf-8", "gbk"), s.outputEncoding) { v ->
                                viewModel.update { it.copy(outputEncoding = v) }
                            }
                            DropdownSetting("文件编码", listOf("utf-8", "utf-8-bom", "gbk"), s.fileEncoding) { v ->
                                viewModel.update { it.copy(fileEncoding = v) }
                            }
                        }
                    }

                    // 目录路径
                    item {
                        SettingSection("目录路径") {
                            TextFieldSetting("server.properties 路径", s.serverPropertiesPath) { v ->
                                viewModel.update { it.copy(serverPropertiesPath = v) }
                            }
                            TextFieldSetting("插件目录", s.pluginsPath) { v ->
                                viewModel.update { it.copy(pluginsPath = v) }
                            }
                            TextFieldSetting("模组目录", s.modsPath) { v ->
                                viewModel.update { it.copy(modsPath = v) }
                            }
                            TextFieldSetting("地图目录", s.worldPath) { v ->
                                viewModel.update { it.copy(worldPath = v) }
                            }
                            TextFieldSetting("Region 目录", s.regionPath) { v ->
                                viewModel.update { it.copy(regionPath = v) }
                            }
                        }
                    }

                    // 备份
                    item {
                        SettingSection("备份") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NumberFieldSetting(
                                    label = "备份数量上限",
                                    value = s.backupMaxCount,
                                    modifier = Modifier.weight(1f),
                                ) { v -> viewModel.update { it.copy(backupMaxCount = v) } }
                                NumberFieldSetting(
                                    label = "备份间隔",
                                    value = s.backupDelay,
                                    modifier = Modifier.weight(1f),
                                    suffix = "秒",
                                ) { v -> viewModel.update { it.copy(backupDelay = v) } }
                            }
                            TextFieldSetting("备份路径", s.backupPath) { v ->
                                viewModel.update { it.copy(backupPath = v) }
                            }
                        }
                    }

                    // FRP
                    item {
                        SettingSection("内网穿透(FRP)") {
                            TextFieldSetting("绑定 FRP ID", s.bindFrpId, placeholder = "8 位数字，多个用逗号分隔") { v ->
                                viewModel.update { it.copy(bindFrpId = v.ifBlank { null }) }
                            }
                        }
                    }

                    // Docker（仅当启动方式为 Docker 时显示）
                    if (isDocker) {
                        item {
                            SettingSection("Docker 运行配置") {
                                TextFieldSetting("镜像", s.dockerImage) { v ->
                                    viewModel.update { it.copy(dockerImage = v) }
                                }
                                TextFieldSetting("工作目录", s.dockerWorkingDir) { v ->
                                    viewModel.update { it.copy(dockerWorkingDir = v) }
                                }
                                TextFieldSetting("端口映射", s.dockerPorts, placeholder = "宿主机端口:容器端口") { v ->
                                    viewModel.update { it.copy(dockerPorts = v) }
                                }
                                DropdownSetting("网络模式", listOf("bridge", "host", "none"), s.dockerNetworkMode) { v ->
                                    viewModel.update { it.copy(dockerNetworkMode = v) }
                                }
                                TextFieldSetting("网络别名", s.dockerNetworkAlias) { v ->
                                    viewModel.update { it.copy(dockerNetworkAlias = v.ifBlank { null }) }
                                }
                                TextFieldSetting("挂载卷", s.dockerVolumes, placeholder = "/宿主机:/容器,多个用逗号") { v ->
                                    viewModel.update { it.copy(dockerVolumes = v.ifBlank { null }) }
                                }
                                TextFieldSetting("环境变量", s.dockerEnvVars, placeholder = "KEY=VALUE,多个用逗号") { v ->
                                    viewModel.update { it.copy(dockerEnvVars = v.ifBlank { null }) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    NumberFieldSetting(
                                        label = "CPU 限制",
                                        value = s.dockerCpuPercentage,
                                        modifier = Modifier.weight(1f),
                                        suffix = "%",
                                    ) { v -> viewModel.update { it.copy(dockerCpuPercentage = v) } }
                                    TextFieldSetting("指定 CPU 核心", s.dockerCpuCores, Modifier.weight(1f), placeholder = "0,1 或 0-3") { v ->
                                        viewModel.update { it.copy(dockerCpuCores = v.ifBlank { null }) }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    NumberFieldSetting(
                                        label = "最大内存",
                                        value = s.dockerMaxMemoryMb,
                                        modifier = Modifier.weight(1f),
                                        suffix = "MB",
                                    ) { v -> viewModel.update { it.copy(dockerMaxMemoryMb = v) } }
                                    NumberFieldSetting(
                                        label = "最大交换",
                                        value = s.dockerMaxSwapMb,
                                        modifier = Modifier.weight(1f),
                                        suffix = "MB",
                                    ) { v -> viewModel.update { it.copy(dockerMaxSwapMb = v) } }
                                }
                                TextFieldSetting("磁盘限制", s.dockerMaxStorage, placeholder = "如 10g 或 500m") { v ->
                                    viewModel.update { it.copy(dockerMaxStorage = v.ifBlank { null }) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    TextFieldSetting("上传限速", s.dockerUploadRate, Modifier.weight(1f), placeholder = "如 1mb") { v ->
                                        viewModel.update { it.copy(dockerUploadRate = v.ifBlank { null }) }
                                    }
                                    TextFieldSetting("下载限速", s.dockerDownloadRate, Modifier.weight(1f), placeholder = "如 1mb") { v ->
                                        viewModel.update { it.copy(dockerDownloadRate = v.ifBlank { null }) }
                                    }
                                }
                                TextFieldSetting("额外参数", s.dockerExtraArgs, placeholder = "docker run 原生参数") { v ->
                                    viewModel.update { it.copy(dockerExtraArgs = v.ifBlank { null }) }
                                }
                                TextFieldSetting("额外 Hosts", s.dockerExtraHosts, placeholder = "host.mslx.internal:host-gateway") { v ->
                                    viewModel.update { it.copy(dockerExtraHosts = v.ifBlank { null }) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 可复用小组件 ====================

/** 分区：统一标题层级（[RolithaxSectionHeader]）+ 玻璃卡片，卡片内边距 16dp、行间距 12dp。 */
@Composable
private fun SettingSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RolithaxSectionHeader(title = title)
        RolithaxCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** 更新进度/失败提示：用不透明语义色容器保证深浅色下正文对比度。 */
@Composable
private fun UpdateBanner(
    message: String,
    progress: Double,
    isError: Boolean,
) {
    val scheme = MaterialTheme.colorScheme
    GlassSurface(
        modifier = Modifier.fillMaxWidth(),
        tint = if (isError) scheme.errorContainer else scheme.secondaryContainer,
        alpha = 1f,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isError) Icons.Filled.Warning else Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!isError) {
                LinearProgressIndicator(
                    progress = { (progress / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 输入框标签：单行省略，避免长标签在窄列里折行撑高控件。 */
@Composable
private fun FieldLabel(text: String) {
    Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun TextFieldSetting(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value.orEmpty(),
        onValueChange = onValueChange,
        enabled = enabled,
        label = { FieldLabel(label) },
        placeholder = { Text(text = placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberFieldSetting(
    label: String,
    value: Int?,
    modifier: Modifier = Modifier,
    suffix: String = "",
    isError: Boolean = false,
    supportingText: String? = null,
    enabled: Boolean = true,
    onValueChange: (Int?) -> Unit,
) {
    // 本地缓冲：清空输入框时不立刻把状态打回 null，避免删到一半被重置
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    val support = supportingText?.takeIf { it.isNotBlank() }
    // 仅在有提示时传 supportingText，否则 Material 会为空占位预留高度
    val supportingSlot: (@Composable () -> Unit)? = if (support == null) {
        null
    } else {
        {
            Text(
                text = support,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(9)
            text = digits
            onValueChange(digits.toIntOrNull())
        },
        enabled = enabled,
        label = { FieldLabel(label) },
        suffix = { if (suffix.isNotBlank()) Text(text = suffix, style = MaterialTheme.typography.labelMedium) },
        supportingText = supportingSlot,
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SwitchSetting(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 整行可点击：点击标题与开关之间的空白也能切换
            .clip(RoundedCornerShape(12.dp))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        )
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DropdownSetting(
    label: String,
    options: List<String>,
    selected: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { FieldLabel(label) },
            shape = RoundedCornerShape(12.dp),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun EntryCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    RolithaxCard(
        modifier = modifier,
        onClick = onClick,
        highlighted = true,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
