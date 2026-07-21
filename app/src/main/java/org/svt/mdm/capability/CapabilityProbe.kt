package org.svt.mdm.capability

import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import androidx.core.content.ContextCompat
import org.svt.mdm.admin.MdmDeviceAdminReceiver

/**
 * Inspects the privilege tier the agent is running in and reports it as the
 * capability map the server uses to gate commands (see shared/protocol.md).
 *
 * The server maps these booleans to a coarse tier
 * (device_owner > device_admin > plain).
 */
class CapabilityProbe(private val context: Context) {

    fun probe(): Map<String, Boolean> {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = MdmDeviceAdminReceiver.componentName(context)

        val deviceOwner = dpm.isDeviceOwnerApp(context.packageName)
        val deviceAdmin = dpm.isAdminActive(admin)

        return mapOf(
            "device_owner" to deviceOwner,
            "device_admin" to (deviceAdmin || deviceOwner),
            "shizuku" to false, // wired up in Phase 3
            "usage_access" to hasUsageAccess(),
            "location" to hasLocation(),
            "query_all_packages" to canQueryPackages(),
        )
    }

    private fun hasLocation(): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    private fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun canQueryPackages(): Boolean =
        runCatching {
            context.packageManager.getInstalledPackages(0).size > 1
        }.getOrDefault(false)
}
