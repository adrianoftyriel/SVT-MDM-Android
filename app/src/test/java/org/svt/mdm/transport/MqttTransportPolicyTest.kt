package org.svt.mdm.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.svt.mdm.transport.dto.MqttInfo
import org.svt.mdm.transport.mqtt.MqttTransport

class MqttTransportPolicyTest {

    private fun info(tls: Boolean) = MqttInfo(
        host = "broker.example.com",
        tls = tls,
        username = "dev",
        password = "secret",
        cmdTopic = "mdm/dev/cmd",
        ackTopic = "mdm/dev/ack",
        statusTopic = "mdm/dev/status",
    )

    @Test
    fun tlsBrokerIsAlwaysAllowed() {
        assertTrue(MqttTransport.isTransportAllowed(info(true), "https://mdm.example.com", false))
    }

    @Test
    fun plainBrokerIsRefusedInRelease() {
        assertFalse(MqttTransport.isTransportAllowed(info(false), "https://mdm.example.com", false))
        assertFalse(MqttTransport.isTransportAllowed(info(false), "http://192.168.1.5:8099", false))
    }

    @Test
    fun plainBrokerNeedsDebugBuildAndCleartextServerUrl() {
        assertTrue(MqttTransport.isTransportAllowed(info(false), "http://192.168.1.5:8099", true))
        assertFalse(MqttTransport.isTransportAllowed(info(false), "https://mdm.example.com", true))
        assertFalse(MqttTransport.isTransportAllowed(info(false), null, true))
    }
}
