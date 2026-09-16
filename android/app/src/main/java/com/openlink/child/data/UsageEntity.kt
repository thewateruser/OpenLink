package com.openlink.child.data

import androidx.room.Entity

/**
 * Today's (or any past day's) per-app foreground minutes, as tallied locally by
 * MonitorForegroundService from UsageStatsManager. Per docs/API.md: "the device is the source
 * of truth for today's usage" -- this table is that source of truth, and it's what gets pushed
 * via POST /device/usage.
 */
@Entity(tableName = "usage", primaryKeys = ["packageName", "date"])
data class UsageEntity(
    val packageName: String,
    /** "YYYY-MM-DD", local date. */
    val date: String,
    val minutesUsed: Int,
    val lastUpdated: Long = System.currentTimeMillis()
)
