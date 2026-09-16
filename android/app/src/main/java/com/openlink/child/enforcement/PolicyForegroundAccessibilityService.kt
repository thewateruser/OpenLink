package com.openlink.child.enforcement

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Detects the foreground app and enforces policy by drawing a full-screen blocking overlay.
 *
 * DESIGN NOTE (single AccessibilityService, not a separate Service+WindowManager):
 * Reliable foreground-app detection on modern Android -- without the multi-second lag of polling
 * UsageStatsManager -- effectively requires an AccessibilityService listening for
 * TYPE_WINDOW_STATE_CHANGED events. Since this service is already running and already holds a
 * privileged binding, it can also draw a `TYPE_ACCESSIBILITY_OVERLAY` window directly: that
 * overlay type is granted implicitly by the accessibility-service binding itself, so showing it
 * does NOT additionally depend on the user having granted SYSTEM_ALERT_WINDOW ("display over
 * other apps"). That keeps the main enforcement path working as long as a single permission
 * (accessibility) is granted, instead of two.
 *
 * SYSTEM_ALERT_WINDOW is still requested separately (see ui/permissions/PermissionsScreen.kt)
 * as a deliberate fallback: MonitorForegroundService uses it to show a `TYPE_APPLICATION_OVERLAY`
 * lock screen if the user has turned the accessibility service off (which -- like removing
 * device-admin -- is worth flagging, since it silently weakens enforcement) but has not also
 * revoked overlay access. Two independent permissions covering two different failure modes,
 * rather than one component depending on both.
 */
class PolicyForegroundAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var overlayController: OverlayController? = null
    private var currentPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 100
        }
        overlayController = OverlayController(
            context = this,
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager,
            overlayType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        )
        scope.launch { EnforcementRepository.primeFromDatabase(applicationContext) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == currentPackage) return
        currentPackage = pkg
        evaluateAndEnforce(pkg)
    }

    private fun evaluateAndEnforce(packageName: String) {
        when (val decision = EnforcementRepository.decisionFor(applicationContext, packageName)) {
            is EnforcementDecision.Allowed -> overlayController?.hide()
            is EnforcementDecision.Blocked -> overlayController?.show(packageName, decision.reason)
        }
    }

    /** Re-evaluates the currently-foregrounded app without waiting for the next app switch --
     *  called after a live `policy:update`, `lock:update`, or approved `request:decision`. */
    fun recheckCurrentApp() {
        currentPackage?.let { evaluateAndEnforce(it) }
    }

    override fun onInterrupt() {
        // No-op: nothing to clean up specifically on interrupt; onUnbind covers teardown.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        overlayController?.hide()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayController?.hide()
        if (instance === this) instance = null
    }

    companion object {
        /** Lets MonitorForegroundService push live updates into the running service without a
         *  bound-service round trip; null when the accessibility service isn't currently
         *  enabled/connected (see the design note above for the SYSTEM_ALERT_WINDOW fallback
         *  used in that case). */
        @Volatile var instance: PolicyForegroundAccessibilityService? = null
            private set
    }
}
