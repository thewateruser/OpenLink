# OpenLink P2P Protocol

OpenLink has **no server**. The Android child device *is* the server: it
hosts a small TLS-secured HTTP + WebSocket listener inside the foreground
service it already runs for enforcement, and the iOS parent app connects to
it directly.

```
   iOS parent app                                    Android child device
   ──────────────                                    ────────────────────
   client                                            embedded Ktor listener
   Bonjour browse (_openlink._tcp)  ────────────────> NSD advertise
   scan pairing QR                  ────────────────> show pairing QR
   HTTPS + WSS (pinned cert, bearer token)  <───────> :8765 (default)
```

The child device is the **source of truth** for everything: policies,
schedules, usage tallies, and time requests all live in its local Room
database. The parent app is a remote control with a cache, not an authority.
This falls out naturally from having no server — and it's also what makes
enforcement keep working when nothing is connected at all.

## Transport and reachability

There is exactly one code path: *dial an IP, speak TLS, present a token.*
What changes between "at home" and "away" is only which address is dialed.

| Situation | How the parent reaches the child |
|---|---|
| Same Wi-Fi | mDNS/Bonjour discovery of `_openlink._tcp`, then the LAN IP |
| Away from home | A stable overlay-network address (Tailscale/WireGuard) |
| Manual | A host:port the user types in, for anything else |

The child listens on `0.0.0.0` so LAN and overlay interfaces are both served
by the same socket. Away-from-home access requires the user to put both
devices on an overlay network — see the root README. That is not "hosting a
server": no VPS, no domain, no deployment, no maintenance.

### Endpoint learning

Every authenticated response to `GET /device` includes the child's current
reachable addresses (all non-loopback interface addresses, plus its NSD
hostname). The parent app merges these into a per-device endpoint list and
persists it.

