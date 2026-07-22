# Device Owner setup (full tier)

Device Owner is the only mode that can push a **new** lock password on modern
Android, and it lets the agent grant its own runtime permissions silently. It
requires a **one-time** setup on a **freshly factory-reset phone with no
accounts added yet**.

Two ways to provision: QR (easiest) or ADB.

## Option A — QR provisioning (recommended)

1. In the add-on config, set **`external_url`** to the https URL the phone will
   reach the server at (e.g. `https://mdm.example.com`). `apk_url` defaults to
   the latest GitHub release APK; override it if you host the APK elsewhere.
2. In the dashboard, **add a device**. Its detail page shows a **Device Owner
   provisioning QR**.
3. **Factory reset** the phone. On the very first setup screen, **tap the screen
   6 times** to open the QR provisioning scanner.
4. Scan the QR. Android downloads the agent APK, verifies its signing
   certificate against the embedded checksum, installs it as **Device Owner**,
   and passes the enrollment details through. The app **auto-enrolls**.
5. Done — the device reports `device_owner`, and `set_password` is available.

The signing-certificate checksum baked into the server default is:

```
QGFnYMwe0rezuujokGa9CLb6pJXweG47KqQg6r81ctg
```

If provisioning ever rejects the APK signature (e.g. after re-keying), recompute
it from the built APK with:

```bash
apksigner verify --print-certs app-release.apk   # "Signer #1 certificate SHA-256 digest"
# then base64url-encode those 32 bytes and set the do_signature_checksum option
```

## Option B — ADB provisioning

1. Factory reset; during setup **skip adding any Google account**.
2. Enable Developer options → USB debugging; connect via USB.
3. Install the release APK:
   ```bash
   adb install app-release.apk
   ```
4. Promote to Device Owner (fails if any account exists on the device):
   ```bash
   adb shell dpm set-device-owner org.svt.mdm/.admin.MdmDeviceAdminReceiver
   ```
5. Open the app and enroll as in `light-install.md`. It will report
   `device_owner: true`.

## What Device Owner unlocks

- **`set_password`** — pushes a new lock-screen password via a reset-password
  token established at provisioning. (If a secure lock already exists and the
  token isn't active, the command reports a clear failure — provision before
  setting a lock.)
- **Silent permission grants** — location, media, contacts, notifications are
  granted without prompts, so location/inventory/usage/backup work immediately.

## Notes

- The reset-password token is stored in the app's encrypted storage. Setting a
  password uses `resetPasswordWithToken`, which needs that token to be active.
- Removing Device Owner requires a factory reset (or `adb shell dpm
  remove-active-admin`), by design.
