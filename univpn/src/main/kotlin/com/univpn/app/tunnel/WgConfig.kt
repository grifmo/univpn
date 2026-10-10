package com.univpn.app.tunnel

/** Helpers for reading WireGuard config text. No Android dependencies, so they're unit-testable. */
object WgConfig {

    /** Host of the first peer's Endpoint, without brackets or port; null if there is none. */
    fun endpointHost(configContent: String): String? {
        for (line in configContent.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Endpoint", ignoreCase = true)) {
                val value = trimmed.substringAfter("=").trim()
                return if (value.startsWith("[")) {
                    value.substringAfter("[").substringBefore("]")
                } else {
                    value.substringBeforeLast(":")
                }
            }
        }
        return null
    }
}
