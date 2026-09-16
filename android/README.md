# OpenLink Child (Android)

The Android "child device" app for OpenLink. It pairs with a self-hosted OpenLink server (see
`../docs/API.md`) and enforces per-app screen-time limits, downtime schedules, hard blocks, and
remote lock, set by a parent using the companion iOS app.

## Opening the project

1. Open the `android/` folder (not the repo root) in Android Studio (Hedgehog/2023.1.1 or newer
   is recommended for AGP 8.2 / Kotlin 1.9 support).
2. Let Gradle sync. `gradle/wrapper/gradle-wrapper.properties` points at Gradle 8.4; Android
   Studio will download it automatically. The wrapper `.jar` binary itself was **not** committed
   (per the task's instructions) -- if `./gradlew` doesn't work from a plain terminal, run
   `gradle wrapper` once with a local Gradle install, or just let Android Studio's bundled Gradle
   handle the sync/build instead of the CLI wrapper.
3. Build variant: `debug`. `minSdk 26` (Android 8.0), `targetSdk`/`compileSdk 34`.

## Setting the server URL

This is a self-hosted app -- there's no baked-in server domain. On first launch, the onboarding
screen asks for:
- **Server address**: scheme+host+port of your OpenLink server, e.g. `https://openlink.example.com`
  or `http://192.168.1.50:3000` for a bare LAN deployment (no `/api` suffix -- the app appends
  that itself, matching `docs/API.md`'s "Base URL: `https://<your-server>/api`").
- **Pairing code**: the 6-character code shown in the parent's iOS app (`POST /pairing/generate`).

It can be edited later from Settings (reachable from the home screen), which also shows the
paired device/family id and an "Unpair this device" action.

The server URL and device token are stored together in one `EncryptedSharedPreferences` file
(`prefs/SecurePrefs.kt`), backed by an AES key in the Android Keystore.

## What's implemented

- **Pairing** (`pairing/`, `ui/onboarding/`): `POST /pairing/claim`, persists
  `deviceToken`/`deviceId`/`familyId` via `SecurePrefs`.
- **Usage tracking** (`service/MonitorForegroundService.kt`): polls `UsageStatsManager` every 30s,
  tallies today's per-app foreground minutes into Room (`data/`), survives process death.
- **Enforcement** (`enforcement/`): `EnforcementEngine` implements docs/API.md's four rules
  (lock > hard block > downtime > per-app limit, with the always-allowed exemption applying to
  downtime only); `EnforcementRepository` is the in-memory cache both the accessibility service
  and the UI read from; `AlwaysAllowed.kt` is the allow-list named in the doc.
- **Blocking overlay**: `PolicyForegroundAccessibilityService` (an `AccessibilityService`)
  detects foreground-app changes and draws a full-screen, back-button-proof
  `TYPE_ACCESSIBILITY_OVERLAY` via `OverlayController`. See the design-note comment at the top of
  that file for why this approach was chosen over a plain `Service` + `SYSTEM_ALERT_WINDOW`, and
  why `SYSTEM_ALERT_WINDOW` is still requested as a documented fallback for the lock-only case.
- **Ask for more time**: `RequestTimeDialog` (openable from the overlay's button or the home
  screen) calls `POST /device/requests`; an approved `request:decision` bumps today's effective
  limit in `EnforcementRepository` immediately, no extra round trip.
- **Remote lock**: `lock:update` (socket) or `isLocked` from `GET /device/policies` triggers
  `DevicePolicyManager.lockNow()` (via `ChildDeviceAdminReceiver`) and shows the overlay
  immediately. Device-admin activation is part of the permissions flow
  (`ui/permissions/PermissionsScreen.kt`), which also explains why it's needed and that removing
  it is treated as a self-unpair signal (best-effort -- see below).
- **Networking**: Retrofit + OkHttp + kotlinx.serialization (`network/`), matching
  `docs/API.md`'s paths/fields/headers exactly; base URL is resolved at request time (see
  `DynamicBaseUrlInterceptor`) since it's user-configured. Socket.IO (`io.socket:socket.io-client`)
  carries `policy:update` / `lock:update` / `request:decision`, with the foreground service
  falling back to polling `GET /device/policies` / `GET /device/requests` every ~30s whenever the
  socket is disconnected.
- **UI**: Jetpack Compose + Material 3 -- onboarding, permissions, home/status (installed apps,
  today's usage, progress bars), request-more-time dialog, settings.

## Known gaps / what a production app would still need

- **Not compiled.** There is no Android SDK in the environment this was written in, so nothing
  here has gone through `./gradlew assembleDebug` or the Kotlin compiler. Every file was written
  and re-read carefully for syntax, imports, and API correctness, and internal consistency (class
  names, function signatures, package structure) was cross-checked with `grep`, but expect to fix
  minor issues (an import miss, an SDK API nuance) on first real build.
- **No dedicated self-unpair endpoint.** `docs/API.md` has no server endpoint for "device admin
  was revoked" -- `admin/ChildDeviceAdminReceiver.onDisabled()` records this locally and fires an
  otherwise-empty usage heartbeat as a best-effort nudge to `lastSeenAt`, which is a weak signal,
  not a real notification. A production system needs a real endpoint + parent-side push.
- **No offline request queueing.** If "ask for more time" or `POST /device/apps` fails because
  the device is offline, the MVP just drops it rather than queueing and retrying (usage/heartbeat
  data does *not* have this problem -- it's durable in Room and retried every heartbeat tick).
- **`respondedAt`-based "today" matching for granted minutes is UTC-date-based** (`TimeRequestDao.
  getApprovedGrantedForDate`), which can be off by up to the device's UTC offset right at local
  midnight. Documented in that DAO's KDoc.
- **No Room migrations** -- `fallbackToDestructiveMigration()` is used; fine for an MVP, not for
  a real upgrade path.
- **No WorkManager backstop.** Enforcement relies entirely on the foreground service staying
  alive (`START_STICKY` + a low-priority persistent notification); very aggressive OEM battery
  managers (some Chinese OEM skins in particular) can still kill it. A production app would add a
  periodic `WorkManager` job as a backstop that also re-primes `EnforcementRepository`.
- **No unit/instrumented tests.** `EnforcementEngine` is pure and easy to test (no Android
  dependencies), but no test source set was written for this MVP.
- **Design polish left minimal, as scoped**: no custom animations, limited dark-mode-specific
  tuning beyond Material 3's dynamic color, no accessibility content descriptions beyond what's
  functionally necessary, simple programmatic-View overlay instead of a themed Compose one (see
  the design note in `OverlayController.kt` for why Compose wasn't used there).
- **`usesCleartextTraffic="true"`** in the manifest, to support LAN/self-signed self-hosting
  without extra setup. A real deployment behind TLS should tighten this with a network security
  config scoped to the configured host.
- **Socket.IO auth option**: `network/SocketManager.kt` sets `IO.Options().auth = mapOf("token" to
  deviceToken)` per `docs/API.md`. This field was added to `io.socket:socket.io-client` in a
  version around 2.1.0 (pinned in `app/build.gradle.kts`); it was not possible to verify against
  the library's actual source in this environment, so double-check this compiles against
  whichever exact version Gradle resolves.

## Assumptions / deviations from docs/API.md

- The Android app only ever acts as a **device-token** client. The parent-auth (JWT) endpoints
  (`/auth/*`, `/devices/*`, `/requests/:id/approve|deny`) belong to the iOS app and were
  intentionally not modeled here.
- The user-entered "server address" is expected to be scheme+host+port only (no path prefix);
  `/api/...` is appended by the app, matching the doc's "Base URL: `https://<your-server>/api`".
  A reverse-proxy deployment that puts OpenLink under an extra path prefix isn't supported by the
  onboarding field as written.
- `ScheduleWindow.daysOfWeek` bit0=Sunday and minute-of-day fields are interpreted in the
  **device's local time**, matching the doc's "downtime/bedtime window" framing (the doc doesn't
  explicitly say local vs. device timezone field, but `ChildDevice.timezone` existing in the
  Prisma schema alongside local-sounding semantics strongly implies local time).
- "Effective daily limit" (rule 1) sums *all* of a package's approved `TimeRequest.grantedMinutes`
  for the day, matching the doc's wording exactly; there's no separate "used up" tracking of
  individual grants.
