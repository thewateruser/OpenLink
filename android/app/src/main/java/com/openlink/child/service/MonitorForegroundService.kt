package com.openlink.child.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openlink.child.MainActivity
import com.openlink.child.R
import com.openlink.child.domain.ChildRepository
import com.openlink.child.server.DeviceInfo
import com.openlink.child.server.EventBus
import com.openlink.child.server.OpenLinkServer
import com.openlink.child.server.ServerState
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
 * The always-on half of the app. It has three jobs now, down from four:
 *
 *  1. Poll UsageStatsManager every ~30s and tally today's per-app foreground minutes into Room
 *     (emitting `usage:update` for anything that moved).
 *  2. Host the embedded TLS listener the parent app connects to, for exactly as long as
 *     enforcement is running.
 *  3. Broadcast `device:state` every ~60s, which doubles as the WebSocket keepalive and as the
 *     refresh that lets a parent learn a newly-available overlay address.
 *
 * The fourth job -- pushing usage to a server and polling it back for policy -- is gone, along
 * with the server. Nothing is uploaded and nothing is fetched; the data was always here.
 */
class MonitorForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var repository: ChildRepository

    /** Written from the IO scope in onCreate, read on the main thread in onDestroy. */
    @Volatile
    private var server: OpenLinkServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Ordering here is load-bearing, and was the cause of the app appearing to do nothing after
     * permissions were granted.
     *
     * `startForeground()` must be reached within ~5s of `startForegroundService()`, and before
     * anything that can block or throw -- otherwise the process dies with either
     * ForegroundServiceDidNotStartInTimeException or whatever threw first. So it goes first, and
     * everything after it is best-effort: a failure degrades the app rather than killing it.
     *
     * Generating the Keystore key and binding the TLS socket are also both slow enough to ANR on
     * the main thread, so the listener starts on IO. The pairing screen already renders a waiting
     * state until [ServerState] reports a bound port, and an error panel if it reports a failure,
     * so starting asynchronously is what that UI was written to expect.
     */
    override fun onCreate() {
        super.onCreate()

        startForegroundWithNotification()

        repository = ChildRepository.getInstance(applicationContext)

        scope.launch {
            runCatching { acquireWifiLock() }
                .onFailure { Log.w(TAG, "Wi-Fi lock unavailable: ${it.javaClass.simpleName}") }

            runCatching {
                repository.primeEnforcement()
                repository.pruneOldData()
            }.onFailure { Log.e(TAG, "Could not prime enforcement: ${it.javaClass.simpleName}") }

            // Assign before starting, so onDestroy can always stop whatever was created even if
            // start() is still in flight.
            runCatching { OpenLinkServer(applicationContext).also { server = it }.start() }
                .onFailure {
                    Log.e(TAG, "Listener failed to start: ${it.javaClass.simpleName}")
                    ServerState.onFailed(
                        "The connection listener could not start (${it.javaClass.simpleName})."
                    )
                }
        }

        scope.launch { usagePollLoop() }
        scope.launch { deviceStateLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If the OS kills this process under memory pressure, restart enforcement ASAP.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Cancel first so nothing new starts, then tear down what exists. start() assigns
        // `server` before binding, so an in-flight startup is still stoppable here.
        scope.cancel()
        server?.stop()
        server = null
        releaseWifiLock()
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

        // FOREGROUND_SERVICE_TYPE_SPECIAL_USE and the manifest's `specialUse` value both arrived
        // in API 34. Passing the constant on API 29..33 is not merely ignored: the platform
        // checks it against the types it parsed from the manifest, finds `specialUse` unknown
        // there, and throws IllegalArgumentException -- which killed the service in onCreate and
        // took the app down the instant the user tapped Continue. Below 34 the untyped overload
        // is the correct call, and is valid on every version this app supports.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---- Wi-Fi reachability -------------------------------------------------------------------

    /**
     * A foreground service keeps the process alive, but it does not keep the Wi-Fi radio
     * responsive once the screen has been off for a while -- and an unreachable listener is a
     * parent app that "randomly" cannot connect. The lock is held only while the service (and
     * therefore the listener) is running.
     */
    private fun acquireWifiLock() {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return
            // WIFI_MODE_FULL_HIGH_PERF is deprecated as of API 29, but it is still the mode that
            // keeps an inbound socket answerable across the API 26+ range this app supports.
            @Suppress("DEPRECATION")
            val lock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire()
            wifiLock = lock
        } catch (e: Exception) {
            Log.w(TAG, "Could not acquire a Wi-Fi lock: ${e.javaClass.simpleName}")
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            // Already released.
        }
        wifiLock = null
    }

    // ---- loops --------------------------------------------------------------------------------

    private suspend fun usagePollLoop() {
        var ticksSincePrune = 0
        while (scope.isActive) {
            try {
                pollUsageStatsOnce()
            } catch (e: Exception) {
                // Best-effort; next tick retries.
            }
            if (++ticksSincePrune >= TICKS_BETWEEN_PRUNES) {
                ticksSincePrune = 0
                try {
                    repository.pruneOldData()
                } catch (e: Exception) {
                    // Retention is housekeeping; a failure here is not worth interrupting.
                }
            }
            delay(USAGE_POLL_INTERVAL_MS)
        }
    }

    /**
     * `device:state` every ~60s. Cheap, and it carries the current endpoint list -- which is how
     * a parent that is already connected picks up an address that only just became available
     * (the overlay network coming up, a Wi-Fi network changing).
     */
    private suspend fun deviceStateLoop() {
        while (scope.isActive) {
            delay(DEVICE_STATE_INTERVAL_MS)
            val port = server?.boundPort ?: continue
            try {
                val endpoints = DeviceInfo.endpoints(port)
                ServerState.onEndpointsChanged(endpoints)
                EventBus.deviceState(DeviceInfo.batteryLevel(applicationContext), endpoints)
            } catch (e: Exception) {
                // Interface enumeration can fail transiently while the network reconfigures.
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
        val tallies = mutableMapOf<String, Int>()
        for (usageStat in stats) {
            if (usageStat.packageName == packageName) continue // never count OpenLink itself
            val minutes = (usageStat.totalTimeInForeground / 60_000L).toInt()
            if (minutes <= 0) continue
            // queryUsageStats can return multiple overlapping buckets for one INTERVAL_DAILY
            // query on some OEM builds; keep the max we've seen for this package today.
            tallies[usageStat.packageName] = maxOf(tallies[usageStat.packageName] ?: 0, minutes)
        }

        repository.recordUsage(tallies, todayDateString())
    }

    companion object {
        private const val TAG = "MonitorService"
        private const val NOTIFICATION_ID = 42
        private const val WIFI_LOCK_TAG = "openlink:listener"
        private const val USAGE_POLL_INTERVAL_MS = 30_000L
        private const val DEVICE_STATE_INTERVAL_MS = 60_000L
        /** ~30 minutes at the current poll interval. */
        private const val TICKS_BETWEEN_PRUNES = 60

        fun start(context: Context) {
            val intent = Intent(context, MonitorForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MonitorForegroundService::class.java))
        }
    }
}
