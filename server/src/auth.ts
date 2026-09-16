import crypto from "node:crypto";
import { Request, Response, NextFunction } from "express";
import jwt from "jsonwebtoken";
import { prisma } from "./db";
import { env } from "./env";

export interface ParentJwtPayload {
  parentId: string;
  familyId: string;
}

export function signParentToken(payload: ParentJwtPayload): string {
  return jwt.sign(payload, env.jwtSecret, { expiresIn: "30d" });
}

export function verifyParentToken(token: string): ParentJwtPayload {
  return jwt.verify(token, env.jwtSecret) as ParentJwtPayload;
}

/** Device tokens are opaque random strings; only their SHA-256 hash is stored. */
export function generateDeviceToken(): string {
  return crypto.randomBytes(32).toString("base64url");
}

export function hashDeviceToken(token: string): string {
  return crypto.createHash("sha256").update(token).digest("hex");
}

export function generatePairingCode(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no ambiguous chars
  let code = "";
  for (let i = 0; i < 6; i++) {
    code += alphabet[crypto.randomInt(alphabet.length)];
  }
  return code;
}

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Express {
    interface Request {
      parent?: ParentJwtPayload;
      device?: { id: string; familyId: string };
    }
  }
}

export function requireParentAuth(req: Request, res: Response, next: NextFunction) {
  const header = req.header("authorization");
  if (!header?.startsWith("Bearer ")) {
    res.status(401).json({ error: "Missing bearer token" });
    return;
  }
  try {
    req.parent = verifyParentToken(header.slice("Bearer ".length));
    next();
  } catch {
    res.status(401).json({ error: "Invalid or expired token" });
  }
}

export async function requireDeviceAuth(req: Request, res: Response, next: NextFunction) {
  const token = req.header("x-device-token");
  if (!token) {
    res.status(401).json({ error: "Missing X-Device-Token header" });
    return;
  }
  const device = await prisma.childDevice.findUnique({
    where: { deviceTokenHash: hashDeviceToken(token) },
  });
  if (!device) {
    res.status(401).json({ error: "Invalid device token" });
    return;
  }
  req.device = { id: device.id, familyId: device.familyId };
  next();
}
