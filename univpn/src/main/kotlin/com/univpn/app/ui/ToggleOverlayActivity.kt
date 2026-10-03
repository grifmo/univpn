package com.univpn.app.ui

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import com.univpn.app.service.DebugOverlayService

class ToggleOverlayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Settings.canDrawOverlays(this)) {
            DebugOverlayService.toggle(this)
        }
        finish()
    }
}
