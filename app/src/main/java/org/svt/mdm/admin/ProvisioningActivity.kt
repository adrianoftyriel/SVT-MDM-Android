package org.svt.mdm.admin

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log
import org.svt.mdm.core.Session

/**
 * Required for a DPC targeting Android 10+ (API 29). The provisioning flow
 * launches this to:
 *  - GET_PROVISIONING_MODE: declare we want a fully-managed device (Device Owner)
 *  - ADMIN_POLICY_COMPLIANCE: finalize after provisioning (capture the
 *    enrollment hand-off and self-provision)
 * Without these activities the QR flow fails with "Something went wrong".
 */
class ProvisioningActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            DevicePolicyManager.ACTION_GET_PROVISIONING_MODE -> {
                val result = Intent().putExtra(
                    DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE,
                )
                setResult(RESULT_OK, result)
            }
            DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE -> {
                captureHandoffAndProvision()
                setResult(RESULT_OK)
            }
            else -> setResult(RESULT_CANCELED)
        }
        finish()
    }

    private fun captureHandoffAndProvision() {
        @Suppress("DEPRECATION")
        val extras = intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE
        )
        if (extras != null) {
            Session(this).savePendingProvisioning(
                serverUrl = extras.getString("server_url"),
                enrollToken = extras.getString("enroll_token"),
                secret = extras.getString("enrollment_secret"),
            )
            Log.i(TAG, "Captured provisioning hand-off")
        }
        runCatching { DevicePolicyController(this).provisionSelf() }
    }

    private companion object {
        const val TAG = "ProvisioningActivity"
    }
}
