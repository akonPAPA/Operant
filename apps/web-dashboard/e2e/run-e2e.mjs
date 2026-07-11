/**
 * P1-B E2E runner: builds the dashboard as a production (non-demo) bundle and then runs
 * Playwright. The explicit env override beats any local .env.local demo configuration so
 * the tested bundle is the real production browser transport (same-origin /api/bff).
 */
import { spawnSync } from "node:child_process";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const nextBin = require.resolve("next/dist/bin/next");
const playwrightBin = require.resolve("@playwright/test/cli");

const overrides = {
  NEXT_PUBLIC_ORDERPILOT_DEMO_MODE: "false",
  ORDERPILOT_DEMO_MODE: "false"
};

function run(nodeArgs) {
  const result = spawnSync(process.execPath, nodeArgs, {
    stdio: "inherit",
    shell: false,
    env: { ...process.env, ...overrides }
  });
  if (result.error) {
    console.error(result.error);
    process.exit(1);
  }
  if (result.signal) {
    process.exit(1);
  }
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

run([nextBin, "build"]);
run([playwrightBin, "test"]);
