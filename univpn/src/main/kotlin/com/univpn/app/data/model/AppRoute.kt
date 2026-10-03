package com.univpn.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_routes")
data class AppRoute(
    @PrimaryKey val packageName: String,
    val profileId: String?,
    val isPassthrough: Boolean = false,
    val isHidden: Boolean = false
)
