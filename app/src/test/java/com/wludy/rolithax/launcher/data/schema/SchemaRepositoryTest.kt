package com.wludy.rolithax.launcher.data.schema

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaRepositoryTest {
    @Test
    fun normalizesGitHubAndNestedGitLabRepositories() {
        assertEquals("https://github.com/example/repository",
            SchemaRepositoryClient.normalizeSource("https://github.com/example/repository.git/"))
        assertEquals("https://gitlab.com/example/team/repository",
            SchemaRepositoryClient.normalizeSource("https://gitlab.com/example/team/repository"))
    }

    @Test
    fun rejectsCredentialAndNonHttpsSources() {
        listOf(
            "http://github.com/example/repository",
            "https://name:secret@github.com/example/repository",
            "https://github.com/example/repository?token=example",
            "https://github.com/example/repository#section",
            "https://github.com/example/repository/tree/main",
            "https://example.org/source.json"
        ).forEach { value -> reject { SchemaRepositoryClient.normalizeSource(value) } }
    }

    @Test
    fun readsFileCatalogueWithoutLoadingNativeRuntime() {
        val catalog = parse(repository())
        assertEquals("Example source", catalog.name)
        assertEquals("a".repeat(40), catalog.revision)
        assertEquals("example.panel", catalog.plugins.single().id)
        assertEquals(setOf("plugin.json", "ui.xml", "rules.yaml"),
            catalog.plugins.single().files.map { it.path }.toSet())
    }

    @Test
    fun rejectsRepeatedMetadataFence() {
        val readme = repository()
        reject { parse(readme + "\n" + readme) }
    }

    @Test
    fun boundsReadmeByUtf8Bytes() {
        reject { parse("文".repeat(90_000) + "\n" + repository()) }
    }

    @Test
    fun rejectsUnsafeSourcePaths() {
        listOf("../outside", "plugins/../../outside", "/absolute", "plugins//panel",
            "plugins/%2e%2e", "plugins\\panel", "plugins/panel?raw=1").forEach { path ->
            reject { parse(repository(path = path)) }
        }
    }

    @Test
    fun rejectsLuaFileSource() {
        reject { parse(repository(engine = "lua")) }
    }

    @Test
    fun rejectsDigestAndFileSizeViolations() {
        reject { parse(repository(hash = "not-a-digest")) }
        reject { parse(repository(size = 65_537)) }
        reject { parse(repository(size = -1)) }
    }

    @Test
    fun rejectsDuplicatePluginAndFileEntries() {
        reject { parse(repository(duplicatePlugin = true)) }
        reject { parse(repository(names = listOf("plugin.json", "ui.xml", "ui.xml"))) }
    }

    @Test
    fun readsYamlAndLuaWithOptionalEmptyRules() {
        assertEquals("initial:\n  state: \"ready\"\n", readSchemaPackage(yamlPackage()).rules)
        val lua = readSchemaPackage(archive(mapOf(
            "plugin.json" to manifest("lua").toByteArray(),
            "ui.xml" to "<panel title=\"Lua\"/>".toByteArray(),
            "business.lua" to "return {}".toByteArray(),
            "rules.yaml" to byteArrayOf()
        )))
        assertEquals("", lua.rules)
        assertEquals("return {}", lua.lua)
    }

    @Test
    fun acceptsUtf8BomFromWindowsEditors() {
        val source = readSchemaPackage(archive(mapOf(
            "plugin.json" to ("\uFEFF" + manifest()).toByteArray(),
            "ui.xml" to "\uFEFF<panel title=\"Panel\"/>".toByteArray(),
            "rules.yaml" to "\uFEFFinitial:\n  state: \"ready\"\n".toByteArray()
        )))
        assertTrue(source.manifest.startsWith("{"))
        assertTrue(source.ui.startsWith("<"))
        assertTrue(source.rules.startsWith("initial:"))
    }

    @Test
    fun rejectsMixedBusinessEngines() {
        reject { readSchemaPackage(archive(mapOf(
            "plugin.json" to manifest("lua").toByteArray(),
            "ui.xml" to "<panel/>".toByteArray(),
            "business.lua" to "return {}".toByteArray(),
            "rules.yaml" to "initial:".toByteArray()
        ))) }
        reject { readSchemaPackage(archive(mapOf(
            "plugin.json" to manifest().toByteArray(),
            "ui.xml" to "<panel/>".toByteArray(),
            "rules.yaml" to "initial:".toByteArray(),
            "business.lua" to "return {}".toByteArray()
        ))) }
    }

    @Test
    fun rejectsZipTraversalAndInvalidUtf8() {
        reject { readSchemaPackage(archive(mapOf("../plugin.json" to manifest().toByteArray()))) }
        reject { readSchemaPackage(archive(mapOf(
            "plugin.json" to manifest().toByteArray(),
            "ui.xml" to byteArrayOf(0xC3.toByte(), 0x28),
            "rules.yaml" to "initial:".toByteArray()
        ))) }
    }

    @Test
    fun rejectsCompressedFileExpansionAndOversizedArchive() {
        reject { readSchemaPackage(archive(mapOf(
            "plugin.json" to manifest().toByteArray(),
            "ui.xml" to "<panel/>".toByteArray(),
            "rules.yaml" to ByteArray(65_537) { 65 }
        ))) }
        reject { readSchemaPackage(ByteArray(SchemaPackageLimit + 1)) }
    }

    @Test
    fun boundsStreamsWithoutDependingOnDeclaredContentLength() {
        assertEquals(10, ByteArray(10).inputStream().readSchemaBytes(10).size)
        reject { ByteArray(11).inputStream().readSchemaBytes(10) }
    }

    private fun parse(text: String) = SchemaRepositoryClient.parseReadme(
        "https://github.com/example/repository",
        "https://raw.githubusercontent.com/example/repository/" + "a".repeat(40) + "/",
        "a".repeat(40), text
    )

    private fun repository(
        path: String = "plugins/example",
        engine: String = "yaml",
        hash: String = "a".repeat(64),
        size: Int = 12,
        names: List<String> = listOf("plugin.json", "ui.xml", "rules.yaml"),
        duplicatePlugin: Boolean = false
    ): String {
        val plugin = buildJsonObject {
            put("id", "example.panel")
            put("version", "1.0.0")
            put("name", "Example panel")
            put("description", "Test definition")
            put("engine", engine)
            put("source", buildJsonObject {
                put("type", "files")
                put("path", path)
                put("files", buildJsonArray {
                    names.forEach { name ->
                        add(buildJsonObject {
                            put("path", name)
                            put("sha256", hash)
                            put("size", size)
                        })
                    }
                })
            })
        }
        val root = buildJsonObject {
            put("schemaVersion", 1)
            put("id", "example.source")
            put("name", "Example source")
            put("plugins", JsonArray(if (duplicatePlugin) listOf(plugin, plugin) else listOf(plugin)))
        }
        return "# Example\n\n```rolithax-schema-repository\n$root\n```\n"
    }

    private fun manifest(engine: String = "yaml") = buildJsonObject {
        put("id", "example.panel")
        put("version", "1.0.0")
        put("sdkVersion", 2)
        put("server", "paper")
        put("engine", engine)
    }.toString()

    private fun yamlPackage() = archive(mapOf(
        "plugin.json" to manifest().toByteArray(),
        "ui.xml" to "<panel title=\"Panel\"/>".toByteArray(),
        "rules.yaml" to "initial:\n  state: \"ready\"\n".toByteArray()
    ))

    private fun archive(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun reject(block: () -> Unit) {
        assertTrue("Expected rejected input", runCatching(block).isFailure)
    }
}
