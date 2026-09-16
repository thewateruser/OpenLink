package com.openlink.child.domain

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.openlink.child.data.AppDatabase
import com.openlink.child.data.PolicyEntity
import com.openlink.child.data.TimeRequestEntity
import com.openlink.child.data.UsageEntity
import com.openlink.child.data.defaultPolicyDto
import com.openlink.child.data.toDto
import com.openlink.child.data.toEntity
import com.openlink.child.enforcement.EnforcementRepository
import com.openlink.child.enforcement.PolicyForegroundAccessibilityService
import com.openlink.child.prefs.SecurePrefs
import com.openlink.child.server.AppDto
import com.openlink.child.server.DeviceInfo
import com.openlink.child.server.DeviceResponse
import com.openlink.child.server.EventBus
import com.openlink.child.server.PolicyDto
import com.openlink.child.server.PolicyPatch
import com.openlink.child.server.ScheduleWindowDto
import com.openlink.child.server.TimeRequestDto
import com.openlink.child.server.UsageEntryDto
import com.openlink.child.server.UsageResponse
import com.openlink.child.util.dateStringDaysAgo
import com.openlink.child.util.isValidDateString
import com.openlink.child.util.isoFromEpochMillis
import com.openlink.child.util.nowIso
import com.openlink.child.util.todayDateString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** Actions that only the foreground service can perform, handed to the repository at startup. */
interface DeviceActions {
    /** Engages or releases a remote lock: `DevicePolicyManager.lockNow()` plus the overlay. */
    fun applyLock(locked: Boolean)
}

/**
 * Thrown by repository operations that a route should answer with a specific status. StatusPages
 * turns it into a terse JSON body (see server/Routes.kt).
 */
class ApiError(val statusCode: Int, val reason: String) : Exception(reason)

/**
 * Every operation the API and the local UI perform on the device's own state.
 *
 * This is the layer that used to be split across a server and a sync client. Now that the child
 * owns the data, a `PUT /policies/...` from a parent and a tap in the child's own UI are the same
 * kind of thing: a local write, followed by an enforcement refresh, followed by a broadcast to
 * whoever happens to be watching. Keeping that in one place is what stops the two paths drifting.
 */
class ChildRepository private constructor(private val appContext: Context) {

    private val db = AppDatabase.getInstance(appContext)
    private val prefs = SecurePrefs(appContext)

    /** Set by MonitorForegroundService once it is running; null before that. */
    @Volatile
    var deviceActions: DeviceActions? = null

    // ---- device ---------------------------------------------------------------------------------

    fun deviceId(): String = prefs.deviceId()

    fun deviceName(): String = prefs.deviceName()

    fun isLocked(): Boolean = prefs.isLocked()

    fun deviceSnapshot(port: Int): DeviceResponse = DeviceResponse(
        deviceId = prefs.deviceId(),
        deviceName = prefs.deviceName(),
        isLocked = prefs.isLocked(),
        platform = DeviceInfo.PLATFORM,
        appVersion = DeviceInfo.appVersion(appContext),
        endpoints = DeviceInfo.endpoints(port),
        batteryLevel = DeviceInfo.batteryLevel(appContext),
        lastBootAt = DeviceInfo.lastBootAtIso()
    )

    /**
     * Applies a lock state. Persisted first, so that a process death (or a force-stop) cannot
     * quietly unlock a device a parent locked.
     */
    fun setLocked(locked: Boolean, originParentId: String?): Boolean {
        prefs.setLocked(locked)
        EnforcementRepository.updateLocked(locked)
        deviceActions?.applyLock(locked)
        EventBus.lockUpdate(locked, exceptParentId = originParentId)
        recheckForegroundApp()
        return locked
    }

    // ---- apps and policies ------------------------------------------------------------------------

    /**
     * Launchable installed apps, with today's minutes and the policy in force.
     *
     * The child enumerates these itself -- there is no catalogue to sync, because the answer
     * never has to travel anywhere to be stored.
     */
    suspend fun apps(): List<AppDto> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        @Suppress("DEPRECATION") // the PackageManager.ResolveInfoFlags overload is API 33+
        val resolved = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (e: Exception) {
            emptyList()
        }

        val packages = resolved
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
            .minus(appContext.packageName) // OpenLink itself is never a policy target

        val policies = db.policyDao().getAllOnce().associateBy { it.packageName }
        val today = todayDateString()
        val usage = db.usageDao().getForDateOnce(today).associate { it.packageName to it.minutesUsed }

