package com.openlink.child.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.openlink.child.MainActivity
import com.openlink.child.R
import com.openlink.child.admin.ChildDeviceAdminReceiver
import com.openlink.child.data.AppDatabase
import com.openlink.child.data.UsageEntity
import com.openlink.child.data.toEntity
import com.openlink.child.enforcement.BlockReason
import com.openlink.child.enforcement.EnforcementRepository
import com.openlink.child.enforcement.OverlayController
import com.openlink.child.enforcement.PolicyForegroundAccessibilityService
import com.openlink.child.network.NetworkModule
import com.openlink.child.network.OpenLinkApi
import com.openlink.child.network.SocketManager
import com.openlink.child.network.model.AppPolicyDto
import com.openlink.child.network.model.LockUpdatePayload
import com.openlink.child.network.model.PolicyUpdatePayload
import com.openlink.child.network.model.ScheduleWindowDto
import com.openlink.child.network.model.TimeRequestDto
import com.openlink.child.network.model.UsageEntryDto
import com.openlink.child.network.model.UsageHeartbeatRequest
import com.openlink.child.prefs.SecurePrefs
import com.openlink.child.util.todayDateString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * The foreground service required to keep enforcement running while the app isn't in the
 * foreground itself. It has four jobs, each its own loop/callback, all described in
 * docs/API.md's "Realtime" and enforcement sections:
 *  1. Poll UsageStatsManager every ~30s and tally today's per-app foreground minutes into Room.
 *  2. Push a usage heartbeat (POST /device/usage) roughly once a minute.
 *  3. Maintain a Socket.IO connection for live `policy:update` / `lock:update` /
 *     `request:decision`, applying each to Room + EnforcementRepository immediately.
 *  4. Fall back to polling GET /device/policies and GET /device/requests every ~30s whenever the
 *     socket is disconnected.
 */
class MonitorForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var prefs: SecurePrefs
    private lateinit var db: AppDatabase
    private lateinit var api: OpenLinkApi
    private val socketManager = SocketManager()
    private var lockFallbackOverlay: OverlayController? = null

    override fun onCreate() {
        super.onCreate()
        prefs = SecurePrefs(applicationContext)
        db = AppDatabase.getInstance(applicationContext)
        api = NetworkModule.buildRetrofit(applicationContext).create(OpenLinkApi::class.java)

        startForegroundWithNotification()
        scope.launch { EnforcementRepository.primeFromDatabase(applicationContext) }

        wireSocketCallbacks()
        connectSocketIfPossible()

        scope.launch { usagePollLoop() }
        scope.launch { heartbeatLoop() }
        scope.launch { pollingFallbackLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If the OS kills this process under memory pressure, restart enforcement ASAP.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        socketManager.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    // ---- foreground notification -----------------------------------------------------------

    private fun startForegroundWithNotification() {
        val channelId = "openlink_monitor"
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "OpenLink protection", NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Keeps OpenLink's screen-time protection running." }
            notificationManager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("OpenLink is active")
            .setContentText("Screen-time protection is running.")
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---- loops --------------------------------------------------------------------------------

    private suspend fun usagePollLoop() {
        while (scope.isActive) {
            try {
                pollUsageStatsOnce()
            } catch (e: Exception) {
                // Best-effort; next tick retries.
            }
            delay(USAGE_POLL_INTERVAL_MS)
        }
    }

    private suspend fun heartbeatLoop() {
        while (scope.isActive) {
            delay(HEARTBEAT_INTERVAL_MS)
            try {
                pushHeartbeat()
            } catch (e: Exception) {
                // Offline: next tick retries. Today's usage stays correct locally regardless.
            }
        }
    }

    private suspend fun pollingFallbackLoop() {
        while (scope.isActive) {
            delay(FALLBACK_POLL_INTERVAL_MS)
            if (!socketManager.isConnected()) {
                connectSocketIfPossible() // retry the socket too, in case it's just reconnecting
                try {
                    refreshPoliciesFromServer()
                } catch (e: Exception) {
                    // offline; try again next tick
                }
                try {
                    refreshRequestsFromServer()
                } catch (e: Exception) {
                    // offline; try again next tick
                }
            }
        }
    }

    // ---- usage tracking -----------------------------------------------------------------------

    private suspend fun pollUsageStatsOnce() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        val now = System.currentTimeMillis()

        val stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startOfDay, now)
            ?: return
        val today = todayDateString()
        val tallies = mutableMapOf<String, Int>()
        for (usageStat in stats) {
            if (usageStat.packageName == packageName) continue // never count OpenLink itself
            val minutes = (usageStat.totalTimeInForeground / 60_000L).toInt()
            if (minutes <= 0) continue
            // queryUsageStats can return multiple overlapping buckets for one INTERVAL_DAILY
            // query on some OEM builds; keep the max we've seen for this package today.
            tallies[usageStat.packageName] = maxOf(tallies[usageStat.packageName] ?: 0, minutes)
        }

        val usageDao = db.usageDao()
        tallies.forEach { (pkg, minutes) ->
            usageDao.upsert(UsageEntity(packageName = pkg, date = today, minutesUsed = minutes))
        }
        EnforcementRepository.updateUsageToday(
            usageDao.getForDateOnce(today).associate { it.packageName to it.minutesUsed }
        )
        PolicyForegroundAccessibilityService.instance?.recheckCurrentApp()
    }

    private suspend fun pushHeartbeat() {
        val today = todayDateString()
        val usage = db.usageDao().getForDateOnce(today).map { UsageEntryDto(it.packageName, it.minutesUsed) }
        if (usage.isEmpty()) return
        api.postUsage(UsageHeartbeatRequest(date = today, usage = usage))
    }

    // ---- socket / policy sync -------------------------------------------------------------------

    private fun wireSocketCallbacks() {
        socketManager.onPolicyUpdate = { raw ->
            scope.launch {
                try {
                    val payload = NetworkModule.json.decodeFromString(PolicyUpdatePayload.serializer(), raw)
                    applyPolicies(payload.policies, payload.schedule)
                } catch (e: Exception) { /* malformed/unexpected payload; ignore this event */ }
            }
        }
        socketManager.onLockUpdate = { raw ->
            scope.launch {
                try {
                    val payload = NetworkModule.json.decodeFromString(LockUpdatePayload.serializer(), raw)
                    applyLock(payload.isLocked)
                } catch (e: Exception) { /* ignore */ }
            }
        }
        socketManager.onRequestDecision = { raw ->
            scope.launch {
                try {
                    val dto = NetworkModule.json.decodeFromString(TimeRequestDto.serializer(), raw)
                    applyRequestDecision(dto)
                } catch (e: Exception) { /* ignore */ }
            }
        }
    }

    private fun connectSocketIfPossible() {
        val serverUrl = prefs.getServerUrl()
        val token = prefs.getDeviceToken()
        if (!serverUrl.isNullOrBlank() && !token.isNullOrBlank()) {
            socketManager.connect(serverUrl, token)
        }
    }

    private suspend fun refreshPoliciesFromServer() {
        val response = api.getPolicies()
        applyPolicies(response.policies, response.schedule)
        applyLock(response.isLocked)
    }

    private suspend fun refreshRequestsFromServer() {
        val requests = api.getRequests()
        val requestDao = db.requestDao()
        requests.forEach { requestDao.upsert(it.toEntity()) }

        val today = todayDateString()
        val grantedToday = requestDao.getApprovedGrantedForDate(today)
            .groupBy { it.packageName }
            .mapValues { (_, reqs) -> reqs.sumOf { it.grantedMinutes ?: 0 } }
        EnforcementRepository.updateGrantedToday(grantedToday)
        PolicyForegroundAccessibilityService.instance?.recheckCurrentApp()
    }

    private suspend fun applyPolicies(policies: List<AppPolicyDto>, schedule: List<ScheduleWindowDto>) {
        val policyDao = db.policyDao()
        val scheduleDao = db.scheduleDao()
        policyDao.replaceAll(policies.map { it.toEntity() })
        scheduleDao.replaceAll(schedule.map { it.toEntity() })
        EnforcementRepository.updatePolicies(policyDao.getAllOnce())
        EnforcementRepository.updateSchedule(scheduleDao.getAllOnce())
        PolicyForegroundAccessibilityService.instance?.recheckCurrentApp()
    }

    private fun applyLock(locked: Boolean) {
        EnforcementRepository.updateLocked(locked)
        if (locked) {
            lockDeviceNow()
        }
        val accessibilityServiceRunning = PolicyForegroundAccessibilityService.instance != null
        when {
            accessibilityServiceRunning -> PolicyForegroundAccessibilityService.instance?.recheckCurrentApp()
            locked -> showFallbackLockOverlay()
            else -> lockFallbackOverlay?.hide()
        }
    }

    private suspend fun applyRequestDecision(dto: TimeRequestDto) {
        db.requestDao().upsert(dto.toEntity())
        if (dto.status == "approved" && (dto.grantedMinutes ?: 0) > 0) {
            // Unblocks immediately, without waiting for the next heartbeat/poll round trip.
            EnforcementRepository.bumpGrantedToday(dto.packageName, dto.grantedMinutes ?: 0)
        }
        PolicyForegroundAccessibilityService.instance?.recheckCurrentApp()
    }

    // ---- remote lock ----------------------------------------------------------------------------

    private fun lockDeviceNow() {
        try {
            val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ChildDeviceAdminReceiver.componentName(this)
            if (devicePolicyManager.isAdminActive(admin)) {
                devicePolicyManager.lockNow()
            }
        } catch (e: Exception) {
            // Device-admin not active; the overlay (below / via accessibility) still enforces
            // visually even though the OS lock screen won't engage immediately.
        }
    }

    /** Fallback so a remote lock still visually enforces even when the accessibility service is
     *  disabled, as long as SYSTEM_ALERT_WINDOW is granted. See the design-note comment on
     *  PolicyForegroundAccessibilityService for the full reasoning. */
    private fun showFallbackLockOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val controller = lockFallbackOverlay ?: OverlayController(
            this, windowManager, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        ).also { lockFallbackOverlay = it }
        controller.show("this device", BlockReason.LOCKED)
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val USAGE_POLL_INTERVAL_MS = 30_000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        private const val FALLBACK_POLL_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, MonitorForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
