package com.wludy.rolithax.launcher.ui.console

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.wludy.rolithax.launcher.data.schema.SchemaRepositoryCatalog
import com.wludy.rolithax.launcher.data.schema.SchemaRepositoryClient
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SchemaSourcePicker(onDismiss: () -> Unit, onSelected: (ConsolePluginImport) -> Unit) {
    val context = LocalContext.current
    val client = remember { SchemaRepositoryClient(File(context.filesDir, "schema")) }
    var sources by remember { mutableStateOf(client.loadSources()) }
    var selected by remember { mutableStateOf(sources.firstOrNull()) }
    var catalog by remember { mutableStateOf<SchemaRepositoryCatalog?>(null) }
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selected, refresh) {
        catalog = null
        val source = selected ?: return@LaunchedEffect
        busy = true
        error = null
        try {
            catalog = withContext(Dispatchers.IO) { client.fetch(source) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "无法读取插件源，请检查地址后重试" }
        finally { busy = false }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().fillMaxHeight(0.9f).imePadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Schema 插件源", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { refresh++ }, enabled = !busy && selected != null) {
                        Icon(Icons.Default.Refresh, "刷新插件源")
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭插件源") }
                }
                Text("添加 GitHub、GitLab 仓库，或自托管 README 地址。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(address, { address = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Git 仓库或 README 地址") }, singleLine = true, enabled = !busy)
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            val normalized = SchemaRepositoryClient.normalizeSource(address)
                            val next = (sources + normalized).distinct()
                            withContext(Dispatchers.IO) { client.saveSources(next) }
                            sources = next
                            address = ""
                            if (selected == normalized) refresh++ else selected = normalized
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) { error = failure.message ?: "无法添加插件源" }
                        finally { busy = false }
                    }
                }, enabled = address.isNotBlank() && !busy) { Text("添加插件源") }
            }
            if (sources.isNotEmpty()) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sources.forEach { source ->
                        FilterChip(selected = selected == source, enabled = !busy, onClick = { selected = source }, label = {
                            Text(if (source == SchemaRepositoryClient.DefaultSource) "官方插件源" else source.substringAfter("https://"),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
                        })
                    }
                }
                selected?.let { current ->
                    Text(current, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val next = sources - current
                                withContext(Dispatchers.IO) { client.saveSources(next) }
                                sources = next
                                selected = next.firstOrNull()
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { error = failure.message ?: "无法移除插件源" }
                            finally { busy = false }
                        }
                    }) { Text("移除此源") }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { refresh++ }, enabled = !busy && selected != null) { Text("重试") }
            } }
            catalog?.let { repository ->
                item {
                    Text(repository.name, style = MaterialTheme.typography.titleMedium)
                    if (repository.description.isNotBlank()) Text(repository.description, style = MaterialTheme.typography.bodyMedium)
                    Text("仓库版本 ${repository.revision.take(12)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top = 12.dp))
                }
                if (repository.plugins.isEmpty()) item { Text("此插件源还没有可安装的 Schema") }
                items(repository.plugins, key = { it.id }) { plugin ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                        Text("${plugin.version} · ${plugin.engine.uppercase()}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (plugin.description.isNotBlank()) Text(plugin.description, style = MaterialTheme.typography.bodyMedium)
                        if (plugin.compatibility.isNotBlank()) Text("声明兼容：${plugin.compatibility}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                try {
                                    val imported = withContext(Dispatchers.IO) { inspectConsolePlugin(client.install(repository, plugin)) }
                                    onSelected(imported)
                                    onDismiss()
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { error = failure.message ?: "无法安装 Schema 插件" }
                                finally { busy = false }
                            }
                        }) { Text("安装并选择权限") }
                        HorizontalDivider(Modifier.padding(top = 6.dp))
                    }
                }
            }
            if (sources.isEmpty()) item { Text("还没有插件源。填写仓库地址后添加。") }
        }
    }
}
