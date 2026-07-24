package org.svt.mdm.core

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import java.time.Instant
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.svt.mdm.admin.DevicePolicyController
import org.svt.mdm.backup.BackupManager
import org.svt.mdm.backup.BackupSummary
import org.svt.mdm.capability.CapabilityProbe
import org.svt.mdm.collect.InventoryCollector
import org.svt.mdm.collect.LocationCollector
import org.svt.mdm.collect.UsageCollector
import org.svt.mdm.ring.Ringer
import org.svt.mdm.transport.ApiClientFactory
import org.svt.mdm.transport.MdmApi
import org.svt.mdm.transport.dto.CheckinRequest
import org.svt.mdm.transport.dto.Command
import org.svt.mdm.transport.dto.CommandAck
import org.svt.mdm.transport.dto.EnrollRequest
import org.svt.mdm.transport.dto.EnrollResponse
import org.svt.mdm.transport.dto.InventoryRequest
import org.svt.mdm.transport.dto.LocationRequest
import org.svt.mdm.transport.dto.UsageRequest

/**
 * The heart of the agent: enrollment, telemetry pushes, and command dispatch.
 * Stateless beyond the [Session]; safe to instantiate wherever a Context is
 * available (service, worker, activity).
 */
class Agent(private val context: Context) {

    val session = Session(context)
    private val probe = CapabilityProbe(context)
    private val controller = DevicePolicyController(context)
    private val locations = LocationCollector(context)
    private val inventory = InventoryCollector(context)
    private val usage = UsageCollector(context)

    private fun api(): MdmApi {
        val url = requireNotNull(session.serverUrl) { "Not enrolled" }
        return ApiClientFactory.create(url) { session.deviceToken }
    }

    fun capabilities(): Map<String, Boolean> = probe.probe()

    // -- enrollment -----------------------------------------------------------

    suspend fun enroll(
        serverUrl: String,
        enrollToken: String,
        enrollmentSecret: String?,
    ): Result<EnrollResponse> = runCatching {
        val api = ApiClientFactory.create(serverUrl) { null }
        val response = api.enroll(
            EnrollRequest(
                enrollToken = enrollToken,
                enrollmentSecret = enrollmentSecret?.ifBlank { null },
                name = Build.MODEL,
                platform = "android",
                model = Build.MODEL,
                osVersion = Build.VERSION.RELEASE,
                capabilities = probe.probe(),
            )
        )
        session.save(serverUrl, response.deviceId, response.deviceToken, response.mqtt)
        // As Device Owner, silently grant permissions and set up the
        // reset-password token so set_password works.
        runCatching { controller.provisionSelf() }
        response
    }

    fun isDeviceOwner(): Boolean = controller.isDeviceOwner

    fun provisionSelf() = controller.provisionSelf()

    // -- telemetry ------------------------------------------------------------

    suspend fun checkin() {
        val response = api().checkin(
            CheckinRequest(
                battery = readBattery(),
                osVersion = Build.VERSION.RELEASE,
                model = Build.MODEL,
                capabilities = probe.probe(),
            )
        )
        // The server echoes the operator-selected interface theme; persist it
        // so the UI can restyle to match the dashboard.
        response.theme?.let { session.themeId = it }
    }

    /** Fetch the active interface theme id from the server. */
    suspend fun fetchTheme(): String {
        val theme = api().theme()
        session.themeId = theme.id
        return theme.id
    }

    suspend fun pushLocation() {
        val loc = locations.current() ?: error("No location fix available")
        api().location(
            LocationRequest(
                lat = loc.latitude,
                lon = loc.longitude,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                capturedAt = Instant.ofEpochMilli(loc.time).toString(),
            )
        )
    }

    suspend fun pushInventory() {
        api().inventory(InventoryRequest(apps = inventory.collect()))
    }

    suspend fun pushUsage(days: Int = 7) {
        api().usage(UsageRequest(rangeDays = days, stats = usage.collect(days)))
    }

    suspend fun runBackup(): BackupSummary = BackupManager(context, api()).run()

    // -- command dispatch -----------------------------------------------------

    suspend fun handleCommand(cmd: Command): CommandAck = try {
        when (cmd.type) {
            "locate" -> { pushLocation(); ok(cmd) }
            "lock" -> { controller.lockNow(); ok(cmd) }
            "wipe" -> {
                val confirmed = cmd.payload["confirm"]?.jsonPrimitive?.booleanOrNull == true
                if (confirmed) { controller.wipe(); ok(cmd) }
                else failed(cmd, "wipe requires confirm=true")
            }
            "set_password" -> {
                val pw = cmd.payload["password"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (controller.setPassword(pw)) ok(cmd)
                else failed(cmd, "set_password requires Device Owner (Phase 3)")
            }
            "refresh_inventory" -> { pushInventory(); ok(cmd) }
            "refresh_usage" -> {
                val days = cmd.payload["days"]?.jsonPrimitive?.intOrNull ?: 7
                pushUsage(days); ok(cmd)
            }
            "backup_now" -> { runBackup(); ok(cmd) }
            "ring" -> {
                val seconds = (cmd.payload["seconds"]?.jsonPrimitive?.intOrNull ?: 30)
                    .coerceIn(1, 300)
                Ringer(context).ring(seconds * 1000L)
                ok(cmd)
            }
            else -> failed(cmd, "unknown command type: ${cmd.type}")
        }
    } catch (e: Exception) {
        failed(cmd, e.message ?: e.javaClass.simpleName)
    }

    /** Poll for queued commands (fallback when MQTT is unavailable). */
    suspend fun drainPendingCommands(send: suspend (CommandAck) -> Unit) {
        val pending = api().pendingCommands().commands
        for (cmd in pending) {
            send(handleCommand(cmd))
        }
    }

    suspend fun sendAck(ack: CommandAck) {
        runCatching { api().ackCommand(ack) }
    }

    private fun ok(cmd: Command) =
        CommandAck(id = cmd.id, status = "acked", completedAt = Instant.now().toString())

    private fun failed(cmd: Command, detail: String) =
        CommandAck(id = cmd.id, status = "failed", detail = detail,
            completedAt = Instant.now().toString())

    private fun readBattery(): Int? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return level?.takeIf { it in 0..100 }
    }
}
