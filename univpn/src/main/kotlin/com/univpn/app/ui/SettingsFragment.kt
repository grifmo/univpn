package com.univpn.app.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.univpn.app.R
import com.univpn.app.receiver.BootReceiver
import com.univpn.app.service.DebugOverlayService
import com.univpn.app.service.OverlayPosition
import com.univpn.app.service.OverlaySize

class SettingsFragment : Fragment(R.layout.fragment_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<LinearLayout>(R.id.rowAutoStart).setOnClickListener {
            val ctx = requireContext()
            BootReceiver.setAutoStart(ctx, !BootReceiver.autoStartEnabled(ctx))
            syncState(view)
        }

        view.findViewById<LinearLayout>(R.id.rowOverlay).setOnClickListener {
            val ctx = requireContext()
            if (!Settings.canDrawOverlays(ctx)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
                    )
                )
                return@setOnClickListener
            }
            DebugOverlayService.toggle(ctx)
            // Service starts/stops asynchronously; wait for it before re-syncing.
            view.postDelayed({ if (isAdded) syncState(view) }, 400)
        }

        view.findViewById<LinearLayout>(R.id.rowOverlaySize).setOnClickListener {
            val ctx = requireContext()
            val next = when (DebugOverlayService.getSize(ctx)) {
                OverlaySize.MIN -> OverlaySize.MED
                OverlaySize.MED -> OverlaySize.MAX
                OverlaySize.MAX -> OverlaySize.MIN
            }
            DebugOverlayService.setSize(ctx, next)
            DebugOverlayService.refresh(ctx)
            syncState(view)
        }

        view.findViewById<LinearLayout>(R.id.rowOverlayPosition).setOnClickListener {
            val ctx = requireContext()
            val positions = OverlayPosition.entries
            val current = DebugOverlayService.getPosition(ctx)
            val next = positions[(positions.indexOf(current) + 1) % positions.size]
            DebugOverlayService.setPosition(ctx, next)
            DebugOverlayService.refresh(ctx)
            syncState(view)
        }

        syncState(view)
    }

    override fun onResume() {
        super.onResume()
        view?.let { syncState(it) }
    }

    private fun syncState(view: View) {
        val ctx = requireContext()
        val overlayOn = DebugOverlayService.isRunning

        setIndicator(view.findViewById(R.id.autoStartState), BootReceiver.autoStartEnabled(ctx))
        setIndicator(view.findViewById(R.id.overlayState), overlayOn)

        val rowSize = view.findViewById<LinearLayout>(R.id.rowOverlaySize)
        val rowPos  = view.findViewById<LinearLayout>(R.id.rowOverlayPosition)
        rowSize.visibility = if (overlayOn) View.VISIBLE else View.GONE
        rowPos.visibility  = if (overlayOn) View.VISIBLE else View.GONE

        if (overlayOn) {
            setSizeIndicator(view.findViewById(R.id.overlaySizeState), DebugOverlayService.getSize(ctx))
            setPosIndicator(view.findViewById(R.id.overlayPositionState), DebugOverlayService.getPosition(ctx))
        }
    }

    private fun setIndicator(pill: TextView, on: Boolean) {
        val ctx = requireContext()
        if (on) {
            pill.text = "ON"
            pill.setTextColor(ContextCompat.getColor(ctx, R.color.bg))
            (pill.background as? GradientDrawable)?.setColor(ContextCompat.getColor(ctx, R.color.accent))
        } else {
            pill.text = "OFF"
            pill.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            (pill.background as? GradientDrawable)?.setColor(ContextCompat.getColor(ctx, R.color.surface_elevated))
        }
    }

    private fun setSizeIndicator(pill: TextView, size: OverlaySize) {
        val ctx = requireContext()
        pill.text = size.name
        pill.setTextColor(ContextCompat.getColor(ctx, R.color.bg))
        (pill.background as? GradientDrawable)?.setColor(ContextCompat.getColor(ctx, R.color.accent))
    }

    private fun setPosIndicator(pill: TextView, pos: OverlayPosition) {
        val ctx = requireContext()
        pill.text = when (pos) {
            OverlayPosition.TOP_END    -> "TOP R"
            OverlayPosition.TOP_START  -> "TOP L"
            OverlayPosition.BOTTOM_END -> "BOT R"
            OverlayPosition.BOTTOM_START -> "BOT L"
        }
        pill.setTextColor(ContextCompat.getColor(ctx, R.color.bg))
        (pill.background as? GradientDrawable)?.setColor(ContextCompat.getColor(ctx, R.color.accent))
    }
}
