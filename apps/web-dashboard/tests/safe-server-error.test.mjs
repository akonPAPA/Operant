import assert from "node:assert/strict";
import test from "node:test";
import {
  PUBLIC_SERVER_ERROR_MESSAGE,
  publicCodeForStatus,
  newCorrelationId,
  redactTechnicalDetail,
  toPublicServerError
} from "../lib/safe-server-error.ts";

// Hostile strings that must never appear in any returned/rendered error surface (F03).
const HOSTILE = [
  "redis://:sup3rSecretPassw0rd@cache.internal.prod:6379/0",
  "http://core-api.internal.svc.cluster.local:8080/api/v1/quotes",
  "at Object.<anonymous> (C:\\OrderPilot\\OrderPilot-Core\\apps\\web-dashboard\\lib\\bff\\bff-proxy.ts:401:17)",
  "/var/run/secrets/orderpilot/gateway.key",
  "ECONNREFUSED 10.42.7.19:6379",
  "org.postgresql.util.PSQLException: ERROR: relation \"tenant_quotes\" does not exist SELECT * FROM tenant_quotes WHERE tenant_id = 'x'",
  "X-OrderPilot-Gateway-Signature: 9f8e7d6c5b4a39281706f5e4d3c2b1a09f8e7d6c5b4a3928",
  "authorization=Bearer eyJhbGciOiToken.payload.signature",
  "cookie=op_session=Abc123_def-456Ghi789Jkl012Mno345Pqr678Stu"
];

test("F03: redactTechnicalDetail strips URLs, paths, IPs, hosts, tokens, and secret pairs", () => {
  for (const input of HOSTILE) {
    const redacted = redactTechnicalDetail(input);
    assert.doesNotMatch(redacted, /redis:\/\/|https?:\/\//i, `URL leaked: ${redacted}`);
    assert.doesNotMatch(redacted, /:\\OrderPilot|\/var\/run/, `path leaked: ${redacted}`);
    assert.doesNotMatch(redacted, /10\.42\.7\.19|6379/, `ip/port leaked: ${redacted}`);
    assert.doesNotMatch(redacted, /sup3rSecretPassw0rd|gateway\.key/, `secret leaked: ${redacted}`);
    assert.doesNotMatch(redacted, /Bearer eyJ|9f8e7d6c5b4a/, `token leaked: ${redacted}`);
  }
});

test("F03: toPublicServerError returns a bounded constant message and never the exception text", () => {
  for (const input of HOSTILE) {
    const publicError = toPublicServerError(new Error(input));
    const serialized = JSON.stringify(publicError);
    // The returned surface contains none of the hostile source fragments.
    for (const fragment of [
      "redis://",
      "core-api.internal",
      "OrderPilot-Core",
      "/var/run/secrets",
      "10.42.7.19",
      "PSQLException",
      "tenant_quotes",
      "Gateway-Signature",
      "Bearer eyJ",
      "op_session="
    ]) {
      assert.equal(serialized.includes(fragment), false, `"${fragment}" leaked into ${serialized}`);
    }
    // The message is the stable bounded constant for the code.
    assert.equal(publicError.message, PUBLIC_SERVER_ERROR_MESSAGE[publicError.code]);
    assert.match(publicError.correlationId, /^req_[a-z0-9]{12}$/);
  }
});

test("F03: publicCodeForStatus maps HTTP statuses to stable codes", () => {
  assert.equal(publicCodeForStatus(401), "AUTH_REQUIRED");
  assert.equal(publicCodeForStatus(403), "ACCESS_DENIED");
  assert.equal(publicCodeForStatus(404), "NOT_FOUND");
  assert.equal(publicCodeForStatus(409), "CONFLICT");
  assert.equal(publicCodeForStatus(400), "VALIDATION_FAILED");
  assert.equal(publicCodeForStatus(422), "VALIDATION_FAILED");
  assert.equal(publicCodeForStatus(429), "RATE_LIMITED");
  assert.equal(publicCodeForStatus(500), "TEMPORARILY_UNAVAILABLE");
  assert.equal(publicCodeForStatus(503), "TEMPORARILY_UNAVAILABLE");
  assert.equal(publicCodeForStatus(418), "REQUEST_FAILED");
});

test("F03: bounded public messages contain no technical markers", () => {
  for (const message of Object.values(PUBLIC_SERVER_ERROR_MESSAGE)) {
    assert.doesNotMatch(message, /https?:\/\/|redis|postgres|\/|\\|:\d{2,5}\b|Exception|SELECT /i);
    assert.ok(message.length <= 80, `message too long: ${message}`);
  }
});

test("F03: correlation ids are non-empty, prefixed, and vary", () => {
  const a = newCorrelationId();
  const b = newCorrelationId();
  assert.match(a, /^req_[a-z0-9]{12}$/);
  assert.notEqual(a, b);
});
