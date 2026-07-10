/**
 * P1-B E2E runner: builds the dashboard as a production (non-demo) bundle and then runs
 * Playwright. The explicit env override beats any local .env.local demo configuration so
 * the tested bundle is the real production browser transport (same-origin /api/bff).
 */
import { spawnSync } from "node:child_process";

const overrides = {
  NEXT_PUBLIC_ORDERPILOT_DEMO_MODE: "false",
  ORDERPILOT_DEMO_MODE: "false"
};

function run(command, args) {
  const result = spawnSync(command, args, {
    stdio: "inherit",
    shell: process.platform === "win32",
    env: { ...process.env, ...overrides }
  });
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }
}

run("npx", ["next", "build"]);
run("npx", ["playwright", "test"]);
