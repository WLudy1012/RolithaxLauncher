package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DaemonManagementTest {
    @Test
    fun deleteSendsExplicitFileChoiceInJsonBody() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/api/instance/delete", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("42", body.getValue("id").jsonPrimitive.content)
            assertEquals("false", body.getValue("deleteFiles").jsonPrimitive.content)
            respond("""{"code":200,"data":null}""")
        })
        val client = DaemonClient("https://daemon.example", "test-key", http)
        try { client.deleteInstance(42) } finally { client.close(); http.close() }
    }

    @Test
    fun settingsRoundTripPreservesUnknownFields() = runTest {
        var count = 0
        val http = HttpClient(MockEngine { request ->
            assertEquals("/api/instance/settings/general/42", request.url.encodedPath)
            if (count++ == 0) respond("""{"code":200,"data":{"id":42,"futureField":{"enabled":true}}}""")
            else {
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertEquals("true", body.getValue("futureField").jsonObject.getValue("enabled").jsonPrimitive.content)
                respond("""{"code":200,"data":{}}""")
            }
        })
        val client = DaemonClient("https://daemon.example", "test-key", http)
        try { client.updateInstanceSettings(42, client.instanceSettings(42)) } finally { client.close(); http.close() }
    }

    @Test
    fun createAndPluginsFollowDaemonContract() = runTest {
        var count = 0
        val http = HttpClient(MockEngine { request ->
            when (count++) {
                0 -> {
                    assertEquals("/api/instance/createServer", request.url.encodedPath)
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("world", body.getValue("name").jsonPrimitive.content)
                    assertEquals("server.jar", body.getValue("core").jsonPrimitive.content)
                    respond("""{"code":200,"data":{"serverId":"task-1"}}""")
                }
                1 -> {
                    assertEquals("/api/files/pm/instance/42/list", request.url.encodedPath)
                    assertEquals("mods", request.url.parameters["mode"])
                    respond("""{"code":200,"data":{"disableJarFiles":["test.jar"]}}""")
                }
                else -> {
                    assertEquals("/api/files/pm/instance/42/set", request.url.encodedPath)
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("enable", body.getValue("action").jsonPrimitive.content)
                    assertEquals("mods", body.getValue("mode").jsonPrimitive.content)
                    respond("""{"code":200,"data":null}""")
                }
            }
        })
        val client = DaemonClient("https://daemon.example", "test-key", http)
        try {
            assertEquals("task-1", client.createInstance(DaemonCreateRequest("world", "server.jar", 1024, 4096)))
            assertEquals(listOf("test.jar"), client.listPluginsMods(42, "mods").disableJarFiles)
            client.setPluginsMods(42, "mods", "enable", listOf("test.jar"))
        } finally { client.close(); http.close() }
    }

    @Test
    fun remoteCoreWithoutCompleteSha256IsRejectedBeforeRequest() = runTest {
        val http = HttpClient(MockEngine { error("Unexpected request") })
        val client = DaemonClient("https://daemon.example", "test-key", http)
        try {
            assertFailsWith<IllegalArgumentException> {
                client.createInstance(DaemonCreateRequest("world", "server.jar", 1024, 4096, coreUrl = "https://example.com/server.jar", coreSha256 = "1234"))
            }
        } finally { client.close(); http.close() }
    }

    @Test
    fun unsafeUserPathIsRejectedBeforeRequest() = runTest {
        val http = HttpClient(MockEngine { error("Unexpected request") })
        val client = DaemonClient("https://daemon.example", "test-key", http)
        try { assertFailsWith<IllegalArgumentException> { client.deleteUser("../other") } }
        finally { client.close(); http.close() }
    }
    @Test
    fun userUpdatePreservesResourceGrantsAndExplicitKeyReset() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/api/admin/user/update/user-1", request.url.encodedPath)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("true", body.getValue("resetApiKey").jsonPrimitive.content)
            assertEquals("[\"server:17\",\"frp:3\"]", body.getValue("resources").toString())
            respond("""{"code":200,"data":null}""")
        })
        val client = DaemonClient("https://daemon.example", "test-key", http)
        try { client.updateUser("user-1", "name", null, "user", listOf("server:17", "frp:3"), true) }
        finally { client.close(); http.close() }
    }

}
