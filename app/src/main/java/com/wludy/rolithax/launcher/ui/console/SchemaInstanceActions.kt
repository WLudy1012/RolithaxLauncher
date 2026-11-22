package com.wludy.rolithax.launcher.ui.console

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.wludy.rolithax.launcher.RolithaxApplication
import com.wludy.rolithax.launcher.data.AppContainer
import com.wludy.rolithax.launcher.data.InstanceRepository
import com.wludy.rolithax.launcher.data.ServerRef
import com.wludy.rolithax.launcher.data.ensureRepository
import com.wludy.rolithax.launcher.shared.*
import com.wludy.rolithax.launcher.shared.console.SchemaAction
import com.wludy.rolithax.launcher.shared.console.SchemaProbe
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal data class SchemaCommandProbe(val id: String, val title: String, val description: String)
internal data class SchemaCheck(
    val probe: SchemaProbe,
    val version: String?,
    val status: String,
    val command: SchemaCommandProbe? = null,
)

internal suspend fun AppContainer.checkSchema(
    ref: ServerRef,
    probes: List<SchemaProbe>,
    confirmedCommand: String? = null,
): List<SchemaCheck> {
    if (probes.isEmpty()) return emptyList()
    val instance = ref.remoteIdOrNull
    val daemon = if (instance == null) null else ensureRepository(ref.daemonId)?.consoleConnection?.daemon
    return probes.map { probe ->
        val result = if (daemon == null || instance == null) null else try {
            daemon.schemaPreflight(instance, probe.kind, probe.id, confirmedCommand) as? JsonObject
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (confirmedCommand != null) throw error
            null
        }
        val version = result?.get("version")?.jsonPrimitive?.contentOrNull
        val status = when {
            result?.get("status")?.jsonPrimitive?.content == "missing" -> "incompatible"
            version == null -> "unknown"
            version == probe.version -> "compatible"
            else -> "incompatible"
        }
        val command = (result?.get("commandProbe") as? JsonObject)?.let { details ->
            val id = details["id"]?.jsonPrimitive?.contentOrNull
            val title = details["title"]?.jsonPrimitive?.contentOrNull
            if (id == null || title.isNullOrBlank()) null
            else SchemaCommandProbe(id, title, details["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
        SchemaCheck(probe, version, status, command)
    }
}

internal fun List<SchemaCheck>.payload() = buildJsonObject {
    put("checks", JsonArray(map { check ->
        buildJsonObject {
            put("kind", check.probe.kind)
            put("id", check.probe.id)
            put("expected", check.probe.version)
            check.version?.let { put("version", it) }
            put("status", check.status)
        }
    }))
}

internal fun SchemaAction.label(): String = if (kind == "host-task") "宿主机任务 · ${taskId.orEmpty()}"
else "${fileOperationLabel(operation)} · ${root.orEmpty()}${path?.let { "/$it" }.orEmpty()}"

private fun fileOperationLabel(operation: String?): String = when (operation) {
    "list" -> "列出目录"
    "read" -> "读取文件"
    "write" -> "编辑文件"
    "mkdir" -> "创建目录"
    "rename" -> "重命名"
    "delete" -> "删除文件"
    "upload" -> "上传文件"
    "download" -> "下载文件"
    else -> "文件操作"
}

private fun validSchemaLeaf(value: String): Boolean = value.length in 1..128 && value == value.trim() &&
    !value.endsWith('.') && value.none { it.isISOControl() || it in "/\\:<>\"|?*%" }

private fun validSchemaPath(value: String, allowEmpty: Boolean): Boolean =
    if (value.isEmpty()) allowEmpty else value.length <= 512 && value.split('/').all(::validSchemaLeaf)

@Composable
internal fun SchemaInstanceActionDialog(
    ref: ServerRef,
    title: String,
    action: SchemaAction,
    stillGranted: () -> Boolean,
    onResult: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as RolithaxApplication).container
    val scope = rememberCoroutineScope()
    var repository by remember { mutableStateOf<InstanceRepository?>(null) }
    var roots by remember { mutableStateOf(emptyList<String>()) }
    var root by remember { mutableStateOf("") }
    var path by remember { mutableStateOf(action.path.orEmpty()) }
    var extra by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var loadedPath by remember { mutableStateOf<String?>(null) }
    var originalContent by remember { mutableStateOf("") }
    var upload by remember { mutableStateOf<Uri?>(null) }
    var detail by remember { mutableStateOf<String?>(null) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(true) }
    val id = ref.remoteIdOrNull
    val operation = action.operation
    val hostTask = action.kind == "host-task"

    fun target(allowEmpty: Boolean = false): String {
        require(stillGranted()) { "实例或插件已切换，请重新确认" }
        require(validSchemaPath(path.trim(), allowEmpty)) { "文件路径必须相对授权目录" }
        return listOf(root, path.trim()).filter(String::isNotEmpty).joinToString("/")
    }

    fun execute(block: suspend () -> String) {
        scope.launch {
            busy = true
            feedback = null
            try {
                require(stillGranted()) { "实例或插件已切换，请重新确认" }
                detail = block()
                onResult("completed", "操作已完成")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                feedback = error.message ?: "操作失败"
                onResult("failed", "操作失败")
            } finally { busy = false }
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> upload = uri }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) execute {
            val file = target()
            val daemon = requireNotNull(repository?.consoleConnection?.daemon)
            withContext(Dispatchers.IO) {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
                    daemon.downloadFile(requireNotNull(id), file) { channel, _ ->
                        val buffer = ByteArray(65536)
                        while (true) {
                            val n = channel.readAvailable(buffer, 0, buffer.size)
                            if (n < 0) break
                            if (n > 0) output.write(buffer, 0, n)
                        }
                    }
                }
            }
            "已保存到所选位置"
        }
    }
    LaunchedEffect(ref, action) {
        try {
            val instance = requireNotNull(id) { "此操作仅适用于 MSLX Daemon 实例" }
            val active = requireNotNull(container.ensureRepository(ref.daemonId)) { "Daemon 未连接" }
            val daemon = requireNotNull(active.consoleConnection?.daemon) { "Daemon 未连接" }
            if (hostTask) {
                val tasks = daemon.schemaTasks(instance) as? JsonArray
                val task = tasks?.mapNotNull { it as? JsonObject }?.firstOrNull {
                    it["id"]?.jsonPrimitive?.content == action.taskId
                }
                requireNotNull(task) { "此任务未登记、未启用或当前账号无权调用" }
                detail = listOfNotNull(task["title"]?.jsonPrimitive?.content, task["description"]?.jsonPrimitive?.content)
                    .joinToString("\n")
            } else if (action.root == "instance") {
                roots = listOf("")
            } else {
                val layout = daemon.schemaLayout(instance) as? JsonObject
                    ?: error("Daemon 未提供 MCDR 目录信息")
                roots = when (action.root) {
                    "minecraft" -> listOfNotNull(layout["gameDirectory"]?.jsonPrimitive?.contentOrNull)
                    "mcdr-config" -> if (layout["kind"]?.jsonPrimitive?.content == "mcdr") listOf("config") else emptyList()
                    "mcdr-plugins" -> (layout["pluginDirectories"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                    else -> emptyList()
                }.filter { validSchemaPath(it, allowEmpty = action.root == "minecraft") }
                require(roots.isNotEmpty()) { "找不到已授权的目录" }
                root = roots.first()
            }
            repository = active
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { feedback = error.message ?: "操作不可用" }
        finally { busy = false }
    }

    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (hostTask) "运行宿主机任务" else fileOperationLabel(operation)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("目标实例：$title")
                if (hostTask) Text(action.taskId.orEmpty(), fontFamily = FontFamily.Monospace)
                else if (roots.isNotEmpty()) {
                    if (roots.size > 1) roots.forEach { value ->
                        FilterChip(selected = value == root, onClick = { root = value; loadedPath = null }, label = { Text(value) })
                    }
                    Text("目录：/${root}", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(path, { path = it; loadedPath = null }, label = { Text("目录内相对路径") },
                        readOnly = action.path != null, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (operation == "rename") OutlinedTextField(extra, { extra = it }, label = { Text("新文件名") }, singleLine = true)
                    if (operation == "write" && loadedPath != null) {
                        OutlinedTextField(content, { content = it.take(65536) }, label = { Text("文件内容") }, minLines = 4)
                    }
                    if (operation == "upload") TextButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }) {
                        Text(if (upload == null) "选择本地文件" else "已选文件，可重新选择")
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                feedback?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && repository != null && (operation != "upload" || upload != null), onClick = {
                if (operation == "download") {
                    try { downloadPicker.launch(target().substringAfterLast('/')) }
                    catch (error: IllegalArgumentException) { feedback = error.message }
                } else execute {
                    val active = requireNotNull(repository)
                    val daemon = requireNotNull(active.consoleConnection?.daemon)
                    val instance = requireNotNull(id)
                    if (hostTask) {
                        val result = daemon.schemaRunTask(instance, requireNotNull(action.taskId)) as? JsonObject
                        val status = result?.get("status")?.jsonPrimitive?.content ?: "failed"
                        if (status != "completed") error("任务执行结果：$status")
                        result?.get("output")?.jsonPrimitive?.content.orEmpty().take(8192).ifBlank { "任务已完成" }
                    } else {
                        val file = target(operation in setOf("list", "upload"))
                        when (operation) {
                            "list" -> active.fileList(instance, file).getOrThrow()
                                .take(128).joinToString("\n") { it.name }.ifBlank { "此目录为空" }
                            "read" -> active.fileContent(instance, file).getOrThrow().take(65536)
                            "write" -> if (loadedPath == file) {
                                require(active.fileContent(instance, file).getOrThrow() == originalContent) { "文件已被修改，请重新打开后保存" }
                                require(stillGranted()) { "插件权限已变更，请重新确认" }
                                active.saveFileContent(instance, file, content).getOrThrow().also { originalContent = content }
                            } else {
                                content = active.fileContent(instance, file).getOrThrow().also {
                                    require(it.length <= 65536) { "文件过大，请用下载操作获取" }
                                }
                                originalContent = content
                                loadedPath = file
                                "修改后点保存"
                            }
                            "mkdir" -> daemon.createDirectory(instance, file.substringBeforeLast('/', ""), file.substringAfterLast('/')) ?: "已创建目录"
                            "rename" -> {
                                require(validSchemaLeaf(extra.trim())) { "新文件名无效" }
                                daemon.renameRemoteFile(instance, file,
                                    listOf(file.substringBeforeLast('/', ""), extra.trim()).filter(String::isNotBlank).joinToString("/"))
                                    ?: "已重命名"
                            }
                            "delete" -> daemon.deleteRemoteFile(instance, file) ?: "已删除"
                            "upload" -> {
                                val uri = requireNotNull(upload)
                                val (name, size) = withContext(Dispatchers.IO) {
                                    requireNotNull(context.contentResolver.query(uri,
                                        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)).use {
                                        require(it.moveToFirst())
                                        it.getString(0) to it.getLong(1)
                                    }
                                }
                                require(validSchemaLeaf(name) && size in 0..(128L * 1024 * 1024)) { "文件名称或大小无效" }
                                val uploadId = active.uploadFileStream({ requireNotNull(context.contentResolver.openInputStream(uri)) }, size) {}
                                    .getOrThrow()
                                if (!stillGranted()) {
                                    active.deleteUpload(uploadId)
                                    error("插件权限已变更，请重新确认")
                                }
                                active.saveUpload(instance, uploadId, name, file).getOrThrow()
                            }
                            else -> error("不支持此操作")
                        }
                    }
                }
            }) { Text(if (operation == "write" && loadedPath != null) "保存" else if (hostTask) "确认运行" else fileOperationLabel(operation)) }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
