package com.univpn.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.univpn.app.R
import com.univpn.app.provider.VpnServer

class ServerAdapter(
    private val onClick: (VpnServer) -> Unit,
    private val showCountry: Boolean = false
) : ListAdapter<VpnServer, ServerAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
    ) {
        val label: TextView = itemView.findViewById(R.id.serverLabel)
        val load: TextView = itemView.findViewById(R.id.serverLoad)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(parent)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = getItem(position)
        val ownership = if (server.owned) "" else " (rented)"
        val hostDisplay = server.id.substringBefore("|").substringBefore(".").ifEmpty { server.hostname }
        holder.label.text = if (showCountry)
            "${server.country} — ${server.city} — $hostDisplay$ownership"
        else
            "${server.city} — $hostDisplay$ownership"
        holder.load.text = if (server.load >= 0) "${server.load}% load" else ""
        holder.itemView.setOnClickListener { onClick(server) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VpnServer>() {
            override fun areItemsTheSame(a: VpnServer, b: VpnServer) = a.id == b.id
            override fun areContentsTheSame(a: VpnServer, b: VpnServer) = a == b
        }
    }
}
