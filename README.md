# OpenLink

OpenLink is a FOSS alternative to Google Family Link, with two twists: the
**managed device is Android**, the **parent's app is iOS** — and there is
**no server**. The two phones talk to each other directly.

A parent with an iPhone can set per-app daily time limits on their kid's
Android phone, schedule downtime, and approve or deny "can I have 15 more
minutes?" requests. No Google account, no cloud service, nothing for you
to host or maintain — and no administrative privilege over the phone.

## How it works

The Android child device *is* the server. It already runs a foreground
service to enforce screen time, so it hosts a small TLS HTTP + WebSocket
listener in that same service. The iOS app connects to it directly.

```
   iOS parent app                                   Android child device
   ──────────────                                   ────────────────────
   scan QR once  ──────── pairs, pins TLS cert ────>  shows pairing QR
   Bonjour browse ─────── finds it on Wi-Fi ───────>  advertises _openlink._tcp
   HTTPS + WSS  <──────── direct connection ───────>  :8765
```

The child device owns all the data — policies, schedules, usage tallies,
pending requests — in its local database. Nothing syncs anywhere. A pleasant
side effect: enforcement keeps working perfectly when nothing is connected,
because it never needed the network to begin with.

See [`docs/PROTOCOL.md`](docs/PROTOCOL.md) for the full protocol and
security model.

## Away from home

At home, the two phones find each other over Wi-Fi automatically. Away from
home, you need a way for the iPhone to reach the Android phone across the
internet — and doing that *without* a middleman means putting both devices
on an overlay network:

1. Install [Tailscale](https://tailscale.com/) on both phones (free tier,
   open-source clients) and sign in to the same account. Self-managed
   WireGuard works identically if you prefer.
2. That's it. Each device gets a stable address reachable from anywhere.

**You don't have to configure anything in OpenLink for this.** Pair at home
over Wi-Fi, and the child device reports all of its current addresses on
every connection — including the Tailscale one. The parent app learns it
automatically and uses it the next time you're away.

This is not "hosting a server": there's no VPS, no domain, no deployment,
no database, no updates to apply. You install an app on two phones.

### Why not NAT hole-punching?

It was considered and deliberately rejected. Mobile carriers are nearly
universally behind CGNAT, where hole-punching fails and needs a TURN relay
to fall back on — which is a server again, just someone else's. It would
fail exactly when you most need it to work. An overlay network solves the
same problem reliably.

## Core features

- **Pairing** — the child device shows a QR code; the parent scans it once.
  The QR carries the device's TLS certificate fingerprint, which the parent
  app pins permanently, so the connection is authenticated end-to-end.
- **Per-app daily time limits**, in minutes, per installed app.
- **Hard blocking** — some apps blocked outright, independent of time.
- **Downtime schedules** — recurring windows (e.g. school nights 9pm–7am)
  where everything but a small allow-list is blocked.
- **"Ask for more time"** — the kid requests extra minutes for a specific
  app with a message; the parent approves with a minute count, or denies.
  Requests queue on the device and are delivered when a parent connects.
- **Multiple parents** — several iPhones can pair with one child device,
  each with its own credentials, and any of them can revoke another.
- **No device-administrator privilege.** OpenLink never asks to be a Device
  Admin, so there's no alarming system grant and the app uninstalls like any
  other. That's why there's no remote-lock button: `lockNow()` requires that
  privilege, and it isn't worth what it costs. Limits, hard blocks and
  downtime all work without it.

## Repo layout

```
docs/PROTOCOL.md   the P2P protocol + security model (read this first)
android/           Kotlin/Compose app for the managed device — and the host
ios/               SwiftUI app for the parent (XcodeGen project)
```

## Requirements

| | Minimum | Built against |
|---|---|---|
| **Android** (child device) | **8.0 Oreo, API 26** | API 34 |
| **iOS** (parent app) | **iOS 16.0** — iPhone 8 and later, plus iPad | iOS 16 SDK |

The Android floor is set by `NotificationChannel`, which the foreground
service's persistent notification requires, introduced in API 26. (The
blocking overlay itself needs only `TYPE_ACCESSIBILITY_OVERLAY`, available
since API 22, so the notification is what actually binds.) API 26 covers
the large majority of Android devices still in use.

The iOS floor is softer — it comes from SwiftUI's `NavigationStack`, not
from anything structural. Everything security-related (CryptoKit,
`URLSessionWebSocketTask`, `NWBrowser`) works on iOS 13+, and
`SecTrustCopyCertificateChain` on iOS 15+, so dropping to iOS 15 is a
navigation refactor if you need older devices.

## Building

**Android:** open `android/` in Android Studio (Ladybug 2024.2+), or run
`cd android && ./gradlew assembleDebug`. Needs JDK 17. A debug APK requires
no signing setup or Play account.

**iOS:** `cd ios && xcodegen generate && open OpenLink.xcodeproj`. Needs
Xcode 15+, macOS, and [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`). No third-party Swift packages. Installing on a
physical iPhone additionally requires an Apple Developer account for
signing — a free account works for a 7-day build, a paid one for a year.

**CI:** [`.github/workflows/build.yml`](.github/workflows/build.yml) builds
the Android APK on every push and uploads it as a downloadable artifact, and
compile-checks the iOS app against the simulator SDK (which needs no
signing). That's the quickest way to get a build without a local toolchain.

Then open the Android app, grant the permissions it asks for, show the
pairing QR, and scan it with the iOS app.

## Project status — read this before relying on it

Built as a functional MVP, not a security-audited, store-ready product.
Being straight about where things stand:

- **Both apps compile, and neither has ever been run.** CI builds the
  Android debug APK and compile-checks the iOS app against the simulator
  SDK on every push, and both are green — so the code is type-correct and
  the APK is a real, installable artifact. But compiling is a long way
  from working: no part of this has been exercised on a physical device.
  Pairing, the TLS handshake, the blocking overlay and the Keystore-backed
  certificate are all unverified at runtime. Expect to find genuine bugs
  the moment you try it.
- **The security model is sound on paper but unreviewed in practice.** The
  QR-delivered certificate fingerprint gives genuine out-of-band
  authentication and defeats MITM, tokens are compared in constant time,
  and the pairing secret is single-use and expiring. But a network listener
  that can change what a phone is allowed to run deserves a real audit
  before you trust it on a network you don't control.
- **No push notifications, by construction.** Waking a closed iOS app needs
  APNs, which needs a provider server holding Apple credentials. Shipping
  those inside the child app would be a serious vulnerability, so OpenLink
  doesn't. The parent sees pending requests on opening the app; if the app
  is merely backgrounded with a live socket, it raises a local notification.

