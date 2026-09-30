package org.svt.mdm.transport.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the wire format matches shared/protocol.md — field names via
 * @SerialName, defaults, and round-trip fidelity.
 */
class DtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun enrollRequestSerializesWithCorrectFieldNames() {
        val req = EnrollRequest(
            enrollToken = "tok123",
            enrollmentSecret = "s3cret",
            name = "Test Phone",
            platform = "android",
            model = "Pixel 7",
            osVersion = "14",
            capabilities = mapOf("device_admin" to true, "location" to true),
        )
        val encoded = json.encodeToString(EnrollRequest.serializer(), req)
        val decoded = json.decodeFromString(EnrollRequest.serializer(), encoded)
        assertEquals(req, decoded)
        // Verify wire field names
        assertTrue(encoded.contains("\"enroll_token\":\"tok123\""))
        assertTrue(encoded.contains("\"enrollment_secret\":\"s3cret\""))
        assertTrue(encoded.contains("\"os_version\":\"14\""))
    }

    @Test
    fun enrollRequestDefaultsPlatformToAndroid() {
        val req = EnrollRequest(enrollToken = "tok")
        assertEquals("android", req.platform)
    }

    @Test
    fun enrollResponseRoundTrips() {
        val resp = EnrollResponse(
            deviceId = "dev-1",
            deviceToken = "token-abc",
            mqtt = MqttInfo(
                host = "mqtt.example.com",
                port = 8883,
                tls = true,
                username = "dev-1",
                password = "token-abc",
                cmdTopic = "mdm/dev-1/cmd",
                ackTopic = "mdm/dev-1/ack",
                statusTopic = "mdm/dev-1/status",
            ),
        )
        val encoded = json.encodeToString(EnrollResponse.serializer(), resp)
        val decoded = json.decodeFromString(EnrollResponse.serializer(), encoded)
        assertEquals(resp, decoded)
    }

    @Test
    fun mqttInfoSerializesWithCorrectFieldNames() {
        val info = MqttInfo(
            host = "broker",
            port = 1883,
            tls = false,
            username = "user",
            password = "pass",
            cmdTopic = "mdm/u/cmd",
            ackTopic = "mdm/u/ack",
            statusTopic = "mdm/u/status",
        )
        val encoded = json.encodeToString(MqttInfo.serializer(), info)
        assertTrue(encoded.contains("\"cmd_topic\":\"mdm/u/cmd\""))
        assertTrue(encoded.contains("\"ack_topic\":\"mdm/u/ack\""))
        assertTrue(encoded.contains("\"status_topic\":\"mdm/u/status\""))
    }

    @Test
    fun commandAckRoundTrips() {
        val ack = CommandAck(
            id = "cmd-1",
            status = "acked",
            detail = null,
            completedAt = "2026-09-30T12:00:00Z",
        )
        val encoded = json.encodeToString(CommandAck.serializer(), ack)
        val decoded = json.decodeFromString(CommandAck.serializer(), encoded)
        assertEquals("cmd-1", decoded.id)
        assertEquals("acked", decoded.status)
        assertNull(decoded.detail)
    }

    @Test
    fun commandAckWithFailureDetailRoundTrips() {
        val ack = CommandAck(
            id = "cmd-2",
            status = "failed",
            detail = "Device Owner required",
            completedAt = "2026-09-30T12:00:01Z",
        )
        val encoded = json.encodeToString(CommandAck.serializer(), ack)
        val decoded = json.decodeFromString(CommandAck.serializer(), encoded)
        assertEquals("failed", decoded.status)
        assertEquals("Device Owner required", decoded.detail)
    }

    @Test
    fun locationRequestRoundTrips() {
        val req = LocationRequest(
            lat = 51.5074,
            lon = -0.1278,
            accuracyM = 10.5,
            capturedAt = "2026-09-30T12:00:00Z",
        )
        val encoded = json.encodeToString(LocationRequest.serializer(), req)
        val decoded = json.decodeFromString(LocationRequest.serializer(), encoded)
        assertEquals(51.5074, decoded.lat, 0.0001)
        assertEquals(-0.1278, decoded.lon, 0.0001)
        assertEquals(10.5, decoded.accuracyM!!, 0.1)
    }

    @Test
    fun checkinRequestRoundTrips() {
        val req = CheckinRequest(
            battery = 82,
            osVersion = "14",
            model = "Pixel 7",
            capabilities = mapOf("location" to true, "query_all_packages" to true),
        )
        val encoded = json.encodeToString(CheckinRequest.serializer(), req)
        val decoded = json.decodeFromString(CheckinRequest.serializer(), encoded)
        assertEquals(82, decoded.battery)
        assertEquals("14", decoded.osVersion)
        assertTrue(decoded.capabilities["location"]!!)
    }

    @Test
    fun okResponseRoundTrips() {
        val resp = OkResponse(
            ok = true,
            tier = "device_admin",
            count = null,
            theme = "midnight",
        )
        val encoded = json.encodeToString(OkResponse.serializer(), resp)
        val decoded = json.decodeFromString(OkResponse.serializer(), encoded)
        assertTrue(decoded.ok)
        assertEquals("device_admin", decoded.tier)
        assertEquals("midnight", decoded.theme)
    }

    @Test
    fun backupConfigResponseRoundTrips() {
        val resp = BackupConfigResponse(
            categories = mapOf(
                "media" to true,
                "contacts" to true,
                "sms" to false,
                "calllog" to false,
                "calendar" to false,
            ),
        )
        val encoded = json.encodeToString(BackupConfigResponse.serializer(), resp)
        val decoded = json.decodeFromString(BackupConfigResponse.serializer(), encoded)
        assertTrue(decoded.categories["media"]!!)
        assertTrue(decoded.categories["contacts"]!!)
        assertEquals(false, decoded.categories["sms"]!!)
    }

    @Test
    fun themeResponseRoundTrips() {
        val resp = ThemeResponse(
            id = "lcars",
            name = "LCARS",
            dark = true,
            font = "condensed",
            colors = mapOf(
                "bg" to "#000000",
                "accent" to "#ff9900",
                "text" to "#ffcc99",
            ),
        )
        val encoded = json.encodeToString(ThemeResponse.serializer(), resp)
        val decoded = json.decodeFromString(ThemeResponse.serializer(), encoded)
        assertEquals("lcars", decoded.id)
        assertEquals("condensed", decoded.font)
        assertEquals("#ff9900", decoded.colors["accent"])
    }
}
