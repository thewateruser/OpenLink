package com.openlink.child.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One downtime window. `PUT /schedule` replaces the whole set, which is why the id is
 * auto-generated and not expected to be stable across edits.
 */
@Entity(tableName = "schedule_windows")
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Bitmask 0-127, bit0 = Sunday, per docs/PROTOCOL.md. */
    val daysOfWeek: Int,
    /** Minute-of-day in the device's local time. */
    val startMinute: Int,
    val endMinute: Int,
    val label: String? = null
)
