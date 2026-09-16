import { prisma } from "./db";

/** The full-resync shape pushed as `policy:update` and returned by GET /device/policies. */
export async function buildPolicyPayload(deviceId: string) {
  const [device, policies, schedule] = await Promise.all([
    prisma.childDevice.findUniqueOrThrow({ where: { id: deviceId } }),
    prisma.appPolicy.findMany({ where: { deviceId } }),
    prisma.scheduleWindow.findMany({ where: { deviceId } }),
  ]);

  return {
    policies: policies.map((p) => ({
      packageName: p.packageName,
      appName: p.appName,
      dailyLimitMinutes: p.dailyLimitMinutes,
      blocked: p.blocked,
    })),
    schedule: schedule.map((s) => ({
      id: s.id,
      daysOfWeek: s.daysOfWeek,
      startMinute: s.startMinute,
      endMinute: s.endMinute,
      label: s.label,
    })),
    isLocked: device.isLocked,
  };
}
