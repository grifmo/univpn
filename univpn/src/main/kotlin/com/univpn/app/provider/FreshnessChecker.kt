package com.univpn.app.provider

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.VpnProfile
import com.univpn.app.tunnel.WgConfig

private const val REFRESH_AGE_MS = 7L * 24 * 60 * 60 * 1000  // 7 days
private const val TAG = "FreshnessChecker"

class FreshnessChecker(private val context: Context) {

    /** Returns true if [profile]'s config was replaced, so a running tunnel should be restarted. */
    suspend fun checkAndRefreshIfStale(profile: VpnProfile): Boolean {
        val db = AppDatabase.get(context)
        val meta = db.generatedProfileMetaDao().getByProfileId(profile.id) ?: return false
        val age = System.currentTimeMillis() - meta.generatedAt
        if (age < REFRESH_AGE_MS) return false

        val connector = ProviderRegistry.get(meta.providerId) ?: return false
        val credStore = CredentialStore(context)
        val credentials = credStore.load(meta.accountId) ?: run {
            Log.w(TAG, "No credentials for ${meta.providerId} account ${meta.accountId}")
            return false
        }

        // Keep the endpoint the profile already uses (the relay's IP for Mullvad). The server id
        // only holds the relay-list hostname, which doesn't resolve on its own.
        val server = VpnServer(
            id = meta.serverId,
            country = meta.serverCountry,
            city = meta.serverCity,
            hostname = WgConfig.endpointHost(profile.configContent) ?: meta.serverId.substringBefore("|"),
            serverName = meta.serverName,
        )

        // For reuse-capable connectors, load the stored keypair so the same key
        // slot is used during refresh rather than registering a new one.
        val (wgPub, wgPriv) = if (connector.supportsKeyReuse) {
            credStore.loadWgKeyPair(meta.providerId, credentials.username)
                ?: Pair(meta.publicKey.ifEmpty { null }, null)
        } else Pair(meta.publicKey.ifEmpty { null }, null)

        val result = connector.generateConfig(server, credentials, wgPub, wgPriv)
        if (result !is ConnectorResult.Success) {
            Log.w(TAG, "Config refresh failed for ${profile.name}: $result")
            return false
        }
        val (newConfig, newPubKey) = result.value
        // UPDATE rather than INSERT OR REPLACE: a profile deleted while the request was in
        // flight stays deleted, and the metadata isn't cascade-deleted and re-inserted.
        val updated = db.withTransaction {
            if (db.vpnProfileDao().update(profile.copy(configContent = newConfig)) == 0) {
                false
            } else {
                db.generatedProfileMetaDao().upsert(
                    meta.copy(generatedAt = System.currentTimeMillis(), publicKey = newPubKey)
                )
                true
            }
        }
        if (updated) Log.i(TAG, "Silently refreshed config for ${profile.name}")
        else Log.i(TAG, "${profile.name} was deleted during refresh; not recreating it")
        return updated
    }

    /** Fixes Mullvad configs whose Endpoint was written as a bare, unresolvable relay hostname. */
    suspend fun repairMullvadEndpoints() {
        val db = AppDatabase.get(context)
        for (meta in db.generatedProfileMetaDao().getByProvider("mullvad")) {
            val profile = db.vpnProfileDao().getById(meta.profileId) ?: continue
            val repaired = MullvadEndpoint.repair(profile.configContent)
            if (repaired != profile.configContent) {
                db.vpnProfileDao().update(profile.copy(configContent = repaired))
                Log.i(TAG, "Repaired Mullvad endpoint for ${profile.name}")
            }
        }
    }
}