        packages.mapNotNull { packageName ->
            val info: ApplicationInfo = try {
                pm.getApplicationInfo(packageName, 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return@mapNotNull null // uninstalled between the query and now
            }
            AppDto(
                packageName = packageName,
                appName = pm.getApplicationLabel(info).toString(),
                isSystemApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                policy = policies[packageName]?.toDto() ?: defaultPolicyDto(packageName),
                todayMinutes = usage[packageName] ?: 0
            )
        }.sortedBy { it.appName.lowercase() }
    }

    suspend fun policy(packageName: String): PolicyDto =
        db.policyDao().getByPackage(packageName)?.toDto() ?: defaultPolicyDto(packageName)

    /**
     * Applies a `PUT /policies/{packageName}` patch.
     *
     * Omitted fields are left alone; an explicit null `dailyLimitMinutes` clears the limit. The
     * patch has already been decoded into its three-state form by the route.
     */
    suspend fun updatePolicy(
        packageName: String,
        patch: PolicyPatch,
        originParentId: String?
    ): PolicyDto {
        if (packageName.isBlank() || packageName.length > MAX_PACKAGE_NAME) {
            throw ApiError(400, "invalid package name")
        }
        patch.dailyLimitMinutes?.let {
            if (it < 0 || it > MAX_DAILY_LIMIT_MINUTES) throw ApiError(400, "invalid dailyLimitMinutes")
        }

        val existing = db.policyDao().getByPackage(packageName)
        val updated = PolicyEntity(
            packageName = packageName,
            dailyLimitMinutes = if (patch.limitProvided) patch.dailyLimitMinutes else existing?.dailyLimitMinutes,
            blocked = patch.blocked ?: existing?.blocked ?: false
        )
        db.policyDao().upsert(updated)

        refreshPolicySnapshot(originParentId)
        return updated.toDto()
    }

    suspend fun schedule(): List<ScheduleWindowDto> =
        db.scheduleDao().getAllOnce().map { it.toDto() }

    /** `PUT /schedule` replaces the whole set. */
    suspend fun replaceSchedule(
        windows: List<ScheduleWindowDto>,
        originParentId: String?
    ): List<ScheduleWindowDto> {
        if (windows.size > MAX_SCHEDULE_WINDOWS) throw ApiError(400, "too many windows")
        windows.forEach { window ->
            if (window.daysOfWeek !in 0..127) throw ApiError(400, "invalid daysOfWeek")
            if (window.startMinute !in 0..1440 || window.endMinute !in 0..1440) {
                throw ApiError(400, "invalid window bounds")
            }
        }

        db.scheduleDao().replaceAll(windows.map { it.toEntity() })
        refreshPolicySnapshot(originParentId)
        return schedule()
    }

    /**
     * Re-reads policies and schedule into the in-memory enforcement cache, re-evaluates the app
     * currently on screen, and tells other connected parents what changed.
     */
    private suspend fun refreshPolicySnapshot(originParentId: String?) {
        val policies = db.policyDao().getAllOnce()
        val schedule = db.scheduleDao().getAllOnce()
        EnforcementRepository.updatePolicies(policies)
        EnforcementRepository.updateSchedule(schedule)
        recheckForegroundApp()
        EventBus.policyUpdate(
            policies = policies.map { it.toDto() },
            schedule = schedule.map { it.toDto() },
            exceptParentId = originParentId
        )
    }

    // ---- usage -------------------------------------------------------------------------------------

    suspend fun usage(date: String?): UsageResponse {
        val resolvedDate = date?.takeIf { it.isNotBlank() } ?: todayDateString()
        if (!isValidDateString(resolvedDate)) throw ApiError(400, "invalid date")
        val rows = db.usageDao().getForDateOnce(resolvedDate)
        return UsageResponse(
            date = resolvedDate,
            usage = rows.map { UsageEntryDto(it.packageName, it.minutesUsed) }
        )
    }

    /**
     * Stores a poll's worth of tallies and emits `usage:update` for the packages whose minute
     * count actually moved -- emitting for every package on every tick would flood the socket
     * with no new information.
     */
    suspend fun recordUsage(tallies: Map<String, Int>, date: String) {
        val usageDao = db.usageDao()
        val previous = usageDao.getForDateOnce(date).associate { it.packageName to it.minutesUsed }

        tallies.forEach { (packageName, minutes) ->
            if (previous[packageName] == minutes) return@forEach
            usageDao.upsert(UsageEntity(packageName = packageName, date = date, minutesUsed = minutes))
            EventBus.usageUpdate(packageName, minutes, date)
        }

        EnforcementRepository.updateUsageToday(
            usageDao.getForDateOnce(date).associate { it.packageName to it.minutesUsed }
        )
        recheckForegroundApp()
    }

