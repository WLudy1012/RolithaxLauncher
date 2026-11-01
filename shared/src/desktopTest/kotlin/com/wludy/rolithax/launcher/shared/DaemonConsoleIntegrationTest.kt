package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DaemonConsoleIntegrationTest {
    @Test
    fun negotiatesHandshakesExchangesMessagesAndReconnects() = runBlocking {
        val connections = AtomicInteger()
        val commands = Channel<String>(Channel.UNLIMITED)
        val events = Channel<ConsoleEvent>(Channel.UNLIMITED)
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                post("/api/hubs/instanceControlHub/negotiate") {
                    assertEquals("test-key", call.request.header("x-api-key"))
                    assertEquals("1", call.request.queryParameters["negotiateVersion"])
                    call.respondText(
                        """{"negotiateVersion":1,"connectionToken":"token","availableTransports":[{"transport":"WebSockets","transferFormats":["Text"]}]}""",
                        ContentType.Application.Json,
                    )
                }
                webSocket("/api/hubs/instanceControlHub") {
                    assertEquals("test-key", call.request.header("x-api-key"))
                    assertEquals("token", call.request.queryParameters["id"])
                    val sequence = connections.incrementAndGet()
                    assertEquals("{\"protocol\":\"json\",\"version\":1}$SIGNALR_SEPARATOR", (incoming.receive() as Frame.Text).readText())
                    send(Frame.Text("{}$SIGNALR_SEPARATOR"))
                    val join = signalRObject((incoming.receive() as Frame.Text).readText().removeSuffix("$SIGNALR_SEPARATOR"))
                    assertEquals("JoinGroup", join["target"]?.jsonPrimitive?.content)
                    assertEquals(42, join["arguments"]?.jsonArray?.first()?.jsonPrimitive?.intOrNull)
                    send(Frame.Text("{\"type\":1,\"target\":\"ReceiveLog\",\"arguments\":[\"line-$sequence\"]}$SIGNALR_SEPARATOR"))
                    if (sequence == 1) {
                        val command = signalRObject((incoming.receive() as Frame.Text).readText().removeSuffix("$SIGNALR_SEPARATOR"))
                        commands.send(command["arguments"]!!.jsonArray[1].jsonPrimitive.content)
                        send(Frame.Text("{\"type\":1,\"target\":\"CommandResult\",\"arguments\":[{\"success\":true,\"message\":\"ok\"}]}$SIGNALR_SEPARATOR"))
                        close()
                    } else {
                        incoming.receiveCatching()
                    }
                }
            }
        }.start(wait = false)
        val http = HttpClient(CIO)
        try {
            val port = server.resolvedConnectors().single().port
            val console = DaemonConsole("http://127.0.0.1:$port", "test-key", http, allowHttp = true)
            val job = launch { console.run(42) { events.send(it) } }
            try {
                withTimeout(15_000) {
                    assertEquals(ConsoleEvent.Connected, events.receive())
                    assertEquals(ConsoleEvent.Log("line-1"), events.receive())
                    console.sendCommand("say hello")
                    assertEquals("say hello", commands.receive())
                    assertEquals(ConsoleEvent.CommandResult(true, "ok"), events.receive())
                    assertEquals(ConsoleEvent.Disconnected, events.receive())
                    assertEquals(ConsoleEvent.Connected, events.receive())
                    assertEquals(ConsoleEvent.Log("line-2"), events.receive())
                    assertTrue(connections.get() >= 2)
                }
            } finally { job.cancelAndJoin() }
        } finally {
            http.close()
            server.stop(100, 1_000)
        }
    }

    @Test
    fun creationProgressTracksServerAndReceivesTypedUpdate() = runBlocking {
        val events = Channel<CreationProgressEvent>(Channel.UNLIMITED)
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                post("/api/hubs/creationProgressHub/negotiate") {
                    assertEquals("test-key", call.request.header("x-api-key"))
                    call.respondText(
                        """{"negotiateVersion":1,"connectionToken":"token","availableTransports":[{"transport":"WebSockets","transferFormats":["Text"]}]}""",
                        ContentType.Application.Json,
                    )
                }
                webSocket("/api/hubs/creationProgressHub") {
                    assertEquals("token", call.request.queryParameters["id"])
                    assertEquals("{\"protocol\":\"json\",\"version\":1}$SIGNALR_SEPARATOR", (incoming.receive() as Frame.Text).readText())
                    send(Frame.Text("{}$SIGNALR_SEPARATOR"))
                    val track = signalRObject((incoming.receive() as Frame.Text).readText().removeSuffix("$SIGNALR_SEPARATOR"))
                    assertEquals("TrackServer", track["target"]?.jsonPrimitive?.content)
                    assertEquals("server-42", track["arguments"]?.jsonArray?.first()?.jsonPrimitive?.content)
                    send(Frame.Text("{\"type\":1,\"target\":\"StatusUpdate\",\"arguments\":[\"server-42\",\"downloading\",0.5]}$SIGNALR_SEPARATOR"))
                    incoming.receiveCatching()
                }
            }
        }.start(wait = false)
        val http = HttpClient(CIO)
        try {
            val port = server.resolvedConnectors().single().port
            val progress = DaemonCreationProgress("http://127.0.0.1:$port", "test-key", http, allowHttp = true)
            val job = launch { progress.run("server-42") { events.send(it) } }
            try {
                withTimeout(10_000) {
                    assertEquals(CreationProgressEvent.Connected, events.receive())
                    assertEquals(CreationProgressEvent.Update("server-42", "downloading", 0.5), events.receive())
                }
            } finally { job.cancelAndJoin() }
        } finally {
            http.close()
            server.stop(100, 1_000)
        }
    }

    @Test
    fun systemMonitorDecodesNestedStatsObject() = runBlocking {
        val events = Channel<SystemMonitorEvent>(Channel.UNLIMITED)
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                post("/api/hubs/system/negotiate") {
                    call.respondText(
                        """{"negotiateVersion":1,"connectionToken":"token","availableTransports":[{"transport":"WebSockets","transferFormats":["Text"]}]}""",
                        ContentType.Application.Json,
                    )
                }
                webSocket("/api/hubs/system") {
                    incoming.receive()
                    send(Frame.Text("{}$SIGNALR_SEPARATOR"))
                    val join = signalRObject((incoming.receive() as Frame.Text).readText().removeSuffix("$SIGNALR_SEPARATOR"))
                    assertEquals("JoinMonitor", join["target"]?.jsonPrimitive?.content)
                    send(Frame.Text("{\"type\":1,\"target\":\"ReceiveSystemStats\",\"arguments\":[{\"local\":{\"cpu\":21.5,\"memTotal\":2048,\"memUsed\":1024,\"memUsage\":50,\"timestamp\":\"now\"}}]}$SIGNALR_SEPARATOR"))
                    incoming.receiveCatching()
                }
            }
        }.start(wait = false)
        val http = HttpClient(CIO)
        try {
            val port = server.resolvedConnectors().single().port
            val monitor = DaemonSystemMonitor("http://127.0.0.1:$port", "test-key", http, allowHttp = true)
            val job = launch { monitor.run { events.send(it) } }
            try {
                withTimeout(10_000) {
                    assertEquals(SystemMonitorEvent.Connected, events.receive())
                    assertEquals(SystemMonitorEvent.Stats(21.5, 2048.0, 1024.0, 50.0, "now"), events.receive())
                }
            } finally { job.cancelAndJoin() }
        } finally {
            http.close()
            server.stop(100, 1_000)
        }
    }
}
