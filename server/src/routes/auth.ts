import { Router } from "express";
import bcrypt from "bcryptjs";
import { z } from "zod";
import { prisma } from "../db";
import { signParentToken } from "../auth";

export const authRouter = Router();

const registerSchema = z.object({
  email: z.string().email(),
  password: z.string().min(8),
  familyName: z.string().min(1),
});

authRouter.post("/register", async (req, res) => {
  const parsed = registerSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.flatten() });
    return;
  }
  const { email, password, familyName } = parsed.data;

  const existing = await prisma.parentUser.findUnique({ where: { email } });
  if (existing) {
    res.status(409).json({ error: "An account with that email already exists" });
    return;
  }

  const passwordHash = await bcrypt.hash(password, 12);
  const family = await prisma.family.create({
    data: {
      name: familyName,
      parents: { create: { email, passwordHash } },
    },
    include: { parents: true },
  });

  const parent = family.parents[0];
  const token = signParentToken({ parentId: parent.id, familyId: family.id });
  res.status(201).json({ token, familyId: family.id });
});

const loginSchema = z.object({
  email: z.string().email(),
  password: z.string(),
});

authRouter.post("/login", async (req, res) => {
  const parsed = loginSchema.safeParse(req.body);
  if (!parsed.success) {
    res.status(400).json({ error: parsed.error.flatten() });
    return;
  }
  const { email, password } = parsed.data;

  const parent = await prisma.parentUser.findUnique({ where: { email } });
  if (!parent || !(await bcrypt.compare(password, parent.passwordHash))) {
    res.status(401).json({ error: "Invalid email or password" });
    return;
  }

  const token = signParentToken({ parentId: parent.id, familyId: parent.familyId });
  res.json({ token });
});
