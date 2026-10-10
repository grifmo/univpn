package com.univpn.app.provider

/**
 * Mullvad relay-list hostnames such as "se-got-wg-001" don't resolve in DNS on their own;
 * only "se-got-wg-001.relays.mullvad.net" does. Config refreshes before 0.9.2 wrote the bare
 * name into Endpoint, so these helpers both build and repair endpoints.
 */
object MullvadEndpoint {
    private const val RELAY_DOMAIN = ".relays.mullvad.net"

    // Endpoint = <bare host>:<port>. IPv4 and FQDNs contain '.', IPv6 is bracketed, so neither matches.
    private val BARE_ENDPOINT = Regex("""(?im)^(\s*Endpoint\s*=\s*)([A-Za-z0-9-]+)(:\d+)\s*$""")

    /** Returns [host] unchanged if it's an IP or FQDN, otherwise the relay's resolvable name. */
    fun resolvable(host: String): String =
        if ('.' in host || ':' in host) host else host + RELAY_DOMAIN

    /** Rewrites a bare relay hostname in the Endpoint line; returns [config] unchanged otherwise. */
    fun repair(config: String): String =
        BARE_ENDPOINT.replace(config) { m -> m.groupValues[1] + resolvable(m.groupValues[2]) + m.groupValues[3] }
}
