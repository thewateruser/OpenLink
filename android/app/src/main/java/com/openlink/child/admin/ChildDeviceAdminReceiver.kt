package com.openlink.child.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.openlink.child.prefs.SecurePrefs

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
     * Called when the user disables device-admin access (Settings > Security > Device admin
     * apps). That removes the ability to call `lockNow()`, so it is worth surfacing.
     *
     * All this can do is record it locally: the timestamp is shown on the child's settings
     * screen, and a parent sees the consequence indirectly -- a lock request still returns
     * `isLocked: true` and raises the blocking overlay, but the OS lock screen no longer engages.
     *
     * The previous build tried to notify a server here. There is no server now, and there is
     * deliberately no new event type for it either: docs/PROTOCOL.md's event list is the
     * contract, and inventing a message the iOS app does not know about would be worse than
     * useless. A future protocol revision adding, say, `device:degraded` would be the right way
     * to carry this -- see android/README.md.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        SecurePrefs(context).setDeviceAdminRevokedAt(System.currentTimeMillis())
        Log.w(TAG, "Device admin revoked; remote lock can no longer engage the OS lock screen")
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
