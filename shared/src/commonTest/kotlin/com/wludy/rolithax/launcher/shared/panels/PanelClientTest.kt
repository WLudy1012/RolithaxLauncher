package com.wludy.rolithax.launcher.shared.panels

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PanelClientTest {
    @Test
    fun mcsmUsesAssignedInstancesForOrdinaryUserAndEncodesCommands() = runTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            val path = request.url.encodedPath
            requests += path
            assertEquals("fixture-key", request.url.parameters["apikey"])
            assertEquals("XMLHttpRequest", request.headers["X-Requested-With"])
            assertNull(request.headers[HttpHeaders.Authorization])
            when (path) {
                "/control/api/auth" -> {
                    assertEquals("true", request.url.parameters["advanced"])
                    respond("""{"status":200,"data":{"permission":1,"instances":[
                        {"instanceUuid":"server-a","daemonId":"node-a","nickname":"Survival","status":3},
                        {"instanceUuid":"server-b","daemonId":"node-b","nickname":"Creative","status":0}
                    ]}}""")
                }
                "/control/api/protected_instance/command" -> {
                    assertEquals("server-a", request.url.parameters["uuid"])
                    assertEquals("node-a", request.url.parameters["daemonId"])
                    assertEquals("say hi & test+", request.url.parameters["command"])
                    respond("""{"status":200,"data":true}""")
                }
                else -> error("Unexpected fixture path")
            }
        })
        val client = McsmPanelClient(profile(PanelKind.MCSM, nodeId = "node-a"), http)
        try {
            val server = client.servers().single()
            assertEquals(PanelServer("server-a", "Survival", "运行中", "node-a"), server)
            client.command(server, "say hi & test+")
            assertEquals(listOf("/control/api/auth", "/control/api/protected_instance/command"), requests)
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun mcsmAdminPaginatesOnlineNodes() = runTest {
        val pages = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/control/api/auth" -> respond("""{"status":200,"data":{"permission":10}}""")
                "/control/api/service/remote_services_list" -> respond("""{"status":200,"data":[
                    {"uuid":"node-a","available":true},{"uuid":"offline","available":false}
                ]}""")
                "/control/api/service/remote_service_instances" -> {
                    assertEquals("50", request.url.parameters["page_size"])
                    assertEquals("node-a", request.url.parameters["daemonId"])
                    assertNull(request.url.parameters["status"])
                    val page = requireNotNull(request.url.parameters["page"])
                    pages += page
                    respond("""{"status":200,"data":{
                        "page":$page,"maxPage":2,"data":[
                            {"instanceUuid":"server-$page","config":{"nickname":"Server $page"},"status":0}
                        ]
                    }}""")
                }
                else -> error("Unexpected fixture path")
            }
        })
        val client = McsmPanelClient(profile(PanelKind.MCSM), http)
        try {
            assertEquals(listOf("Server 1", "Server 2"), client.servers().map(PanelServer::name))
            assertEquals(listOf("1", "2"), pages)
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun pterodactylUsesClientBearerAndPostsCommandAndPower() = runTest {
        val pages = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val powers = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            assertEquals(listOf("Bearer fixture-key"), request.headers.getAll(HttpHeaders.Authorization))
            assertNull(request.url.parameters["apikey"])
            when (request.url.encodedPath) {
                "/control/api/client" -> {
                    val page = requireNotNull(request.url.parameters["page"])
                    pages += page
                    respond("""{"object":"list","data":[{"object":"server","attributes":{
                        "identifier":"abc-$page","name":"World $page","description":"Fixture"
                    }}],"meta":{"pagination":{"total_pages":2,"current_page":$page}}}""")
                }
                "/control/api/client/servers/abc-1/resources" ->
                    respond("""{"object":"stats","attributes":{"current_state":"running"}}""")
                "/control/api/client/servers/abc-2/resources" ->
                    respond("", HttpStatusCode.Forbidden)
                "/control/api/client/servers/abc-1/command", "/control/api/client/servers/abc-1/power" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    val data = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    data["command"]?.jsonPrimitive?.content?.let(commands::add)
                    data["signal"]?.jsonPrimitive?.content?.let(powers::add)
                    respond("", HttpStatusCode.NoContent)
                }
                else -> error("Unexpected fixture path")
            }
        })
        val client = PterodactylPanelClient(profile(PanelKind.PTERODACTYL), http)
        try {
            val servers = client.servers()
            assertEquals(listOf("1", "2"), pages)
            assertEquals(listOf("运行中", "状态读取失败"), servers.map(PanelServer::state))
            client.command(servers.first(), "say hello")
            client.power(servers.first(), PanelPower.RESTART)
            assertEquals(listOf("say hello"), commands)
            assertEquals(listOf("restart"), powers)
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun credentialRequestsRejectRedirectsAndDoNotExposeTransportDetails() = runTest {
        for (kind in listOf(PanelKind.MCSM, PanelKind.PTERODACTYL)) {
            var requests = 0
            val http = HttpClient(MockEngine {
                requests++
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.example/"))
            })
            val api = PanelHttp(profile(kind), http)
            try {
                val error = assertFailsWith<PanelRequestException> { api.request("api/check") }
                assertEquals("${kind.title} 请求失败（HTTP 302）", error.message)
                assertEquals(1, requests)
            } finally {
                api.close()
                http.close()
            }
        }

        val http = HttpClient(MockEngine { request ->
            throw IllegalStateException("Failure at ${request.url}")
        })
        val api = PanelHttp(profile(PanelKind.MCSM), http)
        try {
            val error = assertFailsWith<PanelRequestException> { api.request("api/check") }
            assertFalse(error.stackTraceToString().contains("fixture-key"))
        } finally {
            api.close()
            http.close()
        }
    }

    @Test
    fun invalidPayloadAndUnexpectedPterodactylCommandResponseFail() = runTest {
        val http = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Get) respond("[]") else respond("{}")
        })
        val client = PterodactylPanelClient(profile(PanelKind.PTERODACTYL), http)
        try {
            assertFailsWith<PanelRequestException> { client.servers() }
            assertFailsWith<PanelRequestException> { client.command(PanelServer("abc-1", "World", ""), "say hello") }
        } finally {
            client.close()
            http.close()
        }
    }

    @Test
    fun pterodactylOriginRetainsOnlyNonDefaultPort() {
        assertEquals("https://panel.example", pterodactylOrigin("https://panel.example:443/control"))
        assertEquals("https://panel.example:8443", pterodactylOrigin("https://panel.example:8443/control"))
        assertEquals("https://[2001:db8::1]:8443", pterodactylOrigin("https://[2001:db8::1]:8443/control"))
    }

    private fun profile(kind: PanelKind, nodeId: String = "") =
        PanelProfile("id", "Fixture", kind, "https://panel.example/control", "fixture-key", nodeId = nodeId)
}
