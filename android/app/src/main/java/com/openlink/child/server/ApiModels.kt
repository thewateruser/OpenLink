package com.openlink.child.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The wire types of docs/PROTOCOL.md, in the direction this app serves them.
 *
 * There is no "client" counterpart: this device answers requests, it never makes them.
 */

// ---- pairing ---------------------------------------------------------------------------------

@Serializable
data class PairRequest(
    val parentId: String,
    val parentName: String? = null,
    val proof: String
)

@Serializable
data class PairResponse(
    val deviceId: String,
    val deviceName: String,
    val parentToken: String,
    val endpoints: List<String>
)

/** `DELETE /pair` -- omit `parentId` to unpair the caller, pass one to revoke another parent. */
@Serializable
data class UnpairRequest(val parentId: String? = null)

// ---- device ----------------------------------------------------------------------------------

@Serializable
data class DeviceResponse(
    val deviceId: String,
    val deviceName: String,
    val isLocked: Boolean,
    val platform: String,
    val appVersion: String,
    /** Drives endpoint learning on the parent -- see docs/PROTOCOL.md. */
    val endpoints: List<String>,
    /** 0-100, or null when the battery level can't be read. */
    val batteryLevel: Int?,
    val lastBootAt: String
)

@Serializable
data class LockRequest(val locked: Boolean)

@Serializable
data class LockResponse(val isLocked: Boolean)

// ---- apps and policies -------------------------------------------------------------------------

@Serializable
data class PolicyDto(
    val packageName: String,
    /** null = unlimited. */
    val dailyLimitMinutes: Int? = null,
    val blocked: Boolean = false
)

@Serializable
data class AppDto(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    /**
     * Always present. An app with no stored row is reported as an explicit
     * `{ dailyLimitMinutes: null, blocked: false }` rather than a JSON null, so the parent never
     * has to special-case "unset" against "unlimited and unblocked" -- they mean the same thing
     * to the enforcement engine.
     */
    val policy: PolicyDto,
    val todayMinutes: Int
)

@Serializable
data class ScheduleWindowDto(
    val id: Long? = null,
    /** Bitmask, bit0 = Sunday. */
    val daysOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
    val label: String? = null
)

@Serializable
data class ScheduleDto(val windows: List<ScheduleWindowDto>)

/**
 * A `PUT /policies/{packageName}` patch after it has been teased apart from the raw JSON body.
 *
 * `dailyLimitMinutes` has three states on the wire -- absent (leave alone), explicit null (clear
 * the limit), and a number (set it) -- which a nullable Kotlin field cannot represent. The route
 * decodes the body as a JsonObject and builds this instead; see Routes.kt.
 */
data class PolicyPatch(
    val limitProvided: Boolean,
    val dailyLimitMinutes: Int?,
    val blocked: Boolean?
)

// ---- usage ---------------------------------------------------------------------------------------

@Serializable
data class UsageEntryDto(val packageName: String, val minutesUsed: Int)

@Serializable
data class UsageResponse(val date: String, val usage: List<UsageEntryDto>)

// ---- time requests --------------------------------------------------------------------------------

@Serializable
data class TimeRequestDto(
    val id: String,
    val packageName: String,
    val appName: String? = null,
    val minutesRequested: Int,
    val message: String? = null,
    /** "pending" | "approved" | "denied" */
    val status: String,
    val grantedMinutes: Int? = null,
    val responseNote: String? = null,
    val createdAt: String,
    val respondedAt: String? = null
)

@Serializable
data class ApproveRequest(val grantedMinutes: Int)

@Serializable
data class DenyRequest(val reason: String? = null)

// ---- websocket ---------------------------------------------------------------------------------

/** Every `/events` frame is `{ type, payload }`. */
@Serializable
data class EventEnvelope(val type: String, val payload: JsonElement)

@Serializable
data class UsageUpdatePayload(
    val packageName: String,
    val minutesUsed: Int,
    val date: String
)

@Serializable
data class LockUpdatePayload(val isLocked: Boolean)

@Serializable
data class PolicyUpdatePayload(
    val policies: List<PolicyDto>,
    val schedule: List<ScheduleWindowDto>
)

@Serializable
data class DeviceStatePayload(
    val batteryLevel: Int?,
    val endpoints: List<String>
)

// ---- errors ---------------------------------------------------------------------------------------

/**
 * Deliberately terse: an error body never names which check failed, so a caller cannot use the
 * response text to distinguish "unknown token" from "revoked token" from "malformed header".
 */
@Serializable
data class ErrorResponse(val error: String)
