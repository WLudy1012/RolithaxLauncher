package com.wludy.rolithax.launcher.ui.console

import android.content.ContentResolver
import android.net.Uri
import com.wludy.rolithax.launcher.data.schema.SchemaPackageLimit
import com.wludy.rolithax.launcher.data.schema.readSchemaBytes
import com.wludy.rolithax.launcher.data.schema.readSchemaPackage
import com.wludy.rolithax.launcher.shared.console.ConsolePluginCompiler
import com.wludy.rolithax.launcher.shared.console.ConsolePluginSource

internal data class ConsolePluginImport(
    val source: ConsolePluginSource,
    val title: String,
    val id: String,
    val commands: Map<String, String>,
)

internal fun readConsolePlugin(resolver: ContentResolver, uri: Uri): ConsolePluginImport =
    requireNotNull(resolver.openInputStream(uri)).use {
        inspectConsolePlugin(readSchemaPackage(it.readSchemaBytes(SchemaPackageLimit)))
    }

internal fun inspectConsolePlugin(source: ConsolePluginSource): ConsolePluginImport {
    val compiled = ConsolePluginCompiler.compile(source)
    require(compiled.id != "rolithax.paper") { "不能覆盖内置 Schema" }
    return ConsolePluginImport(source, compiled.title, compiled.id, compiled.commands)
}
