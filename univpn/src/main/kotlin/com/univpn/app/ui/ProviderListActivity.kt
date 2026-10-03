package com.univpn.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.univpn.app.R
import kotlinx.coroutines.launch

class ProviderListActivity : AppCompatActivity() {

    private val viewModel: ProviderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provider_list)

        val adapter = ProviderAdapter { item ->
            startActivity(
                Intent(this, ProviderSetupActivity::class.java)
                    .putExtra("provider_id", item.connector.id)
                    .putExtra("account_id", item.account?.accountId)
            )
        }

        findViewById<RecyclerView>(R.id.recycler).apply {
            layoutManager = LinearLayoutManager(this@ProviderListActivity)
            this.adapter = adapter
        }

        lifecycleScope.launch {
            viewModel.providers.collect { adapter.submitList(it) }
        }
    }
}
