package com.univpn.app.data.db

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.univpn.app.data.model.AppRoute
import com.univpn.app.data.model.GeneratedProfileMeta
import com.univpn.app.data.model.ProviderAccount
import com.univpn.app.data.model.VpnProfile

@Database(
    entities = [AppRoute::class, VpnProfile::class, ProviderAccount::class, GeneratedProfileMeta::class],
    version = 5,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun appRouteDao(): AppRouteDao
    abstract fun vpnProfileDao(): VpnProfileDao
    abstract fun providerAccountDao(): ProviderAccountDao
    abstract fun generatedProfileMetaDao(): GeneratedProfileMetaDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_routes ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS provider_accounts (
                        accountId TEXT NOT NULL PRIMARY KEY,
                        providerId TEXT NOT NULL,
                        usernameHint TEXT NOT NULL,
                        expiryEpochMs INTEGER
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS generated_profile_meta (
                        profileId TEXT NOT NULL PRIMARY KEY,
                        accountId TEXT NOT NULL,
                        providerId TEXT NOT NULL,
                        serverId TEXT NOT NULL,
                        serverName TEXT NOT NULL DEFAULT '',
                        serverCountry TEXT NOT NULL,
                        serverCity TEXT NOT NULL,
                        generatedAt INTEGER NOT NULL,
                        publicKey TEXT NOT NULL DEFAULT '',
                        FOREIGN KEY (profileId) REFERENCES vpn_profiles(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                // A table created by an earlier build of this migration lacks serverName.
                val hasServerName = db.query("PRAGMA table_info(generated_profile_meta)").use { c ->
                    val nameCol = c.getColumnIndex("name")
                    generateSequence { if (c.moveToNext()) c.getString(nameCol) else null }.any { it == "serverName" }
                }
                if (!hasServerName) {
                    db.execSQL("ALTER TABLE generated_profile_meta ADD COLUMN serverName TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        internal val MIGRATIONS = arrayOf(MIGRATION_3_4, MIGRATION_4_5)

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: run {
                val app = context.applicationContext
                runCatching { LegacyConfigEncryption.run(app.getDatabasePath(NAME)) }
                    .onFailure { Log.e("AppDatabase", "Encrypting stored configs failed", it) }
                Room.databaseBuilder(app, AppDatabase::class.java, NAME)
                    .addMigrations(*MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
        }

        private const val NAME = "univpn.db"
    }
}
