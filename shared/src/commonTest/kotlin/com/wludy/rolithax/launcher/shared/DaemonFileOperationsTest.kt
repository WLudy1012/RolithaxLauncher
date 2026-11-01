package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DaemonFileOperationsTest {
    @Test
    fun requestsMatchDaemonAndPreserveDeleteResult() = runTest {
        val expected = listOf(
            "directory" to """{"path":"world","name":"new"}""",
            "rename" to """{"oldPath":"world/a.txt","newPath":"world/b.txt"}""",
            "delete" to """{"paths":["world/b.txt"]}""",
        )
        var count = 0
        val http = HttpClient(MockEngine { request ->
            val (path, body) = expected[count++]
            assertEquals("/api/files/instance/42/$path", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals(Json.parseToJsonElement(body).jsonObject, Json.parseToJsonElement((request.body as TextContent).text).jsonObject)
            respond("""{"code":200,"message":"result","data":null}""")
        })
        val client = DaemonClient("https://daemon.example", "test-key", http)
        try {
            client.createDirectory(42, "world", "new")
            client.renameRemoteFile(42, "world/a.txt", "world/b.txt")
            assertEquals("result", client.deleteRemoteFile(42, "world/b.txt"))
            assertEquals(3, count)
        } finally { client.close(); http.close() }
    }

    @Test
    fun unsafePathsAreRejectedBeforeRequest() = runTest {
        val http = HttpClient(MockEngine { error("Unexpected request") })
        val client = DaemonClient("https://daemon.example", "test-key", http)
        try {
            assertFailsWith<IllegalArgumentException> { client.createDirectory(1, "", "../outside") }
            assertFailsWith<IllegalArgumentException> { client.renameRemoteFile(1, "a", "../b") }
            assertFailsWith<IllegalArgumentException> { client.deleteRemoteFile(1, "") }
        } finally { client.close(); http.close() }
    }
}
