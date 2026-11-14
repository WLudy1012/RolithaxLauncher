package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.preparePost
import io.ktor.client.request.url
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.websocket.Frame
import io.ktor.websocket.FrameTooBigException
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val CONSOLE_MESSAGE_LIMIT = 4 * MAX_SIGNALR_MESSAGE
private const val MAX_BUFFERED_LOGS = 2048
private const val REORDER_TIMEOUT_MILLIS = 3_000L

data class ConsoleCursor(val streamId: String, val sequence: Long)

sealed interface ConsoleEvent {
    data object Connected : ConsoleEvent
    data object Disconnected : ConsoleEvent
    data object Gap : ConsoleEvent
    data class AuthenticationFailed(val httpStatus: Int) : ConsoleEvent
    data object OutputTooLarge : ConsoleEvent
    /** replayed 为 null 表示无法区分该日志来自历史回放还是实时推送。 */
    data class Log(val line: String, val replayed: Boolean? = null, val cursor: ConsoleCursor? = null) : ConsoleEvent
    data class CommandResult(val success: Boolean, val message: String?) : ConsoleEvent
    data object EulaRequired : ConsoleEvent
}

class DaemonConsole(
    baseUrl: String,
    private val apiKey: String,
    private val httpClient: HttpClient,
    allowHttp: Boolean = false,
) {
    private val endpoint = normalizeDaemonUrl(baseUrl, allowHttp)
    private val runLock = Mutex()
    private val sessionLock = Mutex()
    private var activeSession: DefaultClientWebSocketSession? = null
    private var activeInstanceId: Long? = null

    suspend fun run(instanceId: Long, onEvent: suspend (ConsoleEvent) -> Unit) = run(instanceId, null, onEvent)

    suspend fun run(instanceId: Long, cursor: ConsoleCursor?, onEvent: suspend (ConsoleEvent) -> Unit) {
        check(runLock.tryLock()) { "控制台已运行" }
        try {
            val client = httpClient.config {
                followRedirects = false
                expectSuccess = false
                install(WebSockets) { maxFrameSize = CONSOLE_MESSAGE_LIMIT.toLong() }
            }
            try {
                runConnections(client, instanceId, cursor, onEvent)
            } finally {
                client.close()
            }
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun runConnections(
        client: HttpClient,
        instanceId: Long,
        initialCursor: ConsoleCursor?,
        onEvent: suspend (ConsoleEvent) -> Unit,
    ) {
        val instance = JsonPrimitive(instanceId)
        val stream = ConsoleStream(initialCursor, onEvent)
        val retryDelays = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
        var retryIndex = 0
        var legacy = false
        var connectedBefore = initialCursor != null
        var fallbackGap = false
        var connectedAt: TimeMark? = null
        while (true) {
            try {
                withSignalRConnection(client, endpoint, apiKey, "/api/hubs/instanceControlHub", CONSOLE_MESSAGE_LIMIT) { session, messages ->
                    val resume = if (legacy) null else subscribe(
                        session, messages, "ResumeConsole", "resume",
                        listOf(instance, stream.cursor?.toJson() ?: JsonNull, JsonNull, JsonPrimitive(false), JsonPrimitive(0), JsonPrimitive(0)),
                    )
                    if (resume?.completion?.isResumeUnsupported() == true) legacy = true
                    val subscription = if (legacy) {
                        subscribe(session, messages, "JoinGroup", "join", listOf(instance))
                    } else {
                        checkNotNull(resume)
                    }
                    if (subscription.completion["error"] != null) throw DaemonException("控制台订阅失败")
                    val replay = if (legacy) null else subscription.completion.parseConsoleReplay(stream.cursor)

                    try {
                        sessionLock.withLock {
                            activeSession = session
                            activeInstanceId = instanceId
                        }
                        connectedAt = TimeSource.Monotonic.markNow()
                        notify(onEvent, ConsoleEvent.Connected)
                        if (replay != null) {
                            if (subscription.dropped) notify(onEvent, ConsoleEvent.Gap)
                            stream.replay(replay)
                        }
                        else if (connectedBefore || fallbackGap || subscription.dropped) notify(onEvent, ConsoleEvent.Gap)
                        fallbackGap = false
                        connectedBefore = true
                        for (event in subscription.events) stream.receive(event, legacy)
                        receiveConsoleMessages(session, messages, stream, legacy)
                    } finally {
                        withContext(NonCancellable) {
                            sessionLock.withLock {
                                activeSession = null
                                activeInstanceId = null
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CallbackFailed) {
                throw e.cause ?: e
            } catch (e: SignalRAuthenticationException) {
                notify(onEvent, ConsoleEvent.AuthenticationFailed(e.status))
                return
            } catch (e: Exception) {
                if (e.isSignalRMessageTooLarge()) {
                    if (legacy) {
                        notify(onEvent, ConsoleEvent.OutputTooLarge)
                        return
                    }
                    legacy = true
                    fallbackGap = true
                }
            }
            stream.clearPending()
            if (connectedAt?.elapsedNow()?.inWholeSeconds?.let { it >= 30 } == true) retryIndex = 0
            connectedAt = null
            notify(onEvent, ConsoleEvent.Disconnected)
            delay(retryDelays[retryIndex])
            retryIndex = (retryIndex + 1).coerceAtMost(retryDelays.lastIndex)
        }
    }

    suspend fun sendCommand(command: String) {
        sessionLock.withLock {
            val session = activeSession ?: throw DaemonException("控制台尚未连接")
            val instanceId = activeInstanceId ?: throw DaemonException("控制台尚未连接")
            deadline(10_000) {
                session.send(Frame.Text(signalRInvocation("SendCommand", listOf(JsonPrimitive(instanceId), JsonPrimitive(command)))))
            }
        }
    }
}

private suspend fun receiveConsoleMessages(
    session: DefaultClientWebSocketSession,
    messages: SignalRMessages,
    stream: ConsoleStream,
    legacy: Boolean,
) = coroutineScope {
    val ping = launch {
        while (isActive) {
            delay(15_000)
            session.send(Frame.Text("{\"type\":6}$SIGNALR_SEPARATOR"))
        }
    }
    try {
        while (true) {
            val message = deadline(stream.receiveTimeout()) { messages.next() }
            when (message["type"]?.jsonPrimitive?.intOrNull) {
                1 -> parseConsoleEvent(message)?.let { stream.receive(it, legacy) }
                7 -> throw ServerClosed()
            }
        }
    } finally {
        ping.cancel()
    }
}

private data class Subscription(val completion: JsonObject, val events: List<ConsoleEvent>, val dropped: Boolean)

private suspend fun subscribe(
    session: DefaultClientWebSocketSession,
    messages: SignalRMessages,
    method: String,
    invocationId: String,
    arguments: List<JsonElement>,
): Subscription = deadline(10_000) {
    session.send(Frame.Text(signalRInvocation(method, arguments, invocationId)))
    val events = ArrayDeque<ConsoleEvent>()
    var chars = 0
    var dropped = false
    fun eventSize(event: ConsoleEvent): Int = when (event) {
        is ConsoleEvent.Log -> event.line.length
        is ConsoleEvent.CommandResult -> event.message?.length ?: 0
        else -> 0
    }
    var completion: JsonObject? = null
    while (completion == null) {
        val message = messages.next()
        when (message["type"]?.jsonPrimitive?.intOrNull) {
            1 -> parseConsoleEvent(message)?.let { event ->
                val size = eventSize(event)
                while (events.isNotEmpty() && (events.size >= MAX_BUFFERED_LOGS || chars + size > CONSOLE_MESSAGE_LIMIT)) {
                    chars -= eventSize(events.removeFirst())
                    dropped = true
                }
                if (size > CONSOLE_MESSAGE_LIMIT) dropped = true else {
                    events.addLast(event)
                    chars += size
                }
            }
            3 -> if (message["invocationId"]?.jsonPrimitive?.contentOrNull == invocationId) completion = message
            7 -> throw ServerClosed()
        }
    }
    Subscription(checkNotNull(completion), events.toList(), dropped)
}

private fun JsonObject.isResumeUnsupported(): Boolean = when (this["error"]?.jsonPrimitive?.contentOrNull) {
    "Unknown hub method 'ResumeConsole'",
    "Failed to invoke 'ResumeConsole' due to an error on the server. HubException: Method does not exist." -> true
    else -> false
}

private data class ConsoleReplay(val start: ConsoleCursor?, val gap: Boolean, val logs: List<ConsoleEvent.Log>)

private fun JsonObject.parseConsoleReplay(previous: ConsoleCursor?): ConsoleReplay {
    val result = this["result"] as? JsonObject ?: invalidReplay()
    val log = result["log"]
    if (log == JsonNull) return ConsoleReplay(null, gap = previous != null, emptyList())
    val snapshot = log as? JsonObject ?: invalidReplay()
    val cursor = snapshot.parseConsoleCursor() ?: invalidReplay()
    val serverGap = (snapshot["gap"] as? JsonPrimitive)?.booleanOrNull ?: invalidReplay()
    val frames = snapshot["frames"] as? JsonArray ?: invalidReplay()
    if (frames.size > MAX_BUFFERED_LOGS) throw SignalRMessageTooLarge()
    val logs = frames.map { value ->
        val frame = value as? JsonObject ?: invalidReplay()
        val sequence = frame["sequence"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: invalidReplay()
        val text = (frame["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalidReplay()
        ConsoleEvent.Log(text, replayed = true, cursor = cursor.copy(sequence = sequence))
    }
    val gap = previous != null && (serverGap || previous.streamId != cursor.streamId)
    val base = if (previous == null || gap) logs.firstOrNull()?.cursor?.sequence?.minus(1) ?: cursor.sequence else previous.sequence
    var sequence = base
    for (entry in logs) {
        val next = checkNotNull(entry.cursor).sequence
        if (next <= sequence || next - sequence != 1L) invalidReplay()
        sequence = next
    }
    if (sequence != cursor.sequence) invalidReplay()
    return ConsoleReplay(cursor.copy(sequence = base), gap, logs)
}

private fun invalidReplay(): Nothing = throw DaemonException("控制台重放响应无效")

private fun JsonObject.parseConsoleCursor(): ConsoleCursor? {
    val streamId = (this["streamId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
    val sequence = (this["sequence"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 } ?: return null
    return ConsoleCursor(streamId, sequence)
}

private fun ConsoleCursor.toJson() = buildJsonObject {
    put("streamId", streamId)
    put("sequence", sequence)
}

private class ConsoleStream(initialCursor: ConsoleCursor?, private val onEvent: suspend (ConsoleEvent) -> Unit) {
    var cursor: ConsoleCursor? = initialCursor
        private set
    private val pending = mutableMapOf<Long, ConsoleEvent.Log>()
    private var pendingChars = 0
    private var pendingSince: TimeMark? = null

    fun clearPending() {
        pending.clear()
        pendingChars = 0
        pendingSince = null
    }

    fun receiveTimeout(): Long {
        val since = pendingSince ?: return 45_000
        val remaining = REORDER_TIMEOUT_MILLIS - since.elapsedNow().inWholeMilliseconds
        if (remaining <= 0) throw DaemonException("控制台日志序号不连续")
        return remaining
    }

    suspend fun replay(snapshot: ConsoleReplay) {
        if (snapshot.gap) notify(onEvent, ConsoleEvent.Gap)
        cursor = snapshot.start
        for (log in snapshot.logs) emitLog(log)
    }

    suspend fun receive(event: ConsoleEvent, legacy: Boolean) {
        if (event !is ConsoleEvent.Log) {
            notify(onEvent, event)
            return
        }
        val position = event.cursor
        if (legacy || position == null) {
            notify(onEvent, event.copy(replayed = null, cursor = null))
            return
        }
        val current = cursor ?: position.copy(sequence = 0)
        if (current.streamId != position.streamId) throw DaemonException("控制台日志流已变化")
        cursor = current
        if (position.sequence <= current.sequence || position.sequence in pending) return
        if (position.sequence - current.sequence > 1) {
            if (pending.size >= MAX_BUFFERED_LOGS || pendingChars + event.line.length > CONSOLE_MESSAGE_LIMIT) {
                throw DaemonException("控制台日志缓冲区已满")
            }
            pending[position.sequence] = event
            pendingChars += event.line.length
            if (pendingSince == null) pendingSince = TimeSource.Monotonic.markNow()
            return
        }
        emitLog(event)
        var sequence = position.sequence
        while (sequence < Long.MAX_VALUE) {
            val next = pending.remove(sequence + 1) ?: break
            pendingChars -= next.line.length
            emitLog(next)
            sequence++
        }
        if (pending.isEmpty()) pendingSince = null
    }

    private suspend fun emitLog(log: ConsoleEvent.Log) {
        notify(onEvent, log)
        cursor = log.cursor
    }
}

internal suspend fun withSignalRConnection(
        client: HttpClient,
        endpoint: String,
        apiKey: String,
        hubPath: String,
        maxMessageSize: Int = MAX_SIGNALR_MESSAGE,
        block: suspend (DefaultClientWebSocketSession, SignalRMessages) -> Unit,
    ) {
        val body = deadline(10_000) {
            client.preparePost("$endpoint$hubPath/negotiate") {
                if (apiKey.isNotBlank()) header("x-api-key", apiKey)
                parameter("negotiateVersion", "1")
            }.execute { response ->
                if (response.status.value == 401 || response.status.value == 403) throw SignalRAuthenticationException(response.status.value)
                if (!response.status.isSuccess()) throw DaemonException("SignalR 协商失败")
                response.readLimitedText(1024 * 1024)
            }
        }
        val negotiation = signalRObject(body)
        if (negotiation["url"] != null || negotiation["error"] != null) throw DaemonException("不支持此 SignalR 协商响应")
        val token = (negotiation["connectionToken"] ?: negotiation["connectionId"])?.jsonPrimitive?.contentOrNull
            ?: throw DaemonException("SignalR 协商未返回连接令牌")
        val transports = negotiation["availableTransports"] as? JsonArray ?: throw DaemonException("SignalR 协商未返回传输方式")
        val supportsWebSocket = transports.any { item ->
            val transport = item as? JsonObject
            transport?.get("transport")?.jsonPrimitive?.contentOrNull == "WebSockets" &&
                (transport["transferFormats"] as? JsonArray)?.any { it.jsonPrimitive.contentOrNull == "Text" } == true
        }
        if (!supportsWebSocket) throw DaemonException("Daemon 不支持 SignalR WebSocket 文本传输")
        val session = try {
            deadline(10_000) {
                client.webSocketSession {
                    url {
                        takeFrom(endpoint)
                        protocol = if (protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
                        encodedPath = encodedPath.trimEnd('/') + hubPath
                        parameters.append("id", token)
                    }
                    if (apiKey.isNotBlank()) header("x-api-key", apiKey)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val status = e.signalRUpgradeAuthStatus()
            if (status != null) throw SignalRAuthenticationException(status)
            throw e
        }
        try {
            val messages = SignalRMessages(session, maxMessageSize)
            session.send(Frame.Text("{\"protocol\":\"json\",\"version\":1}$SIGNALR_SEPARATOR"))
            val handshake = deadline(10_000) { messages.next() }
            if (handshake["error"] != null) throw DaemonException("SignalR 握手失败")
            block(session, messages)
        } finally {
            withContext(NonCancellable) { withTimeoutOrNull(1_000) { session.close() } }
        }
}

private suspend fun notify(callback: suspend (ConsoleEvent) -> Unit, event: ConsoleEvent) {
    try { callback(event) } catch (e: CancellationException) { throw e } catch (e: Exception) { throw CallbackFailed(e) }
}

private class CallbackFailed(cause: Throwable) : Exception(cause)
internal class ServerClosed : Exception()
internal class SignalRAuthenticationException(val status: Int) : Exception()

private fun Throwable.isSignalRMessageTooLarge(): Boolean =
    generateSequence(this) { it.cause }.take(8).any { it is SignalRMessageTooLarge || it is FrameTooBigException }

private fun Throwable.signalRUpgradeAuthStatus(): Int? {
    for (error in generateSequence(this) { it.cause }.take(8)) {
        val status = (error as? ResponseException)?.response?.status?.value
        if (status == 401 || status == 403) return status
        val text = error.message.orEmpty()
        val match = Regex("(?:Handshake exception, expected status code 101 but was |Expected HTTP 101 response but was ')[ ]?(401|403)(?:\\b|')").find(text)
        if (match != null) return match.groupValues[1].toInt()
    }
    return null
}

internal suspend fun <T : Any> deadline(timeMillis: Long, block: suspend () -> T): T =
    withTimeoutOrNull(timeMillis) { block() } ?: throw DaemonException("SignalR 连接超时")

internal class SignalRMessages(private val session: DefaultClientWebSocketSession, maxMessageSize: Int = MAX_SIGNALR_MESSAGE) {
    private val decoder = SignalRFrames(maxMessageSize)
    private val ready = ArrayDeque<String>()

    suspend fun next(): JsonObject {
        while (ready.isEmpty()) {
            when (val frame = session.incoming.receive()) {
                is Frame.Text -> ready.addAll(decoder.append(frame.readText()))
                is Frame.Close -> throw ServerClosed()
                is Frame.Binary -> throw DaemonException("SignalR 使用了不支持的二进制协议")
                else -> Unit
            }
        }
        return signalRObject(ready.removeFirst())
    }
}

internal fun parseConsoleEvent(message: JsonObject): ConsoleEvent? {
    val args = message["arguments"] as? JsonArray ?: return null
    return when (message["target"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
        "receivelog" -> {
            val line = (args.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val cursor = (args.getOrNull(1) as? JsonObject)?.parseConsoleCursor()?.takeIf { it.sequence > 0 }
            ConsoleEvent.Log(line, replayed = cursor?.let { false }, cursor = cursor)
        }
        "requireeula" -> ConsoleEvent.EulaRequired
        "commandresult" -> {
            val result = args.firstOrNull() as? JsonObject ?: return null
            ConsoleEvent.CommandResult(
                success = result["success"]?.jsonPrimitive?.booleanOrNull ?: false,
                message = result["message"]?.jsonPrimitive?.contentOrNull,
            )
        }
        else -> null
    }
}
