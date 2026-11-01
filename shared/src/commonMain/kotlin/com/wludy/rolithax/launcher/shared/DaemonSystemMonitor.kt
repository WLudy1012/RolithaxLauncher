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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface SystemMonitorEvent {
    data object Connected : SystemMonitorEvent
    data object Disconnected : SystemMonitorEvent
    data class Stats(
        val cpu: Double?,
        val memTotal: Double?,
        val memUsed: Double?,
        val memUsage: Double?,
        val timestamp: String?,
    ) : SystemMonitorEvent
}

class DaemonSystemMonitor(
    baseUrl: String,
    private val apiKey: String,
    private val httpClient: HttpClient,
    allowHttp: Boolean = false,
) {
    private val endpoint = normalizeDaemonUrl(baseUrl, allowHttp)
    private val runLock = Mutex()

    suspend fun run(onEvent: suspend (SystemMonitorEvent) -> Unit) {
        check(runLock.tryLock()) { "系统监控已运行" }
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
                        withSignalRConnection(client, endpoint, apiKey, "/api/hubs/system") { session, messages ->
                            session.send(Frame.Text(signalRInvocation("JoinMonitor", emptyList())))
                            failures = 0
                            emitSystemEvent(onEvent, SystemMonitorEvent.Connected)
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
                                        if (type != "1" || message["target"]?.jsonPrimitive?.contentOrNull?.lowercase() != "receivesystemstats") continue
                                        val args = message["arguments"] as? JsonArray ?: continue
                                        val envelope = args.firstOrNull() as? JsonObject ?: continue
                                        val local = envelope["local"] as? JsonObject ?: continue
                                        fun number(key: String) = local[key]?.jsonPrimitive?.doubleOrNull
                                        emitSystemEvent(onEvent, SystemMonitorEvent.Stats(
                                            cpu = number("cpu"),
                                            memTotal = number("memTotal"),
                                            memUsed = number("memUsed"),
                                            memUsage = number("memUsage"),
                                            timestamp = local["timestamp"]?.jsonPrimitive?.contentOrNull,
                                        ))
                                    }
                                } finally { ping.cancel() }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: MonitorCallbackFailed) {
                        throw e.cause ?: e
                    } catch (_: Exception) {
                    }
                    emitSystemEvent(onEvent, SystemMonitorEvent.Disconnected)
                    val delays = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
                    delay(delays[failures.coerceAtMost(delays.lastIndex)])
                    failures++
                }
            } finally { client.close() }
        } finally { runLock.unlock() }
    }
}

private class MonitorCallbackFailed(cause: Throwable) : Exception(cause)

private suspend fun emitSystemEvent(callback: suspend (SystemMonitorEvent) -> Unit, event: SystemMonitorEvent) {
    try { callback(event) } catch (e: CancellationException) { throw e } catch (e: Exception) { throw MonitorCallbackFailed(e) }
}
