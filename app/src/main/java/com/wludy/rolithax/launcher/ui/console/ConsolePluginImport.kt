package com.wludy.rolithax.launcher.ui.console

import android.content.ContentResolver
import android.net.Uri
import com.wludy.rolithax.launcher.shared.console.ConsolePluginCompiler
import com.wludy.rolithax.launcher.shared.console.ConsolePluginSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

internal data class ConsolePluginImport(
    val source: ConsolePluginSource,
    val title: String,
    val id: String,
    val commands: Map<String, String>,
)

internal fun readConsolePlugin(resolver: ContentResolver, uri: Uri): ConsolePluginImport {
    val files = mapOf("plugin.json" to 8_192, "ui.xml" to 65_536, "rules.yaml" to 65_536)
    val bytes = requireNotNull(resolver.openInputStream(uri)).use { it.readLimited(256 * 1024) }
    val contents = mutableMapOf<String, String>()
    ZipInputStream(bytes.inputStream()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val limit = requireNotNull(files[entry.name])
            require(!entry.isDirectory && entry.name !in contents)
            contents[entry.name] = zip.readLimited(limit).decodeToString(throwOnInvalidSequence = true)
            zip.closeEntry()
        }
    }
    require(contents.keys == files.keys)
    val source = ConsolePluginSource(
        contents.getValue("plugin.json"), contents.getValue("ui.xml"), contents.getValue("rules.yaml"),
    )
    val compiled = ConsolePluginCompiler.compile(source)
    require(compiled.id != "rolithax.paper")
    return ConsolePluginImport(source, compiled.title, compiled.id, compiled.commands)
}

private fun InputStream.readLimited(limit: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val length = read(buffer, 0, minOf(buffer.size, limit - result.size() + 1))
        if (length < 0) return result.toByteArray()
        require(result.size() + length <= limit)
        result.write(buffer, 0, length)
    }
}
