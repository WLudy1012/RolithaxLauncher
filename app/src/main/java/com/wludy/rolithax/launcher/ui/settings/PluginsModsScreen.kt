package com.wludy.rolithax.launcher.ui.settings

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.wludy.rolithax.launcher.data.ServerRef
import com.wludy.rolithax.launcher.ui.MslxCard
import com.wludy.rolithax.launcher.ui.MslxEmptyState
import com.wludy.rolithax.launcher.ui.MslxSectionHeader
import com.wludy.rolithax.launcher.ui.theme.GlassCardShape

enum class PmFilter { ALL, ENABLED, DISABLED, CLIENT }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PluginsModsScreen(
    ref: ServerRef,
    onBack: () -> Unit,
) {
    val viewModel: PluginsModsViewModel = viewModel(
        key = "pm_${ref.catalogKey}",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                PluginsModsViewModel(app, ref)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var filter by remember { mutableStateOf(PmFilter.ALL) }
    var showBatchDialog by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.upload(uri)
    }

    LaunchedEffect(Unit) {
        viewModel.message.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("插件 / 模组管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::load, enabled = !state.busy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                    TextButton(
                        onClick = { filePicker.launch("*/*") },
                        enabled = !state.busy,
                    ) {
                        if (state.busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("上传")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 模式切换
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = state.mode == "plugins",
                    onClick = { viewModel.setMode("plugins") },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("插件") }
                SegmentedButton(
                    selected = state.mode == "mods",
                    onClick = { viewModel.setMode("mods") },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("模组") }
            }

            when {
                state.loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "正在读取文件列表…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                state.error != null && state.data == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        MslxEmptyState(
                            icon = Icons.Filled.Warning,
                            title = "加载失败",
                            description = state.error.orEmpty(),
                            actionLabel = "重试",
                            onAction = viewModel::load,
                        )
                    }
                }

                else -> {
                    val data = state.data
                    if (data != null && data.totalCount == 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            MslxEmptyState(
                                icon = Icons.AutoMirrored.Filled.List,
                                title = "没有找到任何${if (state.mode == "plugins") "插件" else "模组"}",
                                description = "点击右上角「上传」从设备选择文件",
                            )
                        }
                    } else if (data != null) {
                        val enabledFiles = if (filter == PmFilter.DISABLED || filter == PmFilter.CLIENT) emptyList() else data.jarFiles
                        val clientFiles = if (filter == PmFilter.DISABLED || filter == PmFilter.ENABLED) emptyList() else data.clientJarFiles
                        val disabledFiles = if (filter == PmFilter.ENABLED || filter == PmFilter.CLIENT) emptyList() else data.disableJarFiles
                        val visibleCount = enabledFiles.size + clientFiles.size + disabledFiles.size
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item {
                                StatsRow(data, filter, onFilter = { filter = it }, onLongPress = { showBatchDialog = true })
                            }

                            if (state.error != null) {
                                item(key = "error_banner") { ErrorBanner(state.error.orEmpty()) }
                            }

                            if (enabledFiles.isNotEmpty() || clientFiles.isNotEmpty()) {
                                item { GroupTitle("已启用", enabledFiles.size + clientFiles.size) }
                                items(enabledFiles, key = { "enabled_$it" }) { f ->
                                    PmRow(
                                        fileName = f,
                                        subtitle = "已启用",
                                        accent = false,
                                        onToggle = { viewModel.toggle(f, currentlyDisabled = false) },
                                        onDelete = { viewModel.delete(f) },
                                        toggleLabel = "禁用",
                                    )
                                }
                                items(clientFiles, key = { "client_$it" }) { f ->
                                    PmRow(
                                        fileName = f,
                                        subtitle = "仅客户端",
                                        accent = true,
                                        onToggle = null,
                                        onDelete = { viewModel.delete(f) },
                                        toggleLabel = null,
                                    )
                                }
                            }

                            if (disabledFiles.isNotEmpty()) {
                                item { GroupTitle("已禁用", disabledFiles.size) }
                                items(disabledFiles, key = { "disabled_$it" }) { f ->
                                    PmRow(
                                        fileName = f,
                                        subtitle = "已禁用",
                                        accent = false,
                                        onToggle = { viewModel.toggle(f, currentlyDisabled = true) },
                                        onDelete = { viewModel.delete(f) },
                                        toggleLabel = "启用",
                                    )
                                }
                            }

                            if (visibleCount == 0) {
                                item(key = "empty_filter") {
                                    MslxEmptyState(
                                        icon = Icons.Filled.Info,
                                        title = "当前筛选下没有文件",
                                        description = "点击上方统计卡片可切换筛选条件",
                                        actionLabel = "显示全部",
                                        onAction = { filter = PmFilter.ALL },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showBatchDialog) {
        AlertDialog(
            onDismissRequest = { showBatchDialog = false },
            title = { Text("批量操作") },
            text = { Text("对当前${if (state.mode == "plugins") "插件" else "模组"}列表执行批量操作。") },
            confirmButton = {
                TextButton(onClick = { viewModel.batch("enable"); showBatchDialog = false }) { Text("全部启用") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.batch("disable"); showBatchDialog = false }) { Text("全部禁用") }
                    TextButton(onClick = { showBatchDialog = false }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun StatsRow(data: com.wludy.rolithax.launcher.data.model.PmListData, selected: PmFilter, onFilter: (PmFilter) -> Unit, onLongPress: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatChip("总数", data.totalCount, selected == PmFilter.ALL, Modifier.weight(1f), { onFilter(PmFilter.ALL) }, onLongPress)
        StatChip("已启用", data.activeCount, selected == PmFilter.ENABLED, Modifier.weight(1f), { onFilter(PmFilter.ENABLED) }, onLongPress)
        StatChip("已禁用", data.disabledCount, selected == PmFilter.DISABLED, Modifier.weight(1f), { onFilter(PmFilter.DISABLED) }, onLongPress)
        StatChip("仅客户端", data.clientOnlyCount, selected == PmFilter.CLIENT, Modifier.weight(1f), { onFilter(PmFilter.CLIENT) }, onLongPress)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatChip(label: String, value: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit, onLongPress: () -> Unit) {
    // 长按批量操作：MslxCard 只接管单击，这里自绘玻璃底 + 组合手势，clip 保证水波纹不越出圆角
    MslxCard(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(GlassCardShape)
            .combinedClickable(
                role = Role.Button,
                onLongClickLabel = "批量操作",
                onClick = onClick,
                onLongClick = onLongPress,
            ),
        highlighted = selected,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "$value",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun GroupTitle(title: String, count: Int) {
    MslxSectionHeader(title = title, subtitle = "$count 个文件")
}

@Composable
private fun ErrorBanner(message: String) {
    MslxCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun PmRow(
    fileName: String,
    subtitle: String,
    accent: Boolean,
    onToggle: (() -> Unit)?,
    onDelete: () -> Unit,
    toggleLabel: String?,
) {
    MslxCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                // 结束侧留 4dp：IconButton 自带 12dp 内边距，图标视觉边距为 16dp
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (accent) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onToggle != null && toggleLabel != null) {
                OutlinedButton(onClick = onToggle) {
                    Text(toggleLabel)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
