package com.univpn.app.provider

import com.univpn.app.data.db.ProviderAccountDao

/**
 * Deletes a provider account together with the secrets stored for it. Profiles generated from
 * the account are kept: their configs still work, they just can no longer be refreshed.
 */
class AccountRemover(
    private val accountDao: ProviderAccountDao,
    private val credStore: CredentialStore,
) {
    suspend fun delete(accountId: String) {
        val account = accountDao.getById(accountId)
        val username = credStore.load(accountId)?.username
        accountDao.deleteById(accountId)
        credStore.delete(accountId)

        // The reusable WireGuard keypair belongs to the login (provider + username), which other
        // accounts may share; remove it only with the last of them.
        if (account != null && username != null) {
            val stillUsed = accountDao.getByProvider(account.providerId)
                .any { credStore.load(it.accountId)?.username == username }
            if (!stillUsed) credStore.deleteWgKeyPair(account.providerId, username)
        }
    }
}
