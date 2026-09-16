import { Router } from "express";
import { z } from "zod";
import { prisma } from "../db";
import { requireParentAuth } from "../auth";
import { emitToDevice } from "../realtime";

export const requestsRouter = Router();
requestsRouter.use(requireParentAuth);

requestsRouter.get("/", async (req, res) => {
  const status = typeof req.query.status === "string" ? req.query.status : undefined;
  const requests = await prisma.timeRequest.findMany({
    where: {
      status,
      device: { familyId: req.parent!.familyId },
    },
    include: { device: { select: { id: true, name: true } } },
    orderBy: { createdAt: "desc" },
    take: 100,
  });
  res.json(requests);
});

async function loadOwnedRequest(familyId: string, requestId: string) {
  return prisma.timeRequest.findFirst({
    where: { id: requestId, device: { familyId } },
    include: { device: true },
  });
}

const approveSchema = z.object({ grantedMinutes: z.number().int().min(1).max(24 * 60) });

requestsRouter.post("/:id/approve", async (req, res) => {
  const existing = await loadOwnedRequest(req.parent!.familyId, req.params.id);
  if (!existing) {
    res.status(404).json({ error: "Request not found" });
    return;
  }
  const parsed = approveSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.flatten() });
    return;
  }

  const updated = await prisma.timeRequest.update({
    where: { id: existing.id },
    data: {
      status: "approved",
      grantedMinutes: parsed.data.grantedMinutes,
      respondedAt: new Date(),
    },
  });

  emitToDevice(existing.deviceId, "request:decision", updated);
  res.json(updated);
});

const denySchema = z.object({ reason: z.string().max(500).optional() });

requestsRouter.post("/:id/deny", async (req, res) => {
  const existing = await loadOwnedRequest(req.parent!.familyId, req.params.id);
  if (!existing) {
    res.status(404).json({ error: "Request not found" });
    return;
  }
  const parsed = denySchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.flatten() });
    return;
  }

  const updated = await prisma.timeRequest.update({
    where: { id: existing.id },
    data: { status: "denied", respondedAt: new Date(), responseNote: parsed.data.reason },
  });

  emitToDevice(existing.deviceId, "request:decision", updated);
  res.json(updated);
});
