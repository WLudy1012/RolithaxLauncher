package com.wludy.rolithax.console.sdk

import com.wludy.rolithax.launcher.shared.console.ConsolePluginCompiler
import com.wludy.rolithax.launcher.shared.console.ConsolePluginSource
import com.wludy.rolithax.launcher.shared.console.ConsolePluginWorkspace
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

private val json = Json { prettyPrint = true }
private val output = PrintWriter(System.out, true, Charsets.UTF_8)
private val errors = PrintWriter(System.err, true, Charsets.UTF_8)
private const val defaultId = "example.paper-panel"
private val usage = """
    rolithax-console-sdk init <directory> [plugin-id]
    rolithax-console-sdk validate <directory>
    rolithax-console-sdk replay <directory> <logs.txt> [expected-values.json]
    rolithax-console-sdk pack <directory> <output.zip>
""".trimIndent()

fun main(arguments: Array<String>) {
    try {
        val action = arguments.firstOrNull()
        if (action == null || action == "--help") {
            output.println(usage)
            return
        }
        val directory = Path.of(arguments.getOrNull(1) ?: error(usage))
        when (action) {
            "init" -> {
                require(arguments.size in 2..3) { usage }
                initialize(directory, arguments.getOrNull(2) ?: defaultId)
            }
            "validate" -> {
                require(arguments.size == 2) { usage }
                val plugin = validate(load(directory))
                output.println("${plugin.id} ${plugin.version} (SDK 1, ${plugin.server})")
                plugin.commands.forEach { (id, command) -> output.println("$id = $command") }
            }
            "replay" -> {
                require(arguments.size in 3..4) { usage }
                val workspace = ConsolePluginWorkspace()
                workspace.reload(load(directory))
                read(Path.of(arguments[2]), 16 * 1024 * 1024).lineSequence().forEach(workspace::updateLog)
                val snapshot = workspace.snapshot()
                arguments.getOrNull(3)?.let {
                    val expected = json.decodeFromString<Map<String, String>>(read(Path.of(it), 65_536))
                    val mismatches = expected.filter { (key, value) -> snapshot.values[key] != value }
                    require(mismatches.isEmpty()) { "Replay mismatch: ${mismatches.keys.joinToString()}" }
                }
                output.println(json.encodeToString(snapshot))
            }
            "pack" -> {
                require(arguments.size == 3) { usage }
                val source = load(directory)
                validate(source)
                pack(source, Path.of(arguments[2]))
            }
            else -> error(usage)
        }
    } catch (failure: Exception) {
        errors.println(failure.message ?: failure.javaClass.simpleName)
        exitProcess(1)
    }
}

private fun initialize(directory: Path, id: String) {
    require(id.matches(Regex("[a-z][a-z0-9.-]{1,95}")) && id != "rolithax.paper") { "Invalid plugin ID" }
    val paper = ConsolePluginCompiler.paperSource
    val source = paper.copy(manifest = paper.manifest.replace("\"rolithax.paper\"", "\"$id\""))
    validate(source)
    val resources = mapOf(
        "README.md" to "README.md",
        ".gitignore" to "gitignore.txt",
        ".github/workflows/validate.yml" to "validate.yml",
        "fixtures/logs.txt" to "fixtures/logs.txt",
        "fixtures/expected.json" to "fixtures/expected.json",
    ).mapValues { (_, name) ->
        val resource = object {}.javaClass.getResourceAsStream("/template/$name")
            ?: error("Missing SDK template resource $name")
        resource.use { it.readBytes() }
    }
    Files.createDirectory(directory)
    files(source).forEach { (name, contents) -> Files.writeString(directory.resolve(name), "$contents\n") }
    for ((name, contents) in resources) {
        val destination = directory.resolve(name)
        Files.createDirectories(destination.parent)
        Files.write(destination, contents)
    }
    output.println(directory.toAbsolutePath())
}

private fun validate(source: ConsolePluginSource) = ConsolePluginCompiler.compile(source).also {
    require(it.id != "rolithax.paper") { "Reserved built-in plugin ID" }
}

private fun load(directory: Path) = ConsolePluginSource(
    read(directory.resolve("plugin.json"), 8_192),
    read(directory.resolve("ui.xml"), 65_536),
    read(directory.resolve("rules.yaml"), 65_536),
)

private fun read(path: Path, limit: Int): String = Files.newInputStream(path).use { stream ->
    val data = stream.readNBytes(limit + 1)
    require(data.size <= limit) { "${path.fileName} exceeds $limit bytes" }
    data.decodeToString(throwOnInvalidSequence = true)
}

private fun files(source: ConsolePluginSource) =
    mapOf("plugin.json" to source.manifest, "ui.xml" to source.ui, "rules.yaml" to source.rules)

private fun pack(source: ConsolePluginSource, path: Path) {
    val destination = path.toAbsolutePath()
    val staging = Files.createTempFile(destination.parent, ".rolithax-console-", ".zip")
    try {
        ZipOutputStream(Files.newOutputStream(staging)).use { zip ->
            files(source).forEach { (name, content) ->
                val entry = ZipEntry(name)
                entry.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                zip.putNextEntry(entry)
                zip.write(content.encodeToByteArray())
                zip.closeEntry()
            }
        }
        Files.move(staging, destination)
        output.println(destination)
    } finally {
        Files.deleteIfExists(staging)
    }
}
