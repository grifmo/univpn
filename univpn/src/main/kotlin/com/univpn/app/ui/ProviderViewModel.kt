package com.univpn.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.ProviderAccount
import com.univpn.app.provider.AccountRemover
import com.univpn.app.provider.CredentialStore
import com.univpn.app.provider.ProviderRegistry
import com.univpn.app.provider.VpnProviderConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProviderUiItem(
    val connector: VpnProviderConnector,
    val account: ProviderAccount?
)

class ProviderViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)

    val providers = db.providerAccountDao().getAll()
        .map { accounts ->
            val accountMap = accounts.groupBy { it.providerId }
            ProviderRegistry.all.map { connector ->
                ProviderUiItem(
                    connector = connector,
                    account = accountMap[connector.id]?.firstOrNull()
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, ProviderRegistry.all.map { ProviderUiItem(it, null) })

    fun deleteAccount(accountId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            AccountRemover(db.providerAccountDao(), CredentialStore(getApplication())).delete(accountId)
        }
    }
}
