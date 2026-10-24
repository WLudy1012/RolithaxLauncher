package com.wludy.rolithax.launcher.data.remote

import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.util.concurrent.TimeUnit

/**
 * CNB（cnb.cool）公开 Release 元数据源：GitHub API 不可达时（大陆网络）的回退通道。
 *
 * 全部匿名可读，不依赖任何第三方代理：
 * - tag 发现走 git 智能协议 ref 通告（info/refs?service=git-upload-pack）；
 * - 单个 release 的数据取自标签页服务端渲染的 __NEXT_DATA__（releasesDetailData）。
 *
 * 这两个接口都是 CNB 的 Web 载体而非开放 API，解析失败一律返回 null / 抛出，
 * 由调用方按「元数据源不可用」处理，不猜测、不降级到无校验数据。
 */
object CnbReleaseSource {

    private const val REPO_SLUG = "WLudy/RolithaxLauncher"
    private const val REFS_URL = "https://cnb.cool/$REPO_SLUG/info/refs?service=git-upload-pack"
    private const val RELEASE_PAGE_BASE = "https://cnb.cool/$REPO_SLUG/-/releases/tag/"

    /** 标签页约 200KB，4MB 上限足够且防止异常响应撑爆内存。 */
    private const val MAX_PAGE_BYTES = 4L * 1024 * 1024

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("User-Agent", ApiClient.USER_AGENT)
                .build()
            chain.proceed(request)
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** 列出仓库全部版本 tag（如 v1.8.0、v1.7.12-Beta）。 */
    suspend fun fetchTags(): List<String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(REFS_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("CNB refs HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("响应为空")
            parseRefAdvertisement(readCapped(body, MAX_PAGE_BYTES))
        }
    }

    /** 取指定 tag 的 Release 元数据并映射为 [GitHubRelease]；无 Release 或解析失败返回 null。 */
    suspend fun fetchRelease(tag: String): GitHubRelease? = withContext(Dispatchers.IO) {
        if (!SAFE_TAG.matches(tag)) return@withContext null
        val request = Request.Builder().url("$RELEASE_PAGE_BASE$tag").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body ?: return@withContext null
            val html = readCapped(body, MAX_PAGE_BYTES)
            val json = extractNextData(html) ?: return@withContext null
            parseReleasePayload(json, tag)
        }
    }

    /** 解析标签页 __NEXT_DATA__ 里的 releasesDetailData；结构不符或字段缺失返回 null。 */
    internal fun parseReleasePayload(json: String, fallbackTag: String): GitHubRelease? {
        val dto = runCatching { ApiClient.gson().fromJson(json, CnbNextData::class.java) }.getOrNull()
        return toGitHubRelease(dto?.props?.pageProps?.releasesDetailData?.release, fallbackTag)
    }

    private val SAFE_TAG = Regex("[A-Za-z0-9][A-Za-z0-9.+-]*")

    private fun readCapped(body: ResponseBody, limit: Long): String {
        val declared = body.contentLength()
        if (declared > limit) throw IllegalStateException("响应过大：$declared 字节")
        return body.source().readUtf8(limit)
    }

    /** 从 git ref 通告里提取 refs/tags/ 下的 tag 名（pkt-line 长度前缀无关紧要，按行扫描即可）。 */
    internal fun parseRefAdvertisement(text: String): List<String> =
        Regex("refs/tags/([^\\s^]+)")
            .findAll(text)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()

    internal fun extractNextData(html: String): String? =
        Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.startsWith("{") }

    private fun toGitHubRelease(release: CnbRelease?, fallbackTag: String): GitHubRelease? {
        if (release == null) return null
        val tagName = release.tagRef
            ?.removePrefix("refs/tags/")
            ?.takeIf { it.isNotBlank() }
            ?: fallbackTag
        val assets = release.assets.orEmpty().mapNotNull { asset ->
            val name = asset.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = asset.path?.takeIf { it.startsWith("/") } ?: return@mapNotNull null
            GitHubReleaseAsset(
                name = name,
                browserDownloadUrl = "https://cnb.cool$path",
                size = asset.sizeInByte,
                digest = asset.hashValue
                    ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
                    ?.let { "sha256:$it" },
            )
        }
        return GitHubRelease(
            tagName = tagName,
            name = release.title?.takeIf { it.isNotBlank() } ?: tagName,
            body = release.body.orEmpty(),
            prerelease = release.isPrerelease,
            publishedAt = release.publishedAt,
            assets = assets,
        )
    }
}

private data class CnbNextData(val props: CnbProps? = null)

private data class CnbProps(val pageProps: CnbPageProps? = null)

private data class CnbPageProps(val releasesDetailData: CnbReleaseDetail? = null)

private data class CnbReleaseDetail(val release: CnbRelease? = null)

private data class CnbRelease(
    @SerializedName("tagRef") val tagRef: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("body") val body: String? = null,
    @SerializedName("isPrerelease") val isPrerelease: Boolean = false,
    @SerializedName("publishedAt") val publishedAt: String? = null,
    @SerializedName("assets") val assets: List<CnbAsset>? = null,
)

private data class CnbAsset(
    @SerializedName("name") val name: String? = null,
    @SerializedName("path") val path: String? = null,
    @SerializedName("hashValue") val hashValue: String? = null,
    @SerializedName("sizeInByte") val sizeInByte: Long = 0,
)
