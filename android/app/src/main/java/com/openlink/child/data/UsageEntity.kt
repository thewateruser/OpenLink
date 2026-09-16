package com.openlink.child.data

import androidx.room.Entity

/**
 * Per-app foreground minutes for one local calendar day, tallied by MonitorForegroundService
 * from UsageStatsManager.
 *
 * This table is the only record of usage that exists anywhere -- it is served directly by
 * `GET /usage` and retained for 30 days. Nothing is uploaded.
 */
@Entity(tableName = "usage", primaryKeys = ["packageName", "date"])
data class UsageEntity(
    val packageName: String,
    /** "YYYY-MM-DD", local date. */
    val date: String,
    val minutesUsed: Int,
    val lastUpdated: Long = System.currentTimeMillis()
)
