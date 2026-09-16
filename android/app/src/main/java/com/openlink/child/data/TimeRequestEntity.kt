package com.openlink.child.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local cache of a `TimeRequest`, keyed by the server-assigned id. Used both to show
 * pending/approved/denied status in the UI, and to compute today's approved extra minutes for
 * enforcement (see EnforcementRepository.primeFromDatabase).
 */
@Entity(tableName = "time_requests")
data class TimeRequestEntity(
    @PrimaryKey val id: String,
    val packageName: String,
    val minutesRequested: Int,
    val message: String?,
    /** "pending" | "approved" | "denied" */
    val status: String,
    val grantedMinutes: Int?,
    /** ISO-8601 UTC, set by the server once approved/denied. */
    val respondedAt: String?,
    val createdAt: String?
)
