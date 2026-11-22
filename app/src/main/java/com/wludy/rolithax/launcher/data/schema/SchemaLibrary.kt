package com.wludy.rolithax.launcher.data.schema

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.wludy.rolithax.launcher.data.AppLogger
import com.wludy.rolithax.launcher.shared.console.ConsolePluginCompiler
import com.wludy.rolithax.launcher.shared.console.ConsolePluginSource
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl

internal const val SchemaMediaLimit = 64 * 1024 * 1024
internal const val SchemaInstallLimit = 128 * 1024 * 1024
private const val ImageLimit = 8 * 1024 * 1024
private val hashPattern = Regex("[a-fA-F0-9]{64}")
private val segment = Regex("[A-Za-z0-9_.-]{1,128}")
private val images = mapOf("image/png" to ".png", "image/jpeg" to ".jpg", "image/webp" to ".webp")

@Serializable
internal data class SchemaAsset(
    val id: String = "",
    val mediaType: String,
    val size: Int,
    val sha256: String,
    val path: String? = null,
    val url: String? = null,
) {
    val suffix: String get() = images[mediaType] ?: ".mp4"

    fun validate(icon: Boolean = false) {
        require(mediaType in images || !icon && mediaType == "video/mp4") { "不支持的媒体格式" }
        require(size in 1..(if (mediaType == "video/mp4") SchemaMediaLimit else ImageLimit)) { "媒体资源超过大小限制" }
        require(hashPattern.matches(sha256) && (path == null) != (url == null)) { "媒体摘要或地址无效" }
        path?.let { require(safePath(it)) { "媒体路径无效" } }
        url?.let {
            val parsed = it.toHttpUrl()
            require(parsed.isHttps && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.fragment == null)
        }
    }

    fun verify(file: File) {
        verifyFile(file, sha256, size)
        val header = file.inputStream().use { stream ->
            ByteArray(12).also { bytes -> require(stream.read(bytes) == bytes.size) { "媒体内容不完整" } }
        }
        val valid = when (mediaType) {
            "image/png" -> header.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
            "image/jpeg" -> header[0] == (-1).toByte() && header[1] == (-40).toByte()
            "image/webp" -> header.decodeToString(0, 4) == "RIFF" && header.decodeToString(8, 12) == "WEBP"
            "video/mp4" -> header.decodeToString(4, 8) == "ftyp"
            else -> false
        }
        require(valid) { "媒体类型与文件内容不一致" }
    }
}

@Serializable
internal data class SchemaInstall(
    val id: String, val version: String, val title: String, val digest: String, val source: String,
    val engine: String, val description: String = "", val icon: String? = null,
    val catalogIcon: SchemaAsset? = null,
)

@Serializable
internal data class SchemaBinding(val digest: String, val grants: Set<String>)

@Serializable
internal data class SchemaLibraryState(
    val installs: List<SchemaInstall> = emptyList(),
    val bindings: Map<String, SchemaBinding> = emptyMap(),
)

internal class SchemaLibrary(private val context: Context) {
    private val root = File(context.filesDir, "schema")
    val repositories = SchemaRepositoryClient(root)
    private val json = Json { ignoreUnknownKeys = true }
    private val guard = Mutex()
    private val stateFile = AtomicFile(File(root, "library.json"))
    private val mutable = MutableStateFlow(SchemaLibraryState())
    val state = mutable.asStateFlow()

    suspend fun reload() = withContext(Dispatchers.IO) { guard.withLock { readState() } }

    suspend fun install(catalog: SchemaRepositoryCatalog, entry: SchemaRepositoryEntry): SchemaInstall =
        stage(catalog.url, entry, catalog) { zip, staging ->
            if (entry.type == "package") {
                repositories.downloadFile(entry.url, entry.size, zip)
                verifyFile(zip, entry.sha256, entry.size)
            } else {
                ZipOutputStream(zip.outputStream()).use { stream ->
                    val payload = File(staging, "download")
                    val operation = currentCoroutineContext()
                    entry.files.forEach { file ->
                        require(safePath(entry.path) && safePath(file.path))
                        val address = requireNotNull(catalog.filesBase.toHttpUrl().resolve("${entry.path}/${file.path}"))
                        repositories.downloadFile(address.toString(), file.size, payload)
                        verifyFile(payload, file.sha256, file.size)
                        stream.putNextEntry(ZipEntry(file.path).apply { time = 315532800000 })
                        payload.inputStream().use { it.copySchemaBytes(stream, file.size) { operation.ensureActive() } }
                        stream.closeEntry()
                    }
                    payload.delete()
                }
            }
        }

