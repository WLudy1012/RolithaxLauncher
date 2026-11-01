package com.wludy.rolithax.launcher.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JavaRecommendationTest {
    @Test
    fun matchesAndroidVersionBoundaries() {
        assertEquals(8, recommendedJavaMajor("1.16.5"))
        assertEquals(17, recommendedJavaMajor("1.20.4"))
        assertEquals(21, recommendedJavaMajor("1.20.5"))
        assertEquals(25, recommendedJavaMajor("26.1"))
        assertNull(recommendedJavaMajor("unknown"))
    }
}
