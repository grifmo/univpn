package com.univpn.app.service

import com.univpn.app.data.model.AppRoute

/** What a foreground app asks of the tunnel. */
sealed class RouteDecision {
    /** Leave the tunnel as it is (Passthrough, or no route = "Default (keep current tunnel)"). */
    object Keep : RouteDecision()

    /** Run this profile; null means No VPN. */
    data class Use(val profileId: String?) : RouteDecision()
}

fun decideRoute(route: AppRoute?): RouteDecision = when {
    route == null || route.isPassthrough -> RouteDecision.Keep
    else -> RouteDecision.Use(route.profileId)
}

/** Delay before retrying a failed tunnel start: 5 s, 10 s, 20 s, 40 s, then every 60 s. */
fun retryDelayMs(attempt: Int): Long = minOf(60_000L, 5_000L shl minOf(attempt, 4))
