package org.svt.mdm.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.svt.mdm.MainActivity
import org.svt.mdm.R
import org.svt.mdm.core.Agent
import org.svt.mdm.core.Session
import org.svt.mdm.transport.mqtt.MqttTransport
import retrofit2.HttpException

/**
 * Long-lived foreground service. Holds the MQTT command channel for instant
 * commands and pushes a periodic location stream. Bulk telemetry (inventory,
 * usage) is handled separately by [org.svt.mdm.work.TelemetryWorker].
 */
class AgentService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var agent: Agent
    private var mqtt: MqttTransport? = null
    private var locationJob: Job? = null
    private var pollJob: Job? = null
    private var inForeground = false

    override fun onCreate() {
        super.onCreate()
        agent = Agent(this)
        inForeground = enterForeground()
    }

    /**
     * Android 14 throws SecurityException if a location-type foreground service
     * starts without a location permission, and Android 12+ refuses some
     * background starts. Returns false instead of crashing in either case.
     */
    private fun enterForeground(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!hasLocationPermission(this)) {
                Log.w(TAG, "No location permission; not starting foreground service")
                false
            } else {
                // The type is required at startForeground time on Android 10+
                // and enforced on 14; the manifest declares "location".
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
                true
            }
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
            true
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not enter foreground: ${e.message}")
        false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!inForeground || !agent.session.isEnrolled) {
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
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!MqttTransport.isTransportAllowed(info, agent.session.serverUrl, debuggable)) {
            // The device token is the MQTT password; never send it in clear text.
            Log.w(TAG, "MQTT broker is not TLS; instant commands disabled, polling only.")
            return
        }
        mqtt = MqttTransport(info) { command ->
            // Handle each command off the MQTT callback thread.
            scope.launch {
                runCatching {
                    agent.processCommand(command) { ack -> mqtt?.publishAck(ack) }
                }.onFailure { Log.w(TAG, "command ${command.type} failed: ${it.message}") }
            }
        }.also { it.connect() }
    }

    private fun startLocationLoop() {
        if (locationJob?.isActive == true) return
        locationJob = scope.launch {
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
     * device. Commands already run via MQTT are de-duplicated by id in
     * [Agent.processCommand], so they are never executed twice.
     */
    private fun startCommandPollLoop() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                var wait = COMMAND_POLL_INTERVAL_MS
                runCatching {
                    agent.drainPendingCommands { ack -> agent.sendAck(ack) }
                }.onFailure {
                    Log.w(TAG, "command poll failed: ${it.message}")
                    // A revoked token will not recover by polling every 15 s.
                    if (it is HttpException && it.code() == 401) wait = UNAUTHORIZED_BACKOFF_MS
                }
                delay(wait)
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
        private const val UNAUTHORIZED_BACKOFF_MS = 5 * 60 * 1000L

        private fun hasLocationPermission(context: Context): Boolean =
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ).any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }

        /**
         * Starts the service if the device is enrolled and a location permission
         * is held (required for the location-type foreground service). Returns
         * whether a start was requested.
         */
        fun start(context: Context): Boolean {
            if (!Session(context).isEnrolled) return false
            if (!hasLocationPermission(context)) {
                Log.w(TAG, "Location permission missing; agent service not started")
                return false
            }
            val intent = Intent(context, AgentService::class.java)
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: RuntimeException) {
                // e.g. ForegroundServiceStartNotAllowedException from the background
                Log.w(TAG, "Could not start agent service: ${e.message}")
                false
            }
        }
    }
}
