package com.univpn.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "provider_accounts")
data class ProviderAccount(
    @PrimaryKey val accountId: String,   // UUID
    val providerId: String,              // "mullvad" | "nordvpn" | "pia"
    val usernameHint: String,            // display only, not a secret
    val expiryEpochMs: Long? = null      // null if unknown
)
