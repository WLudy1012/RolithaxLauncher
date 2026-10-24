package com.wludy.rolithax.launcher.data

import com.wludy.rolithax.launcher.data.remote.ApiClient
import com.wludy.rolithax.launcher.data.remote.CnbReleaseSource
import com.wludy.rolithax.launcher.data.remote.GitHubRelease
import com.wludy.rolithax.launcher.data.remote.GitHubReleaseAsset
import kotlinx.coroutines.CancellationException

/**
 * 应用更新信息（由 GitHub / CNB Release 元数据解析而来）。
 */
data class AppUpdateInfo(
    /** 展示用版本标识。 */
    val version: String,
    /** Release 介绍（更新内容）。 */
    val notes: String,
    /** APK 下载直链。 */
    val downloadUrl: String,
    /** APK 文件名。 */
    val apkName: String,
    /** APK 大小（字节）。 */
    val apkSize: Long,
    /** 完整版 APK 的可信 SHA-256；缺失时拒绝安装。 */
    val sha256: String? = null,
    /** 是否为测试版（tag 带 -Beta 后缀）。 */
    val beta: Boolean = false,
    /**
     * 是否强制更新：任一高于当前版本的 release tag 带 -Force 后缀，
     * 或其说明含"强制更新"标记（旧版兼容）时为 true。
     */
    val forceUpdate: Boolean = false,
    /**
     * CNB（cnb.cool）镜像下载直链（稳定/测试渠道）。CNB 为首选更新源，
     * 下载失败时客户端自动回退 [downloadUrl]（GitHub）。为 null 时仅使用 GitHub。
     */
    val cnbUrl: String? = null,
    /**
     * 精简版 APK（**不含**内嵌 JRE 运行时，体积小）直链；为 null 表示该 Release 只提供完整版。
     * 同一个 Release 会同时挂载完整版与精简版两个包（见 CI 配置）。
     */
    val liteUrl: String? = null,
    /** 精简版 APK 大小（字节）。 */
    val liteSize: Long = 0,
    /** 精简版 APK 的可信 SHA-256；缺失时拒绝安装。 */
    val liteSha256: String? = null,
    /** 精简版 CNB 镜像直链。 */
    val liteCnbUrl: String? = null,
    /** 该更新包是否内嵌 JRE 运行时（本机开服可离线使用），按体积判定。 */
    val embeddedJre: Boolean = false,
) {
    /** 该 Release 是否同时提供精简版。 */
    val hasLiteVariant: Boolean get() = !liteUrl.isNullOrBlank()
}

/** CNB Release 资产下载地址前缀（形如 .../-/releases/download/v1.5/app-release.apk）。 */
private const val CNB_RELEASE_DOWNLOAD_BASE = "https://cnb.cool/WLudy/RolithaxLauncher/-/releases/download"

/** 内嵌 JRE 运行时的包体积下限（arm64 运行时约 36MB，远大于无 JRE 的 ~10MB）。 */
private const val EMBEDDED_JRE_MIN_BYTES = 30L * 1024 * 1024

/** 完整版 / 精简版资产名（CI 固定命名，见 .github/workflows/release.yml 与 .cnb.yml）。 */
private const val APK_FULL_NAME = "app-release.apk"
private const val APK_LITE_NAME = "app-release-lite.apk"

/** CNB 回退路径单次检查最多拉取的标签页数量（旧版本用户也不必逐页回溯全部历史）。 */
private const val MAX_CNB_PAGE_FETCHES = 5

/** 单个 release 的解析结果（内部使用）。 */
private data class ParsedRelease(
    val tag: ParsedTag,
    val release: GitHubRelease,
    val apk: GitHubReleaseAsset,
    val lite: GitHubReleaseAsset? = null,
)

/** tag 解析结果：语义化版本 + Beta/Force 后缀 + 原始 tag 名（用于拼 CNB 地址）。 */
private data class ParsedTag(
    val version: String,
    val beta: Boolean,
    val force: Boolean,
    val rawTag: String? = null,
)

/**
 * 检查应用更新：优先查询 GitHub Release 列表，不可达时回退 CNB 镜像元数据，
 * 按更新渠道过滤，与当前版本比较，返回更新信息（无更新时返回 null）。
 *
 * 两个元数据源都不可用时返回失败（Result.failure），不猜测版本、不跳过校验。
 */
