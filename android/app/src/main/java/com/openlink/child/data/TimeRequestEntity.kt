package com.openlink.child.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A "can I have more time" request.
 *
 * The id is now minted on this device (a UUID) rather than assigned by a server, and the row is
 * created the moment the child taps send -- there is nowhere for it to fail to reach. It sits
 * here with `status = "pending"` until a parent connects and approves or denies it, which is
 * exactly the queueing behaviour docs/PROTOCOL.md describes for a disconnected device.
 */
@Entity(tableName = "time_requests")
data class TimeRequestEntity(
    @PrimaryKey val id: String,
    val packageName: String,
    /** Label captured at request time, so the parent sees a name even for an app it can't resolve. */
    val appName: String?,
    val minutesRequested: Int,
    val message: String?,
    /** "pending" | "approved" | "denied" */
    val status: String,
    val grantedMinutes: Int?,
    /** Free-text note from the parent when denying (or approving). */
    val responseNote: String?,
    /** ISO-8601 UTC. */
    val createdAt: String,
    /** ISO-8601 UTC, set when a parent responds. */
    val respondedAt: String?
)
