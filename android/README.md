# OpenLink Child (Android)

The Android "child device" app for OpenLink. **It is the server.**

There is no backend to host. This app enforces per-app screen-time limits, downtime schedules and
hard blocks locally, stores all of that in its own Room database, and hosts a
small TLS-secured HTTP + WebSocket listener that the companion iOS parent app connects to
directly — over the LAN at home, or over an overlay network (Tailscale/WireGuard) when the parent
is away. The wire contract is `../docs/PROTOCOL.md`, and this app implements the server side of
it in full.

The consequence worth stating plainly: **enforcement never depends on connectivity.** Policies,
schedules and today's tallies are already on the device. When nothing is connected — the normal
case — limits still apply, downtime still starts on time, and a child's request for more time
queues locally until a parent opens their app.

## Opening the project

1. Open the `android/` folder (not the repo root) in Android Studio (Ladybug/2024.2 or newer, for
   AGP 8.7 / Kotlin 2.1 support).
2. Let Gradle sync. The wrapper is committed, so `./gradlew assembleDebug` also works from a
   plain terminal — it fetches Gradle 8.9 on first run.
3. Build variant: `debug`. `minSdk 26` (Android 8.0), `targetSdk`/`compileSdk 34`.

A debug APK needs no Play Store account or signing setup: Gradle generates a debug keystore
automatically. The output lands at `app/build/outputs/apk/debug/app-debug.apk`, installable with
`adb install`, or by copying it to the phone and allowing install from unknown sources.

`.github/workflows/build.yml` builds this APK on every push and attaches it as a downloadable
artifact, which is the easiest way to get a build without a local Android SDK.

### Why Netty, and why Kotlin 2.1

- **Netty, not CIO.** Ktor's CIO engine **cannot terminate TLS at all**. Starting an
  `sslConnector` on it throws `UnsupportedOperationException: CIO Engine does not currently
  support HTTPS`. Every route here is HTTPS-only, so on CIO the app has no listener and pairing
  is impossible. Netty is heavier on Android, and that is the correct price.
- **TLS is attached to Netty's pipeline by hand, not via `sslConnector`.** Given a keystore,
  Ktor pulls the private key out and Netty re-packs it into a fresh keystore. That breaks twice
  on Android: Netty supplies a null password, which the platform BouncyCastle keystore rejects
  with an NPE, and an AndroidKeyStore key is non-exportable by design, so it could never be
  re-packed even with the right password. Instead `TlsIdentity.serverSslContext` builds an
  `SSLContext` from a `KeyManagerFactory` -- which holds the keystore's opaque handle and asks
  it to sign, so the key never leaves -- and `OpenLinkServer` attaches an `SslHandler` through
  `channelPipelineConfig` on a plain connector.

  Both of the above compiled perfectly and failed on every single run. Neither was caught until
  an emulator actually launched the app, which is why the instrumented tests in
  `src/androidTest/` exist and why CI now boots an emulator on two API levels.
- **Kotlin 2.x** — Ktor 3 artifacts carry Kotlin 2.0 metadata, which the 1.9 compiler this project
  started on refuses to read. Moving to Kotlin 2.1 also means the Compose compiler is applied as
  its own Gradle plugin (`org.jetbrains.kotlin.plugin.compose`) rather than via `composeOptions`.

## Setup on the device

There is no server address to enter and no pairing code to type. First launch is:

1. **Permissions** (`ui/permissions/`) — usage access, accessibility service, notifications.
   These are about enforcing on *this* device, which never involved a server. Note there is no
   "display over other apps" step: the accessibility binding grants
   `TYPE_ACCESSIBILITY_OVERLAY` implicitly, so `SYSTEM_ALERT_WINDOW` is not requested either.
2. **Pairing** (`ui/pairing/PairingScreen.kt`) — the child's screen shows a QR code; the parent
   scans it. That's the whole flow.
3. **Home** — today's usage, limits, "ask for more time".

**No device-administrator privilege is requested.** OpenLink deliberately never asks to become a
Device Admin: that grant goes through an alarming system screen, blocks normal uninstallation, and
would be far broader than anything here needs. Nothing in the app is an administrative privilege
over the device, and it can be uninstalled like any other app. Time limits, hard blocks and
downtime windows are all enforced by the blocking overlay, which needs no such privilege.

Settings shows the paired parents (with per-parent revoke), the port and addresses the device is
listening on, the certificate fingerprint, a "show a pairing code" action, and a "remove all
parents and stop protection" action.

## How pairing and auth work

