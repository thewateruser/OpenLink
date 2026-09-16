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

    @Query("SELECT * FROM time_requests WHERE id = :id")
    suspend fun getById(id: String): TimeRequestEntity?

    @Query("SELECT * FROM time_requests ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TimeRequestEntity>>

    /** `GET /requests` -- most recent first, capped at 100 per docs/PROTOCOL.md. */
    @Query("SELECT * FROM time_requests ORDER BY createdAt DESC LIMIT 100")
    suspend fun recent(): List<TimeRequestEntity>

    @Query("SELECT * FROM time_requests WHERE status = :status ORDER BY createdAt DESC LIMIT 100")
    suspend fun recentByStatus(status: String): List<TimeRequestEntity>

    /**
     * Approved requests whose `respondedAt` falls on [datePrefix] ("YYYY-MM-DD"), used to sum
     * today's approved extra minutes (effective limit = policy limit + sum of approved
     * grantedMinutes for that date). respondedAt is stored as a full ISO-8601 UTC timestamp, so
     * this matches on its date prefix; for a device far from UTC this can be off by up to the
     * device's UTC offset right around local midnight, which is an accepted simplification (see
     * android/README.md).
     */
    @Query("SELECT * FROM time_requests WHERE status = 'approved' AND respondedAt LIKE :datePrefix || '%'")
    suspend fun getApprovedGrantedForDate(datePrefix: String): List<TimeRequestEntity>

    /** Keeps the table from growing without bound on a long-lived install. */
    @Query("DELETE FROM time_requests WHERE createdAt < :isoCutoff AND status != 'pending'")
    suspend fun deleteResolvedOlderThan(isoCutoff: String)
}
