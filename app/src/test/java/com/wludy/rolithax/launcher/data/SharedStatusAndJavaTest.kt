package com.wludy.rolithax.launcher.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wludy.rolithax.launcher.data.model.ApiResponse
import com.wludy.rolithax.launcher.data.model.LocalJava
import com.wludy.rolithax.launcher.data.model.StatusData
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class SharedStatusAndJavaTest {
    @Test
    fun statusAndJavaMatchExistingModelsAndParameters() = runBlocking {
        val statusPayload = """{"code":200,"data":{"clientName":"Daemon","version":"1.0","systemInfo":{"osType":"Linux","cpuUsage":12.5},"future":true}}"""
        val javaPayload = """{"code":200,"data":[{"path":"/java","version":"21","vendor":"Example","is64Bit":true}]}"""
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(statusPayload))
        server.enqueue(MockResponse().setBody(statusPayload))
        server.enqueue(MockResponse().setBody(javaPayload))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/prefix").toString(), "test-key", original)
        try {
            shared.verify()
            val verify = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/prefix/api/status", verify.path)
            assertEquals("test-key", verify.getHeader("x-api-key"))
            val statusType = object : TypeToken<ApiResponse<StatusData>>() {}.type
            val expectedStatus = Gson().fromJson<ApiResponse<StatusData>>(statusPayload, statusType).data
            assertEquals(expectedStatus, shared.status())
            server.takeRequest(5, TimeUnit.SECONDS)
            val javaType = object : TypeToken<ApiResponse<List<LocalJava>>>() {}.type
            val expectedJava = Gson().fromJson<ApiResponse<List<LocalJava>>>(javaPayload, javaType).data
            assertEquals(expectedJava, shared.javaList(true))
            assertEquals("/prefix/api/java/list?refresh=true", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals(emptyList<LocalJava>(), shared.javaList(false))
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun authenticationAndMissingResponsesPreserveAndroidErrors() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":403,"message":"自定义错误"}"""))
        server.enqueue(MockResponse().setBody("""{"code":400}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))
        server.enqueue(MockResponse().setBody("""{"code":400}"""))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/").toString(), "", original)
        try {
            assertEquals("Daemon 鉴权失败，请检查 API Key", runCatching { shared.verify() }.exceptionOrNull()?.message)
            assertEquals("Daemon 鉴权失败，请检查 API Key", runCatching { shared.verify() }.exceptionOrNull()?.message)
            assertEquals("Daemon 鉴权失败：未返回有效状态", runCatching { shared.verify() }.exceptionOrNull()?.message)
            assertEquals("返回数据为空", runCatching { shared.status() }.exceptionOrNull()?.message)
            assertEquals("获取 Java 列表失败", runCatching { shared.javaList(false) }.exceptionOrNull()?.message)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
