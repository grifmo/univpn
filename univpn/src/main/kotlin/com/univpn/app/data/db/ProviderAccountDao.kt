package com.univpn.app.data.db

import androidx.room.*
import com.univpn.app.data.model.ProviderAccount
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderAccountDao {
    @Query("SELECT * FROM provider_accounts ORDER BY providerId")
    fun getAll(): Flow<List<ProviderAccount>>

    @Query("SELECT * FROM provider_accounts WHERE providerId = :providerId")
    suspend fun getByProvider(providerId: String): List<ProviderAccount>

    @Query("SELECT * FROM provider_accounts WHERE accountId = :accountId")
    suspend fun getById(accountId: String): ProviderAccount?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: ProviderAccount)

    @Query("DELETE FROM provider_accounts WHERE accountId = :accountId")
    suspend fun deleteById(accountId: String)
}
