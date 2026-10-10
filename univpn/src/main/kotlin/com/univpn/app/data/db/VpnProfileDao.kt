package com.univpn.app.data.db

import androidx.room.*
import com.univpn.app.data.model.VpnProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Profiles are encrypted on the way in and decrypted on the way out (see [ConfigCipher]), so
 * callers only ever see plain configs and the database only ever holds encrypted ones.
 */
@Dao
abstract class VpnProfileDao {
    @Query("SELECT * FROM vpn_profiles ORDER BY name")
    protected abstract fun getAllStored(): Flow<List<VpnProfile>>

    @Query("SELECT * FROM vpn_profiles WHERE id = :id")
    protected abstract suspend fun getByIdStored(id: String): VpnProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertStored(profile: VpnProfile)

    @Update
    protected abstract suspend fun updateStored(profile: VpnProfile): Int

    fun getAll(): Flow<List<VpnProfile>> = getAllStored().map { list -> list.map(::open) }

    suspend fun getById(id: String): VpnProfile? = getByIdStored(id)?.let(::open)

    suspend fun insert(profile: VpnProfile) = insertStored(seal(profile))

    /** Returns the number of rows updated: 0 if the profile no longer exists. */
    suspend fun update(profile: VpnProfile): Int = updateStored(seal(profile))

    @Delete
    abstract suspend fun delete(profile: VpnProfile)

    private fun seal(p: VpnProfile) = p.copy(configContent = ConfigCipher.encrypt(p.configContent))
    private fun open(p: VpnProfile) = p.copy(configContent = ConfigCipher.decrypt(p.configContent))
}
