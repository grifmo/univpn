package com.univpn.app.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.univpn.app.R
import com.univpn.app.data.model.VpnProfile

sealed class RouteChoice {
    object Default : RouteChoice()      // delete row → service uses global default (keep current)
    object NoVpn : RouteChoice()        // explicit row with null profileId → service drops tunnel
    object Passthrough : RouteChoice()  // explicit row with isPassthrough=true → keep current
    data class Profile(val id: String) : RouteChoice()
}

class AppAdapter(
    private val getProfiles: () -> List<VpnProfile>,
    private val onAssign: (packageName: String, choice: RouteChoice) -> Unit,
    private val onToggleHide: (packageName: String) -> Unit,
) : ListAdapter<AppItem, AppAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_app_route, parent, false)
    ) {
        val mainArea: View     = itemView.findViewById(R.id.mainArea)
        val icon: ImageView    = itemView.findViewById(R.id.icon)
        val appName: TextView  = itemView.findViewById(R.id.appName)
        val profileBadge: TextView = itemView.findViewById(R.id.profileBadge)
        val hideButton: ImageButton = itemView.findViewById(R.id.hideButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(parent)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)

        holder.icon.setImageDrawable(item.icon)
        holder.appName.text = item.label
        val badgeText = when {
            item.isExplicitNoVpn -> "No VPN"
            item.isPassthrough   -> "Passthrough"
            item.currentProfileId != null -> getProfiles().find { it.id == item.currentProfileId }?.name ?: "Default"
            else -> "Default"
        }
        holder.profileBadge.text = badgeText
        // Active profile → accent teal; neutral states → recede into secondary
        val badgeColor = if (item.currentProfileId != null && !item.isExplicitNoVpn && !item.isPassthrough)
            ContextCompat.getColor(holder.itemView.context, R.color.accent)
        else
            ContextCompat.getColor(holder.itemView.context, R.color.text_secondary)
        holder.profileBadge.setTextColor(badgeColor)

        // Dim hidden apps so they're visually distinct when shown
        holder.itemView.alpha = if (item.isHidden) 0.45f else 1f

        holder.hideButton.setImageResource(
            if (item.isHidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility
        )

        holder.mainArea.setOnClickListener { showProfilePicker(it.context, item) }
        holder.hideButton.setOnClickListener { onToggleHide(item.packageName) }
    }

    private fun showProfilePicker(context: Context, item: AppItem) {
        val profiles = getProfiles()
        val options = arrayOf(
            "Default (keep current tunnel)",
            "No VPN (drop tunnel)",
            "Passthrough (keep current)"
        ) + profiles.map { it.name }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(item.label)
            .setItems(options) { _, which ->
                val choice = when (which) {
                    0 -> RouteChoice.Default
                    1 -> RouteChoice.NoVpn
                    2 -> RouteChoice.Passthrough
                    else -> RouteChoice.Profile(profiles[which - 3].id)
                }
                onAssign(item.packageName, choice)
            }
            .show()
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<AppItem>() {
            override fun areItemsTheSame(a: AppItem, b: AppItem) = a.packageName == b.packageName
            override fun areContentsTheSame(a: AppItem, b: AppItem) = a == b
        }
    }
}
