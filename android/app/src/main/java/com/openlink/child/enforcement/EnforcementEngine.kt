package com.openlink.child.enforcement

import com.openlink.child.data.PolicyEntity
import com.openlink.child.data.ScheduleEntity
import java.util.Calendar

/**
 * The enforcement rules, in one place.
 *
 * These used to be specified in docs/API.md, which went away with the server. docs/PROTOCOL.md
 * deliberately covers only the wire contract between the two apps, so THIS FILE is now the
 * specification of record for how policies combine -- see android/README.md, which flags that as
 * a documentation gap worth closing.
 *
 * The three rules, in precedence order:
 *  1. effective daily limit = policy limit + minutes granted by approved requests today
 *     (a null limit stays null: unlimited),
 *  2. a hard block wins over any limit,
 *  3. a downtime window blocks everything except the always-allowed list and the window's own
 *     exempt packages.
 */
sealed class EnforcementDecision {
    object Allowed : EnforcementDecision()
    data class Blocked(val reason: BlockReason) : EnforcementDecision()
}

enum class BlockReason { HARD_BLOCKED, DOWNTIME, LIMIT_REACHED }

data class AppEnforcementState(
    val packageName: String,
    /** `AppPolicy.dailyLimitMinutes`; null = unlimited. */
    val baseLimitMinutes: Int?,
    /** Sum of `grantedMinutes` from today's approved `TimeRequest`s for this package. */
    val grantedExtraMinutes: Int,
    val minutesUsedToday: Int
) {
    /** Rule 1. Null stays null (unlimited) regardless of any grant. */
    val effectiveLimitMinutes: Int? = baseLimitMinutes?.plus(grantedExtraMinutes)
}

object EnforcementEngine {

    /**
     * The windows in force right now. `ScheduleWindow.daysOfWeek` is a bitmask, bit0 = Sunday,
     * per docs/PROTOCOL.md.
     */
    fun activeWindows(
        schedule: List<ScheduleEntity>,
        now: Calendar = Calendar.getInstance()
    ): List<ScheduleEntity> {
        val dayBit = dayOfWeekBit(now)
        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        return schedule.filter { window ->
            (window.daysOfWeek and dayBit) != 0 &&
                isMinuteWithinWindow(minuteOfDay, window.startMinute, window.endMinute)
        }
    }

    /** Whether any downtime is running, regardless of which apps it lets through. */
    fun isWithinDowntime(schedule: List<ScheduleEntity>, now: Calendar = Calendar.getInstance()): Boolean =
        activeWindows(schedule, now).isNotEmpty()

    /**
     * Whether downtime blocks *this* app right now.
     *
     * An exemption belongs to the window that grants it, so where two windows overlap the app
     * has to be exempt from **every** one of them to get through. The alternative -- any single
     * exemption wins -- would let a permissive window silently punch a hole in a stricter one it
     * happens to overlap, which is the opposite of what someone adding a second, tighter window
     * is asking for.
     */
    fun isBlockedByDowntime(
        packageName: String,
        schedule: List<ScheduleEntity>,
        now: Calendar = Calendar.getInstance()
    ): Boolean {
        val active = activeWindows(schedule, now)
        if (active.isEmpty()) return false
        return active.any { window -> packageName !in window.exemptPackages }
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
     * Evaluates rules 1-3 in the precedence documented above: the always-allowed list is exempt
     * outright, then a hard block (rule 2), then downtime (rule 3), then the per-app minute limit
     * (rule 1).
     */
    fun evaluate(
        packageName: String,
        schedule: List<ScheduleEntity>,
        policy: PolicyEntity?,
        state: AppEnforcementState,
        alwaysAllowed: Boolean,
        now: Calendar = Calendar.getInstance()
    ): EnforcementDecision {
        if (alwaysAllowed) return EnforcementDecision.Allowed
        if (policy?.blocked == true) return EnforcementDecision.Blocked(BlockReason.HARD_BLOCKED)
        // A hard block still wins over an exemption: being let through bedtime is not the same
        // as being unblocked, and the parent set that block deliberately.
        if (isBlockedByDowntime(packageName, schedule, now)) {
            return EnforcementDecision.Blocked(BlockReason.DOWNTIME)
        }
        val limit = state.effectiveLimitMinutes
        if (limit != null && state.minutesUsedToday >= limit) {
            return EnforcementDecision.Blocked(BlockReason.LIMIT_REACHED)
        }
        return EnforcementDecision.Allowed
    }
}
