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
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class SharedStatusAndJavaTest {
    @Test
    fun statusAndJavaMatchExistingModelsAndParameters() = runBlocking {
        val statusPayload = """
            {"code":200,"message":"MSLX Daemon 状态正常","data":{
                "clientName":"MSLX Daemon","version":"1.7.8-release-0b4b483","id":"test-user",
                "user":"Example","username":"example","roles":["user"],
                "systemInfo":{"osType":"Linux","osArchitecture":"X64"},"future":true
            }}
        """.trimIndent()
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
            assertEquals("1.7.8-release-0b4b483", shared.verify().version)
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
    fun verifiesAuthenticationWithoutMislabelingDeniedOrInvalidResponses() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":401,"message":"无效的 API 密钥"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":403,"message":"Access from localhost is forbidden. (禁止本机回环访问)"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":403,"message":"访问被拒绝：fixture-key"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("Forbidden"))
        server.enqueue(MockResponse().setBody("""{"code":400}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":[]}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{"systemInfo":"invalid"}}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))
        server.enqueue(MockResponse().setBody("""{"code":400}"""))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/").toString(), "fixture-key", original)
        try {
            assertEquals("Daemon 鉴权失败，请检查 API Key", runCatching { shared.verify() }.exceptionOrNull()?.message)
            assertEquals("Access from localhost is forbidden. (禁止本机回环访问)", runCatching { shared.verify() }.exceptionOrNull()?.message)
            assertEquals("访问被拒绝：***", runCatching { shared.verify() }.exceptionOrNull()?.message)
            assertEquals("Daemon 拒绝访问（403）", runCatching { shared.verify() }.exceptionOrNull()?.message)
            assertEquals("Daemon 返回了无效的状态响应", runCatching { shared.verify() }.exceptionOrNull()?.message)
            assertEquals("Daemon 未返回状态数据", runCatching { shared.verify() }.exceptionOrNull()?.message)
            assertEquals("Daemon 返回了无效的状态响应", runCatching { shared.verify() }.exceptionOrNull()?.message)
            assertEquals("Daemon 返回了无效的状态响应", runCatching { shared.verify() }.exceptionOrNull()?.message)
            assertEquals("Daemon 未返回状态数据", runCatching { shared.status() }.exceptionOrNull()?.message)
            assertEquals("获取 Java 列表失败", runCatching { shared.javaList(false) }.exceptionOrNull()?.message)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun doesNotExposeRawTransportErrorsDuringVerification() = runBlocking {
        val original = OkHttpClient.Builder()
            .addInterceptor { throw SocketTimeoutException("fixture-key") }
            .build()
        val shared = SharedDaemonConnection("http://127.0.0.1:1", "fixture-key", original)
        try {
            assertEquals("Daemon 连接超时", runCatching { shared.verify() }.exceptionOrNull()?.message)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
        }
    }
}
