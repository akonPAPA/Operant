import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import {
  CONTENT_SHA256_HEADER,
  GATEWAY_SIGNATURE_VERSION,
  SIGNATURE_VERSION_HEADER,
  gatewayCanonicalStringV2,
  sha256Hex,
  signGatewayHeaders
} from "../lib/bff/bff-gateway-signer.ts";
import { decodeGatewaySharedSecret } from "../lib/bff/bff-gateway-key.ts";

const fixturePath = join(process.cwd(), "..", "..", "docs", "security", "gateway-signature-fixture.json");
const fixture = JSON.parse(readFileSync(fixturePath, "utf8"));

test("TypeScript v2 canonical string matches the cross-language fixture", () => {
  const canonical = gatewayCanonicalStringV2({
    method: fixture.method,
    path: fixture.path,
    rawQuery: fixture.rawQuery,
    contentType: fixture.contentType,
    bodySha256Hex: fixture.bodySha256Hex,
    tenantId: fixture.tenantId,
    actorId: fixture.actorId,
    permissions: fixture.permissionsHeaderValue,
    timestampEpoch: fixture.timestampEpochSeconds,
    nonce: fixture.nonce
  });
  assert.equal(canonical, fixture.canonicalStringJoinedWithNewline.join("\n"));
});

test("TypeScript signer reproduces the fixture HMAC accepted by the Java verifier", () => {
  const bodyBytes = Buffer.from(fixture.bodyUtf8, "utf8");
  assert.equal(sha256Hex(bodyBytes), fixture.bodySha256Hex);
  const signed = signGatewayHeaders({
    method: fixture.method,
    path: fixture.path,
    rawQuery: fixture.rawQuery,
    contentType: fixture.contentType,
    bodyBytes,
    tenantId: fixture.tenantId,
    actorId: fixture.actorId,
    permissions: fixture.orderedPermissions,
    sharedSecret: fixture.sharedSecretHexTestOnly,
    timestampEpochForTesting: fixture.timestampEpochSeconds,
    nonceForTesting: fixture.nonce
  });
  assert.equal(signed["X-OrderPilot-Gateway-Signature"], fixture.expectedHmacSha256Hex);
  assert.equal(signed[SIGNATURE_VERSION_HEADER], GATEWAY_SIGNATURE_VERSION);
  assert.equal(signed[CONTENT_SHA256_HEADER], fixture.bodySha256Hex);
  assert.equal(signed["X-Tenant-Id"], fixture.tenantId);
  assert.equal(signed["X-OrderPilot-Actor-Id"], fixture.actorId);
  assert.equal(signed["X-OrderPilot-Permissions"], fixture.permissionsHeaderValue);
  assert.equal(signed["X-OrderPilot-Gateway-Timestamp"], String(fixture.timestampEpochSeconds));
  assert.equal(signed["X-OrderPilot-Gateway-Nonce"], fixture.nonce);
});

test("gateway secret contract accepts only 64-hex and uses decoded key", () => {
  assert.equal(decodeGatewaySharedSecret("").ok, false);
  assert.equal(decodeGatewaySharedSecret("abc").ok, false);
  assert.equal(decodeGatewaySharedSecret("0".repeat(64)).ok, false);
  assert.equal(decodeGatewaySharedSecret("not-hex-secret-value-that-is-long-enough-but-wrong").ok, false);
  assert.equal(decodeGatewaySharedSecret(" " + fixture.sharedSecretHexTestOnly).ok, false);
  const ok = decodeGatewaySharedSecret(fixture.sharedSecretHexTestOnly);
  assert.equal(ok.ok, true);
  if (ok.ok) {
    assert.equal(ok.key.length, 32);
  }
});

test("signer errors never include the secret value", () => {
  try {
    signGatewayHeaders({
      method: "GET",
      path: "/api/v1/x",
      rawQuery: "",
      contentType: "",
      bodyBytes: new Uint8Array(0),
      tenantId: fixture.tenantId,
      actorId: fixture.actorId,
      permissions: ["REVIEW_READ"],
      sharedSecret: "plaintext-not-hex"
    });
    assert.fail("expected throw");
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    assert.doesNotMatch(message, /plaintext-not-hex/);
    assert.doesNotMatch(message, /a3f91c7e/);
  }
});

test("fixture contains no real secret material", () => {
  const raw = readFileSync(fixturePath, "utf8");
  assert.match(raw, /Test-only|test-only|TestOnly/i);
  assert.doesNotMatch(raw, /BEGIN PRIVATE KEY|AKIA|password=/);
});
