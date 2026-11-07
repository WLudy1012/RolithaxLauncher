package com.wludy.rolithax.launcher.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.wludy.rolithax.launcher.data.model.ApiResponse
import com.wludy.rolithax.launcher.data.model.InstanceSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
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

class SharedInstanceListTest {
    @Test
    fun listMatchesRetrofitGsonAndKeepsAuthentication() = runBlocking {
        val payload = """{"code":200,"data":[{"id":42,"name":"生存服","core":"server.jar","status":2,"extra":{"onlinePlayers":3},"future":true},{"id":43,"name":null}]}"""
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(payload))
        val original = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("x-api-key", "test-key").build())
        }.build()
        val shared = SharedDaemonConnection(server.url("/prefix").toString(), "test-key", original)
        try {
            val type = object : TypeToken<ApiResponse<List<InstanceSummary>>>() {}.type
            val expected = Gson().fromJson<ApiResponse<List<InstanceSummary>>>(payload, type).data
            assertEquals(expected, shared.listInstances())
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/prefix/api/instance/list", request.path)
            assertEquals(listOf("test-key"), request.headers.values("x-api-key"))
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun nullDataAndErrorsMatchExistingAndroidBehavior() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))
        server.enqueue(MockResponse().setBody("""{"code":400}"""))
        server.enqueue(MockResponse().setBody("""{"code":400,"message":"业务失败"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":403,"message":"无权限"}"""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("not json"))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/").toString(), "", original)
        try {
            assertEquals(emptyList<InstanceSummary>(), shared.listInstances())
            assertEquals("获取实例列表失败", runCatching { shared.listInstances() }.exceptionOrNull()?.message)
            assertEquals("业务失败", runCatching { shared.listInstances() }.exceptionOrNull()?.message)
            assertEquals("无权限", runCatching { shared.listInstances() }.exceptionOrNull()?.message)
            assertEquals("HTTP 500 请求失败", runCatching { shared.listInstances() }.exceptionOrNull()?.message)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun cancellingListCallerAndClosingConnectionStopTheirRequests() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(4, TimeUnit.SECONDS))
        server.enqueue(MockResponse().setBody("{}").setBodyDelay(4, TimeUnit.SECONDS))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/").toString(), "", original)
        try {
            val first = launch { shared.listInstances() }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            first.cancelAndJoin()
            assertTrue(first.isCancelled)
            val second = launch { shared.listInstances() }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
            shared.close()
            withTimeout(5_000) { second.join() }
            assertTrue(second.isCancelled)
            assertTrue(!original.dispatcher.executorService.isShutdown)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
