package com.wludy.rolithax.launcher.ui.connect

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wludy.rolithax.launcher.data.model.PairCodeData
import com.wludy.rolithax.launcher.data.remote.PairingPayloadCodec
import kotlinx.coroutines.delay

@Composable
fun ConnectScreen(
    onConnected: () -> Unit,
    onBack: (() -> Unit)? = null,
    onAutoConnectFailed: () -> Unit = {},
    autoConnect: Boolean = true,
    editingDaemonId: String? = null,
    embedded: Boolean = false,
) {
    val viewModel: ConnectViewModel = viewModel(
        key = "connect_${autoConnect}_$editingDaemonId",
        factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                ConnectViewModel(app, autoConnect, editingDaemonId)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connected by rememberUpdatedState(onConnected)
    val autoConnectFailed by rememberUpdatedState(onAutoConnectFailed)
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        viewModel.onScanResult(result.contents)
    }
    var failMessage by remember { mutableStateOf<String?>(null) }
    var showHttpWarning by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.connected.collect { connected() } }
    LaunchedEffect(viewModel) { viewModel.autoConnectFailed.collect { failMessage = it } }

    ConnectContent(
        state = state,
        isEditing = !editingDaemonId.isNullOrBlank(),
        startsManual = autoConnect,
        embedded = embedded,
        onBack = onBack,
        onScan = {
            scanLauncher.launch(
                ScanOptions()
                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setCaptureActivity(PairingScanActivity::class.java)
                    .setPrompt("")
                    .setBeepEnabled(false)
                    .setOrientationLocked(true),
            )
        },
        onSubmitPairing = viewModel::submitPairingPayload,
        onNameChange = viewModel::onNameChange,
        onAddressChange = viewModel::onBaseUrlChange,
        onEndpointsChange = viewModel::onEndpointsChange,
        onApiKeyChange = viewModel::onApiKeyChange,
        onHttpChange = { checked ->
            if (checked) showHttpWarning = true else viewModel.onAllowHttpChange(false)
        },
        onConnect = viewModel::connect,
        onCreateCode = viewModel::createPairCode,
    )

    state.pairCode?.let { PairCodeDialog(it, viewModel::dismissPairCode) }
    state.pluginNotice?.let { message ->
        AlertDialog(onDismissRequest = viewModel::finishConnection,
            title = { Text("MSLX 扩展检查") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::finishConnection) { Text("完成") } })
    }

    failMessage?.let { message ->
        val dismiss = { failMessage = null; autoConnectFailed() }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("连接失败") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = dismiss) { Text("确定") } },
        )
    }

    if (showHttpWarning || state.confirmHttpPairing) {
        HttpConfirmation(
            pairing = state.confirmHttpPairing,
            onCancel = {
                showHttpWarning = false
                if (state.confirmHttpPairing) viewModel.cancelHttpPairing()
            },
            onConfirm = {
                showHttpWarning = false
                if (state.confirmHttpPairing) viewModel.confirmHttpPairing()
                else viewModel.onAllowHttpChange(true)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectContent(
    state: ConnectUiState,
    isEditing: Boolean,
    startsManual: Boolean,
    embedded: Boolean,
    onBack: (() -> Unit)?,
    onScan: () -> Unit,
    onSubmitPairing: (String) -> String?,
    onNameChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onEndpointsChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onHttpChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onCreateCode: () -> Unit,
) {
    var manual by rememberSaveable(isEditing, startsManual) { mutableStateOf(isEditing || startsManual) }
    val busy = state.initializing || (isEditing && state.editingId == null) ||
        state.loading || state.pairing || state.confirmHttpPairing || state.generating
    val focus = LocalFocusManager.current
    val connect = { focus.clearFocus(); onConnect() }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (!embedded) TopAppBar(
                title = { Text(if (isEditing) "编辑 Daemon" else "添加 MSLX Daemon") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!isEditing) {
                    TabRow(selectedTabIndex = if (manual) 1 else 0, containerColor = Color.Transparent) {
                        Tab(
                            selected = !manual,
                            enabled = !busy,
                            onClick = { focus.clearFocus(); manual = false },
                            text = { Text("插件配对") },
                        )
                        Tab(
                            selected = manual,
                            enabled = !busy,
                            onClick = { focus.clearFocus(); manual = true },
                            text = { Text("手动连接") },
                        )
                    }
                }

                if (state.initializing) ConnectProgress("正在读取已保存的连接…")
                state.error?.let { ConnectError(it) }

                if (!manual) {
                    PairingForm(
                        busy = busy,
                        pairing = state.pairing,
                        loading = state.loading,
                        pairingReady = state.pairingReady,
                        onScan = onScan,
                        onSubmit = onSubmitPairing,
                        onConnect = connect,
                    )
                } else {
                    ManualConnectionForm(
                        state = state,
                        busy = busy,
                        isEditing = isEditing,
                        onNameChange = onNameChange,
                        onAddressChange = onAddressChange,
                        onEndpointsChange = onEndpointsChange,
                        onApiKeyChange = onApiKeyChange,
                        onHttpChange = onHttpChange,
                        onConnect = connect,
                        onCreateCode = onCreateCode,
                    )
                }
            }
        }
    }
}

@Composable
private fun PairingForm(
    busy: Boolean,
    pairing: Boolean,
    loading: Boolean,
    pairingReady: Boolean,
    onScan: () -> Unit,
    onSubmit: (String) -> String?,
    onConnect: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val focus = LocalFocusManager.current
    var payload by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val submit = {
        error = onSubmit(payload)
        if (error == null) focus.clearFocus()
    }
    LaunchedEffect(pairingReady) {
        if (pairingReady) {
            payload = ""
            error = null
        }
    }

    if (pairingReady && !loading) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("凭据已兑换", style = MaterialTheme.typography.titleMedium)
                Text("连接到该 Daemon 并保存配置。", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = onConnect, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("连接")
                }
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("从 Rolithax 插件配对", style = MaterialTheme.typography.titleLarge)
            Text(
                "打开 MSLX 面板的 Rolithax 插件，生成配对二维码。可直接扫码，或点击「复制原文」后在这里粘贴。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            FilledTonalButton(
                onClick = {
                    error = null
                    focus.clearFocus()
                    onScan()
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("扫码配对")
            }
            OutlinedTextField(
                value = payload,
                onValueChange = {
                    payload = it
                    error = null
                },
                label = { Text("配对原文") },
                placeholder = { Text("mslxp1:…") },
                supportingText = if (error != null) ({ Text(error.orEmpty()) }) else null,
                isError = error != null,
                enabled = !busy,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (payload.isNotBlank() && !busy) submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    val text = clipboard.getText()?.text?.trim().orEmpty()
                    if (text.isEmpty()) error = "剪贴板中没有可用的文本。"
                    else {
                        payload = text
                        error = null
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("从剪贴板粘贴")
            }
            Button(
                onClick = submit,
                enabled = !busy && payload.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                when {
                    pairing -> ConnectProgress("正在兑换配对凭据…")
                    loading -> ConnectProgress("正在连接 Daemon…")
                    else -> Text("使用原文配对")
                }
            }
        }
    }
}

@Composable
private fun ManualConnectionForm(
    state: ConnectUiState,
    busy: Boolean,
    isEditing: Boolean,
    onNameChange: (String) -> Unit,
    onAddressChange: (String) -> Unit,
    onEndpointsChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onHttpChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onCreateCode: () -> Unit,
) {
    var showKey by remember { mutableStateOf(false) }
    var advancedOverride by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val advanced = advancedOverride ?: (state.endpointsText.isNotBlank() || state.allowHttp)
    val canConnect = !busy && state.baseUrl.isNotBlank() && state.apiKey.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "使用手机可访问的 Daemon 地址，支持域名和自定义端口。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = state.baseUrl,
            onValueChange = onAddressChange,
            label = { Text("Daemon 地址") },
            placeholder = { Text("https://example.com:8443") },
            enabled = !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.apiKey,
            onValueChange = onApiKeyChange,
            label = { Text("API Key") },
            enabled = !busy,
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canConnect) onConnect() }),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }, enabled = !busy) {
                    Text(if (showKey) "隐藏" else "显示")
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.name,
            onValueChange = onNameChange,
            label = { Text("名称（可选）") },
            enabled = !busy,
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canConnect) onConnect() }),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { advancedOverride = !advanced }, enabled = !busy) {
            Text(if (advanced) "收起高级选项" else "高级选项")
        }
        if (advanced) {
            OutlinedTextField(
                value = state.endpointsText,
                onValueChange = onEndpointsChange,
                label = { Text("备用地址（每行一个）") },
                enabled = !busy,
                minLines = 2,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = state.allowHttp,
                        enabled = !busy,
                        role = Role.Checkbox,
                        onValueChange = onHttpChange,
                    )
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = state.allowHttp, onCheckedChange = null, enabled = !busy)
                Text("允许 HTTP 明文连接", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(onClick = onConnect, enabled = canConnect, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            when {
                state.autoChecking -> ConnectProgress("正在自动连接…")
                state.loading -> ConnectProgress("正在验证连接…")
                isEditing -> Text("验证并保存")
                else -> Text("连接")
            }
        }
        if (isEditing) {
            OutlinedButton(
                onClick = onCreateCode,
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (state.generating) ConnectProgress("正在生成配对二维码…")
                else Text("生成配对二维码")
            }
        }
    }
}

