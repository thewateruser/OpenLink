package com.openlink.child.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openlink.child.prefs.SecurePrefs
import com.openlink.child.service.MonitorForegroundService

/** Lets the user (re-)view/edit the self-hosted server address (item 7 of the spec) and unpair
 *  the device. Reached from HomeScreen's "Settings" action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onUnpaired: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { SecurePrefs(context) }
    var serverUrl by remember { mutableStateOf(prefs.getServerUrl() ?: "") }
    var saved by remember { mutableStateOf(false) }
    var showUnpairConfirm by remember { mutableStateOf(false) }
    val adminRevokedAt = remember { prefs.getDeviceAdminRevokedAt() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(24.dp).fillMaxSize()) {
            Text("Server address", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it; saved = false },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                prefs.setServerUrl(serverUrl.trim())
                saved = true
            }) { Text(if (saved) "Saved" else "Save") }

            Spacer(Modifier.height(24.dp))
            Text("Device ID: ${prefs.getDeviceId() ?: "-"}", style = MaterialTheme.typography.bodySmall)
            Text("Family ID: ${prefs.getFamilyId() ?: "-"}", style = MaterialTheme.typography.bodySmall)

            if (adminRevokedAt > 0) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "Device admin access was removed on this device. This is treated as a " +
                        "self-unpair signal; the app has attempted to notify the server " +
                        "(best-effort). Re-grant device admin from the permissions screen if " +
                        "this was accidental.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { showUnpairConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Unpair this device") }
        }
    }

    if (showUnpairConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairConfirm = false },
            title = { Text("Unpair device?") },
            text = {
                Text(
                    "This stops all enforcement on this device and forgets its link to the " +
                        "family until it's paired again."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    context.stopService(Intent(context, MonitorForegroundService::class.java))
                    prefs.clearPairing()
                    showUnpairConfirm = false
                    onUnpaired()
                }) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { showUnpairConfirm = false }) { Text("Cancel") } }
        )
    }
}
