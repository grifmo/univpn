package com.univpn.app.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.univpn.app.data.db.AppDatabase
import com.univpn.app.data.db.ConfigCipher
import com.univpn.app.data.model.TunnelType
import com.univpn.app.data.model.VpnProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** WireGuard configs (with private keys) are encrypted at rest (#13). */
@RunWith(AndroidJUnit4::class)
class ConfigEncryptionTest {

    private lateinit var db: AppDatabase
    private val config = "[Interface]\nPrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=\n\n[Peer]\nEndpoint = 192.0.2.1:51820\n"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() { db.close() }

    private fun storedConfig(id: String): String =
        db.openHelper.readableDatabase.query("SELECT configContent FROM vpn_profiles WHERE id = ?", arrayOf(id)).use {
            it.moveToFirst(); it.getString(0)
        }

    @Test
    fun cipher_roundTrips_andUsesFreshIvs() {
        val a = ConfigCipher.encrypt(config)
        val b = ConfigCipher.encrypt(config)
        assertTrue(ConfigCipher.isEncrypted(a))
        assertNotEquals(a, b)
        assertEquals(config, ConfigCipher.decrypt(a))
    }

    @Test
    fun dao_storesCiphertext_andReturnsPlainConfig() = runBlocking {
        db.vpnProfileDao().insert(VpnProfile("p1", "UK", TunnelType.WIREGUARD, config))

        val stored = storedConfig("p1")
        assertTrue(ConfigCipher.isEncrypted(stored))
        assertFalse(stored.contains("PrivateKey"))
        assertEquals(config, db.vpnProfileDao().getById("p1")?.configContent)
        assertEquals(config, db.vpnProfileDao().getAll().first().single().configContent)

        db.vpnProfileDao().update(VpnProfile("p1", "UK", TunnelType.WIREGUARD, config + "# edited\n"))
        assertTrue(ConfigCipher.isEncrypted(storedConfig("p1")))
        assertEquals(config + "# edited\n", db.vpnProfileDao().getById("p1")?.configContent)
    }

    @Test
    fun legacyPlaintextRow_isStillReadable() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO vpn_profiles (id, name, type, configContent) VALUES (?, ?, ?, ?)",
            arrayOf("old", "Old", "WIREGUARD", config)
        )
        assertEquals(config, db.vpnProfileDao().getById("old")?.configContent)
    }

    @Test
    fun legacyEncryption_leavesNoPlaintextKeyInTheFile() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "legacy-encryption-test.db"
        ctx.deleteDatabase(name)
        val file = ctx.getDatabasePath(name)
        try {
            // A pre-0.9.2 database in WAL mode with plaintext configs, some rows rewritten.
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { raw ->
                raw.enableWriteAheadLogging()
                raw.execSQL("CREATE TABLE vpn_profiles (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, type TEXT NOT NULL, configContent TEXT NOT NULL)")
                for (i in 1..20) raw.execSQL("INSERT INTO vpn_profiles VALUES ('p$i', 'P$i', 'WIREGUARD', ?)", arrayOf(config))
                raw.execSQL("UPDATE vpn_profiles SET configContent = configContent || '# edited\n'")
            }

            assertEquals(20, com.univpn.app.data.db.LegacyConfigEncryption.run(file))
            assertEquals(0, com.univpn.app.data.db.LegacyConfigEncryption.run(file))

            val bytes = listOf("", "-wal", "-journal").map { java.io.File(file.path + it) }
                .filter { it.exists() }.flatMap { it.readBytes().toList() }.toByteArray()
            assertFalse("plaintext key left on disk", String(bytes, Charsets.ISO_8859_1).contains("PrivateKey"))

            android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, 0).use { raw ->
                raw.rawQuery("SELECT configContent FROM vpn_profiles WHERE id = 'p7'", null).use {
                    it.moveToFirst()
                    assertEquals(config + "# edited\n", ConfigCipher.decrypt(it.getString(0)))
                }
            }
        } finally {
            ctx.deleteDatabase(name)
        }
    }

    @Test
    fun corruptCiphertext_decryptsToEmpty() {
        assertEquals("", ConfigCipher.decrypt("enc:v1:AAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
    }
}
