package com.wludy.rolithax.launcher.data.resources

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class ModrinthVersionFilterTest {
    @Test
    fun v3QueryAndLocalCompatibilityBothFilterVersions() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[
                ${entry("supported", "1.21", "fabric", "client_and_server")},
                ${entry("old", "1.20", "fabric", "client_and_server")},
                ${entry("forge", "1.21", "forge", "client_and_server")},
                ${entry("client", "1.21", "fabric", "singleplayer_only")}
            ]"""))
            val filter = ResourceFilter("mod", gameVersion = "1.21", loader = "Fabric", serverOnly = true)
            val result = provider(server).versions("project", filter)
            val url = requireNotNull(server.takeRequest().requestUrl)
            assertEquals("/project/project/version", url.encodedPath)
            assertEquals("""{"game_versions":["1.21"]}""", url.queryParameter("loader_fields"))
            assertEquals("""["fabric"]""", url.queryParameter("loaders"))
            assertEquals("false", url.queryParameter("include_changelog"))
            assertEquals(listOf("supported"), result.map { it.id })
        }
    }

    @Test
    fun modpacksUseThePackLoaderInsteadOfTheMrpackFormat() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{
                "id":"pack", "game_versions":["1.21"], "loaders":["mrpack"],
                "mrpack_loaders":["fabric"], "files":[$file]
            }]"""))
            val result = provider(server).versions("pack", ResourceFilter("modpack", gameVersion = "1.21", loader = "fabric"))
            val url = requireNotNull(server.takeRequest().requestUrl)
            assertEquals("""{"game_versions":["1.21"],"mrpack_loaders":["fabric"]}""", url.queryParameter("loader_fields"))
            assertNull(url.queryParameter("loaders"))
            assertEquals(listOf("fabric"), result.single().loaders)
        }
    }

    private fun provider(server: MockWebServer): ModrinthResourceProvider = ModrinthResourceProvider(
        Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(GsonConverterFactory.create())
            .build().create(ModrinthApi::class.java),
    )

    private fun entry(id: String, version: String, loader: String, environment: String): String = """{
        "id":"$id", "game_versions":["$version"], "loaders":["$loader"],
        "environment":"$environment", "files":[$file]
    }"""

    private val file = """{"filename":"example.jar","url":"https://cdn.modrinth.com/example.jar","hashes":{"sha256":"${"a".repeat(64)}"}}"""
}
