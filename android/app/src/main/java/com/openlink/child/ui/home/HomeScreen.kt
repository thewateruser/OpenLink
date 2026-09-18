package com.openlink.child.ui.home

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openlink.child.data.AppDatabase
import com.openlink.child.data.PolicyEntity
import com.openlink.child.data.TimeRequestEntity
import com.openlink.child.data.UsageEntity
import com.openlink.child.domain.ChildRepository
import com.openlink.child.enforcement.AlwaysAllowed
import com.openlink.child.pairing.ParentRegistry
import com.openlink.child.server.ServerState
import com.openlink.child.ui.requesttime.RequestTimeDialog
import com.openlink.child.util.todayDateString
import kotlinx.coroutines.launch

/**
 * Shown while no parent is linked.
 *
 * Limits on this device are set from a parent's phone, so before pairing there is genuinely
 * nothing to adjust here -- but a screen that shows usage and no controls, with no explanation,
 * looks like an app that forgot to include them. This says where the controls live and offers
 * the one action that gets them.
 */
@Composable
private fun UnpairedNotice(onShowPairingCode: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "No parent phone linked yet",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Time limits, blocked apps and downtime are all set from a parent's iPhone. " +
                    "Until one is linked, this phone just keeps track of how long each app is " +
                    "used — nothing is restricted.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onShowPairingCode) { Text("Show pairing code") }
        }
    }
}

data class AppUsageRow(
    val packageName: String,
    val appName: String,
    val minutesUsed: Int,
    val effectiveLimitMinutes: Int?,
    val blocked: Boolean
)

/**
 * The child's own view: what they've used today, what the limits are, and a way to ask for more.
 *
 * Every number here comes straight out of the local database, because that database is now the
 * only place any of it exists. Nothing on this screen depends on a parent being reachable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    initialRequestPackage: String? = null,
    onOpenSettings: () -> Unit = {},
    onShowPairingCode: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val repository = remember { ChildRepository.getInstance(context) }
    val today = remember { todayDateString() }
    val parentRegistry = remember { ParentRegistry(context) }

    val policies by db.policyDao().observeAll().collectAsState(initial = emptyList())
    val usage by db.usageDao().observeForDate(today).collectAsState(initial = emptyList())
    val requests by db.requestDao().observeAll().collectAsState(initial = emptyList())
    val serverStatus by ServerState.snapshot.collectAsState()

    var requestDialogPackage by remember { mutableStateOf(initialRequestPackage) }

    // Re-read on every recomposition rather than remembering: returning from the pairing screen
    // should flip this without needing the screen to be recreated.
    val isPaired = parentRegistry.isPaired()

    val rows = remember(policies, usage, requests) { buildRows(context, policies, usage, requests, today) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenLink") },
                actions = { TextButton(onClick = onOpenSettings) { Text("Settings") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Text(
                when {
                    !serverStatus.running -> "Protection is not running."
                    !isPaired -> "Waiting to be linked to a parent's phone."
                    serverStatus.connectedParents > 0 ->
                        "A parent's app is connected right now."
                    else -> "Protection is running. No parent is connected."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 0.dp)
            )

            // Without this, an unpaired device is just a usage list with no controls and no
            // explanation of where controls would come from, which reads as a broken app.
            if (!isPaired) {
                UnpairedNotice(onShowPairingCode = onShowPairingCode)
            }

            if (requests.isNotEmpty()) {
                Text(
                    "Recent requests",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    requests.take(3).forEach { request ->
                        val grantedSuffix = request.grantedMinutes?.let { " (granted ${it}m)" } ?: ""
                        Text(
                            "${request.appName ?: request.packageName}: " +
                                "${request.minutesRequested}m requested - ${request.status}$grantedSuffix",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Text(
                "Today's usage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp)
            )
            if (rows.isEmpty()) {
                Text(
                    "No usage recorded yet today.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rows, key = { it.packageName }) { row ->
                    AppUsageRowView(row, onRequestMoreTime = { requestDialogPackage = row.packageName })
                    Divider()
                }
            }
        }
    }

    requestDialogPackage?.let { packageName ->
        RequestTimeDialog(
            packageName = packageName,
            onDismiss = { requestDialogPackage = null },
            onSubmit = { minutes, message ->
                scope.launch {
                    // Always succeeds: the row is written locally and waits for a parent. The
                    // old server-backed version could silently drop a request when offline.
                    repository.createRequest(packageName, minutes, message)
                    requestDialogPackage = null
                }
            }
        )
    }
}

private fun buildRows(
    context: Context,
    policies: List<PolicyEntity>,
    usage: List<UsageEntity>,
    requests: List<TimeRequestEntity>,
    today: String
): List<AppUsageRow> {
    val pm = context.packageManager
    val usageByPackage = usage.associateBy { it.packageName }
    val policiesByPackage = policies.associateBy { it.packageName }

    // Extra minutes approved today, summed per package -- the same "effective limit" the
    // enforcement engine applies, so the progress bar matches what actually blocks.
    val grantedToday = requests
        .filter { it.status == ChildRepository.STATUS_APPROVED && it.respondedAt?.startsWith(today) == true }
        .groupBy { it.packageName }
        .mapValues { (_, list) -> list.sumOf { it.grantedMinutes ?: 0 } }

    val allPackages = (policies.map { it.packageName } + usage.map { it.packageName }).toSet()

    return allPackages
        .filterNot { AlwaysAllowed.isAlwaysAllowed(context, it) }
        .map { packageName ->
            val policy = policiesByPackage[packageName]
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                packageName
            }
            AppUsageRow(
                packageName = packageName,
                appName = appName,
                minutesUsed = usageByPackage[packageName]?.minutesUsed ?: 0,
                effectiveLimitMinutes = policy?.dailyLimitMinutes?.plus(grantedToday[packageName] ?: 0),
                blocked = policy?.blocked ?: false
            )
        }
        .sortedByDescending { it.minutesUsed }
}

@Composable
private fun AppUsageRowView(row: AppUsageRow, onRequestMoreTime: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp, 10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(row.appName, style = MaterialTheme.typography.bodyLarge)
            Text(
                if (row.effectiveLimitMinutes != null) "${row.minutesUsed}m / ${row.effectiveLimitMinutes}m"
                else "${row.minutesUsed}m",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (row.blocked) {
            Text("Blocked by parent", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else if (row.effectiveLimitMinutes != null && row.effectiveLimitMinutes > 0) {
            Spacer(Modifier.height(6.dp))
            val fraction = (row.minutesUsed.toFloat() / row.effectiveLimitMinutes.toFloat())
                .coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRequestMoreTime) { Text("Ask for more time") }
    }
}
