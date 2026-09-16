import { Router } from "express";
import { z } from "zod";
import { prisma } from "../db";
import { requireDeviceAuth } from "../auth";
import { emitToFamily } from "../realtime";
import { buildPolicyPayload } from "../policyPayload";

/** Self-service endpoints called by the paired Android device itself. */
export const deviceRouter = Router();
deviceRouter.use(requireDeviceAuth);

const appsSchema = z.object({
  apps: z.array(z.object({ packageName: z.string().min(1), appName: z.string().min(1) })),
});

deviceRouter.post("/apps", async (req, res) => {
  const parsed = appsSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.flatten() });
    return;
  }
  const deviceId = req.device!.id;

  await prisma.$transaction(
    parsed.data.apps.map((app) =>
      prisma.appPolicy.upsert({
        where: { deviceId_packageName: { deviceId, packageName: app.packageName } },
        create: { deviceId, packageName: app.packageName, appName: app.appName },
        update: { appName: app.appName },
      }),
    ),
  );

  await prisma.childDevice.update({ where: { id: deviceId }, data: { lastSeenAt: new Date() } });
  res.status(204).send();
});

const usageSchema = z.object({
  date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  usage: z.array(z.object({ packageName: z.string().min(1), minutesUsed: z.number().int().min(0) })),
});

deviceRouter.post("/usage", async (req, res) => {
  const parsed = usageSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.flatten() });
    return;
  }
  const deviceId = req.device!.id;
  const { date, usage } = parsed.data;

  await prisma.$transaction(
    usage.map((u) =>
      prisma.usageRecord.upsert({
        where: { deviceId_packageName_date: { deviceId, packageName: u.packageName, date } },
        create: { deviceId, packageName: u.packageName, date, minutesUsed: u.minutesUsed },
        update: { minutesUsed: u.minutesUsed },
      }),
    ),
  );

  const device = await prisma.childDevice.update({
    where: { id: deviceId },
    data: { lastSeenAt: new Date() },
  });

  emitToFamily(device.familyId, "device:heartbeat", { deviceId, lastSeenAt: device.lastSeenAt });
  res.status(204).send();
});

deviceRouter.get("/policies", async (req, res) => {
  res.json(await buildPolicyPayload(req.device!.id));
});

const requestSchema = z.object({
  packageName: z.string().min(1),
  minutesRequested: z.number().int().min(1).max(24 * 60),
  message: z.string().max(500).optional(),
});

deviceRouter.post("/requests", async (req, res) => {
  const parsed = requestSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.flatten() });
    return;
  }
  const device = await prisma.childDevice.findUniqueOrThrow({ where: { id: req.device!.id } });

  const timeRequest = await prisma.timeRequest.create({
    data: { deviceId: device.id, ...parsed.data },
  });

  emitToFamily(device.familyId, "request:new", timeRequest);
  res.status(201).json(timeRequest);
});

deviceRouter.get("/requests", async (req, res) => {
  const requests = await prisma.timeRequest.findMany({
    where: { deviceId: req.device!.id },
    orderBy: { createdAt: "desc" },
    take: 50,
  });
  res.json(requests);
});
