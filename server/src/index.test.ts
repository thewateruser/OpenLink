import { afterAll, beforeAll, describe, expect, it } from "vitest";
import request from "supertest";
import { execSync } from "node:child_process";
import fs from "node:fs";
import { createApp } from "./index";

// DATABASE_URL/JWT_SECRET point at a throwaway SQLite file — see vitest.config.ts,
// which sets them before any module (and its module-scope PrismaClient) loads.
const dbFile = "test.db";

beforeAll(() => {
  execSync("npx prisma db push --skip-generate", {
    env: { ...process.env },
    stdio: "inherit",
  });
});

afterAll(() => {
  for (const suffix of ["", "-journal"]) {
    if (fs.existsSync(dbFile + suffix)) fs.unlinkSync(dbFile + suffix);
  }
});

describe("OpenLink server end-to-end flow", () => {
  const app = createApp();

  it("registers a family, pairs a device, sets a policy, requests and approves time", async () => {
    const register = await request(app)
      .post("/api/auth/register")
      .send({ email: "parent@example.com", password: "supersecret1", familyName: "The Testersons" });
    expect(register.status).toBe(201);
    const parentToken = register.body.token as string;
    expect(parentToken).toBeTruthy();

    const pairing = await request(app)
      .post("/api/pairing/generate")
      .set("Authorization", `Bearer ${parentToken}`)
      .send();
    expect(pairing.status).toBe(201);
    expect(pairing.body.code).toMatch(/^[A-Z0-9]{6}$/);

    const claim = await request(app)
      .post("/api/pairing/claim")
      .send({ code: pairing.body.code, deviceName: "Kid's Phone", platform: "android" });
    expect(claim.status).toBe(201);
    const deviceToken = claim.body.deviceToken as string;
    const deviceId = claim.body.deviceId as string;
    expect(deviceToken).toBeTruthy();

    // Re-claiming the same code must fail (single use).
    const reclaim = await request(app)
      .post("/api/pairing/claim")
      .send({ code: pairing.body.code, deviceName: "Second Phone", platform: "android" });
    expect(reclaim.status).toBe(400);

    const setPolicy = await request(app)
      .put(`/api/devices/${deviceId}/policies/com.example.game`)
      .set("Authorization", `Bearer ${parentToken}`)
      .send({ dailyLimitMinutes: 30, appName: "Example Game" });
    expect(setPolicy.status).toBe(200);
    expect(setPolicy.body.dailyLimitMinutes).toBe(30);

    const devicePolicies = await request(app)
      .get("/api/device/policies")
      .set("X-Device-Token", deviceToken);
    expect(devicePolicies.status).toBe(200);
    expect(devicePolicies.body.policies).toHaveLength(1);
    expect(devicePolicies.body.policies[0].dailyLimitMinutes).toBe(30);
    expect(devicePolicies.body.isLocked).toBe(false);

    const lock = await request(app)
      .post(`/api/devices/${deviceId}/lock`)
      .set("Authorization", `Bearer ${parentToken}`)
      .send({ locked: true });
    expect(lock.status).toBe(200);
    expect(lock.body.isLocked).toBe(true);

    const heartbeat = await request(app)
      .post("/api/device/usage")
      .set("X-Device-Token", deviceToken)
      .send({ date: "2026-09-16", usage: [{ packageName: "com.example.game", minutesUsed: 25 }] });
    expect(heartbeat.status).toBe(204);

    const usage = await request(app)
      .get(`/api/devices/${deviceId}/usage?date=2026-09-16`)
      .set("Authorization", `Bearer ${parentToken}`);
    expect(usage.body.usage[0].minutesUsed).toBe(25);

    const timeRequest = await request(app)
      .post("/api/device/requests")
      .set("X-Device-Token", deviceToken)
      .send({ packageName: "com.example.game", minutesRequested: 15, message: "please" });
    expect(timeRequest.status).toBe(201);
    const requestId = timeRequest.body.id as string;

    const pending = await request(app)
      .get("/api/requests?status=pending")
      .set("Authorization", `Bearer ${parentToken}`);
    expect(pending.body).toHaveLength(1);

    const approve = await request(app)
      .post(`/api/requests/${requestId}/approve`)
      .set("Authorization", `Bearer ${parentToken}`)
      .send({ grantedMinutes: 15 });
    expect(approve.status).toBe(200);
    expect(approve.body.status).toBe("approved");
    expect(approve.body.grantedMinutes).toBe(15);

    // A different family must not be able to see or act on this device/request.
    const otherFamily = await request(app)
      .post("/api/auth/register")
      .send({ email: "other@example.com", password: "supersecret1", familyName: "Other Family" });
    const otherToken = otherFamily.body.token as string;

    const forbiddenDevice = await request(app)
      .get(`/api/devices/${deviceId}`)
      .set("Authorization", `Bearer ${otherToken}`);
    expect(forbiddenDevice.status).toBe(404);

    const forbiddenLock = await request(app)
      .post(`/api/devices/${deviceId}/lock`)
      .set("Authorization", `Bearer ${otherToken}`)
      .send({ locked: false });
    expect(forbiddenLock.status).toBe(404);
  });

  it("rejects bad credentials and missing tokens", async () => {
    const badLogin = await request(app)
      .post("/api/auth/login")
      .send({ email: "nope@example.com", password: "wrong" });
    expect(badLogin.status).toBe(401);

    const noAuth = await request(app).get("/api/devices");
    expect(noAuth.status).toBe(401);

    const noDeviceToken = await request(app).get("/api/device/policies");
    expect(noDeviceToken.status).toBe(401);
  });
});