class UpdateRepository(
    private val githubReleases: suspend () -> List<GitHubRelease> = { ApiClient.buildGitHubReleaseApi().releases() },
    private val cnbTags: suspend () -> List<String> = { CnbReleaseSource.fetchTags() },
    private val cnbRelease: suspend (String) -> GitHubRelease? = { tag -> CnbReleaseSource.fetchRelease(tag) },
) {

    /** 检查是否有新版本。currentVersion 形如 "1.2.16"。 */
    suspend fun checkLatest(currentVersion: String, channel: UpdateChannel): Result<AppUpdateInfo?> = try {
        Result.success(computeUpdate(currentVersion, channel))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private suspend fun computeUpdate(currentVersion: String, channel: UpdateChannel): AppUpdateInfo? {
        val fromGitHub = attempt { githubReleases() }
        if (fromGitHub != null) {
            return parseUpdate(fromGitHub, currentVersion, channel)
        }
        AppLogger.i("Update", "GitHub Release 元数据不可达，回退 CNB 元数据源")
        return parseUpdateFromCnb(currentVersion, channel)
    }

    /** 元数据源失败返回 null（触发回退或跳过该版本）；协程取消必须继续向上传播。 */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** 从 Release 列表解析更新信息；无更新时返回 null。 */
    private fun parseUpdate(
        releases: List<GitHubRelease>,
        currentVersion: String,
        channel: UpdateChannel,
    ): AppUpdateInfo? {
        // 过滤出正式(非预发布)且带 APK 资产的版本，解析 tag 的 Beta/Force 后缀
        val parsed = releases
            .filter { !it.prerelease }
            .mapNotNull { release ->
                val tag = parseTag(release.tagName) ?: return@mapNotNull null
                val (full, lite) = pickApkAssets(release)
                val url = full?.browserDownloadUrl
                if (full == null || url.isNullOrBlank()) null
                else ParsedRelease(tag, release, full, lite)
            }

        // 渠道过滤：稳定渠道只出稳定版；测试渠道稳定+测试都出
        val candidates = if (channel == UpdateChannel.BETA) {
            parsed
        } else {
            parsed.filter { !it.tag.beta }
        }

        val newest = candidates
            .sortedWith { a, b -> compareVersions(b.tag.version, a.tag.version) }
            .firstOrNull() ?: return null
        if (compareVersions(newest.tag.version, currentVersion) <= 0) return null

        // 强制判定：任一高于当前版本的 release 带 Force 后缀或说明含"强制更新"（旧版兼容）
        val forceUpdate = parsed.any { p ->
            compareVersions(p.tag.version, currentVersion) > 0 &&
                (p.tag.force || p.release.body.orEmpty().contains("强制更新", ignoreCase = true))
        }

        return buildUpdateInfo(newest.tag, newest.release, forceUpdate)
    }

    /**
     * CNB 回退路径：tag 列表来自 git ref 通告，逐个标签页取 release 元数据。
     * 强制判定用 tag 后缀覆盖全部候选（与 GitHub 路径的列表语义一致），
     * 「说明含强制更新」的旧版兼容只覆盖实际取到页面的最新 release。
     */
    private suspend fun parseUpdateFromCnb(currentVersion: String, channel: UpdateChannel): AppUpdateInfo? {
        val parsed = cnbTags().mapNotNull { raw -> parseTag(raw)?.let { it.copy(rawTag = raw) } }
        val candidates = if (channel == UpdateChannel.BETA) {
            parsed
        } else {
            parsed.filter { !it.beta }
        }
        val sorted = candidates.sortedWith { a, b -> compareVersions(b.version, a.version) }
        val forcedByTag = candidates.any { compareVersions(it.version, currentVersion) > 0 && it.force }
        var fetched = 0
        for (entry in sorted) {
            if (compareVersions(entry.version, currentVersion) <= 0) return null
            if (fetched >= MAX_CNB_PAGE_FETCHES) return null
            val rawTag = entry.rawTag?.takeIf { it.isNotBlank() } ?: continue
            val release = attempt { cnbRelease(rawTag) } ?: continue
            fetched++
            val forceUpdate = forcedByTag || release.body.orEmpty().contains("强制更新", ignoreCase = true)
            buildUpdateInfo(entry, release, forceUpdate)?.let { return it }
        }
        return null
    }

    /** 组装更新信息；release 不带可用 APK 资产时返回 null（调用方继续尝试更早的版本）。 */
    private fun buildUpdateInfo(tag: ParsedTag, release: GitHubRelease, forceUpdate: Boolean): AppUpdateInfo? {
        val (full, lite) = pickApkAssets(release)
        val url = full?.browserDownloadUrl
        if (full == null || url.isNullOrBlank()) return null
        val apkName = full.name ?: APK_FULL_NAME
        val rawTag = tag.rawTag?.takeIf { it.isNotBlank() }
        return AppUpdateInfo(
            version = tag.version,
            notes = release.body.orEmpty(),
            downloadUrl = url,
            apkName = apkName,
            apkSize = full.size ?: 0,
            sha256 = assetSha256(full, release.body),
            beta = tag.beta,
            forceUpdate = forceUpdate,
            embeddedJre = (full.size ?: 0) >= EMBEDDED_JRE_MIN_BYTES,
            // CNB 首选：镜像仓库同名 tag 的 Release 资产（下载失败时由客户端回退 GitHub）
            cnbUrl = rawTag?.let { "$CNB_RELEASE_DOWNLOAD_BASE/$it/$apkName" },
            liteUrl = lite?.browserDownloadUrl,
            liteSize = lite?.size ?: 0,
            liteSha256 = lite?.let { assetSha256(it, release.body) },
            liteCnbUrl = if (lite != null && rawTag != null) {
                "$CNB_RELEASE_DOWNLOAD_BASE/$rawTag/${lite.name ?: APK_LITE_NAME}"
            } else {
                null
            },
        )
    }

    private fun parseAssetSha256(body: String?, assetName: String?): String? {
        if (body.isNullOrBlank() || assetName.isNullOrBlank()) return null
        return body.lineSequence()
            .firstOrNull { it.contains(assetName, ignoreCase = true) }
            ?.let { Regex("(?i)\\b[0-9a-f]{64}\\b").find(it)?.value?.lowercase() }
    }

    private fun assetSha256(asset: GitHubReleaseAsset, body: String?): String? {
        val digest = asset.digest?.removePrefix("sha256:")?.trim()?.lowercase()
        return digest?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            ?: parseAssetSha256(body, asset.name)
    }

    /**
     * 从 release 资产里挑出「完整版（含内嵌 JRE）」与「精简版（不含 JRE）」。
     * 命名不匹配时回退：把最大的 APK 当完整版，其余非同名 APK 视为候选精简版。
     */
    private fun pickApkAssets(
        release: GitHubRelease,
    ): Pair<GitHubReleaseAsset?, GitHubReleaseAsset?> {
        val apks = release.assets.filter { it.name?.endsWith(".apk", ignoreCase = true) == true }
        if (apks.isEmpty()) return null to null
        val lite = apks.firstOrNull { it.name.equals(APK_LITE_NAME, ignoreCase = true) }
        val full = apks.firstOrNull { it.name.equals(APK_FULL_NAME, ignoreCase = true) }
            ?: apks.filter { it !== lite }.maxByOrNull { it.size ?: 0 }
        return full to lite
    }

    /** 解析 tag 为 (版本号, 是否测试版, 是否强制版, 原始 tag)。支持 v 前缀与 -Beta/-Force 后缀。 */
    private fun parseTag(tagName: String?): ParsedTag? {
        val raw = tagName?.trim()?.removePrefix("v") ?: return null
        if (raw.isBlank()) return null
        // 非数字开头的 tag（如 dev / nightly）不属于语义化版本，稳定/测试渠道一律跳过
        if (raw.firstOrNull()?.isDigit() != true) return null
        val lower = raw.lowercase()
        val beta = lower.endsWith("-beta")
        val force = lower.endsWith("-force")
        var version = raw
        if (beta || force) version = raw.substringBeforeLast("-").trim()
        if (version.isBlank()) return null
        return ParsedTag(version, beta, force, tagName)
    }

    /** 语义化版本比较："1.10.0" > "1.9.9"。返回正数表示 a 更新。 */
    private fun compareVersions(a: String, b: String): Int {
        val pa = Regex("\\d+").findAll(a).take(4).mapNotNull { it.value.toIntOrNull() }.toList()
        val pb = Regex("\\d+").findAll(b).take(4).mapNotNull { it.value.toIntOrNull() }.toList()
        val max = maxOf(pa.size, pb.size)
        for (i in 0 until max) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
