package com.wludy.rolithax.launcher.data

import com.wludy.rolithax.launcher.data.remote.SystemMonitorClient
import com.wludy.rolithax.launcher.shared.ConsoleEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DaemonSignalRTransportTest {
    @Test
    fun okHttpConsoleStopsAfterDeniedWebSocketUpgrade() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(
                """{"connectionToken":"fixture","availableTransports":[{"transport":"WebSockets","transferFormats":["Text"]}]}""",
            ))
            server.enqueue(MockResponse().setResponseCode(403))
            val original = OkHttpClient()
            val connection = SharedDaemonConnection(server.url("/").toString(), "fixture-key", original)
            try {
                val events = mutableListOf<ConsoleEvent>()
                withTimeout(5_000) {
                    connection.runConsole(7, null, onSession = {}, onEvent = { events += it })
                }
                assertEquals(listOf(ConsoleEvent.AuthenticationFailed(403)), events)
                assertEquals(2, server.requestCount)
            } finally {
                connection.close()
                original.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test
    fun javaMonitorRecognizesRejectedNegotiation() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            val client = SystemMonitorClient(server.url("/").toString(), "fixture-key") {}
            try {
                assertTrue(runCatching { client.connect() }.isFailure)
                assertTrue(client.authenticationRejected)
                assertEquals(1, server.requestCount)
            } finally {
                client.close()
            }
        }
    }
}
