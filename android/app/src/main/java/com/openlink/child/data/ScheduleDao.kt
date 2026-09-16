package com.openlink.child.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {

    @Query("SELECT * FROM schedule_windows ORDER BY startMinute")
    suspend fun getAllOnce(): List<ScheduleEntity>

    @Query("SELECT * FROM schedule_windows ORDER BY startMinute")
    fun observeAll(): Flow<List<ScheduleEntity>>

    /** `PUT /schedule` replaces the whole set, so this stays a wholesale swap. */
    @Transaction
    suspend fun replaceAll(windows: List<ScheduleEntity>) {
        clear()
        insertAll(windows)
    }

    @Query("DELETE FROM schedule_windows")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(windows: List<ScheduleEntity>)
}
