# Light install (Device Admin tier) — no factory reset

This is the no-reset path. You get location, app inventory, usage stats,
force-lock, and factory-wipe. You do **not** get remote *set-a-new-password*
(that needs Device Owner — see `device-owner-setup.md`).

## Steps

1. **Build & install the APK.**
   Open the project in Android Studio and Run, or:
   ```bash
   ./gradlew assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
   (You may need to allow "Install unknown apps" for your file manager.)

2. **Create the device on the server.** In the SVT MDM dashboard, add a device
   and copy the one-time **enrollment token**.

3. **Enroll.** Open the app, enter:
   - **Server URL** — your Home Assistant base URL for the add-on ingress, or a
     directly reachable `https://…` endpoint.
   - **Enrollment token** — from step 2.
   - **Enrollment secret** — the value set in the add-on configuration.
   Tap **Enroll**.

4. **Grant permissions** on the Status screen, in order:
   - **Grant location & notifications** — choose *Allow* for location.
   - **App settings → background location** — set location to *Allow all the
     time* so tracking works when the app is closed.
   - **Grant usage access** — toggle SVT MDM on in the Usage access list.
   - **Enable device admin** — confirm the device-admin prompt (unlocks
     lock/wipe).

5. **Start the agent.** Tap **Start / restart agent**. A persistent
   "Device management active" notification confirms the foreground service is
   running. The dashboard should show the device checking in.

## Notes

- **Battery optimisation:** for reliable background location, exempt the app
  from battery optimisation (Settings → Apps → SVT MDM → Battery → Unrestricted).
- **Usage access without prompts:** a future Shizuku integration (Phase 3) can
  grant usage access without the manual toggle.
