package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface CreationProgressEvent {
    data object Connected : CreationProgressEvent
    data object Disconnected : CreationProgressEvent
    data class Update(val serverId: String, val message: String, val progress: Double) : CreationProgressEvent
}

class DaemonCreationProgress(
    baseUrl: String,
    private val apiKey: String,
    private val httpClient: HttpClient,
    allowHttp: Boolean = false,
) {
    private val endpoint = normalizeDaemonUrl(baseUrl, allowHttp)
    private val runLock = Mutex()

    suspend fun run(serverId: String, onEvent: suspend (CreationProgressEvent) -> Unit) {
        require(serverId.isNotBlank())
        check(runLock.tryLock()) { "创建进度监控已运行" }
        try {
            val client = httpClient.config {
                followRedirects = false
                expectSuccess = false
                install(WebSockets) { maxFrameSize = 1024 * 1024 }
            }
            try {
                var failures = 0
                while (true) {
                    try {
                        withSignalRConnection(client, endpoint, apiKey, "/api/hubs/creationProgressHub") { session, messages ->
                            session.send(Frame.Text(signalRInvocation("TrackServer", listOf(JsonPrimitive(serverId)))))
                            failures = 0
                            emit(onEvent, CreationProgressEvent.Connected)
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
                                        val type = message["type"]?.jsonPrimitive?.contentOrNull
                                        if (type == "7") throw ServerClosed()
                                        if (type != "1" || message["target"]?.jsonPrimitive?.contentOrNull?.lowercase() != "statusupdate") continue
                                        val args = message["arguments"] as? JsonArray ?: continue
                                        if (args.size != 3) continue
                                        val id = args[0].jsonPrimitive.contentOrNull ?: continue
                                        val text = args[1].jsonPrimitive.contentOrNull ?: continue
                                        val progress = args[2].jsonPrimitive.doubleOrNull ?: continue
                                        if (id == serverId) emit(onEvent, CreationProgressEvent.Update(id, text, progress))
                                    }
                                } finally { ping.cancel() }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ProgressCallbackFailed) {
                        throw e.cause ?: e
                    } catch (_: Exception) {
                    }
                    emit(onEvent, CreationProgressEvent.Disconnected)
                    val delays = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
                    delay(delays[failures.coerceAtMost(delays.lastIndex)])
                    failures++
                }
            } finally { client.close() }
        } finally { runLock.unlock() }
    }
}

private class ProgressCallbackFailed(cause: Throwable) : Exception(cause)

private suspend fun emit(callback: suspend (CreationProgressEvent) -> Unit, event: CreationProgressEvent) {
    try { callback(event) } catch (e: CancellationException) { throw e } catch (e: Exception) { throw ProgressCallbackFailed(e) }
}
