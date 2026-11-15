package com.wludy.rolithax.launcher.data.resources

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class CurseForgeResourceProviderTest {
    @Test
    fun searchPassesVersionLoaderAndPageToTheOfficialRoute() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""
                {"data":[{"id":31,"classId":6,"name":"Example mod","downloadCount":50,
                "latestFilesIndexes":[{"gameVersion":"1.21.4","modLoader":6}]}],
                "pagination":{"resultCount":1,"totalCount":24}}
            """.trimIndent()))
            val provider = CurseForgeResourceProvider(createApi(server)) { "fixture-key" }
            val page = provider.search(ResourceFilter("mod", "rail line", "1.21.4", "neoforge"), 20, 20, null)
            val request = server.takeRequest()
            val url = requireNotNull(request.requestUrl)
            assertEquals("/v1/mods/search", url.encodedPath)
            assertEquals("432", url.queryParameter("gameId"))
            assertEquals("6", url.queryParameter("classId"))
            assertEquals("rail line", url.queryParameter("searchFilter"))
            assertEquals("1.21.4", url.queryParameter("gameVersion"))
            assertEquals("6", url.queryParameter("modLoaderType"))
            assertEquals("20", url.queryParameter("index"))
            assertEquals("20", url.queryParameter("pageSize"))
            assertEquals(listOf("fixture-key"), request.headers.values("x-api-key"))
            assertEquals(24, page.totalHits)
            assertEquals(1, page.resultCount)
            assertEquals("31", page.projects.single().id)
            assertEquals(listOf("neoforge"), page.projects.single().loaders)
        }
    }

    @Test
    fun versionRequiresAnOfficialDownloadAndVerifiedHash() {
        val sha1 = "b".repeat(40)
        val file = CurseForgeFile(
            id = 350,
            displayName = "Mod 2.0",
            fileName = "mod.jar",
            downloadUrl = "https://edge.forgecdn.net/files/1/350/mod.jar",
            fileLength = 700,
            isAvailable = true,
            gameVersions = listOf("26.1", "NeoForge", "Java 25"),
            hashes = listOf(CurseForgeHash(1, sha1)),
            dependencies = listOf(CurseForgeDependency(12, 3), CurseForgeDependency(21, 5)),
        )
        val version = requireNotNull(file.toDomain())
        assertEquals("sha1:$sha1", version.files.single().sha512)
        assertEquals(listOf("26.1"), version.gameVersions)
        assertEquals(listOf("neoforge"), version.loaders)
        assertEquals(listOf(ResourceDependency("12", null, "required"), ResourceDependency("21", null, "incompatible")), version.dependencies)
        assertNull(file.copy(downloadUrl = null).toDomain())
        assertNull(file.copy(downloadUrl = "https://edge.forgecdn.net.example.org/mod.jar").toDomain())
        assertNull(file.copy(hashes = listOf(CurseForgeHash(2, "c".repeat(32)))).toDomain())
    }

    private fun createApi(server: MockWebServer): CurseForgeApi = Retrofit.Builder()
        .baseUrl(server.url("/v1/"))
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(CurseForgeApi::class.java)
}
