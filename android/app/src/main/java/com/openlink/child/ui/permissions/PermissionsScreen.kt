package com.openlink.child.ui.permissions

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openlink.child.admin.ChildDeviceAdminReceiver
import com.openlink.child.service.MonitorForegroundService

private data class PermissionItem(
    val title: String,
    val description: String,
    val isGranted: () -> Boolean,
    val required: Boolean,
    val launch: () -> Unit
)

/**
 * Walks the user through every special/runtime permission this app needs: usage access, the
 * accessibility service, device admin, "display over other apps", and (Android 13+)
 * notifications. Statuses are re-checked whenever the screen resumes, since all of these are
 * granted in a separate Settings screen the user backs out of.
 *
 * Unchanged by the move to a serverless architecture -- these permissions are about enforcing on
 * this device, which is the half of the app that never depended on a server. The one difference
 * is what happens after: continuing now starts the foreground service *and* the listener a
 * parent will connect to, which is why pairing comes after this screen rather than before it.
 */
@Composable
fun PermissionsScreen(onAllGrantedContinue: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshTick by remember { mutableIntStateOf(0) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshTick++ }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshTick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionItems = remember(refreshTick) {
        listOf(
            PermissionItem(
                title = "Usage access",
                description = "Lets OpenLink see how long each app is used today.",
                isGranted = { PermissionChecks.hasUsageAccess(context) },
                required = true,
                launch = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            ),
            PermissionItem(
                title = "Accessibility service",
                description = "Lets OpenLink detect which app is in the foreground and show the " +
                    "block screen reliably.",
                isGranted = { PermissionChecks.hasAccessibilityServiceEnabled(context) },
                required = true,
                launch = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            ),
            PermissionItem(
                title = "Device admin",
                description = "Lets a parent lock this device remotely. Removing this later is " +
                    "treated as unpairing the device, and the parent will be notified (best-effort).",
                isGranted = { PermissionChecks.hasDeviceAdmin(context) },
                required = true,
                launch = {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(
                            DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            ChildDeviceAdminReceiver.componentName(context)
                        )
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "OpenLink needs this to lock the device when a parent sends a remote lock."
                        )
                    }
                    context.startActivity(intent)
                }
            ),
            PermissionItem(
                title = "Display over other apps",
                description = "Backup so a remote lock can still show a block screen even if the " +
                    "accessibility service gets turned off.",
                isGranted = { PermissionChecks.hasOverlayPermission(context) },
                required = true,
                launch = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    )
                }
            ),
            PermissionItem(
                title = "Notifications",
                description = "Shows the always-on 'OpenLink is active' status notification " +
                    "required to run in the background.",
                isGranted = { PermissionChecks.hasNotificationPermission(context) },
                required = false,
                launch = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )
        )
    }

    val allRequiredGranted = permissionItems.filter { it.required }.all { it.isGranted() }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("Grant permissions", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "OpenLink needs these permissions to enforce screen-time rules.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(permissionItems) { item ->
                PermissionRow(item)
                Divider()
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                MonitorForegroundService.start(context)
                onAllGrantedContinue()
            },
            enabled = allRequiredGranted,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (allRequiredGranted) "Continue" else "Grant all required permissions to continue")
        }
    }
}

@Composable
private fun PermissionRow(item: PermissionItem) {
    val granted = item.isGranted()
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Text(item.description, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(12.dp))
        if (granted) {
            Text("Granted", color = MaterialTheme.colorScheme.primary)
        } else {
            OutlinedButton(onClick = item.launch) { Text("Grant") }
        }
    }
}
