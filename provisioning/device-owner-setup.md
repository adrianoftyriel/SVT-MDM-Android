# Device Owner setup (full tier) — Phase 3

> **Not yet implemented.** This document outlines the intended provisioning
> flow. The set-password capability and silent permission grants that Device
> Owner unlocks are Phase 3 work.

Device Owner is the only mode that can push a **new** lock password on modern
Android, and it grants usage access / package queries silently. It requires a
one-time setup on a **freshly factory-reset phone with no accounts added yet**.

## Enrollment via ADB (no QR)

1. Factory reset the phone. During setup, **skip adding any Google account**.
2. Enable Developer options → USB debugging, connect via USB.
3. Install the APK:
   ```bash
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
4. Promote to Device Owner (fails if any account exists on the device):
   ```bash
   adb shell dpm set-device-owner org.svt.mdm/.admin.MdmDeviceAdminReceiver
   ```
5. Open the app and enroll as in `light-install.md`. The capability probe will
   report `device_owner: true`, and the dashboard will enable `set_password`.

## Enrollment via QR (alternative)

On the very first setup screen of a freshly reset phone, tapping 6 times opens
the QR provisioning flow. A `qr-provision.json` payload (Phase 3) will point the
device at a hosted APK and set the Device Owner component automatically.

## What Device Owner adds over the light tier

- `set_password` — push a new lock-screen password.
- Silent `usage_access` and `query_all_packages` grants (no user prompts).
- Stronger lockdown options (disable factory reset, camera, etc.) — future.
