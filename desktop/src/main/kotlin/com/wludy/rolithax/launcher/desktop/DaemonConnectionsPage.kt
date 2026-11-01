package com.wludy.rolithax.launcher.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.net.URI

@Composable
internal fun DaemonConnectionsPage(
    state: DesktopState,
    onSelect: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onConnectSaved: (SavedDaemon) -> Unit,
    onRemoveSaved: (SavedDaemon) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = !state.busy && !state.transferring
    LazyColumn(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Daemon 连接", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("管理当前会话与保存在本机的远端服务器。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    WindowsButton(onClick = onAdd, enabled = enabled) { Text("添加连接") }
                }
            }
        }
        item { ConnectionSectionTitle("当前会话", state.connections.size) }
        if (state.connections.isEmpty()) item { ConnectionEmpty("暂无已连接的服务器。") }
        items(state.connections, key = { "session-${it.id}" }) { connection ->
            ConnectionCard {
                WorkspaceConnection("${connection.name}（会话 #${connection.id}）", connection.id == state.selectedId, true, enabled,
                    onConnect = { onSelect(connection.id) }, onRemove = { onRemove(connection.id) })
                Text("会话 #${connection.id}" + (connection.displayAddress?.let { " · $it" } ?: ""), Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item { ConnectionSectionTitle("已保存", state.savedConnections.size) }
        if (state.savedConnections.isEmpty()) item { ConnectionEmpty("尚无保存的连接。") }
        items(state.savedConnections, key = { "saved-${it.id}" }) { saved ->
            ConnectionCard {
                WorkspaceConnection(saved.name, false, false, enabled,
                    onConnect = { onConnectSaved(saved) }, onRemove = { onRemoveSaved(saved) })
                Text(displayDaemonAddress(saved.address), Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ConnectionSectionTitle(title: String, count: Int) {
    Text("$title · $count", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ConnectionEmpty(message: String) {
    Text(message, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConnectionCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(4.dp), content = content)
    }
}

internal fun displayDaemonAddress(address: String): String = try {
    val uri = URI(if ("://" in address) address else "https://$address")
    val host = uri.host
    if (host == null) "远端地址" else "${uri.scheme}://$host${if (uri.port >= 0) ":${uri.port}" else ""}"
} catch (_: Exception) { "远端地址" }
