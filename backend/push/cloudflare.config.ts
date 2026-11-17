import { bindings, defineConfig, triggers } from "cf/config";
import * as entrypoint from "./worker.mjs" with { type: "cf-worker" };

export default defineConfig({
  worker: {
    name: "shared-organizer-push",
    workersDev: false,
    observability: { enabled: true, redactQueryString: true, traces: { enabled: true, headSamplingRate: 0.01 } },
    compatibilityDate: "2026-11-17",
    entrypoint,
    env: {
      GOOGLE_SERVICE_ACCOUNT: bindings.secret(),
      FIREBASE_PROJECT: bindings.secret(),
      SPACE_ID: bindings.secret(),
    },
    triggers: [triggers.scheduled({ schedule: "* * * * *" })],
  },
});
