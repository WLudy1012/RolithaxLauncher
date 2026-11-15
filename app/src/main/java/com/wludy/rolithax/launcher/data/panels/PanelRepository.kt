package com.wludy.rolithax.launcher.data.panels

import android.content.Context
import com.wludy.rolithax.launcher.shared.panels.PanelClient
import com.wludy.rolithax.launcher.shared.panels.PanelProfile
import com.wludy.rolithax.launcher.shared.panels.createPanelClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class PanelRepository(context: Context) {
    val store = PanelStore(context)

    suspend fun <T> withClient(profile: PanelProfile, action: suspend (PanelClient) -> T): T = withContext(Dispatchers.IO) {
        val http = HttpClient(OkHttp) {
            followRedirects = false
            engine {
                config {
                    followRedirects(false)
                    followSslRedirects(false)
                    connectTimeout(15, TimeUnit.SECONDS)
                    readTimeout(30, TimeUnit.SECONDS)
                    writeTimeout(20, TimeUnit.SECONDS)
                }
            }
        }
        try {
            val panel = createPanelClient(profile, http)
            try { action(panel) } finally { panel.close() }
        } finally {
            http.close()
        }
    }
}
