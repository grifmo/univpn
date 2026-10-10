package com.univpn.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.univpn.app.R
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.VpnProfile
import com.univpn.app.provider.FreshnessChecker
import com.univpn.app.receiver.ToggleOverlayReceiver
import com.univpn.app.tunnel.TunnelManager
import com.univpn.app.tunnel.WireGuardTunnelManager
import com.wireguard.android.backend.BackendException
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

sealed class TestResult {
    data class Success(val externalIp: String) : TestResult()
    object NoHandshake : TestResult()
    data class Error(val reason: String) : TestResult()
    object ServiceUnavailable : TestResult()
}

/**
 * Keeps the WireGuard tunnel matching the foreground app's route.
 *
 * Foreground changes, failed starts, unexpected tunnel drops and config refreshes all just
 * send a signal; a single reconciler compares the profile the routes ask for ([desiredProfileId])
 * with the tunnel actually running ([activeProfile]) and fixes any difference. The UI only ever
 * shows a profile as active once its tunnel is up.
 */
class VpnSwitcherService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tunnelMutex = Mutex()
    private val wgManager = WireGuardTunnelManager(onUnexpectedDown = ::onTunnelDropped)
    private var activeTunnelManager: TunnelManager? = null
    private var activeProfile: VpnProfile? = null      // tunnel that is actually up
    private var desiredProfileId: String? = null       // what the routes ask for; null = no VPN
    @Volatile private var foregroundPkg: String? = null
    private val reconcileSignal = Channel<Unit>(Channel.CONFLATED)
    private var retryJob: Job? = null
    private var retryAttempt = 0
    // Set when another app took the VPN slot; cleared by the next foreground change.
    @Volatile private var holdUntilForegroundChange = false
    private val freshnessChecker by lazy { FreshnessChecker(applicationContext) }
    private var watcherJob: Job? = null
    private var reconcilerJob: Job? = null

    // Re-checks the foreground app when the Shield wakes from standby.
    // ACTION_SCREEN_ON cannot be declared in the manifest — dynamic only.
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            Log.i(TAG, "Screen on — re-checking foreground app")
            scope.launch {
                val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
                getCurrentForegroundPackage(usm)?.let { foregroundPkg = it }
                requestReconcile()
            }
            // Restart the overlay if it was enabled before standby.
            if (DebugOverlayService.wasEnabled(context)) {
                DebugOverlayService.start(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Restore the profile the routes last asked for, so a process restart while on the
        // home screen (Passthrough) brings the same tunnel back.
        desiredProfileId = prefs().getString(PREF_DESIRED_PROFILE, null)
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        Log.i(TAG, "Service started")
        // Guard against duplicate workers from multiple startService() calls
        // (e.g. TvMainActivity + START_STICKY restart + BootReceiver).
        if (reconcilerJob?.isActive != true) {
            reconcilerJob = scope.launch {
                runCatching { freshnessChecker.repairMullvadEndpoints() }
                    .onFailure { Log.e(TAG, "Mullvad endpoint repair failed: ${it.message}") }
                for (signal in reconcileSignal) reconcile()
            }
        }
        if (watcherJob?.isActive != true) {
            watcherJob = scope.launch { runForegroundWatcher() }
        }
        // Restore overlay across a process kill + START_STICKY restart.
        if (DebugOverlayService.wasEnabled(this) && !DebugOverlayService.isRunning) {
            DebugOverlayService.start(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screenOnReceiver)
        instance = null
        scope.cancel()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun prefs() = getSharedPreferences("univpn_prefs", MODE_PRIVATE)

    private fun requestReconcile() {
        reconcileSignal.trySend(Unit)
    }

    // ── Test connection ───────────────────────────────────────────────────────

    suspend fun testProfile(profile: VpnProfile): TestResult {
        val acquired = withTimeoutOrNull(3_000L) { tunnelMutex.lock() }
        if (acquired == null) return TestResult.Error("Another tunnel operation is in progress")

        val testManager = WireGuardTunnelManager()
        try {
            runCatching { activeTunnelManager?.stop() }
            activeTunnelManager = null
            activeProfile = null
            testManager.start(profile, this)

            // Received bytes prove the peer answered (a handshake completed), so the IP check
            // below can't pass on traffic that bypassed the tunnel.
            var handshake = false
            var externalIp: String? = null
            repeat(10) { attempt ->
                if (externalIp != null) return@repeat
                delay(if (attempt == 0) 2_000L else 1_500L)
                if (!handshake) handshake = testManager.receivedBytes() > 0
                if (handshake) externalIp = tryFetchExternalIp()
            }

            return when {
                externalIp != null -> TestResult.Success(externalIp!!)
                handshake -> TestResult.Error("Handshake completed, but no internet access through the tunnel")
                else -> TestResult.NoHandshake
            }
        } catch (e: Exception) {
            return TestResult.Error(e.message ?: "Unknown error")
        } finally {
            runCatching { testManager.stop() }
            tunnelMutex.unlock()
            requestReconcile()   // bring back the tunnel the foreground app needs
        }
    }

    private fun tryFetchExternalIp(): String? = runCatching {
        val conn = URL("https://api.ipify.org").openConnection() as HttpURLConnection
        conn.connectTimeout = 3_000
        conn.readTimeout = 3_000
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText().trim()
        else null
    }.getOrNull()

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification() = buildNotification(this)

    // ── Foreground watcher ────────────────────────────────────────────────────

    private suspend fun runForegroundWatcher() {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        var lastPollTime = System.currentTimeMillis() - 2_000L
        var pollCount = 0

        var currentPkg = getCurrentForegroundPackage(usm) ?: ""
        if (currentPkg.isNotEmpty()) foregroundPkg = currentPkg
        requestReconcile()

        while (coroutineContext.isActive) {
            try {
                val now = System.currentTimeMillis()
                val events = usm.queryEvents(lastPollTime, now)
                lastPollTime = now
                pollCount++

                val event = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        val pkg = event.packageName
                        if (pkg != currentPkg) {
                            currentPkg = pkg
                            Log.i(TAG, "FOREGROUND_CHANGE [poll #$pollCount]: $pkg")
                            foregroundPkg = pkg
                            holdUntilForegroundChange = false
                            requestReconcile()
                        }
                    }
                }

                if (pollCount % 30 == 0) {
                    Log.d(TAG, "heartbeat: poll #$pollCount, current=$currentPkg profile=${activeProfile?.name}")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "PACKAGE_USAGE_STATS revoked: ${e.message}")
                break
            }
            delay(1_000L)
        }
        Log.i(TAG, "Watcher stopped")
    }

    // ── Reconciler ────────────────────────────────────────────────────────────

    private suspend fun reconcile() {
        if (holdUntilForegroundChange) return
        val db = AppDatabase.get(this)
        val pkg = foregroundPkg
        if (pkg != null && pkg != packageName && pkg !in ALWAYS_PASSTHROUGH) {
            val decision = decideRoute(db.appRouteDao().getByPackage(pkg))
            if (decision is RouteDecision.Use && decision.profileId != desiredProfileId) {
                desiredProfileId = decision.profileId
                prefs().edit().putString(PREF_DESIRED_PROFILE, desiredProfileId).apply()
            }
        }

        // Re-read so edits and refreshes are picked up; a deleted profile means no VPN.
        val desired = desiredProfileId?.let { db.vpnProfileDao().getById(it) }

        tunnelMutex.withLock {
            val active = activeProfile
            if (desired?.id == active?.id && desired?.configContent == active?.configContent) {
                // Nothing to switch, but a failed profile may no longer be wanted (e.g. a No VPN app).
                if (desired == null && failedProfileFlow.value != null) {
                    cancelRetry()
                    publishState(active = null, failed = null)
                }
                return
            }

            switchingFlow.value = true
            try {
                if (activeTunnelManager != null) {
                    runCatching { activeTunnelManager?.stop() }
                        .onFailure { Log.e(TAG, "Error stopping tunnel: ${it.message}") }
                }
                activeTunnelManager = null
                activeProfile = null

                if (desired == null) {
                    cancelRetry()
                    publishState(active = null, failed = null)
                    Log.i(TAG, "No VPN for $pkg")
                    return
                }

                try {
                    wgManager.start(desired, this)
                    activeTunnelManager = wgManager
                    activeProfile = desired
                    cancelRetry()
                    publishState(active = desired, failed = null)
                    Log.i(TAG, "Switched to profile=${desired.name} for $pkg")
                    scope.launch(Dispatchers.IO) {
                        if (freshnessChecker.checkAndRefreshIfStale(desired)) requestReconcile()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start tunnel for ${desired.name}: ${(e as? BackendException)?.reason ?: e.message}")
                    publishState(active = null, failed = desired)
                    scheduleRetry(e)
                }
            } finally {
                switchingFlow.value = false
            }
        }
    }

    private fun scheduleRetry(cause: Exception) {
        retryJob?.cancel()
        if ((cause as? BackendException)?.reason == BackendException.Reason.VPN_NOT_AUTHORIZED) {
            // Another VPN app holds the VPN slot. Retrying would fight it; the next foreground
            // change or opening UniVPN (which asks for consent again) tries once more.
            Log.w(TAG, "VPN permission lost — not retrying automatically")
            return
        }
        val delayMs = retryDelayMs(retryAttempt++)
        Log.i(TAG, "Retrying in ${delayMs / 1000}s (attempt $retryAttempt)")
        retryJob = scope.launch {
            delay(delayMs)
            requestReconcile()
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
        retryAttempt = 0
    }

    /**
     * Called by the WireGuard backend when the tunnel goes down without us stopping it, usually
     * because another VPN app took the VPN slot. Once consent is given, Android lets either app
     * take the slot back at any time, so reconnecting here would fight the other app endlessly.
     * Instead flag the profile as not connected and wait for the next app switch.
     */
    private fun onTunnelDropped() {
        scope.launch {
            tunnelMutex.withLock {
                val dropped = activeProfile ?: return@launch
                runCatching { wgManager.stop() }
                activeTunnelManager = null
                activeProfile = null
                holdUntilForegroundChange = true
                cancelRetry()
                publishState(active = null, failed = dropped)
                Log.w(TAG, "${dropped.name} was taken down externally — waiting for the next app switch")
            }
        }
    }

    private fun publishState(active: VpnProfile?, failed: VpnProfile?) {
        DebugState.profileName = active?.name ?: "None"
        DebugState.profileType = active?.type?.name ?: ""
        profileFlow.value = active?.name
        failedProfileFlow.value = failed?.name
    }

    private fun getCurrentForegroundPackage(usm: UsageStatsManager): String? {
        val now = System.currentTimeMillis()
        // Look back far enough to find an app that has been open for hours (e.g. after a
        // process restart or waking from standby), not just the last few seconds.
        val events = usm.queryEvents(now - FOREGROUND_LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var lastForeground: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForeground = event.packageName
            }
        }
        return lastForeground
    }

    companion object {
        private const val TAG = "UniVPN"
        private const val CHANNEL_ID = "univpn_service"
        private const val NOTIF_ID = 1
        private const val PREF_DESIRED_PROFILE = "desired_profile_id"
        private const val FOREGROUND_LOOKBACK_MS = 3L * 24 * 60 * 60 * 1000  // 3 days

        @Volatile var instance: VpnSwitcherService? = null
        val switchingFlow = MutableStateFlow(false)
        val profileFlow = MutableStateFlow<String?>(null)
        /** Name of the profile that should be running but isn't (start failed or tunnel dropped). */
        val failedProfileFlow = MutableStateFlow<String?>(null)

        private val ALWAYS_PASSTHROUGH = setOf(
            "com.android.systemui",
            "com.android.settings",
            "com.google.android.inputmethod.latin",
            "com.android.inputmethod.latin",
        )

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, VpnSwitcherService::class.java))

        fun buildNotification(context: Context): Notification {
            val nm = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "UniVPN", NotificationManager.IMPORTANCE_LOW)
                        .apply { description = "VPN profile switcher running" }
                )
            }
            val overlayOn = DebugOverlayService.isRunning
            val toggleIntent = PendingIntent.getBroadcast(
                context, 0,
                Intent(ToggleOverlayReceiver.ACTION).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val overlayIconRes = if (overlayOn) R.drawable.ic_visibility else R.drawable.ic_visibility_off
            val overlayLabel = if (overlayOn) "Overlay: ON" else "Overlay: OFF"
            val overlayAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(context, overlayIconRes),
                overlayLabel, toggleIntent
            ).build()

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(context, CHANNEL_ID)
            else
                @Suppress("DEPRECATION") Notification.Builder(context)
                    .setPriority(@Suppress("DEPRECATION") Notification.PRIORITY_LOW)
            return builder
                .setContentTitle("UniVPN active")
                .setContentText("Switching profiles per app")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .addAction(overlayAction)
                .build()
        }

        fun updateNotification(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(context))
        }
    }
}
