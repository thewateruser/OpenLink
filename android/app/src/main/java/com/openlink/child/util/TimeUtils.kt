package com.openlink.child.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Today" in the device's local calendar date -- matches how downtime windows (also local-time
 * based per docs/API.md) and a human parent/child would both think about "today's" usage.
 */
fun todayDateString(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
