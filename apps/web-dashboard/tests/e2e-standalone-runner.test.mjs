import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

test("E2E runner and standalone launcher avoid shell execution", () => {
  const runE2e = readFileSync(join(root, "e2e", "run-e2e.mjs"), "utf8");
  const standalone = readFileSync(join(root, "e2e", "standalone-server.mjs"), "utf8");
  assert.doesNotMatch(runE2e, /shell:\s*true/);
  assert.doesNotMatch(standalone, /shell:\s*true/);
  assert.match(runE2e, /shell:\s*false/);
  assert.match(standalone, /shell:\s*false/);
  assert.doesNotMatch(runE2e, /npx/);
});
