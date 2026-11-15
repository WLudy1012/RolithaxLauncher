package com.wludy.rolithax.launcher.data.schema

import com.wludy.rolithax.launcher.shared.console.ConsolePluginSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val SchemaPackageLimit = 512 * 1024
internal val SchemaFileLimits = mapOf("plugin.json" to 16_384, "ui.xml" to 65_536,
    "rules.yaml" to 65_536, "business.lua" to 131_072)

internal fun InputStream.readSchemaBytes(limit: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val length = read(buffer, 0, minOf(buffer.size, limit - result.size() + 1))
        if (length < 0) return result.toByteArray()
        require(result.size() + length <= limit) { "Schema 文件超过大小限制" }
        result.write(buffer, 0, length)
    }
}

internal fun readSchemaPackage(bytes: ByteArray): ConsolePluginSource {
    require(bytes.size <= SchemaPackageLimit) { "Schema 包不能超过 512 KiB" }
    val contents = mutableMapOf<String, String>()
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val limit = requireNotNull(SchemaFileLimits[entry.name]) { "包内包含未支持的文件" }
            require(!entry.isDirectory && entry.name !in contents) { "包内包含目录或重复文件" }
            contents[entry.name] = zip.readSchemaBytes(limit).decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF")
            zip.closeEntry()
        }
    }
    require(contents.keys.containsAll(setOf("plugin.json", "ui.xml"))) { "包内缺少元数据或界面" }
    val manifest = Json.parseToJsonElement(contents.getValue("plugin.json")).jsonObject
    when (manifest["engine"]?.jsonPrimitive?.content ?: "yaml") {
        "yaml" -> require(contents.keys == setOf("plugin.json", "ui.xml", "rules.yaml")) { "YAML 包必须包含三个定义文件" }
        "lua" -> require("business.lua" in contents && contents["rules.yaml"].isNullOrBlank()) { "Lua 包需包含 business.lua，不能混入 YAML 业务" }
        else -> error("不支持此 Schema 引擎")
    }
    return ConsolePluginSource(contents.getValue("plugin.json"), contents.getValue("ui.xml"),
        contents["rules.yaml"].orEmpty(), contents["business.lua"].orEmpty())
}
