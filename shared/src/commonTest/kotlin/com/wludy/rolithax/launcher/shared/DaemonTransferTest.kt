package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.core.readBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DaemonTransferTest {
    @Test
    fun downloadUsesAuthAndStream() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/files/instance/17/download", request.url.encodedPath)
            assertEquals("mods/example.jar", request.url.parameters["path"])
            assertEquals("test-key", request.headers["x-api-key"])
            respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/octet-stream"))
        })
        val client = DaemonClient("https://daemon.example", "test-key", http)
        try {
            client.downloadFile(17, "mods/example.jar") { channel, _ -> assertContentEquals(byteArrayOf(1, 2, 3), channel.readRemaining().readBytes()) }
        } finally { client.close(); http.close() }
    }

    @Test
    fun unsafeRemotePathIsRejectedBeforeRequest() = runTest {
        val http = HttpClient(MockEngine { error("Unexpected request") })
        val client = DaemonClient("https://daemon.example", "test-key", http)
        try {
            assertFailsWith<IllegalArgumentException> { client.downloadFile(17, "../outside") { _, _ -> } }
            assertFailsWith<IllegalArgumentException> { client.saveUpload(17, "abc", "../outside", "") }
            assertFailsWith<IllegalArgumentException> { client.uploadChunk("../id", 0, byteArrayOf()) }
        } finally { client.close(); http.close() }
    }

    @Test
    fun uploadUsesChunkEndpointAndMultipart() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/files/upload/chunk/abc", request.url.encodedPath)
            assertEquals("test-key", request.headers["x-api-key"])
            kotlin.test.assertTrue(request.body.contentType.toString().startsWith("multipart/form-data"))
            respond("""{"code":200,"data":null}""")
        })
        val client = DaemonClient("https://daemon.example", "test-key", http)
        try { client.uploadChunk("abc", 0, byteArrayOf(1, 2, 3)) }
        finally { client.close(); http.close() }
    }
}
