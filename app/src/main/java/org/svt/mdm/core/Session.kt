package org.svt.mdm.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.svt.mdm.transport.ApiClientFactory
import org.svt.mdm.transport.dto.MqttInfo

/**
 * Persistent, encrypted enrollment state: the server URL, device id, the
 * long-lived device token, and MQTT connection details. Backed by
 * EncryptedSharedPreferences so the token is not stored in the clear.
 */
class Session(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "svt_mdm_session",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, null)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var deviceId: String?
        get() = prefs.getString(KEY_DEVICE_ID, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    var deviceToken: String?
        get() = prefs.getString(KEY_DEVICE_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    var mqtt: MqttInfo?
        get() = prefs.getString(KEY_MQTT, null)?.let {
            runCatching { ApiClientFactory.json.decodeFromString<MqttInfo>(it) }.getOrNull()
        }
        set(value) {
            val encoded = value?.let { ApiClientFactory.json.encodeToString(it) }
            prefs.edit().putString(KEY_MQTT, encoded).apply()
        }

    val isEnrolled: Boolean
        get() = !deviceToken.isNullOrBlank() && !serverUrl.isNullOrBlank()

    fun save(serverUrl: String, deviceId: String, deviceToken: String, mqtt: MqttInfo) {
        prefs.edit()
            .putString(KEY_SERVER_URL, serverUrl)
            .putString(KEY_DEVICE_ID, deviceId)
            .putString(KEY_DEVICE_TOKEN, deviceToken)
            .putString(KEY_MQTT, ApiClientFactory.json.encodeToString(mqtt))
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    /** Device Owner reset-password token (32 bytes), base64-encoded at rest. */
    var resetPasswordToken: ByteArray?
        get() = prefs.getString(KEY_RESET_TOKEN, null)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }
        set(value) {
            val encoded = value?.let {
                android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP)
            }
            prefs.edit().putString(KEY_RESET_TOKEN, encoded).apply()
        }

    // --- QR provisioning hand-off (values passed via the admin extras bundle) ---

    val pendingServerUrl: String? get() = prefs.getString(KEY_PENDING_URL, null)
    val pendingEnrollToken: String? get() = prefs.getString(KEY_PENDING_TOKEN, null)
    val pendingSecret: String? get() = prefs.getString(KEY_PENDING_SECRET, null)

    fun savePendingProvisioning(serverUrl: String?, enrollToken: String?, secret: String?) {
        prefs.edit()
            .putString(KEY_PENDING_URL, serverUrl)
            .putString(KEY_PENDING_TOKEN, enrollToken)
            .putString(KEY_PENDING_SECRET, secret)
            .apply()
    }

    fun clearPendingProvisioning() {
        prefs.edit()
            .remove(KEY_PENDING_URL)
            .remove(KEY_PENDING_TOKEN)
            .remove(KEY_PENDING_SECRET)
            .apply()
    }

    private companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_MQTT = "mqtt"
        const val KEY_RESET_TOKEN = "reset_password_token"
        const val KEY_PENDING_URL = "pending_server_url"
        const val KEY_PENDING_TOKEN = "pending_enroll_token"
        const val KEY_PENDING_SECRET = "pending_secret"
    }
}
