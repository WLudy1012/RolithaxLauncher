package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ResourcePlanTest {
    private val validFile = ModrinthFile("server.jar", mapOf("sha256" to "a".repeat(64)))
    private fun version(id: String, dependencies: List<ModrinthDependency>?) = ModrinthVersion(
        id = id, projectId = "main", gameVersions = listOf("1.20.1"), loaders = listOf("fabric"), files = listOf(validFile), dependencies = dependencies,
    )

    @Test
    fun requiredDependencyIsIncluded() = runTest {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v3/project/main" -> respond("""{"id":"main","name":"Main","project_type":"mod"}""")
                "/v3/project/dep" -> respond("""{"id":"dep","name":"Dependency","project_type":"mod"}""")
                "/v3/project/dep/version" -> respond("""[{"id":"dep-version","project_id":"dep","game_versions":["1.20.1"],"loaders":["fabric"],"dependencies":[],"files":[{"filename":"dep.jar","hashes":{"sha256":"${"a".repeat(64)}"}}]}]""")
                else -> error("Unexpected request")
            }
        })
        val client = ModrinthClient(http)
        try {
            val plan = client.planInstall(ModrinthProject(projectId = "main"), version("main-version", listOf(ModrinthDependency(projectId = "dep", type = "required"))), "mod", "1.20.1", "fabric")
            assertEquals(listOf("main", "dep"), plan.entries.map { it.project.id })
        } finally { client.close(); http.close() }
    }

    @Test
    fun missingDependencyMetadataFailsClosed() = runTest {
        val http = HttpClient(MockEngine { respond("""{"id":"main","project_type":"mod"}""") })
        val client = ModrinthClient(http)
        try {
            assertFailsWith<DaemonException> { client.planInstall(ModrinthProject(projectId = "main"), version("v1", null), "mod", "1.20.1", "fabric") }
        } finally { client.close(); http.close() }
    }

    @Test
    fun incompatibleSelectedDependencyFailsClosed() = runTest {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v3/project/main" -> respond("""{"id":"main","project_type":"mod"}""")
                "/v3/project/dep" -> respond("""{"id":"dep","project_type":"mod"}""")
                "/v3/project/dep/version" -> respond("""[{"id":"v2","project_id":"dep","game_versions":["1.20.1"],"loaders":["fabric"],"dependencies":[{"project_id":"main","dependency_type":"incompatible"}],"files":[{"filename":"dep.jar","hashes":{"sha256":"${"a".repeat(64)}"}}]}]""")
                else -> error("Unexpected request")
            }
        })
        val client = ModrinthClient(http)
        try {
            assertFailsWith<DaemonException> { client.planInstall(ModrinthProject(projectId = "main"), version("v1", listOf(ModrinthDependency(projectId = "dep", type = "required"))), "mod", "1.20.1", "fabric") }
        } finally { client.close(); http.close() }
    }
    @Test
    fun missingRequiredVersionFailsClosed() = runTest {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v3/project/main" -> respond("""{"id":"main","project_type":"mod"}""")
                "/v3/project/dep" -> respond("""{"id":"dep","project_type":"mod"}""")
                "/v3/project/dep/version" -> respond("[]")
                else -> error("Unexpected request")
            }
        })
        val client = ModrinthClient(http)
        try {
            assertFailsWith<DaemonException> { client.planInstall(ModrinthProject(projectId = "main"), version("v1", listOf(ModrinthDependency(projectId = "dep", type = "required"))), "mod", "1.20.1", "fabric") }
        } finally { client.close(); http.close() }
    }

    @Test
    fun cycleIsResolvedOnce() = runTest {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v3/project/main" -> respond("""{"id":"main","project_type":"mod"}""")
                "/v3/project/dep" -> respond("""{"id":"dep","project_type":"mod"}""")
                "/v3/project/dep/version" -> respond("""[{"id":"v2","project_id":"dep","game_versions":["1.20.1"],"loaders":["fabric"],"dependencies":[{"project_id":"main","dependency_type":"required"}],"files":[{"filename":"dep.jar","hashes":{"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}]}]""")
                else -> error("Unexpected request")
            }
        })
        val client = ModrinthClient(http)
        try {
            val plan = client.planInstall(ModrinthProject(projectId = "main"), version("v1", listOf(ModrinthDependency(projectId = "dep", type = "required"))), "mod", "1.20.1", "fabric")
            assertEquals(listOf("main", "dep"), plan.entries.map { it.project.id })
        } finally { client.close(); http.close() }
    }

    @Test
    fun mismatchedVersionAndUnsafeFileFailClosed() = runTest {
        val http = HttpClient(MockEngine { respond("""{"id":"main","project_type":"mod"}""") })
        val client = ModrinthClient(http)
        try {
            assertFailsWith<DaemonException> { client.planInstall(ModrinthProject(projectId = "main"), version("v1", emptyList()).copy(projectId = "other"), "mod", "1.20.1", "fabric") }
            assertFailsWith<DaemonException> { client.planInstall(ModrinthProject(projectId = "main"), version("v1", emptyList()).copy(files = listOf(validFile.copy(filename = "../evil.jar"))), "mod", "1.20.1", "fabric") }
        } finally { client.close(); http.close() }
    }

    @Test
    fun duplicateInstallTargetsAreRejected() = runTest {
        val http = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/v3/project/main" -> respond("""{"id":"main","project_type":"mod"}""")
                "/v3/project/dep" -> respond("""{"id":"dep","project_type":"mod"}""")
                "/v3/project/dep/version" -> respond("""[{"id":"v2","project_id":"dep","game_versions":["1.20.1"],"loaders":["fabric"],"dependencies":[],"files":[{"filename":"server.jar","hashes":{"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}]}]""")
                else -> error("Unexpected request")
            }
        })
        val client = ModrinthClient(http)
        try {
            assertFailsWith<DaemonException> { client.planInstall(ModrinthProject(projectId = "main"), version("v1", listOf(ModrinthDependency(projectId = "dep", type = "required"))), "mod", "1.20.1", "fabric") }
        } finally { client.close(); http.close() }
    }

}
