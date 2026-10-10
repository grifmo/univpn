package com.univpn.app.data.db

import androidx.room.*
import com.univpn.app.data.model.GeneratedProfileMeta

@Dao
interface GeneratedProfileMetaDao {
    @Query("SELECT * FROM generated_profile_meta WHERE profileId = :profileId")
    suspend fun getByProfileId(profileId: String): GeneratedProfileMeta?

    @Query("SELECT * FROM generated_profile_meta WHERE providerId = :providerId")
    suspend fun getByProvider(providerId: String): List<GeneratedProfileMeta>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: GeneratedProfileMeta)
}
