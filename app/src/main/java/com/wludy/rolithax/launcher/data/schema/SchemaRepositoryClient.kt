package com.wludy.rolithax.launcher.data.schema

import com.wludy.rolithax.launcher.shared.console.ConsolePluginCompiler
import com.wludy.rolithax.launcher.shared.console.ConsolePluginSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal data class SchemaRepositoryFile(val path: String, val sha256: String, val size: Int)
internal data class SchemaRepositoryEntry(val id: String, val version: String, val name: String,
    val description: String, val engine: String, val type: String, val path: String,
    val url: String, val sha256: String, val size: Int, val files: List<SchemaRepositoryFile>, val compatibility: String = "",
    val sdkVersion: Int = 2, val icon: SchemaAsset? = null, val authors: List<String> = emptyList(),
    val license: String = "", val homepage: String = "")
internal data class SchemaRepositoryCatalog(val url: String, val name: String, val description: String,
    val revision: String, val filesBase: String, val plugins: List<SchemaRepositoryEntry>, val icon: SchemaAsset? = null,
    val authors: List<String> = emptyList(), val homepage: String = "")

internal class SchemaRepositoryClient(private val storage: File, private val http: OkHttpClient = defaultHttp) {
    fun loadSources(): List<String> {
        val file = File(storage, "sources.json")
        if (!file.isFile) return listOf(DefaultSource)
        return runCatching {
            require(file.length() <= 65_536)
            Json.decodeFromString<List<String>>(file.readText()).map(::normalizeSource).distinct().take(32)
        }.getOrElse { listOf(DefaultSource) }
    }

    fun saveSources(sources: List<String>) {
        val normalized = sources.map(::normalizeSource).distinct()
        require(normalized.size <= 32) { "最多添加 32 个插件源" }
        storage.mkdirs()
        val temporary = File.createTempFile("sources-", ".tmp", storage)
        try {
            temporary.writeText(Json.encodeToString(normalized))
            check(temporary.renameTo(File(storage, "sources.json"))) { "无法保存插件源" }
        } finally { temporary.delete() }
    }

    fun fetch(source: String): SchemaRepositoryCatalog {
        val normalized = normalizeSource(source)
        val url = normalized.toHttpUrl()
        var revision = "README"
        val base = when (url.host) {
            "github.com" -> {
                val project = url.encodedPath.trim('/')
                revision = try {
                    val commits = Json.parseToJsonElement(download("https://api.github.com/repos/$project/commits?per_page=1", 262_144)
                        .decodeToString(throwOnInvalidSequence = true)).jsonArray
                    commits.first().jsonObject.required("sha")
                } catch (failure: DownloadFailure) {
                    if (failure.status !in setOf(403, 429)) throw failure
                    val atom = download("https://github.com/$project/commits/HEAD.atom", 262_144)
                        .decodeToString(throwOnInvalidSequence = true)
                    require(!atom.contains("<!DOCTYPE", true) && !atom.contains("<!ENTITY", true)) { "仓库提交信息无效" }
                    requireNotNull(Regex("<id>\\s*tag:github.com,2008:Grit::Commit/([a-fA-F0-9]{40,64})\\s*</id>")
                        .find(atom)?.groupValues?.get(1)) { "无法读取仓库提交" }
                }
                require(commitPattern.matches(revision)) { "仓库提交信息无效" }
                "https://raw.githubusercontent.com/$project/$revision/"
            }
            "gitlab.com" -> {
                val project = url.pathSegments.joinToString("/")
                val api = "https://gitlab.com/api/v4/projects/".toHttpUrl().newBuilder()
                    .addPathSegment(project).addPathSegments("repository/commits").addQueryParameter("per_page", "1").build()
                val commits = Json.parseToJsonElement(download(api.toString(), 262_144)
                    .decodeToString(throwOnInvalidSequence = true)).jsonArray
                revision = commits.first().jsonObject.required("id")
                require(commitPattern.matches(revision)) { "仓库提交信息无效" }
                "https://gitlab.com/$project/-/raw/$revision/"
            }
            else -> requireNotNull(url.resolve(".")).toString()
        }
        val readme = if (url.host in setOf("github.com", "gitlab.com")) "${base}README.md" else normalized
        return parseReadme(normalized, base, revision,
            download(readme, 262_144).decodeToString(throwOnInvalidSequence = true))
    }

