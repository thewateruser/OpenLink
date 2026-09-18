# OpenLink

**Screen-time controls for a kid's Android phone, managed from a parent's
iPhone. No account, no subscription, and no server — the two phones talk
straight to each other.**

A parent can set daily time limits per app, block apps outright, schedule
downtime like school nights, and approve or deny "can I have 15 more
minutes?" requests. Nothing about your family goes to anyone's cloud,
because there is nowhere for it to go.

> **Not ready to rely on yet.** The apps build and install, but they're
> young and lightly tested on real devices. Treat this as something to try,
> not something to trust with a rule that matters today. See
> [Project status](#project-status).

## Getting started

1. **Install the child app** on the kid's Android phone — grab the APK from
   [Releases](../../releases), or build it yourself (see [Building](#building)).
2. **Install the parent app** on your iPhone. There's no App Store build, so
   this means sideloading the `.ipa` from Releases or opening `ios/` in
   Xcode — see [Installing on iPhone](#installing-on-iphone).
3. **Open the Android app** and grant the permissions it asks for. It needs
   to see which app is in the foreground and for how long; that's how limits
   work at all.
4. **Pair them.** The Android phone shows a QR code. Scan it with the iPhone
   app, with both phones on the same Wi-Fi. That's the whole setup.

After pairing, the iPhone app lists the child's apps and you set limits from
there.

### Using it away from home

At home the phones find each other over Wi-Fi by themselves. To manage the
phone while you're out, both phones need to be on the same private network.
The easy way:

1. Install [Tailscale](https://tailscale.com/) on **both** phones (free, and
   the apps are open source).
2. Sign in to the **same account** on each.

That's it — there's nothing to configure inside OpenLink. Do it once while
both phones are together on home Wi-Fi, and the parent app quietly learns
the new address and uses it next time you're away. If you'd rather run your
own WireGuard, that works identically.

## How it works

Most parental-control apps put a company's server between the two phones.
OpenLink doesn't have one. Instead, **the Android phone is the server**. It's
already running a background service to enforce screen time, so it also
listens for connections from the parent's phone.

```
   iPhone (parent)                                  Android (the kid's phone)
   ──────────────                                   ─────────────────────────
   scan QR once  ──────── pairs, pins TLS cert ────>  shows pairing QR
   Bonjour browse ─────── finds it on Wi-Fi ───────>  advertises _openlink._tcp
   HTTPS + WSS  <──────── direct connection ───────>  :8765
```

Two consequences worth understanding:

- **All the data lives on the kid's phone** — limits, schedules, today's
  usage, pending requests. Nothing syncs anywhere.
- **Enforcement never depends on a connection.** Limits still apply and
  downtime still starts on time when the parent's phone is nowhere nearby,
  because the rules were never stored anywhere else. A request for more time
  just queues on the device until a parent connects.

### How the phones trust each other

The QR code isn't a password. It carries the Android phone's TLS certificate
fingerprint, and the iPhone app pins that exact certificate forever. Because
the fingerprint travelled through the QR — a channel an attacker on your
Wi-Fi can't touch — it defeats someone impersonating the device. The QR also
carries a single-use secret that expires in five minutes, so the one
unauthenticated route on the device only answers while a human is actually
looking at the pairing screen.

[`docs/PROTOCOL.md`](docs/PROTOCOL.md) has the full protocol and threat model.

### Why no remote lock

Locking a phone's screen remotely requires becoming a **Device Admin** on
Android. That's approved through an alarming system screen and grants far
more power than the one feature needs. OpenLink deliberately doesn't ask, so
it holds no administrative power over the phone and uninstalls like any
normal app. It doesn't ask for "display over other apps" either. Time limits,
blocking and downtime all work without any of it.

### Why not hole-punching instead of Tailscale

Considered and rejected. Mobile carriers are almost universally behind CGNAT,
where NAT hole-punching fails and has to fall back on a relay server — which
is a server again, just someone else's, and it would fail exactly when you
need it. An overlay network solves the same problem reliably.

## Features

- **Per-app daily time limits**, in minutes.
- **Hard blocking** — some apps off-limits regardless of time left.
- **Downtime schedules** — recurring windows like school nights 9pm–7am.
- **"Ask for more time"** — the kid requests extra minutes with a message;
  the parent approves a specific number of minutes, or denies.
- **Multiple parents** — several iPhones can pair with one child phone, each
  with its own credentials, and any can revoke another.
- **No device-administrator privilege**, and no remote lock (see above).

## Requirements

| | Minimum | Built against |
|---|---|---|
| **Android** (the kid's phone) | **8.0 Oreo, API 26** | API 34 |
| **iOS** (the parent's phone) | **iOS 15.1** — iPhone 6s and later, plus iPad | current SDK |

The Android floor comes from `NotificationChannel`, which the background
service's persistent notification needs (API 26). The blocking overlay itself
only needs `TYPE_ACCESSIBILITY_OVERLAY`, available since API 22.

The iOS app uses `NavigationView` rather than iOS 16's `NavigationStack`
specifically to keep the 15.1 floor — don't "modernise" that without raising
the minimum.

## Building

**Android:** open `android/` in Android Studio (Ladybug 2024.2+), or run
`cd android && ./gradlew assembleDebug`. Needs JDK 17. A debug APK needs no
signing setup or Play account.

**iOS:** `cd ios && xcodegen generate && open OpenLink.xcodeproj`. Needs
macOS, Xcode 15+, and [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`). No third-party Swift packages.

**CI:** [`.github/workflows/build.yml`](.github/workflows/build.yml) builds
the APK on every push, compile-checks the iOS app, and — importantly — boots
an Android emulator and actually launches the app on it, on two API levels.
That last part exists because a crash-on-startup once shipped with every
other check green.

### Installing on iPhone

There's no App Store listing, so the `.ipa` in Releases is **unsigned**.
Building an installable one requires an Apple Developer account and
provisioning profile that CI doesn't have. Your options:

- **[AltStore](https://altstore.io/)** or **[Sideloadly](https://sideloadly.io/)** —
  re-signs with your own Apple ID. Free account works; the app expires every
  7 days and needs refreshing. A paid developer account extends that to a year.
- **[TrollStore](https://github.com/opa334/TrollStore)** — permanent, but only
  on the iOS versions it supports.
- **Xcode** — open `ios/` and run it straight onto your own device.

## Project status

Built quickly as a working MVP, not a security-audited product. Where things
honestly stand:

- **The Android app is verified to start; the rest is lightly tested.** CI
  boots an emulator on two API levels every push and asserts that the
  background service comes up and the TLS listener actually binds and reports
  a reachable address. That much is proven on each commit. What is *not* yet
  covered by a test: completing a pairing handshake with a real iPhone,
  certificate pinning against the live certificate, the blocking overlay, and
  downtime schedules. Expect bugs there.

  Worth knowing how that test came to exist. Three separate bugs shipped in
  builds where every check was green, because every check only compiled the
  code: a crash on Android 10–13 the moment you tapped Continue, an HTTP
  engine that cannot do TLS at all, and a TLS setup that could never load the
  device's key. None were visible to a compiler; all three were obvious the
  first time an emulator ran the app.
- **The security design is sound on paper but unaudited.** The pinned
  fingerprint genuinely defeats impersonation, tokens are compared in
  constant time, the pairing secret is single-use and expiring. But a
  listener on a phone that decides what a kid can open deserves a real review
  before you trust it on a network you don't control.
- **No push notifications, by design.** Waking a closed iOS app needs Apple's
  push service, which needs a server holding Apple credentials. Shipping those
  inside the child app would be a serious vulnerability, so OpenLink doesn't.
  You see pending requests when you open the app; if it's merely backgrounded
  and still connected, it raises a local notification.

## Repo layout

```
docs/PROTOCOL.md   the protocol and security model (start here to hack on it)
android/           Kotlin/Compose app for the kid's phone — and the server
ios/               SwiftUI app for the parent (XcodeGen project)
```
