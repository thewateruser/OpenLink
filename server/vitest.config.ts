import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    env: {
      DATABASE_URL: "file:./test.db",
      JWT_SECRET: "test-secret",
    },
    testTimeout: 15000,
  },
});
