package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class DaemonClientTest {
    @Test
    fun listUsesEnvelopeAndAuthentication() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("/api/instance/list", request.url.encodedPath)
            assertEquals("test-key", request.headers["x-api-key"])
            respond("""{"code":200,"data":[{"id":17,"name":"world","status":2}]}""")
        })
        try {
            val instances = DaemonClient("https://daemon.example", "test-key", http).listInstances()
            assertEquals(17L, instances.single().id)
            assertEquals(2, instances.single().status)
        } finally { http.close() }
    }

    @Test
    fun apiFailureDoesNotExposeKey() = runTest {
        val http = HttpClient(MockEngine {
            respond("""{"code":403,"message":"invalid test-key"}""", HttpStatusCode.Forbidden)
        })
        try {
            val error = assertFailsWith<DaemonException> {
                DaemonClient("https://daemon.example", "test-key", http).listInstances()
            }
            assertFalse(error.message.orEmpty().contains("test-key"))
        } finally { http.close() }
    }

    @Test
    fun redirectIsRejected() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine {
            requests++
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.example/"))
        })
        try {
            assertFailsWith<DaemonException> {
                DaemonClient("https://daemon.example", "test-key", http).listInstances()
            }
            assertEquals(1, requests)
        } finally { http.close() }
    }

    @Test
    fun insecureHttpRequiresExplicitPermission() {
        assertFailsWith<IllegalArgumentException> { normalizeDaemonUrl("http://daemon.example") }
        assertEquals("http://daemon.example", normalizeDaemonUrl("http://daemon.example/", allowHttp = true))
        assertFailsWith<IllegalArgumentException> { normalizeDaemonUrl("https://user@daemon.example") }
    }

    @Test
    fun missingListAndOversizedResponsesFail() = runTest {
        val emptyData = HttpClient(MockEngine { respond("""{"code":200,"data":null}""") })
        try {
            assertFailsWith<DaemonException> {
                DaemonClient("https://daemon.example", "", emptyData).listInstances()
            }
        } finally { emptyData.close() }

        val oversized = HttpClient(MockEngine { respond("x".repeat(4 * 1024 * 1024 + 1)) })
        try {
            assertFailsWith<DaemonException> {
                DaemonClient("https://daemon.example", "", oversized).listInstances()
            }
        } finally { oversized.close() }
    }

    @Test
    fun managementRequestsIncludeDefaultsAndOmitNulls() = runTest {
        val http = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("alice", body["username"]?.jsonPrimitive?.content)
            assertEquals("user", body["role"]?.jsonPrimitive?.content)
            assertEquals(0, body["resources"]?.jsonArray?.size)
            assertFalse(body.containsKey("name"))
            respond("""{"code":200,"data":null}""")
        })
        try {
            DaemonClient("https://daemon.example", "", http).createUser("alice", "password", null, "user")
        } finally { http.close() }
    }

    @Test
    fun resourceInstallAccepts202WithoutChangingOtherEndpoints() = runTest {
        val http = HttpClient(MockEngine { request ->
            if (request.url.encodedPath.endsWith("/resources/install")) {
                respond("""{"code":202,"data":{"id":"task-1","status":"running","progress":0,"message":"started"}}""", HttpStatusCode.Accepted)
            } else {
                respond("""{"code":202,"data":null}""", HttpStatusCode.Accepted)
            }
        })
        try {
            val client = DaemonClient("https://daemon.example", "", http)
            val task = client.installResources(1, "1.20.1", "fabric", listOf(DaemonResourceInstallItem("p", "v", "mod")))
            assertEquals("task-1", task.id)
            assertFailsWith<DaemonException> { client.action(1, "start") }
            client.close()
        } finally { http.close() }
    }

    @Test
    fun androidConsoleActionsAreSentAsJsonValues() = runTest {
        val actions = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            actions += body["action"]!!.jsonPrimitive.content
            respond("""{"code":200}""")
        })
        try {
            val client = DaemonClient("https://daemon.example", "", http)
            client.action(42, "forceExit")
            client.action(42, "agreeEula?true")
            client.action(42, "backup")
            assertEquals(listOf("forceExit", "agreeEula?true", "backup"), actions)
            client.close()
        } finally { http.close() }
    }
}
