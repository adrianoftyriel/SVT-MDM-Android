package org.svt.mdm

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import org.svt.mdm.work.TelemetryWorker

class MdmApp : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleTelemetry()
    }

    private fun scheduleTelemetry() {
        val request = PeriodicWorkRequestBuilder<TelemetryWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            TelemetryWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
