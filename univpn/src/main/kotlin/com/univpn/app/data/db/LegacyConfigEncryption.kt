package com.univpn.app.data.db

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Encrypts configs that versions before 0.9.2 stored in plain text. Runs on the database file
 * before Room opens it: with no other connections, the old rows can be overwritten
 * (secure_delete), and the file rewritten (VACUUM), so no plaintext private key is left in
 * freed pages or the WAL.
 */
object LegacyConfigEncryption {
    private const val TAG = "LegacyConfigEncryption"

    /** Returns the number of configs encrypted. */
    fun run(dbFile: File): Int {
        if (!dbFile.exists()) return 0
        // Opening without WAL checkpoints any existing WAL into the main file and removes it.
        return SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            val hasTable = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'vpn_profiles'", null
            ).use { it.moveToFirst() }
            if (!hasTable) return@use 0

            val legacy = db.rawQuery(
                "SELECT id, configContent FROM vpn_profiles WHERE configContent NOT LIKE 'enc:v1:%'", null
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) } }
            if (legacy.isEmpty()) return@use 0

            db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
            db.beginTransaction()
            try {
                for ((id, config) in legacy) {
                    db.execSQL(
                        "UPDATE vpn_profiles SET configContent = ? WHERE id = ?",
                        arrayOf(ConfigCipher.encrypt(config), id)
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.execSQL("VACUUM")
            Log.i(TAG, "Encrypted ${legacy.size} stored configs")
            legacy.size
        }
    }
}
