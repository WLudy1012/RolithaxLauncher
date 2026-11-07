package com.wludy.rolithax.launcher.data.migration

import com.google.gson.Gson
import com.wludy.rolithax.launcher.data.model.InstanceSummary
import com.wludy.rolithax.launcher.shared.DaemonInstance
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DaemonWireCompatibilityTest {
    @Test
    fun instancePayloadHasTheSameMeaningForAndroidAndSharedClients() {
        val payload = """{"id":42,"name":"生存服","status":2,"statusText":"运行中","core":"server.jar","extra":{"onlinePlayers":3},"futureField":"ignored"}"""
        val android = Gson().fromJson(payload, InstanceSummary::class.java)
        val shared = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            .decodeFromString<DaemonInstance>(payload)

        assertEquals(android.id, shared.id)
        assertEquals(android.name, shared.name)
        assertEquals(android.status, shared.status)
        assertEquals(android.statusText, shared.statusText)
        assertEquals(android.core, shared.core)
        assertEquals(android.extra?.onlinePlayers, shared.extra?.onlinePlayers)
    }
}
