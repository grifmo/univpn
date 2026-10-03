package com.univpn.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.GeneratedProfileMeta
import com.univpn.app.data.model.ProviderAccount
import com.univpn.app.data.model.TunnelType
import com.univpn.app.data.model.VpnProfile
import com.univpn.app.provider.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

sealed class ProviderSetupUiState {
    object CredentialEntry : ProviderSetupUiState()
    object FetchingServers : ProviderSetupUiState()
    data class AccountWarning(val message: String, val servers: List<VpnServer>) : ProviderSetupUiState()
    data class ServerPicker(val servers: List<VpnServer>) : ProviderSetupUiState()
    object GeneratingConfig : ProviderSetupUiState()
    data class Success(val profileName: String, val profileId: String) : ProviderSetupUiState()
    data class ErrorBadCredentials(val prefillUsername: String) : ProviderSetupUiState()
    object ErrorNetworkOffline : ProviderSetupUiState()
    object ErrorApiQuota : ProviderSetupUiState()
    data class ErrorGeneric(val message: String) : ProviderSetupUiState()
}

class ProviderSetupViewModel(app: Application) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow<ProviderSetupUiState>(ProviderSetupUiState.CredentialEntry)
    val uiState: StateFlow<ProviderSetupUiState> = _uiState

    private val db = AppDatabase.get(app)
    private val credStore = CredentialStore(app)
    private var fetchJob: Job? = null
    private var currentConnector: VpnProviderConnector? = null
    private var currentCredentials: ProviderCredentials? = null
    private var currentAccountId: String? = null

    // Server list cache for this session
    private var cachedServers: List<VpnServer> = emptyList()

    fun connect(connector: VpnProviderConnector, fieldValues: Map<String, String>) {
        currentConnector = connector
        val username = fieldValues["username"] ?: ""
        val password = fieldValues["password"] ?: ""
        currentCredentials = ProviderCredentials(username, password)

        fetchJob?.cancel()
        fetchJob = viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = ProviderSetupUiState.FetchingServers

            if (cachedServers.isNotEmpty()) {
                showServerPicker(cachedServers, username)
                return@launch
            }

            when (val result = connector.fetchServers(currentCredentials!!)) {
                is ConnectorResult.Success -> {
                    cachedServers = result.value
                    // Check account info
                    val accountResult = connector.fetchAccountInfo(currentCredentials!!)
                    val expiry = (accountResult as? ConnectorResult.Success)?.value?.expiryEpochMs
                    val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
                    if (expiry != null && expiry - System.currentTimeMillis() < sevenDaysMs) {
                        val days = ((expiry - System.currentTimeMillis()) / (24 * 60 * 60 * 1000)).coerceAtLeast(0)
                        _uiState.value = ProviderSetupUiState.AccountWarning(
                            "Your account expires in $days day${if (days != 1L) "s" else ""}",
                            cachedServers
                        )
                    } else {
                        showServerPicker(cachedServers, username)
                    }
                }
                is ConnectorResult.AuthError -> _uiState.value = ProviderSetupUiState.ErrorBadCredentials(username)
                is ConnectorResult.NetworkError -> _uiState.value = ProviderSetupUiState.ErrorNetworkOffline
                is ConnectorResult.ApiQuota -> _uiState.value = ProviderSetupUiState.ErrorApiQuota
                is ConnectorResult.UnknownError -> _uiState.value = ProviderSetupUiState.ErrorGeneric(result.cause.message ?: "Unknown error")
            }
        }
    }

    fun dismissWarning() {
        val servers = cachedServers
        if (servers.isNotEmpty()) {
            viewModelScope.launch { showServerPicker(servers, currentCredentials?.username ?: "") }
        }
    }

    fun selectServer(server: VpnServer) {
        val connector = currentConnector ?: return
        val credentials = currentCredentials ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = ProviderSetupUiState.GeneratingConfig

            // For reuse-capable providers (Mullvad), load or generate a single
            // keypair per account so every server profile shares the same key slot.
            val (wgPub, wgPriv) = if (connector.supportsKeyReuse) {
                credStore.loadWgKeyPair(connector.id, credentials.username)
                    ?: run {
                        val kp = com.wireguard.crypto.KeyPair()
                        val priv = kp.privateKey.toBase64()
                        val pub  = kp.publicKey.toBase64()
                        credStore.saveWgKeyPair(connector.id, credentials.username, priv, pub)
                        Pair(pub, priv)
                    }
            } else Pair(null, null)

            when (val result = connector.generateConfig(server, credentials, wgPub, wgPriv)) {
                is ConnectorResult.Success -> {
                    val (config, pubKey) = result.value
                    val profileId = UUID.randomUUID().toString()
                    val profileName = "${connector.displayName} — ${server.city} (${server.serverName})"
                    val profile = VpnProfile(profileId, profileName, TunnelType.WIREGUARD, config)
                    db.vpnProfileDao().insert(profile)

                    val accountId = currentAccountId ?: UUID.randomUUID().toString()
                    val accountInfo = (connector.fetchAccountInfo(credentials) as? ConnectorResult.Success)?.value
                    db.providerAccountDao().upsert(ProviderAccount(
                        accountId = accountId,
                        providerId = connector.id,
                        usernameHint = accountInfo?.usernameHint ?: credentials.username,
                        expiryEpochMs = accountInfo?.expiryEpochMs
                    ))
                    credStore.save(accountId, credentials)
                    currentAccountId = accountId

                    db.generatedProfileMetaDao().upsert(GeneratedProfileMeta(
                        profileId = profileId,
                        accountId = accountId,
                        providerId = connector.id,
                        serverId = server.id,
                        serverName = server.serverName,
                        serverCountry = server.country,
                        serverCity = server.city,
                        generatedAt = System.currentTimeMillis(),
                        publicKey = pubKey
                    ))
                    Log.i(TAG, "Profile saved: $profileName")
                    _uiState.value = ProviderSetupUiState.Success(profileName, profileId)
                }
                is ConnectorResult.AuthError -> _uiState.value = ProviderSetupUiState.ErrorBadCredentials(credentials.username)
                is ConnectorResult.NetworkError -> _uiState.value = ProviderSetupUiState.ErrorNetworkOffline
                is ConnectorResult.ApiQuota -> _uiState.value = ProviderSetupUiState.ErrorApiQuota
                is ConnectorResult.UnknownError -> _uiState.value = ProviderSetupUiState.ErrorGeneric(result.cause.message ?: "Unknown error")
            }
        }
    }

    fun connectWithStoredCredentials(connector: VpnProviderConnector, accountId: String) {
        val credentials = credStore.load(accountId) ?: run {
            _uiState.value = ProviderSetupUiState.CredentialEntry
            return
        }
        currentAccountId = accountId
        connect(connector, mapOf("username" to credentials.username, "password" to credentials.password))
    }

    fun retry() { _uiState.value = ProviderSetupUiState.CredentialEntry }

    private fun showServerPicker(servers: List<VpnServer>, username: String) {
        if (servers.isEmpty()) {
            _uiState.value = ProviderSetupUiState.ErrorGeneric("No servers available")
            return
        }
        _uiState.value = ProviderSetupUiState.ServerPicker(servers)
    }

    companion object { private const val TAG = "ProviderSetupVM" }
}
