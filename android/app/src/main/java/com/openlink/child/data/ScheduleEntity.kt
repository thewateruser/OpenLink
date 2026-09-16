package com.openlink.child.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local cache of a `ScheduleWindow` (downtime window), resynced the same way as [PolicyEntity]. */
@Entity(tableName = "schedule_windows")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Bitmask 0-127, bit0 = Sunday, per docs/API.md. */
    val daysOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int
)
