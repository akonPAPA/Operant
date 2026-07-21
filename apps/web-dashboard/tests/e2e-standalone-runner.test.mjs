import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

test("E2E runner and standalone launcher avoid shell and network installation", () => {
  const runE2e = readFileSync(join(root, "e2e", "run-e2e.mjs"), "utf8");
  const standalone = readFileSync(join(root, "e2e", "standalone-server.mjs"), "utf8");
  assert.doesNotMatch(runE2e, /shell:\s*true/);
  assert.doesNotMatch(standalone, /shell:\s*true/);
  assert.match(runE2e, /shell:\s*false/);
  assert.match(standalone, /shell:\s*false/);
  assert.doesNotMatch(runE2e, /playwrightBin,\s*"install"/);
  assert.doesNotMatch(runE2e, /spawnSync\(\s*["']npx/);
});

test("E2E standalone harness uses process env and no production-module runtime downgrade", () => {
  const standalone = readFileSync(join(root, "e2e", "standalone-server.mjs"), "utf8");
  assert.doesNotMatch(standalone, /writeE2eNodeEnvLockWrapper/);
  assert.doesNotMatch(standalone, /\.e2e-server-/);
  assert.doesNotMatch(standalone, /ORDERPILOT_E2E_RUNTIME_NODE_ENV/);
  const playwright = readFileSync(join(root, "playwright.config.ts"), "utf8");
  assert.match(playwright, /NODE_ENV:\s*"test"/);
  assert.doesNotMatch(playwright, /ORDERPILOT_E2E_RUNTIME_NODE_ENV/);
  for (const port of [3101, 3102]) {
    const block = playwright.split(`standalone-server.mjs --port ${port}`)[1]?.split("command:")[0] ?? "";
    assert.doesNotMatch(block, /NODE_ENV:\s*"test"/);
    assert.match(block, /NODE_ENV:\s*"production"/);
  }
  const profile = readFileSync(join(root, "lib/bff/bff-deployment-profile.ts"), "utf8");
  assert.doesNotMatch(profile, /ORDERPILOT_E2E_RUNTIME_NODE_ENV/);
  assert.match(profile, /\["NODE", "ENV"\]\.join\("_"\)/);
});
