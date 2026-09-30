package org.svt.mdm.transport.mqtt

import android.util.Log
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.svt.mdm.transport.ApiClientFactory.json
import org.svt.mdm.transport.dto.Command
import org.svt.mdm.transport.dto.CommandAck
import org.svt.mdm.transport.dto.MqttInfo

/**
 * Maintains the device's MQTT command channel: subscribes to the command
 * topic, hands decoded commands to [onCommand], and publishes acks and a
 * retained presence status. Uses HiveMQ's auto-reconnect; because a reconnect
 * starts a clean session, the subscription and "online" status are re-sent from
 * a connected listener that fires after every successful connect.
 */
class MqttTransport(
    private val info: MqttInfo,
    private val onCommand: (Command) -> Unit,
) {
    private var client: Mqtt3AsyncClient? = null

    fun connect() {
        val host = info.host ?: run {
            Log.w(TAG, "No MQTT host; command channel disabled (polling only).")
            return
        }
        val builder = Mqtt3Client.builder()
            .identifier(info.username)
            .serverHost(host)
            .serverPort(info.port)
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener { onConnected() }
        if (info.tls) builder.sslWithDefaultConfig()

        val c = builder.buildAsync()
        client = c

        // Registered once; it keeps receiving across reconnects.
        c.publishes(MqttGlobalPublishFilter.ALL) { publish ->
            val text = String(publish.payloadAsBytes)
            val cmd = runCatching { json.decodeFromString<Command>(text) }.getOrNull()
            if (cmd != null) onCommand(cmd)
            else Log.w(TAG, "Dropping unparseable command")
        }

        c.connectWith()
            .simpleAuth()
                .username(info.username)
                .password(info.password.toByteArray())
                .applySimpleAuth()
            .willPublish()
                .topic(info.statusTopic)
                .payload(statusPayload(false))
                .retain(true)
                .applyWillPublish()
            .send()
            .whenComplete { _, throwable ->
                if (throwable != null) {
                    Log.w(TAG, "MQTT connect failed: ${throwable.message}")
                }
            }
    }

    private fun onConnected() {
        val c = client ?: return
        c.subscribeWith()
            .topicFilter(info.cmdTopic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .send()
            .whenComplete { _, throwable ->
                if (throwable != null) Log.w(TAG, "MQTT subscribe failed: ${throwable.message}")
            }
        publishStatus(true)
    }

    fun publishAck(ack: CommandAck) {
        client?.publishWith()
            ?.topic(info.ackTopic)
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.payload(json.encodeToString(ack).toByteArray())
            ?.send()
    }

    fun publishStatus(online: Boolean) {
        client?.publishWith()
            ?.topic(info.statusTopic)
            ?.qos(MqttQos.AT_LEAST_ONCE)
            ?.retain(true)
            ?.payload(statusPayload(online))
            ?.send()
    }

    fun disconnect() {
        runCatching {
            publishStatus(false)
            client?.disconnect()
        }
        client = null
    }

    private fun statusPayload(online: Boolean) = "{\"online\":$online}".toByteArray()

    companion object {
        private const val TAG = "MqttTransport"

        /**
         * The device token is the MQTT password, so the broker connection must
         * be TLS. Plain MQTT is only tolerated in a debuggable build whose
         * server URL is itself explicit cleartext http://.
         */
        fun isTransportAllowed(info: MqttInfo, serverUrl: String?, debuggable: Boolean): Boolean =
            info.tls ||
                (debuggable && serverUrl?.trim()?.startsWith("http://", ignoreCase = true) == true)
    }
}
