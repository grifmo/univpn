package com.univpn.app.service

import com.univpn.app.data.model.AppRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteDecisionTest {

    @Test
    fun noRoute_keepsCurrentTunnel() {
        // "Default (keep current tunnel)" deletes the route; it must not drop the tunnel (#5).
        assertEquals(RouteDecision.Keep, decideRoute(null))
    }

    @Test
    fun passthrough_keepsCurrentTunnel() {
        assertEquals(RouteDecision.Keep, decideRoute(AppRoute("com.example.launcher", null, isPassthrough = true)))
    }

    @Test
    fun explicitNoVpn_usesNoProfile() {
        assertEquals(RouteDecision.Use(null), decideRoute(AppRoute("com.example.tv", null, isPassthrough = false)))
    }

    @Test
    fun profileRoute_usesThatProfile() {
        assertEquals(RouteDecision.Use("p1"), decideRoute(AppRoute("com.bbc.iplayer", "p1", isPassthrough = false)))
    }

    @Test
    fun retryDelay_backsOffAndCapsAtOneMinute() {
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 40_000L, 60_000L, 60_000L, 60_000L),
            (0..6).map { retryDelayMs(it) })
        assertEquals(60_000L, retryDelayMs(1_000))
    }
}
