import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const CHILD_FLAG = "ORDERPILOT_OIDC_MAPPING_SOURCE_CHILD";

if (process.env[CHILD_FLAG] !== "1") {
  test("P1-C mapping source passes under server-only condition", () => {
    const result = spawnSync(process.execPath, ["--conditions=react-server", "--test", fileURLToPath(import.meta.url)], {
      cwd: process.cwd(),
      env: { ...process.env, [CHILD_FLAG]: "1" },
      encoding: "utf8"
    });
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  });
} else {
  const {
    ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV,
    createMemoizedProductionOidcIdentityMappingResolverLoader,
    createProductionOidcIdentityMappingResolver,
    readConfiguredOidcIdentityMappingSource
  } = await import("../lib/bff/bff-oidc-identity-mapping.ts");

  const ISSUER = "https://idp.example.test/tenant-a";
  const AUDIENCE = "operant-dashboard-client";
  const TENANT_ID = "11111111-1111-4111-8111-111111111111";
  const ACTOR_ID = "22222222-2222-4222-8222-222222222222";
  const CUSTOMER_ID = "33333333-3333-4333-8333-333333333333";
  const STAFF_ID = "44444444-4444-4444-8444-444444444444";

  function envFor(mappings) { return { [ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV]: JSON.stringify({ mappings }) }; }
  function tenantMapping(overrides = {}) {
    return {
      issuer: ISSUER,
      subject: "tenant-subject",
      audience: AUDIENCE,
      enabled: true,
      accessPlane: "TENANT_USER",
      tenantRef: TENANT_ID,
      actorRef: ACTOR_ID,
      bffPermissions: ["REVIEW_READ", "REVIEW_ACTION"],
      safeEmail: "operator@example.test",
      requireEmail: "operator@example.test",
      mappingVersion: "v1",
      ...overrides
    };
  }
  function principal(overrides = {}) {
    return { issuer: ISSUER, subject: "tenant-subject", audience: AUDIENCE, email: "operator@example.test", emailVerified: true, claims: {}, tokenExpiresAtEpochSec: 4_102_444_800, ...overrides };
  }
  function resolve(mappings) { return createProductionOidcIdentityMappingResolver(envFor(mappings), { nowEpochSec: () => 2_000 }); }

  test("valid tenant mapping resolves only configured authority", () => {
    const result = resolve([tenantMapping()])(principal({ claims: { name: "Profile" } }));
    assert.equal(result.status, "MAPPED");
    assert.equal(result.accessPlane, "TENANT_USER");
    assert.equal(result.tenantRef, TENANT_ID);
    assert.equal(result.actorRef, ACTOR_ID);
    assert.deepEqual(result.safeProjection.permissions, ["REVIEW_READ", "REVIEW_ACTION"]);
  });

  test("missing empty malformed duplicate and later malformed source fail closed without partial publication", () => {
    assert.equal(readConfiguredOidcIdentityMappingSource({}).error.code, "MAPPING_CONFIG_REQUIRED");
    assert.equal(readConfiguredOidcIdentityMappingSource({ [ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV]: "[]" }).error.code, "MAPPING_CONFIG_EMPTY");
    assert.equal(readConfiguredOidcIdentityMappingSource({ [ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV]: "{" }).error.code, "MAPPING_CONFIG_MALFORMED");
    assert.equal(readConfiguredOidcIdentityMappingSource(envFor([tenantMapping(), tenantMapping({ safeEmail: "second@example.test" })])).error.code, "MAPPING_CONFIG_INVALID");
    const invalidLater = envFor([tenantMapping(), tenantMapping({ subject: "other", bffPermissions: ["NOT_REGISTERED"] })]);
    assert.equal(readConfiguredOidcIdentityMappingSource(invalidLater).ok, false);
    assert.equal(createProductionOidcIdentityMappingResolver(invalidLater)(principal()).status, "DENIED_UNTRUSTED_ISSUER");
  });

  test("authority-like provider claims and approval_status are denied before mapping", () => {
    for (const claim of ["tenantId", "actor_id", "permissions", "roles", "approval_status", "riskLevel", "margin", "stock", "executionStatus"]) {
      assert.equal(resolve([tenantMapping()])(principal({ claims: { [claim]: "attacker" } })).status, "DENIED_UNTRUSTED_CLAIM", claim);
    }
  });

  test("non-tenant planes do not mint tenant-user authority for browser flow", () => {
    const external = tenantMapping({ subject: "customer-subject", accessPlane: "EXTERNAL_CUSTOMER", tenantRef: undefined, actorRef: undefined, bffPermissions: undefined, externalCustomerRef: CUSTOMER_ID, requireEmail: undefined });
    const staff = tenantMapping({ subject: "staff-subject", accessPlane: "OPERANT_SUPPORT", tenantRef: undefined, actorRef: undefined, bffPermissions: undefined, staffRef: STAFF_ID, requireEmail: undefined });
    assert.equal(readConfiguredOidcIdentityMappingSource(envFor([external, staff])).ok, true);
    assert.equal(resolve([external])(principal({ subject: "customer-subject" })).accessPlane, "EXTERNAL_CUSTOMER");
    assert.equal(resolve([staff])(principal({ subject: "staff-subject" })).accessPlane, "OPERANT_SUPPORT");
    const service = tenantMapping({ accessPlane: "SERVICE_ACCOUNT", tenantRef: undefined, actorRef: undefined, bffPermissions: undefined, serviceAccountRef: STAFF_ID });
    assert.equal(readConfiguredOidcIdentityMappingSource(envFor([service])).ok, false);
  });

  test("resolver snapshot is immutable memoized and no empty-source bypass exists", () => {
    const env = envFor([tenantMapping()]);
    const loader = createMemoizedProductionOidcIdentityMappingResolverLoader(env, { nowEpochSec: () => 2_000 });
    const first = loader();
    const second = loader();
    assert.equal(first, second);
    assert.equal(first(principal()).status, "MAPPED");
    env[ORDERPILOT_OIDC_IDENTITY_MAPPINGS_ENV] = "{";
    assert.equal(second(principal()).status, "MAPPED");
    assert.equal(readConfiguredOidcIdentityMappingSource({}, { allowEmptySourceForTesting: true }).ok, false);
  });
}
