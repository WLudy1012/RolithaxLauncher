package com.wludy.rolithax.launcher.ui.console

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.wludy.rolithax.launcher.RolithaxApplication
import com.wludy.rolithax.launcher.data.schema.*
import com.wludy.rolithax.launcher.ui.WorkspaceBar
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SchemaCenterScreen(onBack: () -> Unit) {
    val library = schemaLibrary()
    val state by library.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var input by rememberSaveable { mutableStateOf("") }
    var sources by remember { mutableStateOf(emptyList<String>()) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var catalog by remember { mutableStateOf<SchemaRepositoryCatalog?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var remove by remember { mutableStateOf<SchemaInstall?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun runOperation(block: suspend () -> Unit) {
        scope.launch {
            installing = true
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { snackbar.showSnackbar(error.message ?: "操作失败，请重试") }
            finally { installing = false }
        }
    }

    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !installing) runOperation {
            val plugin = library.import(uri)
            tab = 0
            snackbar.showSnackbar("已安装 ${plugin.title}")
        }
    }
    LaunchedEffect(library) {
        try {
            library.reload()
            sources = withContext(Dispatchers.IO) { library.repositories.loadSources() }
            if (selected !in sources) selected = sources.firstOrNull()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { snackbar.showSnackbar(error.message ?: "读取插件库失败") }
    }
    LaunchedEffect(tab, selected, refresh) {
        if (tab != 1) return@LaunchedEffect
        val url = selected ?: return@LaunchedEffect
        catalog = null
        refreshing = true
        try { catalog = withContext(Dispatchers.IO) { library.repositories.fetch(url) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { snackbar.showSnackbar(error.message ?: "无法读取插件源") }
        finally { refreshing = false }
    }

    Scaffold(topBar = {
        WorkspaceBar("Schema 插件中心", navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
        }) { TextButton(enabled = !installing, onClick = { import.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("导入 ZIP") } }
    }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(tab) {
                listOf("已安装", "浏览", "插件源").forEachIndexed { index, label ->
                    Tab(tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
            if (refreshing || installing) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (tab) {
                    0 -> {
                        if (state.installs.isEmpty()) item {
                            Text("还没有已安装的插件", style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { tab = 1 }) { Text("浏览插件源") }
                        }
                        items(state.installs, key = { it.digest + it.source }) { plugin ->
                            SchemaListItem(plugin.title, "${plugin.version} · ${plugin.engine.uppercase()}",
                                plugin.description, library.installedIcon(plugin)) {
                                TextButton(enabled = !installing, onClick = { remove = plugin }) { Text("卸载") }
                            }
                        }
                    }
                    1 -> {
                        item {
                            if (sources.isEmpty()) TextButton(onClick = { tab = 2 }) { Text("添加插件源") }
                            if (sources.isNotEmpty()) {
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { expanded = true }) {
                                        Text(catalog?.name ?: selected?.substringAfter("://") ?: "选择插件源",
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                        sources.forEach { url ->
                                            DropdownMenuItem(text = { Text(url.substringAfter("://")) },
                                                onClick = { selected = url; expanded = false })
                                        }
                                    }
                                }
                            }
                            OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(),
                                label = { Text("搜索插件") }, singleLine = true)
                            TextButton(enabled = selected != null && !refreshing, onClick = { refresh++ }) { Text("刷新目录") }
                        }
                        catalog?.let { repo ->
                            item {
                                SchemaListItem(repo.name, repo.authors.joinToString("、"), repo.description,
                                    rememberIcon(library, repo, repo.icon)) {}
                            }
                            val results = repo.plugins.filter { search.isBlank() ||
                                listOf(it.name, it.id, it.description).any { value -> value.contains(search, ignoreCase = true) } }
                            if (results.isEmpty()) item { Text("没有符合条件的插件") }
                            items(results, key = { it.id }) { plugin ->
                                val installed = state.installs.firstOrNull { it.id == plugin.id && it.source == repo.url }
                                val same = installed?.version == plugin.version
                                SchemaListItem(plugin.name,
                                    listOf(plugin.version, plugin.authors.joinToString("、"), plugin.license).filter(String::isNotBlank).joinToString(" · "),
                                    listOf(plugin.description, plugin.compatibility).filter(String::isNotBlank).joinToString("\n"),
                                    rememberIcon(library, repo, plugin.icon)) {
                                    FilledTonalButton(enabled = !installing && !same, onClick = {
                                        runOperation { library.install(repo, plugin); snackbar.showSnackbar("已安装 ${plugin.name}") }
                                    }) { Text(if (same) "已安装" else if (installed == null) "安装" else "更新") }
                                }
                            }
                        }
                    }
                    else -> {
                        item {
                            OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(),
                                label = { Text("Git 仓库或 HTTPS README 地址") }, singleLine = true)
                            TextButton(enabled = input.isNotBlank() && !installing, onClick = {
                                runOperation {
                                    val url = SchemaRepositoryClient.normalizeSource(input)
                                    val next = (sources + url).distinct()
                                    withContext(Dispatchers.IO) { library.repositories.saveSources(next) }
                                    sources = next
                                    selected = url
                                    input = ""
                                    tab = 1
                                }
                            }) { Text("添加插件源") }
                        }
                        items(sources, key = { it }) { url ->
                            SchemaListItem(
                                if (url == SchemaRepositoryClient.DefaultSource) "Rolithax 官方插件源" else url.substringAfter("://"),
                                url, "", null,
                            ) {
                                TextButton(enabled = !installing, onClick = {
                                    runOperation {
                                        val next = sources - url
                                        withContext(Dispatchers.IO) { library.repositories.saveSources(next) }
                                        sources = next
                                        if (selected == url) selected = next.firstOrNull()
                                    }
                                }) { Text("移除") }
                            }
                        }
                    }
                }
            }
        }
    }
    remove?.let { plugin ->
        AlertDialog(onDismissRequest = { remove = null }, title = { Text("卸载 ${plugin.title}") },
            text = { Text("关联实例将恢复内置面板。") },
            confirmButton = { TextButton(onClick = { remove = null; runOperation { library.uninstall(plugin) } }) { Text("卸载") } },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } })
    }
}

@Composable
internal fun schemaLibrary() = (LocalContext.current.applicationContext as RolithaxApplication).container.schemaLibrary

@Composable
private fun rememberIcon(library: SchemaLibrary, catalog: SchemaRepositoryCatalog, asset: SchemaAsset?): File? {
    val file by produceState<File?>(null, catalog.url, asset) {
        value = null
        if (asset != null) value = try { library.icon(catalog, asset) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }
    return file
}

@Composable
internal fun SchemaListItem(title: String, metadata: String, description: String, icon: File?,
                            actions: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        if (icon == null) Icon(Icons.Default.Settings, null, modifier = Modifier.size(22.dp))
                        else AsyncImage(icon, null, Modifier.fillMaxSize())
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
        }
    }
}
