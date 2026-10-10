package com.univpn.app.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class MullvadEndpointTest {

    private fun config(endpoint: String) = """
        [Interface]
        PrivateKey = aaaa
        Address = 10.64.1.2/32

        [Peer]
        PublicKey = bbbb
        Endpoint = $endpoint
        AllowedIPs = 0.0.0.0/0, ::/0
    """.trimIndent()

    @Test
    fun resolvable_bareRelayHostname_getsRelayDomain() {
        assertEquals("al-tia-wg-001.relays.mullvad.net", MullvadEndpoint.resolvable("al-tia-wg-001"))
    }

    @Test
    fun resolvable_ipAndFqdnAndIpv6_unchanged() {
        assertEquals("103.124.165.2", MullvadEndpoint.resolvable("103.124.165.2"))
        assertEquals("se-got-wg-001.relays.mullvad.net", MullvadEndpoint.resolvable("se-got-wg-001.relays.mullvad.net"))
        assertEquals("2a03:1b20::1", MullvadEndpoint.resolvable("2a03:1b20::1"))
    }

    @Test
    fun repair_rewritesBareHostnameFromBrokenRefresh() {
        assertEquals(
            config("al-tia-wg-001.relays.mullvad.net:51820"),
            MullvadEndpoint.repair(config("al-tia-wg-001:51820"))
        )
    }

    @Test
    fun repair_leavesWorkingEndpointsAlone() {
        for (ok in listOf("103.124.165.2:51820", "[2a03:1b20::1]:51820", "se-got-wg-001.relays.mullvad.net:51820")) {
            assertEquals(config(ok), MullvadEndpoint.repair(config(ok)))
        }
    }

    @Test
    fun repair_handlesCrlfConfigs() {
        val broken = config("al-tia-wg-001:51820").replace("\n", "\r\n")
        assertEquals(
            config("al-tia-wg-001.relays.mullvad.net:51820").replace("\n", "\r\n"),
            MullvadEndpoint.repair(broken)
        )
    }
}
