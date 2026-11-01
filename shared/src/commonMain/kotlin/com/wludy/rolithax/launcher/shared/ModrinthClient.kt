package com.wludy.rolithax.launcher.shared

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.core.readBytes
import io.ktor.utils.io.readRemaining
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class ModrinthProject(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("id") val alternateId: String? = null,
    @SerialName("name") val nameValue: String? = null,
    @SerialName("title") val titleValue: String? = null,
    @SerialName("summary") val summaryValue: String? = null,
    @SerialName("description") val descriptionValue: String? = null,
    val author: String = "",
    @SerialName("project_types") val projectTypes: List<String> = emptyList(),
    @SerialName("project_type") val projectType: String? = null,
    @SerialName("server_side") val serverSide: JsonElement? = null,
    val environment: JsonElement? = null,
    @SerialName("project_loader_fields") val loaderFields: ModrinthLoaderFields? = null,
) {
    val id: String get() = projectId ?: alternateId.orEmpty()
    val title: String get() = nameValue ?: titleValue.orEmpty()
    val description: String get() = summaryValue ?: descriptionValue.orEmpty()
}

@Serializable
data class ModrinthLoaderFields(val environment: JsonElement? = null)

@Serializable
private data class ModrinthSearchResponse(val hits: List<ModrinthProject> = emptyList())

@Serializable
data class ModrinthFile(val filename: String = "", val hashes: Map<String, String> = emptyMap(), val primary: Boolean = false)

@Serializable
data class ModrinthDependency(
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("version_id") val versionId: String? = null,
    @SerialName("dependency_type") val type: String = "",
)

@Serializable
data class ModrinthVersion(
    val id: String,
    val name: String = "",
    @SerialName("version_number") val number: String = "",
    @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    val files: List<ModrinthFile> = emptyList(),
    val dependencies: List<ModrinthDependency>? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("server_side") val serverSide: JsonElement? = null,
)

class ModrinthClient(httpClient: HttpClient) {
    private val client = httpClient.config { followRedirects = false; expectSuccess = false }
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    fun close() = client.close()

    suspend fun search(query: String, type: String, gameVersion: String, loader: String): List<ModrinthProject> {
        require(type in setOf("mod", "plugin"))
        val facets = mutableListOf(listOf("project_types:$type"))
        if (gameVersion.isNotBlank()) facets += listOf("game_versions:$gameVersion")
        val normalizedLoader = loader.lowercase()
        val fits = when (type) {
            "mod" -> normalizedLoader in setOf("fabric", "forge", "neoforge", "quilt")
            "plugin" -> normalizedLoader in setOf("paper", "spigot", "bukkit")
            else -> false
        }
        if (fits) facets += listOf("categories:$normalizedLoader")
        val body = get("search", mapOf("query" to query, "limit" to "30", "facets" to json.encodeToString(facets)))
        return json.decodeFromString<ModrinthSearchResponse>(body).hits.filter { it.id.isNotBlank() }
    }

    suspend fun versions(projectId: String, gameVersion: String, loader: String): List<ModrinthVersion> {
        require(projectId.isNotEmpty() && projectId.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        val query = buildMap {
            if (gameVersion.isNotBlank()) put("game_version", gameVersion)
            if (loader.isNotBlank()) put("loader", loader)
        }
        return json.decodeFromString<List<ModrinthVersion>>(get("project/$projectId/version", query)).filter { version ->
            val serverValues = when (val side = version.serverSide) {
                is JsonPrimitive -> listOfNotNull(side.contentOrNull)
                is JsonArray -> side.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            }
            "unsupported" !in serverValues && version.files.any { file -> file.hashes.any { (algorithm, hash) ->
                val length = when (algorithm.lowercase()) { "sha512" -> 128; "sha256" -> 64; "sha1" -> 40; else -> 0 }
                length > 0 && hash.length == length && hash.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }
            } }
        }
    }

    suspend fun project(id: String): ModrinthProject {
        require(safeId(id))
        return json.decodeFromString(get("project/$id", emptyMap()))
    }

    suspend fun version(id: String): ModrinthVersion {
        require(safeId(id))
        return json.decodeFromString(get("version/$id", emptyMap()))
    }

    private fun safeId(id: String): Boolean = id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private suspend fun get(path: String, query: Map<String, String>): String =
        client.prepareGet("https://api.modrinth.com/v3/$path") {
            header("User-Agent", "Rolithax-Launcher (https://github.com/WLudy1012/RolithaxLauncher)")
            header("Accept", "application/json")
            query.forEach { (key, value) -> parameter(key, value) }
        }.execute { response ->
            if (!response.status.isSuccess()) throw DaemonException("Modrinth 请求失败：HTTP ${response.status.value}")
            val bytes = response.bodyAsChannel().readRemaining(4 * 1024 * 1024L + 1).readBytes()
            if (bytes.size > 4 * 1024 * 1024) throw DaemonException("Modrinth 响应过大")
            bytes.decodeToString()
        }
}
