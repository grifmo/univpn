package com.univpn.app

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.univpn.app.receiver.BootReceiver
import com.univpn.app.service.DebugOverlayService
import com.univpn.app.service.VpnSwitcherService
import com.univpn.app.ui.AppRouteActivity
import com.univpn.app.ui.ProfileListActivity
import com.univpn.app.ui.ProviderListActivity

class MainActivity : AppCompatActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.i(TAG, "UniVPN starting on ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})")

        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "PACKAGE_USAGE_STATS not granted — opening settings")
            findViewById<TextView>(R.id.statusText).text = "Usage access required — check Settings"
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }

        requestVpnConsentThenStart()

        findViewById<Button>(R.id.configureButton).setOnClickListener {
            startActivity(Intent(this, AppRouteActivity::class.java))
        }
        findViewById<Button>(R.id.profilesButton).setOnClickListener {
            startActivity(Intent(this, ProfileListActivity::class.java))
        }
        findViewById<Button>(R.id.providersButton).setOnClickListener {
            startActivity(Intent(this, ProviderListActivity::class.java))
        }
        findViewById<Button>(R.id.autoStartButton).setOnClickListener {
            val enabled = !BootReceiver.autoStartEnabled(this)
            BootReceiver.setAutoStart(this, enabled)
            syncAutoStartButton()
        }
        findViewById<Button>(R.id.overlayButton).setOnClickListener {
            toggleOverlay()
        }
    }

    override fun onResume() {
        super.onResume()
        syncAutoStartButton()
        syncOverlayButton()
    }

    private fun requestVpnConsentThenStart() {
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            Log.i(TAG, "VPN consent required — showing dialog")
            @Suppress("DEPRECATION")
            startActivityForResult(vpnIntent, REQUEST_VPN_PERMISSION)
        } else {
            startVpnService()
        }
    }

    @Deprecated("Using legacy onActivityResult for TV compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN_PERMISSION) {
            if (resultCode == RESULT_OK) {
                startVpnService()
            } else {
                Log.w(TAG, "VPN permission denied by user")
                findViewById<TextView>(R.id.statusText).text = "VPN permission required"
                Toast.makeText(this, "VPN permission is required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startVpnService() {
        VpnSwitcherService.start(this)
        Log.i(TAG, "VpnSwitcherService started")
        findViewById<TextView>(R.id.statusText).text = "Service running"
    }

    private fun toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        DebugOverlayService.toggle(this)
        syncOverlayButton()
    }

    private fun syncAutoStartButton() {
        val enabled = BootReceiver.autoStartEnabled(this)
        findViewById<Button>(R.id.autoStartButton).text =
            if (enabled) "Auto-start: ON" else "Auto-start: OFF"
    }

    private fun syncOverlayButton() {
        findViewById<Button>(R.id.overlayButton).text =
            if (DebugOverlayService.isRunning) "Debug Overlay: ON" else "Debug Overlay: OFF"
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        private const val TAG = "UniVPN"
        private const val REQUEST_VPN_PERMISSION = 100
    }
}
