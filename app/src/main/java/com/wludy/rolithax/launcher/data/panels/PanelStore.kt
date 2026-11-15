package com.wludy.rolithax.launcher.data.panels

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wludy.rolithax.launcher.data.CryptoManager
import com.wludy.rolithax.launcher.shared.panels.PanelProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.panelDataStore by preferencesDataStore(name = "remote_panels")

class PanelStore(context: Context) {
    private val store = context.applicationContext.panelDataStore
    private val key = stringPreferencesKey("profiles")
    private val json = Json { ignoreUnknownKeys = true }

    val profiles: Flow<List<PanelProfile>> = store.data.map { decode(it[key]) }.flowOn(Dispatchers.IO)

    suspend fun save(profile: PanelProfile) = update { profiles ->
        profiles.filterNot { it.id == profile.id } + profile
    }

    suspend fun remove(id: String) = update { profiles -> profiles.filterNot { it.id == id } }

    private suspend fun update(transform: (List<PanelProfile>) -> List<PanelProfile>) = withContext(Dispatchers.IO) {
        store.edit { preferences ->
            val updated = transform(decode(preferences[key]))
            preferences[key] = CryptoManager.encrypt(json.encodeToString(updated))
                ?: error("无法加密面板配置")
        }
    }

    private fun decode(stored: String?): List<PanelProfile> {
        if (stored == null) return emptyList()
        check(CryptoManager.isEncrypted(stored)) { "面板配置格式无效" }
        val decoded = CryptoManager.decrypt(stored) ?: error("无法解密面板配置")
        return json.decodeFromString<List<PanelProfile>>(decoded)
    }
}