    /** 30-day usage retention, plus a longer sweep for resolved time requests. */
    suspend fun pruneOldData() {
        db.usageDao().deleteOlderThan(dateStringDaysAgo(USAGE_RETENTION_DAYS))
        db.requestDao().deleteResolvedOlderThan(
            isoFromEpochMillis(
                System.currentTimeMillis() - REQUEST_RETENTION_DAYS * 24L * 60L * 60L * 1000L
            )
        )
    }

    // ---- time requests --------------------------------------------------------------------------------

    suspend fun requests(status: String?): List<TimeRequestDto> {
        val dao = db.requestDao()
        val rows = when {
            status.isNullOrBlank() -> dao.recent()
            status in VALID_STATUSES -> dao.recentByStatus(status)
            else -> throw ApiError(400, "invalid status")
        }
        return rows.map { it.toDto() }
    }

    /**
     * Files a request from the child's own UI. Unlike the old server-backed flow this cannot
     * fail for being offline -- the row is created locally and simply waits.
     */
    suspend fun createRequest(
        packageName: String,
        minutesRequested: Int,
        message: String?
    ): TimeRequestDto {
        val entity = TimeRequestEntity(
            id = UUID.randomUUID().toString(),
            packageName = packageName,
            appName = resolveAppName(packageName),
            minutesRequested = minutesRequested.coerceIn(1, MAX_REQUEST_MINUTES),
            message = message?.takeIf { it.isNotBlank() }?.take(280),
            status = STATUS_PENDING,
            grantedMinutes = null,
            responseNote = null,
            createdAt = nowIso(),
            respondedAt = null
        )
        db.requestDao().upsert(entity)
        val dto = entity.toDto()
        EventBus.requestNew(dto)
        return dto
    }

    suspend fun approveRequest(id: String, grantedMinutes: Int): TimeRequestDto {
        if (grantedMinutes < 1 || grantedMinutes > MAX_REQUEST_MINUTES) {
            throw ApiError(400, "invalid grantedMinutes")
        }
        val existing = db.requestDao().getById(id) ?: throw ApiError(404, "no such request")
        if (existing.status != STATUS_PENDING) throw ApiError(409, "already answered")

        val updated = existing.copy(
            status = STATUS_APPROVED,
            grantedMinutes = grantedMinutes,
            respondedAt = nowIso()
        )
        db.requestDao().upsert(updated)

        // Takes effect immediately -- same process, no round trip, so the blocked app can be
        // reopened the moment the parent taps approve.
        EnforcementRepository.bumpGrantedToday(updated.packageName, grantedMinutes)
        recheckForegroundApp()
        return updated.toDto()
    }

    suspend fun denyRequest(id: String, reason: String?): TimeRequestDto {
        val existing = db.requestDao().getById(id) ?: throw ApiError(404, "no such request")
        if (existing.status != STATUS_PENDING) throw ApiError(409, "already answered")

        val updated = existing.copy(
            status = STATUS_DENIED,
            responseNote = reason?.takeIf { it.isNotBlank() }?.take(280),
            respondedAt = nowIso()
        )
        db.requestDao().upsert(updated)
        return updated.toDto()
    }

    // ---- shared helpers ---------------------------------------------------------------------------------

    /** Loads the persisted snapshot into the in-memory enforcement cache. */
    suspend fun primeEnforcement() {
        EnforcementRepository.updateLocked(prefs.isLocked())
        EnforcementRepository.primeFromDatabase(appContext)
        recheckForegroundApp()
    }

    private fun recheckForegroundApp() {
        PolicyForegroundAccessibilityService.instance?.recheckCurrentApp()
    }

    private fun resolveAppName(packageName: String): String? = try {
        val pm = appContext.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_DENIED = "denied"

        private val VALID_STATUSES = setOf(STATUS_PENDING, STATUS_APPROVED, STATUS_DENIED)

        private const val MAX_PACKAGE_NAME = 255
        private const val MAX_DAILY_LIMIT_MINUTES = 24 * 60
        private const val MAX_SCHEDULE_WINDOWS = 64
        private const val MAX_REQUEST_MINUTES = 8 * 60
        private const val USAGE_RETENTION_DAYS = 30L
        private const val REQUEST_RETENTION_DAYS = 90L

        @Volatile private var INSTANCE: ChildRepository? = null

        fun getInstance(context: Context): ChildRepository =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ChildRepository(context.applicationContext).also { INSTANCE = it }
            }
    }
}
