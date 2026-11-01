package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModrinthClientTest {
    @Test
    fun searchParsesBothV3FieldShapesWithoutDaemonCredentials() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("api.modrinth.com", request.url.host)
            assertNull(request.headers["x-api-key"])
            respond("""{"hits":[{"project_id":"one","name":"New name","summary":"New summary"},{"id":"two","title":"Old title","description":"Old description"}]}""")
        })
        val client = ModrinthClient(http)
        try {
            val projects = client.search("test", "mod", "1.20.1", "fabric")
            assertEquals(listOf("one", "two"), projects.map { it.id })
            assertEquals(listOf("New name", "Old title"), projects.map { it.title })
            assertEquals(listOf("New summary", "Old description"), projects.map { it.description })
        } finally { client.close(); http.close() }
    }
    @Test
    fun searchAppliesOnlyCompatibleLoaderFacets() = runTest {
        val facets = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            facets += request.url.parameters["facets"].orEmpty()
            respond("""{"hits":[]}""")
        })
        val client = ModrinthClient(http)
        try {
            client.search("", "mod", "1.20.1", "fabric")
            client.search("", "plugin", "1.20.1", "fabric")
            kotlin.test.assertTrue(facets[0].contains("categories:fabric"))
            kotlin.test.assertFalse(facets[1].contains("categories:fabric"))
        } finally { client.close(); http.close() }
    }

}
