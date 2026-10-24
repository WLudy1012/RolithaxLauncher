package com.wludy.rolithax.launcher.data

import com.wludy.rolithax.launcher.data.remote.GitHubRelease
import com.wludy.rolithax.launcher.data.remote.GitHubReleaseAsset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRepositoryFallbackTest {

    private val fullDigest = "539ac5268fd189817c3dbfa19d1672ec56b103db5e66785c61600e4930406454"
    private val liteDigest = "5e0fce4f19fcba8eec32051010ab5622f7e9481f2de4b42af8cda7094c0eb179"

    private fun release(
        tag: String,
        full: Pair<String, Long>? = "app-release.apk" to 50_058_089L,
        lite: Pair<String, Long>? = "app-release-lite.apk" to 11_991_596L,
        body: String = "release notes",
        prerelease: Boolean = false,
    ): GitHubRelease {
        val assets = buildList {
            if (full != null) add(
                GitHubReleaseAsset(
                    name = full.first,
                    browserDownloadUrl = "https://cnb.cool/WLudy/RolithaxLauncher/-/releases/download/$tag/${full.first}",
                    size = full.second,
                    digest = "sha256:$fullDigest",
                )
            )
            if (lite != null) add(
                GitHubReleaseAsset(
                    name = lite.first,
                    browserDownloadUrl = "https://cnb.cool/WLudy/RolithaxLauncher/-/releases/download/$tag/${lite.first}",
                    size = lite.second,
                    digest = "sha256:$liteDigest",
                )
            )
        }
        return GitHubRelease(
            tagName = tag,
            name = tag,
            body = body,
            prerelease = prerelease,
            publishedAt = "2026-10-18T05:41:07Z",
            assets = assets,
        )
    }

    private fun repository(
        github: suspend () -> List<GitHubRelease>,
        tags: suspend () -> List<String>,
        releasePage: suspend (String) -> GitHubRelease?,
    ) = UpdateRepository(githubReleases = github, cnbTags = tags, cnbRelease = releasePage)

    @Test
    fun githubFailureFallsBackToCnbMetadata() = runBlocking {
        val repo = repository(
            github = { throw IllegalStateException("network down") },
            tags = { listOf("v1.8.0", "v1.7.12-Beta") },
            releasePage = { tag -> if (tag == "v1.8.0") release("v1.8.0") else null },
        )

        val update = repo.checkLatest("1.7.12", UpdateChannel.STABLE).getOrThrow()

        requireNotNull(update)
        assertEquals("1.8.0", update.version)
        assertFalse(update.beta)
        assertEquals(50_058_089L, update.apkSize)
        assertEquals(fullDigest, update.sha256)
        assertTrue(update.embeddedJre)
        assertEquals(
            "https://cnb.cool/WLudy/RolithaxLauncher/-/releases/download/v1.8.0/app-release.apk",
            update.downloadUrl,
        )
        assertEquals(
            "https://cnb.cool/WLudy/RolithaxLauncher/-/releases/download/v1.8.0/app-release.apk",
            update.cnbUrl,
        )
        assertEquals("app-release-lite.apk", update.liteUrl?.substringAfterLast('/'))
        assertEquals(liteDigest, update.liteSha256)
        assertTrue(update.hasLiteVariant)
    }

    @Test
    fun githubSuccessDoesNotTouchCnb() = runBlocking {
        val repo = repository(
            github = { listOf(release("v1.8.0")) },
            tags = { throw IllegalStateException("CNB 不应被调用") },
            releasePage = { throw IllegalStateException("CNB 不应被调用") },
        )

        val update = repo.checkLatest("1.7.12", UpdateChannel.STABLE).getOrThrow()

        assertEquals("1.8.0", update?.version)
    }

    @Test
    fun emptyGitHubListMeansNoUpdateWithoutFallback() = runBlocking {
        val repo = repository(
            github = { emptyList() },
            tags = { throw IllegalStateException("CNB 不应被调用") },
            releasePage = { throw IllegalStateException("CNB 不应被调用") },
        )

        val update = repo.checkLatest("1.7.12", UpdateChannel.STABLE).getOrThrow()

        assertNull(update)
    }

    @Test
    fun stableChannelSkipsBetaTags() = runBlocking {
        val repo = repository(
            github = { throw IllegalStateException("network down") },
            tags = { listOf("v1.7.13-Beta") },
            releasePage = { release("v1.7.13-Beta") },
        )

        assertNull(repo.checkLatest("1.7.12", UpdateChannel.STABLE).getOrThrow())

        val beta = repo.checkLatest("1.7.12", UpdateChannel.BETA).getOrThrow()
        requireNotNull(beta)
        assertEquals("1.7.13", beta.version)
        assertTrue(beta.beta)
    }

    @Test
    fun forceTagAmongCandidatesMarksForceUpdate() = runBlocking {
        val repo = repository(
            github = { throw IllegalStateException("network down") },
            tags = { listOf("v1.8.0", "v1.9.0-Force") },
            releasePage = { tag -> if (tag == "v1.9.0-Force") release("v1.9.0-Force") else null },
        )

        val update = repo.checkLatest("1.7.12", UpdateChannel.STABLE).getOrThrow()

        requireNotNull(update)
        assertEquals("1.9.0", update.version)
        assertTrue(update.forceUpdate)
    }

    @Test
    fun legacyForceMarkerInReleaseBodyStillForces() = runBlocking {
        val repo = repository(
            github = { throw IllegalStateException("network down") },
            tags = { listOf("v1.9.0") },
            releasePage = { release("v1.9.0", body = "本次为强制更新，请务必升级") },
        )

        val update = repo.checkLatest("1.7.12", UpdateChannel.STABLE).getOrThrow()

        requireNotNull(update)
        assertTrue(update.forceUpdate)
    }

    @Test
    fun newestTagWithoutApkFallsBackToOlderRelease() = runBlocking {
        val repo = repository(
            github = { throw IllegalStateException("network down") },
            tags = { listOf("v1.9.0", "v1.8.0") },
            releasePage = { tag ->
                when (tag) {
                    "v1.9.0" -> release("v1.9.0", full = null, lite = null)
                    else -> release("v1.8.0")
                }
            },
        )

        val update = repo.checkLatest("1.7.12", UpdateChannel.STABLE).getOrThrow()

        requireNotNull(update)
        assertEquals("1.8.0", update.version)
    }

    @Test
    fun notNewerThanCurrentReturnsNull() = runBlocking {
        val repo = repository(
            github = { throw IllegalStateException("network down") },
            tags = { listOf("v1.8.0") },
            releasePage = { release("v1.8.0") },
        )

        assertNull(repo.checkLatest("1.8.0", UpdateChannel.STABLE).getOrThrow())
        assertNull(repo.checkLatest("1.8.1", UpdateChannel.STABLE).getOrThrow())
    }

    @Test
    fun bothSourcesUnavailableReturnsFailure() = runBlocking {
        val repo = repository(
            github = { throw IllegalStateException("github down") },
            tags = { throw IllegalStateException("cnb down") },
            releasePage = { null },
        )

        assertTrue(repo.checkLatest("1.7.12", UpdateChannel.STABLE).isFailure)
    }

    @Test
    fun cancellationIsNotSwallowedByFallback() = runBlocking {
        val repo = repository(
            github = { throw kotlinx.coroutines.CancellationException("cancelled") },
            tags = { throw IllegalStateException("CNB 不应被调用") },
            releasePage = { throw IllegalStateException("CNB 不应被调用") },
        )

        var thrown: Throwable? = null
        try {
            repo.checkLatest("1.7.12", UpdateChannel.STABLE).getOrThrow()
        } catch (e: kotlinx.coroutines.CancellationException) {
            thrown = e
        }
        assertNotNull(thrown)
    }
}
