package com.openlink.child.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TimeRequestDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TimeRequestEntity)

    @Query("SELECT * FROM time_requests ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TimeRequestEntity>>

    /**
     * Approved requests whose `respondedAt` falls on [datePrefix] ("YYYY-MM-DD"), used to sum
     * today's approved extra minutes per docs/API.md rule 1 (effective limit = policy limit +
     * sum of approved grantedMinutes for that date). respondedAt is stored as a full ISO-8601
     * UTC timestamp, so this matches on its date prefix; for a device far from UTC this can be
     * off by up to the device's UTC offset right around local midnight, which is an accepted
     * simplification for this MVP (see android/README.md).
     */
    @Query("SELECT * FROM time_requests WHERE status = 'approved' AND respondedAt LIKE :datePrefix || '%'")
    suspend fun getApprovedGrantedForDate(datePrefix: String): List<TimeRequestEntity>
}
