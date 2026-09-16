package com.openlink.child.enforcement

import android.content.Context
import com.openlink.child.data.AppDatabase
import com.openlink.child.data.PolicyEntity
import com.openlink.child.data.ScheduleEntity
import com.openlink.child.util.todayDateString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single in-process source of truth for enforcement decisions.
 *
 * Room is the durable store (survives process death); this singleton caches the latest snapshot
 * in memory so [PolicyForegroundAccessibilityService] can decide synchronously on every
 * foreground-app change (an accessibility event handler that awaited a suspend DB query would
 * add visible lag before the block screen appears) without every caller re-querying Room. Room
 * remains the source of truth: [primeFromDatabase] reloads this cache from it on cold start /
 * process restart, and every writer here also persists to Room first.
 */
object EnforcementRepository {

    @Volatile private var isLocked: Boolean = false
    @Volatile private var policies: Map<String, PolicyEntity> = emptyMap()
    @Volatile private var schedule: List<ScheduleEntity> = emptyList()
    @Volatile private var usageToday: Map<String, Int> = emptyMap()
    @Volatile private var grantedToday: Map<String, Int> = emptyMap()

    private val _lockState = MutableStateFlow(false)
    /** Observed by the Compose UI (e.g. to show a "locked by parent" banner on the home screen). */
    val lockState: StateFlow<Boolean> = _lockState

    fun updateLocked(locked: Boolean) {
        isLocked = locked
        _lockState.value = locked
    }

    fun updatePolicies(newPolicies: List<PolicyEntity>) {
        policies = newPolicies.associateBy { it.packageName }
    }

    fun updateSchedule(newSchedule: List<ScheduleEntity>) {
        schedule = newSchedule
    }

    fun updateUsageToday(usage: Map<String, Int>) {
        usageToday = usage
    }

    fun updateGrantedToday(granted: Map<String, Int>) {
        grantedToday = granted
    }

    /** Applies an approved time-request grant immediately, without waiting for a full Room
     *  reload. Since the approval is handled in this same process (`POST /requests/{id}/approve`
     *  lands here directly), the blocked app becomes usable the instant the parent taps approve
     *  -- there is no round trip left to wait for. */
    fun bumpGrantedToday(packageName: String, extraMinutes: Int) {
        val updated = grantedToday.toMutableMap()
        updated[packageName] = (updated[packageName] ?: 0) + extraMinutes
        grantedToday = updated
    }

    fun decisionFor(context: Context, packageName: String): EnforcementDecision {
        val alwaysAllowed = AlwaysAllowed.isAlwaysAllowed(context, packageName)
        val policy = policies[packageName]
        val state = AppEnforcementState(
            packageName = packageName,
            baseLimitMinutes = policy?.dailyLimitMinutes,
            grantedExtraMinutes = grantedToday[packageName] ?: 0,
            minutesUsedToday = usageToday[packageName] ?: 0
        )
        return EnforcementEngine.evaluate(
            packageName = packageName,
            isLocked = isLocked,
            schedule = schedule,
            policy = policy,
            state = state,
            alwaysAllowed = alwaysAllowed
        )
    }

    /** Loads the last-persisted snapshot from Room; call once at process start (cold start,
     *  reboot, or after the OS kills the process under memory pressure) before relying on
     *  [decisionFor]. */
    suspend fun primeFromDatabase(context: Context) {
        val db = AppDatabase.getInstance(context)
        policies = db.policyDao().getAllOnce().associateBy { it.packageName }
        schedule = db.scheduleDao().getAllOnce()
        val today = todayDateString()
        usageToday = db.usageDao().getForDateOnce(today).associate { it.packageName to it.minutesUsed }
        grantedToday = db.requestDao().getApprovedGrantedForDate(today)
            .groupBy { it.packageName }
            .mapValues { (_, requests) -> requests.sumOf { it.grantedMinutes ?: 0 } }
    }
}
