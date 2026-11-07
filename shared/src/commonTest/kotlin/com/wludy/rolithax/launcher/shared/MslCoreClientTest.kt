package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MslCoreClientTest {
    @Test
    fun parsesObjectAndArrayResponsesAndRequiresDownloadHash() = runTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            requests += request.url.encodedPath
            assertTrue(request.headers["User-Agent"].orEmpty().contains("Rolithax Launcher"))
            when (request.url.encodedPath) {
                "/v4/mirrors" -> respond("""{"code":200,"data":[{"vanillaCore":["Vanilla"],"bedrockCore":["Bedrock"]}]}""")
                "/v4/mirrors/Vanilla" -> respond("""{"code":200,"data":{"versions":["1.20.1"]}}""")
                "/v4/mirrors/Vanilla/1.20.1" -> respond("""{"code":200,"data":["latest"]}""")
                "/v4/download/server/Vanilla/1.20.1" -> respond("""{"code":200,"data":{"url":"https://example.invalid/server.jar","sha256":"short"}}""")
                else -> error("Unexpected request")
            }
        })
        val client = MslCoreClient(http)
        try {
            assertEquals(listOf("Vanilla", "Bedrock"), client.cores())
            assertTrue(client.isBedrock("Bedrock"))
            assertEquals(listOf("1.20.1"), client.versions("Vanilla"))
            assertEquals(listOf("latest"), client.builds("Vanilla", "1.20.1"))
            assertFailsWith<IllegalArgumentException> { client.download("Vanilla", "1.20.1", "latest") }
            assertFalse(requests.isEmpty())
        } finally { client.close(); http.close() }
    }
}
