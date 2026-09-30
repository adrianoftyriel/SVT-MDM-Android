# SVT MDM — Android agent

The Android device agent for [SVT MDM](https://github.com/adrianoftyriel/svt-mdm-server).
Reports location, installed apps, and usage; receives lock / wipe / locate
commands from the server.

> **Status: Phase 2 (light / Device Admin tier).** Enrollment, telemetry
> (location, inventory, usage), instant commands over MQTT with an HTTPS polling
> fallback, and force-lock / wipe via Device Admin are implemented. Device Owner
> features (set-password, silent grants, QR provisioning) are Phase 3.

## Capability tiers

One APK adapts to the privilege it's granted and reports its tier to the server,
which only offers commands the device can run:

| Tier | Setup | Adds |
|------|-------|------|
| **plain** | install APK | location, inventory (usage needs a grant) |
| **device_admin** | enable Device Admin | + force-lock, factory wipe |
| **device_owner** | ADB provision on reset phone (Phase 3) | + set new password, silent grants |

See [`provisioning/light-install.md`](provisioning/light-install.md) for the
no-reset setup, and [`provisioning/device-owner-setup.md`](provisioning/device-owner-setup.md)
for the full tier.

## Architecture

```
MainActivity (Compose)        Agent (core)                     Server
  enroll / grant perms  ─────▶ enroll ──────────────────────▶ POST /api/enroll
                               │
AgentService (foreground) ────┤ MQTT cmd channel  ◀───────── mdm/<id>/cmd
  · MQTT command channel      │ handleCommand → ack ────────▶ mdm/<id>/ack
  · periodic location push    │ pushLocation ──────────────▶ POST /api/telemetry/location
                              │
TelemetryWorker (WorkManager) ┤ checkin / inventory / usage ▶ POST /api/telemetry/*
  every 6h                    │ drainPendingCommands ───────▶ GET /api/commands/pending
```

- **Instant commands** arrive over MQTT ([HiveMQ client](https://github.com/hivemq/hivemq-mqtt-client));
  a WorkManager job also polls `GET /api/commands/pending` as a fallback.
- The device token is the MQTT password, so the agent **only connects to a TLS
  broker** (`tls: true` in the enroll response). With a plain-MQTT broker it
  skips MQTT and relies on HTTPS polling (a debuggable build pointed at an
  explicit `http://` server URL is the only exception).
- Commands are de-duplicated by id (re-delivered commands are re-acked, not
  re-run), executed one at a time, and `wipe` / `lock` / `set_password` are
  rejected if issued more than 10 minutes earlier.
- **Telemetry** is posted over HTTPS.
- The device token is stored in `EncryptedSharedPreferences`.

## Module map

```
transport/          Retrofit API, DTOs (mirror server shared/protocol.md), MQTT client
core/               Session (encrypted storage) · Agent (enroll, telemetry, command dispatch)
capability/         CapabilityProbe — reports the privilege tier to the server
admin/              DeviceAdminReceiver + DevicePolicyController (lock, wipe)
collect/            LocationCollector · InventoryCollector · UsageCollector
service/            AgentService (foreground: MQTT + location) · BootReceiver
work/               TelemetryWorker (periodic bulk telemetry + command drain)
MainActivity.kt     Compose enrollment + status/permissions UI
```

## Build

Requires Android Studio (Ladybug+) or a local Android SDK.

The Gradle **wrapper jar** is intentionally not committed. First time, either
open the project in Android Studio (it provisions Gradle automatically) or, with
a local Gradle, generate the wrapper:

```bash
gradle wrapper --gradle-version 8.11.1
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Minimum Android 8.0 (API 26); target/compile SDK 35.

### Release signing

No signing key is committed. Debug builds and release builds without a key use
the default Android debug key. To sign a release with your own key, generate one
(keep it **out of the repo**):

```bash
keytool -genkeypair -v -keystore ~/svt-release.jks -alias svtmdm \
  -keyalg RSA -keysize 4096 -validity 10000
```

and supply it via environment variables or `~/.gradle/gradle.properties`:

```
SVT_SIGNING_STORE_FILE=/home/you/svt-release.jks   # absolute path
SVT_SIGNING_STORE_PASSWORD=...
SVT_SIGNING_KEY_ALIAS=svtmdm
SVT_SIGNING_KEY_PASSWORD=...
```

All four must be set, otherwise the debug key is used. In CI, add repo secrets
`SVT_SIGNING_KEYSTORE_B64` (`base64 -w0 svt-release.jks`),
`SVT_SIGNING_STORE_PASSWORD`, `SVT_SIGNING_KEY_ALIAS` and
`SVT_SIGNING_KEY_PASSWORD`. Every release must use the same key so updates
install in place. Changing the key changes the signing-certificate checksum
used for QR provisioning (see `provisioning/device-owner-setup.md`), and
already-installed agents signed with the old key must be uninstalled first.

> **Note:** this agent is **not** intended for the Play Store — it uses
> `QUERY_ALL_PACKAGES` and device-admin behaviours Play forbids. It is designed
> for sideloading onto devices you own.

## Roadmap

- **Phase 2 (this):** light tier — enroll, location, inventory, usage, lock, wipe. ✅
- **Phase 3:** Device Owner — set password, silent grants, QR provisioning, Shizuku.
- **Phase 4:** Windows agent (separate repo).
