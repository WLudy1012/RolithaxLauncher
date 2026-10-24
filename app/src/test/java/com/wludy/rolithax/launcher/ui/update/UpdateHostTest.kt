package com.wludy.rolithax.launcher.ui.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateHostTest {
    @Test
    fun cnbReleaseAndItsAssetRedirectAreTrusted() {
        assertTrue(isTrustedUpdateHost("cnb.cool"))
        assertTrue(isTrustedUpdateHost("asset.cnb.cool"))
        assertTrue(isTrustedUpdateHost("ASSET.CNB.COOL"))
    }

    @Test
    fun githubHostsAreTrusted() {
        assertTrue(isTrustedUpdateHost("github.com"))
        assertTrue(isTrustedUpdateHost("release-assets.githubusercontent.com"))
    }

    @Test
    fun lookalikeAndEmptyHostsAreRejected() {
        assertFalse(isTrustedUpdateHost("evilcnb.cool"))
        assertFalse(isTrustedUpdateHost("cnb.cool.evil.com"))
        assertFalse(isTrustedUpdateHost("raw.githubusercontent.com"))
        assertFalse(isTrustedUpdateHost(""))
        assertFalse(isTrustedUpdateHost(null))
    }
}
