package com.wludy.rolithax.launcher.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wludy.rolithax.launcher.ui.RolithaxCard
import com.wludy.rolithax.launcher.ui.RolithaxSectionHeader
import com.wludy.rolithax.launcher.ui.StatusBadge
import com.wludy.rolithax.launcher.ui.theme.ConsoleBackground
import com.wludy.rolithax.launcher.ui.theme.ConsoleFont
import com.wludy.rolithax.launcher.ui.theme.ConsoleSystem
import com.wludy.rolithax.launcher.ui.theme.ConsoleText
import com.wludy.rolithax.launcher.ui.theme.ConsoleTextStyle
import com.wludy.rolithax.launcher.ui.theme.GlassCardShape
import com.wludy.rolithax.launcher.ui.theme.GlassSurface
import com.wludy.rolithax.launcher.ui.theme.LocalRolithaxDarkTheme
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

// 页面级间距约定：水平边距 16dp、卡片间距 12dp、卡片内边距 16dp
private val PageHorizontalPadding = 16.dp
private val CardSpacing = 12.dp
private val CardInnerPadding = 16.dp

// 终端卡片刻意不套玻璃：高对比不透明深色底才能保证 ANSI 日志可读，且不干扰背景图
private val TerminalHeaderDividerColor = Color(0xFF2A2E35)

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ConsoleScreen(
    controller: ConsoleController,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val viewModel = controller

    val state by viewModel.state.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var autoScroll by rememberSaveable { mutableStateOf(true) }
    var command by rememberSaveable { mutableStateOf("") }
    var confirmAction by remember { mutableStateOf<String?>(null) }
    var showEulaDialog by remember { mutableStateOf(false) }

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= info.totalItemsCount - 1
        }
    }

    // 日志高频输出时合并滚动请求，避免每个微批都触发一次布局滚动。
    LaunchedEffect(autoScroll) {
        snapshotFlow { logs.size }
            .filter { autoScroll && it > 0 }
            .sample(100)
            .collect { size -> listState.scrollToItem(size - 1) }
    }

    // 视口高度变化（点击输入框弹出 IME / 收起键盘/旋转）后，日志区需要重新贴底，
    // 否则键盘会遮住最新日志（仅靠 logs.size 变化无法触发，因为此次没有新日志）
    val latestLogCount by rememberUpdatedState(logs.size)
    LaunchedEffect(listState, autoScroll) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .filter { it > 0 }
            .collect {
                if (autoScroll && latestLogCount > 0) {
                    listState.scrollToItem(latestLogCount - 1)
                }
            }
    }

    // 处理一次性事件(提示 / EULA 弹窗)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ConsoleEvent.Toast -> snackbarHostState.showSnackbar(event.message)
                ConsoleEvent.EulaRequired -> showEulaDialog = true
            }
        }
    }

    Scaffold(
        // 页面透明：透出全局毛玻璃背景层（日志区自绘深色终端卡片）
        containerColor = Color.Transparent,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.instanceName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "实例设置")
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
                .padding(horizontal = PageHorizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(CardSpacing),
        ) {
            StatusOverviewCard(state = state, onRetryConnect = viewModel::retryConnect)

            ActionCard(
                status = state.status,
                busy = state.busy,
                onAction = { action ->
                    if (action in CONFIRM_REQUIRED_ACTIONS) {
                        confirmAction = action
                    } else {
                        viewModel.sendAction(action)
                    }
                },
            )

            // 终端卡片：自绘不透明深色底，不使用玻璃组件
            TerminalCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                logs = logs,
                listState = listState,
                autoScroll = autoScroll,
                atBottom = atBottom,
                onToggleAutoScroll = { checked ->
                    autoScroll = checked
                    if (checked && logs.isNotEmpty()) {
                        scope.launch { listState.scrollToItem(logs.size - 1) }
                    }
                },
                onClearLogs = viewModel::clearLogs,
                onJumpToLatest = {
                    autoScroll = true
                    if (logs.isNotEmpty()) {
                        scope.launch { listState.scrollToItem(logs.size - 1) }
                    }
                },
            )

            // 命令输入栏：键盘回车与发送按钮行为一致
            CommandInputBar(
                value = command,
                onValueChange = { command = it },
                connected = state.connected,
                onSend = {
                    if (command.isNotBlank() && state.connected) {
                        viewModel.sendCommand(command)
                        command = ""
                        focusManager.clearFocus()
                    }
                },
            )
        }
    }

    // 危险操作确认弹窗（弹窗内部保留 Material 默认容器）
    val pendingAction = confirmAction
    if (pendingAction != null) {
        val (title, message) = when (pendingAction) {
            "stop" -> "停止实例" to "确定要停止该实例吗？"
            "restart" -> "重启实例" to "确定要重启该实例吗？"
            "forceExit" -> "强制结束" to "强制结束会立即终止服务器进程，可能导致数据丢失。确定继续吗？"
            else -> "确认操作" to "确定执行该操作吗？"
        }
        AlertDialog(
            onDismissRequest = { confirmAction = null },
            title = { Text(title) },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.sendAction(pendingAction)
                        confirmAction = null
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { confirmAction = null }) { Text("取消") }
            },
        )
    }

    // EULA 未签署弹窗（弹窗内部保留 Material 默认容器）
    if (showEulaDialog) {
        AlertDialog(
            onDismissRequest = { showEulaDialog = false },
            title = { Text("需要签署 EULA 协议") },
            text = { Text("检测到该实例尚未同意 Minecraft EULA 协议，服务器无法启动。是否同意并启动？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEulaDialog = false
                        viewModel.agreeEulaAndStart()
                    },
                ) { Text("同意并启动") }
            },
            dismissButton = {
                TextButton(onClick = { showEulaDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 状态总览：状态徽章 + 在线人数 / 运行时长 + 连接态（含失败重连入口）。 */
@Composable
private fun StatusOverviewCard(state: ConsoleUiState, onRetryConnect: () -> Unit) {
    RolithaxCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "运行状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                StatusBadge(status = state.status, statusText = state.statusText)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MetaText("在线 ${state.onlinePlayers} 人")
                state.uptime?.takeIf { it.isNotBlank() }?.let { uptime ->
                    MetaText("运行 $uptime")
                }
            }
            ConnectionStatus(
                connecting = state.connecting,
                connected = state.connected,
                error = state.connectionError,
                onRetry = onRetryConnect,
            )
        }
    }
}

/** 连接三态：正在连接 / 连接失败（可重连）/ 断线自动重连中。 */
@Composable
private fun ConnectionStatus(
    connecting: Boolean,
    connected: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    when {
        connecting -> ConnectionLine(
            text = "正在连接控制台…",
            color = scheme.onSurfaceVariant,
            showSpinner = true,
        )

        error != null -> Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "控制台连接失败：$error",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text("重连") }
        }

        // 静态圆点而非第二个转圈：避免加载指示器不确定语义，也区分"正在尝试"与"等待重试"
        !connected -> ConnectionLine(
            text = "连接已断开，正在自动重连…",
            color = scheme.onSurfaceVariant,
            showSpinner = false,
        )
    }
}

