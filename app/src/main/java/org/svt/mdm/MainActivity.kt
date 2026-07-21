package org.svt.mdm

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.svt.mdm.admin.MdmDeviceAdminReceiver
import org.svt.mdm.core.Agent
import org.svt.mdm.service.AgentService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val agent = remember { Agent(context) }
    var enrolled by remember { mutableStateOf(agent.session.isEnrolled) }

    if (enrolled) {
        StatusScreen(agent, onUnenroll = {
            agent.session.clear()
            enrolled = false
        })
    } else {
        // Don't auto-start the location foreground service here: on Android 14+
        // starting a location-typed FGS before the location permission is
        // granted throws. The user starts it from the Status screen after
        // granting permissions.
        EnrollmentScreen(agent, onEnrolled = { enrolled = true })
    }
}

@Composable
private fun EnrollmentScreen(agent: Agent, onEnrolled: () -> Unit) {
    var serverUrl by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Enroll device", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = serverUrl, onValueChange = { serverUrl = it },
            label = { Text("Server URL (https://…)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it },
            label = { Text("Enrollment token") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = secret, onValueChange = { secret = it },
            label = { Text("Enrollment secret") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && serverUrl.isNotBlank() && token.isNotBlank(),
            onClick = {
                busy = true
                status = "Enrolling…"
                scope.launch {
                    val result = agent.enroll(serverUrl.trim(), token.trim(), secret.trim())
                    busy = false
                    result.onSuccess { onEnrolled() }
                        .onFailure { status = "Failed: ${it.message}" }
                }
            },
        ) { Text("Enroll") }
        status?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun StatusScreen(agent: Agent, onUnenroll: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var caps by remember { mutableStateOf(agent.capabilities()) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { caps = agent.capabilities() }

    fun refresh() {
        caps = agent.capabilities()
        scope.launch { runCatching { agent.checkin() } }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Enrolled", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("App version: $appVersion")
                Text("Device ID: ${agent.session.deviceId ?: "—"}", fontFamily = FontFamily.Monospace)
                Text("Server: ${agent.session.serverUrl ?: "—"}")
                Text("Tier: ${tierOf(caps)}", style = MaterialTheme.typography.titleMedium)
                caps.forEach { (k, v) -> Text("• $k: ${if (v) "yes" else "no"}") }
            }
        }

        Text("Permissions & privileges", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = {
                val perms = buildList {
                    add(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
                locationLauncher.launch(perms)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Grant location & notifications") }

        Button(
            onClick = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Grant usage access") }

        Button(
            onClick = {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        MdmDeviceAdminReceiver.componentName(context),
                    )
                    .putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        context.getString(R.string.device_admin_description),
                    )
                context.startActivity(intent)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Enable device admin (lock / wipe)") }

        Button(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("App settings (background location)") }

        Text("Agent", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = { AgentService.start(context); refresh() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Start / restart agent") }
        Button(onClick = { refresh() }, modifier = Modifier.fillMaxWidth()) {
            Text("Re-check capabilities")
        }
        Button(
            onClick = {
                syncStatus = "Syncing…"
                scope.launch {
                    val result = runCatching {
                        agent.checkin()
                        var count = 0
                        agent.drainPendingCommands { ack ->
                            count++
                            agent.sendAck(ack)
                        }
                        count
                    }
                    syncStatus = result.fold(
                        onSuccess = { "Sync OK — ran $it queued command(s)" },
                        onFailure = { "Sync failed: ${it.message ?: it.javaClass.simpleName}" },
                    )
                    caps = agent.capabilities()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Sync now (run queued commands)") }
        syncStatus?.let { Text(it) }
        Button(onClick = onUnenroll, modifier = Modifier.fillMaxWidth()) { Text("Unenroll") }
    }
}

private fun tierOf(caps: Map<String, Boolean>): String = when {
    caps["device_owner"] == true -> "device_owner"
    caps["device_admin"] == true -> "device_admin"
    caps["location"] == true || caps["query_all_packages"] == true -> "plain"
    else -> "unknown"
}
