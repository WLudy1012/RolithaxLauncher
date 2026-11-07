package com.wludy.rolithax.launcher.shared

import com.wludy.rolithax.launcher.shared.console.ConsolePanelSnapshot
import com.wludy.rolithax.launcher.shared.console.ConsolePluginCompiler
import com.wludy.rolithax.launcher.shared.console.ConsolePluginSource
import com.wludy.rolithax.launcher.shared.console.ConsolePluginWorkspace
import io.ktor.client.HttpClient
import io.ktor.client.engine.winhttp.WinHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class WindowsDaemonSession(
    baseUrl: String,
    private val apiKey: String,
    allowHttp: Boolean,
    allowSelfSigned: Boolean,
) {
    private val endpoint = normalizeDaemonUrl(baseUrl, allowHttp)
    private val http = HttpClient(WinHttp) {
        followRedirects = false
        engine { sslVerify = !allowSelfSigned }
    }
    private val client = DaemonClient(endpoint, apiKey, http, allowHttp)
    private val console = DaemonConsole(endpoint, apiKey, http, allowHttp)
    private val monitor = DaemonSystemMonitor(endpoint, apiKey, http, allowHttp)
    private val creation = DaemonCreationProgress(endpoint, apiKey, http, allowHttp)
    private val publicHttp = lazy { HttpClient(WinHttp) { followRedirects = false } }
    private val modrinth = lazy { ModrinthClient(publicHttp.value) }
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.Default)
    private val streams = Mutex()
    private val consoleLock = Mutex()
    private var consoleJob: Job? = null
    private var consoleEpoch = 0L
    private var consoleOwner: ConsoleOwner? = null
    private var monitorJob: Job? = null
    private var creationJob: Job? = null
    private var consoleState = ConsoleBuffer()
    private val monitorState = MutableStateFlow(MonitorSnapshot())
    private val creationState = MutableStateFlow(JsonObject(emptyMap()))

    suspend fun close() {
        scopeJob.cancelAndJoin()
        client.close()
        http.close()
        if (modrinth.isInitialized()) modrinth.value.close()
        if (publicHttp.isInitialized()) publicHttp.value.close()
    }

    fun redact(message: String): String = if (apiKey.isEmpty()) message else message.replace(apiKey, "***")

    suspend fun call(operation: String, args: JsonObject): JsonElement? = with(client) {
        when (operation) {
            "status" -> statusPayload() as? JsonObject ?: throw DaemonException("Daemon 未返回有效状态")
            "instances" -> listInstancesPayload() as? JsonArray ?: throw DaemonException("Daemon 未返回实例列表")
            "instance" -> instanceInfoPayload(args.id)
            "action" -> {
                val action = args.requiredString("action")
                require(action in setOf("start", "stop", "restart", "forceExit", "backup", "agreeEula?true")) { "不支持的实例操作" }
                actionWithMessage(args.id, action)?.let(::JsonPrimitive)
            }
            "deleteInstance" -> deleteInstanceWithMessage(args.id, args.flag("deleteFiles"))?.let(::JsonPrimitive)
            "createInstance" -> JsonPrimitive(createInstance(json.decodeFromJsonElement<DaemonCreateRequest>(args)))
            "cancelCreation" -> cancelCreationWithMessage(args.requiredString("serverId"), args.flag("cleanupFiles"))?.let(::JsonPrimitive)
            "javaList" -> javaListPayload(args.flag("refresh"))
            "settings" -> instanceSettings(args.id)
            "saveSettings" -> {
                val settings = args["settings"] as? JsonObject ?: throw IllegalArgumentException("缺少实例设置")
                validateMemory(settings)
                updateInstanceSettingsResponse(args.id, settings).message?.let(::JsonPrimitive)
            }
            "files" -> listFilesPayload(args.id, args.string("path").orEmpty())
            "readFile" -> JsonPrimitive(fileContent(args.id, args.requiredString("path")))
            "saveFile" -> saveFileContentWithMessage(args.id, args.requiredString("path"), args.string("content").orEmpty())?.let(::JsonPrimitive)
            "mkdir" -> createDirectory(args.id, args.string("path").orEmpty(), args.requiredString("name"))?.let(::JsonPrimitive)
            "renameFile" -> renameRemoteFile(args.id, args.requiredString("oldPath"), args.requiredString("newPath"))?.let(::JsonPrimitive)
            "deleteFiles" -> deleteRemoteFiles(args.id, args.strings("paths")).message?.let(::JsonPrimitive)
            "downloadFile" -> downloadToWindows(args.id, args.requiredString("path"), args.requiredString("localPath")).let { null }
            "uploadFile" -> uploadFromWindows(args.id, args.string("path").orEmpty(), args.requiredString("localPath"), args.requiredString("name")).let { null }
            "consoleStart" -> buildJsonObject { put("streamEpoch", startConsole(args.id)) }
            "consolePoll" -> pollConsole(args)
            "consoleSend" -> sendConsoleCommand(args).let { null }
            "consoleStop" -> stopConsole().let { null }
            "consolePanelInspect" -> inspectConsolePlugin(args)
            "consolePanelImport" -> changeConsolePlugin(args, restore = false)
            "consolePanelRestore" -> changeConsolePlugin(args, restore = true)
            "consolePanelPrepareAction" -> JsonPrimitive(prepareConsolePanelAction(args))
            "consolePanelAction" -> sendConsolePanelAction(args).let { null }
            "monitorStart" -> startMonitor().let { null }
            "monitorPoll" -> monitorState.value.payload()
            "monitorStop" -> stopMonitor().let { null }
            "creationStart" -> startCreation(args.requiredString("serverId")).let { null }
            "creationPoll" -> creationState.value
            "creationStop" -> stopCreation().let { null }
            "users" -> adminUserListPayload()
            "frps" -> frpListPayload()
            "createUser" -> adminCreateUserWithMessage(args)?.let(::JsonPrimitive)
            "updateUser" -> adminUpdateUserWithMessage(args.requiredString("id"), JsonObject(args - "id"))?.let(::JsonPrimitive)
            "deleteUser" -> adminDeleteUserWithMessage(args.requiredString("id"))?.let(::JsonPrimitive)
            "profile" -> currentUserPayload()
            "updateProfile" -> updateSelfWithMessage(args)?.let(::JsonPrimitive)
            "pluginsMods" -> json.encodeToJsonElement(listPluginsMods(args.id, args.requiredString("mode")))
            "setPluginsMods" -> setPluginsMods(args.id, args.requiredString("mode"), args.requiredString("action"), args.strings("targets")).let { null }
            "resourceSearch" -> JsonArray(modrinth.value.search(
                args.string("query").orEmpty(), args.requiredString("type"), args.string("gameVersion").orEmpty(), args.string("loader").orEmpty(),
            ).map { project ->
                buildJsonObject {
                    put("id", project.id)
                    put("title", project.title)
                    put("description", project.description)
                    put("author", project.author)
                }
            })
            "resourceVersions" -> JsonArray(modrinth.value.versions(
                args.requiredString("projectId"), args.requiredString("gameVersion"), args.requiredString("loader"),
            ).map { version ->
                buildJsonObject {
                    put("id", version.id)
                    put("name", version.name)
                    put("number", version.number)
                }
            })
            "resourceInstall" -> json.encodeToJsonElement(installModrinthResource(args))
            "resourceTask" -> json.encodeToJsonElement(resourceTask(args.requiredString("taskId")))
            "resourceCancel" -> cancelResourceTask(args.requiredString("taskId")).let { null }
            "pair" -> DaemonClient(endpoint, "", http, endpoint.startsWith("http://", true)).let { anonymous ->
                try {
                    anonymous.postResponse("api/plugin/mslx-plugin-rolithax/pair/redeem", args.toString()).data
                } catch (error: DaemonException) {
                    throw DaemonException("配对失败，请检查配对码和插件状态", error.httpStatus, error.businessCode)
                } finally {
                    anonymous.close()
                }
            }
            else -> throw IllegalArgumentException("未知操作")
        }
    }

    private suspend fun installModrinthResource(args: JsonObject): DaemonResourceTask {
        val projectId = args.requiredString("projectId")
        val versionId = args.requiredString("versionId")
        val type = args.requiredString("type")
        val gameVersion = args.requiredString("gameVersion")
        val loader = args.requiredString("loader")
        val provider = modrinth.value
        val version = provider.versions(projectId, gameVersion, loader).firstOrNull { it.id == versionId }
            ?: throw DaemonException("未找到与实例兼容的资源版本")
        val plan = provider.planInstall(ModrinthProject(projectId = projectId), version, type, gameVersion, loader)
        val items = plan.entries.map { DaemonResourceInstallItem(it.project.id, it.version.id, it.type) }
        return client.installResources(args.id, gameVersion, loader, items)
    }

    private suspend fun startConsole(id: Long) = streams.withLock {
        consoleJob?.cancelAndJoin()
        val owner = consoleLock.withLock {
            ConsoleOwner(id, ++consoleEpoch).also {
                consoleOwner = it
                consoleState = ConsoleBuffer()
            }
        }
        consoleJob = scope.launch {
            console.run(id) { event ->
                consoleLock.withLock eventLock@{
                    if (consoleOwner !== owner) return@eventLock
                    when (event) {
                        ConsoleEvent.Connected -> consoleState.connected = true
                        ConsoleEvent.Disconnected -> consoleState.connected = false
                        ConsoleEvent.EulaRequired -> consoleState.eulaRequired = true
                        ConsoleEvent.Gap -> {
                            if (owner.workspace.markGap()) owner.panel = owner.workspace.snapshot()
                            consoleState.append("［控制台日志可能有缺失］")
                        }
                        is ConsoleEvent.Log -> {
                            val line = redact(event.line)
                            if (owner.workspace.updateLog(line)) owner.panel = owner.workspace.snapshot()
                            consoleState.append(line)
                        }
                        is ConsoleEvent.CommandResult -> event.message?.let { consoleState.append(redact(it)) }
                    }
                }
            }
        }
        owner.epoch
    }

    private suspend fun stopConsole() = streams.withLock {
        consoleJob?.cancelAndJoin()
        consoleJob = null
        consoleLock.withLock {
            consoleOwner = null
            consoleState = ConsoleBuffer()
        }
    }

    private suspend fun pollConsole(args: JsonObject): JsonObject {
        val (delta, panel) = consoleLock.withLock {
            val owner = consoleOwner
            if (args.number("streamEpoch") != null) requireConsoleOwner(args)
            val after = args.number("afterSequence")?.coerceAtLeast(0) ?: 0
            val limit = args.number("limit")?.coerceIn(1, 1000)?.toInt() ?: 1000
            val panel = args.number("afterPanelRevision")?.let { revision ->
                owner?.panel?.takeIf { it.revision != revision }
            }
            consoleState.delta(after, limit, owner?.epoch ?: 0) to panel
        }
        return delta.payload(panel)
    }

    private suspend fun sendConsoleCommand(args: JsonObject) = streams.withLock {
        if (args.number("streamEpoch") != null) consoleLock.withLock { requireConsoleOwner(args) }
        console.sendCommand(args.requiredString("command"))
    }

    private fun inspectConsolePlugin(args: JsonObject): JsonElement =
        Json.encodeToJsonElement(ConsolePluginCompiler.compile(args.pluginSource()).commands)

    private suspend fun changeConsolePlugin(args: JsonObject, restore: Boolean): JsonElement = streams.withLock {
        consoleLock.withLock {
            val owner = requireConsoleOwner(args)
            val snapshot = if (restore) owner.workspace.restoreBuiltIn()
            else owner.workspace.reload(args.pluginSource(), args.strings("allowedCommands").toSet())
            owner.panel = snapshot
            Json.encodeToJsonElement(snapshot)
        }
    }

    private suspend fun prepareConsolePanelAction(args: JsonObject): String = consoleLock.withLock {
        consolePanelCommand(args)
    }

    private suspend fun sendConsolePanelAction(args: JsonObject) = streams.withLock {
        val command = consoleLock.withLock {
            consolePanelCommand(args).also {
                require(it == args.requiredString("confirmedCommand")) { "面板命令已更新，请重新确认" }
            }
        }
        console.sendCommand(command)
    }

    private fun consolePanelCommand(args: JsonObject): String {
        val owner = requireConsoleOwner(args)
        if (!consoleState.connected) throw DaemonException("控制台尚未连接")
        val arguments: Map<String, String> = args["arguments"]?.let { Json.decodeFromJsonElement(it) } ?: emptyMap()
        val revision = requireNotNull(args.number("packageRevision")) { "缺少面板版本" }
        return owner.workspace.command(args.requiredString("actionId"), arguments, revision)
            ?: throw DaemonException("面板命令不可用，请检查插件权限及输入")
    }

    private fun requireConsoleOwner(args: JsonObject): ConsoleOwner {
        val owner = consoleOwner
        if (owner == null || owner.id != args.id || owner.epoch != args.number("streamEpoch"))
            throw DaemonException("控制台实例已切换")
        return owner
    }

    private suspend fun startMonitor() = streams.withLock {
        if (monitorJob?.isActive == true) return@withLock
        monitorJob = scope.launch {
            monitor.run { event ->
                monitorState.value = when (event) {
                    SystemMonitorEvent.Connected -> MonitorSnapshot(connected = true)
                    SystemMonitorEvent.Disconnected -> MonitorSnapshot()
                    is SystemMonitorEvent.Stats -> MonitorSnapshot(connected = true, stats = event)
                }
            }
        }
    }

    private suspend fun stopMonitor() = streams.withLock {
        monitorJob?.cancelAndJoin()
        monitorJob = null
        monitorState.value = MonitorSnapshot()
    }

    private suspend fun startCreation(id: String) = streams.withLock {
        creationJob?.cancelAndJoin()
        creationState.value = JsonObject(emptyMap())
        creationJob = scope.launch {
            creation.run(id) { event ->
                creationState.value = buildJsonObject {
                    put("connected", event != CreationProgressEvent.Disconnected)
                    if (event is CreationProgressEvent.Update) {
                        put("serverId", event.serverId)
                        put("message", redact(event.message))
                        put("progress", event.progress)
                    }
                }
            }
        }
    }

    private suspend fun stopCreation() = streams.withLock {
        creationJob?.cancelAndJoin()
        creationJob = null
        creationState.value = JsonObject(emptyMap())
    }
}

