package com.openlink.child.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openlink.child.prefs.SecurePrefs

/** Restarts enforcement after a reboot, if this device is already paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (SecurePrefs(context).isPaired()) {
            MonitorForegroundService.start(context)
        }
    }
}
