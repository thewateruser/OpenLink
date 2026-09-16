package com.openlink.child.data

import com.openlink.child.network.model.AppPolicyDto
import com.openlink.child.network.model.ScheduleWindowDto
import com.openlink.child.network.model.TimeRequestDto

fun AppPolicyDto.toEntity() = PolicyEntity(
    packageName = packageName,
    appName = appName,
    dailyLimitMinutes = dailyLimitMinutes,
    blocked = blocked
)

fun ScheduleWindowDto.toEntity() = ScheduleEntity(
    daysOfWeek = daysOfWeek,
    startMinute = startMinute,
    endMinute = endMinute
)

fun TimeRequestDto.toEntity() = TimeRequestEntity(
    id = id,
    packageName = packageName,
    minutesRequested = minutesRequested,
    message = message,
    status = status,
    grantedMinutes = grantedMinutes,
    respondedAt = respondedAt,
    createdAt = createdAt
)
