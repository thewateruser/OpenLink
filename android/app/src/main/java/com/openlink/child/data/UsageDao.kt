package com.openlink.child.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UsageEntity)

    @Query("SELECT * FROM usage WHERE date = :date")
    suspend fun getForDateOnce(date: String): List<UsageEntity>

    @Query("SELECT * FROM usage WHERE date = :date")
    fun observeForDate(date: String): Flow<List<UsageEntity>>
}
