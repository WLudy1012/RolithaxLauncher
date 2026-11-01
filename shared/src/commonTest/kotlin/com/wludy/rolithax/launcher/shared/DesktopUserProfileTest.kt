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

class DesktopUserProfileTest {
    @Test
    fun profileUsesCurrentUserEndpointAndIgnoresUnknownFields() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/user/me", request.url.encodedPath)
            assertEquals("fixture-key", request.headers["x-api-key"])
            respond("""{"code":200,"data":{"username":"alex","name":"Alex","role":"user","future":true}}""")
        })
        try {
            val client = DaemonClient("https://daemon.example", "fixture-key", http)
            try { assertEquals(DaemonProfile("alex", "Alex", null, "user"), client.currentProfile()) }
            finally { client.close() }
        } finally { http.close() }
    }

    @Test
    fun passwordChangeRequiresCurrentPasswordBeforeRequest() = runTest {
        var requests = 0
        val http = HttpClient(MockEngine { request ->
            requests++
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/user/me/update", request.url.encodedPath)
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("alex", body.getValue("username").jsonPrimitive.content)
            assertEquals("old", body.getValue("oldPassword").jsonPrimitive.content)
            assertEquals("new", body.getValue("password").jsonPrimitive.content)
            assertEquals("false", body.getValue("resetApiKey").jsonPrimitive.content)
            respond("""{"code":200}""")
        })
        try {
            val client = DaemonClient("https://daemon.example", "fixture-key", http)
            try {
                assertFailsWith<IllegalArgumentException> { client.updateProfile(null, null, null, null, "new") }
                assertEquals(0, requests)
                client.updateProfile("alex", null, null, "old", "new")
                assertEquals(1, requests)
            } finally { client.close() }
        } finally { http.close() }
    }
}
