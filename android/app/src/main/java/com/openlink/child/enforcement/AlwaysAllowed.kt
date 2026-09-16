package com.openlink.child.enforcement

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telecom.TelecomManager

/**
 * Per docs/API.md rule 3: during a downtime `ScheduleWindow`, "all apps are blocked except the
 * OpenLink app itself and a short allow-list (Phone, Settings, the default launcher)". This
 * allow-list also gates the `isLocked` overlay indirectly: [EnforcementEngine.evaluate] checks
 * `isLocked` before `alwaysAllowed`, so a remote lock still blocks everything including these
 * apps -- only downtime treats them specially.
 */
object AlwaysAllowed {

    /** Must match the applicationId in app/build.gradle.kts. */
    const val SELF_PACKAGE = "com.openlink.child"

    // Common dialer/settings package names across stock Android and major OEM skins. This list
    // can't be exhaustive; resolveDefaultLauncherPackage()/isDefaultDialer() below cover the
    // current device precisely, these are just a same-behavior fallback.
    private val STATIC_ALLOWLIST = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.server.telecom",
        "com.android.settings",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.oneplus.launcher",
        "com.huawei.android.launcher"
    )

    fun isAlwaysAllowed(context: Context, packageName: String): Boolean {
        if (packageName == SELF_PACKAGE) return true
        if (packageName in STATIC_ALLOWLIST) return true
        if (packageName == resolveDefaultLauncherPackage(context)) return true
        if (isDefaultDialer(context, packageName)) return true
        return false
    }

    private fun resolveDefaultLauncherPackage(context: Context): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName
    }

    private fun isDefaultDialer(context: Context, packageName: String): Boolean = try {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        telecomManager?.defaultDialerPackage == packageName
    } catch (e: Exception) {
        false
    }
}
