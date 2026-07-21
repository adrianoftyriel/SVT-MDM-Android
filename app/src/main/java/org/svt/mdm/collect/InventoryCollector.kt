package org.svt.mdm.collect

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.svt.mdm.transport.dto.AppEntry

/** Enumerates installed packages into the inventory wire format. */
class InventoryCollector(private val context: Context) {

    fun collect(): List<AppEntry> {
        val pm = context.packageManager
        val packages = pm.getInstalledPackages(0)
        return packages.map { info ->
            val appInfo = info.applicationInfo
            AppEntry(
                pkg = info.packageName,
                label = appInfo?.let { pm.getApplicationLabel(it).toString() },
                version = info.versionName,
                system = appInfo != null &&
                    (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            )
        }.sortedBy { it.label ?: it.pkg }
    }
}
