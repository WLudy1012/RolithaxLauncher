package com.wludy.rolithax.launcher.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CnbReleaseSourceTest {

    @Test
    fun parsesTagRefsFromGitRefAdvertisement() {
        val text = listOf(
            "001e# service=git-upload-pack",
            "0000",
            "003edf9af4ad9fae4f8714da322bfb1aa9373ec68a21 refs/tags/v1.0.0",
            "004215588f5a4a9ae4f3b918f02ea308e0f69def78f9 refs/tags/v1.2.10^{}",
            "0044930c90dc57897f70adbcd4184c363a005ce2951e refs/tags/v1.2.14-Beta",
            "003fbfbb8e1e17a06e28a2b0a3a06e7fd8bd97f2f31 refs/heads/main",
            "0000",
        ).joinToString("\n")

        val tags = CnbReleaseSource.parseRefAdvertisement(text)

        assertEquals(listOf("v1.0.0", "v1.2.10", "v1.2.14-Beta"), tags)
    }

    @Test
    fun extractsNextDataScriptBody() {
        val html = """<html><body><script id="__NEXT_DATA__" type="application/json">{"a":1}</script></body></html>"""

        val json = CnbReleaseSource.extractNextData(html)

        assertEquals("{\"a\":1}", json)
    }

    @Test
    fun extractNextDataReturnsNullWhenAbsent() {
        assertNull(CnbReleaseSource.extractNextData("<html><body>no data</body></html>"))
    }

    @Test
    fun mapsReleasePayloadWithExactSizeAndDigest() {
        val json = """
            {"props":{"pageProps":{"releasesDetailData":{"release":{
              "tagRef":"refs/tags/v1.8.0",
              "title":"v1.8.0",
              "body":"# notes\n| `app-release.apk` | 48 MB | `539ac5268fd189817c3dbfa19d1672ec56b103db5e66785c61600e4930406454` |",
              "isPrerelease":false,
              "publishedAt":"2026-10-18T05:41:07Z",
              "assets":[
                {"name":"app-release.apk","path":"/WLudy/RolithaxLauncher/-/releases/download/v1.8.0/app-release.apk","hashAlgo":"sha256","hashValue":"539ac5268fd189817c3dbfa19d1672ec56b103db5e66785c61600e4930406454","sizeInByte":50058089},
                {"name":"app-release-lite.apk","path":"/WLudy/RolithaxLauncher/-/releases/download/v1.8.0/app-release-lite.apk","hashAlgo":"sha256","hashValue":"5e0fce4f19fcba8eec32051010ab5622f7e9481f2de4b42af8cda7094c0eb179","sizeInByte":11991596}
              ]
            }}}}}
        """.trimIndent()

        val release = CnbReleaseSource.parseReleasePayload(json, "v1.8.0")

        requireNotNull(release)
        assertEquals("v1.8.0", release.tagName)
        assertEquals(false, release.prerelease)
        assertTrue(release.body.orEmpty().contains("app-release.apk"))
        val full = release.assets.first { it.name == "app-release.apk" }
        assertEquals("https://cnb.cool/WLudy/RolithaxLauncher/-/releases/download/v1.8.0/app-release.apk", full.browserDownloadUrl)
        assertEquals(50058089L, full.size)
        assertEquals("sha256:539ac5268fd189817c3dbfa19d1672ec56b103db5e66785c61600e4930406454", full.digest)
    }

    @Test
    fun releasePayloadWithoutReleaseOrAssetsYieldsNull() {
        val empty = """{"props":{"pageProps":{"releasesDetailData":{"release":null}}}}"""
        val noAssets = """{"props":{"pageProps":{"releasesDetailData":{"release":{"tagRef":"refs/tags/v1.8.0"}}}}}"""
        val garbage = "not-json"

        assertNull(CnbReleaseSource.parseReleasePayload(empty, "v1.8.0"))
        assertNull(CnbReleaseSource.parseReleasePayload(garbage, "v1.8.0"))
        val mapped = CnbReleaseSource.parseReleasePayload(noAssets, "v1.8.0")
        requireNotNull(mapped)
        assertTrue(mapped.assets.isEmpty())
        assertEquals("v1.8.0", mapped.tagName)
    }
}
