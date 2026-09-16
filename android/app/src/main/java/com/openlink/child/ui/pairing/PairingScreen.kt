package com.openlink.child.ui.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openlink.child.pairing.PairingSession
import com.openlink.child.pairing.PairingUri
import com.openlink.child.pairing.ParentRegistry
import com.openlink.child.pairing.QrCodeEncoder
import com.openlink.child.prefs.SecurePrefs
import com.openlink.child.security.TlsIdentity
import com.openlink.child.server.ServerState
import com.openlink.child.service.MonitorForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class PairingPayload(
    val uri: String,
    val fingerprintHex: String,
    val qr: ImageBitmap?
)

/**
 * Pairing, serverless edition: the child shows a QR, the parent scans it. There is no code to
 * type, no address to enter, and nothing to sign up for.
 *
 * The QR carries the device id, name, current endpoints, the TLS certificate fingerprint the
 * parent will pin forever, and a single-use `psk` that is minted when this screen opens and
 * destroyed when it closes. That last part is what makes the device's one unauthenticated route
 * safe to expose: outside this screen, `POST /pair` has nothing to check a proof against and
 * refuses everything.
 */
@Composable
fun PairingScreen(onPaired: () -> Unit, onSkip: (() -> Unit)? = null) {
    val context = LocalContext.current
    val prefs = remember { SecurePrefs(context) }
    val registry = remember { ParentRegistry(context) }

    val serverStatus by ServerState.snapshot.collectAsState()

    var payload by remember { mutableStateOf<PairingPayload?>(null) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var secondsRemaining by remember { mutableIntStateOf(0) }
    var paired by remember { mutableStateOf(false) }

    val parentCountAtEntry = remember { registry.parents().size }

    // The listener lives in the foreground service, and the QR can't be built until it has
    // actually bound a port -- the parent needs somewhere to connect.
    LaunchedEffect(Unit) { MonitorForegroundService.start(context) }

    // Close the pairing window when the screen goes away, not just on expiry.
    DisposableEffect(Unit) {
        onDispose { PairingSession.end() }
    }

    LaunchedEffect(serverStatus.port, serverStatus.endpoints, refreshTick) {
        val port = serverStatus.port ?: return@LaunchedEffect
        payload = withContext(Dispatchers.Default) {
            val identity = TlsIdentity.loadOrCreate()
            val psk = PairingSession.begin()
            val uri = PairingUri.build(
                deviceId = prefs.deviceId(),
                deviceName = prefs.deviceName(),
                fingerprintBase64Url = identity.fingerprintBase64Url,
                pskBase64Url = psk,
                endpoints = serverStatus.endpoints
            )
            PairingPayload(
                uri = uri,
                fingerprintHex = identity.fingerprintHex,
                qr = QrCodeEncoder.encode(uri, QR_SIZE_PX)?.asImageBitmap()
            )
        }
        // Seed the countdown before the ticker's first pass, so the QR isn't rendered as
        // "expired" for one frame.
        secondsRemaining = PairingSession.expiresAtMillis()
            ?.let { ((it - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L).toInt() }
            ?: 0
    }

    // One ticker drives both the countdown and the "did someone just pair?" check. Polling the
    // registry once a second is cheap and avoids wiring a callback out of the listener into the UI.
    LaunchedEffect(payload) {
        if (payload == null) return@LaunchedEffect
        while (true) {
            val expiresAt = PairingSession.expiresAtMillis()
            secondsRemaining = if (expiresAt == null) 0
            else ((expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L).toInt()

            if (registry.parents().size > parentCountAtEntry) {
                paired = true
                return@LaunchedEffect
            }
            delay(1_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Pair with a parent", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Open OpenLink on the parent's phone and scan this code. Both phones need to be on " +
                "the same Wi-Fi right now.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))

        when {
            paired -> PairedConfirmation(onPaired)

            serverStatus.lastError != null -> ErrorPanel(serverStatus.lastError!!) { refreshTick++ }

            serverStatus.port == null -> WaitingPanel()

            serverStatus.endpoints.isEmpty() -> ErrorPanel(
                "This device isn't on a network, so there's no address for a parent to connect " +
                    "to. Join Wi-Fi and try again."
            ) { refreshTick++ }

            payload == null -> WaitingPanel()

            else -> QrPanel(
                payload = payload!!,
                secondsRemaining = secondsRemaining,
                endpoints = serverStatus.endpoints,
                onRefresh = { refreshTick++ }
            )
        }

        if (onSkip != null && !paired) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onSkip) { Text("Do this later") }
        }
    }
}

@Composable
private fun QrPanel(
    payload: PairingPayload,
    secondsRemaining: Int,
    endpoints: List<String>,
    onRefresh: () -> Unit
) {
    val expired = secondsRemaining <= 0

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val qr = payload.qr
            if (qr != null && !expired) {
                Image(
                    bitmap = qr,
                    contentDescription = "Pairing QR code",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
            } else if (expired) {
                Text(
                    "This code has expired.",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 48.dp)
                )
            } else {
                // Encoding failed -- fall back to the raw URI so pairing is still possible.
                Text(
                    payload.uri,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Text(
        if (expired) "Generate a new code to try again."
        else "Expires in ${secondsRemaining / 60}:${"%02d".format(secondsRemaining % 60)}",
        style = MaterialTheme.typography.bodyMedium
    )

    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) {
        Text("Generate a new code")
    }

    Spacer(Modifier.height(24.dp))
    Text("This device is reachable at", style = MaterialTheme.typography.titleSmall)
    endpoints.forEach { endpoint ->
        Text(endpoint, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }

    Spacer(Modifier.height(16.dp))
    Text("Certificate fingerprint", style = MaterialTheme.typography.titleSmall)
    Text(
        payload.fingerprintHex,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "The parent's app pins this fingerprint. If it ever shows something different, stop and " +
            "pair again in person.",
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun WaitingPanel() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text("Starting the connection…", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorPanel(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun PairedConfirmation(onContinue: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "Paired.",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "The parent's app is connected to this device. Nothing was sent anywhere else.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
    }
}

private const val QR_SIZE_PX = 640
