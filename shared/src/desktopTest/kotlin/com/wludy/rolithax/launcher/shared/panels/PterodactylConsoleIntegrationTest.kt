package com.wludy.rolithax.launcher.shared.panels

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PterodactylConsoleIntegrationTest {
    @Test
    fun separateWingsOriginReceivesRenewableJwtWithoutThePanelKey() = runBlocking {
        val panelOrigin = Channel<String>(1)
        val received = Channel<String>(2)
        val historyRequests = AtomicInteger()
        val wing = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            install(WebSockets)
            routing {
                webSocket("/api/servers/server-a/ws") {
                    assertNull(call.request.header(HttpHeaders.Authorization))
                    assertNull(call.request.queryParameters["apikey"])
                    assertEquals(panelOrigin.receive(), call.request.header(HttpHeaders.Origin))
                    suspend fun readRequest(): JsonObject {
                        val request = Json.parseToJsonElement((incoming.receive() as Frame.Text).readText()).jsonObject
                        if (request["event"]!!.jsonPrimitive.content == "send logs") historyRequests.incrementAndGet()
                        return request
                    }
                    val initial = readRequest()
                    assertEquals("auth", initial["event"]!!.jsonPrimitive.content)
                    received.send(initial["args"]!!.jsonArray.single().jsonPrimitive.content)
                    send(Frame.Text("""{"event":"auth success","args":[]}"""))
                    val request = readRequest()
                    assertEquals("send logs", request["event"]!!.jsonPrimitive.content)
                    send(Frame.Text("""{"event":"console output","args":["Hello from Wings"]}"""))
                    for (event in listOf("token expiring", "token expired")) {
                        send(Frame.Text("""{"event":"$event","args":[]}"""))
                        val renew = readRequest()
                        assertEquals("auth", renew["event"]!!.jsonPrimitive.content)
                        received.send(renew["args"]!!.jsonArray.single().jsonPrimitive.content)
                        send(Frame.Text("""{"event":"auth success","args":[]}"""))
                    }
                    send(Frame.Text("""{"event":"console output","args":["Live after renewal"]}"""))
                    incoming.receiveCatching()
                }
            }
        }.start(wait = false)
        val requests = AtomicInteger()
        val wingPort = wing.resolvedConnectors().single().port
        val panel = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            routing {
                get("/api/client/servers/abc-1/websocket") {
                    assertEquals("Bearer fixture-key", call.request.header(HttpHeaders.Authorization))
                    val count = requests.incrementAndGet()
                    call.respondText(
                        """{"data":{"token":"wings-jwt-$count","socket":"ws://127.0.0.1:$wingPort/api/servers/server-a/ws"}}""",
                        ContentType.Application.Json,
                    )
                }
            }
        }.start(wait = false)
        val http = HttpClient(CIO)
        val base = "http://127.0.0.1:${panel.resolvedConnectors().single().port}"
        val client = PterodactylPanelClient(
            PanelProfile("id", "Fixture", PanelKind.PTERODACTYL, base, "fixture-key", allowHttp = true),
            http,
        )
        val logs = Channel<String>(1)
        try {
            panelOrigin.send(base)
            val job = launch { client.logs(PanelServer("abc-1", "Fixture", "")) { logs.send(it) } }
            try {
                withTimeout(5_000) {
                    assertEquals("wings-jwt-1", received.receive())
                    assertEquals("Hello from Wings\n", logs.receive())
                    assertEquals("wings-jwt-2", received.receive())
                    assertEquals("wings-jwt-3", received.receive())
                    assertEquals("Hello from Wings\nLive after renewal\n", logs.receive())
                    assertEquals(3, requests.get())
                    assertEquals(1, historyRequests.get())
                }
            } finally {
                job.cancelAndJoin()
            }
        } finally {
            client.close()
            http.close()
            panel.stop(100, 1_000)
            wing.stop(100, 1_000)
        }
    }
}
