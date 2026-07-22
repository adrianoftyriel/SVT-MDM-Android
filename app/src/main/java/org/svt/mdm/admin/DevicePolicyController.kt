package org.svt.mdm.admin

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.util.Log
import org.svt.mdm.core.Session

/**
 * Thin wrapper over DevicePolicyManager. Lock and wipe work for a Device Admin;
 * setting a new password and silently granting permissions require Device Owner
 * (Phase 3).
 */
class DevicePolicyController(private val context: Context) {

    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = MdmDeviceAdminReceiver.componentName(context)
    private val session = Session(context)

    val isAdminActive: Boolean get() = dpm.isAdminActive(admin)
    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)

    /** Force the device to lock, requiring the existing credential to unlock. */
    fun lockNow() {
        require(isAdminActive) { "Device admin not active" }
        dpm.lockNow()
    }

    /** Factory reset. Irreversible. */
    fun wipe() {
        require(isAdminActive) { "Device admin not active" }
        // wipeData() wipes "the user the admin runs on". On a Device Owner in
        // headless system user mode (Android 14+) that user is the system user
        // (user 0), which the framework refuses to remove ("User 0 is a system
        // user and cannot be removed"). wipeDevice() (API 34) factory-resets the
        // whole device from a Device Owner and is the correct call there.
        if (isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            dpm.wipeDevice(0)
        } else {
            dpm.wipeData(0)
        }
    }

    /**
     * Set a new lock password. Device Owner only: uses a reset-password token
     * established during provisioning. Returns false (with no change) otherwise.
     */
    fun setPassword(password: String): Boolean {
        if (!isDeviceOwner) return false
        ensureResetPasswordToken()
        val token = session.resetPasswordToken ?: return false
        if (!dpm.isResetPasswordTokenActive(admin)) {
            // The token needs the user to confirm the current credential once
            // before it can be used on a device that already has a lock set.
            Log.w(TAG, "Reset-password token not active; cannot set password yet")
            return false
        }
        return try {
            dpm.resetPasswordWithToken(admin, password, token, 0)
        } catch (e: Exception) {
            Log.w(TAG, "resetPasswordWithToken failed: ${e.message}")
            false
        }
    }

    /**
     * One-time self-provisioning as Device Owner: silently grant the runtime
     * permissions the agent needs, and establish a reset-password token.
     * No-op if we're not Device Owner. Idempotent.
     */
    fun provisionSelf() {
        if (!isDeviceOwner) return
        grantRuntimePermissions()
        ensureResetPasswordToken()
    }

    private fun grantRuntimePermissions() {
        val pkg = context.packageName
        val permissions = buildList {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            add(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            add(android.Manifest.permission.READ_CONTACTS)
            add(android.Manifest.permission.READ_SMS)
            add(android.Manifest.permission.READ_CALL_LOG)
            add(android.Manifest.permission.READ_CALENDAR)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.READ_MEDIA_IMAGES)
                add(android.Manifest.permission.READ_MEDIA_VIDEO)
                add(android.Manifest.permission.READ_MEDIA_AUDIO)
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        for (permission in permissions) {
            runCatching {
                dpm.setPermissionGrantState(
                    admin, pkg, permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }.onFailure { Log.w(TAG, "grant $permission failed: ${it.message}") }
        }
    }

    private fun ensureResetPasswordToken() {
        if (session.resetPasswordToken != null) return
        val token = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val ok = runCatching { dpm.setResetPasswordToken(admin, token) }.getOrDefault(false)
        if (ok) {
            session.resetPasswordToken = token
            Log.i(TAG, "Reset-password token established")
        } else {
            Log.w(TAG, "Could not set reset-password token")
        }
    }

    private companion object {
        const val TAG = "DevicePolicyController"
    }
}