private class ConsoleOwner(val id: Long, val epoch: Long) {
    val workspace = ConsolePluginWorkspace()
    var panel = workspace.snapshot()
}

private class ConsoleBuffer {
    var connected = false
    var eulaRequired = false
    private var sequence = 0L
    private val lines = ArrayDeque<Pair<Long, String>>()

    fun append(line: String) {
        if (lines.size == 1000) lines.removeFirst()
        lines.addLast(++sequence to line.take(8_192))
    }

    fun delta(after: Long, limit: Int, epoch: Long) = ConsoleDelta(
        connected, eulaRequired, epoch, lines.firstOrNull()?.first ?: sequence + 1,
        lines.asSequence().filter { it.first > after }.take(limit).toList(),
    )
}

private data class ConsoleDelta(
    val connected: Boolean,
    val eulaRequired: Boolean,
    val epoch: Long,
    val firstAvailableSequence: Long,
    val lines: List<Pair<Long, String>>,
) {
    fun payload(panel: ConsolePanelSnapshot?) = buildJsonObject {
        put("connected", connected)
        put("eulaRequired", eulaRequired)
        put("streamEpoch", epoch)
        put("firstAvailableSequence", firstAvailableSequence)
        panel?.let { put("panel", Json.encodeToJsonElement(it)) }
        put("lines", JsonArray(lines.map { (number, line) ->
            buildJsonObject {
                put("sequence", number)
                put("line", line)
            }
        }))
    }
}

