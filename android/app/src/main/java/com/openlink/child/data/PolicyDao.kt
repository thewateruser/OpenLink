package com.openlink.child.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PolicyDao {

    @Query("SELECT * FROM policies")
    suspend fun getAllOnce(): List<PolicyEntity>

    @Query("SELECT * FROM policies")
    fun observeAll(): Flow<List<PolicyEntity>>

    @Query("SELECT * FROM policies WHERE packageName = :packageName")
    suspend fun getByPackage(packageName: String): PolicyEntity?

    /**
     * Policies are now edited one package at a time (`PUT /policies/{packageName}` is a patch,
     * not a resync), so there is no wholesale replace any more -- the old `replaceAll` existed
     * only to apply a server's full snapshot.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policy: PolicyEntity)

    @Query("DELETE FROM policies WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
