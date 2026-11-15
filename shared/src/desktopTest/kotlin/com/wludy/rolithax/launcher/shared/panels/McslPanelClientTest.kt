package com.wludy.rolithax.launcher.shared.panels

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCio
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class McslPanelClientTest {
    @Test
    fun acceptsTheV2PathAndRequiresExplicitPlaintext() {
        assertEquals("wss://example.test:11452/api/v2", mcslEndpoint("example.test:11452", false).toString())
        assertEquals("ws://example.test:11452/api/v2", mcslEndpoint("http://example.test:11452/api/v2/", true).toString())
        assertFailsWith<IllegalArgumentException> { mcslEndpoint("ws://example.test:11452", false) }
        assertFailsWith<IllegalArgumentException> { mcslEndpoint("wss://example.test/api/v2?token=wrong", false) }
        assertFailsWith<IllegalArgumentException> { mcslEndpoint("wss://example.test/api/v1", false) }
    }

    @Test
    fun sendsV2RpcRequestsAndReadsReportsAndLogs() = runBlocking {
        val calls = Channel<String>(Channel.UNLIMITED)
        val token = "test+token&value"
        val instanceId = "848b99bd-1ebd-4a28-8a16-be59c2cbce25"
        val host = embeddedServer(ServerCio, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocket("/api/v2") {
                    assertEquals(token, call.request.queryParameters["token"])
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val request = Json.parseToJsonElement(frame.readText()).jsonObject
                        val id = request.getValue("id").jsonPrimitive.content
                        val method = request.getValue("method").jsonPrimitive.content
                        val params = request.getValue("params").jsonObject
                        if (method != "mcsl.instance.report.list") {
                            assertEquals(instanceId, params.getValue("instance_id").jsonPrimitive.content)
                        }
                        if (method == "mcsl.instance.command.send") {
                            assertEquals("say hello", params.getValue("command").jsonPrimitive.content)
                        }
                        calls.send(method)
                        val result = when (method) {
                            "mcsl.instance.report.list" ->
                                """{"reports":{"$instanceId":{"status":"running","config":{"name":"Survival"}}}}"""
                            "mcsl.instance.log.get" -> """{"logs":["first","second"]}"""
                            else -> "{}"
                        }
                        val response = buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", id)
                            put("result", Json.parseToJsonElement(result))
                        }
                        outgoing.send(Frame.Text(response.toString()))
                    }
                }
            }
        }.start(wait = false)
        val port = host.resolvedConnectors().single().port
        val http = HttpClient(CIO)
        val client = McslPanelClient(PanelProfile("test", "Test", PanelKind.MCSL, "ws://127.0.0.1:$port", token, true), http)
        try {
            withTimeout(7_000) {
                val server = client.servers().single()
                assertEquals(PanelServer(instanceId, "Survival", "运行中"), server)
                client.power(server, PanelPower.START)
                client.command(server, "say hello")
                val snapshots = Channel<String>(Channel.UNLIMITED)
                val logs = launch { client.logs(server) { snapshots.send(it) } }
                try {
                    assertEquals("first\nsecond", snapshots.receive())
                    assertEquals(
                        listOf("mcsl.instance.report.list", "mcsl.instance.start", "mcsl.instance.command.send", "mcsl.instance.log.get"),
                        List(4) { calls.receive() },
                    )
                } finally {
                    logs.cancelAndJoin()
                }
            }
        } finally {
            client.close()
            http.close()
            host.stop(100, 1_000)
        }
    }
}
