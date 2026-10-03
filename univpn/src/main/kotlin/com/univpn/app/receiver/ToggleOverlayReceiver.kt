package com.univpn.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.univpn.app.service.DebugOverlayService
import com.univpn.app.service.VpnSwitcherService

class ToggleOverlayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission not granted — ignoring toggle")
            return
        }
        val wasRunning = DebugOverlayService.isRunning
        DebugOverlayService.toggle(context)
        Log.i(TAG, "Overlay toggled → ${if (!wasRunning) "ON" else "OFF"}")
        VpnSwitcherService.updateNotification(context)
    }

    companion object {
        private const val TAG = "UniVPN_Overlay"
        const val ACTION = "com.univpn.app.TOGGLE_OVERLAY"
    }
}
