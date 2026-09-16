# OpenLink Server

The backend the OpenLink Android (child) app and OpenLink iOS (parent) app
both talk to. Node.js + TypeScript + Express + Prisma (SQLite by default) +
Socket.IO. See [`../docs/API.md`](../docs/API.md) for the full API contract.

This is meant to be **self-hosted** — one instance per family (or a few
families if you don't mind them sharing a server; data is scoped per-family
so it's safe, just not a polished multi-tenant SaaS).

## Local development

```bash
npm install
cp .env.example .env        # edit JWT_SECRET before deploying anywhere real
npx prisma migrate dev      # creates dev.db and applies the schema
npm run dev                 # runs on :4000 with auto-reload
```

Verify it's up: `curl localhost:4000/api/health` -> `{"ok":true}`.

## Tests

```bash
npm test
```

`src/index.test.ts` runs a full flow against an in-process app and a
throwaway SQLite file (register a family, generate + claim a pairing code,
set an app policy, push a usage heartbeat, submit and approve a time
request, confirm cross-family isolation, confirm auth is enforced).

## Production build

```bash
npm run build     # tsc -> dist/
npm start         # node dist/index.js (expects DATABASE_URL/JWT_SECRET/PORT env vars)
```

Or via Docker, from the repo root:

```bash
echo "JWT_SECRET=$(openssl rand -hex 32)" > .env
docker compose up --build
```

That builds `server/Dockerfile`, runs `prisma migrate deploy` on container
start, and persists the SQLite file in a named volume.

## Notes / current limitations

- SQLite is the default datasource for simplicity; swap the `datasource`
  block in `prisma/schema.prisma` to `postgresql` if you want to run this
  for more than one household behind a real deployment.
- There's no rate limiting or account lockout on `/auth/login` — add one
  (e.g. `express-rate-limit`) before exposing this on the open internet.
- Push notifications are not implemented server-side (see `ios/README.md`)
  — the iOS app relies on Socket.IO + polling instead.
- CORS is wide open (`origin: "*"`) since the clients are native mobile
  apps, not browsers; tighten this if you add a web client.
