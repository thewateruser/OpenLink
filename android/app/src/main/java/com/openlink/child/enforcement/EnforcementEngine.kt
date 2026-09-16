package com.openlink.child.enforcement

import com.openlink.child.data.PolicyEntity
import com.openlink.child.data.ScheduleEntity
import java.util.Calendar

/** Client-side implementation of the "Enforcement semantics" section of docs/API.md. */
sealed class EnforcementDecision {
    object Allowed : EnforcementDecision()
    data class Blocked(val reason: BlockReason) : EnforcementDecision()
}

enum class BlockReason { LOCKED, HARD_BLOCKED, DOWNTIME, LIMIT_REACHED }

data class AppEnforcementState(
    val packageName: String,
    /** `AppPolicy.dailyLimitMinutes`; null = unlimited. */
    val baseLimitMinutes: Int?,
    /** Sum of `grantedMinutes` from today's approved `TimeRequest`s for this package. */
    val grantedExtraMinutes: Int,
    val minutesUsedToday: Int
) {
    /** docs/API.md rule 1. Null stays null (unlimited) regardless of any grant. */
    val effectiveLimitMinutes: Int? = baseLimitMinutes?.plus(grantedExtraMinutes)
}

object EnforcementEngine {

    /** docs/API.md: `ScheduleWindow.daysOfWeek` bitmask, bit0 = Sunday. */
    fun isWithinDowntime(schedule: List<ScheduleEntity>, now: Calendar = Calendar.getInstance()): Boolean {
        val dayBit = dayOfWeekBit(now)
        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return schedule.any { window ->
            (window.daysOfWeek and dayBit) != 0 &&
                isMinuteWithinWindow(minuteOfDay, window.startMinute, window.endMinute)
        }
    }

    private fun isMinuteWithinWindow(minute: Int, start: Int, end: Int): Boolean =
        if (start <= end) minute in start until end
        else minute >= start || minute < end // window wraps past local midnight

    private fun dayOfWeekBit(now: Calendar): Int {
        // Calendar.DAY_OF_WEEK: SUNDAY=1 .. SATURDAY=7 -> bit0=Sunday means we shift down by 1.
        val bitIndex = now.get(Calendar.DAY_OF_WEEK) - 1
        return 1 shl bitIndex
    }

    /**
     * Evaluates docs/API.md rules 1-4 in the documented precedence: an active remote lock wins
     * outright (rule 4), then the always-allowed list is exempt from everything *except* that
     * lock, then a hard block (rule 2), then downtime (rule 3), then the per-app minute limit
     * (rule 1).
     */
    fun evaluate(
        packageName: String,
        isLocked: Boolean,
        schedule: List<ScheduleEntity>,
        policy: PolicyEntity?,
        state: AppEnforcementState,
        alwaysAllowed: Boolean
    ): EnforcementDecision {
        if (isLocked) return EnforcementDecision.Blocked(BlockReason.LOCKED)
        if (alwaysAllowed) return EnforcementDecision.Allowed
        if (policy?.blocked == true) return EnforcementDecision.Blocked(BlockReason.HARD_BLOCKED)
        if (isWithinDowntime(schedule)) return EnforcementDecision.Blocked(BlockReason.DOWNTIME)
        val limit = state.effectiveLimitMinutes
        if (limit != null && state.minutesUsedToday >= limit) {
            return EnforcementDecision.Blocked(BlockReason.LIMIT_REACHED)
        }
        return EnforcementDecision.Allowed
    }
}
