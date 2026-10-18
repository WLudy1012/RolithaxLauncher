package com.wludy.rolithax.launcher.data.resources

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import retrofit2.HttpException

data class ResourceSearchPage(
    val projects: List<ResourceProject>,
    val totalHits: Int,
    val resultCount: Int,
    val etag: String?,
    val notModified: Boolean = false,
)

interface ResourceProvider {
    val id: String
    suspend fun search(filter: ResourceFilter, limit: Int, offset: Int, etag: String?): ResourceSearchPage
    suspend fun versions(projectId: String, filter: ResourceFilter): List<ResourceVersion>
    suspend fun project(projectId: String): ResourceProject?
    suspend fun projectIdForVersion(versionId: String): String?
}

class ModrinthResourceProvider(private val api: ModrinthApi) : ResourceProvider {
    override val id = "modrinth"

    override suspend fun search(filter: ResourceFilter, limit: Int, offset: Int, etag: String?): ResourceSearchPage {
        val response = api.search(
            query = filter.query,
            limit = limit,
            offset = offset,
            facets = searchFacets(filter),
            etag = etag,
        )
        if (response.code() == 304) return ResourceSearchPage(emptyList(), 0, 0, response.headers()["ETag"], true)
        if (!response.isSuccessful) throw HttpException(response)
        val body = response.body() ?: throw IllegalStateException("Modrinth 响应为空")
        val hits = body.getAsJsonArray("hits") ?: JsonArray()
        return ResourceSearchPage(
            projects = hits.mapNotNull { hit -> runCatching { parseProject(hit.asJsonObject, filter.type) }.getOrNull() },
            totalHits = body.get("total_hits")?.asInt ?: 0,
            resultCount = hits.size(),
            etag = response.headers()["ETag"],
        )
    }

    override suspend fun versions(projectId: String, filter: ResourceFilter): List<ResourceVersion> {
        val response = api.versions(
            projectId,
            filter.gameVersion.takeIf(String::isNotBlank),
            filter.loader.takeIf(String::isNotBlank),
        )
        if (!response.isSuccessful) throw HttpException(response)
        return response.body().orEmpty().mapNotNull(::parseVersion)
    }

    override suspend fun project(projectId: String): ResourceProject? {
        val response = api.project(projectId)
        if (!response.isSuccessful) throw HttpException(response)
        return response.body()?.let { parseProject(it, "mod") }
    }

    override suspend fun projectIdForVersion(versionId: String): String? {
        val response = api.version(versionId)
        if (!response.isSuccessful) throw HttpException(response)
        return response.body()?.get("project_id").stringOrNull()
    }
}

private fun parseVersion(json: JsonObject): ResourceVersion? {
    val id = json.get("id")?.takeIf(JsonElement::isJsonPrimitive)?.asString ?: return null
    val files = json.getAsJsonArray("files")?.mapNotNull { item ->
        val file = item.asJsonObject
        val hashes = file.getAsJsonObject("hashes")
        val sha512 = hashes?.get("sha512")?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
        val sha256 = hashes?.get("sha256")?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
        val sha1 = hashes?.get("sha1")?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty()
        val url = file.get("url")?.takeIf(JsonElement::isJsonPrimitive)?.asString ?: return@mapNotNull null
        val digest = when {
            sha512.isNotBlank() -> "sha512:$sha512"
            sha256.isNotBlank() -> "sha256:$sha256"
            sha1.isNotBlank() -> "sha1:$sha1"
            else -> return@mapNotNull null
        }
        ResourceFile(
            filename = file.get("filename")?.takeIf(JsonElement::isJsonPrimitive)?.asString.orEmpty(),
            url = url,
            sha512 = digest,
            size = file.get("size")?.asLong ?: 0,
            primary = file.get("primary")?.asBoolean ?: false,
        )
    }.orEmpty()
    if (files.isEmpty()) return null
    val dependencies = json.getAsJsonArray("dependencies")?.mapNotNull { item ->
        val dependency = item.asJsonObject
        ResourceDependency(
            projectId = dependency.get("project_id").stringOrNull(),
            versionId = dependency.get("version_id").stringOrNull(),
            type = dependency.get("dependency_type").stringOrNull().orEmpty(),
        )
    }.orEmpty()
    return ResourceVersion(
        id = id,
        name = json.get("name").stringOrNull().orEmpty(),
        number = json.get("version_number").stringOrNull().orEmpty(),
        gameVersions = json.getAsJsonArray("game_versions").asStringValues(),
        loaders = json.getAsJsonArray("loaders").asStringValues(),
        files = files,
        dependencies = dependencies,
        serverCompatible = json.get("server_side").asStringValues().none { it == "unsupported" },
    )
}