On first run the app generates a P-256 key pair **inside the Android Keystore**
(`security/TlsIdentity.kt`). The private key has no software copy: signing happens in the keystore
daemon, and on most devices in the TEE behind it. AndroidKeyStore mints the self-signed
certificate for it automatically. SHA-256 of that certificate's DER is the `fp` in the QR, and the
parent pins it forever.

The QR encodes the `openlink://pair?...` URI from PROTOCOL.md, including a fresh single-use `psk`
minted when the pairing screen opens and destroyed on success, on expiry (5 minutes), or when the
screen closes. `POST /pair` verifies
`HMAC-SHA256(psk, "openlink-pair-v1" || parentId)` in constant time, issues 32 random bytes as a
`parentToken`, and stores **only SHA-256 of that token**. The token itself exists on this device
for exactly the duration of one HTTP response.

Every other route requires `Authorization: Bearer <token>`, compared by SHA-256 in constant time.
Specific properties, since this is a network listener on a phone that decides what a child can
open:

- `POST /pair` is the **only** unauthenticated route, and it is inert outside an open pairing
  window (it has no secret to check a proof against, so it refuses everything).
- Authentication is enforced by a single interceptor **ahead of routing**
  (`server/Routes.kt`), not per route. A route added later is authenticated by construction.
- `POST /pair` is rate-limited to 5 attempts/minute per source address; failed authentications to
  10/minute per address. Both limiters also carry a **global** ceiling, because a per-address
  limit alone is worthless against an attacker who can rotate source addresses — which is trivial
  on a LAN.
- Comparisons never short-circuit (`security/Crypto.constantTimeEquals`), the rate-limiter's key
  map is capped so it can't be used to exhaust memory, request bodies over 256 KB are refused
  before parsing, and no token, `psk`, proof or fingerprint-adjacent secret is ever logged. Error
  bodies are deliberately terse and never say *which* check failed.
- Multiple parents can pair; each gets its own token, and any of them can revoke another via
  `DELETE /pair`.
- A revoked parent's **live WebSocket is evicted**, not left running. REST re-authenticates every
  request, but a socket is authenticated only at the handshake, so `/events` watches a process-wide
  revocation counter and drops the connection once it moves. The eviction happens on the next
  event rather than instantly — in practice within ~60s, since `device:state` ticks that often.

## Reachability

The listener binds `0.0.0.0` on port 8765, falling back through 8766–8774 if that's taken, so one
socket serves the LAN interface and any overlay-network interface at once. `server/NsdAdvertiser`
advertises `_openlink._tcp` with the port actually bound, plus the device id and fingerprint as
TXT records.

`GET /device` returns every current non-loopback interface address as `host:port`, LAN-private
IPv4 first, then other IPv4 (where a Tailscale 100.64/10 address lands), then IPv6. This is the
whole endpoint-learning mechanism: a device paired at home learns its own overlay address the
first time a parent asks while the overlay is up, and the parent persists it. Nobody types a
remote address anywhere. `device:state` re-broadcasts the list every ~60s, so an address that
only just became available reaches an already-connected parent too.

The foreground service also holds a Wi-Fi lock while the listener is up. Without it, an inbound
socket becomes unanswerable some minutes after the screen goes off, which presents to the user as
"the parent app randomly can't connect".

## What's implemented

- **Embedded TLS listener** (`server/OpenLinkServer.kt`, `server/Routes.kt`) — Ktor Netty inside
  `MonitorForegroundService`, so its lifetime is exactly the lifetime of enforcement. Every route
  in PROTOCOL.md is served: `POST /pair`, `DELETE /pair`, `GET /device`,
  `GET /apps`, `PUT /policies/{packageName}`, `GET|PUT /schedule`, `GET /usage`, `GET /requests`,
  `POST /requests/{id}/approve|deny`, and `WS /events`.
- **Device identity** (`security/TlsIdentity.kt`) — Keystore-backed, non-exportable, generated
  once.
- **Pairing** (`pairing/`, `ui/pairing/`) — QR generated on-device with ZXing `core`; single-use
  expiring PSK; constant-time proof verification; rate limiting.
- **WebSocket `/events`** — `request:new`, `usage:update`, `policy:update`,
  `device:state`, fanned out to every connected parent. `policy:update` skips the parent that
  caused it, per PROTOCOL.md. The bus drops the oldest event rather than blocking when a parent's
  socket stalls: every event describes state that can be re-read over REST, and blocking an
  enforcement coroutine on a wedged socket would be strictly worse.
