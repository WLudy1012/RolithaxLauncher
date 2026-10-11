package com.wludy.rolithax.launcher.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wludy.rolithax.launcher.data.AppLogger

/** GitHub Issue 提交地址（崩溃日志报告入口）。 */
const val GITHUB_ISSUE_URL = "https://github.com/WLudy1012/RolithaxLauncher/issues/new"

/** 展示用日志上限：超长堆栈只截断显示，复制时仍是完整内容。 */
private const val MAX_DISPLAY_CHARS = 20_000

/**
 * 崩溃报告弹窗：应用启动时若检测到上次会话发生未捕获异常，
 * 展示崩溃日志 + GitHub Issue 地址，提示用户提交 issue。
 * 弹窗位于独立窗口（Haze 取不到主窗口背景），因此保持 Material 默认容器。
 */
@Composable
fun CrashReportDialog() {
    var crash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        crash = AppLogger.readLastCrash()
    }

    val content = crash ?: return
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val dismiss = {
        AppLogger.markCrashHandled()
        crash = null
    }

    // 超长堆栈不整体参与布局，避免首帧卡顿
    val displayed = remember(content) {
        if (content.length <= MAX_DISPLAY_CHARS) {
            content
        } else {
            content.take(MAX_DISPLAY_CHARS) + "\n…（日志过长，仅显示前 $MAX_DISPLAY_CHARS 字符）"
        }
    }
    val lineCount = remember(content) { content.count { it == '\n' } + 1 }

    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Text(
                text = "检测到上次运行崩溃",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = "应用上次运行时发生了异常。建议将以下信息提交到 GitHub Issue，帮助我们修复问题。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Issue 地址：$GITHUB_ISSUE_URL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "崩溃日志",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (content.length > MAX_DISPLAY_CHARS) "$lineCount 行（已截断显示）" else "$lineCount 行",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = displayed,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        // 固定上限 + 内部滚动：堆栈再长也不会把按钮挤出屏幕
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { openIssue(context) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("提交 Issue", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(content))
                    dismiss()
                },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("复制日志", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
    )
}

private fun openIssue(context: android.content.Context) {
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_ISSUE_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
