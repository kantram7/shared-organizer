import { defineConfig } from "cf/config";
import * as entrypoint from "./retired-worker.mjs" with { type: "cf-worker" };

// Retired sender: an ordinary deployment MUST NOT restore cron or delivery code.
export default defineConfig({
 worker: {
  name: "shared-organizer-push",
  workersDev: false,
  compatibilityDate: "2026-11-17",
  entrypoint,
  triggers: [],
 },
});
