package com.wludy.rolithax.launcher.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DaemonConnectionVerificationTest {
    @Test
    fun rejectedApiKeyDoesNotReplaceAnExistingConnection() = runBlocking {
        MockWebServer().use { daemon ->
            daemon.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401,"message":"unauthorized"}"""))
            val config = DaemonConfig(baseUrl = daemon.url("/").toString(), apiKey = "original-key", allowHttp = true)
            val registry = DaemonRegistry()
            try {
                registry.sync(AppSettings(daemons = listOf(config)))
                val error = runCatching {
                    registry.verifyConnection(config.copy(apiKey = "wrong-key"))
                }.exceptionOrNull()
                assertEquals("Daemon 鉴权失败，请检查 API Key", error?.message)
                assertEquals("wrong-key", daemon.takeRequest().getHeader("x-api-key"))
                assertEquals("original-key", registry.repositoryFor(config.id)?.apiKey)
                assertNull(registry.statusOf(config.id).activeBaseUrl)
            } finally {
                registry.close()
            }
        }
    }

    @Test
    fun verifiedBackupIsUsedImmediatelyWithoutChangingTheSavedPrimary() = runBlocking {
        MockWebServer().use { primary ->
            MockWebServer().use { backup ->
                primary.enqueue(MockResponse().setResponseCode(503))
                backup.enqueue(MockResponse().setBody("""{"code":200,"data":{"version":"1.7.7"}}"""))
                backup.enqueue(MockResponse().setBody("""{"code":200,"data":[]}"""))
                val config = DaemonConfig(
                    baseUrl = primary.url("/").toString(),
                    apiKey = "test-key",
                    allowHttp = true,
                    endpoints = listOf(backup.url("/daemon/").toString()),
                )
                val registry = DaemonRegistry()
                try {
                    val verified = registry.verifyConnection(config)
                    assertNull(registry.repositoryFor(config.id))
                    registry.syncVerified(AppSettings(daemons = listOf(config)), config, verified)
                    assertEquals(DaemonState.ONLINE, registry.statusOf(config.id).state)
                    assertEquals(backup.url("/daemon").toString(), registry.statusOf(config.id).activeBaseUrl)
                    assertEquals(backup.url("/daemon").toString(), registry.repositoryFor(config.id)?.baseUrl)
                    assertEquals(emptyList<Any>(), registry.repositoryFor(config.id)?.listInstances()?.getOrThrow())
                    assertEquals("/api/status", primary.takeRequest().path)
                    assertEquals("/daemon/api/status", backup.takeRequest().path)
                    assertEquals("/daemon/api/instance/list", backup.takeRequest().path)
                    assertEquals(primary.url("/").toString(), config.baseUrl)
                } finally {
                    registry.close()
                }
            }
        }
    }
}
