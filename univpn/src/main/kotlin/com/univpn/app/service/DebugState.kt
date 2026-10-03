package com.univpn.app.service

import kotlinx.coroutines.flow.MutableStateFlow

object DebugState {
    @Volatile var profileName: String = "None"
    @Volatile var profileType: String = ""
    @Volatile var latencyMs: Int = -1
    @Volatile var txBytes: Long = 0L
    @Volatile var rxBytes: Long = 0L

    /** Reactive mirror of [latencyMs] for UI collection. -1 = no data. */
    val latencyFlow = MutableStateFlow(-1)
}