@Composable
private fun ConnectionLine(text: String, color: Color, showSpinner: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 等宽次要信息（人数 / 时长），与页面正文区分。 */
@Composable
private fun MetaText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = ConsoleFont,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 操作按钮组：横向滚动，按钮满足 48dp 最小触控高度。 */
@Composable
private fun ActionCard(
    status: Int,
    busy: Boolean,
    onAction: (String) -> Unit,
) {
    val transitioning = status in listOf(1, 3, 4)
    val isRunning = status == 2
    val isStopped = status == 0

    RolithaxCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(CardInnerPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RolithaxSectionHeader(title = "实例操作")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ActionButton(
                    text = if (transitioning) "操作中…" else "启动",
                    enabled = isStopped && !busy,
                    color = MaterialTheme.colorScheme.primary,
                    onClick = { onAction("start") },
                )
                ActionButton(
                    text = "停止",
                    enabled = isRunning && !busy,
                    color = actionColor(ActionTone.Warning),
                    onClick = { onAction("stop") },
                )
                ActionButton(
                    text = "重启",
                    enabled = isRunning && !busy,
                    color = actionColor(ActionTone.Info),
                    onClick = { onAction("restart") },
                )
                ActionButton(
                    text = "强制结束",
                    enabled = isRunning && !busy,
                    color = MaterialTheme.colorScheme.error,
                    onClick = { onAction("forceExit") },
                )
                ActionButton(
                    text = "备份",
                    enabled = isRunning && !busy,
                    color = actionColor(ActionTone.Neutral),
                    onClick = { onAction("backup") },
                )
            }
        }
    }
}

