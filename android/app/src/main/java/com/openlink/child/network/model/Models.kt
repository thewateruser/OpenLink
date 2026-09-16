package com.openlink.child.network.model

import kotlinx.serialization.Serializable

/**
 * DTOs mirroring docs/API.md exactly (field names and JSON shapes). This Android app only ever
 * acts as a "child device" (device-token auth) -- the parent-auth (JWT) endpoints in the doc
 * (/auth/*, /devices/*, /requests/:id/approve|deny) belong to the iOS app and are intentionally
 * not modeled here.
 */

// ---- Pairing --------------------------------------------------------------------------------

@Serializable
data class PairingClaimRequest(
    val code: String,
    val deviceName: String,
    val platform: String = "android"
)

@Serializable
data class PairingClaimResponse(
    val deviceToken: String,
    val deviceId: String,
    val familyId: String
)

// ---- Policies / schedule ----------------------------------------------------------------------

@Serializable
data class AppPolicyDto(
    val packageName: String,
    val appName: String? = null,
    val dailyLimitMinutes: Int? = null,
    val blocked: Boolean = false
)

@Serializable
data class ScheduleWindowDto(
    /** Bitmask 0-127, bit0 = Sunday. */
    val daysOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val label: String? = null
)

@Serializable
data class DevicePoliciesResponse(
    val policies: List<AppPolicyDto>,
    val schedule: List<ScheduleWindowDto>,
    val isLocked: Boolean
)

/** Payload of the `policy:update` socket event: `{ policies, schedule }`. */
@Serializable
data class PolicyUpdatePayload(
    val policies: List<AppPolicyDto>,
    val schedule: List<ScheduleWindowDto>
)

/** Payload of the `lock:update` socket event: `{ isLocked }`. */
@Serializable
data class LockUpdatePayload(val isLocked: Boolean)

// ---- Installed-app catalog sync ----------------------------------------------------------------

@Serializable
data class InstalledApp(val packageName: String, val appName: String)

@Serializable
data class SyncAppsRequest(val apps: List<InstalledApp>)

// ---- Usage heartbeat --------------------------------------------------------------------------

@Serializable
data class UsageEntryDto(val packageName: String, val minutesUsed: Int)

@Serializable
data class UsageHeartbeatRequest(val date: String, val usage: List<UsageEntryDto>)

// ---- Time requests ----------------------------------------------------------------------------

@Serializable
data class TimeRequestCreate(
    val packageName: String,
    val minutesRequested: Int,
    val message: String? = null
)

@Serializable
data class TimeRequestDto(
    val id: String,
    val packageName: String,
    val minutesRequested: Int,
    val message: String? = null,
    /** "pending" | "approved" | "denied" */
    val status: String,
    val grantedMinutes: Int? = null,
    val respondedAt: String? = null,
    val createdAt: String? = null
)
