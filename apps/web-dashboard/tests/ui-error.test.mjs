import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import {
  UI_ERROR_MESSAGE,
  uiErrorForStatus,
  caughtUiError,
  caughtUiErrorMessage
} from "../lib/ui-error.ts";

const root = process.cwd();

test("F07: uiErrorForStatus maps HTTP statuses to stable codes + bounded messages", () => {
  assert.deepEqual(uiErrorForStatus(401), { code: "AUTH_REQUIRED", message: UI_ERROR_MESSAGE.AUTH_REQUIRED });
  assert.deepEqual(uiErrorForStatus(403), { code: "ACCESS_DENIED", message: UI_ERROR_MESSAGE.ACCESS_DENIED });
  assert.deepEqual(uiErrorForStatus(404), { code: "NOT_FOUND", message: UI_ERROR_MESSAGE.NOT_FOUND });
  assert.deepEqual(uiErrorForStatus(409), { code: "CONFLICT", message: UI_ERROR_MESSAGE.CONFLICT });
  assert.deepEqual(uiErrorForStatus(422), { code: "VALIDATION_FAILED", message: UI_ERROR_MESSAGE.VALIDATION_FAILED });
  assert.deepEqual(uiErrorForStatus(429), { code: "RATE_LIMITED", message: UI_ERROR_MESSAGE.RATE_LIMITED });
  assert.deepEqual(uiErrorForStatus(503), { code: "TEMPORARILY_UNAVAILABLE", message: UI_ERROR_MESSAGE.TEMPORARILY_UNAVAILABLE });
  assert.deepEqual(uiErrorForStatus(418), { code: "REQUEST_FAILED", message: UI_ERROR_MESSAGE.REQUEST_FAILED });
});

test("F07: caughtUiError never surfaces the raw exception (hostile strings scrubbed)", () => {
  const hostile = [
    new Error("redis://:pw@cache.prod:6379 failed at C:\\app\\bff-proxy.ts:401"),
    new Error("PSQLException: SELECT * FROM tenant_quotes"),
    "Bearer eyJhbGciOi.token.sig",
    { message: "http://core.internal:8080 leaked" }
  ];
  for (const error of hostile) {
    const mapped = caughtUiError(error);
    const serialized = JSON.stringify(mapped);
    for (const fragment of ["redis://", "cache.prod", "bff-proxy.ts", "PSQLException", "tenant_quotes", "Bearer eyJ", "core.internal"]) {
      assert.equal(serialized.includes(fragment), false, `"${fragment}" leaked into ${serialized}`);
    }
    assert.equal(mapped.message, "Core API is not reachable.");
    assert.equal(mapped.code, "TEMPORARILY_UNAVAILABLE");
    assert.equal(caughtUiErrorMessage(error), "Core API is not reachable.");
  }
});

test("F07: bounded UI messages contain no technical markers", () => {
  for (const message of Object.values(UI_ERROR_MESSAGE)) {
    assert.doesNotMatch(message, /https?:\/\/|redis|postgres|Exception|SELECT |\/|\\/i);
  }
});

// AST/source guard: no browser API client OR browser component may pass a raw exception through
// to UI-visible state. Bounded passthrough is only allowed via the shared mapper
// (boundedUiErrorMessage / caughtUiErrorMessage), which these patterns do not match.
test("F07: no browser API client or component surfaces raw exception text", () => {
  const RAW_PATTERNS = [
    /\b(?:error|err|ex|exception)\.message\b/,
    /\b(?:error|err|ex|exception)\.stack\b/,
    /\bString\(\s*(?:error|err|ex|exception)\s*\)/
  ];
  // The shared mappers are allowed to inspect the exception (they redact / gate on BoundedUiError
  // and never return raw text). bff-session-store returns a constant for its typed startup error
  // and is server-only.
  const EXEMPT = new Set(["safe-server-error.ts", "ui-error.ts"]);

  const libDir = join(root, "lib");
  const clients = readdirSync(libDir).filter(
    (f) => /-api\.ts$/.test(f) && !f.endsWith(".server.ts") && !EXEMPT.has(f)
  );
  assert.ok(clients.length >= 12, `expected the browser API clients, found ${clients.length}`);
  const componentsDir = join(root, "components");
  const components = readdirSync(componentsDir).filter((f) => /\.tsx?$/.test(f));
  assert.ok(components.length >= 10, `expected the browser components, found ${components.length}`);

  const targets = [
    ...clients.map((f) => join(libDir, f)),
    ...components.map((f) => join(componentsDir, f)),
    join(libDir, "operator-action-runtime.ts")
  ];
  for (const file of targets) {
    const source = readFileSync(file, "utf8");
    for (const pattern of RAW_PATTERNS) {
      assert.ok(!pattern.test(source), `${file} surfaces raw exception text (matched ${pattern})`);
    }
  }
});
