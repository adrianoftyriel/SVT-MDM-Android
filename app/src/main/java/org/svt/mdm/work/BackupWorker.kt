package org.svt.mdm.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.svt.mdm.core.Agent
import retrofit2.HttpException

/**
 * Periodic backup. Scheduled (see MdmApp) with unmetered-network + charging
 * constraints so it runs overnight without eating mobile data or battery.
 * On-demand backups come via the `backup_now` command instead.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val agent = Agent(applicationContext)
        if (!agent.session.isEnrolled) return Result.success()
        return try {
            agent.runBackup()
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
        const val UNIQUE_NAME = "svt_mdm_backup"
    }
}