private val MOD_LOADERS = setOf("fabric", "forge", "neoforge", "quilt")
private val PLUGIN_LOADERS = setOf("paper", "spigot", "bukkit")

// Modrinth 搜索只识别 facets，project_type/game_version/loader 查询参数会被忽略并返回全站结果。
internal fun searchFacets(filter: ResourceFilter): String {
    val groups = mutableListOf("project_types:${filter.type}")
    if (filter.gameVersion.isNotBlank()) groups += "game_versions:${filter.gameVersion}"
    val loader = filter.loader.lowercase()
    val loaderFits = when (filter.type) {
        "mod", "modpack" -> loader in MOD_LOADERS
        "plugin" -> loader in PLUGIN_LOADERS
        else -> false
    }
    if (loaderFits) groups += "categories:$loader"
    return groups.joinToString(prefix = "[", postfix = "]", separator = ",") { "[\"$it\"]" }
}

private fun parseProject(json: JsonObject, fallbackType: String): ResourceProject {
    val environment = json.getAsJsonObject("project_loader_fields")?.get("environment") ?: json.get("environment")
    val env = environment.asStringValues()
    val clientSide = json.get("client_side").asStringValues()
    val serverSide = json.get("server_side").asStringValues()
    val projectTypes = json.getAsJsonArray("project_types").asStringValues()
    val projectType = fallbackType.takeIf { it in projectTypes }
        ?: projectTypes.firstOrNull()
        ?: json.get("project_type").stringOrNull()
        ?: fallbackType
    return ResourceProject(
        id = json.get("project_id").stringOrNull() ?: json.get("id").stringOrNull().orEmpty(),
        name = json.get("name").stringOrNull() ?: json.get("title").stringOrNull().orEmpty(),
        summary = json.get("summary").stringOrNull() ?: json.get("description").stringOrNull().orEmpty(),
        author = json.get("author").stringOrNull().orEmpty(),
        iconUrl = json.get("icon_url").stringOrNull(),
        downloads = json.get("downloads")?.asLong ?: 0,
        updatedAt = json.get("date_modified").stringOrNull().orEmpty(),
        type = projectType,
        loaders = json.getAsJsonArray("loaders").asStringValues(),
        gameVersions = json.getAsJsonObject("project_loader_fields")?.getAsJsonArray("game_versions").asStringValues()
            .ifEmpty { json.getAsJsonArray("game_versions").asStringValues() },
        serverCompatible = "client_only" !in env && "unsupported" !in serverSide,
        clientCompatible = "server_only" !in env && "unsupported" !in clientSide,
    )
}

private fun JsonArray?.asStringValues(): List<String> = this?.mapNotNull(JsonElement::stringOrNull).orEmpty()

private fun JsonElement?.asStringValues(): List<String> = when {
    this == null -> emptyList()
    isJsonArray -> asJsonArray.mapNotNull(JsonElement::stringOrNull)
    isJsonPrimitive -> listOf(asString)
    else -> emptyList()
}

private fun JsonElement?.stringOrNull(): String? = this?.takeIf(JsonElement::isJsonPrimitive)?.asString
