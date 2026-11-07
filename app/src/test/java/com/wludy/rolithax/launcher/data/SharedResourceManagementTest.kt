package com.wludy.rolithax.launcher.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.wludy.rolithax.launcher.data.model.ApiResponse
import com.wludy.rolithax.launcher.data.model.PmListData
import com.wludy.rolithax.launcher.data.model.PmSetRequest
import com.wludy.rolithax.launcher.data.model.ResourceInstallBatchRequest
import com.wludy.rolithax.launcher.data.model.ResourceInstallItemRequest
import com.wludy.rolithax.launcher.data.model.ResourceInstallTaskData
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class SharedResourceManagementTest {
    @Test
    fun pluginListAndActionsPreserveLegacySemantics() = runBlocking {
        val payload = """{"code":200,"data":{"totalCount":2,"jarFiles":["a.jar"],"disableJarFiles":["b.jar"]}}"""
        val body = PmSetRequest("plugins", "disable", listOf("a.jar"))
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(payload))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":403,"message":"不应显示"}"""))
        server.enqueue(MockResponse().setBody("""{"code":400,"message":"权限不足"}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"已禁用"}"""))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/prefix").toString(), "test-key", original)
        try {
            val type = object : TypeToken<ApiResponse<PmListData>>() {}.type
            assertEquals(Gson().fromJson<ApiResponse<PmListData>>(payload, type).data, shared.pmList(42, "plugins"))
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/prefix/api/files/pm/instance/42/list", request.requestUrl?.encodedPath)
            assertEquals("plugins", request.requestUrl?.queryParameter("mode"))
            assertEquals("false", request.requestUrl?.queryParameter("checkClient"))
            assertEquals(PmListData(), shared.pmList(42, "plugins"))
            assertEquals("获取列表失败", runCatching { shared.pmList(42, "plugins") }.exceptionOrNull()?.message)
            assertEquals("权限不足", runCatching { shared.pmList(42, "plugins") }.exceptionOrNull()?.message)
            assertEquals("已禁用", shared.pmSet(42, body))
            repeat(3) { server.takeRequest(5, TimeUnit.SECONDS) }
            assertEquals(JsonParser.parseString(Gson().toJson(body)), JsonParser.parseString(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()))
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun resourceTasksPreserveAcceptedCodesAndPaths() = runBlocking {
        val body = ResourceInstallBatchRequest(42, "1.21", "fabric", listOf(ResourceInstallItemRequest("p1", "v1", "mod")))
        val payload = """{"code":202,"data":{"id":"task-1","status":"queued","progress":0,"message":""}}"""
        val taskPayload = """{"code":200,"data":{"id":"task-1","status":"running","progress":45,"message":"下载中","installedFiles":["a.jar"]}}"""
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(202).setBody(payload))
        server.enqueue(MockResponse().setBody(taskPayload))
        server.enqueue(MockResponse().setBody("""{"code":200}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"code":201,"data":{"id":"task-2","status":"queued","progress":0,"message":""}}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))
        server.enqueue(MockResponse().setBody("""{"code":400}"""))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/").toString(), "test-key", original)
        try {
            val type = object : TypeToken<ApiResponse<ResourceInstallTaskData>>() {}.type
            assertEquals(Gson().fromJson<ApiResponse<ResourceInstallTaskData>>(payload, type).data, shared.installResources(body))
            val install = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/plugin/mslx-plugin-rolithax/resources/install", install.path)
            assertEquals(JsonParser.parseString(Gson().toJson(body)), JsonParser.parseString(install.body.readUtf8()))
            assertEquals(Gson().fromJson<ApiResponse<ResourceInstallTaskData>>(taskPayload, type).data, shared.resourceInstallTask("task 1"))
            assertEquals("/api/plugin/mslx-plugin-rolithax/resources/install/task%201", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            shared.cancelResourceInstall("task-1")
            assertEquals("/api/plugin/mslx-plugin-rolithax/resources/install/task-1/cancel", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals("task-2", shared.installResources(body).id)
            assertEquals("Daemon 未返回安装进度", runCatching { shared.resourceInstallTask("task-1") }.exceptionOrNull()?.message)
            assertEquals("取消资源安装失败", runCatching { shared.cancelResourceInstall("task-1") }.exceptionOrNull()?.message)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