    suspend fun import(uri: Uri): SchemaInstall = stage("本地 ZIP", null) { zip, _ ->
        val operation = currentCoroutineContext()
        requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开插件包" }.use { input ->
            zip.outputStream().use { input.copySchemaBytes(it, SchemaInstallLimit) { operation.ensureActive() } }
        }
    }

    suspend fun source(install: SchemaInstall): ConsolePluginSource = withContext(Dispatchers.IO) {
        guard.withLock {
            val zip = File(packageDir(install.digest), "plugin.zip")
            ZipFile(zip).use(::readSource)
        }
    }

    fun media(install: SchemaInstall, id: String): File? {
        if (!segment.matches(id) || id in setOf(".", "..")) return null
        val dir = File(packageDir(install.digest), "media")
        return listOf(".png", ".jpg", ".webp", ".mp4").firstNotNullOfOrNull {
            File(dir, id + it).takeIf(File::isFile)
        }
    }

    fun installedIcon(install: SchemaInstall): File? =
        install.icon?.let { media(install, it) }
            ?: install.catalogIcon?.takeIf { hashPattern.matches(it.sha256) && it.mediaType in images }
                ?.let(::iconFile)?.takeIf(File::isFile)

    suspend fun icon(catalog: SchemaRepositoryCatalog, asset: SchemaAsset): File = withContext(Dispatchers.IO) {
        asset.validate(icon = true)
        val file = iconFile(asset)
        if (file.isFile && runCatching { asset.verify(file) }.isSuccess) return@withContext file
        val folder = requireNotNull(file.parentFile).apply { mkdirs() }
        val temp = File.createTempFile("icon-", ".tmp", folder)
        try {
            val address = asset.url ?: requireNotNull(catalog.filesBase.toHttpUrl().resolve(requireNotNull(asset.path))).toString()
            repositories.downloadFile(address, asset.size, temp)
            asset.verify(temp)
            currentCoroutineContext().ensureActive()
            check(temp.renameTo(file)) { "无法保存图标" }
            file
        } finally {
            temp.delete()
        }
    }

    suspend fun bind(key: String, install: SchemaInstall?, grants: Set<String>) = withContext(Dispatchers.IO) {
        guard.withLock {
            val current = readState()
            require(install == null || install in current.installs) { "插件已更新，请重新选择" }
            writeState(current.copy(bindings = if (install == null) current.bindings - key
                else current.bindings + (key to SchemaBinding(install.digest, grants))))
        }
    }

    suspend fun uninstall(install: SchemaInstall) = withContext(Dispatchers.IO) {
        guard.withLock {
            val state = readState()
            if (install in state.installs) {
                updateInstalls(state, state.installs - install)
            }
        }
    }

    private suspend fun stage(
        source: String,
        entry: SchemaRepositoryEntry?,
        catalog: SchemaRepositoryCatalog? = null,
        download: suspend (File, File) -> Unit,
    ): SchemaInstall = withContext(Dispatchers.IO) {
        root.mkdirs()
        val staging = File.createTempFile("staging-", ".tmp", root)
        check(staging.delete() && staging.mkdir()) { "无法创建插件暂存目录" }
        try {
            val zip = File(staging, "plugin.zip")
            download(zip, staging)
            currentCoroutineContext().ensureActive()
            require(zip.length() in 1..SchemaInstallLimit.toLong()) { "插件包大小无效" }
            val digest = hashFile(zip)
            val install = ZipFile(zip).use { archive ->
                val sourceFiles = readSource(archive)
                val compiled = ConsolePluginCompiler.compile(sourceFiles)
                require(compiled.id != "rolithax.paper") { "不能覆盖内置插件" }
                require(entry == null || entry.id == compiled.id && entry.version == compiled.version && entry.engine == compiled.engine) {
                    "插件内容与源索引不一致"
                }
                require(entry == null || entry.authors.isEmpty() || entry.sdkVersion == compiled.sdkVersion) {
                    "插件 SDK 版本与源索引不一致"
                }
                val manifest = json.parseToJsonElement(sourceFiles.manifest).jsonObject
                val assets = if (compiled.sdkVersion == 3) manifest["assets"]?.let {
                    json.decodeFromJsonElement<List<SchemaAsset>>(it)
                }.orEmpty() else emptyList()
                val mediaPaths = assets.mapNotNull { it.path }.toSet()
                val names = archive.entries().toList().map { it.name }
                require(names.size == names.distinct().size && names.all { it in SchemaFileLimits || it in mediaPaths })
                require(assets.size <= 64 && assets.sumOf { it.size.toLong() } <= SchemaInstallLimit)
                if (assets.isNotEmpty()) {
                    val directory = File(staging, "media").apply { mkdir() }
                    for (asset in assets) {
                        val operation = currentCoroutineContext()
                        operation.ensureActive()
                        asset.validate()
                        require(segment.matches(asset.id) && asset.id !in setOf(".", ".."))
                        val file = File(directory, asset.id + asset.suffix)
                        if (asset.url != null) repositories.downloadFile(asset.url, asset.size, file)
                        else archive.getInputStream(requireNotNull(archive.getEntry(asset.path))).use { input ->
                            file.outputStream().use { input.copySchemaBytes(it, asset.size) { operation.ensureActive() } }
                        }
                        asset.verify(file)
                    }
                }
                val catalogIcon = entry?.icon
                if (catalogIcon != null && catalog != null) icon(catalog, catalogIcon)
                SchemaInstall(compiled.id, compiled.version, compiled.title, digest, source, compiled.engine,
                    manifest["description"]?.jsonPrimitive?.content ?: entry?.description.orEmpty(),
                    manifest["icon"]?.jsonPrimitive?.content, catalogIcon)
            }
            guard.withLock {
                val state = readState()
                val destination = packageDir(digest)
                destination.parentFile?.mkdirs()
                currentCoroutineContext().ensureActive()
                val created = !destination.isDirectory
                if (created) check(staging.renameTo(destination)) { "无法保存插件包" }
                try {
                    updateInstalls(state, state.installs.filterNot { it.id == install.id && it.source == install.source } + install)
                } catch (error: Exception) {
                    if (created) destination.deleteRecursively()
                    throw error
                }
            }
            install
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun updateInstalls(previous: SchemaLibraryState, installs: List<SchemaInstall>) {
        val referenced = installs.mapTo(mutableSetOf()) { it.digest }
        writeState(previous.copy(installs = installs, bindings = previous.bindings.filterValues { it.digest in referenced }))
        previous.installs.map { it.digest }.distinct().filterNot(referenced::contains)
            .filter(hashPattern::matches).forEach { digest ->
                runCatching { packageDir(digest).deleteRecursively() }
                    .onFailure { AppLogger.w("Schema", "清理旧插件文件失败", it) }
            }
    }

    private fun readSource(zip: ZipFile): ConsolePluginSource {
        val entries = zip.entries().toList()
        require(entries.size in 3..68 && entries.none { it.isDirectory || !safePath(it.name) } &&
            entries.map { it.name }.distinct().size == entries.size) { "插件包条目无效" }
        fun text(name: String): String? = zip.getEntry(name)?.let { entry ->
            val limit = SchemaFileLimits.getValue(name)
            require(entry.size in 0..limit.toLong())
            zip.getInputStream(entry).use { it.readSchemaBytes(limit) }.decodeToString(throwOnInvalidSequence = true)
                .removePrefix("\uFEFF")
        }
        val manifest = requireNotNull(text("plugin.json"))
        val ui = requireNotNull(text("ui.xml"))
        val rules = text("rules.yaml")
        val lua = text("business.lua")
        when (json.parseToJsonElement(manifest).jsonObject["engine"]?.jsonPrimitive?.content ?: "yaml") {
            "yaml" -> require(rules != null && lua == null)
            "lua" -> require(lua != null && rules.isNullOrBlank())
            else -> error("不支持此 Schema 引擎")
        }
        return ConsolePluginSource(manifest, ui, rules.orEmpty(), lua.orEmpty())
    }

    private fun packageDir(digest: String): File =
        File(File(root, "installed"), digest.also { require(hashPattern.matches(it)) })

    private fun iconFile(asset: SchemaAsset): File =
        File(File(root, "icons"), asset.sha256.lowercase() + asset.suffix)

    private fun readState(): SchemaLibraryState {
        val loaded = try {
            json.decodeFromString<SchemaLibraryState>(stateFile.openRead().use { it.readSchemaBytes(2 * 1024 * 1024) }.decodeToString())
        } catch (error: FileNotFoundException) {
            if (stateFile.baseFile.exists()) throw error
            SchemaLibraryState()
        }
        mutable.value = loaded
        return loaded
    }

    private fun writeState(state: SchemaLibraryState) {
        val stream = stateFile.startWrite()
        try {
            stream.write(json.encodeToString(state).encodeToByteArray())
            stateFile.finishWrite(stream)
            mutable.value = state
        } catch (error: Exception) {
            stateFile.failWrite(stream)
            throw error
        }
    }
}

internal fun InputStream.copySchemaBytes(output: OutputStream, limit: Int, checkActive: () -> Unit = {}) {
    val bytes = ByteArray(32 * 1024)
    var size = 0
    while (true) {
        checkActive()
        val count = read(bytes)
        if (count < 0) return
        size += count
        require(size <= limit) { "插件文件超过大小限制" }
        output.write(bytes, 0, count)
    }
}

private fun safePath(path: String): Boolean = path.length <= 512 &&
    path.split('/').all { segment.matches(it) && it !in setOf(".", "..") }

private fun hashFile(file: File): String {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

private fun verifyFile(file: File, hash: String, size: Int) =
    require(file.length() == size.toLong() && hashFile(file).equals(hash, ignoreCase = true)) {
        "插件文件大小或 SHA-256 与声明不符"
    }
