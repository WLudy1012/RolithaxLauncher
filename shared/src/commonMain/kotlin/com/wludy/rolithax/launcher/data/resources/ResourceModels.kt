package com.wludy.rolithax.launcher.data.resources

data class ResourceFilter(
    val type: String,
    val query: String = "",
    val gameVersion: String = "",
    val loader: String = "",
    val serverOnly: Boolean = false,
    val providerId: String = "modrinth",
) {
    val key: String get() = listOf(providerId.lowercase(), type, query.trim().lowercase(), gameVersion, loader, serverOnly).joinToString("|")
}

data class ResourceProject(
    val id: String,
    val name: String,
    val summary: String,
    val author: String,
    val iconUrl: String?,
    val downloads: Long,
    val updatedAt: String,
    val type: String,
    val loaders: List<String>,
    val gameVersions: List<String>,
    val serverCompatible: Boolean,
    val clientCompatible: Boolean = true,
    val providerId: String? = null,
)

data class ResourceFile(
    val filename: String,
    val url: String,
    val sha512: String,
    val size: Long,
    val primary: Boolean,
)

data class ResourceDependency(
    val projectId: String?,
    val versionId: String?,
    val type: String,
)

data class ResourceVersion(
    val id: String,
    val name: String,
    val number: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val files: List<ResourceFile>,
    val dependencies: List<ResourceDependency>,
    val serverCompatible: Boolean,
)

fun ResourceVersion.isCompatibleWith(gameVersion: String, loader: String, type: String = "mod"): Boolean =
    serverCompatible && matchesVersion(gameVersion, resourceTargetLoaders(type, loader))

fun ResourceFilter.matchesVersion(version: ResourceVersion): Boolean =
    (!serverOnly || version.serverCompatible) && version.matchesVersion(gameVersion, resourceSearchLoaders(type, loader))

private fun ResourceVersion.matchesVersion(gameVersion: String, acceptedLoaders: List<String>): Boolean =
    (gameVersion.isBlank() || gameVersions.isEmpty() || gameVersion in gameVersions) &&
        (acceptedLoaders.isEmpty() || loaders.isEmpty() || loaders.any { it.lowercase() in acceptedLoaders })

fun resourceSearchLoaders(type: String, loader: String): List<String> {
    val selected = loader.trim().lowercase()
    return when (type) {
        "mod", "modpack" -> listOf(selected).filter { it in MOD_LOADERS }
        "plugin" -> when (selected) {
            "paper" -> listOf("paper", "spigot", "bukkit")
            "purpur" -> listOf("purpur", "paper", "spigot", "bukkit")
            "spigot" -> listOf("spigot", "bukkit")
            "waterfall" -> listOf("waterfall", "bungeecord")
            else -> listOf(selected).filter { it in PLUGIN_LOADERS }
        }
        else -> emptyList()
    }
}

fun resourceTargetLoaders(type: String, loader: String): List<String> =
    if (type == "datapack" || loader.isBlank()) emptyList()
    else resourceSearchLoaders(type, loader).ifEmpty { listOf(loader.trim().lowercase()) }

private val MOD_LOADERS = setOf("fabric", "forge", "neoforge", "quilt", "liteloader", "rift", "babric", "legacy-fabric", "ornithe")
private val PLUGIN_LOADERS = setOf("bukkit", "folia", "sponge", "velocity", "bungeecord")

fun ResourceVersion.hasCompleteCompatibilityMetadata(gameVersion: String, loader: String, type: String = "mod"): Boolean =
    (gameVersion.isBlank() || gameVersions.isNotEmpty()) &&
        (resourceTargetLoaders(type, loader).isEmpty() || loaders.isNotEmpty())

data class ResourceCartEntry(
    val project: ResourceProject,
    val version: ResourceVersion,
    val providerId: String = "modrinth",
)

data class ResourceIssue(
    val severity: Severity,
    val message: String,
    val projectIds: Set<String> = emptySet(),
) {
    enum class Severity { INFO, WARNING, BLOCKING }
}
