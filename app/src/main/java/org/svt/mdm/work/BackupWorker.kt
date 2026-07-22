package org.svt.mdm.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.svt.mdm.core.Agent

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
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_NAME = "svt_mdm_backup"
    }
}
