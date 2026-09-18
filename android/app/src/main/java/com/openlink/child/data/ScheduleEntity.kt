package com.openlink.child.data

import androidx.room.ColumnInfo
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
    val label: String? = null,
    /**
     * Packages this window does NOT block -- the phone, messages, an alarm clock.
     *
     * Scoped to the window rather than the device, so "all night, except the phone" and "homework
     * hours, except the calculator" can coexist without either leaking into the other.
     *
     * The SQL default matters: it is what the v2 -> v3 migration backfills existing rows with,
     * and Room validates the declared default against the one in the database, so the two must
     * agree exactly.
     */
    @ColumnInfo(defaultValue = "")
    val exemptPackages: List<String> = emptyList()
)
