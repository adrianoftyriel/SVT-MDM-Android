package org.svt.mdm.ring

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Target of the "Stop ringing" notification action. */
class RingStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Ringer.stop()
    }
}
