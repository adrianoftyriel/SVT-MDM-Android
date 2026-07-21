package org.svt.mdm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.svt.mdm.MainActivity
import org.svt.mdm.R
import org.svt.mdm.core.Agent
import org.svt.mdm.transport.dto.CommandAck
import org.svt.mdm.transport.mqtt.MqttTransport

/**
 * Long-lived foreground service. Holds the MQTT command channel for instant
 * commands and pushes a periodic location stream. Bulk telemetry (inventory,
 * usage) is handled separately by [org.svt.mdm.work.TelemetryWorker].
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var agent: Agent
    private var mqtt: MqttTransport? = null

    override fun onCreate() {
        super.onCreate()
        agent = Agent(this)
        // Android 10+ wants the foreground-service type at startForeground time,
        // and Android 14 enforces it. The manifest declares type "location".
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!agent.session.isEnrolled) {
            stopSelf()
            return START_NOT_STICKY
        }
        connectMqtt()
        startLocationLoop()
        startCommandPollLoop()
        return START_STICKY
    }

    private fun connectMqtt() {
        if (mqtt != null) return
        val info = agent.session.mqtt ?: return
        mqtt = MqttTransport(info) { command ->
            // Handle each command off the MQTT callback thread.
            scope.launch {
                val ack: CommandAck = agent.handleCommand(command)
                mqtt?.publishAck(ack)
            }
        }.also { it.connect() }
    }

    private fun startLocationLoop() {
        scope.launch {
            runCatching { agent.checkin() }
            while (isActive) {
                runCatching { agent.pushLocation() }
                    .onFailure { Log.w(TAG, "location push failed: ${it.message}") }
                delay(LOCATION_INTERVAL_MS)
            }
        }
    }

    /**
     * Collect and execute queued commands over HTTPS. This is the primary
     * command channel: it works through the same reverse proxy as the rest of
     * the API, so it does not require the MQTT broker to be reachable from the
     * device. When MQTT push is enabled and connected, commands simply arrive
     * sooner and are already marked delivered, so they won't be polled twice.
     */
    private fun startCommandPollLoop() {
        scope.launch {
            while (isActive) {
                runCatching {
                    agent.drainPendingCommands { ack -> agent.sendAck(ack) }
                }.onFailure { Log.w(TAG, "command poll failed: ${it.message}") }
                delay(COMMAND_POLL_INTERVAL_MS)
            }
        }
    }

    override fun onDestroy() {
        mqtt?.disconnect()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.agent_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
        }
        val contentIntent = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText(getString(R.string.agent_notification_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "svt_mdm_agent"
        private const val NOTIFICATION_ID = 1001
        private const val LOCATION_INTERVAL_MS = 5 * 60 * 1000L
        private const val COMMAND_POLL_INTERVAL_MS = 15 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, AgentService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
