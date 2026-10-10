package com.univpn.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.model.GeneratedProfileMeta
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opens hand-built version-4 databases through Room, which fails if the migrated schema is wrong (#6). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private lateinit var ctx: Context
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.deleteDatabase(dbName)
    }

    @After
    fun tearDown() { ctx.deleteDatabase(dbName) }

    private fun createV4(extra: (SQLiteDatabase) -> Unit = {}) {
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(dbName), null).use { db ->
            db.execSQL("CREATE TABLE app_routes (packageName TEXT NOT NULL, profileId TEXT, isPassthrough INTEGER NOT NULL, isHidden INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(packageName))")
            db.execSQL("CREATE TABLE vpn_profiles (id TEXT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, configContent TEXT NOT NULL, PRIMARY KEY(id))")
            db.execSQL("INSERT INTO vpn_profiles VALUES ('p1', 'UK', 'WIREGUARD', '[Interface]')")
            db.execSQL("INSERT INTO app_routes VALUES ('com.bbc.iplayer', 'p1', 0, 0)")
            extra(db)
            db.version = 4
        }
    }

    private fun openMigrated() = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
        .addMigrations(*AppDatabase.MIGRATIONS)
        .build()

    private val meta = GeneratedProfileMeta("p1", "a1", "mullvad", "se-got-wg-001|pk", "se-got-wg-001", "Sweden", "Gothenburg", 1L, "pub")

    @Test
    fun migrate4to5_keepsDataAndMatchesSchema() = runBlocking {
        createV4()
        val db = openMigrated()
        try {
            assertEquals("p1", db.appRouteDao().getByPackage("com.bbc.iplayer")?.profileId)
            db.generatedProfileMetaDao().upsert(meta)
            assertEquals("se-got-wg-001", db.generatedProfileMetaDao().getByProfileId("p1")?.serverName)
        } finally { db.close() }
    }

    @Test
    fun migrate4to5_addsServerNameToTableFromEarlierMigration() = runBlocking {
        createV4 { db ->
            // generated_profile_meta as the original 4→5 migration created it, without serverName.
            db.execSQL("CREATE TABLE generated_profile_meta (profileId TEXT NOT NULL PRIMARY KEY, accountId TEXT NOT NULL, providerId TEXT NOT NULL, serverId TEXT NOT NULL, serverCountry TEXT NOT NULL, serverCity TEXT NOT NULL, generatedAt INTEGER NOT NULL, publicKey TEXT NOT NULL DEFAULT '', FOREIGN KEY (profileId) REFERENCES vpn_profiles(id) ON DELETE CASCADE)")
            db.execSQL("INSERT INTO generated_profile_meta VALUES ('p1', 'a1', 'mullvad', 'x|pk', 'Sweden', 'Gothenburg', 1, '')")
        }
        val db = openMigrated()
        try {
            val row = db.generatedProfileMetaDao().getByProfileId("p1")
            assertEquals("", row?.serverName)
            assertEquals("Gothenburg", row?.serverCity)
        } finally { db.close() }
    }
}
