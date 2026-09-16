# OpenLink API Contract

OpenLink is a FOSS "Family Link" alternative: an **Android device is the managed
("child") device**, and an **iOS app is the parent/controller app**. Both talk
to a self-hosted **OpenLink Server** (Node.js/TypeScript, in `server/`) over
HTTPS REST + a Socket.IO realtime channel.

Base URL: `https://<your-server>/api`

All request/response bodies are JSON. Timestamps are ISO-8601 UTC strings.
Durations are in whole minutes unless noted.

## Auth model

Two separate credentials:

- **Parent (family) JWT** — obtained via `/auth/login`, sent as
  `Authorization: Bearer <jwt>`. Identifies a `ParentUser` in a `Family`.
- **Device token** — obtained once via `/pairing/claim`, sent as
  `X-Device-Token: <token>` on every child-device request. Identifies a
  `ChildDevice` in a `Family`. Long-lived, stored locally on the Android
  device (Keystore-backed on Android, Keychain-backed would be used if a
  child app existed on iOS — it doesn't in this project).

There is no cross-family access: every resource lookup is scoped by the
caller's `familyId`.

## Data model (see `server/prisma/schema.prisma` for the source of truth)

- `Family` — one household. Has many `ParentUser`, `ChildDevice`.
- `ParentUser` — email + password (bcrypt hash), belongs to a `Family`.
- `ChildDevice` — a paired Android device: `name`, `platform`, `deviceToken`
  (hashed at rest), `lastSeenAt`, `isLocked`, `timezone`.
- `AppPolicy` — per `(ChildDevice, packageName)`: `appName`,
  `dailyLimitMinutes` (nullable = unlimited), `blocked` (bool, hard block
  regardless of time).
- `ScheduleWindow` — per `ChildDevice`: `daysOfWeek` (bitmask 0-127,
  bit0=Sunday), `startMinute`, `endMinute` (minutes since local midnight) —
  a "downtime"/bedtime window during which all non-exempt apps are blocked.
- `UsageRecord` — per `(ChildDevice, packageName, date)`: `minutesUsed`,
  updated by the child's periodic heartbeat (upsert, not append).
- `TimeRequest` — a child's ask for extra time: `packageName`,
  `minutesRequested`, `message`, `status` (`pending|approved|denied`),
  `grantedMinutes`, `responseNote` (parent's optional note, e.g. a denial
  reason), `respondedAt`.

## REST endpoints

### Parent auth
- `POST /auth/register` `{ email, password, familyName }` -> `{ token, familyId }`
- `POST /auth/login` `{ email, password }` -> `{ token }`

### Pairing (link an Android device to a family)
- `POST /pairing/generate` *(parent auth)* -> `{ code, expiresAt }`
  6-character alphanumeric code, valid 10 minutes, single use.
- `POST /pairing/claim` *(no auth)* `{ code, deviceName, platform }` ->
  `{ deviceToken, deviceId, familyId }`
  Called once by the Android app after the user types in the code shown on
  the parent's iOS app.

### Devices *(parent auth unless noted)*
- `GET /devices` -> list of `ChildDevice` summaries (name, lastSeenAt,
  isLocked, appCount)
- `GET /devices/:deviceId` -> full device detail incl. policies + today's usage
- `DELETE /devices/:deviceId` -> unpair (invalidates the device token)
- `POST /devices/:deviceId/lock` `{ locked: boolean }` -> updates `isLocked`,
  pushes `lock:update` over the socket to that device's room, and returns
  the updated device. The Android app enforces the actual screen lock via
  `DevicePolicyManager.lockNow()`.
- `PUT /devices/:deviceId/policies/:packageName`
  `{ dailyLimitMinutes?: number|null, blocked?: boolean }` -> upserts the
  policy and pushes `policy:update` to the device.
- `PUT /devices/:deviceId/schedule` `{ windows: ScheduleWindow[] }` ->
  replaces the device's downtime schedule, pushes `policy:update`.
- `GET /devices/:deviceId/usage?date=YYYY-MM-DD` -> per-app usage for that day.

### Child device self-service *(device-token auth)*
- `POST /device/apps` `{ apps: [{ packageName, appName }] }` — syncs the
  installed-app catalog so the parent app can show names, not just package IDs.
- `POST /device/usage` `{ date: "YYYY-MM-DD", usage: [{ packageName, minutesUsed }] }`
  — heartbeat, called every ~1 minute by the foreground service. Also
  updates `lastSeenAt`.
- `GET /device/policies` -> `{ policies: AppPolicy[], schedule: ScheduleWindow[], isLocked: boolean }`
  — used on cold start / reconnect to resync state; the socket carries
  live deltas after that.
- `POST /device/requests` `{ packageName, minutesRequested, message? }` ->
  creates a `TimeRequest`, pushes `request:new` to the parent's family room.
- `GET /device/requests` -> the device's own requests (for polling fallback
  if the socket is down), most recent first.

### Time requests *(parent auth)*
- `GET /requests?status=pending` -> list of `TimeRequest` across the family's devices
- `POST /requests/:id/approve` `{ grantedMinutes }` -> sets status
  `approved`, raises that app's effective limit for *today only* by
  `grantedMinutes`, pushes `request:decision` to the device.
- `POST /requests/:id/deny` `{ reason? }` -> sets status `denied`, pushes
  `request:decision` to the device.

## Realtime (Socket.IO, path `/socket.io`)

Auth: pass the same JWT or device token as a `token` field in the Socket.IO
`auth` payload on connect. The server puts the connection in one or both
rooms:
- `family:<familyId>` — parent app joins this; receives `device:heartbeat`,
  `request:new`.
- `device:<deviceId>` — that child device joins this; receives
  `policy:update`, `lock:update`, `request:decision`.

Events:
| Event | Direction | Payload |
|---|---|---|
| `policy:update` | server -> device | `{ policies, schedule }` (full resync, simplest to apply idempotently) |
| `lock:update` | server -> device | `{ isLocked }` |
| `request:new` | server -> parent | `TimeRequest` |
| `request:decision` | server -> device | `TimeRequest` |
| `device:heartbeat` | server -> parent | `{ deviceId, lastSeenAt }` |

Both apps must also work when the socket is disconnected: Android falls
back to polling `GET /device/policies` and `GET /device/requests` every
~30s while the enforcement service is running; iOS falls back to polling
`GET /devices` and `GET /requests` when the app is foregrounded.

## Enforcement semantics (implemented client-side, on Android)

1. Effective daily limit for `(device, package)` on a given date =
   `policy.dailyLimitMinutes` (if set) + sum of `grantedMinutes` from
   `approved` `TimeRequest`s for that package with `respondedAt` on that date.
2. If `policy.blocked` is true, the app is always blocked regardless of limit.
3. If the current local time falls inside any `ScheduleWindow`, all apps
   are blocked except the OpenLink app itself and a short allow-list
   (Phone, Settings, the default launcher — see
   `android/app/src/main/java/.../enforcement/AlwaysAllowed.kt`).
4. If `isLocked` is true, the blocking overlay is shown immediately
   regardless of usage, covering the whole screen until a parent unlocks.
5. `UsageRecord.minutesUsed` for today is tracked locally by the Android
   foreground service (via `UsageStatsManager`) and pushed to the server
   every heartbeat; the server is the source of truth for policies, the
   device is the source of truth for today's usage.