/** 日志终端：不透明深色底 + 等宽字体，头部提供自动滚动与清空。 */
@Composable
private fun TerminalCard(
    logs: List<LogLine>,
    listState: LazyListState,
    autoScroll: Boolean,
    atBottom: Boolean,
    onToggleAutoScroll: (Boolean) -> Unit,
    onClearLogs: () -> Unit,
    onJumpToLatest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(GlassCardShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, GlassCardShape)
            .background(ConsoleBackground),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "控制台",
                style = MaterialTheme.typography.labelMedium,
                fontFamily = ConsoleFont,
                color = ConsoleSystem,
            )
            if (logs.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.08f))
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = logs.size.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = ConsoleFont,
                        color = ConsoleSystem,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = "自动滚动",
                style = MaterialTheme.typography.labelSmall,
                color = ConsoleSystem,
            )
            Switch(
                checked = autoScroll,
                onCheckedChange = onToggleAutoScroll,
                colors = SwitchDefaults.colors(
                    uncheckedThumbColor = ConsoleSystem,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.12f),
                    uncheckedBorderColor = Color.White.copy(alpha = 0.24f),
                ),
            )
            IconButton(
                onClick = onClearLogs,
                enabled = logs.isNotEmpty(),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = ConsoleSystem,
                    disabledContentColor = ConsoleSystem.copy(alpha = 0.38f),
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "清空日志",
                )
            }
        }
        HorizontalDivider(color = TerminalHeaderDividerColor)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (logs.isEmpty()) {
                Text(
                    text = "暂无日志输出",
                    style = ConsoleTextStyle,
                    color = ConsoleSystem,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(16.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    // key = 日志行稳定 id：列表裁剪/追加时保持条目身份，避免整表重组
                    items(items = logs, key = { it.id }) { line ->
                        // ANSI 原彩：按转义序列拆段着色，未着色段继承默认前景色
                        val segments = line.segments ?: remember(line.text) { parseAnsiLog(line.text) }
                        val baseColor = if (line.system) ConsoleSystem else ConsoleText
                        Text(
                            text = buildAnnotatedString {
                                segments.forEach { seg ->
                                    withStyle(
                                        SpanStyle(
                                            color = seg.color ?: baseColor,
                                            fontWeight = seg.ansiFontWeight,
                                            background = seg.background ?: Color.Unspecified,
                                        ),
                                    ) { append(seg.text) }
                                }
                            },
                            style = ConsoleTextStyle,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // 未在底部时显示"回到最新"
            if (!atBottom && logs.isNotEmpty()) {
                Button(
                    onClick = onJumpToLatest,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("回到最新", maxLines = 1)
                }
            }
        }
    }
}

/** 命令输入栏：玻璃容器 + 等宽输入框 + 发送按钮（未连接或无内容时禁用）。 */
@Composable
private fun CommandInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    connected: Boolean,
    onSend: () -> Unit,
) {
    val canSend = connected && value.isNotBlank()
    GlassSurface(
        modifier = Modifier
            .fillMaxWidth()
            // adjustResize 已由 Activity 处理键盘避让，这里只补系统导航栏
            .navigationBarsPadding()
            .padding(bottom = 4.dp),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("输入命令…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = ConsoleFont),
                singleLine = true,
                enabled = connected,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                    disabledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = canSend,
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送命令",
                )
            }
        }
    }
}

// 需要二次确认的危险操作：先弹确认框，再交给控制器执行
private val CONFIRM_REQUIRED_ACTIONS = setOf("stop", "restart", "forceExit")

private enum class ActionTone { Warning, Info, Neutral }

/** 操作色按深浅色分别取色，避免固定色在浅色/深色下对比度不足。 */
@Composable
private fun actionColor(tone: ActionTone): Color {
    val dark = LocalRolithaxDarkTheme.current
    return when (tone) {
        ActionTone.Warning -> if (dark) Color(0xFFFFC46B) else Color(0xFFB25E00)
        ActionTone.Info -> if (dark) Color(0xFF9EC5FE) else Color(0xFF1558B0)
        ActionTone.Neutral -> if (dark) Color(0xFFC3CBD8) else Color(0xFF4A5568)
    }
}

/** tonal 风格操作按钮：容器取同色低透明度，内容用同色保证对比度；高度满足 48dp 触控目标。 */
@Composable
private fun ActionButton(
    text: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = color.copy(alpha = 0.16f),
            contentColor = color,
            disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(
            text = text,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
