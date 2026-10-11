package com.wludy.rolithax.launcher.ui.settings

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wludy.rolithax.launcher.data.localengine.InstanceStorage
import com.wludy.rolithax.launcher.data.localengine.LocalInstanceStore
import com.wludy.rolithax.launcher.data.localengine.LocalStorage
import com.wludy.rolithax.launcher.data.localengine.ServerFiles
import com.wludy.rolithax.launcher.ui.RolithaxCard
import com.wludy.rolithax.launcher.ui.RolithaxSectionHeader
import kotlinx.coroutines.launch

/**
 * 本机实例设置：直接编辑实例目录（公共或应用私有）下的 `instance.json` 元数据
 * 与 `server.properties`，并提供 eula.txt / server.properties 的文本编辑入口（对应 Daemon 实例的「文件管理」）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalInstanceSettingsScreen(
    dirName: String,
    onBack: () -> Unit,
) {
    val viewModel: LocalInstanceSettingsViewModel = viewModel(
        key = "local_instance_settings_$dirName",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                LocalInstanceSettingsViewModel(app, dirName)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editingFile by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    // 从系统「所有文件访问」授权页回来时重读权限，否则公共目录实例会一直显示未授权
    LifecycleResumeEffect(Unit) {
        viewModel.refreshStorageAccess()
        onPauseOrDispose { }
    }

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("本机实例设置", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.meta != null) {
                        TextButton(
                            onClick = viewModel::save,
                            enabled = !state.saving,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            if (state.saving) {
                                CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp)
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
        val meta = state.meta
        when {
            state.loading -> Column(
                Modifier.fillMaxSize().padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            meta == null -> Column(
                Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            ) {
                Text(state.error ?: "无法加载实例", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::load, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
            }

            else -> {
                // 启动性能参数：默认跟随「设置 → 本机运行时与开服设置」的全局默认，需要差异时才独立覆盖
                val eff = state.effective ?: meta
                val inherit = meta.inheritGlobal
                val portInvalid = meta.serverPort !in 1..65535
                val playersInvalid = meta.maxPlayers < 1
                // 跟随全局时字段只读，错误态留给用户可改的场景
                val memoryOrderInvalid = !inherit && eff.maxMemMb < eff.minMemMb
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item {
                        Text(
                            "直接读写下面这个实例目录，改动在下次启动生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 存放位置：公共目录给出能在文件管理器里直接打开的绝对路径
                    item {
                        SettingSection("实例目录（${state.storage.label}）") {
                            Text(
                                text = state.dirPath,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            if (state.storage == InstanceStorage.PUBLIC && !state.publicStorageGranted) {
                                SettingNote(
                                    text = "该实例在公共目录，但未授予「所有文件访问」，现在无法启动。",
                                    isError = true,
                                )
                                TextButton(
                                    onClick = {
                                        if (!viewModel.openPublicStorageSettings()) {
                                            scope.launch { snackbarHostState.showSnackbar("未能打开系统授权页，请手动到设置里查找") }
                                        }
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("去系统授权") }
                            }
                        }
                    }

                    // 显示名不可改目录（目录名固定为 dirName），仅改 server 名称展示与 MOTD
                    item {
                        SettingSection("服务器") {
                            TextFieldSetting("服务器名称（instance.json）", meta.name) { v ->
                                viewModel.update { it.copy(name = v) }
                            }
                            TextFieldSetting("MOTD", meta.motd) { v -> viewModel.update { it.copy(motd = v) } }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NumberFieldSetting(
                                    label = "端口",
                                    value = meta.serverPort,
                                    modifier = Modifier.weight(1f),
                                    isError = portInvalid,
                                    supportingText = if (portInvalid) "范围 1 - 65535" else null,
                                ) { p -> viewModel.update { it.copy(serverPort = p) } }
                                NumberFieldSetting(
                                    label = "最大玩家",
                                    value = meta.maxPlayers,
                                    modifier = Modifier.weight(1f),
                                    isError = playersInvalid,
                                    supportingText = if (playersInvalid) "至少 1" else null,
                                ) { p -> viewModel.update { it.copy(maxPlayers = p) } }
                            }
                            SwitchSetting("正版验证 online-mode", meta.onlineMode) { v ->
                                viewModel.update { it.copy(onlineMode = v) }
                            }
                        }
                    }

                    item {
                        SettingSection("启动与性能") {
                            SwitchSetting(
                                title = "跟随全局默认（内存 / JVM 参数 / GC / 保活）",
                                checked = inherit,
                                subtitle = "当前生效：最小 ${state.globals.minMemMb} MB · 最大 ${state.globals.maxMemMb} MB · " +
                                    "JVM 参数「${state.globals.jvmArgs.ifBlank { "无" }}」",
                                onCheckedChange = { v -> viewModel.setInheritGlobal(v) },
                            )
                            if (inherit) {
                                SettingNote("要改请去「设置 → 本机运行时与开服设置」。")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NumberFieldSetting(
                                    label = "最小内存",
                                    value = eff.minMemMb,
                                    modifier = Modifier.weight(1f),
                                    suffix = "MB",
                                    enabled = !inherit,
                                ) { p -> viewModel.update { it.copy(minMemMb = p) } }
                                NumberFieldSetting(
                                    label = "最大内存",
                                    value = eff.maxMemMb,
                                    modifier = Modifier.weight(1f),
                                    suffix = "MB",
                                    enabled = !inherit,
                                    isError = memoryOrderInvalid,
                                    supportingText = if (memoryOrderInvalid) "需不小于最小内存" else null,
                                ) { p -> viewModel.update { it.copy(maxMemMb = p) } }
                            }
                            TextFieldSetting("额外 JVM 参数", eff.jvmArgs, enabled = !inherit) { v ->
                                viewModel.update { it.copy(jvmArgs = v.trim()) }
                            }
                            SwitchSetting("后台保活（常驻通知）", eff.keepAlive, enabled = !inherit) { v ->
                                viewModel.update { it.copy(keepAlive = v) }
                            }
                            SwitchSetting("Serial GC（省内存）", eff.useSerialGc, enabled = !inherit) { v ->
                                viewModel.update { it.copy(useSerialGc = v) }
                            }
                        }
                    }

                    // Java 运行时
                    if (state.runtimeOptions.size > 1) {
                        item {
                            SettingSection(
                                title = "Java 运行时（${state.jreAbi}）",
                                subtitle = "未安装的请到「设置 → 本机运行时与开服设置」安装；" +
                                    "标「不可用」的 Java 版本暂无 Android 构建。",
                            ) {
                                RuntimeChips(
                                    options = state.runtimeOptions,
                                    selectedId = meta.runtimeId,
                                    onSelect = viewModel::selectRuntime,
                                )
                            }
                        }
                    }

                    // 文本文件编辑
                    item {
                        SettingSection("配置文本") {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(
                                    onClick = { editingFile = ServerFiles.PROPERTIES_NAME },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Text("server.properties", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                OutlinedButton(
                                    onClick = { editingFile = ServerFiles.EULA_NAME },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Text("eula.txt", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }

                    state.error?.let {
                        item {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    editingFile?.let { name ->
        FileEditorDialog(
            title = name,
            initial = remember(name) { viewModel.readFile(name) },
            onDismiss = { editingFile = null },
            onSave = { content ->
                if (viewModel.writeFile(name, content)) {
                    scope.launch { snackbarHostState.showSnackbar("已写入 $name") }
                } else {
                    scope.launch { snackbarHostState.showSnackbar("写入失败") }
                }
                editingFile = null
            },
        )
    }
}

/**
 * 本机实例的二级子页兜底：「文件管理 / 插件模组 / server.properties」的 App 内统一体验只对接了 Daemon API；
 * 本机实例的配置文本与 Java 运行时在本机实例设置页提供，
 * 而**公共目录**实例本身就能用系统文件管理器直接放插件/改配置（路径在本机实例设置页顶部）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LocalInstancePendingScreen(
    dirName: String,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val storage = remember(dirName) { LocalInstanceStore.storageOf(context, dirName) }
    val dirPath = remember(dirName, storage) { LocalInstanceStore.dir(context, dirName).absolutePath }
    val canOpenInFileManager = storage == InstanceStorage.PUBLIC && LocalStorage.publicStorageGranted()

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("本机实例", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                // 小屏下正文较长，允许滚动避免底部按钮被挤出屏幕
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "本机的文件与插件直接在实例目录里管理",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "这一页对接的是远程 Daemon 的文件接口。本机实例的文件就在下面这个目录里，" +
                        "用系统文件管理器就能放 mods/、plugins/ 与改配置文件（改完重启服务生效）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingSection("实例目录（${storage.label}）") {
                Text(dirPath, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("Rolithax 实例目录", dirPath))
                            scope.launch { snackbarHostState.showSnackbar("已复制目录路径") }
                        },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text("复制路径") }
                    if (canOpenInFileManager) {
                        TextButton(
                            onClick = {
                                val opened = openPublicDirInFileManager(context, dirPath)
                                if (!opened) {
                                    scope.launch { snackbarHostState.showSnackbar("未找到可打开该目录的文件管理器，已改为复制路径") }
                                    openPublicDirInFileManager(context, dirPath, copyOnly = true)
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("在文件管理器打开", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            if (storage == InstanceStorage.PRIVATE) {
                SettingNote(
                    "提示：该实例在应用私有目录，系统文件管理器看不到；新建时选「公共目录」可直接拖文件进去。",
                )
            }
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(
                    text = "本机实例设置（server.properties / eula / Java 运行时）",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回") }
        }
    }
}

/**
 * 用系统文件管理器打开公共目录下的实例目录（SAF 的 primary:<相对路径> 文档 URI）。
 * [copyOnly] 为 true 时只把路径写回剪贴板（没有可用文件管理器时的兜底）；成功打开返回 true。
 */