    fun install(catalog: SchemaRepositoryCatalog, entry: SchemaRepositoryEntry): ConsolePluginSource {
        val bytes = if (entry.type == "files") {
            val output = ByteArrayOutputStream()
            ZipOutputStream(output).use { zip ->
                entry.files.forEach { file ->
                    val url = requireNotNull(catalog.filesBase.toHttpUrl().resolve("${entry.path}/${file.path}"))
                    val data = download(url.toString(), SchemaFileLimits.getValue(file.path))
                    verify(data, file.sha256, file.size)
                    zip.putNextEntry(ZipEntry(file.path).apply { time = 315532800000L })
                    zip.write(data)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        } else download(entry.url, SchemaPackageLimit).also { verify(it, entry.sha256, entry.size) }
        val source = readSchemaPackage(bytes)
        val compiled = ConsolePluginCompiler.compile(source)
        require(compiled.id == entry.id && compiled.version == entry.version && compiled.engine == entry.engine) {
            "包的 ID、版本或业务引擎与插件源声明不一致"
        }
        require(compiled.id != "rolithax.paper") { "插件源不能覆盖内置面板" }
        val cache = File(storage, "packages").apply { mkdirs() }
        File(cache, "${digest(bytes)}.zip").writeBytes(bytes)
        return source
    }

    private fun download(address: String, limit: Int): ByteArray =
        readDownload(address, limit) { it.readSchemaBytes(limit) }

    suspend fun downloadFile(address: String, limit: Int, file: File) {
        val operation = currentCoroutineContext()
        operation.ensureActive()
        readDownload(address, limit) { input ->
            file.outputStream().use { output -> input.copySchemaBytes(output, limit) { operation.ensureActive() } }
        }
    }

    private fun <T> readDownload(address: String, limit: Int, read: (InputStream) -> T): T {
        var url = https(address)
        repeat(6) {
            http.newCall(Request.Builder().url(url).header("User-Agent", "Rolithax-Schema/2.0").build()).execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    url = https(requireNotNull(url.resolve(requireNotNull(response.header("Location")))).toString())
                } else {
                    if (!response.isSuccessful) throw DownloadFailure(response.code)
                    val body = requireNotNull(response.body)
                    require(body.contentLength() <= limit) { "插件源文件超过大小限制" }
                    return body.byteStream().use(read)
                }
            }
        }
        error("插件源重定向次数过多")
    }

    private class DownloadFailure(val status: Int) : IllegalStateException("插件源下载失败（HTTP $status）")

