package com.univpn.app.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.GeneratedProfileMeta
import com.univpn.app.data.model.TunnelType
import com.univpn.app.data.model.VpnProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The write pattern FreshnessChecker uses for a refreshed config (#11). */
@RunWith(AndroidJUnit4::class)
class ProfileRefreshDaoTest {

    private lateinit var db: AppDatabase

    private val profile = VpnProfile("p1", "Mullvad — Tirana", TunnelType.WIREGUARD, "old config")
    private val meta = GeneratedProfileMeta(
        profileId = "p1", accountId = "a1", providerId = "mullvad", serverId = "al-tia-wg-001|pk",
        serverName = "al-tia-wg-001", serverCountry = "Albania", serverCity = "Tirana",
        generatedAt = 1L, publicKey = "pub"
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() { db.close() }

    private suspend fun refresh(newConfig: String): Boolean = db.withTransaction {
        if (db.vpnProfileDao().update(profile.copy(configContent = newConfig)) == 0) false
        else {
            db.generatedProfileMetaDao().upsert(meta.copy(generatedAt = 2L))
            true
        }
    }

    @Test
    fun refresh_updatesExistingProfileAndMeta() = runBlocking {
        db.vpnProfileDao().insert(profile)
        db.generatedProfileMetaDao().upsert(meta)

        assertEquals(true, refresh("new config"))
        assertEquals("new config", db.vpnProfileDao().getById("p1")?.configContent)
        assertEquals(2L, db.generatedProfileMetaDao().getByProfileId("p1")?.generatedAt)
    }

    @Test
    fun refresh_doesNotRecreateDeletedProfile() = runBlocking {
        db.vpnProfileDao().insert(profile)
        db.generatedProfileMetaDao().upsert(meta)
        db.vpnProfileDao().delete(profile)   // user deletes it while the refresh request is in flight

        assertEquals(false, refresh("new config"))
        assertNull(db.vpnProfileDao().getById("p1"))
        assertNull(db.generatedProfileMetaDao().getByProfileId("p1"))
    }

    @Test
    fun update_keepsMetadataRow() = runBlocking {
        // INSERT OR REPLACE deletes the parent row first, which cascades to the metadata.
        // UPDATE must leave it in place.
        db.vpnProfileDao().insert(profile)
        db.generatedProfileMetaDao().upsert(meta)
        db.vpnProfileDao().update(profile.copy(configContent = "edited"))
        assertNotNull(db.generatedProfileMetaDao().getByProfileId("p1"))
    }
}
