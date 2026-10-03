package com.univpn.app.ui

import android.graphics.drawable.Drawable

data class AppItem(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val currentProfileId: String?,
    val isPassthrough: Boolean = false,
    val isExplicitNoVpn: Boolean = false,
    val isSystemApp: Boolean = false,
    val isHidden: Boolean = false
)
