import http from "node:http";
import express from "express";
import cors from "cors";
import { env } from "./env";
import { initRealtime } from "./realtime";
import { authRouter } from "./routes/auth";
import { pairingRouter } from "./routes/pairing";
import { devicesRouter } from "./routes/devices";
import { deviceRouter } from "./routes/device";
import { requestsRouter } from "./routes/requests";

export function createApp() {
  const app = express();
  app.use(cors());
  app.use(express.json());

  app.get("/api/health", (_req, res) => res.json({ ok: true }));

  app.use("/api/auth", authRouter);
  app.use("/api/pairing", pairingRouter);
  app.use("/api/devices", devicesRouter);
  app.use("/api/device", deviceRouter);
  app.use("/api/requests", requestsRouter);

  app.use((_req, res) => {
    res.status(404).json({ error: "Not found" });
  });

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  app.use((err: any, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
    console.error(err);
    res.status(500).json({ error: "Internal server error" });
  });

  return app;
}

if (require.main === module) {
  const app = createApp();
  const httpServer = http.createServer(app);
  initRealtime(httpServer);

  httpServer.listen(env.port, () => {
    console.log(`OpenLink server listening on :${env.port}`);
  });
}
