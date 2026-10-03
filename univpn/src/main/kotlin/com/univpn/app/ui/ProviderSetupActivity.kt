package com.univpn.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.univpn.app.R

class ProviderSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_provider_setup)
        val providerId = intent.getStringExtra("provider_id") ?: return finish()
        val accountId = intent.getStringExtra("account_id")
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.container, ProviderSetupFragment.newInstance(providerId, accountId))
                .commit()
        }
    }
}
