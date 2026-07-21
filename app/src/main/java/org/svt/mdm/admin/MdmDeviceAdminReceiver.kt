package org.svt.mdm.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * Device Admin (and, when provisioned, Device Owner) receiver. Its presence and
 * active state is what unlocks force-lock and wipe. Callbacks are left as the
 * framework defaults for now.
 */
class MdmDeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, MdmDeviceAdminReceiver::class.java)
    }
}
