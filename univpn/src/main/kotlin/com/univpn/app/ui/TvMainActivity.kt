package com.univpn.app.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.univpn.app.R
import com.univpn.app.data.AppRouteSeeder
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.service.DebugState
import com.univpn.app.service.VpnSwitcherService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TvMainActivity : FragmentActivity() {

    private val navIds = listOf(R.id.navAppRoutes, R.id.navProfiles, R.id.navSettings)
    private var currentTab = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tv_main)

        lifecycleScope.launch(Dispatchers.IO) {
            AppRouteSeeder.seedIfNeeded(applicationContext)
            AppRouteSeeder.seedUserAppDefaults(applicationContext)
        }

        if (!hasUsageStatsPermission()) {
            Log.w(TAG, "PACKAGE_USAGE_STATS not granted — opening settings")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            finish()
            return
        }

        requestVpnConsentThenStart()

        // Wire up sidebar nav items — focus = navigate on TV
        navIds.forEachIndexed { index, id ->
            val navItem = findViewById<TextView>(id)
            navItem.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) selectTab(index) }
            navItem.setOnClickListener { selectTab(index) }
        }

        // Initial tab: App Routes if profiles exist, otherwise Profiles
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                val hasProfiles = withContext(Dispatchers.IO) {
                    AppDatabase.get(applicationContext).vpnProfileDao().getAll().first().isNotEmpty()
                }
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                    selectTab(if (hasProfiles) TAB_APP_ROUTES else TAB_PROFILES)
                }
            }
        }

        // Status chip
        val switchingChip = findViewById<TextView>(R.id.switchingChip)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    VpnSwitcherService.switchingFlow,
                    VpnSwitcherService.profileFlow,
                    DebugState.latencyFlow,
                ) { switching, profileName, latencyMs -> Triple(switching, profileName, latencyMs) }
                    .collect { (switching, profileName, latencyMs) ->
                        when {
                            switching -> {
                                switchingChip.text = "⟳ Switching…"
                                switchingChip.setTextColor(ContextCompat.getColor(this@TvMainActivity, R.color.latency_good))
                                switchingChip.visibility = View.VISIBLE
                            }
                            profileName != null -> {
                                val latencyText = if (latencyMs >= 0) " — ${latencyMs}ms" else ""
                                switchingChip.text = "$profileName$latencyText"
                                switchingChip.setTextColor(ContextCompat.getColor(
                                    this@TvMainActivity,
                                    when {
                                        latencyMs < 0   -> R.color.latency_good
                                        latencyMs < 50  -> R.color.latency_good
                                        latencyMs < 150 -> R.color.latency_warn
                                        else            -> R.color.latency_bad
                                    }
                                ))
                                switchingChip.visibility = View.VISIBLE
                            }
                            else -> switchingChip.visibility = View.GONE
                        }
                    }
            }
        }
    }

    private fun selectTab(index: Int) {
        if (index == currentTab) return
        currentTab = index

        navIds.forEachIndexed { i, id ->
            val tv = findViewById<TextView>(id)
            tv.isSelected = i == index
            tv.setTextColor(ContextCompat.getColor(
                this,
                if (i == index) R.color.text_primary else R.color.text_secondary
            ))
        }

        val fragment = when (index) {
            TAB_APP_ROUTES -> AppRoutesFragment()
            TAB_PROFILES   -> ProfilesFragment()
            else           -> SettingsFragment()
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.contentContainer, fragment)
            .commitAllowingStateLoss()
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
                Log.w(TAG, "VPN permission denied")
                Toast.makeText(this, "VPN permission is required", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startVpnService() {
        VpnSwitcherService.start(this)
        Log.i(TAG, "VpnSwitcherService started")
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    companion object {
        private const val TAG = "UniVPN_TV"
        private const val REQUEST_VPN_PERMISSION = 100
        const val TAB_APP_ROUTES = 0
        const val TAB_PROFILES   = 1
        const val TAB_SETTINGS   = 2
    }
}
