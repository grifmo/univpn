package com.univpn.app.tunnel

import android.net.VpnService
import com.univpn.app.data.model.VpnProfile

interface TunnelManager {
    suspend fun start(profile: VpnProfile, service: VpnService)
    suspend fun stop()
}
