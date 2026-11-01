package com.wludy.rolithax.launcher.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wludy.rolithax.launcher.data.model.ApiResponse
import com.wludy.rolithax.launcher.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SharedFileListTest {
    @Test
    fun pathAndResultsMatchExistingAndroidClient() = runBlocking {
        val payload = """{"code":200,"data":[{"name":"配置","type":"folder","size":0},{"name":"server.properties","type":"file","size":42,"lastModified":"now","permission":"rw"}]}"""
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(payload))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))
        server.enqueue(MockResponse().setBody("""{"code":400}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":403,"message":"无权限"}"""))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/prefix").toString(), "test-key", original)
        try {
            val type = object : TypeToken<ApiResponse<List<FileItem>>>() {}.type
            val expected = Gson().fromJson<ApiResponse<List<FileItem>>>(payload, type).data
            assertEquals(expected, shared.fileList(42, "目录 名/a&b.txt"))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/prefix/api/files/instance/42/lists", request.requestUrl?.encodedPath)
            assertEquals("目录 名/a&b.txt", request.requestUrl?.queryParameter("path"))
            assertEquals("test-key", request.getHeader("x-api-key"))
            assertEquals(emptyList<FileItem>(), shared.fileList(42, ""))
            assertEquals("获取文件列表失败", runCatching { shared.fileList(42, "") }.exceptionOrNull()?.message)
            assertEquals("无权限", runCatching { shared.fileList(42, "") }.exceptionOrNull()?.message)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun cancellingCallerStopsFileListRequest() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(4, TimeUnit.SECONDS))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/").toString(), "", original)
        try {
            val job = launch { shared.fileList(42, "目录") }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
