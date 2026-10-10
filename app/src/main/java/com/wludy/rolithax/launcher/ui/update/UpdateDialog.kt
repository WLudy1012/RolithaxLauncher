package com.wludy.rolithax.launcher.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wludy.rolithax.launcher.data.AppUpdateInfo
import com.wludy.rolithax.launcher.data.localengine.LocalJreManager

/**
 * 全局更新弹窗宿主：挂载在导航根节点外层。
 * - 启动自动检查：发现新版本即弹窗；
 * - 设置页手动检查：结果也通过同一状态弹窗/提示；
 * - 稳定版与测试版均在应用内下载并安装。
 */
@Composable
fun UpdateHost(
    viewModel: UpdateViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 启动时自动检查一次（仅首次）
    LaunchedEffect(Unit) {
        viewModel.checkOnLaunch()
    }

    val update = state.update
    if (update != null) {
        val context = LocalContext.current
        // 当前安装包是否自带内嵌 JRE（决定推荐完整版还是精简版）
        val currentHasEmbeddedJre = remember(context) {
            LocalJreManager.hasEmbeddedAsset(context, LocalJreManager.DEFAULT_RUNTIME)
        }
        UpdateDialog(
            currentVersion = state.currentVersion,
            update = update,
            currentHasEmbeddedJre = currentHasEmbeddedJre,
            downloading = state.downloading,
            downloadProgress = state.downloadProgress,
            onInstall = { viewModel.downloadAndInstall() },
            onInstallLite = { viewModel.downloadAndInstall(lite = true) },
            onSkip = { viewModel.skip() },
        )
    }
}

// 弹窗位于独立窗口，取不到主窗口背景，因此保持 Material 默认容器，不使用玻璃组件。
@Composable
private fun UpdateDialog(
    currentVersion: String,
    update: AppUpdateInfo,
    currentHasEmbeddedJre: Boolean,
    downloading: Boolean,
    downloadProgress: Float,
    onInstall: () -> Unit,
    onInstallLite: () -> Unit,
    onSkip: () -> Unit,
) {
    AlertDialog(
        // 强制更新时不可通过点击外部/返回键关闭，必须更新后才能继续使用
        onDismissRequest = { if (!update.forceUpdate && !downloading) onSkip() },
        icon = {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        title = {
            Text(
                text = when {
                    update.forceUpdate -> "必须更新到 v${update.version}"
                    update.beta -> "发现测试版 v${update.version}"
                    else -> "发现新版本 v${update.version}"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 长更新日志在弹窗内滚动：限制高度才能保证下方按钮始终留在屏幕内
                        .heightIn(max = 300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (update.forceUpdate) {
                        ForceUpdateBadge()
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "此版本必须更新，否则无法继续使用。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        text = if (currentVersion.isBlank()) "更新内容：" else "当前版本 v$currentVersion → 新版本 v${update.version}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = update.notes.ifBlank { "暂无说明" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (update.apkSize > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "APK 大小：${formatSize(update.apkSize)}" +
                                if (update.hasLiteVariant) "（完整版）" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // 带 / 不带内嵌 JRE 的版本提示（同一 Release 同时提供两个包）
                    Spacer(Modifier.height(6.dp))
                    if (update.embeddedJre) {
                        Text(
                            text = "✓ 完整版内嵌 JRE 运行时：本机开服可直接离线开服，无需下载运行时。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Text(
                            text = "⚠ 该包未内嵌 JRE 运行时：本机开服首次使用需联网下载约 36MB 运行时。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (!currentHasEmbeddedJre && update.hasLiteVariant) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "当前安装的是精简版：如需离线开服请选择完整版（体积约 ${formatSize(update.apkSize)}）；只用守护进程功能可选精简版（约 ${formatSize(update.liteSize)}）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (update.hasLiteVariant) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "同一版本提供两种包：完整版 ${formatSize(update.apkSize)}（含 JRE）/ 精简版 ${formatSize(update.liteSize)}（不含 JRE，本机开服需联网下载运行时）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 下载进度固定在滚动区之外，避免日志滚动时看不到进度
                if (downloading) {
                    val fraction = downloadProgress.coerceIn(0f, 1f)
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "下载中",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${(fraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            textAlign = TextAlign.End,
                            // 固定最小宽度：百分比位数变化时进度条长度不跳动
                            modifier = Modifier.widthIn(min = 44.dp),
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (update.hasLiteVariant && !downloading) {
                    TextButton(
                        onClick = onInstallLite,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = "精简版 ${formatSize(update.liteSize)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                TextButton(
                    onClick = onInstall,
                    enabled = !downloading,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        text = when {
                            downloading -> "下载中…"
                            update.forceUpdate -> "立即下载并安装"
                            update.hasLiteVariant -> "完整版 ${formatSize(update.apkSize)}"
                            else -> "下载并安装"
                        },
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        },
        dismissButton = {
            if (!update.forceUpdate && !downloading) {
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("跳过", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
    )
}

/** 强制更新标记：与“可跳过”形成一眼可辨的区别。 */
@Composable
private fun ForceUpdateBadge() {
    val error = MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(error.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = error,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "必须更新",
            style = MaterialTheme.typography.labelMedium,
            color = error,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
