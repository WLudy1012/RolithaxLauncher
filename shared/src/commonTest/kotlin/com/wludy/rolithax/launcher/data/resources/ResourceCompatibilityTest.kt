package com.wludy.rolithax.launcher.data.resources

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceCompatibilityTest {
    private val version = ResourceVersion(
        id = "v1",
        name = "Example",
        number = "1",
        gameVersions = listOf("1.21"),
        loaders = listOf("fabric"),
        files = emptyList(),
        dependencies = emptyList(),
        serverCompatible = true,
    )

    @Test
    fun compatibilityRequiresServerSupportAndMatchingConstraints() {
        assertTrue(version.isCompatibleWith("1.21", "Fabric"))
        assertFalse(version.isCompatibleWith("1.20", "fabric"))
        assertFalse(version.isCompatibleWith("1.21", "forge"))
        assertFalse(version.copy(serverCompatible = false).isCompatibleWith("", ""))
    }

    @Test
    fun missingMetadataIsDistinguishedFromKnownIncompatibility() {
        val unknown = version.copy(gameVersions = emptyList(), loaders = emptyList())
        assertTrue(unknown.isCompatibleWith("1.21", "fabric"))
        assertFalse(unknown.hasCompleteCompatibilityMetadata("1.21", "fabric"))
        assertTrue(unknown.hasCompleteCompatibilityMetadata("", ""))
    }

    @Test
    fun browseFiltersUseTheSameVersionRulesForOnlineAndCachedResults() {
        val filter = ResourceFilter("mod", gameVersion = "1.21", loader = "fabric", serverOnly = true)
        assertTrue(filter.matchesVersion(version))
        assertFalse(filter.matchesVersion(version.copy(gameVersions = listOf("1.20"))))
        assertFalse(filter.matchesVersion(version.copy(loaders = listOf("forge"))))
        assertFalse(filter.matchesVersion(version.copy(serverCompatible = false)))
        assertTrue(filter.copy(serverOnly = false).matchesVersion(version.copy(serverCompatible = false)))
    }

    @Test
    fun installationCompatibilityConsidersResourceType() {
        assertTrue(version.copy(loaders = listOf("bukkit")).isCompatibleWith("1.21", "paper", "plugin"))
        assertTrue(version.copy(loaders = listOf("datapack")).isCompatibleWith("1.21", "fabric", "datapack"))
        assertFalse(version.isCompatibleWith("1.21", "paper", "mod"))
        assertFalse(version.copy(loaders = listOf("paper")).isCompatibleWith("1.21", "folia", "plugin"))
    }
}
