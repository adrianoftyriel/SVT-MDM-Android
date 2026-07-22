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
import androidx.compose.runtime.LaunchedEffect
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
import org.svt.mdm.update.UpdateManager

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

    // As Device Owner, silently grant permissions / set the reset-password
    // token as soon as the app opens.
    LaunchedEffect(Unit) { runCatching { if (agent.isDeviceOwner()) agent.provisionSelf() } }

    if (enrolled) {
        StatusScreen(agent, onUnenroll = {
            agent.session.clear()
            enrolled = false
        })
    } else {
        EnrollmentScreen(agent, onEnrolled = {
            agent.session.clearPendingProvisioning()
            enrolled = true
        })
    }
}

@Composable
private fun EnrollmentScreen(agent: Agent, onEnrolled: () -> Unit) {
    // Pre-fill from a QR provisioning hand-off when present.
    var serverUrl by remember { mutableStateOf(agent.session.pendingServerUrl ?: "") }
    var token by remember { mutableStateOf(agent.session.pendingEnrollToken ?: "") }
    var secret by remember { mutableStateOf(agent.session.pendingSecret ?: "") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun doEnroll() {
        busy = true
        status = "Enrolling…"
        scope.launch {
            val result = agent.enroll(serverUrl.trim(), token.trim(), secret.trim())
            busy = false
            result.onSuccess { onEnrolled() }
                .onFailure { status = "Failed: ${it.message}" }
        }
    }

    // Auto-enroll once if a provisioning hand-off pre-filled everything.
    LaunchedEffect(Unit) {
        if (!agent.session.pendingEnrollToken.isNullOrBlank() &&
            !agent.session.pendingServerUrl.isNullOrBlank()
        ) {
            doEnroll()
        }
    }

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
            onClick = { doEnroll() },
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

    var backupStatus by remember { mutableStateOf<String?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var availableUpdate by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    val updater = remember { UpdateManager(context) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { caps = agent.capabilities() }

    val backupLauncher = rememberLauncherForActivityResult(
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
                val perms = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(android.Manifest.permission.READ_MEDIA_IMAGES)
                        add(android.Manifest.permission.READ_MEDIA_VIDEO)
                        add(android.Manifest.permission.READ_MEDIA_AUDIO)
                    } else {
                        add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                    add(android.Manifest.permission.READ_CONTACTS)
                }.toTypedArray()
                backupLauncher.launch(perms)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Grant backup access (media & contacts)") }

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
        Button(
            onClick = {
                backupStatus = "Backing up… (this can take a while)"
                scope.launch {
                    val result = runCatching { agent.runBackup() }
                    backupStatus = result.fold(
                        onSuccess = { "Backup OK — ${it.fileCount} files, ${it.uploaded} uploaded" },
                        onFailure = { "Backup failed: ${it.message ?: it.javaClass.simpleName}" },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Back up now") }
        backupStatus?.let { Text(it) }

        Button(
            onClick = {
                updateStatus = "Checking…"
                availableUpdate = null
                scope.launch {
                    runCatching { updater.check() }.fold(
                        onSuccess = { info ->
                            if (info == null) {
                                updateStatus = "Up to date (v${updater.currentVersion})"
                            } else {
                                availableUpdate = info
                                updateStatus = "Update available: v${info.latest} (installed v${info.current})"
                            }
                        },
                        onFailure = { updateStatus = "Update check failed: ${it.message}" },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Check for updates") }
        availableUpdate?.let { info ->
            Button(
                onClick = {
                    updateStatus = "Downloading & installing…"
                    scope.launch {
                        runCatching { updater.downloadAndInstall(info) }
                            .onFailure { updateStatus = "Update failed: ${it.message}" }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Download & install v${info.latest}") }
        }
        updateStatus?.let { Text(it) }

        Button(onClick = onUnenroll, modifier = Modifier.fillMaxWidth()) { Text("Unenroll") }
    }
}

private fun tierOf(caps: Map<String, Boolean>): String = when {
    caps["device_owner"] == true -> "device_owner"
    caps["device_admin"] == true -> "device_admin"
    caps["location"] == true || caps["query_all_packages"] == true -> "plain"
    else -> "unknown"
}
