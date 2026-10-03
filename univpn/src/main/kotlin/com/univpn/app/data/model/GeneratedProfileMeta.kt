package com.univpn.app.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "generated_profile_meta",
    foreignKeys = [ForeignKey(
        entity = VpnProfile::class,
        parentColumns = ["id"],
        childColumns = ["profileId"],
        onDelete = ForeignKey.CASCADE
    )]
)
data class GeneratedProfileMeta(
    @PrimaryKey val profileId: String,
    val accountId: String,
    val providerId: String,
    val serverId: String,
    val serverName:String,
    val serverCountry: String,
    val serverCity: String,
    val generatedAt: Long,        // epoch millis
    val publicKey: String = ""    // WireGuard public key for Mullvad re-registration
)
