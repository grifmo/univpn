package com.univpn.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.univpn.app.R

class ProviderAdapter(
    private val onClick: (ProviderUiItem) -> Unit
) : ListAdapter<ProviderUiItem, ProviderAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_provider, parent, false)
    ) {
        val name: TextView = itemView.findViewById(R.id.providerName)
        val status: TextView = itemView.findViewById(R.id.accountStatus)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(parent)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.name.text = item.connector.displayName
        holder.status.text = if (item.account != null) item.account.usernameHint else "Not configured"
        holder.itemView.setOnClickListener { onClick(item) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ProviderUiItem>() {
            override fun areItemsTheSame(a: ProviderUiItem, b: ProviderUiItem) = a.connector.id == b.connector.id
            override fun areContentsTheSame(a: ProviderUiItem, b: ProviderUiItem) = a == b
        }
    }
}
