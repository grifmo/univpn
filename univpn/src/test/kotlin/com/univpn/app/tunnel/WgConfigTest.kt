package com.univpn.app.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WgConfigTest {

    @Test
    fun endpointHost_ipv4() {
        assertEquals("103.124.165.2", WgConfig.endpointHost("[Peer]\nEndpoint = 103.124.165.2:51820"))
    }

    @Test
    fun endpointHost_ipv6Bracketed() {
        assertEquals("2a03:1b20::1", WgConfig.endpointHost("[Peer]\nEndpoint = [2a03:1b20::1]:51820"))
    }

    @Test
    fun endpointHost_hostnameAndLooseSpacing() {
        assertEquals("vpn.example.org", WgConfig.endpointHost("[Peer]\n  endpoint=vpn.example.org:443  "))
    }

    @Test
    fun endpointHost_none() {
        assertNull(WgConfig.endpointHost("[Interface]\nPrivateKey = x"))
    }
}
