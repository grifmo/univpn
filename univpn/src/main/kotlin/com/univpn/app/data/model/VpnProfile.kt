package com.univpn.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vpn_profiles")
data class VpnProfile(
    @PrimaryKey val id: String,
    val name: String,
    val type: TunnelType,
    val configContent: String
)
