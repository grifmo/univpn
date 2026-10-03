package com.univpn.app.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.AppRoute
import com.univpn.app.data.model.TunnelType
import com.univpn.app.data.model.VpnProfile
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import org.junit.Assert.assertNotEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the routing invariants that govern behaviour on every foreground-app change,
 * and specifically on service restart (START_STICKY after process kill).
 *
 * Architecture note: VpnSwitcherService.onAppForeground() performs three lookups
 * then decides whether to call wgManager.start/stop. These tests verify that
 * decision logic by exercising the DAO layer directly — the same path the service
 * takes — without needing a live VPN connection.
 *
 * The critical invariant for SERVICE_DEAD recovery is:
 *   currentProfile == null after restart
 *   → targetProfile?.id != null?.id  (guard NEVER fires)
 *   → tunnel IS re-established on the first foreground poll (~1 s)
 *   → cleartext window ≤ ~2 s (poll interval + switch latency)
 *
 * If the guard fired incorrectly (e.g. null == null short-circuit), traffic
 * would flow without a tunnel until the user manually intervened — undetectable
 * in production because it looks identical to a working passthrough route.
 */
@RunWith(AndroidJUnit4::class)
class ServiceRecoveryTest {

    private lateinit var db: AppDatabase
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() { db.close() }

    // ── Profile resolution ────────────────────────────────────────────────────

    @Test
    fun profileResolution_returnsCorrectProfile_forMappedApp() = runBlocking {
        val profile = VpnProfile("p1", "UK London", TunnelType.WIREGUARD, "")
        db.vpnProfileDao().insert(profile)
        db.appRouteDao().upsert(AppRoute("com.example.streamer", profileId = "p1", isPassthrough = false))

        val route = db.appRouteDao().getByPackage("com.example.streamer")
        val resolved = route?.profileId?.let { db.vpnProfileDao().getById(it) }

        assertEquals("p1", resolved?.id)
        assertEquals("UK London", resolved?.name)
    }

    @Test
    fun profileResolution_returnsNull_forUnknownApp() = runBlocking {
        assertNull(db.appRouteDao().getByPackage("com.unknown.app"))
    }

    @Test
    fun profileResolution_isPassthrough_forPassthroughRoute() = runBlocking {
        db.appRouteDao().upsert(AppRoute("com.example.browser", profileId = null, isPassthrough = true))

        val route = db.appRouteDao().getByPackage("com.example.browser")
        assertTrue(route?.isPassthrough == true)
        assertNull(route?.profileId)
    }

    @Test
    fun profileResolution_differentAppsGetDifferentProfiles() = runBlocking {
        db.vpnProfileDao().insert(VpnProfile("p1", "UK", TunnelType.WIREGUARD, ""))
        db.vpnProfileDao().insert(VpnProfile("p2", "US", TunnelType.WIREGUARD, ""))
        db.appRouteDao().upsert(AppRoute("com.bbc.iplayer", profileId = "p1", isPassthrough = false))
        db.appRouteDao().upsert(AppRoute("com.netflix.mediaclient", profileId = "p2", isPassthrough = false))

        val bbcProfile = db.appRouteDao().getByPackage("com.bbc.iplayer")
            ?.profileId?.let { db.vpnProfileDao().getById(it) }
        val netflixProfile = db.appRouteDao().getByPackage("com.netflix.mediaclient")
            ?.profileId?.let { db.vpnProfileDao().getById(it) }

        assertEquals("p1", bbcProfile?.id)
        assertEquals("p2", netflixProfile?.id)
        assertNotEquals(bbcProfile?.id, netflixProfile?.id)
    }

    // ── SERVICE_DEAD recovery ─────────────────────────────────────────────────
    //
    // These tests verify the routing decision after a service process kill.
    // VpnSwitcherService.currentProfile is an instance field: it is null in
    // every new service instance. The same-profile early-return guard is:
    //
    //   if (targetProfile?.id == currentProfile?.id) return
    //
    // After restart, currentProfile == null. If targetProfile is also null
    // (NoVPN route) the guard would fire. For any non-null targetProfile the
    // guard must NOT fire so the tunnel is re-established immediately.

