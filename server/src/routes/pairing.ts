import { Router } from "express";
import { z } from "zod";
import { prisma } from "../db";
import { requireParentAuth, generatePairingCode, generateDeviceToken, hashDeviceToken } from "../auth";
import { env } from "../env";

export const pairingRouter = Router();

pairingRouter.post("/generate", requireParentAuth, async (req, res) => {
  const familyId = req.parent!.familyId;

  const expiresAt = new Date(Date.now() + env.pairingCodeTtlMinutes * 60_000);
  // Retry on the (astronomically unlikely) unique-code collision.
  for (let attempt = 0; attempt < 5; attempt++) {
    try {
      const pairing = await prisma.pairingCode.create({
        data: { code: generatePairingCode(), familyId, expiresAt },
      });
      res.status(201).json({ code: pairing.code, expiresAt: pairing.expiresAt });
      return;
    } catch (err: any) {
      if (err?.code !== "P2002") throw err;
    }
  }
  res.status(500).json({ error: "Could not generate a unique pairing code, try again" });
});

const claimSchema = z.object({
  code: z.string().length(6),
  deviceName: z.string().min(1),
  platform: z.string().default("android"),
});

pairingRouter.post("/claim", async (req, res) => {
  const parsed = claimSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.flatten() });
    return;
  }
  const { code, deviceName, platform } = parsed.data;

  const pairing = await prisma.pairingCode.findUnique({ where: { code: code.toUpperCase() } });
  if (!pairing || pairing.claimedAt || pairing.expiresAt < new Date()) {
    res.status(400).json({ error: "Pairing code is invalid or expired" });
    return;
  }

  const deviceToken = generateDeviceToken();
  const [device] = await prisma.$transaction([
    prisma.childDevice.create({
      data: {
        name: deviceName,
        platform,
        deviceTokenHash: hashDeviceToken(deviceToken),
        familyId: pairing.familyId,
      },
    }),
    prisma.pairingCode.update({ where: { id: pairing.id }, data: { claimedAt: new Date() } }),
  ]);

  res.status(201).json({ deviceToken, deviceId: device.id, familyId: device.familyId });
});
