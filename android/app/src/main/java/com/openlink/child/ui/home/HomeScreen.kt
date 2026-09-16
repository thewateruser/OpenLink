package com.openlink.child.ui.home

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.LaunchedEffect
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
import com.openlink.child.data.UsageEntity
import com.openlink.child.data.toEntity
import com.openlink.child.enforcement.AlwaysAllowed
import com.openlink.child.enforcement.EnforcementRepository
import com.openlink.child.network.NetworkModule
import com.openlink.child.network.OpenLinkApi
import com.openlink.child.network.model.InstalledApp
import com.openlink.child.network.model.SyncAppsRequest
import com.openlink.child.network.model.TimeRequestCreate
import com.openlink.child.ui.requesttime.RequestTimeDialog
import com.openlink.child.util.todayDateString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppUsageRow(
    val packageName: String,
    val appName: String,
    val minutesUsed: Int,
    val effectiveLimitMinutes: Int?,
    val blocked: Boolean
)

/** Status/home screen: installed apps with today's usage + effective limit (item 8 of the spec). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(initialRequestPackage: String? = null, onOpenSettings: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val today = remember { todayDateString() }

    val policies by db.policyDao().observeAll().collectAsState(initial = emptyList())
    val usage by db.usageDao().observeForDate(today).collectAsState(initial = emptyList())
    val requests by db.requestDao().observeAll().collectAsState(initial = emptyList())
    val isLocked by EnforcementRepository.lockState.collectAsState()

    var requestDialogPackage by remember { mutableStateOf(initialRequestPackage) }

    val rows = remember(policies, usage) { buildRows(context, policies, usage) }

    // Best-effort catalog sync so the parent app can show names instead of raw package ids
    // (POST /device/apps). Failing silently here is fine: MonitorForegroundService's fallback
    // polling loop and the next heartbeat don't depend on this succeeding.
    LaunchedEffect(Unit) {
        try {
            val apps = installedApps(context)
            NetworkModule.buildRetrofit(context).create(OpenLinkApi::class.java)
                .syncApps(SyncAppsRequest(apps))
        } catch (e: Exception) {
            // offline at startup; will retry the next time this screen is shown
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenLink") },
                actions = { TextButton(onClick = onOpenSettings) { Text("Settings") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLocked) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "This device is locked by a parent.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            if (requests.isNotEmpty()) {
                Text(
                    "Recent requests",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)
                )
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    requests.take(3).forEach { req ->
                        val grantedSuffix = req.grantedMinutes?.let { " (granted ${it}m)" } ?: ""
                        Text(
                            "${req.packageName}: ${req.minutesRequested}m requested - ${req.status}$grantedSuffix",
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

    requestDialogPackage?.let { pkg ->
        RequestTimeDialog(
            packageName = pkg,
            onDismiss = { requestDialogPackage = null },
            onSubmit = { minutes, message ->
                scope.launch {
                    try {
                        val api = NetworkModule.buildRetrofit(context).create(OpenLinkApi::class.java)
                        val dto = api.createTimeRequest(TimeRequestCreate(pkg, minutes, message.ifBlank { null }))
                        db.requestDao().upsert(dto.toEntity())
                    } catch (e: Exception) {
                        // Offline: the request wasn't created server-side. MVP simplification --
                        // a production app would queue this locally and retry.
                    }
                    requestDialogPackage = null
                }
            }
        )
    }
}

private suspend fun installedApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .filter { app ->
            (app.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || pm.getLaunchIntentForPackage(app.packageName) != null
        }
        .map { InstalledApp(it.packageName, pm.getApplicationLabel(it).toString()) }
}

private fun buildRows(context: Context, policies: List<PolicyEntity>, usage: List<UsageEntity>): List<AppUsageRow> {
    val pm = context.packageManager
    val usageByPackage = usage.associateBy { it.packageName }
    val allPackages = (policies.map { it.packageName } + usage.map { it.packageName }).toSet()

    return allPackages
        .filterNot { AlwaysAllowed.isAlwaysAllowed(context, it) }
        .map { pkg ->
            val policy = policies.find { it.packageName == pkg }
            val minutesUsed = usageByPackage[pkg]?.minutesUsed ?: 0
            val appName = policy?.appName ?: try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                pkg
            }
            AppUsageRow(
                packageName = pkg,
                appName = appName,
                minutesUsed = minutesUsed,
                effectiveLimitMinutes = policy?.dailyLimitMinutes,
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
            LinearProgressIndicator(
                progress = (row.minutesUsed.toFloat() / row.effectiveLimitMinutes.toFloat()).coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = onRequestMoreTime) { Text("Ask for more time") }
    }
}
