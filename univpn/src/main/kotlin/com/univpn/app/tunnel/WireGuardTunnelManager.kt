package com.univpn.app.tunnel

import android.net.VpnService
import android.util.Log
import com.univpn.app.data.model.VpnProfile
import com.univpn.app.service.DebugState
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

/**
 * @param onUnexpectedDown called when the backend takes the tunnel down without us asking,
 *   e.g. when another VPN app takes over and GoBackend's VpnService is destroyed.
 */
class WireGuardTunnelManager(
    private val onUnexpectedDown: (() -> Unit)? = null
) : TunnelManager {

    private var backend: GoBackend? = null
    private var activeTunnel: WgTunnel? = null
    private var pollScope: CoroutineScope? = null
    @Volatile private var stopping = false

    override suspend fun start(profile: VpnProfile, service: VpnService) {
        withContext(Dispatchers.IO) {
            val be = GoBackend(service)
            val config = Config.parse(profile.configContent.reader().buffered())
            val tunnel = WgTunnel(profile.name) { t ->
                if (!stopping && t === activeTunnel) {
                    Log.w(TAG, "WireGuard ${profile.name} went down unexpectedly")
                    onUnexpectedDown?.invoke()
                }
            }
            be.setState(tunnel, Tunnel.State.UP, config)
            backend = be
            activeTunnel = tunnel
            Log.i(TAG, "WireGuard tunnel up: ${profile.name}")

            val peerHost = WgConfig.endpointHost(profile.configContent)

            pollScope?.cancel()
            pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
                scope.launch { pollStats(be, tunnel, peerHost) }
            }
        }
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            stopping = true
            try {
                pollScope?.cancel()
                pollScope = null
                activeTunnel?.let { t ->
                    runCatching { backend?.setState(t, Tunnel.State.DOWN, null) }
                        .onFailure { Log.e(TAG, "Error stopping tunnel: ${it.message}") }
                }
                activeTunnel = null
                backend = null
            } finally {
                stopping = false
            }
            DebugState.latencyMs = -1
            DebugState.latencyFlow.value = -1
            DebugState.txBytes = 0L
            DebugState.rxBytes = 0L
        }
    }

    /** Bytes received from the peer. Non-zero means a handshake completed. */
    fun receivedBytes(): Long {
        val be = backend ?: return 0L
        val t = activeTunnel ?: return 0L
        return runCatching { be.getStatistics(t).totalRx() }.getOrDefault(0L)
    }

    private suspend fun pollStats(be: GoBackend, tunnel: WgTunnel, peerHost: String?) {
        var baselineTx = 0L
        var baselineRx = 0L
        runCatching {
            val stats = be.getStatistics(tunnel)
            baselineTx = stats.totalTx()
            baselineRx = stats.totalRx()
        }

        if (peerHost != null) {
            val ms = pingMs(peerHost) ?: -1
            DebugState.latencyMs = ms
            DebugState.latencyFlow.value = ms
        }

        while (coroutineContext.isActive) {
            delay(30_000L)
            runCatching {
                val stats = be.getStatistics(tunnel)
                DebugState.txBytes = stats.totalTx() - baselineTx
                DebugState.rxBytes = stats.totalRx() - baselineRx
            }
            if (peerHost != null) {
                val ms = pingMs(peerHost) ?: -1
                DebugState.latencyMs = ms
                DebugState.latencyFlow.value = ms
            }
        }
    }

    private fun pingMs(host: String): Int? = runCatching {
        val proc = Runtime.getRuntime().exec(arrayOf("ping", "-c", "1", "-W", "2", host))
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        Regex("time[<=](\\d+\\.?\\d*)\\s*ms").find(output)
            ?.groupValues?.get(1)?.toFloatOrNull()?.toInt()
    }.getOrNull()

    private class WgTunnel(
        private val name: String,
        private val onDown: (WgTunnel) -> Unit
    ) : Tunnel {
        override fun getName() = name
        override fun onStateChange(newState: Tunnel.State) {
            Log.i(TAG, "WireGuard $name → $newState")
            if (newState == Tunnel.State.DOWN) onDown(this)
        }
    }

    companion object {
        private const val TAG = "UniVPN_WG"
    }
}
