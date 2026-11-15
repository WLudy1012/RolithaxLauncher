package com.wludy.rolithax.launcher.ui.servers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.wludy.rolithax.launcher.data.resources.CURSEFORGE_PROVIDER_ID
import kotlinx.coroutines.launch

@Composable
internal fun ResourceProviderSelector(
    providerId: String,
    curseForgeKeyConfigured: Boolean,
    onProviderChange: (String) -> Unit,
    onSaveCurseForgeKey: suspend (String) -> String?,
    onCredentialsChanged: () -> Unit,
) {
    var editingKey by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("资源来源", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = providerId == "modrinth",
                onClick = { onProviderChange("modrinth") },
                label = { Text("Modrinth") },
            )
            FilterChip(
                selected = providerId == CURSEFORGE_PROVIDER_ID,
                onClick = { onProviderChange(CURSEFORGE_PROVIDER_ID) },
                label = { Text("CurseForge") },
            )
        }
        if (providerId == CURSEFORGE_PROVIDER_ID) {
            Text(
                text = if (curseForgeKeyConfigured) "可安装到本机实例。模组是否支持服务端请查看项目说明。"
                else "填写官方 API Key 后可检索 CurseForge 模组。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { editingKey = true }) {
                Text(if (curseForgeKeyConfigured) "管理 API Key" else "配置 API Key")
            }
        }
    }

    if (editingKey) {
        CurseForgeKeyDialog(
            configured = curseForgeKeyConfigured,
            onSave = onSaveCurseForgeKey,
            onSaved = {
                editingKey = false
                onCredentialsChanged()
            },
            onDismiss = { editingKey = false },
        )
    }
}

@Composable
private fun CurseForgeKeyDialog(
    configured: Boolean,
    onSave: suspend (String) -> String?,
    onSaved: () -> Unit,
    onDismiss: () -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun save(value: String) {
        if (saving) return
        saving = true
        scope.launch {
            error = onSave(value)
            saving = false
            if (error == null) onSaved()
        }
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("CurseForge API Key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = key,
                    onValueChange = {
                        key = it
                        error = null
                    },
                    label = { Text(if (configured) "新的 API Key" else "API Key") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !saving,
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (configured) TextButton(onClick = { save("") }, enabled = !saving) { Text("移除已保存的 Key") }
            }
        },
        confirmButton = {
            TextButton(onClick = { save(key) }, enabled = key.isNotBlank() && !saving) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("取消") } },
    )
}
