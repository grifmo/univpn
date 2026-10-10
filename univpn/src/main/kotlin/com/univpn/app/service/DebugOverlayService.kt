package com.univpn.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.univpn.app.R
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import kotlin.coroutines.coroutineContext

enum class OverlaySize { MIN, MED, MAX }
enum class OverlayPosition { TOP_END, BOTTOM_END, BOTTOM_START, TOP_START }

class DebugOverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager
    private lateinit var overlayView: View
    private var updaterJob: Job? = null

    private var cachedExternalIp: String = "—"
    private var lastExternalIpFetch: Long = 0L
    private var lastProfileForIpRefresh: String = ""

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_debug, null)

        val params = buildLayoutParams(getPosition(this))
        wm.addView(overlayView, params)
        isRunning = true
        Log.i(TAG, "Overlay attached")
        applyConfig()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::overlayView.isInitialized) applyConfig()
        if (updaterJob?.isActive != true) {
            updaterJob = scope.launch { runUpdater() }
        }
        return START_STICKY
    }

    private fun applyConfig() {
        val size = getSize(this)

        fun vis(show: Boolean) = if (show) View.VISIBLE else View.GONE
        overlayView.findViewById<TextView>(R.id.overlayVirtualIp).visibility  = vis(size == OverlaySize.MAX)
        overlayView.findViewById<TextView>(R.id.overlayExternalIp).visibility = vis(size != OverlaySize.MIN)
        overlayView.findViewById<TextView>(R.id.overlayLatency).visibility    = vis(size == OverlaySize.MAX)
        overlayView.findViewById<TextView>(R.id.overlayThroughput).visibility = vis(size == OverlaySize.MAX)
        overlayView.findViewById<TextView>(R.id.overlaySession).visibility    = vis(size == OverlaySize.MAX)

        val params = overlayView.layoutParams as WindowManager.LayoutParams
        params.gravity = gravityFor(getPosition(this))
        wm.updateViewLayout(overlayView, params)
    }

    private fun buildLayoutParams(position: OverlayPosition) =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = gravityFor(position)
            x = 24
            y = 24
        }

    private fun gravityFor(pos: OverlayPosition) = when (pos) {
        OverlayPosition.TOP_END    -> Gravity.TOP    or Gravity.END
        OverlayPosition.TOP_START  -> Gravity.TOP    or Gravity.START
        OverlayPosition.BOTTOM_END -> Gravity.BOTTOM or Gravity.END
        OverlayPosition.BOTTOM_START -> Gravity.BOTTOM or Gravity.START
    }

    private suspend fun runUpdater() {
        var lastRx = TrafficStats.getTotalRxBytes()
        var lastTx = TrafficStats.getTotalTxBytes()

        while (coroutineContext.isActive) {
            delay(1_000L)

            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            val rxRate = (rx - lastRx).coerceAtLeast(0)
            val txRate = (tx - lastTx).coerceAtLeast(0)
            lastRx = rx
            lastTx = tx

            val virtualIp = getVirtualIp() ?: "—"

            val now = System.currentTimeMillis()
            val profileChanged = DebugState.profileName != lastProfileForIpRefresh
            val staleSince = now - lastExternalIpFetch > 30_000L
            if (profileChanged || staleSince) {
                lastProfileForIpRefresh = DebugState.profileName
                scope.launch(Dispatchers.IO) {
                    val ip = fetchExternalIp()
                    withContext(Dispatchers.Main) {
                        cachedExternalIp = ip ?: "—"
                        lastExternalIpFetch = System.currentTimeMillis()
                    }
                }
            }

            val failed = VpnSwitcherService.failedProfileFlow.value
            val profileLine = when {
                failed != null                      -> "⚠ $failed — NOT CONNECTED"
                DebugState.profileType.isNotEmpty() -> "● ${DebugState.profileName} (${DebugState.profileType})"
                DebugState.profileName != "None"    -> "● ${DebugState.profileName}"
                else                                -> "○ No VPN"
            }

            overlayView.findViewById<TextView>(R.id.overlayProfile).text    = profileLine
            overlayView.findViewById<TextView>(R.id.overlayVirtualIp).text  = "VPN: $virtualIp"
            overlayView.findViewById<TextView>(R.id.overlayExternalIp).text = "EXT: $cachedExternalIp"

            val latencyView = overlayView.findViewById<TextView>(R.id.overlayLatency)
            val latency = DebugState.latencyMs
            if (latency < 0) {
                latencyView.text = "RTT: —"
                latencyView.setTextColor(0xFF888888.toInt())
            } else {
                latencyView.text = "RTT: ${latency}ms"
                latencyView.setTextColor(when {
                    latency < 50  -> 0xFF00FF88.toInt()
                    latency < 150 -> 0xFFFFCC00.toInt()
                    else          -> 0xFFFF5555.toInt()
                })
            }

            overlayView.findViewById<TextView>(R.id.overlayThroughput).text =
                "↑ ${formatRate(txRate)}  ↓ ${formatRate(rxRate)}"
            overlayView.findViewById<TextView>(R.id.overlaySession).text =
                "↑ ${formatBytes(DebugState.txBytes)}  ↓ ${formatBytes(DebugState.rxBytes)} (session)"
        }
    }

    private fun getVirtualIp(): String? =
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { iface ->
                    iface.name.startsWith("tun") ||
                    iface.name.startsWith("wg") ||
                    iface.name.startsWith("ppp")
                }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull()
                ?.hostAddress
        }.getOrNull()

    private fun fetchExternalIp(): String? =
        runCatching {
            URL("https://api.ipify.org").openStream().bufferedReader().readText().trim()
        }.getOrNull()

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
        bytes >= 1_000_000     -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000         -> "%.0f KB".format(bytes / 1_000.0)
        else                   -> "${bytes} B"
    }

    private fun formatRate(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_000_000 -> "%.1f MB/s".format(bytesPerSec / 1_000_000.0)
        bytesPerSec >= 1_000     -> "%.0f KB/s".format(bytesPerSec / 1_000.0)
        else                     -> "${bytesPerSec} B/s"
    }

    override fun onDestroy() {
        isRunning = false
        updaterJob?.cancel()
        scope.cancel()
        runCatching { wm.removeView(overlayView) }
        Log.i(TAG, "Overlay removed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "UniVPN_Overlay"
        private const val PREFS = "univpn"
        private const val KEY_SIZE     = "overlay_size"
        private const val KEY_POSITION = "overlay_position"
        private const val KEY_ENABLED  = "overlay_enabled"

        @Volatile var isRunning = false
            private set

        fun start(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, true).apply()
            context.startService(Intent(context, DebugOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ENABLED, false).apply()
            context.stopService(Intent(context, DebugOverlayService::class.java))
        }

        fun toggle(context: Context) =
            if (isRunning) stop(context) else start(context)

        fun refresh(context: Context) {
            if (isRunning) start(context)
        }

        /** True if the overlay was enabled before this process started (survives kill+restart). */
        fun wasEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)

        fun getSize(context: Context): OverlaySize =
            OverlaySize.valueOf(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_SIZE, OverlaySize.MAX.name) ?: OverlaySize.MAX.name
            )

        fun setSize(context: Context, size: OverlaySize) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_SIZE, size.name).apply()
        }

        fun getPosition(context: Context): OverlayPosition =
            OverlayPosition.valueOf(
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_POSITION, OverlayPosition.TOP_END.name) ?: OverlayPosition.TOP_END.name
            )

        fun setPosition(context: Context, pos: OverlayPosition) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_POSITION, pos.name).apply()
        }
    }
}
