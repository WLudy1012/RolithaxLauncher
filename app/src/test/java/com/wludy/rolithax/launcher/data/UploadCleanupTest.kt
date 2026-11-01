package com.wludy.rolithax.launcher.data

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadCleanupTest {
    @Test
    fun cancellationPropagatesAndDeletesServerChunks() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{"uploadId":"upload-1"}}"""))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("""{"code":200}"""))
        val repository = InstanceRepository()
        repository.configure(server.url("/").toString(), "", allowHttp = true)
        try {
            val job = launch { repository.uploadFileStream({ ByteArrayInputStream(byteArrayOf(1)) }, 1) {} }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals("/api/files/upload/delete/upload-1", withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }?.path)
        } finally {
            repository.close()
            server.shutdown()
        }
    }

    @Test
    fun failedChunkDeletesServerChunks() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{"uploadId":"upload-2"}}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("""{"code":500}"""))
        server.enqueue(MockResponse().setBody("""{"code":200}"""))
        val repository = InstanceRepository()
        repository.configure(server.url("/").toString(), "", allowHttp = true)
        try {
            assertTrue(repository.uploadFileStream({ ByteArrayInputStream(byteArrayOf(1)) }, 1) {}.isFailure)
            server.takeRequest(5, TimeUnit.SECONDS)
            server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("/api/files/upload/delete/upload-2", server.takeRequest(5, TimeUnit.SECONDS)?.path)
        } finally {
            repository.close()
            server.shutdown()
        }
    }
}
