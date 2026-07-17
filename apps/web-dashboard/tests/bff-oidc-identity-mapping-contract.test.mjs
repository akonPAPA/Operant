import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const CHILD_FLAG = "ORDERPILOT_OIDC_MAPPING_CONTRACT_CHILD";

if (process.env[CHILD_FLAG] !== "1") {
  test("OIDC identity mapping contract passes under the server-only condition", () => {
    const result = spawnSync(
      process.execPath,
      ["--conditions=react-server", "--test", fileURLToPath(import.meta.url)],
      { cwd: process.cwd(), env: { ...process.env, [CHILD_FLAG]: "1" }, encoding: "utf8" }
    );
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  });
} else {
  const {
    ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV,
    createProductionOidcIdentityMappingResolver
  } = await import("../lib/bff/bff-oidc-identity-mapping.ts");

  const ISSUER = "https://idp.example.test/tenant-a";
  const AUDIENCE = "operant-dashboard-client";
  const TENANT = "11111111-1111-4111-8111-111111111111";
  const ACTOR = "22222222-2222-4222-8222-222222222222";
  const STAFF = "33333333-3333-4333-8333-333333333333";

  function tenantMapping(overrides = {}) {
    return {
      issuer: ISSUER,
      subject: "tenant-subject",
      audience: AUDIENCE,
      enabled: true,
      accessPlane: "TENANT_USER",
      tenantRef: TENANT,
      actorRef: ACTOR,
      bffPermissions: ["REVIEW_READ"],
      requireEmail: "operator@example.test",
      safeEmail: "operator@example.test",
      mappingVersion: "contract-v1",
      ...overrides
    };
  }

  function resolver(mappings) {
    return createProductionOidcIdentityMappingResolver({
      [ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV]: JSON.stringify({ mappings })
    });
  }

  function principal(overrides = {}) {
    return {
      issuer: ISSUER,
      subject: "tenant-subject",
      audience: AUDIENCE,
      email: "operator@example.test",
      emailVerified: true,
      claims: {},
      tokenExpiresAtEpochSec: 4_102_444_800,
      ...overrides
    };
  }

  test("tenant authority comes only from the configured server mapping", () => {
    const result = resolver([tenantMapping()])(principal({ claims: { name: "Operator" } }));
    assert.equal(result.status, "MAPPED");
    assert.equal(result.accessPlane, "TENANT_USER");
    assert.equal(result.tenantRef, TENANT);
    assert.equal(result.actorRef, ACTOR);
    assert.deepEqual(result.safeProjection.permissions, ["REVIEW_READ"]);
  });

  test("required email must be verified and must match", () => {
    assert.equal(
      resolver([tenantMapping()])(principal({ emailVerified: false })).status,
      "DENIED_UNVERIFIED_EMAIL"
    );
    assert.equal(
      resolver([tenantMapping()])(principal({ email: "other@example.test" })).status,
      "DENIED_EMAIL_MISMATCH"
    );
  });

  test("claim-supplied authority is denied before mapping", () => {
    for (const claims of [
      { tenantId: "attacker" },
      { permissions: ["REVIEW_READ"] },
      { approval_status: "APPROVED" },
      { roles: ["ADMIN"] }
    ]) {
      assert.equal(
        resolver([tenantMapping()])(principal({ claims })).status,
        "DENIED_UNTRUSTED_CLAIM"
      );
    }
  });

  test("support identity never becomes a tenant-user session projection", () => {
    const support = tenantMapping({
      subject: "support-subject",
      accessPlane: "OPERANT_SUPPORT",
      tenantRef: undefined,
      actorRef: undefined,
      bffPermissions: undefined,
      requireEmail: undefined,
      safeEmail: undefined,
      staffRef: STAFF
    });
    const result = resolver([support])(principal({ subject: "support-subject", email: undefined, emailVerified: undefined }));
    assert.equal(result.status, "MAPPED");
    assert.equal(result.accessPlane, "OPERANT_SUPPORT");
    assert.equal("tenantRef" in result, false);
    assert.equal("actorRef" in result, false);
  });
}
