package org.svt.mdm.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.svt.mdm.core.Agent
import retrofit2.HttpException

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
        } catch (e: HttpException) {
            // A revoked/invalid token (401/403) will not fix itself on retry.
            if (e.code() == 401 || e.code() == 403) Result.failure() else retryOrGiveUp()
        } catch (e: Exception) {
            retryOrGiveUp()
        }
    }

    /** Retry transient failures a few times; the next periodic run tries again. */
    private fun retryOrGiveUp(): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        private const val MAX_ATTEMPTS = 5
        const val UNIQUE_NAME = "svt_mdm_telemetry"
    }
}
