package org.svt.mdm.transport.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire types mirroring `shared/protocol.md` in the server repo. Keep field
 * names (via @SerialName) in sync with that contract.
 */

@Serializable
data class EnrollRequest(
    @SerialName("enroll_token") val enrollToken: String,
    @SerialName("enrollment_secret") val enrollmentSecret: String? = null,
    val name: String? = null,
    val platform: String = "android",
    val model: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    val capabilities: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class MqttInfo(
    val host: String? = null,
    val port: Int = 1883,
    val tls: Boolean = false,
    val username: String,
    val password: String,
    @SerialName("cmd_topic") val cmdTopic: String,
    @SerialName("ack_topic") val ackTopic: String,
    @SerialName("status_topic") val statusTopic: String,
)

@Serializable
data class EnrollResponse(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_token") val deviceToken: String,
    val mqtt: MqttInfo,
)

@Serializable
data class CheckinRequest(
    val battery: Int? = null,
    @SerialName("os_version") val osVersion: String? = null,
    val model: String? = null,
    val capabilities: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class LocationRequest(
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Double? = null,
    @SerialName("captured_at") val capturedAt: String? = null,
)

@Serializable
data class AppEntry(
    @SerialName("package") val pkg: String,
    val label: String? = null,
    val version: String? = null,
    val system: Boolean = false,
)

@Serializable
data class InventoryRequest(
    @SerialName("captured_at") val capturedAt: String? = null,
    val apps: List<AppEntry> = emptyList(),
)

@Serializable
data class UsageEntry(
    @SerialName("package") val pkg: String,
    @SerialName("foreground_ms") val foregroundMs: Long = 0,
    @SerialName("last_used") val lastUsed: String? = null,
)

@Serializable
data class UsageRequest(
    @SerialName("captured_at") val capturedAt: String? = null,
    @SerialName("range_days") val rangeDays: Int = 7,
    val stats: List<UsageEntry> = emptyList(),
)

@Serializable
data class Command(
    val id: String,
    val type: String,
    val payload: JsonObject = JsonObject(emptyMap()),
    @SerialName("issued_at") val issuedAt: String? = null,
)

@Serializable
data class PendingCommands(
    val commands: List<Command> = emptyList(),
)

@Serializable
data class CommandAck(
    val id: String,
    val status: String, // "acked" | "failed"
    val detail: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

/** Generic `{ "ok": true, ... }` telemetry response. */
@Serializable
data class OkResponse(
    val ok: Boolean = true,
    val tier: String? = null,
    val count: Int? = null,
)