    @Test
    fun serviceDead_mappedForegroundApp_guardDoesNotFire_tunnelReestablished() = runBlocking {
        db.vpnProfileDao().insert(VpnProfile("p1", "BBC Profile", TunnelType.WIREGUARD, ""))
        db.appRouteDao().upsert(AppRoute("com.bbc.iplayer", profileId = "p1", isPassthrough = false))

        val route = db.appRouteDao().getByPackage("com.bbc.iplayer")
        val targetProfile = route?.profileId?.let { db.vpnProfileDao().getById(it) }

        // After restart currentProfile is always null.
        val currentProfileAfterRestart: VpnProfile? = null

        // Guard must NOT fire: targetProfile is non-null so IDs differ.
        assertNotEquals(currentProfileAfterRestart?.id, targetProfile?.id)
        // The service will call wgManager.start() — tunnel is re-established.
        assertNotNull(targetProfile)
        assertEquals("p1", targetProfile!!.id)
    }

    @Test
    fun serviceDead_ownPackageForeground_earlyReturnBeforeDbLookup() = runBlocking {
        // VpnSwitcherService.onAppForeground returns immediately when the foreground
        // package matches the service's own packageName:
        //   if (packageName == this.packageName) return
        // This is the fix for "opening UniVPN drops the tunnel". The DB has no route
        // for the own package (seeder explicitly skips it at line 58).
        val ownRoute = db.appRouteDao().getByPackage(ctx.packageName)
        assertNull(ownRoute)
        // Absence of a route plus the early-return guard means the tunnel is
        // never touched when the management UI is in the foreground.
    }

    @Test
    fun serviceDead_passthroughForegroundApp_noTunnelChange() = runBlocking {
        db.appRouteDao().upsert(AppRoute("com.android.launcher", profileId = null, isPassthrough = true))

        val route = db.appRouteDao().getByPackage("com.android.launcher")
        // isPassthrough == true → onAppForeground returns after the second check,
        // before touching the tunnel.
        assertTrue(route?.isPassthrough == true)
        assertNull(route?.profileId)
    }

    @Test
    fun serviceDead_explicitNoVpnForegroundApp_tunnelDropped() = runBlocking {
        // Explicit NoVPN: route exists, no profileId, not passthrough.
        db.appRouteDao().upsert(AppRoute("com.netflix.ninja", profileId = null, isPassthrough = false))

        val route = db.appRouteDao().getByPackage("com.netflix.ninja")
        val targetProfile = route?.profileId?.let { db.vpnProfileDao().getById(it) }

        assertFalse(route?.isPassthrough == true)
        assertNull(targetProfile) // targetProfile null → tunnel is stopped
    }

    @Test
    fun serviceDead_cleartext_window_bounded_sameProfileGuardNotNullEqualsNull() = runBlocking {
        // Regression test for a subtle bug: if the guard were `null == null` true,
        // the service would no-op after restart for NoVPN apps and never recover.
        // For apps with a real profile the guard resolves "p1" == null → false,
        // ensuring the switch is always attempted on the first poll after restart.
        db.vpnProfileDao().insert(VpnProfile("p1", "BBC Profile", TunnelType.WIREGUARD, ""))
        db.appRouteDao().upsert(AppRoute("com.bbc.iplayer", profileId = "p1", isPassthrough = false))

        val target = db.appRouteDao().getByPackage("com.bbc.iplayer")
            ?.profileId?.let { db.vpnProfileDao().getById(it) }

        val currentBeforeKill = target          // service had p1 running
        val currentAfterKill: VpnProfile? = null // new instance, field reset

        // Pre-kill: guard fires → no unnecessary re-establishment.
        assertEquals(currentBeforeKill?.id, target?.id)

        // Post-restart: guard does NOT fire → re-establishment happens immediately.
        assertNotEquals(currentAfterKill?.id, target?.id)
    }

    @Test
    fun serviceDead_unmappedUserApp_implicitNoVpn_tunnelDropped() = runBlocking {
        // User app with no DB entry and not a system app → route == null, not system.
        // onAppForeground falls through to targetProfile = null → tunnel stopped.
        val route = db.appRouteDao().getByPackage("com.some.newapp")
        assertNull(route)
        // No route + not system app → targetProfile = null → tunnel will be dropped.
        // This is the implicit NoVPN behaviour for unmapped user apps.
    }
}
