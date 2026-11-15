package com.wludy.rolithax.launcher.data.resources

import com.wludy.rolithax.launcher.data.remote.ApiClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

internal const val CURSEFORGE_PROVIDER_ID = "curseforge"
internal val CURSEFORGE_DOWNLOAD_HOSTS = setOf("edge.forgecdn.net", "mediafilez.forgecdn.net")
private const val MINECRAFT = 432
private const val MODS_CLASS = 6
private const val MAX_FILES_PAGE = 50
private const val MAX_SEARCH_WINDOW = 10_000
private const val MAX_FILE_PAGES = 10
private val loaders = mapOf("forge" to 1, "liteloader" to 3, "fabric" to 4, "quilt" to 5, "neoforge" to 6)
private val sha1Pattern = Regex("[a-fA-F0-9]{40}")
private val releaseVersion = Regex("(?:[ab])?\\d+\\.\\d+(?:\\.\\d+)?(?:[- ].+)?|\\d{2}w\\d{2}[a-z]")

internal class CurseForgeResourceProvider(
    private val api: CurseForgeApi,
    private val readApiKey: suspend () -> String?,
) : ResourceProvider {
    override val id = CURSEFORGE_PROVIDER_ID

    override suspend fun search(filter: ResourceFilter, limit: Int, offset: Int, etag: String?): ResourceSearchPage {
        if (filter.type != "mod" || offset >= MAX_SEARCH_WINDOW) return ResourceSearchPage(emptyList(), 0, 0, null)
        val gameVersion = filter.gameVersion.takeIf(String::isNotBlank)
        val loader = loaders[filter.loader.lowercase()].takeIf { gameVersion != null }
        val page = api.search(
            apiKey(), MINECRAFT, MODS_CLASS, filter.query.trim().takeIf(String::isNotBlank), gameVersion, loader,
            offset, limit.coerceIn(1, minOf(MAX_FILES_PAGE, MAX_SEARCH_WINDOW - offset)),
        ).requireBody()
        val records = page.data ?: error("CurseForge 未返回搜索结果")
        val projects = records.mapNotNull(CurseForgeProject::toDomain)
        return ResourceSearchPage(projects, (page.pagination?.totalCount ?: offset + records.size).coerceIn(0, MAX_SEARCH_WINDOW), records.size, null)
    }

    override suspend fun versions(projectId: String, filter: ResourceFilter): List<ResourceVersion> {
        if (filter.type != "mod") return emptyList()
        val project = projectId.toIntOrNull()?.takeIf { it > 0 } ?: return emptyList()
        val key = apiKey()
        val gameVersion = filter.gameVersion.takeIf(String::isNotBlank)
        val loader = loaders[filter.loader.lowercase()].takeIf { gameVersion != null }
        val versions = mutableListOf<ResourceVersion>()
        var offset = 0
        repeat(MAX_FILE_PAGES) {
            val page = api.files(key, project, gameVersion, loader, offset, MAX_FILES_PAGE).requireBody()
            val records = page.data ?: error("CurseForge 未返回文件列表")
            versions += records.mapNotNull(CurseForgeFile::toDomain).filter(filter::matchesVersion)
            offset += page.pagination?.resultCount?.takeIf { it > 0 } ?: records.size
            if (records.isEmpty() || offset >= (page.pagination?.totalCount ?: offset)) return versions.distinctBy { it.id }
        }
        return versions.distinctBy { it.id }
    }

    override suspend fun project(projectId: String): ResourceProject? {
        val id = projectId.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val response = api.project(apiKey(), id)
        return if (response.code() == 404) null else response.requireBody().data?.toDomain()
    }

    override suspend fun projectIdForVersion(versionId: String): String? = null

    private suspend fun apiKey(): String = readApiKey()?.takeIf(String::isNotBlank)
        ?: throw IllegalStateException("请先在资源来源中配置 CurseForge API Key")

    companion object {
        fun create(readApiKey: suspend () -> String?): CurseForgeResourceProvider {
            val client = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder()
                        .header("User-Agent", ApiClient.USER_AGENT)
                        .header("Accept", "application/json")
                        .build())
                }
                .build()
            val api = Retrofit.Builder()
                .baseUrl("https://api.curseforge.com/v1/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(CurseForgeApi::class.java)
            return CurseForgeResourceProvider(api, readApiKey)
        }
    }
}

internal interface CurseForgeApi {
    @GET("mods/search?sortField=2&sortOrder=desc")
    suspend fun search(
        @Header("x-api-key") key: String,
        @Query("gameId") gameId: Int,
        @Query("classId") classId: Int,
        @Query("searchFilter") query: String?,
        @Query("gameVersion") gameVersion: String?,
        @Query("modLoaderType") loader: Int?,
        @Query("index") offset: Int,
        @Query("pageSize") limit: Int,
    ): Response<CurseForgePage<List<CurseForgeProject>>>