@Composable
private fun ConnectProgress(message: String) {
    Row(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(message)
    }
}

@Composable
private fun ConnectError(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HttpConfirmation(pairing: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit) {
    var countdown by remember(pairing) { mutableIntStateOf(5) }
    LaunchedEffect(pairing) {
        while (countdown > 0) {
            delay(1000)
            countdown--
        }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text(if (pairing) "确认通过 HTTP 配对" else "确认允许 HTTP 连接") },
        text = {
            Text(
                "HTTP 会明文发送配对凭据、API Key 和管理命令，网络中的其他设备可能读取或篡改这些内容。仅在可信内网继续。\n\n" +
                    "客户端兼容 MSLX 自签证书，连接 Daemon 时未验证服务端证书。请确认目标地址来自你信任的设备。",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = countdown == 0) {
                Text(if (countdown > 0) "请等待 $countdown 秒" else "仍然继续")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

@Composable
private fun PairCodeDialog(data: PairCodeData, onDismiss: () -> Unit) {
    val clipboard = LocalContext.current.getSystemService(ClipboardManager::class.java)
    var copied by remember(data.payload) { mutableStateOf(false) }
    val expiresAt = rememberSaveable(data.payload) {
        PairingPayloadCodec.decode(data.payload)?.expiresAt?.takeIf { it > 0 }
            ?: (System.currentTimeMillis() / 1000L + data.expiresInSeconds.coerceAtLeast(0))
    }
    var now by remember(expiresAt) { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(expiresAt) {
        while (now < expiresAt) {
            delay(1000)
            now = System.currentTimeMillis() / 1000L
        }
    }
    val remaining = (expiresAt - now).coerceAtLeast(0L)
    val qr = remember(data.payload) { renderQrCodeBitmap(data.payload)?.asImageBitmap() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配对二维码") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = "配对二维码",
                        modifier = Modifier
                            .sizeIn(maxWidth = 224.dp, maxHeight = 224.dp)
                            .background(Color.White, MaterialTheme.shapes.medium)
                            .padding(12.dp),
                    )
                }
                Text(
                    if (remaining > 0) "剩余 $remaining 秒，仅可使用一次" else "配对二维码已过期，请重新生成",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (remaining > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Text(
                    if (qr != null) "在另一台设备扫描二维码，或复制原文后使用插件配对。"
                    else "二维码未能生成，可复制原文在另一台设备配对。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        },
        dismissButton = {
            TextButton(
                enabled = remaining > 0 && clipboard != null,
                onClick = {
                    if (System.currentTimeMillis() / 1000L >= expiresAt) return@TextButton
                    val clip = ClipData.newPlainText("配对原文", data.payload)
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                    clipboard?.setPrimaryClip(clip)
                    copied = true
                },
            ) {
                Text(if (copied) "已复制" else "复制原文")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
private fun renderQrCodeBitmap(content: String, sizePx: Int = 720): android.graphics.Bitmap? = runCatching {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(
        content,
        com.google.zxing.BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(com.google.zxing.EncodeHintType.MARGIN to 1),
    )
    val pixels = IntArray(sizePx * sizePx) { index ->
        val x = index % sizePx
        val y = index / sizePx
        if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    android.graphics.Bitmap.createBitmap(pixels, sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
}.getOrNull()
