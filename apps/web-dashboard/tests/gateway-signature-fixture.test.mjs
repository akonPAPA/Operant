import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import test from "node:test";
import {
  CONTENT_SHA256_HEADER,
  GATEWAY_PROTOCOL_MARKER,
  GATEWAY_SIGNATURE_VERSION,
  SIGNATURE_VERSION_HEADER,
  gatewayCanonicalStringV2,
  sha256Hex,
  signGatewayHeaders
} from "../lib/bff/bff-gateway-signer.ts";
import { decodeGatewaySharedSecret } from "../lib/bff/bff-gateway-key.ts";

const repoRoot = join(process.cwd(), "..", "..");
const fixturePath = join(repoRoot, "docs", "security", "gateway-signature-fixture.json");
const fixture = JSON.parse(readFileSync(fixturePath, "utf8"));

const authoritativeGatewayContractFiles = [
  join(repoRoot, "docs", "security", "TRUSTED_GATEWAY_HEADER_BOUNDARY.md"),
  join(repoRoot, "docs", "security", "gateway-header-strip-nginx-example.conf")
];

const requiredGatewayContractTerms = [
  GATEWAY_PROTOCOL_MARKER,
  "METHOD",
  "PATH",
  "RAW_QUERY",
  "CONTENT_TYPE",
  "BODY_SHA256_HEX",
  "tenantId",
  "actorId",
  "permissions",
  "timestamp",
  "nonce",
  SIGNATURE_VERSION_HEADER,
  CONTENT_SHA256_HEADER,
  "X-Tenant-Id",
  "X-OrderPilot-Actor-Id",
  "X-OrderPilot-Permissions",
  "X-OrderPilot-Gateway-Timestamp",
  "X-OrderPilot-Gateway-Nonce",
  "X-OrderPilot-Gateway-Signature"
];

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

test("authoritative gateway contract files define HMAC v2 and do not reintroduce the legacy canonical contract", () => {
  const expectedCanonical = fixture.canonicalStringJoinedWithNewline;
  assert.deepEqual(expectedCanonical.slice(0, 6), [
    GATEWAY_PROTOCOL_MARKER,
    fixture.method,
    fixture.path,
    fixture.rawQuery,
    fixture.contentType,
    fixture.bodySha256Hex
  ]);

  for (const filePath of authoritativeGatewayContractFiles) {
    const content = readFileSync(filePath, "utf8");
    assert.doesNotMatch(content, /METHOD\s*\\n\s*REQUEST_URI_PATH\s*\\n\s*tenantId\s*\\n\s*actorId\s*\\n\s*permissions\s*\\n\s*timestamp\s*\\n\s*nonce/i, filePath);
    assert.doesNotMatch(content, /does not include\s+query string,\s+request body,\s+or body hash/i, filePath);
    assert.doesNotMatch(content, /query\/body binding is a separate production-code security slice/i, filePath);
    assert.doesNotMatch(content, /HMAC v1|gateway v1|legacy canonical/i, filePath);
    for (const term of requiredGatewayContractTerms) {
      assert.match(content, new RegExp(term.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), `${filePath} missing ${term}`);
    }
  }
});
test("NGINX header-strip artifact does not claim complete body-bound mutation signing", () => {
  const docs = readFileSync(join(repoRoot, "docs", "security", "TRUSTED_GATEWAY_HEADER_BOUNDARY.md"), "utf8");
  const nginx = readFileSync(join(repoRoot, "docs", "security", "gateway-header-strip-nginx-example.conf"), "utf8");
  assert.match(nginx, /proxy_pass_request_body off/);
  assert.match(nginx, /cannot sign body-bearing\s+# mutations|cannot sign body-bearing mutations/i);
  assert.match(docs, /cannot independently compute SHA-256 over the exact forwarded body bytes/i);
  assert.match(docs, /Body-bearing requests must be signed by the existing body-aware BFF\/gateway path/i);
  assert.doesNotMatch(nginx, /complete HMAC v2 signer for body-bearing mutations|complete body-bound mutation signer\./i);
});
