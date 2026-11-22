package com.wludy.rolithax.launcher.shared

import com.wludy.rolithax.launcher.shared.panels.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.winhttp.WinHttp
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

internal class WindowsPanelSession(config: JsonObject, kind: String) : WindowsSession {
    private val profile = PanelProfile(
        id = "windows", name = "",
        kind = PanelKind.entries.firstOrNull { it.name.equals(kind, true) } ?: error("不支持此面板类型"),
        baseUrl = config.requiredString("baseUrl"),
        token = config.requiredString("apiKey"),
        allowHttp = config.flag("allowHttp"), nodeId = config.string("nodeId").orEmpty(),
    )
    private val http = HttpClient(WinHttp) { followRedirects = false }
    private val client = createPanelClient(profile, http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val callLock = Mutex()
    private val logLock = Mutex()
    private val ids = mutableMapOf<Pair<String, String>, Long>()
    private var servers = emptyMap<Long, PanelServer>()
    private var epoch = 0L
    private var sequence = 0L
    private var activeId: Long? = null
    private var job: Job? = null
    private var log = ""
    private val lines = ArrayDeque<Pair<Long, String>>()
    private var connected = false
    private var streamError: String? = null

    override fun redact(message: String) = message.replace(profile.token, "***")

    override suspend fun close() {
        scope.coroutineContext[Job]?.cancelAndJoin()
        client.close()
        http.close()
    }

    override suspend fun call(operation: String, args: JsonObject): JsonElement? = callLock.withLock {
        val id = (args["id"] as? JsonPrimitive)?.longOrNull
        when (operation) {
            "status" -> {
                refresh()
                buildJsonObject {
                    put("clientName", profile.kind.title)
                    put("version", profile.kind.title)
                }
            }
            "instances" -> {
                refresh()
                JsonArray(servers.map { (key, server) -> server.payload(key) })
            }
            "instance" -> requireNotNull(servers[id]) { "实例不存在" }.payload(requireNotNull(id))
            "action" -> {
                val action = when (args.requiredString("action")) {
                    "start" -> PanelPower.START
                    "stop" -> PanelPower.STOP
                    "restart" -> PanelPower.RESTART
                    "forceExit" -> PanelPower.KILL
                    else -> throw IllegalArgumentException("该面板不支持此操作")
                }
                require(action in client.powers) { "该面板不支持此操作" }
                client.power(requireNotNull(servers[id]) { "实例不存在" }, action)
                null
            }
            "consoleStart" -> {
                stop()
                val server = requireNotNull(servers[id]) { "实例不存在" }
                activeId = id
                epoch++
                job = scope.launch {
                    try {
                        client.logs(server) { snapshot ->
                            logLock.withLock {
                                connected = true
                                streamError = null
                                val delta = if (snapshot.startsWith(log)) snapshot.substring(log.length) else snapshot
                                if (snapshot != log) delta.lineSequence().filter(String::isNotBlank).take(1000).forEach {
                                    if (lines.size >= 1000) lines.removeFirst()
                                    lines.addLast(++sequence to it.take(8192))
                                }
                                log = snapshot
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        logLock.withLock { streamError = "日志连接已断开，请重试" }
                    } finally {
                        logLock.withLock { connected = false }
                    }
                }
                buildJsonObject { put("streamEpoch", epoch) }
            }
            "consolePoll" -> {
                requireStream(id, args)
                logLock.withLock {
                    val after = (args["afterSequence"] as? JsonPrimitive)?.longOrNull ?: 0L
                    buildJsonObject {
                        put("streamEpoch", epoch)
                        put("firstAvailableSequence", lines.firstOrNull()?.first ?: (sequence + 1))
                        put("connected", connected)
                        streamError?.let { put("error", it) }
                        put("lines", JsonArray(lines.filter { it.first > after }.take(128).map { (seq, line) ->
                            buildJsonObject {
                                put("sequence", seq)
                                put("line", line)
                            }
                        }))
                    }
                }
            }
            "consoleStop" -> { stop(); null }
            "consoleSend" -> {
                requireStream(id, args)
                client.command(requireNotNull(servers[id]) { "实例不存在" }, args.requiredString("command"))
                null
            }
            "schemaCapabilities" -> null
            else -> throw IllegalArgumentException("该面板不提供此功能")
        }
    }

    private fun requireStream(id: Long?, args: JsonObject) {
        require(id != null && id == activeId && (args["streamEpoch"] as? JsonPrimitive)?.longOrNull == epoch) {
            "控制台实例已切换"
        }
    }

    private suspend fun refresh() {
        servers = client.servers().associateBy { server -> ids.getOrPut(server.nodeId to server.id) { ids.size.toLong() + 1 } }
    }

    private suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        activeId = null
        logLock.withLock {
            log = ""
            lines.clear()
            sequence = 0L
            streamError = null
            connected = false
        }
    }

    private fun PanelServer.payload(key: Long) = buildJsonObject {
        put("id", key)
        put("providerId", id)
        put("nodeId", nodeId)
        put("name", name)
        put("core", profile.kind.title)
        put("statusText", state)
        put("status", when (state.lowercase()) {
            "running", "online", "运行中" -> 2
            "starting", "启动中" -> 1
            "stopping", "停止中" -> 3
            else -> 0
        })
        put("powers", JsonArray(client.powers.map { JsonPrimitive(it.name.lowercase()) }))
    }
}
