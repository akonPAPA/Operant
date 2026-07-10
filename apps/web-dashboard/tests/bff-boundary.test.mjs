import assert from "node:assert/strict";
import test from "node:test";
import { isBffProxyPathAllowed } from "../lib/bff/bff-allowlist.ts";
import { createSessionToken, parseSessionToken } from "../lib/bff/bff-session.ts";

test("bff allowlist permits operator v1 paths and blocks demo and webhooks", () => {
  assert.equal(isBffProxyPathAllowed(["api", "v1", "quotes"]), true);
  assert.equal(isBffProxyPathAllowed(["api", "v1", "demo", "rfq-handoff"]), false);
  assert.equal(isBffProxyPathAllowed(["api", "v1", "webhooks", "telegram"]), false);
  assert.equal(isBffProxyPathAllowed(["internal", "v1"]), false);
});

test("bff session token round-trip and expiry", () => {
  const secret = "x".repeat(32);
  const token = createSessionToken(
    {
      sessionId: "test-session-id",
      tenantId: "11111111-1111-4111-8111-111111111111",
      actorId: "22222222-2222-4222-8222-222222222222",
      permissions: ["REVIEW_READ"],
      expiresAtEpochSec: Math.floor(Date.now() / 1000) + 60
    },
    secret
  );
  const parsed = parseSessionToken(token, secret);
  assert.equal(parsed?.tenantId, "11111111-1111-4111-8111-111111111111");
  assert.equal(parseSessionToken(`${token}tampered`, secret), null);
});
