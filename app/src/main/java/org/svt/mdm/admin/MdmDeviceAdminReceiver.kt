package org.svt.mdm.admin

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import org.svt.mdm.core.Session

/**
 * Device Admin / Device Owner receiver. Its active state unlocks force-lock and
 * wipe; as Device Owner it also enables set-password and silent grants.
 */
class MdmDeviceAdminReceiver : DeviceAdminReceiver() {

    /**
     * Called after QR / NFC / ADB Device Owner provisioning. Reads the
     * enrollment hand-off from the admin extras bundle (server URL + one-time
     * token + secret embedded in the QR), stashes it for the app to auto-enroll,
     * and self-provisions (silent grants + reset-password token).
     */
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        @Suppress("DEPRECATION")
        val extras = intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
        )
        if (extras != null) {
            Session(context).savePendingProvisioning(
                serverUrl = extras.getString("server_url"),
                enrollToken = extras.getString("enroll_token"),
                secret = extras.getString("enrollment_secret"),
            )
            Log.i(TAG, "Stored provisioning hand-off from admin extras")
        }
        runCatching { DevicePolicyController(context).provisionSelf() }
    }

    companion object {
        private const val TAG = "MdmDeviceAdminReceiver"

        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, MdmDeviceAdminReceiver::class.java)
    }
}
