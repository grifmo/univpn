package com.univpn.app.data.db

import androidx.room.*
import com.univpn.app.data.model.AppRoute
import kotlinx.coroutines.flow.Flow

@Dao
interface AppRouteDao {
    @Query("SELECT * FROM app_routes")
    fun getAll(): Flow<List<AppRoute>>

    @Query("SELECT * FROM app_routes WHERE packageName = :packageName")
    suspend fun getByPackage(packageName: String): AppRoute?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: AppRoute)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(route: AppRoute)

    @Query("DELETE FROM app_routes WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)
}
