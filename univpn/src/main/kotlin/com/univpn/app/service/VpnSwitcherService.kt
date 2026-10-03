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
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.univpn.app.R
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.db.AppRouteDao
import com.univpn.app.data.db.VpnProfileDao
import com.univpn.app.data.model.VpnProfile
import com.univpn.app.provider.FreshnessChecker
import com.univpn.app.receiver.ToggleOverlayReceiver
import com.univpn.app.tunnel.TunnelManager
import com.univpn.app.tunnel.WireGuardTunnelManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

sealed class TestResult {
    data class Success(val externalIp: String) : TestResult()
    object NoHandshake : TestResult()
    data class Error(val reason: String) : TestResult()
    object ServiceUnavailable : TestResult()
}

class VpnSwitcherService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val tunnelMutex = Mutex()
    private val wgManager = WireGuardTunnelManager()
    private var activeTunnelManager: TunnelManager? = null
    private var currentProfile: VpnProfile? = null
    private val freshnessChecker by lazy { FreshnessChecker(applicationContext) }
    private var watcherJob: Job? = null

    // Re-checks the foreground app when the Shield wakes from standby.
    // ACTION_SCREEN_ON cannot be declared in the manifest — dynamic only.
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_ON) return
            Log.i(TAG, "Screen on — re-checking foreground app")
            scope.launch {
                val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
                val db = AppDatabase.get(this@VpnSwitcherService)
                val pkg = getCurrentForegroundPackage(usm) ?: return@launch
                onAppForeground(pkg, db.appRouteDao(), db.vpnProfileDao())
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
        registerReceiver(screenOnReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        Log.i(TAG, "Service started")
        // Guard against duplicate watchers from multiple startService() calls
        // (e.g. TvMainActivity + START_STICKY restart + BootReceiver).
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

    // ── Test connection ───────────────────────────────────────────────────────

    suspend fun testProfile(profile: VpnProfile): TestResult {
        val acquired = withTimeoutOrNull(3_000L) { tunnelMutex.lock() }
        if (acquired == null) return TestResult.Error("Another tunnel operation is in progress")

        val previousProfile = currentProfile
        val testManager = WireGuardTunnelManager()
        try {
            runCatching { activeTunnelManager?.stop() }
            testManager.start(profile, this)

            var externalIp: String? = null
            repeat(10) { attempt ->
                if (externalIp != null) return@repeat
                delay(if (attempt == 0) 2_000L else 1_500L)
                externalIp = tryFetchExternalIp()
            }

            return if (externalIp != null) TestResult.Success(externalIp!!) else TestResult.NoHandshake
        } catch (e: Exception) {
            return TestResult.Error(e.message ?: "Unknown error")
        } finally {
            runCatching { testManager.stop() }
            if (previousProfile != null) {
                runCatching { wgManager.start(previousProfile, this) }
                    .onSuccess { activeTunnelManager = wgManager }
                    .onFailure { activeTunnelManager = null }
            } else {
                activeTunnelManager = null
            }
            currentProfile = previousProfile
            tunnelMutex.unlock()
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
        val db = AppDatabase.get(this)
        var lastPollTime = System.currentTimeMillis() - 2_000L
        var pollCount = 0

        var currentPkg = getCurrentForegroundPackage(usm) ?: ""
        if (currentPkg.isNotEmpty()) {
            onAppForeground(currentPkg, db.appRouteDao(), db.vpnProfileDao())
        }

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
                            onAppForeground(pkg, db.appRouteDao(), db.vpnProfileDao())
                        }
                    }
                }

                if (pollCount % 30 == 0) {
                    Log.d(TAG, "heartbeat: poll #$pollCount, current=$currentPkg profile=${currentProfile?.name}")
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "PACKAGE_USAGE_STATS revoked: ${e.message}")
                break
            }
            delay(1_000L)
        }
        Log.i(TAG, "Watcher stopped")
    }

    private fun isSystemApp(packageName: String): Boolean = try {
        val flags = packageManager.getApplicationInfo(packageName, 0).flags
        (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
        (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
    } catch (e: PackageManager.NameNotFoundException) { true }

    private suspend fun onAppForeground(packageName: String, routeDao: AppRouteDao, profileDao: VpnProfileDao) {
        if (packageName == this.packageName) return   // management UI never affects the tunnel
        if (packageName in ALWAYS_PASSTHROUGH) return
        val route = routeDao.getByPackage(packageName)
        if (route?.isPassthrough == true) return
        if (route == null && isSystemApp(packageName)) return

        val targetProfile = route?.profileId?.let { profileDao.getById(it) }
        if (targetProfile?.id == currentProfile?.id) return

        switchingFlow.value = true
        val acquired = withTimeoutOrNull(2_000L) { tunnelMutex.lock() }
        if (acquired == null) {
            Log.w(TAG, "TunnelMutex acquisition timed out for $packageName")
            switchingFlow.value = false
            return
        }
        try {
            if (currentProfile != null) {
                runCatching { activeTunnelManager?.stop() }
                    .onFailure { Log.e(TAG, "Error stopping tunnel: ${it.message}") }
            }
            if (targetProfile != null) {
                runCatching { wgManager.start(targetProfile, this) }
                    .onSuccess {
                        activeTunnelManager = wgManager
                        scope.launch(Dispatchers.IO) {
                            freshnessChecker.checkAndRefreshIfStale(targetProfile)
                        }
                    }
                    .onFailure {
                        Log.e(TAG, "Failed to start tunnel for ${targetProfile.name}: ${it.message}")
                        activeTunnelManager = null
                    }
            } else {
                activeTunnelManager = null
            }
            currentProfile = targetProfile
            DebugState.profileName = targetProfile?.name ?: "None"
            DebugState.profileType = targetProfile?.type?.name ?: ""
            profileFlow.value = targetProfile?.name
            Log.i(TAG, "Switched to profile=${targetProfile?.name} for $packageName")
        } finally {
            tunnelMutex.unlock()
            switchingFlow.value = false
        }
    }

    private fun getCurrentForegroundPackage(usm: UsageStatsManager): String? {
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 10_000L, now)
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

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked (another VPN took over)")
        scope.launch {
            runCatching { activeTunnelManager?.stop() }
            activeTunnelManager = null
            currentProfile = null
            DebugState.profileName = "None"
            DebugState.profileType = ""
            profileFlow.value = null
        }
        super.onRevoke()
    }

    companion object {
        private const val TAG = "UniVPN"
        private const val CHANNEL_ID = "univpn_service"
        private const val NOTIF_ID = 1

        @Volatile var instance: VpnSwitcherService? = null
        val switchingFlow = MutableStateFlow(false)
        val profileFlow = MutableStateFlow<String?>(null)

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