private fun openPublicDirInFileManager(
    context: Context,
    dirPath: String,
    copyOnly: Boolean = false,
): Boolean {
    if (copyOnly) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Rolithax 实例目录", dirPath))
        return true
    }
    val externalRoot = runCatching { android.os.Environment.getExternalStorageDirectory().absolutePath }.getOrDefault("")
    val relative = dirPath.removePrefix(externalRoot).removePrefix("/")
    if (relative.isBlank() || relative == dirPath) return false
    return runCatching {
        val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relative")
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
}

// ==================== 可复用小组件 ====================

/** 分区：统一标题层级（[RolithaxSectionHeader]）+ 玻璃卡片，卡片内边距 16dp、行间距 12dp。 */
@Composable
private fun SettingSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RolithaxSectionHeader(title = title, subtitle = subtitle)
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

/** 卡片内说明文字：比正文弱一级；[isError] 用于需要立刻被看见的阻断性提示。 */
@Composable
private fun SettingNote(text: String, isError: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 输入框标签：单行省略，避免长标签在窄列里折行撑高控件。 */
@Composable
private fun FieldLabel(text: String) {
    Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun TextFieldSetting(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        label = { FieldLabel(label) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun NumberFieldSetting(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    suffix: String = "",
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    onValueChange: (Int) -> Unit,
) {
    // 本地缓冲：清空输入框时不立刻把状态打回旧值，避免删到一半被重置
    var text by remember(value) { mutableStateOf(value.toString()) }
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
            digits.toIntOrNull()?.let(onValueChange)
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
    subtitle: String? = null,
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
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

/** Java 运行时选项：可换行排列，避免选项多时横向溢出或压缩触控区。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuntimeChips(
    options: List<LocalRuntimeOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { opt ->
            val suffix = when {
                !opt.supported -> "·不可用"
                !opt.installed -> "·未装"
                else -> ""
            }
            FilterChip(
                selected = opt.id == selectedId,
                onClick = { onSelect(opt.id) },
                enabled = opt.supported,
                label = { Text(text = opt.label + suffix, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun FileEditorDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(title) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "编辑 $title", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                // 配置文件按代码文本阅读：等宽 + 限制高度后由输入框内部滚动
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 8,
                maxLines = 16,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
