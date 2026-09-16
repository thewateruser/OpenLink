package com.openlink.child.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.openlink.child.pairing.ParentRegistry

/**
 * Restarts enforcement -- and with it the listener a parent connects to -- after a reboot, if at
 * least one parent is paired.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (ParentRegistry(context).isPaired()) {
            MonitorForegroundService.start(context)
        }
    }
}
