package com.univpn.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.univpn.app.R
import kotlinx.coroutines.launch

class ProvidersFragment : Fragment(R.layout.fragment_providers) {

    private val viewModel: ProviderViewModel by viewModels()
    private lateinit var adapter: ProviderAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = ProviderAdapter { item ->
            startActivity(
                Intent(requireContext(), ProviderSetupActivity::class.java)
                    .putExtra("provider_id", item.connector.id)
                    .putExtra("account_id", item.account?.accountId)
            )
        }

        view.findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@ProvidersFragment.adapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.providers.collect { items ->
                adapter.submitList(items)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        view?.findViewById<RecyclerView>(R.id.recycler)
            ?.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
    }
}
