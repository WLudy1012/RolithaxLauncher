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
}