    @GET("mods/{id}")
    suspend fun project(@Header("x-api-key") key: String, @Path("id") id: Int): Response<CurseForgePage<CurseForgeProject>>

    @GET("mods/{id}/files")
    suspend fun files(
        @Header("x-api-key") key: String,
        @Path("id") id: Int,
        @Query("gameVersion") gameVersion: String?,
        @Query("modLoaderType") loader: Int?,
        @Query("index") offset: Int,
        @Query("pageSize") limit: Int,
    ): Response<CurseForgePage<List<CurseForgeFile>>>
}

internal data class CurseForgePage<T>(val data: T?, val pagination: CurseForgePagination? = null)
internal data class CurseForgePagination(val totalCount: Int, val resultCount: Int)

internal data class CurseForgeProject(
    val id: Int,
    val classId: Int,
    val name: String?,
    val summary: String?,
    val authors: List<CurseForgeAuthor>?,
    val logo: CurseForgeLogo?,
    val downloadCount: Long,
    val dateModified: String?,
    val latestFilesIndexes: List<CurseForgeFileIndex>?,
) {
    fun toDomain(): ResourceProject? {
        if (classId != MODS_CLASS || id <= 0 || name.isNullOrBlank()) return null
        val indexes = latestFilesIndexes.orEmpty()
        return ResourceProject(
            id = id.toString(),
            name = name,
            summary = summary.orEmpty(),
            author = authors?.firstOrNull()?.name.orEmpty(),
            iconUrl = logo?.thumbnailUrl,
            downloads = downloadCount,
            updatedAt = dateModified.orEmpty(),
            type = "mod",
            loaders = indexes.mapNotNull { file -> loaders.entries.firstOrNull { it.value == file.modLoader }?.key }.distinct(),
            gameVersions = indexes.mapNotNull { it.gameVersion }.distinct(),
            serverCompatible = true,
        )
    }
}

internal data class CurseForgeAuthor(val name: String?)
internal data class CurseForgeLogo(val thumbnailUrl: String?)
internal data class CurseForgeFileIndex(val gameVersion: String?, val modLoader: Int?)

internal data class CurseForgeFile(
    val id: Int,
    val displayName: String?,
    val fileName: String?,
    val downloadUrl: String?,
    val fileLength: Long,
    val isAvailable: Boolean?,
    val gameVersions: List<String>?,
    val hashes: List<CurseForgeHash>?,
    val dependencies: List<CurseForgeDependency>?,
) {
    fun toDomain(): ResourceVersion? {
        if (id <= 0 || fileLength <= 0 || isAvailable == false) return null
        val name = fileName?.takeIf { it.endsWith(".jar", true) && '/' !in it && '\\' !in it && ':' !in it }
            ?: return null
        val url = downloadUrl?.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.host in CURSEFORGE_DOWNLOAD_HOSTS && it.username.isEmpty() && it.password.isEmpty()
        } ?: return null
        val hash = hashes.orEmpty().firstNotNullOfOrNull { digest ->
            digest.value?.takeIf { digest.algo == 1 && sha1Pattern.matches(it) }
        } ?: return null
        val tags = gameVersions.orEmpty()
        return ResourceVersion(
            id = id.toString(),
            name = displayName?.takeIf(String::isNotBlank) ?: name,
            number = id.toString(),
            gameVersions = tags.filter { releaseVersion.matches(it) }.distinct(),
            loaders = tags.map { it.lowercase() }.filter { it in loaders }.distinct(),
            files = listOf(ResourceFile(name, url.toString(), "sha1:$hash", fileLength, primary = true)),
            dependencies = dependencies.orEmpty().filter { it.modId > 0 }.map {
                val type = when (it.relationType) {
                    3 -> "required"
                    5 -> "incompatible"
                    1, 6 -> "embedded"
                    else -> "optional"
                }
                ResourceDependency(it.modId.toString(), null, type)
            },
            serverCompatible = true,
        )
    }
}

internal data class CurseForgeHash(val algo: Int, val value: String?)
internal data class CurseForgeDependency(val modId: Int, val relationType: Int)

private fun <T> Response<T>.requireBody(): T {
    if (!isSuccessful) {
        error(when (code()) {
            401, 403 -> "CurseForge 拒绝请求，请检查 API Key 和访问权限"
            429 -> "CurseForge 请求频率过高，请稍后再试"
            else -> "CurseForge 请求失败（HTTP ${code()}）"
        })
    }
    return body() ?: error("CurseForge 响应为空")
}
