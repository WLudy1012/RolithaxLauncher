package com.wludy.rolithax.launcher.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.wludy.rolithax.launcher.data.model.ApiResponse
import com.wludy.rolithax.launcher.data.model.CreateServerRequest
import com.wludy.rolithax.launcher.data.model.ServerCoreMirror
import com.wludy.rolithax.launcher.data.model.ServerSettings
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

class SharedInstanceManagementTest {
    @Test
    fun instanceOperationsMatchGsonPayloadAndResponseSemantics() = runBlocking {
        val settingsResponse = """{"code":200,"data":{"id":42,"name":"生存服","java":"MSLX://Java/21","core":"server.jar","maxM":4096,"future":true}}"""
        val settings = ServerSettings(id = 42, name = "新名称", java = "MSLX://Java/21", dockerVolumes = null)
        val create = CreateServerRequest(name = "新实例", core = "server.jar", minM = 1024, maxM = 2048,
            coreUrl = "https://example.invalid/server.jar", coreSha256 = "a".repeat(64),
            coreMirrors = listOf(ServerCoreMirror("https://mirror.invalid/server.jar", "a".repeat(64))))
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(settingsResponse))
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"已保存","data":{"needListen":true}}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{"serverId":"abc"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"已删除"}"""))
        server.enqueue(MockResponse().setBody("""{"code":200}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))
        server.enqueue(MockResponse().setBody("""{"code":400}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":403,"message":"无权限"}"""))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/prefix").toString(), "test-key", original)
        try {
            val type = object : TypeToken<ApiResponse<ServerSettings>>() {}.type
            assertEquals(Gson().fromJson<ApiResponse<ServerSettings>>(settingsResponse, type).data, shared.getSettings(42))
            assertEquals("/prefix/api/instance/settings/general/42", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals("已保存" to true, shared.updateSettings(42, settings))
            val save = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("test-key", save.getHeader("x-api-key"))
            assertEquals(JsonParser.parseString(Gson().toJson(settings)), JsonParser.parseString(save.body.readUtf8()))
            assertEquals("abc", shared.createInstance(create))
            val creation = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/prefix/api/instance/createServer", creation.path)
            assertEquals(JsonParser.parseString(Gson().toJson(create)), JsonParser.parseString(creation.body.readUtf8()))
            assertEquals("已删除", shared.deleteInstance(42, true))
            assertEquals(JsonParser.parseString("""{"id":42,"deleteFiles":true}"""), JsonParser.parseString(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()))
            assertEquals("取消信号已发送", shared.cancelCreation("abc", false))
            assertEquals(JsonParser.parseString("""{"serverId":"abc","cleanupFiles":false}"""), JsonParser.parseString(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()))
            assertEquals("服务器未返回 ServerId", runCatching { shared.createInstance(create) }.exceptionOrNull()?.message)
            assertEquals("获取设置失败", runCatching { shared.getSettings(42) }.exceptionOrNull()?.message)
            assertEquals("无权限", runCatching { shared.deleteInstance(42, false) }.exceptionOrNull()?.message)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun closingConnectionCancelsInFlightSettingsUpdate() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(4, TimeUnit.SECONDS))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/").toString(), "", original)
        try {
            val job = launch { shared.updateSettings(42, ServerSettings()) }
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
