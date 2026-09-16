package com.openlink.child.util

import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeParseException

/**
 * "Today" in the device's local calendar date -- matches how downtime windows (also local-time
 * based, per docs/PROTOCOL.md) and a human parent/child would both think about "today's" usage.
 */
fun todayDateString(): String = LocalDate.now().toString()

/** "YYYY-MM-DD" for a day [days] before today, used for the 30-day usage retention cutoff. */
fun dateStringDaysAgo(days: Long): String = LocalDate.now().minusDays(days).toString()

/** ISO-8601 UTC, second precision -- the timestamp format every API field in PROTOCOL.md uses. */
fun nowIso(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

fun isoFromEpochMillis(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS).toString()

/**
 * Validates a `?date=` query parameter before it reaches a LIKE/= comparison. Attacker-supplied
 * and therefore parsed strictly rather than trusted.
 */
fun isValidDateString(value: String): Boolean = try {
    LocalDate.parse(value)
    true
} catch (e: DateTimeParseException) {
    false
}