- **Usage tracking** (`service/MonitorForegroundService.kt`) — polls `UsageStatsManager` every
  30s, tallies today's per-app foreground minutes into Room, 30-day retention, emits
  `usage:update` only for packages whose count actually moved.
- **Enforcement** (`enforcement/`) — `EnforcementEngine` implements the three rules (hard block >
  downtime > per-app limit, with the always-allowed list exempt from all of them);
  `EnforcementRepository` is the in-memory cache the accessibility service reads synchronously.
  Each downtime window also carries its own `exemptPackages` allow-list. An exemption is scoped
  to the window that grants it — where windows overlap, an app must be exempt from every active
  one to get through — and it exempts from downtime only: a hard block still wins, and an exempt
  app still spends its daily limit. `test/.../DowntimeExemptionTest.kt` pins all of that.
- **Blocking overlay** — `PolicyForegroundAccessibilityService` draws a full-screen,
  back-button-proof `TYPE_ACCESSIBILITY_OVERLAY`. See the design note at the top of that file for
  why this beats a plain `Service` + `SYSTEM_ALERT_WINDOW`.
- **Ask for more time** — writes a row locally and announces it. It cannot fail for being
  offline; the previous version silently dropped requests made without a connection. An approval
  lands in the same process, so the app unblocks the instant the parent taps approve.
- **UI** — Jetpack Compose + Material 3: permissions, pairing, home/status, settings.

## What was deleted

The entire outbound-client layer: `network/OpenLinkApi.kt`, `NetworkModule.kt`,
`AuthInterceptor.kt`, `DynamicBaseUrlInterceptor.kt`, `SocketManager.kt`, `network/model/`, the
"claim a code from a server" `PairingRepository`, and `ui/onboarding/` (server URL + 6-digit
code). Retrofit, OkHttp and Socket.IO are gone from `app/build.gradle.kts`; no HTTP client of any
kind remains. `usesCleartextTraffic="true"` is gone from the manifest — there is no outbound
traffic at all now, and what this app *hosts* is TLS-only.

## Known gaps / what a production app would still need

- **It compiles; it has never been run.** CI (`.github/workflows/build.yml`) runs
  `./gradlew assembleDebug` on every push and publishes the APK as an artifact, so the Kotlin is
  type-correct and the APK is real and installable. It has never been on a device or emulator.
  Everything below the type system is unverified: the TLS handshake, pairing, the overlay,
  NSD advertisement and the Room queries have all only been *compiled*.

  Two of the three breakages predicted here before the first build did happen, and are fixed:
  Ktor's `call` is an extension on `PipelineContext` inside `intercept` (it needed an import,
  the API itself is unchanged in 3.2.3), and `Modifier.weight` must come from the
  `Row`/`Column` scope rather than a top-level import. The third is untouched by compiling and
  is now the biggest open risk:

  - **AndroidKeyStore + JSSE.** Ktor's `sslConnector` takes a `java.security.KeyStore` and pulls
    the key via `KeyManagerFactory.init(keyStore, password)`. Conscrypt is expected to handle
    opaque AndroidKeyStore keys there, and the empty passphrase is the conventional spelling for
    a keystore that has none — but this is a *runtime* binding that compiles regardless of
    whether it works. If it fails, it fails when the listener starts, and the fix is a
    hand-rolled `X509KeyManager`, contained to `TlsIdentity` plus the connector call. This is the
    first thing to try on a real device.
- **The TLS certificate has no subjectAltName.** AndroidKeyStore's auto-generated certificate
  can't have one, because the addresses the device will be dialled on aren't known when the key is
  generated. **The iOS app must therefore disable hostname verification and validate purely by
  comparing the presented leaf's SHA-256 against the pinned `fp`.** That is strictly stronger than
  name-checking a self-signed certificate, but it has to be done deliberately, and doing it wrong
  (accepting any certificate) would silently remove the protocol's entire defence against an
  active MITM.
- **No key rotation.** The identity is generated once and pinned forever. A factory reset or an
  app reinstall means re-pairing every parent, and there is no story for rotating a key that is
  suspected compromised other than "pair again in person".
- **Room migrations start at v2** — v2 -> v3 (the downtime allow-list) is a real `Migration`,
  as the previous note said the next schema change would need. `fallbackToDestructiveMigration()`
  is still set for v1 only, which belongs to the deleted server-based architecture and carries
  nothing worth keeping. Every schema change from here needs its own `Migration`: this database
  *is* the policy, so dropping it would throw away every limit, schedule and usage tally the
  family has set up.
