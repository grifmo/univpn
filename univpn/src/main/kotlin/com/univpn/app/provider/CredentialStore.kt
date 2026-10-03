package com.univpn.app.provider

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CredentialStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "univpn_credentials",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // ── Account credentials ───────────────────────────────────────────────────

    fun save(accountId: String, credentials: ProviderCredentials) {
        prefs.edit()
            .putString("${accountId}_username", credentials.username)
            .putString("${accountId}_password", credentials.password)
            .apply()
    }

    fun load(accountId: String): ProviderCredentials? {
        val username = prefs.getString("${accountId}_username", null) ?: return null
        val password = prefs.getString("${accountId}_password", null) ?: return null
        return ProviderCredentials(username, password)
    }

    fun delete(accountId: String) {
        prefs.edit()
            .remove("${accountId}_username")
            .remove("${accountId}_password")
            .apply()
    }

    // ── Reusable WireGuard keypair (keyed by providerId + username) ───────────
    // Stored separately from per-profile data so the same keypair survives
    // across multiple server profiles for the same account.

    fun saveWgKeyPair(providerId: String, username: String, privateKey: String, publicKey: String) {
        val prefix = wgKey(providerId, username)
        prefs.edit()
            .putString("${prefix}_priv", privateKey)
            .putString("${prefix}_pub", publicKey)
            .apply()
    }

    fun loadWgKeyPair(providerId: String, username: String): Pair<String, String>? {
        val prefix = wgKey(providerId, username)
        val priv = prefs.getString("${prefix}_priv", null) ?: return null
        val pub  = prefs.getString("${prefix}_pub",  null) ?: return null
        return Pair(pub, priv)
    }

    fun deleteWgKeyPair(providerId: String, username: String) {
        val prefix = wgKey(providerId, username)
        prefs.edit().remove("${prefix}_priv").remove("${prefix}_pub").apply()
    }

    private fun wgKey(providerId: String, username: String) = "wg_${providerId}_${username}"
}