private data class MonitorSnapshot(val connected: Boolean = false, val stats: SystemMonitorEvent.Stats? = null) {
    fun payload() = buildJsonObject {
        put("connected", connected)
        stats?.let {
            put("cpu", it.cpu)
            put("memTotal", it.memTotal)
            put("memUsed", it.memUsed)
            put("memUsage", it.memUsage)
            put("timestamp", it.timestamp)
        }
    }
}

internal fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.requiredString(key: String): String =
    requireNotNull(string(key)?.takeIf { it.isNotBlank() }) { "缺少参数 $key" }

internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
private val JsonObject.id: Long get() = requireNotNull(number("id")?.takeIf { it > 0 }) { "实例 ID 无效" }
private fun JsonObject.strings(key: String): List<String> = Json.decodeFromJsonElement(get(key) ?: JsonArray(emptyList()))
private fun JsonObject.pluginSource(): ConsolePluginSource =
    Json.decodeFromJsonElement(get("source") ?: throw IllegalArgumentException("缺少控制台插件内容"))

private fun validateMemory(settings: JsonObject) {
    fun memory(key: String): Int? {
        val value = settings.string(key)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return requireNotNull(value.toIntOrNull()?.takeIf { it >= 0 }) { "内存必须为非负整数" }
    }
    val min = memory("minM")
    val max = memory("maxM")
    require(min == null || max == null || min <= max) { "最大内存不能小于最小内存" }
}
