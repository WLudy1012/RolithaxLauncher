package com.wludy.rolithax.launcher.desktop

import com.github.javakeyring.Keyring
import com.github.javakeyring.KeyringStorageType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class SavedDaemon(val id: String, val name: String, val address: String, val allowHttp: Boolean, val fingerprint: String)

internal class OrphanedCredentialException(val credentialId: String) : Exception("系统密钥清理失败")
internal class CredentialRestoreException(val credentialId: String) : Exception("系统密钥恢复失败")

internal interface DesktopSecrets {
    fun put(id: String, key: String)
    fun get(id: String): String
    fun delete(id: String)
}

internal class DesktopConnectionStore(
    private val path: Path = configDirectory().resolve("daemons.json"),
    private val secrets: DesktopSecrets = SystemDesktopSecrets(),
) {

    fun list(): List<SavedDaemon> {
        if (!Files.exists(path)) return emptyList()
        require(Files.size(path) <= 1024 * 1024) { "配置文件过大" }
        val root = Json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8)) as? JsonArray ?: error("配置格式无效")
        return root.map { item ->
            val value = item as? JsonObject ?: error("配置格式无效")
            fun field(name: String): String = value[name]?.jsonPrimitive?.contentOrNull ?: error("配置缺少字段")
            val id = field("id")
            require(runCatching { UUID.fromString(id) }.isSuccess)
            SavedDaemon(id, field("name"), field("address"), value["allowHttp"]?.jsonPrimitive?.booleanOrNull ?: false, field("fingerprint"))
        }
    }

    fun add(name: String, address: String, allowHttp: Boolean, fingerprint: String, key: String): SavedDaemon {
        val entries = list()
        val saved = SavedDaemon(UUID.randomUUID().toString(), name, address, allowHttp, fingerprint)
        try { secrets.put(saved.id, key) } catch (error: Exception) {
            try { secrets.delete(saved.id) } catch (_: Exception) { throw OrphanedCredentialException(saved.id) }
            throw error
        }
        try {
            write(entries + saved)
        } catch (error: Exception) {
            try { secrets.delete(saved.id) } catch (_: Exception) { throw OrphanedCredentialException(saved.id) }
            throw error
        }
        return saved
    }

    fun password(id: String): String = secrets.get(id)

    fun cleanupOrphan(id: String) {
        require(runCatching { UUID.fromString(id) }.isSuccess)
        secrets.delete(id)
    }

    fun remove(id: String) {
        val entries = list()
        val key = secrets.get(id)
        secrets.delete(id)
        try { write(entries.filterNot { it.id == id }) } catch (error: Exception) {
            try { secrets.put(id, key) } catch (_: Exception) { throw CredentialRestoreException(id) }
            throw error
        }
    }

    private fun write(entries: List<SavedDaemon>) {
        Files.createDirectories(path.parent)
        val data = JsonArray(entries.map { entry -> JsonObject(mapOf(
            "id" to JsonPrimitive(entry.id), "name" to JsonPrimitive(entry.name), "address" to JsonPrimitive(entry.address),
            "allowHttp" to JsonPrimitive(entry.allowHttp), "fingerprint" to JsonPrimitive(entry.fingerprint),
        )) }).toString()
        val temp = Files.createTempFile(path.parent, ".daemons-", ".tmp")
        try {
            Files.writeString(temp, data, StandardCharsets.UTF_8)
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }

}

private class SystemDesktopSecrets : DesktopSecrets {
    private val service = "RolithaxLauncher.Daemon"
    override fun put(id: String, key: String) { keyring().use { it.setPassword(service, id, key) } }
    override fun get(id: String): String = keyring().use { it.getPassword(service, id) }
    override fun delete(id: String) { keyring().use { it.deletePassword(service, id) } }

    private fun keyring(): Keyring {
        val os = System.getProperty("os.name").lowercase()
        val backend = when {
            os.startsWith("windows") -> KeyringStorageType.WINDOWS_CREDENTIAL_STORE
            os.startsWith("linux") -> KeyringStorageType.GNOME_KEYRING
            else -> error("此平台不支持系统密钥存储")
        }
        return Keyring.create(backend)
    }
}

private fun configDirectory(): Path {
    val os = System.getProperty("os.name").lowercase()
    val home = System.getProperty("user.home")
    val base = when {
        os.startsWith("windows") -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() } ?: home
        os.startsWith("linux") -> System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: Path.of(home, ".config").toString()
        else -> error("此平台不支持桌面配置")
    }
    return Path.of(base).resolve("RolithaxLauncher")
}
