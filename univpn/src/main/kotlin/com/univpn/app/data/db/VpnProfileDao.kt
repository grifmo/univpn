package com.univpn.app.data.db

import androidx.room.*
import com.univpn.app.data.model.VpnProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface VpnProfileDao {
    @Query("SELECT * FROM vpn_profiles ORDER BY name")
    fun getAll(): Flow<List<VpnProfile>>

    @Query("SELECT * FROM vpn_profiles WHERE id = :id")
    suspend fun getById(id: String): VpnProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: VpnProfile)

    /** Returns the number of rows updated: 0 if the profile no longer exists. */
    @Update
    suspend fun update(profile: VpnProfile): Int

    @Delete
    suspend fun delete(profile: VpnProfile)
}
