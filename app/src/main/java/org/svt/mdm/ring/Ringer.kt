package org.svt.mdm.ring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.svt.mdm.MainActivity

/**
 * Plays a loud "find my phone" alarm that ignores silent/vibrate mode by using
 * the ALARM audio stream (which the ringer mode does not mute), at max alarm
 * volume, plus vibration. Restores the previous alarm volume afterwards.
 *
 * [start] returns immediately; the alarm ends after the duration, when
 * [Ringer.stop] is called (remote `stop_ring` command, the "Stop ringing"
 * notification action, or opening the app), or when a new ring replaces it.
 *
 * Caveat: Do-Not-Disturb can still silence alarms unless the user allows them;
 * overriding DND requires notification-policy access we don't request.
 */
class Ringer(context: Context) {

    private val context: Context = context.applicationContext

    fun start(durationMs: Long) = synchronized(LOCK) {
        stopLocked()

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching {
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
                0,
            )
        }

        val player = startPlayer()
        val vibrator = startVibration()
        showNotification()

        val done = AtomicBoolean(false)
        val cleanup: () -> Unit = {
            if (done.compareAndSet(false, true)) {
                runCatching { player?.stop() }
                runCatching { player?.release() }
                runCatching { vibrator?.cancel() }
                runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0) }
                hideNotification()
            }
        }
        val job = scope.launch {
            try {
                delay(durationMs)
            } finally {
                synchronized(LOCK) {
                    if (active?.cleanup === cleanup) active = null
                }
                cleanup()
            }
        }
        active = Active(job, cleanup)
    }

    private fun startPlayer(): MediaPlayer? {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ?: return null
        return try {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not start alarm sound: ${e.message}")
            null
        }
    }

    private fun startVibration(): Vibrator? {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager)
                .defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        return try {
            // 500ms on / 500ms off, repeating from index 0.
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 500), 0))
            vibrator
        } catch (e: Exception) {
            Log.w(TAG, "Could not vibrate: ${e.message}")
            null
        }
    }

    /** Ongoing notification with a "Stop ringing" action that works on the lock screen. */
    private fun showNotification() {
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Find my phone", NotificationManager.IMPORTANCE_HIGH)
                    .apply { setSound(null, null) }
            )
            val stop = PendingIntent.getBroadcast(
                context, 0, Intent(context, RingStopReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            @Suppress("DEPRECATION")
            val action = Notification.Action.Builder(0, "Stop ringing", stop).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setAuthenticationRequired(false)
            }.build()
            val notification = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Phone is ringing")
                .setContentText("Tap Stop ringing to silence it")
                .setCategory(Notification.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(action)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "Could not show ring notification: ${it.message}") }
    }

    private fun hideNotification() {
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    private class Active(val job: Job, val cleanup: () -> Unit)

    companion object {
        private const val TAG = "Ringer"
        private const val CHANNEL_ID = "svt_find_phone"
        private const val NOTIFICATION_ID = 0x5EA7

        private val LOCK = Any()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        @Volatile
        private var active: Active? = null

        /** Silences the alarm if one is playing; safe to call at any time. */
        fun stop() = synchronized(LOCK) { stopLocked() }

        private fun stopLocked() {
            active?.let {
                it.job.cancel()
                it.cleanup()
            }
            active = null
        }
    }
}
