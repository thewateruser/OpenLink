package com.openlink.child.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openlink.child.server.ServerState

/**
 * The one piece of setup OpenLink cannot do for the household: getting both phones onto the same
 * private network so the parent can reach this device from outside the house.
 *
 * Everything this screen describes used to live only in code comments and the repo README, which
 * is no use to somebody holding the phone. The mechanism itself is already automatic -- see
 * [com.openlink.child.server.DeviceInfo.endpoints] and docs/PROTOCOL.md's endpoint learning -- so
 * the only thing missing was telling the user that installing Tailscale on both phones is the
 * whole job.
 *
 * The address list at the bottom is the feedback loop: it is the same [ServerState] snapshot the
 * settings screen shows, but labelled so a non-technical user can tell whether the overlay
 * address has actually appeared yet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AwayFromHomeScreen(onBack: () -> Unit) {
    val serverStatus by ServerState.snapshot.collectAsState()

    val endpoints = serverStatus.endpoints
    val hasOverlayAddress = endpoints.any { isOverlayAddress(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Away from home") },
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
            Text("At home there's nothing to set up", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "When this phone and the parent's phone are both on your home Wi-Fi, they find " +
                    "each other on their own.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(24.dp))
            Text("To check in from anywhere else", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Install Tailscale on both phones — this one and the parent's — and sign in to " +
                    "the same account on each. That is the whole setup.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Tailscale is a separate app from another company, and installing it is your " +
                    "choice. Its free plan is more than enough for a family. If you already run " +
                    "WireGuard yourself, that works exactly the same way and you can use it " +
                    "instead.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(Modifier.height(24.dp))
            Text("Then there's nothing to configure", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "OpenLink picks up the new address by itself and the parent's app remembers it. " +
                    "Do this once while both phones are together on your home Wi-Fi, and open " +
                    "the parent's app before you go out, so it has the new address ready.",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(24.dp))
            Text("This phone's addresses", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            when {
                serverStatus.lastError != null -> Text(
                    "Protection isn't running, so this phone has no address right now.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )

                endpoints.isEmpty() -> Text(
                    "No addresses yet. Join Wi-Fi and make sure protection is running.",
                    style = MaterialTheme.typography.bodySmall
                )

                else -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            endpoints.forEach { endpoint ->
                                Text(
                                    endpoint,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (isOverlayAddress(endpoint)) {
                                    Text(
                                        "Tailscale address — works from anywhere",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                } else {
                                    Text(
                                        "Home Wi-Fi address — works at home only",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (hasOverlayAddress) {
                            "Tailscale is working on this phone. Open the parent's app once " +
                                "while both phones are on your home Wi-Fi and it will pick this " +
                                "address up."
                        } else {
                            "No Tailscale address yet. Once Tailscale is installed here and " +
                                "signed in, an address starting with 100. will appear in this " +
                                "list."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * True for the 100.64.0.0/10 range that Tailscale (and WireGuard setups that follow its
 * convention) hands out. Purely cosmetic: it only decides which caption a row gets, so a
 * misjudged address costs a wrong label and nothing else.
 */
private fun isOverlayAddress(endpoint: String): Boolean {
    if (endpoint.startsWith("[")) return false
    val host = endpoint.substringBeforeLast(':')
    val parts = host.split('.')
    if (parts.size != 4) return false
    val first = parts[0].toIntOrNull() ?: return false
    val second = parts[1].toIntOrNull() ?: return false
    return first == 100 && second in 64..127
}
