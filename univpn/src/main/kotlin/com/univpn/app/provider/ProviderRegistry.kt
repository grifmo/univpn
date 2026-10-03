package com.univpn.app.provider

object ProviderRegistry {
    val all: List<VpnProviderConnector> = listOf(
        MullvadConnector(),
        //NordVpnConnector(),
        PiaConnector()
    )

    fun get(id: String): VpnProviderConnector? = all.find { it.id == id }
}