- **`respondedAt`-based "today" matching for granted minutes is UTC-date-based**
  (`TimeRequestDao.getApprovedGrantedForDate`), so it can be off by up to the device's UTC offset
  right at local midnight.
- **Most enforcement semantics are still undocumented outside the code.** They used to live in
  `docs/API.md`, which went away with the server; `docs/PROTOCOL.md` deliberately covers only the
  wire contract, though it now also states how `exemptPackages` combines, since that is a rule
  two implementations could disagree about. The rest — rule precedence, how granted minutes
  extend a limit — is written up only in `EnforcementEngine.kt`'s KDoc, and a protocol document
  that a second implementation could be built from should state it.
- **No event for "enforcement was turned off".** If the child disables the accessibility service,
  blocking silently stops, and PROTOCOL.md's event list has no type to tell a parent so — which is
  exactly the thing a parent would most want to know. Inventing one the iOS app doesn't know about
  would be worse than useless; a future protocol revision (`device:degraded`?) is the right home
  for it.
- **No WorkManager backstop.** Both enforcement *and* the listener now depend on the foreground
  service staying alive (`START_STICKY` + a low-priority persistent notification). Aggressive OEM
  battery managers can still kill it, and the failure mode is worse than it used to be: the parent
  simply cannot connect. A production app would add a periodic `WorkManager` job as a backstop.
- **Doze and battery.** Holding a Wi-Fi lock and keeping a listener up costs power. It has not
  been measured. Some devices will also need a battery-optimisation exemption before an inbound
  connection is reliable while idle, and the app does not currently ask for one.
- **No unit/instrumented tests.** `EnforcementEngine`, `Crypto`, `RateLimiter` and `PairingUri`
  are all pure and easy to test; the pairing proof and the constant-time comparison in particular
  deserve tests before anyone trusts this.
- **Design polish left minimal**, as before: no custom animations, no accessibility content
  descriptions beyond what's functionally necessary, plain-View overlay rather than a themed
  Compose one (see the design note in `OverlayController.kt`).

## Assumptions / deviations from docs/PROTOCOL.md

- **The HMAC message is the base64url *text* of `parentId`, not its 32 decoded bytes.** PROTOCOL
  writes `proof = HMAC-SHA256(psk, "openlink-pair-v1" || parentId)`, but `parentId` crosses the
  wire as a JSON string, so "the parentId" is ambiguous. This implementation hashes the UTF-8
  bytes of the string exactly as it appears in the request body, because that is the one form both
  peers can agree on without re-deriving a canonical encoding. **The iOS side must match.** This
  belongs in PROTOCOL.md explicitly.
- **`endpoints` omits the NSD hostname.** PROTOCOL asks for "all non-loopback interface addresses,
  plus its NSD hostname". Android's `NsdManager` does not expose the hostname it registers under,
  and it may rename a service on collision — so any `<name>.local` would be a guess. A guess in
  this list is worse than an omission: it costs the parent a connection attempt on every future
  connect. mDNS discovery already covers what a hostname would.
- **`endpoints` also omits link-local addresses** (169.254/16, fe80::/10), which are only
  meaningful with a scope id local to this device's interface numbering and cannot be dialled by a
  peer.
- **`AppDto.policy` is always present**, as `{ dailyLimitMinutes: null, blocked: false }` for an
  app with no stored row, rather than JSON `null`. "Unset" and "unlimited and unblocked" mean the
  same thing to the enforcement engine, and collapsing them removes a case the parent would
  otherwise have to handle.
- **Answering an already-answered request returns 409.** PROTOCOL doesn't say what
  `POST /requests/{id}/approve` should do to a request that is already approved or denied.
  Refusing is the conservative choice; it avoids two parents silently double-granting time.
- **No event is emitted when a request is approved or denied.** PROTOCOL's event table has
  `request:new` but no counterpart for the decision, and the parent that made the decision has it
  in its HTTP response. Another connected parent finds out on its next `GET /requests` — which
  PROTOCOL already says happens on foreground and every 30s.
- **Port fallback range.** PROTOCOL says "default port 8765"; this tries 8765–8774 and gives up.
  The bound port is what goes in the QR, the mDNS advertisement and `endpoints`, so a parent is
  never told the wrong one.
- **Schedule window ids are not stable.** `PUT /schedule` replaces the whole set, so incoming ids
  are discarded and Room assigns new ones. PROTOCOL marks `id` optional, which this reads as
  "informational".
