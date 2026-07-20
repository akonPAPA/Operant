import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";

const ROOT = process.cwd();

test("home entry uses server-owned safe workspace resolver", () => {
  const page = readFileSync(join(ROOT, "app/page.tsx"), "utf8");
  assert.match(page, /resolveSafeWorkspacePath/);
  assert.match(page, /SESSION_DENIED/);
  assert.doesNotMatch(page, /\blocalStorage\b|\bsessionStorage\b|\bsearchParams\b/);
});

test("workspace resolver prefers real work surfaces and never reads Host/client authority", () => {
  const source = readFileSync(join(ROOT, "lib/server/resolve-safe-workspace.server.ts"), "utf8");
  assert.match(source, /loadUiCapabilityProjection/);
  assert.match(source, /\/quote-review/);
  assert.match(source, /\/documents/);
  assert.match(source, /\/command-center/);
  assert.match(source, /server-only/);
  assert.doesNotMatch(source, /\blocalStorage\b|\bsessionStorage\b|X-Forwarded-Host|headers\(\)\.get\(["']host/i);
});
