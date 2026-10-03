package com.univpn.app.ui

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.univpn.app.R
import com.univpn.app.data.model.VpnProfile
import com.univpn.app.service.TestResult
import com.univpn.app.service.VpnSwitcherService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ProfileAdapter(
    private val onDelete: (VpnProfile) -> Unit
) : ListAdapter<VpnProfile, ProfileAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_profile, parent, false)
    ) {
        val name: TextView = itemView.findViewById(R.id.profileName)
        val type: TextView = itemView.findViewById(R.id.profileType)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(parent)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = getItem(position)
        holder.name.text = profile.name
        holder.type.text = "WireGuard"

        holder.itemView.setOnClickListener {
            showConfig(it.context, profile)
        }

        holder.itemView.setOnLongClickListener {
            AlertDialog.Builder(it.context)
                .setTitle("Delete \"${profile.name}\"?")
                .setMessage("This will remove the profile. Any app routes using it will have no VPN assigned.")
                .setPositiveButton("Delete") { _, _ -> onDelete(profile) }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    private fun showConfig(context: android.content.Context, profile: VpnProfile) {
        val scroll = ScrollView(context)
        val text = TextView(context).apply {
            text = profile.configContent
            textSize = 11f
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(32, 24, 32, 24)
        }
        scroll.addView(text)

        AlertDialog.Builder(context)
            .setTitle(profile.name)
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Test Connection") { _, _ -> testConnection(context, profile) }
            .show()
    }

    private fun testConnection(context: android.content.Context, profile: VpnProfile) {
        val service = VpnSwitcherService.instance
        if (service == null) {
            AlertDialog.Builder(context)
                .setTitle("Test unavailable")
                .setMessage("The VPN service is not running. Open UniVPN first to start it.")
                .setPositiveButton("Close", null)
                .show()
            return
        }

        val progress = AlertDialog.Builder(context)
            .setTitle("Testing ${profile.name}")
            .setMessage("Establishing WireGuard connection…\n\nThis may take up to 15 seconds.")
            .setCancelable(false)
            .show()

        CoroutineScope(Dispatchers.IO).launch {
            val result = service.testProfile(profile)
            val message = when (result) {
                is TestResult.Success ->
                    "✓  Connected successfully\n\nExternal IP through VPN:\n${result.externalIp}"
                is TestResult.NoHandshake ->
                    "✗  No response after 15 seconds\n\nThe WireGuard handshake did not complete. " +
                    "Check that the server endpoint and public key in the config are correct."
                is TestResult.Error ->
                    "✗  Error: ${result.reason}"
                is TestResult.ServiceUnavailable ->
                    "✗  VPN service not available"
            }
            Handler(Looper.getMainLooper()).post {
                progress.dismiss()
                AlertDialog.Builder(context)
                    .setTitle("Test: ${profile.name}")
                    .setMessage(message)
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VpnProfile>() {
            override fun areItemsTheSame(a: VpnProfile, b: VpnProfile) = a.id == b.id
            override fun areContentsTheSame(a: VpnProfile, b: VpnProfile) = a == b
        }
    }
}
