package com.univpn.app.provider

import android.content.Context
import android.util.Log
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.GeneratedProfileMeta
import com.univpn.app.data.model.VpnProfile
import com.univpn.app.data.model.TunnelType
import java.util.UUID

private const val REFRESH_AGE_MS = 7L * 24 * 60 * 60 * 1000  // 7 days
private const val TAG = "FreshnessChecker"

class FreshnessChecker(private val context: Context) {

    suspend fun checkAndRefreshIfStale(profile: VpnProfile) {
        val db = AppDatabase.get(context)
        val meta = db.generatedProfileMetaDao().getByProfileId(profile.id) ?: return
        val age = System.currentTimeMillis() - meta.generatedAt
        if (age < REFRESH_AGE_MS) return

        val connector = ProviderRegistry.get(meta.providerId) ?: return
        val credStore = CredentialStore(context)
        val credentials = credStore.load(meta.accountId) ?: run {
            Log.w(TAG, "No credentials for ${meta.providerId} account ${meta.accountId}")
            return
        }

        val server = VpnServer(
            id = meta.serverId,
            country = meta.serverCountry,
            city = meta.serverCity,
            hostname = meta.serverId.substringBefore("|"),
            serverName = meta.serverName,
        )

        // For reuse-capable connectors, load the stored keypair so the same key
        // slot is used during refresh rather than registering a new one.
        val (wgPub, wgPriv) = if (connector.supportsKeyReuse) {
            credStore.loadWgKeyPair(meta.providerId, credentials.username)
                ?: Pair(meta.publicKey.ifEmpty { null }, null)
        } else Pair(meta.publicKey.ifEmpty { null }, null)

        val result = connector.generateConfig(server, credentials, wgPub, wgPriv)
        if (result is ConnectorResult.Success) {
            val (newConfig, newPubKey) = result.value
            val updatedProfile = profile.copy(configContent = newConfig)
            db.vpnProfileDao().insert(updatedProfile)  // REPLACE strategy
            db.generatedProfileMetaDao().upsert(
                meta.copy(generatedAt = System.currentTimeMillis(), publicKey = newPubKey)
            )
            Log.i(TAG, "Silently refreshed config for ${profile.name}")
        } else {
            Log.w(TAG, "Config refresh failed for ${profile.name}: $result")
        }
    }
}
