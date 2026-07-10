import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import {
  gatewayCanonicalString,
  signGatewayHeaders
} from "../lib/bff/bff-gateway-signer.ts";

const fixturePath = join(process.cwd(), "..", "..", "docs", "security", "gateway-signature-fixture.json");
const fixture = JSON.parse(readFileSync(fixturePath, "utf8"));

test("TypeScript canonical string matches the cross-language fixture", () => {
  const canonical = gatewayCanonicalString(
    fixture.method,
    fixture.requestUri,
    fixture.tenantId,
    fixture.actorId,
    fixture.permissionsHeaderValue,
    fixture.timestampEpochSeconds,
    fixture.nonce
  );
  assert.equal(canonical, fixture.canonicalStringJoinedWithNewline.join("\n"));
});

test("TypeScript signer reproduces the fixture HMAC accepted by the Java verifier", () => {
  const signed = signGatewayHeaders({
    method: fixture.method,
    requestUri: fixture.requestUri,
    tenantId: fixture.tenantId,
    actorId: fixture.actorId,
    permissions: fixture.orderedPermissions,
    sharedSecret: fixture.sharedSecretTestOnly,
    timestampEpochForTesting: fixture.timestampEpochSeconds,
    nonceForTesting: fixture.nonce
  });
  assert.equal(signed["X-OrderPilot-Gateway-Signature"], fixture.expectedHmacSha256Hex);
  assert.equal(signed["X-Tenant-Id"], fixture.tenantId);
  assert.equal(signed["X-OrderPilot-Actor-Id"], fixture.actorId);
  assert.equal(signed["X-OrderPilot-Permissions"], fixture.permissionsHeaderValue);
  assert.equal(signed["X-OrderPilot-Gateway-Timestamp"], String(fixture.timestampEpochSeconds));
  assert.equal(signed["X-OrderPilot-Gateway-Nonce"], fixture.nonce);
});

test("fixture contains no real secret material", () => {
  const raw = readFileSync(fixturePath, "utf8");
  assert.match(raw, /test-only/);
  assert.doesNotMatch(raw, /BEGIN PRIVATE KEY|AKIA|password=/);
});
