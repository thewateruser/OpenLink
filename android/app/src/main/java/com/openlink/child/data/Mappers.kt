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
    label = label,
    exemptPackages = exemptPackages
)

/** Ids are assigned by Room on insert, so an incoming window's id is deliberately discarded. */
fun ScheduleWindowDto.toEntity() = ScheduleEntity(
    id = 0,
    daysOfWeek = daysOfWeek,
    startMinute = startMinute,
    endMinute = endMinute,
    label = label?.take(64),
    exemptPackages = sanitisePackageNames(exemptPackages)
)

/**
 * The parent is authenticated but is still a remote peer writing to this device's database, so
 * what it sends is bounded before it is stored.
 *
 * The comma filter is not paranoia about a malicious parent -- it protects the storage format.
 * PackageListConverter joins on commas, so one smuggled into a name would silently split into
 * two bogus entries on the way back out. A name that cannot be a package name is dropped rather
 * than mangled.
 */
private fun sanitisePackageNames(packages: List<String>): List<String> = packages
    .asSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && it.length <= MAX_PACKAGE_NAME_LENGTH && !it.contains(',') }
    .distinct()
    .take(MAX_EXEMPT_PACKAGES)
    .toList()

/** Android's own limit on a package name. */
private const val MAX_PACKAGE_NAME_LENGTH = 255

/** Far more than any real allow-list, and small enough that the column stays sane. */
private const val MAX_EXEMPT_PACKAGES = 64

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
