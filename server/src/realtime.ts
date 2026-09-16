import type { Server as HttpServer } from "node:http";
import { Server, Socket } from "socket.io";
import { hashDeviceToken, verifyParentToken } from "./auth";
import { prisma } from "./db";

let io: Server | undefined;

export function initRealtime(httpServer: HttpServer): Server {
  io = new Server(httpServer, {
    path: "/socket.io",
    cors: { origin: "*" },
  });

  io.use(async (socket: Socket, next) => {
    const token = socket.handshake.auth?.token as string | undefined;
    if (!token) return next(new Error("missing auth token"));

    // Try parent JWT first, then fall back to a device token.
    try {
      const payload = verifyParentToken(token);
      socket.data.role = "parent";
      socket.data.familyId = payload.familyId;
      return next();
    } catch {
      // not a valid parent token, fall through to device-token check
    }

    const device = await prisma.childDevice.findUnique({
      where: { deviceTokenHash: hashDeviceToken(token) },
    });
    if (device) {
      socket.data.role = "device";
      socket.data.deviceId = device.id;
      socket.data.familyId = device.familyId;
      return next();
    }

    next(new Error("invalid auth token"));
  });

  io.on("connection", (socket: Socket) => {
    if (socket.data.role === "parent") {
      socket.join(`family:${socket.data.familyId}`);
    } else if (socket.data.role === "device") {
      socket.join(`device:${socket.data.deviceId}`);
    }
  });

  return io;
}

/**
 * Realtime is a best-effort push channel on top of a REST API that's
 * fully functional on its own (both apps poll as a fallback), so a missing
 * socket server (e.g. under test, where createApp() is used without
 * initRealtime()) degrades to a no-op instead of failing the request.
 */
export function emitToFamily(familyId: string, event: string, payload: unknown) {
  io?.to(`family:${familyId}`).emit(event, payload);
}

export function emitToDevice(deviceId: string, event: string, payload: unknown) {
  io?.to(`device:${deviceId}`).emit(event, payload);
}
