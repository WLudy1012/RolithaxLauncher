package com.wludy.rolithax.launcher.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.wludy.rolithax.launcher.data.model.AdminCreateUserRequest
import com.wludy.rolithax.launcher.data.model.AdminUpdateUserRequest
import com.wludy.rolithax.launcher.data.model.ApiResponse
import com.wludy.rolithax.launcher.data.model.FrpSummary
import com.wludy.rolithax.launcher.data.model.UpdateSelfRequest
import com.wludy.rolithax.launcher.data.model.UserInfo
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.TimeUnit

class SharedUserManagementTest {
    @Test
    fun userAndFrpPayloadsPreserveGsonAndRequestFields() = runBlocking {
        val userPayload = """{"code":200,"data":{"id":"u-1","username":"alice","avatar":"url","role":"admin","resources":["instance:42"],"future":true}}"""
        val usersPayload = """{"code":200,"data":[{"id":"u-1","username":"alice","resources":["instance:42"]}]}"""
        val frpPayload = """{"code":200,"data":[{"id":7,"name":"tunnel","service":"tcp"}]}"""
        val self = UpdateSelfRequest(username = "alice", oldPassword = "old-password", password = "new-password", resetApiKey = true, resources = listOf("instance:42"))
        val create = AdminCreateUserRequest("bob", "create-password", "Bob", "user", listOf("instance:42"))
        val update = AdminUpdateUserRequest(name = "Bob", avatar = "avatar", password = "update-password", resources = listOf("instance:42"))
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody(userPayload))
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"已更新"}"""))
        server.enqueue(MockResponse().setBody(usersPayload))
        server.enqueue(MockResponse().setBody("""{"code":200}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"已更新"}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"已删除"}"""))
        server.enqueue(MockResponse().setBody(frpPayload))
        server.enqueue(MockResponse().setBody("""{"code":200,"data":null}"""))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/prefix").toString(), "api-key", original)
        try {
            val userType = object : TypeToken<ApiResponse<UserInfo>>() {}.type
            assertEquals(Gson().fromJson<ApiResponse<UserInfo>>(userPayload, userType).data, shared.userMe())
            assertEquals("/prefix/api/user/me", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            assertEquals("已更新", shared.updateSelf(self))
            val selfRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("api-key", selfRequest.getHeader("x-api-key"))
            assertEquals(JsonParser.parseString(Gson().toJson(self)), JsonParser.parseString(selfRequest.body.readUtf8()))
            val usersType = object : TypeToken<ApiResponse<List<UserInfo>>>() {}.type
            assertEquals(Gson().fromJson<ApiResponse<List<UserInfo>>>(usersPayload, usersType).data, shared.adminUserList())
            server.takeRequest(5, TimeUnit.SECONDS)
            assertEquals("创建成功", shared.adminCreateUser(create))
            assertEquals(JsonParser.parseString(Gson().toJson(create)), JsonParser.parseString(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()))
            assertEquals("已更新", shared.adminUpdateUser("user id", update))
            val updateRequest = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/prefix/api/admin/user/update/user%20id", updateRequest.path)
            assertEquals(JsonParser.parseString(Gson().toJson(update)), JsonParser.parseString(updateRequest.body.readUtf8()))
            assertEquals("已删除", shared.adminDeleteUser("u-1"))
            assertEquals("/prefix/api/admin/user/delete/u-1", server.takeRequest(5, TimeUnit.SECONDS)?.path)
            val frpType = object : TypeToken<ApiResponse<List<FrpSummary>>>() {}.type
            assertEquals(Gson().fromJson<ApiResponse<List<FrpSummary>>>(frpPayload, frpType).data, shared.frpList())
            assertEquals(emptyList<UserInfo>(), shared.adminUserList())
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }

    @Test
    fun userErrorsDoNotExposeSubmittedPasswords() = runBlocking {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("""{"code":400,"message":"invalid old-password and new-password"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":403,"message":"invalid create-password"}"""))
        server.enqueue(MockResponse().setBody("""{"code":200,"message":"updated update-password"}"""))
        val original = OkHttpClient()
        val shared = SharedDaemonConnection(server.url("/").toString(), "", original)
        try {
            val selfError = runCatching { shared.updateSelf(UpdateSelfRequest(oldPassword = "old-password", password = "new-password")) }.exceptionOrNull()!!
            assertFalse(selfError.toString().contains("old-password"))
            assertFalse(selfError.toString().contains("new-password"))
            val createError = runCatching { shared.adminCreateUser(AdminCreateUserRequest("bob", "create-password")) }.exceptionOrNull()!!
            assertFalse(createError.toString().contains("create-password"))
            assertEquals("updated ***", shared.adminUpdateUser("u-1", AdminUpdateUserRequest(password = "update-password")))
        } finally {
            shared.close()
            original.dispatcher.executorService.shutdown()
            original.connectionPool.evictAll()
            server.shutdown()
        }
    }
}
