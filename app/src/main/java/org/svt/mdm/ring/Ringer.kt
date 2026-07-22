package org.svt.mdm.ring

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Plays a loud "find my phone" alarm that ignores silent/vibrate mode by using
 * the ALARM audio stream (which the ringer mode does not mute), at max alarm
 * volume, plus vibration. Restores the previous alarm volume afterwards.
 *
 * Caveat: Do-Not-Disturb can still silence alarms unless the user allows them;
 * overriding DND requires notification-policy access we don't request.
 */
class Ringer(private val context: Context) {

    suspend fun ring(durationMs: Long) {
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
        try {
            delay(durationMs)
        } finally {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            runCatching { vibrator?.cancel() }
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0) }
        }
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

    private companion object {
        const val TAG = "Ringer"
    }
}
