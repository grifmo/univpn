package com.univpn.app.provider

import java.io.IOException

sealed class ConnectorResult<out T> {
    data class Success<T>(val value: T) : ConnectorResult<T>()
    data class AuthError(val message: String) : ConnectorResult<Nothing>()
    data class NetworkError(val cause: IOException) : ConnectorResult<Nothing>()
    data class ApiQuota(val retryAfterSeconds: Int?) : ConnectorResult<Nothing>()
    data class UnknownError(val cause: Throwable) : ConnectorResult<Nothing>()
}

data class ProviderCredentials(val username: String, val password: String) {
    override fun toString() = "ProviderCredentials(username=$username, password=[REDACTED])"
}

data class VpnServer(
    val id: String,
    val country: String,
    val city: String,
    val serverName: String,
    val hostname: String,
    val load: Int = -1,
    val owned: Boolean = true
)

data class CredentialField(
    val key: String,
    val label: String,
    val inputType: Int,
    val hint: String = ""
)

interface VpnProviderConnector {
    val id: String
    val displayName: String
    val credentialFields: List<CredentialField>
    val signUpUrl: String

    /**
     * When true, all profiles for the same account share a single WireGuard keypair.
     * The keypair is generated once and stored in CredentialStore, so only one key
     * slot is consumed at the provider regardless of how many server profiles exist.
     */
    val supportsKeyReuse: Boolean get() = false

    suspend fun fetchServers(credentials: ProviderCredentials): ConnectorResult<List<VpnServer>>

    suspend fun generateConfig(
        server: VpnServer,
        credentials: ProviderCredentials,
        existingPublicKey: String? = null,
        existingPrivateKey: String? = null
    ): ConnectorResult<Pair<String, String>>   // config content, public key

    suspend fun fetchAccountInfo(credentials: ProviderCredentials): ConnectorResult<AccountInfo>
}

data class AccountInfo(
    val usernameHint: String,
    val expiryEpochMs: Long? = null
)
