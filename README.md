# OpenLink

OpenLink is a FOSS alternative to Google Family Link, with a twist: the
**managed device is Android**, and the **parent/controller app is iOS**. A
parent with an iPhone can set per-app daily time limits on their kid's
Android phone, lock it remotely, schedule downtime (e.g. bedtime), and
approve or deny "can I have 15 more minutes?" requests — all self-hosted,
no Google account or cloud service required.

## How it fits together

```
   iOS app (parent)              OpenLink Server              Android app (child)
  ─────────────────           ───────────────────           ─────────────────────
  SwiftUI, ios/                Node/TS + Express +            Kotlin/Compose,
                                Prisma + Socket.IO,            android/
                                server/
        │                             │                              │
        │  REST (JWT auth)            │                              │
        ├────────────────────────────>│<─────────────────────────────┤
        │                             │   REST (device-token auth)   │
        │  Socket.IO (family room)    │   Socket.IO (device room)    │
        ├────────────────────────────>│<─────────────────────────────┤
```

- **`server/`** — the backend both apps talk to. Owns accounts, pairing,
  policies, usage history, and time requests; pushes realtime updates over
  Socket.IO and falls back to polling. See [`docs/API.md`](docs/API.md)
  for the full contract and [`server/README.md`](server/README.md) to run it.
- **`android/`** — the app installed on the managed device. Reads today's
  per-app usage via `UsageStatsManager`, enforces limits/blocklist/downtime
  with an `AccessibilityService`-driven full-screen overlay, and can be
  remotely locked via Android's Device Admin APIs. See
  [`android/README.md`](android/README.md).
- **`ios/`** — the parent's controller app: pairing, per-app limits,
  remote lock, downtime schedules, and the approve/deny queue for time
  requests. See [`ios/README.md`](ios/README.md).

## Core features

- **Pairing** — the parent generates a 6-character code (+ QR) in the iOS
  app; the Android app claims it once to link to the family.
- **Per-app daily time limits** — set in minutes per installed app.
- **Hard blocking** — some apps can be blocked outright, independent of time.
- **Downtime schedules** — recurring windows (e.g. school nights 9pm–7am)
  where everything but a small allow-list is blocked.
- **Remote lock** — lock the Android device immediately from the iOS app.
- **"Ask for more time"** — the child requests extra minutes for a specific
  app with an optional message; the parent approves (with a minute count)
  or denies from a live queue.
- **Works offline-ish** — the Android app caches policies locally and keeps
  enforcing them if connectivity drops; both apps fall back to polling if
  the realtime socket is down.

## Quick start

1. **Run the server** (see [`server/README.md`](server/README.md) for detail):
   ```bash
   cd server
   npm install
   cp .env.example .env   # set a real JWT_SECRET
   npx prisma migrate dev
   npm run dev
   ```
   or `docker compose up --build` from the repo root.
2. **Build the Android app** in Android Studio from `android/`, point it at
   your server's URL in the onboarding screen.
3. **Build the iOS app**: `cd ios && xcodegen generate && open OpenLink.xcodeproj`,
   point it at the same server URL, register a family account, and generate
   a pairing code to link the Android device.

## Project status — read this before relying on it

This was built end-to-end in one sitting as a functional MVP, not a
security-audited, store-ready product. Being honest about where things
stand:

- **`server/`** is implemented, typechecked, and covered by an end-to-end
  test (`npm test` in `server/`) that exercises register → pair → set
  policy → heartbeat usage → request/approve time, plus auth and
  cross-family isolation checks. It runs.
- **`android/`** and **`ios/`** were written carefully against
  [`docs/API.md`](docs/API.md) but **could not be compiled in this
  environment** (no Android SDK or macOS/Xcode toolchain available here).
  Treat them as strong first drafts: open them in Android Studio / Xcode,
  fix whatever SDK-version or dependency-resolution issues come up, and
  test on real devices before trusting them with an actual kid's phone.
- **Not implemented**: push notifications (APNs) end-to-end — the iOS app
  relies on Socket.IO + polling, which works but isn't as instant as a
  push notification for a pending time request; multi-parent permission
  tiers; usage history/analytics beyond "today"; iOS-as-managed-device
  support (Apple's Screen Time APIs are a different, much more restrictive
  integration than Android's Device Admin/Accessibility APIs and weren't
  in scope here).
- **Android enforcement depends on the user granting several powerful
  permissions** (Usage Access, Draw Over Other Apps, Accessibility
  Service, Device Admin, notifications). All of these are things a
  motivated teenager can revoke from Settings to defeat the app — same
  fundamental limitation Family Link, Bark, and every non-MDM parental
  control app on Android has. A production deployment for anything beyond
  a cooperative household would want Android Enterprise / a Device Owner
  provisioning flow, which is a significantly bigger lift.
- Passwords are bcrypt-hashed and device tokens are stored as SHA-256
  hashes, but there's no rate limiting, email verification, or password
  reset flow yet — see `server/README.md`'s notes section.

## Repo layout

```
docs/API.md       full REST + Socket.IO contract (read this first)
server/           Node/TS backend (Express, Prisma/SQLite, Socket.IO)
android/          Kotlin/Compose app for the managed Android device
ios/              SwiftUI app for the parent (XcodeGen project)
docker-compose.yml   one-command self-hosted server deploy
```
