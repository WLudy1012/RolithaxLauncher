package com.wludy.rolithax.launcher.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

class DaemonRedirectTest {
    @Test
    fun daemonClientDoesNotForwardCredentialsOrPairingBody() {
        MockWebServer().use { origin ->
            MockWebServer().use { destination ->
                val connection = ApiClient.buildDaemonConnection(origin.url("/").toString(), "test-key")
                try {
                    origin.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/target")))
                    connection.client.newCall(Request.Builder().url(origin.url("/codes")).build()).execute().use {
                        assertEquals(302, it.code)
                    }
                    assertEquals("test-key", origin.takeRequest().getHeader("x-api-key"))
                    assertNull(destination.takeRequest(200, TimeUnit.MILLISECONDS))

                    origin.enqueue(MockResponse().setResponseCode(307).addHeader("Location", destination.url("/target")))
                    val body = """{"previousApiKey":"old-test-key"}""".toRequestBody("application/json".toMediaType())
                    connection.client.newCall(Request.Builder().url(origin.url("/redeem")).post(body).build()).execute().use {
                        assertEquals(307, it.code)
                    }
                    assertEquals("test-key", origin.takeRequest().getHeader("x-api-key"))
                    assertNull(destination.takeRequest(200, TimeUnit.MILLISECONDS))
                } finally {
                    connection.client.dispatcher.executorService.shutdown()
                    connection.client.connectionPool.evictAll()
                }
            }
        }
    }
}
