package org.svt.mdm.core

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.svt.mdm.admin.DevicePolicyController
import org.svt.mdm.admin.DevicePolicyController.SetPasswordResult
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
import retrofit2.HttpException

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

    private class Outcome(val ack: CommandAck, val afterAck: (() -> Unit)? = null)

    /**
     * Run [cmd] and hand its ack to [deliver]. A command id that was already
     * executed is not run again; its recorded ack is re-delivered instead
     * (the server re-hands out commands whose ack it never received).
     * Quick commands run one at a time; `backup_now` runs in the background so
     * it neither blocks the poll loop nor delays a `lock`.
     */
    suspend fun processCommand(cmd: Command, deliver: suspend (CommandAck) -> Unit) {
        cachedAck(cmd.id)?.let { deliver(it); return }

        if (cmd.type in LONG_RUNNING) {
            if (!inFlight.add(cmd.id)) return // already running; it acks on completion
            backgroundScope.launch {
                try {
                    val ack = backgroundMutex.withLock {
                        cachedAck(cmd.id) ?: execute(cmd).ack.also { remember(it) }
                    }
                    deliver(ack)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "background command ${cmd.type} failed to finish: ${e.message}")
                } finally {
                    inFlight.remove(cmd.id)
                }
            }
            return
        }

        val outcome = commandMutex.withLock {
            val cached = cachedAck(cmd.id)
            if (cached != null) Outcome(cached) else execute(cmd).also { remember(it.ack) }
        }
        deliver(outcome.ack)
        outcome.afterAck?.let { action ->
            delay(AFTER_ACK_GRACE_MS)
            action()
        }
    }

    private suspend fun execute(cmd: Command): Outcome {
        expiredDetail(cmd)?.let { return Outcome(failed(cmd, it)) }
        return try {
            when (cmd.type) {
                "locate" -> { pushLocation(); Outcome(ok(cmd)) }
                "lock" -> { controller.lockNow(); Outcome(ok(cmd)) }
                "wipe" -> {
                    val confirmed = cmd.payload["confirm"]?.jsonPrimitive?.booleanOrNull == true
                    when {
                        !confirmed -> Outcome(failed(cmd, "wipe requires confirm=true"))
                        !controller.isAdminActive -> Outcome(failed(cmd, "device admin not active"))
                        // The ack must reach the server before the device is erased.
                        else -> Outcome(ok(cmd), afterAck = { controller.wipe() })
                    }
                }
                "set_password" -> {
                    val pw = cmd.payload["password"]?.jsonPrimitive?.contentOrNull
                    if (pw.isNullOrEmpty()) {
                        Outcome(failed(cmd, "set_password requires a non-empty password"))
                    } else {
                        Outcome(setPasswordAck(cmd, controller.setPassword(pw)))
                    }
                }
                "refresh_inventory" -> { pushInventory(); Outcome(ok(cmd)) }
                "refresh_usage" -> {
                    val days = cmd.payload["days"]?.jsonPrimitive?.intOrNull ?: 7
                    pushUsage(days); Outcome(ok(cmd))
                }
                "backup_now" -> { runBackup(); Outcome(ok(cmd)) }
                "ring" -> {
                    val seconds = (cmd.payload["seconds"]?.jsonPrimitive?.intOrNull ?: 30)
                        .coerceIn(1, 300)
                    Ringer(context).start(seconds * 1000L)
                    Outcome(ok(cmd))
                }
                "stop_ring" -> { Ringer.stop(); Outcome(ok(cmd)) }
                else -> Outcome(failed(cmd, "unknown command type: ${cmd.type}"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome(failed(cmd, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun setPasswordAck(cmd: Command, result: SetPasswordResult): CommandAck =
        when (result) {
            SetPasswordResult.OK -> ok(cmd)
            SetPasswordResult.EMPTY ->
                failed(cmd, "set_password requires a non-empty password")
            SetPasswordResult.NOT_DEVICE_OWNER ->
                failed(cmd, "set_password requires Device Owner; this device is not Device Owner")
            SetPasswordResult.NO_TOKEN ->
                failed(cmd, "could not create a reset-password token on this device")
            SetPasswordResult.TOKEN_INACTIVE ->
                failed(cmd, "reset-password token is not active yet; confirm the current screen lock on the device once, then retry")
            SetPasswordResult.REJECTED ->
                failed(cmd, "the device rejected the password (complexity rules?) or the reset failed")
        }

    private fun expiredDetail(cmd: Command): String? {
        if (cmd.type !in EXPIRING) return null
        val issued = parseIssuedAt(cmd.issuedAt) ?: return null
        val age = Duration.between(issued, Instant.now())
        return if (age > COMMAND_TTL) {
            "command expired: issued ${age.toMinutes()} min ago (limit ${COMMAND_TTL.toMinutes()} min)"
        } else {
            null
        }
    }

    private fun cachedAck(id: String): CommandAck? {
        synchronized(memoryAcks) { memoryAcks[id] }?.let { return it }
        val persisted = session.recentAcks().firstOrNull { it.id == id } ?: return null
        synchronized(memoryAcks) { memoryAcks[id] = persisted }
        return persisted
    }

    private fun remember(ack: CommandAck) {
        synchronized(memoryAcks) { memoryAcks[ack.id] = ack }
        session.rememberAck(ack)
    }

    /** Poll for queued commands (fallback when MQTT is unavailable). */
    suspend fun drainPendingCommands(send: suspend (CommandAck) -> Unit) {
        val pending = api().pendingCommands().commands
        for (cmd in pending) {
            processCommand(cmd, send)
        }
    }

    /** Send [ack] over HTTPS, retrying transient failures. Returns whether it was accepted. */
    suspend fun sendAck(ack: CommandAck): Boolean {
        var wait = ACK_RETRY_BASE_MS
        for (attempt in 1..ACK_ATTEMPTS) {
            try {
                api().ackCommand(ack)
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                // A definite client error (bad token, unknown command) will not fix itself.
                if (e.code() in 400..499 && e.code() != 408 && e.code() != 429) return false
                Log.w(TAG, "ack attempt $attempt failed: HTTP ${e.code()}")
            } catch (e: Exception) {
                Log.w(TAG, "ack attempt $attempt failed: ${e.message}")
            }
            if (attempt < ACK_ATTEMPTS) {
                delay(wait)
                wait *= 2
            }
        }
        return false
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

    companion object {
        private const val TAG = "Agent"
        private const val ACK_ATTEMPTS = 4
        private const val ACK_RETRY_BASE_MS = 1_000L
        private const val AFTER_ACK_GRACE_MS = 2_000L
        private const val MAX_CACHED_ACKS = 100
        private val COMMAND_TTL: Duration = Duration.ofMinutes(10)

        /** Commands that must not fire long after they were issued. */
        private val EXPIRING = setOf("wipe", "lock", "set_password")
        private val LONG_RUNNING = setOf("backup_now")

        // Shared by every Agent instance (service, workers, UI) in this process.
        private val commandMutex = Mutex()
        private val backgroundMutex = Mutex()
        private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()
        private val memoryAcks = object : LinkedHashMap<String, CommandAck>(64, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CommandAck>?,
            ): Boolean = size > MAX_CACHED_ACKS
        }

        /**
         * Parses the server's `issued_at`. Accepts ISO-8601 with `Z`/offset, or a
         * zone-less timestamp (which the server emits today) treated as UTC.
         * Returns null when absent or unparseable; callers then allow the command.
         */
        internal fun parseIssuedAt(value: String?): Instant? {
            if (value.isNullOrBlank()) return null
            return runCatching { Instant.parse(value) }.getOrNull()
                ?: runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(value).toInstant(ZoneOffset.UTC) }.getOrNull()
        }
    }
}
