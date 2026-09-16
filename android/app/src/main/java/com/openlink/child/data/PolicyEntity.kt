package com.openlink.child.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The per-app policy for one package. Written by `PUT /policies/{packageName}` and read by the
 * enforcement engine.
 *
 * This is no longer a cache of anything: with no server, this row *is* the policy. The app name
 * that used to be stored alongside it is gone -- PackageManager knows the current label, and a
 * stored copy could only ever drift.
 */
@Entity(tableName = "policies")
data class PolicyEntity(
    @PrimaryKey val packageName: String,
    /** null = unlimited. */
    val dailyLimitMinutes: Int?,
    val blocked: Boolean
)
