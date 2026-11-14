package com.wludy.rolithax.launcher.data.localengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalJreManagerTest {
    @Test
    fun everySupportedArchiveUsesOnlyTheRolithaxRepositories() {
        val archives = LocalJreManager.RUNTIMES.flatMap { runtime ->
            runtime.specs.values.flatMap { it.allArchives }
        }.distinct()

        assertEquals(
            setOf(
                "jre8-universal.tar.xz",
                "jre8-arm64-bin.tar.xz",
                "jre8-x86_64-bin.tar.xz",
                "jre17-arm64-20210825-release.tar.xz",
                "jre17-x86_64-20210825-release.tar.xz",
                "jre21-arm64-20260223-release.tar.xz",
                "jre25-arm64-20260223-release.tar.xz",
            ),
            archives.map { it.assetName }.toSet(),
        )
        for (archive in archives) {
            assertTrue(archive.sha256.matches(Regex("[0-9a-f]{64}")))
            assertEquals(
                listOf(
                    "https://cnb.cool/WLudy/RolithaxLauncher/-/git/raw/main/runtimes/android/${archive.assetName}",
                    "https://raw.githubusercontent.com/WLudy1012/RolithaxLauncher/main/runtimes/android/${archive.assetName}",
                ),
                archive.sources,
            )
        }
    }
}
