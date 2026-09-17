package com.openlink.child.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openlink.child.pairing.ParentRegistry
import com.openlink.child.prefs.PairedParent
import com.openlink.child.prefs.SecurePrefs
import com.openlink.child.security.TlsIdentity
import com.openlink.child.server.ServerState
import com.openlink.child.service.MonitorForegroundService
import com.openlink.child.util.isoFromEpochMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings for a device that is its own server: who can control it, where it can be reached, and
 * how to hand out a new pairing code.
 *
 * The "server address" field this screen used to have is gone -- there is no server to point at.
 * What replaced it is the inverse: the addresses *this* device is listening on, which is
 * information the household may occasionally need (typing an address by hand is the documented
 * fallback when neither mDNS nor a learned overlay address works).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRepair: () -> Unit,
    onUnpairedAll: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { SecurePrefs(context) }
    val registry = remember { ParentRegistry(context) }

    val serverStatus by ServerState.snapshot.collectAsState()
    val parents = remember { registry.parents().toMutableStateList() }

    var deviceName by remember { mutableStateOf(prefs.deviceName()) }
    var nameSaved by remember { mutableStateOf(false) }
    var fingerprint by remember { mutableStateOf<String?>(null) }
    var pendingRevoke by remember { mutableStateOf<PairedParent?>(null) }
    var showUnpairAllConfirm by remember { mutableStateOf(false) }

    // Keystore access can block; never on the composition thread.
    LaunchedEffect(Unit) {
        fingerprint = withContext(Dispatchers.Default) {
            try {
                TlsIdentity.loadOrCreate().fingerprintHex
            } catch (e: Exception) {
                null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .fillMaxSize()
        ) {
            // ---- paired parents -------------------------------------------------------------
            Text("Parents", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (parents.isEmpty()) {
                Text(
                    "No parent is paired with this device. Nothing can control it remotely.",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                parents.forEach { parent ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(parent.parentName, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Paired ${isoFromEpochMillis(parent.pairedAtEpochMillis)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(
                            onClick = { pendingRevoke = parent },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Revoke") }
                    }
                    Divider()
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = onRepair, modifier = Modifier.fillMaxWidth()) {
                Text("Show a pairing code")
            }

            // ---- connection -----------------------------------------------------------------
            Spacer(Modifier.height(32.dp))
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            when {
                serverStatus.lastError != null -> Text(
                    "Not listening: ${serverStatus.lastError}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )

                serverStatus.running -> {
                    Text(
                        "Listening on port ${serverStatus.port}. " +
                            "${serverStatus.connectedParents} parent app(s) connected.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    serverStatus.endpoints.forEach { endpoint ->
                        Text(
                            endpoint,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                else -> Text(
                    "Not running. Grant the permissions on the previous screen to start it.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            fingerprint?.let { hex ->
                Spacer(Modifier.height(16.dp))
                Text("Certificate fingerprint", style = MaterialTheme.typography.titleSmall)
                Text(hex, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }

            // ---- device name ----------------------------------------------------------------
            Spacer(Modifier.height(32.dp))
            Text("Device name", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it; nameSaved = false },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                prefs.setDeviceName(deviceName)
                nameSaved = true
            }) { Text(if (nameSaved) "Saved" else "Save") }
            Text(
                "Shown in the parent's app and in the mDNS advertisement. Takes effect the next " +
                    "time protection restarts.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(32.dp))
            OutlinedButton(
                onClick = { showUnpairAllConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Remove all parents and stop protection") }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingRevoke?.let { parent ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = { Text("Revoke ${parent.parentName}?") },
            text = {
                Text(
                    "Their app will immediately lose access to this device. They can pair again " +
                        "by scanning a new code."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    registry.revoke(parent.parentId)
                    parents.remove(parent)
                    pendingRevoke = null
                }) { Text("Revoke") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevoke = null }) { Text("Cancel") }
            }
        )
    }

    if (showUnpairAllConfirm) {
        AlertDialog(
            onDismissRequest = { showUnpairAllConfirm = false },
            title = { Text("Remove all parents?") },
            text = {
                Text(
                    "This stops enforcement on this device and revokes every parent's access. " +
                        "Screen-time limits stop applying immediately."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    registry.revokeAll()
                    parents.clear()
                    MonitorForegroundService.stop(context)
                    showUnpairAllConfirm = false
                    onUnpairedAll()
                }) { Text("Remove all") }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairAllConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
