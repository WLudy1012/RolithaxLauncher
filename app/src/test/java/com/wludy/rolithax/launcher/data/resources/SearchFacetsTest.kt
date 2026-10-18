package com.wludy.rolithax.launcher.data.resources

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFacetsTest {
    @Test
    fun typeIsAlwaysFiltered() {
        assertEquals("""[["project_types:plugin"]]""", searchFacets(ResourceFilter(type = "plugin")))
        assertEquals("""[["project_types:modpack"]]""", searchFacets(ResourceFilter(type = "modpack")))
    }

    @Test
    fun gameVersionAndMatchingLoaderAreAnded() {
        assertEquals(
            """[["project_types:mod"],["game_versions:1.21.4"],["categories:fabric"]]""",
            searchFacets(ResourceFilter(type = "mod", gameVersion = "1.21.4", loader = "Fabric")),
        )
        assertEquals(
            """[["project_types:plugin"],["categories:paper"]]""",
            searchFacets(ResourceFilter(type = "plugin", loader = "paper")),
        )
    }

    @Test
    fun loaderOfOtherKindIsIgnored() {
        assertEquals("""[["project_types:mod"]]""", searchFacets(ResourceFilter(type = "mod", loader = "paper")))
        assertEquals("""[["project_types:plugin"]]""", searchFacets(ResourceFilter(type = "plugin", loader = "fabric")))
        assertEquals("""[["project_types:datapack"]]""", searchFacets(ResourceFilter(type = "datapack", loader = "fabric")))
    }
}
