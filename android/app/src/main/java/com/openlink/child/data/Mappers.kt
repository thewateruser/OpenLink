package com.openlink.child.data

import com.openlink.child.server.PolicyDto
import com.openlink.child.server.ScheduleWindowDto
import com.openlink.child.server.TimeRequestDto
import com.openlink.child.server.UsageEntryDto

/**
 * Room row -> wire type. The mappings only run in this direction now: the child device owns the
 * data, so nothing arrives as a DTO to be stored wholesale. Incoming writes are narrow patches
 * applied in ChildRepository, not entity replacements.
 */

fun PolicyEntity.toDto() = PolicyDto(
    packageName = packageName,
    dailyLimitMinutes = dailyLimitMinutes,
    blocked = blocked
)

/** The default policy for a package with no stored row: unlimited and unblocked. */
fun defaultPolicyDto(packageName: String) = PolicyDto(
    packageName = packageName,
    dailyLimitMinutes = null,
    blocked = false
)

fun ScheduleEntity.toDto() = ScheduleWindowDto(
    id = id,
    daysOfWeek = daysOfWeek,
    startMinute = startMinute,
    endMinute = endMinute,
    label = label
)

/** Ids are assigned by Room on insert, so an incoming window's id is deliberately discarded. */
fun ScheduleWindowDto.toEntity() = ScheduleEntity(
    id = 0,
    daysOfWeek = daysOfWeek,
    startMinute = startMinute,
    endMinute = endMinute,
    label = label?.take(64)
)

fun TimeRequestEntity.toDto() = TimeRequestDto(
    id = id,
    packageName = packageName,
    appName = appName,
    minutesRequested = minutesRequested,
    message = message,
    status = status,
    grantedMinutes = grantedMinutes,
    responseNote = responseNote,
    createdAt = createdAt,
    respondedAt = respondedAt
)

fun UsageEntity.toDto() = UsageEntryDto(
    packageName = packageName,
    minutesUsed = minutesUsed
)
