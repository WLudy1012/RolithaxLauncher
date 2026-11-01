package com.wludy.rolithax.launcher.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wludy.rolithax.launcher.shared.DaemonProfile

@Composable
internal fun ProfileDialog(
    profile: DaemonProfile,
    busy: Boolean,
    error: String?,
    onSave: (String, String, String, String, String) -> Unit,
    onClose: () -> Unit,
) {
    var username by remember(profile) { mutableStateOf(profile.username.orEmpty()) }
    var name by remember(profile) { mutableStateOf(profile.name.orEmpty()) }
    var avatar by remember(profile) { mutableStateOf(profile.avatar.orEmpty()) }
    var oldPassword by remember(profile) { mutableStateOf("") }
    var password by remember(profile) { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        title = { Text("当前用户") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("角色：${profile.role ?: "未知"}")
                OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text("用户名") }, singleLine = true, enabled = !busy)
                OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("显示名称") }, singleLine = true, enabled = !busy)
                OutlinedTextField(avatar, { avatar = it }, modifier = Modifier.fillMaxWidth(), label = { Text("头像地址") }, singleLine = true, enabled = !busy)
                OutlinedTextField(oldPassword, { oldPassword = it }, modifier = Modifier.fillMaxWidth(), label = { Text("当前密码（修改密码时填写）") }, singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("新密码（可选）") }, singleLine = true, enabled = !busy, visualTransformation = PasswordVisualTransformation())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { WindowsTextButton(onClick = { onSave(username, name, avatar, oldPassword, password) }, enabled = !busy) { Text("保存") } },
        dismissButton = { WindowsTextButton(onClick = onClose, enabled = !busy) { Text("取消") } },
    )
}
