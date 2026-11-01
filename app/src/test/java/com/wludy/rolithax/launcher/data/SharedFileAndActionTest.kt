package com.wludy.rolithax.launcher.data

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SharedFileAndActionTest {
    @Test
    fun fileAndActionRequestsPreservePayloadsMessagesAndErrors() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("""{"code":200,"data":"中文\n内容"}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"已保存"}"""))
        server.enqueue(MockResponse().setBody("""{"code":200}"""))
        server.enqueue(MockResponse().setBody("""{"code":400}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":403,"message":"无权限"}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/prefix").toString(), "test-key", original)
        try {
            assertEquals("中文\n内容", shared.fileContent(42, "目录 名/a&b.txt"))
            val read = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/prefix/api/files/instance/42/content", read.requestUrl?.encodedPath)
            assertEquals("目录 名/a&b.txt", read.requestUrl?.queryParameter("path"))
            assertEquals("test-key", read.getHeader("x-api-key"))

            assertEquals("已保存", shared.saveFileContent(42, "目录/a.txt", "正文\n"))
            val save = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("POST", save.method)
            assertEquals(JsonParser.parseString("""{"path":"目录/a.txt","content":"正文\n"}"""), JsonParser.parseString(save.body.readUtf8()))

            assertEquals("操作成功", shared.sendAction(42, "backup"))
            val action = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals(JsonParser.parseString("""{"id":42,"action":"backup"}"""), JsonParser.parseString(action.body.readUtf8()))
            assertEquals("操作失败", runCatching { shared.sendAction(42, "stop") }.exceptionOrNull()?.message)
            assertEquals("无权限", runCatching { shared.saveFileContent(42, "a", "b") }.exceptionOrNull()?.message)
            assertEquals("返回数据为空", runCatching { shared.fileContent(42, "a") }.exceptionOrNull()?.message)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun closingConnectionCancelsInFlightFileRead() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(4, TimeUnit.SECONDS))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/").toString(), "", original)
        try {
            val job = launch { shared.fileContent(42, "a") }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            shared.close()
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
