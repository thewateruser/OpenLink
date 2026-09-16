package com.openlink.child.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PolicyDao {

    @Query("SELECT * FROM policies")
    suspend fun getAllOnce(): List<PolicyEntity>

    @Query("SELECT * FROM policies")
    fun observeAll(): Flow<List<PolicyEntity>>

    /** `policy:update` / GET /device/policies are full resyncs, so we replace wholesale rather
     *  than diff -- simplest way to apply them idempotently, per docs/API.md's own suggestion. */
    @Transaction
    suspend fun replaceAll(policies: List<PolicyEntity>) {
        clear()
        insertAll(policies)
    }

    @Query("DELETE FROM policies")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(policies: List<PolicyEntity>)
}
