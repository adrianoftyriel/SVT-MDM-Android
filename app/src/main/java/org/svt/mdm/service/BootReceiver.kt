package org.svt.mdm.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.svt.mdm.core.Session

/** Restarts the agent service after a reboot, if the device is enrolled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Session(context).isEnrolled) {
            AgentService.start(context)
        }
    }
}
