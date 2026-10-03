package com.univpn.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import com.univpn.app.service.VpnSwitcherService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!autoStartEnabled(context)) return

        if (VpnService.prepare(context) != null) {
            Log.w(TAG, "VPN consent not yet granted — skipping auto-start")
            return
        }

        Log.i(TAG, "Boot complete — starting VpnSwitcherService")
        VpnSwitcherService.start(context)
    }

    companion object {
        private const val TAG = "UniVPN_Boot"
        private const val PREFS = "univpn"
        private const val KEY_AUTO_START = "auto_start"

        fun autoStartEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START, false)

        fun setAutoStart(context: Context, enabled: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_AUTO_START, enabled).apply()
    }
}
