package org.svt.mdm.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.svt.mdm.core.Agent

/**
 * Periodic bulk telemetry: check-in, installed-app inventory, and usage stats.
 * Runs less frequently than the live location stream (see MdmApp for the
 * schedule). Also drains any queued commands as an MQTT-independent fallback.
 */
class TelemetryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val agent = Agent(applicationContext)
        if (!agent.session.isEnrolled) return Result.success()

        return try {
            agent.checkin()
            agent.pushInventory()
            runCatching { agent.pushUsage() } // needs usage-access grant; ignore if absent
            agent.drainPendingCommands { ack -> agent.sendAck(ack) }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "svt_mdm_telemetry"
    }
}
