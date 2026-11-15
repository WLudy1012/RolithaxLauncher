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
            projects = hits.mapNotNull { hit ->
                runCatching { parseProject(hit.asJsonObject, filter.type) }.getOrNull()?.takeIf { it.id.isNotBlank() }
            },
            totalHits = body.get("total_hits")?.asInt ?: 0,
            resultCount = hits.size(),
            etag = response.headers()["ETag"],
        )
    }

    override suspend fun versions(projectId: String, filter: ResourceFilter): List<ResourceVersion> {
        val loaders = resourceSearchLoaders(filter.type, filter.loader)
        val loaderFields = JsonObject().apply {
            if (filter.gameVersion.isNotBlank()) add("game_versions", listOf(filter.gameVersion).toJsonArray())
            if (filter.type == "modpack" && loaders.isNotEmpty()) add("mrpack_loaders", loaders.toJsonArray())
        }
        val response = api.versions(
            id = projectId,
            loaderFields = loaderFields.takeIf { it.size() > 0 }?.toString(),
            loaders = loaders.takeIf { filter.type != "modpack" && it.isNotEmpty() }?.toJsonArray()?.toString(),
        )
        if (!response.isSuccessful) throw HttpException(response)
        return response.body().orEmpty().mapNotNull { parseVersion(it, filter.type) }.filter(filter::matchesVersion)
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

private fun parseVersion(json: JsonObject, type: String): ResourceVersion? {
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
        gameVersions = json.loaderValues("game_versions"),
        loaders = json.resourceLoaders(type),
        files = files,
        dependencies = dependencies,
        serverCompatible = json.supportsServer(),
    )
}

internal fun searchFacets(filter: ResourceFilter): String {
    val facets = JsonArray()
    facets.add(listOf("project_types:${filter.type}").toJsonArray())
    if (filter.gameVersion.isNotBlank()) facets.add(listOf("game_versions:${filter.gameVersion}").toJsonArray())
    val loaders = resourceSearchLoaders(filter.type, filter.loader)
    if (loaders.isNotEmpty()) {
        val field = if (filter.type == "modpack") "mrpack_loaders" else "loaders"
        facets.add(loaders.map { "$field:$it" }.toJsonArray())
    }
    return facets.toString()
}

private fun parseProject(json: JsonObject, fallbackType: String): ResourceProject {
    val env = json.loaderValues("environment")
    val clientSide = json.get("client_side").asStringValues()
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
        loaders = json.resourceLoaders(projectType),
        gameVersions = json.loaderValues("game_versions"),
        serverCompatible = json.supportsServer(),
        clientCompatible = !env.allWhenPresent { it in SERVER_ONLY_ENVIRONMENTS } &&
            !clientSide.allWhenPresent { it == "unsupported" },
    )
}

private val CLIENT_ONLY_ENVIRONMENTS = setOf("client_only", "singleplayer_only")
private val SERVER_ONLY_ENVIRONMENTS = setOf("server_only", "dedicated_server_only")

private fun JsonObject.supportsServer(): Boolean =
    !loaderValues("environment").allWhenPresent { it in CLIENT_ONLY_ENVIRONMENTS } &&
        !get("server_side").asStringValues().allWhenPresent { it == "unsupported" }

private fun JsonObject.loaderValues(field: String): List<String> =
    getAsJsonObject("project_loader_fields")?.get(field).asStringValues().ifEmpty { get(field).asStringValues() }

private fun JsonObject.resourceLoaders(type: String): List<String> {
    val loaders = get("loaders").asStringValues()
    return if (type == "modpack") loaderValues("mrpack_loaders").ifEmpty { loaders.filterNot { it == "mrpack" } } else loaders
}

private inline fun List<String>.allWhenPresent(predicate: (String) -> Boolean): Boolean = isNotEmpty() && all(predicate)

private fun List<String>.toJsonArray(): JsonArray = JsonArray(size).also { values -> forEach { values.add(it) } }

private fun JsonArray?.asStringValues(): List<String> = this?.mapNotNull(JsonElement::stringOrNull).orEmpty()

private fun JsonElement?.asStringValues(): List<String> = when {
    this == null -> emptyList()
    isJsonArray -> asJsonArray.mapNotNull(JsonElement::stringOrNull)
    isJsonPrimitive -> listOf(asString)
    else -> emptyList()
}

private fun JsonElement?.stringOrNull(): String? = this?.takeIf(JsonElement::isJsonPrimitive)?.asString
