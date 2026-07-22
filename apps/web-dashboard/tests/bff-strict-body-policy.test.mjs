/**
 * Contract tests for the reusable strict request-body allowlist (bff-strict-body-policy).
 * These lock the pure validator independent of the proxy wiring in the mutation matrix.
 */
import assert from "node:assert/strict";
import test from "node:test";
import { bodyMatchesStrictPolicy } from "../lib/bff/bff-strict-body-policy.ts";

const APPROVAL_POLICY = { allowedKeys: ["approvalRequestId", "reason", "comment"] };
const RFQ_POLICY = {
  allowedKeys: ["customerExternalRef", "requestedLocation", "requestedDiscountPercent", "requestedItems"],
  arrayItemKeys: { requestedItems: ["rawSkuOrAlias", "description", "quantity", "uom"] }
};

test("accepts an exact-allowlist object", () => {
  assert.equal(bodyMatchesStrictPolicy({ reason: "ok", comment: "c" }, APPROVAL_POLICY), true);
});

test("accepts an empty object (allowlist is a ceiling, not a floor)", () => {
  assert.equal(bodyMatchesStrictPolicy({}, APPROVAL_POLICY), true);
});

test("rejects an unknown top-level key", () => {
  assert.equal(bodyMatchesStrictPolicy({ reason: "ok", unexpected: 1 }, APPROVAL_POLICY), false);
});

for (const key of ["tenantId", "actorId", "approvedBy", "createdBy", "permission", "role", "status", "approvalStatus", "executionStatus"]) {
  test(`rejects authority/server-state key: ${key}`, () => {
    assert.equal(bodyMatchesStrictPolicy({ reason: "ok", [key]: "x" }, APPROVAL_POLICY), false);
  });
}

for (const raw of ['{"__proto__":{"polluted":true},"reason":"ok"}', '{"constructor":{"x":1},"reason":"ok"}', '{"prototype":{"x":1},"reason":"ok"}']) {
  test(`rejects prototype-pollution payload ${raw.slice(0, 20)}...`, () => {
    assert.equal(bodyMatchesStrictPolicy(JSON.parse(raw), APPROVAL_POLICY), false);
  });
}

test("rejects a non-object body (array)", () => {
  assert.equal(bodyMatchesStrictPolicy([{ reason: "ok" }], APPROVAL_POLICY), false);
});

test("rejects a non-object body (string / null)", () => {
  assert.equal(bodyMatchesStrictPolicy("reason", APPROVAL_POLICY), false);
  assert.equal(bodyMatchesStrictPolicy(null, APPROVAL_POLICY), false);
});

test("accepts a valid RFQ body with allowlisted array items", () => {
  assert.equal(
    bodyMatchesStrictPolicy(
      {
        customerExternalRef: "CUST-1",
        requestedLocation: "WH-1",
        requestedDiscountPercent: 0,
        requestedItems: [{ rawSkuOrAlias: "SKU-1", description: "d", quantity: 1, uom: "EA" }]
      },
      RFQ_POLICY
    ),
    true
  );
});

test("rejects an unknown key inside an array item", () => {
  assert.equal(
    bodyMatchesStrictPolicy(
      {
        customerExternalRef: "CUST-1",
        requestedItems: [{ rawSkuOrAlias: "SKU-1", quantity: 1, uom: "EA", injected: "x" }]
      },
      RFQ_POLICY
    ),
    false
  );
});

test("rejects prototype-pollution inside an array item", () => {
  assert.equal(
    bodyMatchesStrictPolicy(
      JSON.parse('{"requestedItems":[{"__proto__":{"p":1},"rawSkuOrAlias":"SKU-1"}]}'),
      RFQ_POLICY
    ),
    false
  );
});

test("rejects a non-array value for a declared array field", () => {
  assert.equal(bodyMatchesStrictPolicy({ requestedItems: "not-an-array" }, RFQ_POLICY), false);
});
