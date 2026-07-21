package org.svt.mdm.admin

import android.app.admin.DevicePolicyManager
import android.content.Context

/**
 * Thin wrapper over DevicePolicyManager for the admin actions this agent
 * performs. Lock and wipe work for a Device Admin; setting a new password only
 * works as Device Owner (implemented in Phase 3).
 */
class DevicePolicyController(private val context: Context) {

    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = MdmDeviceAdminReceiver.componentName(context)

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
        dpm.wipeData(0)
    }

    /**
     * Set a new lock password. Only functional as Device Owner on modern
     * Android; Phase 3 implements this via a reset-password token. For the
     * light (Device Admin) tier it is unsupported by design.
     */
    fun setPassword(@Suppress("UNUSED_PARAMETER") password: String): Boolean {
        // Intentionally unimplemented for Phase 2. See Phase 3.
        return false
    }
}
