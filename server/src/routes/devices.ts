import { Router } from "express";
import { z } from "zod";
import { prisma } from "../db";
import { requireParentAuth } from "../auth";
import { emitToDevice } from "../realtime";
import { buildPolicyPayload } from "../policyPayload";

export const devicesRouter = Router();
devicesRouter.use(requireParentAuth);

async function loadOwnedDevice(familyId: string, deviceId: string) {
  const device = await prisma.childDevice.findFirst({ where: { id: deviceId, familyId } });
  return device;
}

devicesRouter.get("/", async (req, res) => {
  const devices = await prisma.childDevice.findMany({
    where: { familyId: req.parent!.familyId },
    include: { _count: { select: { policies: true } } },
    orderBy: { createdAt: "asc" },
  });
  res.json(
    devices.map((d) => ({
      id: d.id,
      name: d.name,
      platform: d.platform,
      lastSeenAt: d.lastSeenAt,
      isLocked: d.isLocked,
      appCount: d._count.policies,
    })),
  );
});

devicesRouter.get("/:deviceId", async (req, res) => {
  const device = await loadOwnedDevice(req.parent!.familyId, req.params.deviceId);
  if (!device) {
    res.status(404).json({ error: "Device not found" });
    return;
  }
  const today = new Date().toISOString().slice(0, 10);
  const [policies, schedule, usage] = await Promise.all([
    prisma.appPolicy.findMany({ where: { deviceId: device.id } }),
    prisma.scheduleWindow.findMany({ where: { deviceId: device.id } }),
    prisma.usageRecord.findMany({ where: { deviceId: device.id, date: today } }),
  ]);
  res.json({
    id: device.id,
    name: device.name,
    platform: device.platform,
    lastSeenAt: device.lastSeenAt,
    isLocked: device.isLocked,
    timezone: device.timezone,
    policies,
    schedule,
    todayUsage: usage,
  });
});

devicesRouter.delete("/:deviceId", async (req, res) => {
  const device = await loadOwnedDevice(req.parent!.familyId, req.params.deviceId);
  if (!device) {
    res.status(404).json({ error: "Device not found" });
    return;
  }
  await prisma.childDevice.delete({ where: { id: device.id } });
  res.status(204).send();
});

const lockSchema = z.object({ locked: z.boolean() });

devicesRouter.post("/:deviceId/lock", async (req, res) => {
  const device = await loadOwnedDevice(req.parent!.familyId, req.params.deviceId);
  if (!device) {
    res.status(404).json({ error: "Device not found" });
    return;
  }
  const parsed = lockSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.flatten() });
    return;
  }
  const updated = await prisma.childDevice.update({
    where: { id: device.id },
    data: { isLocked: parsed.data.locked },
  });
  emitToDevice(device.id, "lock:update", { isLocked: updated.isLocked });
  res.json({ id: updated.id, isLocked: updated.isLocked });
});

const policySchema = z.object({
  dailyLimitMinutes: z.number().int().min(0).nullable().optional(),
  blocked: z.boolean().optional(),
  appName: z.string().optional(),
});

devicesRouter.put("/:deviceId/policies/:packageName", async (req, res) => {
  const device = await loadOwnedDevice(req.parent!.familyId, req.params.deviceId);
  if (!device) {
    res.status(404).json({ error: "Device not found" });
    return;
  }
  const parsed = policySchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.flatten() });
    return;
  }
  const { packageName } = req.params;
  const policy = await prisma.appPolicy.upsert({
    where: { deviceId_packageName: { deviceId: device.id, packageName } },
    create: { deviceId: device.id, packageName, ...parsed.data },
    update: parsed.data,
  });

  emitToDevice(device.id, "policy:update", await buildPolicyPayload(device.id));
  res.json(policy);
});

const scheduleSchema = z.object({
  windows: z.array(
    z.object({
      daysOfWeek: z.number().int().min(0).max(127),
      startMinute: z.number().int().min(0).max(1439),
      endMinute: z.number().int().min(0).max(1439),
      label: z.string().optional(),
    }),
  ),
});

devicesRouter.put("/:deviceId/schedule", async (req, res) => {
  const device = await loadOwnedDevice(req.parent!.familyId, req.params.deviceId);
  if (!device) {
    res.status(404).json({ error: "Device not found" });
    return;
  }
  const parsed = scheduleSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.flatten() });
    return;
  }

  await prisma.$transaction([
    prisma.scheduleWindow.deleteMany({ where: { deviceId: device.id } }),
    prisma.scheduleWindow.createMany({
      data: parsed.data.windows.map((w) => ({ ...w, deviceId: device.id })),
    }),
  ]);

  const payload = await buildPolicyPayload(device.id);
  emitToDevice(device.id, "policy:update", payload);
  res.json({ windows: payload.schedule });
});

devicesRouter.get("/:deviceId/usage", async (req, res) => {
  const device = await loadOwnedDevice(req.parent!.familyId, req.params.deviceId);
  if (!device) {
    res.status(404).json({ error: "Device not found" });
    return;
  }
  const date = typeof req.query.date === "string" ? req.query.date : new Date().toISOString().slice(0, 10);
  const usage = await prisma.usageRecord.findMany({ where: { deviceId: device.id, date } });
  res.json({ date, usage });
});