    companion object {
        const val DefaultSource = "https://github.com/WLudy1012/rolithax-schema-plugins"
        private val defaultHttp = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
        private val commitPattern = Regex("[a-fA-F0-9]{40,64}")
        private val hashPattern = Regex("[a-fA-F0-9]{64}")
        private val idPattern = Regex("[a-z][a-z0-9._-]{0,127}")
        private val segmentPattern = Regex("[A-Za-z0-9_.-]+")

        fun normalizeSource(value: String): String {
            val url = https(value.trim())
            require(url.query == null && url.fragment == null) { "插件源地址不能包含查询参数或锚点" }
            if (url.host in setOf("github.com", "gitlab.com")) {
                val parts = url.pathSegments.filter(String::isNotEmpty)
                require(parts.size >= 2 && (url.host != "github.com" || parts.size == 2) &&
                    parts.all { segmentPattern.matches(it) && it !in setOf(".", "..") }) { "请输入仓库主页地址" }
                return url.toString().trimEnd('/').removeSuffix(".git")
            }
            require(url.encodedPath.endsWith("/README.md", ignoreCase = true)) { "自托管源需提供可直接下载的 HTTPS README.md 地址" }
            return url.toString()
        }

        fun parseReadme(url: String, filesBase: String, revision: String, readme: String): SchemaRepositoryCatalog {
            require(readme.toByteArray(Charsets.UTF_8).size <= 262_144) { "README 过大" }
            val versioned = Regex("(?m)^```rolithax-schema-repository(-v2)?[ \\t]*\\r?\\n([\\s\\S]*?)^```[ \\t]*\\r?$")
                .findAll(readme).toList().groupBy { it.groupValues[1] == "-v2" }
            val v2 = versioned.containsKey(true)
            val blocks = versioned[v2].orEmpty()
            require(blocks.size == 1) { "README 必须包含唯一的插件源索引" }
            val root = Json.parseToJsonElement(blocks.single().groupValues[2]).jsonObject
            require(root["schemaVersion"]?.jsonPrimitive?.intOrNull == if (v2) 2 else 1) { "不支持此插件源格式" }
            require(idPattern.matches(root.required("id"))) { "插件源 ID 无效" }
            val entries = root.getValue("plugins").jsonArray
            require(entries.size <= 256) { "插件源条目过多" }
            val plugins = entries.map { parseEntry(it.jsonObject, v2) }
            require(plugins.map { it.id }.distinct().size == plugins.size) { "插件源中存在重复 ID" }
            return SchemaRepositoryCatalog(url, root.required("name"),
                if (v2) root.required("description") else root.optional("description"), revision, filesBase, plugins,
                if (v2) root.catalogIcon() else null, if (v2) root.authors() else emptyList(),
                if (v2) https(root.required("homepage")).toString() else "")
        }

        private fun parseEntry(item: JsonObject, v2: Boolean): SchemaRepositoryEntry {
            val id = item.required("id")
            require(idPattern.matches(id)) { "插件 ID 无效" }
            val engine = item.required("engine")
            val source = item.getValue("source").jsonObject
            val type = source.required("type")
            val sdk = if (v2) requireNotNull(item["sdkVersion"]?.jsonPrimitive?.intOrNull)
                .also { require(it in 1..3) { "不支持此 SDK 版本" } } else 2
            var path = ""
            var url = ""
            var hash = ""
            var size = 0
            var files = emptyList<SchemaRepositoryFile>()
            if (type == "files" && engine == "yaml") {
                path = relativePath(source.required("path"))
                files = source.getValue("files").jsonArray.map {
                    val file = it.jsonObject
                    val name = file.required("path")
                    val limit = SchemaFileLimits[name] ?: if (sdk == 3 && relativePath(name).startsWith("assets/")) {
                        SchemaMediaLimit
                    } else error("不支持的插件文件")
                    SchemaRepositoryFile(name, file.hash(), file.size(limit))
                }
                val names = files.map { it.path }.toSet()
                require(files.size == names.size && names.containsAll(setOf("plugin.json", "ui.xml", "rules.yaml"))) { "文件型源需列出三个定义文件且不能重复" }
                if (sdk < 3) require(files.size == 3) { "旧版插件必须恰好包含三个定义文件" }
                require(files.size <= 67 && files.sumOf { it.size.toLong() } <= SchemaInstallLimit) { "文件型插件超过大小限制" }
            } else if (type == "package" && engine in setOf("yaml", "lua")) {
                url = https(source.required("url")).toString()
                hash = source.hash()
                size = source.size(if (sdk == 3) SchemaInstallLimit else SchemaPackageLimit)
            } else error("Lua 插件必须提供预构建 ZIP 包；文件型源仅支持 YAML")
            val compatibility = item["compatibility"] as? JsonObject
            val versions = (compatibility?.get("versions") as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content
            }?.joinToString(" / ").orEmpty().take(256)
            return SchemaRepositoryEntry(id, item.required("version"), if (v2) item.required("name") else item.optional("name").ifEmpty { id },
                if (v2) item.required("description") else item.optional("description"), engine, type, path, url, hash, size, files, versions,
                sdk, if (v2) item.catalogIcon() else null, if (v2) item.authors() else emptyList(),
                if (v2) item.required("license") else "", if (v2) https(item.required("homepage")).toString() else "")
        }

        private fun JsonObject.authors(): List<String> = getValue("authors").jsonArray.map { it.jsonPrimitive.content }
            .also { require(it.size in 1..10 && it.all { name -> name.isNotBlank() && name.length <= 128 }) }

        private fun JsonObject.catalogIcon(): SchemaAsset =
            Json.decodeFromJsonElement<SchemaAsset>(getValue("icon")).also {
                it.validate(icon = true)
                it.path?.let(::relativePath)
            }

        private fun relativePath(path: String): String {
            require(path.length in 1..512 && path.split('/').all { segmentPattern.matches(it) && it !in setOf(".", "..") }) { "插件文件路径不安全" }
            return path
        }
        private fun https(value: String): HttpUrl = value.toHttpUrl().also {
            require(it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.fragment == null) { "插件源必须使用无凭据的 HTTPS 地址" }
        }
        private fun JsonObject.required(key: String): String = requireNotNull(this[key]?.jsonPrimitive?.takeIf { it.isString }?.content) { "缺少 $key" }
            .also { require(it.isNotBlank() && it.length <= 4096) { "$key 无效" } }
        private fun JsonObject.optional(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty().take(4096)
        private fun JsonObject.hash(): String = required("sha256").also { require(hashPattern.matches(it)) { "SHA256 无效" } }
        private fun JsonObject.size(limit: Int): Int = requireNotNull(this["size"]?.jsonPrimitive?.intOrNull)
            .also { require(it in 0..limit) { "声明的文件大小超出限制" } }
        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        private fun verify(bytes: ByteArray, expectedHash: String, expectedSize: Int) {
            require(bytes.size == expectedSize && digest(bytes).equals(expectedHash, ignoreCase = true)) { "Schema 文件大小或 SHA256 校验失败" }
        }
    }
}
