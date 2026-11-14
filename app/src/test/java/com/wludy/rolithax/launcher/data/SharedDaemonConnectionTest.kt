package com.wludy.rolithax.launcher.data

import com.google.gson.Gson
import com.wludy.rolithax.launcher.data.model.ApiResponse
import com.wludy.rolithax.launcher.data.model.InstanceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SharedDaemonConnectionTest {
    @Test
    fun inheritsTheExistingDaemonCertificateTrust() = runBlocking {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val trustedCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        server.enqueue(MockResponse().setBody("""{"code":200,"data":{"id":7}}"""))
        val original = OkHttpClient.Builder()
            .sslSocketFactory(trustedCertificates.sslSocketFactory(), trustedCertificates.trustManager)
            .build()
        val shared = SharedDaemonConnection(server.url("/").toString(), "", original)
        try {
            assertEquals(7L, shared.instanceInfo(7).id)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun detailUsesExistingCredentialsAndPreservesGsonFieldsAndClientLifetime() = runBlocking {
        val payload = """{"code":200,"data":{"id":42,"name":"生存服","status":2,"statusText":"运行中","uptime":"1h","onlinePlayers":3,"java":"jre","core":"server.jar","future":true}}"""
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(payload))
        val baseUrl = server.url("/prefix").toString()
        val original = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("x-api-key", "test-key").build())
        }.build()
        val shared = SharedDaemonConnection(baseUrl, "test-key", original)
        try {
            val actual = shared.instanceInfo(42)
            val expected = Gson().fromJson(payload, ApiResponse::class.java).data
            val expectedInfo = Gson().fromJson(Gson().toJson(expected), InstanceInfo::class.java)
            assertEquals(expectedInfo, actual)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/prefix/api/instance/info?id=42", request.path)
            assertEquals(listOf("test-key"), request.headers.values("x-api-key"))
            shared.close()
            server.enqueue(MockResponse().setBody("ok"))
            original.newCall(Request.Builder().url("$baseUrl/api/instance/info?id=42").build()).execute().use {
                assertTrue(it.isSuccessful)
            }
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun businessFailureAndMissingDataKeepAndroidMessages() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("""{"code":400}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":403,"message":"无权限"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("not json"))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/").toString(), "", original)
        try {
            assertEquals("获取实例信息失败", runCatching { shared.instanceInfo(42) }.exceptionOrNull()?.message)
            assertEquals("返回数据为空", runCatching { shared.instanceInfo(42) }.exceptionOrNull()?.message)
            assertEquals("无权限", runCatching { shared.instanceInfo(42) }.exceptionOrNull()?.message)
            assertEquals("HTTP 403 请求失败", runCatching { shared.instanceInfo(42) }.exceptionOrNull()?.message)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun cancellingTheCallerCancelsTheDetailRequest() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("{} ").setBodyDelay(4, TimeUnit.SECONDS))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/").toString(), "", original)
        try {
            val job = launch { shared.instanceInfo(42) }
            val request = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            assertNotNull(request)
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun closingConnectionFailsItsRequestsWithoutCancellingTheCallerOrOriginalClient() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("{} ").setBodyDelay(4, TimeUnit.SECONDS))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/").toString(), "", original)
        try {
            val requestResult = async { runCatching { shared.instanceInfo(42) } }
            val request = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            assertNotNull(request)
            shared.close()
            val error = withTimeout(5_000) { requestResult.await().exceptionOrNull() }
            assertTrue(error is DaemonConnectionClosedException)
            assertTrue(!requestResult.isCancelled)
            assertTrue(!original.dispatcher.executorService.isShutdown)
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
