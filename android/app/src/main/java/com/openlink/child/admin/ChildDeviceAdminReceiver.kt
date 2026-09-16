package com.openlink.child.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openlink.child.network.NetworkModule
import com.openlink.child.network.OpenLinkApi
import com.openlink.child.network.model.UsageHeartbeatRequest
import com.openlink.child.prefs.SecurePrefs
import com.openlink.child.util.todayDateString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Backs the device-admin activation flow (`ACTION_ADD_DEVICE_ADMIN`, started from
 * ui/permissions/PermissionsScreen.kt) and receives `lockNow()` capability once active.
 */
class ChildDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
    }

    /**
     * Called when the user disables device-admin access for this app (Settings > Security >
     * Device admin apps). Per spec, that should be treated like a "self-unpair" signal worth
     * surfacing to the parent -- but docs/API.md has no dedicated endpoint for it (only pairing,
     * policy, usage and time-request flows are defined), so this is a best-effort approximation
     * rather than a first-class server feature:
     *
     *   1) record locally that admin access was revoked (surfaced on the Settings screen), and
     *   2) immediately fire a usage heartbeat -- the one child-device endpoint the docs say
     *      updates `lastSeenAt` -- so at minimum the parent's device list reflects a check-in at
     *      the moment of revocation.
     *
     * This does NOT fully replace a real notification: losing device-admin only removes the
     * immediate `lockNow()` capability (the overlay/downtime/limit enforcement paths above still
     * work), and the parent has no explicit "admin was removed" alert, only an ordinary
     * `lastSeenAt` update. A production system would add a real endpoint (e.g.
     * `POST /device/self-unpair`) for this and push a dedicated notification to the parent app.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        val prefs = SecurePrefs(context)
        prefs.setDeviceAdminRevokedAt(System.currentTimeMillis())

        if (prefs.getDeviceToken() == null) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val api = NetworkModule.buildRetrofit(context).create(OpenLinkApi::class.java)
                api.postUsage(UsageHeartbeatRequest(date = todayDateString(), usage = emptyList()))
            } catch (e: Exception) {
                Log.w(TAG, "Best-effort self-unpair heartbeat failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "ChildDeviceAdminReceiver"

        fun componentName(context: Context): ComponentName =
            ComponentName(context, ChildDeviceAdminReceiver::class.java)

        fun isActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(componentName(context))
        }
    }
}
