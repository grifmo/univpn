package com.univpn.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.univpn.app.R

data class CountryItem(val name: String, val serverCount: Int)

class CountryAdapter(
    private val onClick: (CountryItem) -> Unit
) : ListAdapter<CountryItem, CountryAdapter.ViewHolder>(DIFF) {

    inner class ViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_server, parent, false)
    ) {
        val label: TextView = itemView.findViewById(R.id.serverLabel)
        val load: TextView = itemView.findViewById(R.id.serverLoad)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(parent)

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.label.text = item.name
        holder.load.text = "${item.serverCount} servers"
        holder.itemView.setOnClickListener { onClick(item) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CountryItem>() {
            override fun areItemsTheSame(a: CountryItem, b: CountryItem) = a.name == b.name
            override fun areContentsTheSame(a: CountryItem, b: CountryItem) = a == b
        }
    }
}