This means a device paired on home Wi-Fi **automatically learns its overlay
address** the first time it connects while the overlay is up — the user
doesn't configure a remote address by hand. On connect the parent races its
known endpoints (LAN first, since it's lowest latency) and uses whichever
completes the TLS handshake first.

## Identity and pairing

Pairing has to survive an attacker sitting on the same network, so the QR
code is used as an out-of-band channel to authenticate the TLS certificate.

**Child device, on first run:**
1. Generates a long-lived self-signed TLS certificate (P-256), private key
   stored in the Android Keystore, non-exportable.
2. Generates a random `deviceId`.

**Pairing (parent scans a QR shown on the child's screen):**

The QR encodes a URI:

```
openlink://pair?v=1
  &id=<deviceId>
  &name=<url-encoded device name>
  &fp=<base64url(SHA-256(certificate DER))>
  &psk=<base64url(32 random bytes)>
  &ep=<host:port,host:port,...>
```

- `psk` is a **single-use pairing secret**, freshly generated each time the
  pairing screen opens, valid for 5 minutes, and destroyed after one
  successful pairing or on expiry.
- `fp` is the certificate fingerprint. The parent pins **exactly** this
  fingerprint for this device, forever.

Then:

1. Parent dials an endpoint from `ep` over TLS, and **aborts unless the
   presented certificate's SHA-256 matches `fp`**. Because `fp` arrived
   out-of-band via the QR, this defeats an active MITM — an attacker would
   have to present the child's actual certificate, which never leaves the
   Keystore.
2. Parent generates a random 32-byte `parentId` and proves it saw the screen:
   ```
   proof = base64url(HMAC-SHA256(key = psk, msg = "openlink-pair-v1" || parentId))
   ```
3. `POST /pair` `{ parentId, parentName, proof }`
4. Child recomputes the HMAC, compares in constant time, and on success
   returns `{ deviceId, deviceName, parentToken, endpoints }` where
   `parentToken` is 32 fresh random bytes, base64url-encoded.
5. Parent stores `parentToken` + `fp` in the iOS Keychain. Child stores the
   token's SHA-256 hash (not the token) in EncryptedSharedPreferences.

`psk` is invalidated immediately after step 4. The pairing endpoint is the
only unauthenticated route, and it is rate-limited (5 attempts/minute,
rejecting all attempts once a valid pairing completes and the screen closes).

## Authentication (all other routes)

Every request carries `Authorization: Bearer <parentToken>` over the pinned
TLS connection. The child compares `SHA-256(presented token)` against its
stored hash **in constant time**, and rejects with `401` otherwise. Failed
auth attempts are rate-limited.

Multiple parents can pair with one child (each gets its own token). Any
paired parent can revoke another, or unpair itself, via `DELETE /pair`.

## REST API (served by the child device)

All bodies are JSON. Durations are whole minutes. Timestamps are ISO-8601
UTC. Base path is the root — e.g. `https://100.101.102.103:8765/device`.

### Unauthenticated
- `POST /pair` — see pairing above. The only unauthenticated route.

### Device
- `GET /device` -> `{ deviceId, deviceName, isLocked, platform, appVersion,
  endpoints: string[], batteryLevel, lastBootAt }`
  `endpoints` is what drives endpoint learning (see above).
- `POST /device/lock` `{ locked: boolean }` -> `{ isLocked }`
  On `true` the child calls `DevicePolicyManager.lockNow()` and raises the
  blocking overlay. Broadcast to other connected parents over the WebSocket.
- `DELETE /pair` `{ parentId?: string }` -> `204`. Omit `parentId` to unpair
  the calling parent; pass one to revoke a different parent.

### Apps and policies
- `GET /apps` -> `[{ packageName, appName, isSystemApp, policy, todayMinutes }]`
  The child enumerates installed launchable apps itself — there's no
  catalogue-sync step, since the data never has to travel anywhere to be
  stored.
- `PUT /policies/{packageName}` `{ dailyLimitMinutes?: number|null, blocked?: boolean }`
  -> the updated policy. `null` clears a limit (unlimited); omitting a field
  leaves it unchanged.
- `GET /schedule` -> `{ windows: ScheduleWindow[] }`
- `PUT /schedule` `{ windows: ScheduleWindow[] }` -> `{ windows }`
  Replaces the whole set. `ScheduleWindow` is
  `{ id?, daysOfWeek (bitmask, bit0=Sunday), startMinute, endMinute, label? }`,
  minute-of-day in the **device's local time**.

### Usage
- `GET /usage?date=YYYY-MM-DD` -> `{ date, usage: [{ packageName, minutesUsed }] }`
  Defaults to today. The child retains 30 days locally.

### Time requests
- `GET /requests?status=pending|approved|denied` -> `TimeRequest[]`
  (omit `status` for all; most recent first, capped at 100)
- `POST /requests/{id}/approve` `{ grantedMinutes }` -> the updated request.
  Takes effect on the child immediately — it's the same process.
- `POST /requests/{id}/deny` `{ reason? }` -> the updated request.

`TimeRequest` is `{ id, packageName, appName, minutesRequested, message?,
status, grantedMinutes?, responseNote?, createdAt, respondedAt? }`.

## WebSocket (`/events`)

The parent opens `wss://<endpoint>/events` on the same pinned TLS
connection, authenticating with the same bearer token in the
`Authorization` header. Messages are JSON `{ type, payload }`.

Child -> parent:

| `type` | `payload` | When |
|---|---|---|
| `request:new` | `TimeRequest` | The kid asks for more time |
| `usage:update` | `{ packageName, minutesUsed, date }` | Each usage tick (~1/min) |
| `lock:update` | `{ isLocked }` | Lock state changed (incl. by another parent) |
| `policy:update` | `{ policies, schedule }` | Changed by another parent |
| `device:state` | `{ batteryLevel, endpoints }` | Every ~60s, doubles as a keepalive |

The WebSocket is a live-update convenience, not a requirement: the parent
app re-fetches over REST on foreground and every 30s whenever the socket
isn't connected. Nothing depends on it being up.

## What happens when nothing is connected

This is the normal case, and it's fine:

- **Enforcement continues unaffected.** Policies, schedules and today's
  tallies are already on the child device; the foreground service never
  needed the network to do its job.
- **Time requests queue locally.** The kid can file one any time; it sits in
  the child's database with `status: pending` and is delivered the moment a
  parent connects (live over the WebSocket, or in the `GET /requests`
  response).
- **Remote lock requires a connection**, necessarily. A lock can't be
  delivered to a device that can't be reached.

## Deliberate limitations

- **No push notifications.** Waking a closed iOS app requires APNs, which
  requires a provider server with Apple-issued credentials. Shipping those
  credentials inside the child app would be a serious vulnerability, so
  OpenLink doesn't. The parent sees pending requests when they open the app.
  If the app is merely backgrounded and the socket is still alive, it raises
  a local notification.
- **No NAT hole-punching.** Mobile carriers are nearly universally behind
  CGNAT, where hole-punching fails and needs a TURN relay — i.e. a server.
  An overlay network solves the same problem reliably, so OpenLink uses
  that instead of failing unpredictably.
- **A child device with physical access can be defeated**, by revoking
  accessibility/device-admin permissions or uninstalling. This is true of
  every non-MDM parental control app on Android, OpenLink included. Real
  tamper-resistance needs Android Enterprise Device Owner provisioning,
  which is a much larger undertaking.
