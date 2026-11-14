package com.wludy.rolithax.launcher.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingEndpointTest {
    @Test
    fun acceptsOnlyUsableCredentialFreeDaemonUrls() {
        assertNotNull(pairingEndpoint(" https://Example.org:443/daemon/ "))
        assertNull(pairingEndpoint("http://"))
        assertNull(pairingEndpoint("https://user:pass@example.org/daemon"))
        assertNull(pairingEndpoint("https://@example.org/daemon"))
        assertNull(pairingEndpoint("https://example.org\\other"))
        assertNull(pairingEndpoint("https://example.org/daemon?target=other"))
        assertNull(pairingEndpoint("https://example.org/daemon#section"))
        assertNull(pairingEndpoint("file:///etc/passwd"))
    }

    @Test
    fun comparesHostAndPortCanonicallyButPreservesPathCase() {
        assertTrue(samePairingEndpoint("https://EXAMPLE.org:443/Daemon/", "https://example.org/Daemon"))
        assertFalse(samePairingEndpoint("https://example.org/Daemon", "https://example.org/daemon"))
        assertFalse(samePairingEndpoint("https://example.org/Daemon", "http://example.org/Daemon"))
        assertFalse(samePairingEndpoint("https://example.org/Daemon", "https://example.org:8443/Daemon"))
        assertFalse(samePairingEndpoint("https://user@example.org/Daemon", "https://example.org/Daemon"))
    }
}
