package com.openlink.child.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local cache of an `AppPolicy`, resynced from GET /device/policies and `policy:update`. */
@Entity(tableName = "policies")
data class PolicyEntity(
    @PrimaryKey val packageName: String,
    val appName: String?,
    /** null = unlimited. */
    val dailyLimitMinutes: Int?,
    val blocked: Boolean
)
