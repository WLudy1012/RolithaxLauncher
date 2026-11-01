package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.preparePost
import io.ktor.client.request.url
import io.ktor.http.URLProtocol
import io.ktor.http.encodedPath
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.websocket.Frame
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface ConsoleEvent {
    data object Connected : ConsoleEvent
    data object Disconnected : ConsoleEvent
    data class Log(val line: String) : ConsoleEvent
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

    suspend fun run(instanceId: Long, onEvent: suspend (ConsoleEvent) -> Unit) {
        check(runLock.tryLock()) { "控制台已运行" }
        val client = httpClient.config {
            followRedirects = false
            expectSuccess = false
            install(WebSockets) { maxFrameSize = 1024 * 1024 }
        }
        try {
            var failures = 0
            while (true) {
                try {
                    withSignalRConnection(client, endpoint, apiKey, "/api/hubs/instanceControlHub") { session, messages ->
                        session.send(Frame.Text(signalRInvocation("JoinGroup", listOf(JsonPrimitive(instanceId)))))
                        sessionLock.withLock {
                            activeSession = session
                            activeInstanceId = instanceId
                        }
                        failures = 0
                        notify(onEvent, ConsoleEvent.Connected)
                        coroutineScope {
                            val ping = launch {
                                while (isActive) {
                                    delay(15_000)
                                    session.send(Frame.Text("{\"type\":6}$SIGNALR_SEPARATOR"))
                                }
                            }
                            try {
                                while (true) {
                                    val message = deadline(45_000) { messages.next() }
                                    when (message["type"]?.jsonPrimitive?.intOrNull) {
                                        1 -> parseConsoleEvent(message)?.let { notify(onEvent, it) }
                                        7 -> throw ServerClosed()
                                    }
                                }
                            } finally { ping.cancel() }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CallbackFailed) {
                    throw e.cause ?: e
                } catch (_: Exception) {
                    // Transport errors never include the URL, negotiation token, or API key in UI events.
                } finally {
                    sessionLock.withLock {
                        activeSession = null
                        activeInstanceId = null
                    }
                }
                notify(onEvent, ConsoleEvent.Disconnected)
                val delays = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
                delay(delays[failures.coerceAtMost(delays.lastIndex)])
                failures++
            }
        } finally {
            client.close()
            runLock.unlock()
        }
    }

    suspend fun sendCommand(command: String) {
        sessionLock.withLock {
            val session = activeSession ?: throw DaemonException("控制台尚未连接")
            val instanceId = activeInstanceId ?: throw DaemonException("控制台尚未连接")
            session.send(Frame.Text(signalRInvocation("SendCommand", listOf(JsonPrimitive(instanceId), JsonPrimitive(command)))))
        }
    }

}

internal suspend fun withSignalRConnection(
        client: HttpClient,
        endpoint: String,
        apiKey: String,
        hubPath: String,
        block: suspend (DefaultClientWebSocketSession, SignalRMessages) -> Unit,
    ) {
        val body = deadline(10_000) {
            client.preparePost("$endpoint$hubPath/negotiate") {
                if (apiKey.isNotBlank()) header("x-api-key", apiKey)
                parameter("negotiateVersion", "1")
            }.execute { response ->
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
        val session = deadline(10_000) {
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
        try {
            val messages = SignalRMessages(session)
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

internal suspend fun <T : Any> deadline(timeMillis: Long, block: suspend () -> T): T =
    withTimeoutOrNull(timeMillis) { block() } ?: throw DaemonException("SignalR 连接超时")

internal class SignalRMessages(private val session: DefaultClientWebSocketSession) {
    private val decoder = SignalRFrames()
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
        "receivelog" -> args.firstOrNull()?.jsonPrimitive?.contentOrNull?.let(ConsoleEvent::Log)
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
